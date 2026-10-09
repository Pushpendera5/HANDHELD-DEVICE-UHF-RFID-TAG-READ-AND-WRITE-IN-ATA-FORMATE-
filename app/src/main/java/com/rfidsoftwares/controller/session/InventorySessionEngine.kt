
package com.rfidsoftwares.controller.session

import android.content.Context
import com.rfidsoftwares.data.local.CatalogSeeder
import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.entities.InventorySessionEntity
import com.rfidsoftwares.data.local.entities.SessionProductStateEntity
import com.rfidsoftwares.data.local.entities.SessionScanEntity
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.TagSource
import com.rfidsoftwares.rfid.UhfReaderGateway
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayList

class InventorySessionEngine(
    private val db: RfidSessionDatabase,
    private val gateway: UhfReaderGateway,
    private val context: Context,
    /** Test-only: virtual clock for `waitForInsertingQuiescence` timeout math. */
    private val quiescenceClock: () -> Long = { System.currentTimeMillis() },
    /** Test-only: replace `Thread.sleep` so quiescence loops are fast/deterministic. */
    private val quiescenceSleep: (Long) -> Unit = { ms -> Thread.sleep(ms) },
) {

    data class SessionTabsState(
        val allProductsCount: Int,
        val foundProductsCount: Int,
        val notFoundProductsCount: Int,
        val unknownEpcCount: Int,
    )

    interface Listener {
        fun onStatus(text: String)
        fun onTabsUpdated(state: SessionTabsState)
        fun onSessionFinished(sessionId: String)
        fun onSessionStopped(sessionId: String, incomplete: Boolean)
        fun onError(message: String)
        fun onKnownEpcMatched(epc: String) {}
    }

    data class StartRequest(
        val providerConnectionId: String,
        val operatorId: String,
        val locationId: String,
    )

    private val running = AtomicBoolean(false)
    private var scanThread: Thread? = null

    private var activeSessionId: String? = null
    private var activeProviderConnectionId: String? = null

    private var expectedEpcToProductId: Map<String, String> = emptyMap()
    private var productStatesByProductId: MutableMap<String, SessionProductStateEntity> = mutableMapOf()
    private val seenEpcsInMemory: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val insertingNow = AtomicBoolean(false)

    fun isScanActive(): Boolean = running.get()

    fun stopActiveAsIncompleteIfScanActive(listener: Listener) {
        if (!running.get()) return
        stopIncomplete(listener)
    }

    fun startNewSession(request: StartRequest, listener: Listener) {
        stopExistingInternal()

        val providerConnectionId = request.providerConnectionId
        val now = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()

        CatalogSeeder.seedIfEmpty(db, providerConnectionId)

        val products = db.productDao().getProducts(providerConnectionId)
        val epcs = db.productEpcDao().getProductEpcs(providerConnectionId)
        val snapshotMarker = buildSnapshotMarker(products, epcs)

        val expectedMap = epcs.associate { epcEntity ->
            epcEntity.epc.trim().uppercase(java.util.Locale.US) to epcEntity.productId
        }
        expectedEpcToProductId = expectedMap

        val expectedCounts: Map<String, Int> = epcs
            .groupBy { it.productId }
            .mapValues { (_, list) -> list.map { it.epc }.distinct().size }

        val session = InventorySessionEntity(
            sessionId = sessionId,
            providerConnectionId = providerConnectionId,
            operatorId = request.operatorId,
            locationId = request.locationId,
            startedAt = now,
            finishedAt = null,
            state = "active",
            catalogSnapshotMarker = snapshotMarker,
            updatedAt = now,
        )

        db.inventorySessionDao().insertSession(session)

        val initialStates = products.map { product ->
            val expectedCount = expectedCounts[product.id] ?: 0
            SessionProductStateEntity(
                sessionId = sessionId,
                providerConnectionId = providerConnectionId,
                productId = product.id,
                expectedCount = expectedCount,
                foundCount = 0,
                status = "pending",
            )
        }

        db.sessionProductStateDao().insertProductStates(initialStates)
        productStatesByProductId = initialStates.associateBy { it.productId }.toMutableMap()

        activeSessionId = sessionId
        activeProviderConnectionId = providerConnectionId

        seenEpcsInMemory.clear()
        insertingNow.set(false)
        running.set(true)
        listener.onStatus("Starting inventory session...")

        if (!gateway.init(context)) {
            cleanupFailedStart(
                sessionId = sessionId,
                providerConnectionId = providerConnectionId,
                listener = listener,
                errorMessage = "RFID reader init failed",
            )
            return
        }

        if (!gateway.startInventory()) {
            cleanupFailedStart(
                sessionId = sessionId,
                providerConnectionId = providerConnectionId,
                listener = listener,
                errorMessage = "Failed to start inventory",
            )
            return
        }

        // Start scan loop.
        scanThread = Thread {
            runScanLoop(sessionId, providerConnectionId, listener)
        }.apply { start() }

        listener.onTabsUpdated(readTabsState(providerConnectionId))
    }

    fun resumeSession(sessionId: String, providerConnectionId: String, listener: Listener) {
        stopExistingInternal()

        CatalogSeeder.seedIfEmpty(db, providerConnectionId)

        val now = System.currentTimeMillis()

        // Load expected snapshot from catalog.
        val epcs = db.productEpcDao().getProductEpcs(providerConnectionId)
        expectedEpcToProductId = epcs.associate { epcEntity ->
            epcEntity.epc.trim().uppercase(java.util.Locale.US) to epcEntity.productId
        }

        // Load persisted product states.
        val persistedStates = db.sessionProductStateDao().getStates(sessionId, providerConnectionId)
        productStatesByProductId = persistedStates.associateBy { it.productId }.toMutableMap()

        // Seed in-memory dedupe from persisted scans so restart dedupe is immediate.
        val seen = db.sessionScanDao().getSeenEpcs(sessionId, providerConnectionId)
        seenEpcsInMemory.clear()
        seenEpcsInMemory.addAll(seen)
        insertingNow.set(false)

        db.inventorySessionDao().updateSessionState(
            sessionId = sessionId,
            state = "active",
            finishedAt = null,
            updatedAt = now
        )

        activeSessionId = sessionId
        activeProviderConnectionId = providerConnectionId

        running.set(true)
        listener.onStatus("Resuming inventory session...")

        if (!gateway.init(context)) {
            cleanupFailedStart(
                sessionId = sessionId,
                providerConnectionId = providerConnectionId,
                listener = listener,
                errorMessage = "RFID reader init failed",
            )
            return
        }
        if (!gateway.startInventory()) {
            cleanupFailedStart(
                sessionId = sessionId,
                providerConnectionId = providerConnectionId,
                listener = listener,
                errorMessage = "Failed to start inventory",
            )
            return
        }

        scanThread = Thread {
            runScanLoop(sessionId, providerConnectionId, listener)
        }.apply { start() }

        listener.onTabsUpdated(readTabsState(providerConnectionId))
    }

    fun stopIncomplete(listener: Listener) {
        val sessionId = activeSessionId ?: return
        val providerConnectionId = activeProviderConnectionId ?: return

        // Phase 2 boundary rule: stop inventory first, then cancel scan loop.
        try {
            gateway.stopInventory()
        } catch (_: Exception) {
        }
        running.set(false)

        // Wait for scan thread end so finish won't race.
        scanThread?.join(5000)

        val now = System.currentTimeMillis()
        db.inventorySessionDao().updateSessionState(
            sessionId = sessionId,
            state = "incomplete",
            finishedAt = null,
            updatedAt = now
        )

        try {
            gateway.free()
        } catch (_: Exception) {
        }

        activeSessionId = null
        activeProviderConnectionId = null
        seenEpcsInMemory.clear()
        listener.onSessionStopped(sessionId, incomplete = true)
        listener.onTabsUpdated(readTabsState(providerConnectionId))
    }

    fun finishSession(listener: Listener) {
        val sessionId = activeSessionId ?: return
        val providerConnectionId = activeProviderConnectionId ?: return

        try {
            gateway.stopInventory()
        } catch (_: Exception) {
        }
        running.set(false)

        scanThread?.join(5000)
        waitForInsertingQuiescence(timeoutMs = 2000)

        val now = System.currentTimeMillis()

        // Mark product states matched/mismatch.
        val updated = productStatesByProductId.values.map { state ->
            val status = if (state.foundCount == state.expectedCount) "matched" else "mismatch"
            state.copy(status = status)
        }
        // Persist by updating each product row.
        for (s in updated) {
            db.sessionProductStateDao().updateProductFound(
                sessionId = s.sessionId,
                providerConnectionId = s.providerConnectionId,
                productId = s.productId,
                foundCount = s.foundCount,
                status = s.status,
            )
        }

        db.inventorySessionDao().updateSessionState(
            sessionId = sessionId,
            state = "finished",
            finishedAt = now,
            updatedAt = now
        )

        try {
            gateway.free()
        } catch (_: Exception) {
        }

        activeSessionId = null
        activeProviderConnectionId = null
        seenEpcsInMemory.clear()

        listener.onSessionFinished(sessionId)
        listener.onTabsUpdated(readTabsState(providerConnectionId))
    }

    private fun stopExistingInternal() {
        // Best-effort stop previous run and make the previous session non-push eligible.
        val oldSessionId = activeSessionId
        val oldProviderConnectionId = activeProviderConnectionId

        try {
            // Stop reader first, then cancel scan loop.
            gateway.stopInventory()
        } catch (_: Exception) {
        }
        running.set(false)
        try {
            scanThread?.join(1000)
        } catch (_: Exception) {
        }

        // Clean up reader resources so a new start doesn't inherit SDK state.
        try {
            gateway.free()
        } catch (_: Exception) {
        }

        // If we had an unfinished session, mark it incomplete.
        if (oldSessionId != null && oldProviderConnectionId != null) {
            val now = System.currentTimeMillis()
            try {
                db.inventorySessionDao().updateSessionState(
                    sessionId = oldSessionId,
                    state = "incomplete",
                    finishedAt = null,
                    updatedAt = now
                )
            } catch (_: Exception) {
            }
        }

        scanThread = null
        activeSessionId = null
        activeProviderConnectionId = null
        expectedEpcToProductId = emptyMap()
        productStatesByProductId = mutableMapOf()
        seenEpcsInMemory.clear()
    }

    private fun cleanupFailedStart(
        sessionId: String,
        providerConnectionId: String,
        listener: Listener,
        errorMessage: String,
    ) {
        running.set(false)
        try {
            gateway.stopInventory()
        } catch (_: Exception) {
        }
        try {
            gateway.free()
        } catch (_: Exception) {
        }
        val now = System.currentTimeMillis()
        try {
            db.inventorySessionDao().updateSessionState(
                sessionId = sessionId,
                state = "incomplete",
                finishedAt = null,
                updatedAt = now,
            )
        } catch (_: Exception) {
        }
        activeSessionId = null
        activeProviderConnectionId = null
        scanThread = null
        expectedEpcToProductId = emptyMap()
        productStatesByProductId = mutableMapOf()
        seenEpcsInMemory.clear()
        insertingNow.set(false)
        listener.onError(errorMessage)
        listener.onTabsUpdated(readTabsState(providerConnectionId))
    }

    private fun runScanLoop(sessionId: String, providerConnectionId: String, listener: Listener) {
        // Scan loop continues until stop/finish flips running=false.
        val pendingEvents = ArrayList<TagEvent>()
        var lastFlushAt = System.currentTimeMillis()
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val events = gateway.readBufferedTagEvents()
            if (events.isEmpty()) {
                // Opportunistically flush small pending buffers so UI updates feel responsive.
                val now = System.currentTimeMillis()
                if (pendingEvents.isNotEmpty() && (now - lastFlushAt) > 120) {
                    flushPendingEvents(sessionId, providerConnectionId, now, listener, pendingEvents)
                    pendingEvents.clear()
                    lastFlushAt = now
                }
                try {
                    Thread.sleep(40)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            pendingEvents.addAll(events)
            val now = System.currentTimeMillis()
            if (pendingEvents.size >= 10 || (now - lastFlushAt) > 200) {
                flushPendingEvents(sessionId, providerConnectionId, now, listener, pendingEvents)
                pendingEvents.clear()
                lastFlushAt = now
            }
        }
    }

    private fun flushPendingEvents(
        sessionId: String,
        providerConnectionId: String,
        now: Long,
        listener: Listener,
        events: List<TagEvent>,
    ) {
        if (events.isEmpty()) return
        if (!running.get()) return

        insertingNow.set(true)
        try {
            val stagedScans = ArrayList<SessionScanEntity>(events.size)
            val stagedMeta = ArrayList<Pair<TagEvent, Boolean>>(events.size) // (event, known?)

            // First handle "already seen" in-memory unknown EPC updates.
            for (event in events) {
                val epc = event.epc
                val known = expectedEpcToProductId.containsKey(epc)
                val alreadySeenInSession = seenEpcsInMemory.contains(epc)

                if (alreadySeenInSession) {
                    if (!known) {
                        db.unknownEpcCacheDao().upsertAndEnforceCap(
                            providerConnectionId = providerConnectionId,
                            epc = epc,
                            now = now,
                            cap = 100,
                        )
                    }
                    continue
                }

                val productId = expectedEpcToProductId[epc]
                stagedScans.add(
                    SessionScanEntity(
                        sessionId = sessionId,
                        providerConnectionId = providerConnectionId,
                        epc = epc,
                        firstSeenAt = now,
                        source = event.source.name,
                        isKnown = known,
                        productId = productId,
                    )
                )
                stagedMeta.add(event to known)
            }

            if (stagedScans.isNotEmpty()) {
                val insertedIds = db.sessionScanDao().insertScans(stagedScans)
                for (i in insertedIds.indices) {
                    if (!running.get()) break
                    val insertedId = insertedIds[i]
                    val (event, known) = stagedMeta[i]
                    val epc = event.epc
                    val productId = expectedEpcToProductId[epc]

                    if (insertedId > 0L) {
                        seenEpcsInMemory.add(epc)
                    }

                    // Unknown cache must update even when SessionScan insert was ignored (duplicate).
                    if (!known) {
                        db.unknownEpcCacheDao().upsertAndEnforceCap(
                            providerConnectionId = providerConnectionId,
                            epc = epc,
                            now = now,
                            cap = 100,
                        )
                        continue
                    }

                    if (insertedId > 0L) {
                        // Only first-time inserts update foundCount.
                        val safeProductId = productId ?: continue
                        val state = productStatesByProductId[safeProductId] ?: continue
                        val newFound = state.foundCount + 1
                        val newStatus = "pending"
                        val updatedState = state.copy(foundCount = newFound, status = newStatus)
                        productStatesByProductId[safeProductId] = updatedState
                        db.sessionProductStateDao().updateProductFound(
                            sessionId = sessionId,
                            providerConnectionId = providerConnectionId,
                            productId = safeProductId,
                            foundCount = newFound,
                            status = newStatus,
                        )
                        listener.onKnownEpcMatched(epc)
                    }
                }
            }
        } finally {
            insertingNow.set(false)
        }

        // UI updates: do once per flush to reduce render congestion.
        listener.onTabsUpdated(readTabsState(providerConnectionId))
    }

    private fun waitForInsertingQuiescence(timeoutMs: Long) {
        val start = quiescenceClock()
        while (insertingNow.get() && (quiescenceClock() - start) < timeoutMs) {
            try {
                quiescenceSleep(10)
            } catch (_: InterruptedException) {
                break
            }
        }
    }
    private fun readTabsState(providerConnectionId: String): SessionTabsState {
        val productStates = productStatesByProductId.values
        val allProductsCount = productStates.size
        val foundProductsCount = productStates.count { it.foundCount > 0 }
        val notFoundProductsCount = productStates.count { it.foundCount < it.expectedCount }
        val unknownEpcCount = db.unknownEpcCacheDao().countUnknown(providerConnectionId)

        return SessionTabsState(
            allProductsCount = allProductsCount,
            foundProductsCount = foundProductsCount,
            notFoundProductsCount = notFoundProductsCount,
            unknownEpcCount = unknownEpcCount,
        )
    }

    private fun buildSnapshotMarker(
        products: List<com.rfidsoftwares.data.local.entities.ProductEntity>,
        epcs: List<com.rfidsoftwares.data.local.entities.ProductEpcEntity>,
    ): String {
        val maxProductUpdated = products.maxOfOrNull { it.updatedAt } ?: 0L
        val maxEpcUpdated = epcs.maxOfOrNull { it.updatedAt } ?: 0L
        return "p:$maxProductUpdated|e:$maxEpcUpdated"
    }
}

