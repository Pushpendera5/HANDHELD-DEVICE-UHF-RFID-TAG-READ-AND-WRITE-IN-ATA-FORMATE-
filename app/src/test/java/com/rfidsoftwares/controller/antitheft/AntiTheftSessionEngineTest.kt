package com.rfidsoftwares.controller.antitheft

import android.content.Context
import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.dao.ProductDao
import com.rfidsoftwares.data.local.dao.ProductEpcDao
import com.rfidsoftwares.data.local.dao.UnknownEpcCacheDao
import com.rfidsoftwares.data.local.entities.ProductEpcEntity
import com.rfidsoftwares.data.local.entities.ProductEntity
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.TagSource
import com.rfidsoftwares.rfid.UhfReaderGateway
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AntiTheftSessionEngineTest {

    private lateinit var db: RfidSessionDatabase
    private lateinit var gateway: UhfReaderGateway
    private lateinit var context: Context
    private lateinit var epcDao: ProductEpcDao
    private lateinit var productDao: ProductDao
    private lateinit var unknownDao: UnknownEpcCacheDao

    @Before
    fun setup() {
        db = mock()
        gateway = mock()
        context = mock()
        epcDao = mock()
        productDao = mock()
        unknownDao = mock()
        whenever(db.productEpcDao()).thenReturn(epcDao)
        whenever(db.productDao()).thenReturn(productDao)
        whenever(db.unknownEpcCacheDao()).thenReturn(unknownDao)
        whenever(productDao.getProducts(any())).thenReturn(
            listOf(
                ProductEntity("p1", "custom_node", null, "P", null, "active", 1L, null, null, null),
            ),
        )
    }

    @Test
    fun warning_latches_when_active_epc_seen_even_after_later_billed_only() {
        val events = ArrayDeque(
            listOf(
                TagEvent(epc = "B1", rssi = null, source = TagSource.EPC, seenAt = 1L),
                TagEvent(epc = "A1", rssi = null, source = TagSource.EPC, seenAt = 2L),
                TagEvent(epc = "B2", rssi = null, source = TagSource.EPC, seenAt = 3L),
            ),
        )
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenAnswer {
            val e = events.pollFirst() ?: return@thenAnswer emptyList<TagEvent>()
            listOf(e)
        }
        whenever(gateway.stopInventory()).thenReturn(true)
        whenever(gateway.free()).thenReturn(true)

        whenever(epcDao.getProductEpcs("custom_node")).thenReturn(
            listOf(
                ProductEpcEntity("B1", "custom_node", "p1", "billed", 1L, 1L),
                ProductEpcEntity("B2", "custom_node", "p1", "billed", 1L, 1L),
                ProductEpcEntity("A1", "custom_node", "p2", "active", 1L, 1L),
            ),
        )

        val lastState = AtomicReference<AntiTheftPresentationState?>()
        val activeLatch = CountDownLatch(1)
        val listener = object : AntiTheftSessionEngine.Listener {
            override fun onAntiTheftState(state: AntiTheftPresentationState) {
                lastState.set(state)
                if (state.activeEver) activeLatch.countDown()
            }

            override fun onStatus(text: String) {}
            override fun onError(message: String) {}
            override fun onAntiTheftAudit(eventType: String, message: String, detail: String?) {}
        }

        val engine = AntiTheftSessionEngine(db, gateway, context)
        engine.reloadSnapshot("custom_node")
        engine.startScanning(listener)
        assertTrue(activeLatch.await(3, TimeUnit.SECONDS))
        engine.stopScanKeepingResults(listener)
        Thread.sleep(50)
        val s = lastState.get()!!
        assertTrue(s.activeEver)
        assertTrue(s.activeSeenCount >= 1)
        assertTrue(engine.buildFinalizePayload() == null)
    }

    @Test
    fun pass_allows_finalize_when_only_billed_and_unknown() {
        val events = ArrayDeque(
            listOf(
                TagEvent(epc = "B1", rssi = null, source = TagSource.EPC, seenAt = 1L),
                TagEvent(epc = "ZZZ", rssi = null, source = TagSource.EPC, seenAt = 2L),
            ),
        )
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenAnswer {
            val e = events.pollFirst() ?: return@thenAnswer emptyList<TagEvent>()
            listOf(e)
        }
        whenever(gateway.stopInventory()).thenReturn(true)
        whenever(gateway.free()).thenReturn(true)

        whenever(epcDao.getProductEpcs("custom_node")).thenReturn(
            listOf(
                ProductEpcEntity("B1", "custom_node", "p1", "billed", 1L, 1L),
            ),
        )

        val engine = AntiTheftSessionEngine(db, gateway, context)
        engine.reloadSnapshot("custom_node")
        val listener = object : AntiTheftSessionEngine.Listener {
            override fun onAntiTheftState(state: AntiTheftPresentationState) {}
            override fun onStatus(text: String) {}
            override fun onError(message: String) {}
            override fun onAntiTheftAudit(eventType: String, message: String, detail: String?) {}
        }
        engine.startScanning(listener)
        Thread.sleep(250)
        engine.stopScanKeepingResults(listener)
        val payload = engine.buildFinalizePayload()
        assertFalse(payload!!.tagsToUpdate.isEmpty())
        assertTrue(payload.tagsToUpdate.any { it.epc == "B1" })
    }
}
