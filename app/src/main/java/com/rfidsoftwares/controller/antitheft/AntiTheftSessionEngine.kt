
package com.rfidsoftwares.controller.antitheft

import android.content.Context
import com.rfidsoftwares.data.local.CatalogSeeder
import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.entities.ProductEpcEntity
import com.rfidsoftwares.integration.models.AntiTheftUpdatePayload
import com.rfidsoftwares.integration.models.TagUpdate
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.UhfReaderGateway
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class AntiTheftEvalUi {
    IDLE,
    SCANNING_PASS,
    SCANNING_WARNING,
    STOPPED_PASS,
    STOPPED_WARNING,
}

data class AntiTheftPresentationState(
    val evaluation: AntiTheftEvalUi,
    val billedSeenCount: Int,
    val activeSeenCount: Int,
    val unknownSeenCount: Int,
    val activeEver: Boolean,
    val alarmMuted: Boolean,
    val scanning: Boolean,
)

/**
 * Anti-theft scan + evaluation: billed vs active vs unknown, with restart-safe EPC dedupe.
 */
class AntiTheftSessionEngine(
    private val db: RfidSessionDatabase,
    private val gateway: UhfReaderGateway,
    private val context: Context,
) {

    interface Listener {
        fun onAntiTheftState(state: AntiTheftPresentationState)
        fun onStatus(text: String)
        fun onError(message: String)
        /** Invoked from the scan thread — persist off the UI thread. */
        fun onAntiTheftAudit(eventType: String, message: String, detail: String?)
    }

    private val running = AtomicBoolean(false)
    private var scanThread: Thread? = null

    private var expectedByEpc: Map<String, ProductEpcEntity> = emptyMap()
    private var providerConnectionId: String = ""

    private val seenEpcs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val billedEpcs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val activeEpcs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val unknownEpcs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var activeEver: Boolean = false

    @Volatile
    private var alarmMuted: Boolean = false

    fun isScanning(): Boolean = running.get()

    /**
     * Used by the UI to avoid enabling Final Pass when the expected EPC snapshot was not loaded.
     */
    fun hasSnapshotLoaded(): Boolean = expectedByEpc.isNotEmpty()

    /**
     * Reload expected EPC snapshot from Room (call after catalog resync).
     */
    fun reloadSnapshot(providerConnectionId: String) {
        CatalogSeeder.seedIfEmpty(db, providerConnectionId)
        val epcs = db.productEpcDao().getProductEpcs(providerConnectionId)
        expectedByEpc = epcs.associateBy { it.epc.trim().uppercase(Locale.US) }
        this.providerConnectionId = providerConnectionId
    }

    /**
     * Clears evaluation + dedupe state and starts the RFID inventory loop.
     */
    fun startScanning(listener: Listener) {
        internalStopScan(joinMs = 2500)

        seenEpcs.clear()
        billedEpcs.clear()
        activeEpcs.clear()
        unknownEpcs.clear()
        activeEver = false
        alarmMuted = false

        if (expectedByEpc.isEmpty()) {
            listener.onError("No EPC catalog loaded. Run catalog sync first.")
            pushState(listener)
            return
        }

        running.set(true)
        listener.onStatus("Anti-theft scan running…")
        pushState(listener)

        if (!gateway.init(context)) {
            running.set(false)
            listener.onError("RFID reader init failed")
            pushState(listener)
            return
        }
        if (!gateway.startInventory()) {
            running.set(false)
            listener.onError("Failed to start inventory")
            try {
                gateway.free()
            } catch (_: Exception) {
            }
            pushState(listener)
            return
        }

        scanThread = Thread {
            runScanLoop(listener)
        }.apply { start() }
    }

    /** Mutes alarm only; does not change evaluation or stop scanning. */
    fun stopSoundOnly(listener: Listener) {
        alarmMuted = true
        pushState(listener)
    }

    /** Stops scan + reader; clears alert mute flag for next active detection cycle. */
    fun rescanStopScan(listener: Listener) {
        alarmMuted = true
        internalStopScan(joinMs = 5000)
        pushState(listener)
    }

    fun stopOnLeave(listener: Listener) {
        alarmMuted = true
        internalStopScan(joinMs = 5000)
        pushState(listener)
    }

    /** Stops RFID inventory but keeps evaluation counts (for Final Pass after operator stops scanning). */
    fun stopScanKeepingResults(listener: Listener) {
        internalStopScan(joinMs = 5000)
        pushState(listener)
    }

    /**
     * After a fatal resync error: stop hardware and clear evaluation so the UI is not stuck on a stale pass/warning.
     */
    fun resetToIdleAfterFailure(listener: Listener) {
        alarmMuted = true
        internalStopScan(joinMs = 5000)
        seenEpcs.clear()
        billedEpcs.clear()
        activeEpcs.clear()
        unknownEpcs.clear()
        activeEver = false
        pushState(listener)
    }

    fun buildFinalizePayload(): AntiTheftUpdatePayload? {
        if (activeEver) return null
        if (providerConnectionId.isBlank()) return null
        val tags = ArrayList<TagUpdate>(billedEpcs.size)
        for (epc in billedEpcs) {
            val ent = expectedByEpc[epc] ?: continue
            tags.add(TagUpdate(epc = epc, billedState = ent.state, meta = null))
        }
        return AntiTheftUpdatePayload(
            providerConnectionId = providerConnectionId,
            tagsToUpdate = tags,
        )
    }

    private fun internalStopScan(joinMs: Long) {
        try {
            gateway.stopInventory()
        } catch (_: Exception) {
        }
        running.set(false)
        try {
            scanThread?.join(joinMs)
        } catch (_: Exception) {
        }
        scanThread = null
        try {
            gateway.free()
        } catch (_: Exception) {
        }
    }

    private fun runScanLoop(listener: Listener) {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val events: List<TagEvent> = gateway.readBufferedTagEvents()
            if (events.isEmpty()) {
                try {
                    Thread.sleep(40)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            val now = System.currentTimeMillis()
            for (event in events) {
                if (!running.get()) break
                handleEvent(event, now, listener)
            }
        }
        pushState(listener)
    }

    private fun handleEvent(event: TagEvent, now: Long, listener: Listener) {
        val epc = event.epc
        if (!seenEpcs.add(epc)) {
            val entity = expectedByEpc[epc]
            if (entity == null) {
                db.unknownEpcCacheDao().upsertAndEnforceCap(
                    providerConnectionId = providerConnectionId,
                    epc = epc,
                    now = now,
                    cap = 100,
                )
            }
            return
        }

        val entity = expectedByEpc[epc]
        when {
            entity == null -> {
                unknownEpcs.add(epc)
                db.unknownEpcCacheDao().upsertAndEnforceCap(
                    providerConnectionId = providerConnectionId,
                    epc = epc,
                    now = now,
                    cap = 100,
                )
                listener.onAntiTheftAudit(
                    "ANTITHEFT_UNKNOWN_EPC",
                    "Unknown EPC (not in catalog snapshot)",
                    epc,
                )
            }
            isBilledState(entity.state) -> {
                billedEpcs.add(epc)
            }
            else -> {
                activeEpcs.add(epc)
                if (!activeEver) {
                    activeEver = true
                    listener.onAntiTheftAudit(
                        "ANTITHEFT_ACTIVE_TAG",
                        "Active / theft-risk EPC detected",
                        epc,
                    )
                }
            }
        }
        pushState(listener)
    }

    private fun isBilledState(state: String): Boolean =
        state.trim().equals("billed", ignoreCase = true)

    private fun pushState(listener: Listener) {
        val scanning = running.get()
        val eval = when {
            scanning && activeEver -> AntiTheftEvalUi.SCANNING_WARNING
            scanning -> AntiTheftEvalUi.SCANNING_PASS
            !scanning && seenEpcs.isEmpty() -> AntiTheftEvalUi.IDLE
            activeEver -> AntiTheftEvalUi.STOPPED_WARNING
            else -> AntiTheftEvalUi.STOPPED_PASS
        }
        listener.onAntiTheftState(
            AntiTheftPresentationState(
                evaluation = eval,
                billedSeenCount = billedEpcs.size,
                activeSeenCount = activeEpcs.size,
                unknownSeenCount = unknownEpcs.size,
                activeEver = activeEver,
                alarmMuted = alarmMuted,
                scanning = scanning,
            ),
        )
    }
}
