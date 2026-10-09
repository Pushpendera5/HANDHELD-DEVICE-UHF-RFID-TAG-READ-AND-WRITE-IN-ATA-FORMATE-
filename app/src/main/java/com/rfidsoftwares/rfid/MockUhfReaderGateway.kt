
package com.rfidsoftwares.rfid

import android.content.Context
import com.rfidsoftwares.common.config.FeatureFlags
import com.rfidsoftwares.testing.fixtures.FixtureJsonLoader
import com.rfidsoftwares.rfid.TagSource
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.util.concurrent.atomic.AtomicBoolean
import com.rfidsoftwares.rfid.TagEvent

/**
 * Phase 2 mock skeleton.
 *
 * Full scripted fixture scripting is implemented in Phase 8.
 */
class MockUhfReaderGateway : UhfReaderGateway {
    private val active = AtomicBoolean(false)
    private var context: Context? = null

    private var inventorySequenceEvents: List<TagEvent> = emptyList()
    private var index: Int = 0

    /** When set, the next [startInventory] loads this asset path instead of the default inventory fixture. */
    @Volatile
    var fixturePathOverride: String? = null

    override fun init(context: Context): Boolean {
        this.context = context
        return true
    }

    override fun startInventory(): Boolean {
        if (!FeatureFlags.TEST_MODE_ENABLED || !FeatureFlags.UHF_TEST_MODE_ENABLED) {
            // Boundary rule: this gateway should only drive events in UHF test mode.
            inventorySequenceEvents = emptyList()
            index = 0
            active.set(true)
            return true
        }

        val fixturePath = fixturePathOverride ?: "test-fixtures/uhf/mock-inventory-sequence.json"
        fixturePathOverride = null
        inventorySequenceEvents = runCatching { loadInventoryEventsFromFixture(fixturePath) }.getOrElse {
            // If fixture parsing fails, keep inventory deterministic but empty (fail safe).
            emptyList()
        }
        index = 0
        active.set(true)
        return true
    }

    override fun stopInventory(): Boolean {
        active.set(false)
        return true
    }

    override fun free(): Boolean {
        active.set(false)
        inventorySequenceEvents = emptyList()
        index = 0
        context = null
        return true
    }

    override fun readBufferedTagEvents(): List<TagEvent> {
        if (!active.get()) return emptyList()
        if (index >= inventorySequenceEvents.size) return emptyList()

        // Simulate buffered reads by returning a small deterministic batch.
        val batchSize = 2
        val endExclusive = minOf(inventorySequenceEvents.size, index + batchSize)
        val batch = inventorySequenceEvents.subList(index, endExclusive)
        index = endExclusive
        return batch
    }

    override fun readDiagnosticsSummary(): ReaderDiagnosticsSummary {
        return ReaderDiagnosticsSummary(
            sdkReady = true,
            readerOpen = active.get(),
            powerDbm = 30,
            regionOrFrequency = "MOCK",
            batteryNote = "Mock gateway",
            detailLine = if (active.get()) "Mock reader · inventory active" else "Mock reader · idle",
        )
    }

    private fun loadInventoryEventsFromFixture(fixturePathFromAssetsRoot: String): List<TagEvent> {
        val obj: JsonObject = FixtureJsonLoader.loadJsonObject(fixturePathFromAssetsRoot)

        val eventsArr: JsonArray = obj.getAsJsonArray("events") ?: JsonArray()
        if (eventsArr.size() == 0) return emptyList()

        // Deterministic across runs: avoid wall clock base.
        val baseSeenAt = obj.get("baseSeenAtEpochMs")?.asLong ?: 0L
        val result = ArrayList<TagEvent>(eventsArr.size())
        for (i in 0 until eventsArr.size()) {
            val e = eventsArr[i].asJsonObject
            val epc = e.get("epc")?.asString?.trim()?.uppercase(java.util.Locale.US).orEmpty()
            if (epc.isBlank()) continue

            val rssi = e.get("rssi")?.let { it.asInt }
            val sourceStr = e.get("source")?.asString?.trim()?.uppercase(java.util.Locale.US) ?: "EPC"
            val source = runCatching { TagSource.valueOf(sourceStr) }.getOrDefault(TagSource.EPC)

            val offsetMs = e.get("seenAtOffsetMs")?.asLong ?: (i * 10L)
            val seenAt = baseSeenAt + offsetMs

            result.add(
                TagEvent(
                    epc = epc,
                    rssi = rssi,
                    source = source,
                    seenAt = seenAt,
                )
            )
        }
        return result
    }
}

