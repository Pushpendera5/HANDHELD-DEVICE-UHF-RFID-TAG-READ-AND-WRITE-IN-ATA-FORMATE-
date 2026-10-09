
package com.rfidsoftwares.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.rfidsoftwares.data.local.RfidSessionDatabase
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Phase 2 local persistence for "missing EPCs" workflow.
 *
 * For now this is local-only (JSON persisted to app `filesDir`).
 * Later we can add server sync without changing the UI flow.
 */
class MissingItemsLocalStore private constructor(
    private val context: Context,
) {
    private val gson = Gson()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private val file: File = File(context.filesDir, MISSING_ITEMS_FILE_NAME)

    fun addMissingEpcAsync(
        providerConnectionId: String,
        sessionId: String,
        productId: String,
        epc: String,
    ) {
        io.execute {
            addMissingEpcBlocking(
                providerConnectionId = providerConnectionId,
                sessionId = sessionId,
                productId = productId,
                epc = epc,
            )
        }
    }

    fun removeMissingProductAsync(
        providerConnectionId: String,
        productId: String,
    ) {
        io.execute {
            removeMissingProductBlocking(
                providerConnectionId = providerConnectionId,
                productId = productId,
            )
        }
    }

    fun removeMissingEpcAsync(
        providerConnectionId: String,
        productId: String,
        epc: String,
    ) {
        io.execute {
            removeMissingEpcBlocking(
                providerConnectionId = providerConnectionId,
                productId = productId,
                epc = epc,
            )
        }
    }

    fun removeMissingProductBlocking(
        providerConnectionId: String,
        productId: String,
    ) {
        synchronized(lock) {
            val state = readStateLocked()
            val providerState = state.providers[providerConnectionId] ?: return
            providerState.products.remove(productId)
            // Keep file small by removing empty provider buckets.
            if (providerState.products.isEmpty()) state.providers.remove(providerConnectionId)
            writeStateLocked(state)
        }
    }

    fun removeMissingEpcBlocking(
        providerConnectionId: String,
        productId: String,
        epc: String,
    ) {
        val epcNorm = normalizeEpc(epc)
        if (epcNorm.isBlank()) return
        synchronized(lock) {
            val state = readStateLocked()
            val providerState = state.providers[providerConnectionId] ?: return
            val productState = providerState.products[productId] ?: return
            productState.epcs.remove(epcNorm)
            if (productState.epcs.isEmpty()) providerState.products.remove(productId)
            if (providerState.products.isEmpty()) state.providers.remove(providerConnectionId)
            writeStateLocked(state)
        }
    }

    fun mergeMissingFromSessionBlocking(
        providerConnectionId: String,
        sessionId: String,
        db: RfidSessionDatabase,
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val state = readStateLocked()

            // Missing is defined as: expected EPCs (catalog for session products) minus
            // found EPCs (known scans for session products).
            val sessionStates = db.sessionProductStateDao().getStates(sessionId, providerConnectionId)
            if (sessionStates.isEmpty()) return

            val sessionProductIds = sessionStates
                .map { it.productId }
                .distinct()

            val expectedEpcsByProductId = db.productEpcDao()
                .getProductEpcs(providerConnectionId)
                .asSequence()
                .filter { it.productId in sessionProductIds }
                .groupBy { it.productId }
                .mapValues { (_, list) ->
                    list.map { normalizeEpc(it.epc) }.toSet()
                }

            val foundByProductId = db.sessionScanDao().getScans(sessionId, providerConnectionId)
                .asSequence()
                .filter { it.isKnown && !it.productId.isNullOrBlank() }
                .groupBy { it.productId!! }
                .mapValues { (_, list) ->
                    list.map { normalizeEpc(it.epc) }.toSet()
                }

            for (s in sessionStates) {
                if (s.expectedCount <= 0) continue
                val expectedSet = expectedEpcsByProductId[s.productId] ?: emptySet()
                if (expectedSet.isEmpty()) continue

                val foundSet = foundByProductId[s.productId] ?: emptySet()
                val missingEpcs = expectedSet - foundSet
                if (missingEpcs.isEmpty()) continue

                for (epcNorm in missingEpcs) {
                    upsertMissingLocked(
                        state = state,
                        providerConnectionId = providerConnectionId,
                        sessionId = sessionId,
                        productId = s.productId,
                        epcNorm = epcNorm,
                        now = now,
                    )
                }
            }

            writeStateLocked(state)
        }
    }

    fun loadMissingProductsForProvider(
        providerConnectionId: String,
    ): List<MissingProductWithEpcs> {
        synchronized(lock) {
            val state = readStateLocked()
            val providerState = state.providers[providerConnectionId] ?: return emptyList()
            return providerState.products.values.map { p ->
                MissingProductWithEpcs(
                    productId = p.productId,
                    epcs = p.epcs.entries.map { e ->
                        MissingEpcState(
                            epc = e.key,
                            firstDetectedAt = e.value.firstDetectedAt,
                            lastDetectedAt = e.value.lastDetectedAt,
                            lastSessionId = e.value.lastSessionId,
                        )
                    }.sortedByDescending { it.lastDetectedAt },
                )
            }.sortedBy { it.productId }
        }
    }

    private fun addMissingEpcBlocking(
        providerConnectionId: String,
        sessionId: String,
        productId: String,
        epc: String,
    ) {
        val epcNorm = normalizeEpc(epc)
        if (epcNorm.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val state = readStateLocked()
            upsertMissingLocked(
                state = state,
                providerConnectionId = providerConnectionId,
                sessionId = sessionId,
                productId = productId,
                epcNorm = epcNorm,
                now = now,
            )
            writeStateLocked(state)
        }
    }

    private fun upsertMissingLocked(
        state: MissingItemsStateJson,
        providerConnectionId: String,
        sessionId: String,
        productId: String,
        epcNorm: String,
        now: Long,
    ) {
        val providerState = state.providers.getOrPut(providerConnectionId) { ProviderMissingJson() }
        val productState = providerState.products.getOrPut(productId) { MissingProductJson(productId = productId) }
        val existing = productState.epcs[epcNorm]
        productState.epcs[epcNorm] = if (existing == null) {
            MissingEpcJson(
                firstDetectedAt = now,
                lastDetectedAt = now,
                lastSessionId = sessionId,
            )
        } else {
            existing.copy(
                lastDetectedAt = now,
                lastSessionId = sessionId,
            )
        }
    }

    private fun readStateLocked(): MissingItemsStateJson {
        if (!file.exists()) return MissingItemsStateJson()
        val text = runCatching { file.readText() }.getOrDefault("")
        if (text.isBlank()) return MissingItemsStateJson()
        return gson.fromJson(text, MissingItemsStateJson::class.java) ?: MissingItemsStateJson()
    }

    private fun writeStateLocked(state: MissingItemsStateJson) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(state))
    }

    private fun normalizeEpc(epc: String): String =
        epc.trim().uppercase(Locale.US)

    companion object {
        private const val MISSING_ITEMS_FILE_NAME = "missing_items_v1.json"

        @Volatile
        private var instance: MissingItemsLocalStore? = null

        fun getInstance(context: Context): MissingItemsLocalStore {
            // Safe enough because app `filesDir` is stable; if context changes, worst case is a no-op.
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: MissingItemsLocalStore(context.applicationContext).also { instance = it }
            }
        }
    }
}

data class MissingProductWithEpcs(
    val productId: String,
    val epcs: List<MissingEpcState>,
)

data class MissingEpcState(
    val epc: String,
    val firstDetectedAt: Long,
    val lastDetectedAt: Long,
    val lastSessionId: String?,
)

private data class MissingItemsStateJson(
    @SerializedName("providers")
    val providers: MutableMap<String, ProviderMissingJson> = mutableMapOf(),
)

private data class ProviderMissingJson(
    @SerializedName("products")
    val products: MutableMap<String, MissingProductJson> = mutableMapOf(),
)

private data class MissingProductJson(
    @SerializedName("productId")
    val productId: String,
    @SerializedName("epcs")
    val epcs: MutableMap<String, MissingEpcJson> = mutableMapOf(),
)

private data class MissingEpcJson(
    @SerializedName("firstDetectedAt")
    val firstDetectedAt: Long,
    @SerializedName("lastDetectedAt")
    val lastDetectedAt: Long,
    @SerializedName("lastSessionId")
    val lastSessionId: String?,
)

