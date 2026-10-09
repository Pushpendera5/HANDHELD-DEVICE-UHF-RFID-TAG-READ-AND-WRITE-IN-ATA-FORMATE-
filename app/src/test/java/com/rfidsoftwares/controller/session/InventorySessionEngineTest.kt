package com.rfidsoftwares.controller.session

import android.content.Context
import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.dao.InventorySessionDao
import com.rfidsoftwares.data.local.dao.ProductDao
import com.rfidsoftwares.data.local.dao.ProductEpcDao
import com.rfidsoftwares.data.local.dao.SessionProductStateDao
import com.rfidsoftwares.data.local.dao.SessionScanDao
import com.rfidsoftwares.data.local.dao.UnknownEpcCacheDao
import com.rfidsoftwares.data.local.entities.ProductEntity
import com.rfidsoftwares.data.local.entities.ProductEpcEntity
import com.rfidsoftwares.data.local.entities.SessionProductStateEntity
import com.rfidsoftwares.rfid.ReaderDiagnosticsSummary
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.TagSource
import com.rfidsoftwares.rfid.UhfReaderGateway
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class InventorySessionEngineTest {

    private lateinit var db: RfidSessionDatabase
    private lateinit var gateway: UhfReaderGateway
    private lateinit var context: Context
    private lateinit var listener: InventorySessionEngine.Listener
    private lateinit var engine: InventorySessionEngine

    private lateinit var inventorySessionDao: InventorySessionDao
    private lateinit var productDao: ProductDao
    private lateinit var epcDao: ProductEpcDao
    private lateinit var productStateDao: SessionProductStateDao
    private lateinit var sessionScanDao: SessionScanDao
    private lateinit var unknownDao: UnknownEpcCacheDao

    @Before
    fun setup() {
        db = mock()
        gateway = mock()
        context = mock()
        listener = mock()

        inventorySessionDao = mock()
        productDao = mock()
        epcDao = mock()
        productStateDao = mock()
        sessionScanDao = mock()
        unknownDao = mock()

        whenever(db.inventorySessionDao()).thenReturn(inventorySessionDao)
        whenever(db.productDao()).thenReturn(productDao)
        whenever(db.productEpcDao()).thenReturn(epcDao)
        whenever(db.sessionProductStateDao()).thenReturn(productStateDao)
        whenever(db.sessionScanDao()).thenReturn(sessionScanDao)
        whenever(db.unknownEpcCacheDao()).thenReturn(unknownDao)

        whenever(gateway.readDiagnosticsSummary()).thenReturn(
            ReaderDiagnosticsSummary(
                sdkReady = true,
                readerOpen = false,
                powerDbm = null,
                regionOrFrequency = null,
                batteryNote = null,
                detailLine = "mock",
            ),
        )

        whenever(productDao.getProducts("custom_node")).thenReturn(
            listOf(
                ProductEntity("p1", "custom_node", null, "P1", null, "active", 100L, null, null, null),
                ProductEntity("p2", "custom_node", null, "P2", null, "active", 200L, null, null, null),
            )
        )
        whenever(epcDao.getProductEpcs("custom_node")).thenReturn(
            listOf(
                ProductEpcEntity("EPC1", "custom_node", "p1", "active", 1L, 100L),
                ProductEpcEntity("EPC2", "custom_node", "p2", "active", 1L, 200L),
            )
        )
        whenever(unknownDao.countUnknown("custom_node")).thenReturn(0)
        whenever(sessionScanDao.insertScans(any())).thenReturn(emptyList())
        whenever(sessionScanDao.getSeenEpcs(any(), any())).thenReturn(emptyList())
        whenever(productStateDao.getStates(any(), any())).thenReturn(
            listOf(
                SessionProductStateEntity("s", "custom_node", "p1", 1, 1, "pending"),
                SessionProductStateEntity("s", "custom_node", "p2", 1, 0, "pending"),
            )
        )

        engine = InventorySessionEngine(db = db, gateway = gateway, context = context)
    }

    @Test
    fun startNewSession_whenGatewayInitFails_marksNotRunningAndReportsError() {
        whenever(gateway.init(context)).thenReturn(false)

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )

        assertFalse(engine.isScanActive())
        verify(listener).onError("RFID reader init failed")
        verify(inventorySessionDao).insertSession(any())
        verify(productStateDao).insertProductStates(any())
        verify(inventorySessionDao).updateSessionState(
            sessionId = any(),
            state = eq("incomplete"),
            finishedAt = eq(null),
            updatedAt = any(),
        )
        verify(gateway, atLeast(1)).free()
    }

    @Test
    fun startNewSession_whenStartInventoryFails_marksNotRunningAndReportsError() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(false)

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )

        assertFalse(engine.isScanActive())
        verify(listener).onError("Failed to start inventory")
        verify(inventorySessionDao).insertSession(any())
        verify(inventorySessionDao).updateSessionState(
            sessionId = any(),
            state = eq("incomplete"),
            finishedAt = eq(null),
            updatedAt = any(),
        )
        verify(gateway, atLeast(1)).free()
    }

    @Test
    fun finishSession_updatesPerProductMatchedMismatch_andMarksSessionFinished() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(emptyList())

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )
        engine.finishSession(listener)

        verify(productStateDao, times(1)).updateProductFound(
            sessionId = any(),
            providerConnectionId = eq("custom_node"),
            productId = eq("p1"),
            foundCount = eq(0),
            status = eq("mismatch"),
        )
        verify(productStateDao, times(1)).updateProductFound(
            sessionId = any(),
            providerConnectionId = eq("custom_node"),
            productId = eq("p2"),
            foundCount = eq(0),
            status = eq("mismatch"),
        )
        verify(inventorySessionDao).updateSessionState(
            sessionId = any(),
            state = eq("finished"),
            finishedAt = any(),
            updatedAt = any(),
        )
        assertFalse(engine.isScanActive())
    }

    @Test
    fun stopIncomplete_marksSessionIncomplete_andClearsRunningState() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(emptyList())

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )
        assertTrue(engine.isScanActive())

        engine.stopIncomplete(listener)

        verify(inventorySessionDao).updateSessionState(
            sessionId = any(),
            state = eq("incomplete"),
            finishedAt = eq(null),
            updatedAt = any(),
        )
        assertFalse(engine.isScanActive())
    }

    @Test
    fun stopActiveAsIncompleteIfScanActive_noopWhenNotRunning() {
        engine.stopActiveAsIncompleteIfScanActive(listener)
        verify(inventorySessionDao, times(0)).updateSessionState(any(), any(), any(), any())
    }

    @Test
    fun resumeSession_whenInitFails_reportsErrorAndStops() {
        whenever(gateway.init(context)).thenReturn(false)
        whenever(sessionScanDao.getSeenEpcs("s1", "custom_node")).thenReturn(listOf("EPC1"))
        whenever(productStateDao.getStates("s1", "custom_node")).thenReturn(
            listOf(SessionProductStateEntity("s1", "custom_node", "p1", 1, 1, "pending"))
        )

        engine.resumeSession("s1", "custom_node", listener)

        assertFalse(engine.isScanActive())
        verify(listener).onError("RFID reader init failed")
        verify(inventorySessionDao).updateSessionState(
            sessionId = eq("s1"),
            state = eq("active"),
            finishedAt = eq(null),
            updatedAt = any(),
        )
        verify(inventorySessionDao).updateSessionState(
            sessionId = eq("s1"),
            state = eq("incomplete"),
            finishedAt = eq(null),
            updatedAt = any(),
        )
        verify(gateway, atLeast(1)).free()
    }

    @Test
    fun resumeSession_whenStartInventoryFails_reportsErrorAndStops() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(false)
        whenever(sessionScanDao.getSeenEpcs("s1", "custom_node")).thenReturn(emptyList())
        whenever(productStateDao.getStates("s1", "custom_node")).thenReturn(
            listOf(SessionProductStateEntity("s1", "custom_node", "p1", 1, 0, "pending"))
        )

        engine.resumeSession("s1", "custom_node", listener)

        assertFalse(engine.isScanActive())
        verify(listener).onError("Failed to start inventory")
        verify(inventorySessionDao).updateSessionState(
            sessionId = eq("s1"),
            state = eq("incomplete"),
            finishedAt = eq(null),
            updatedAt = any(),
        )
        verify(gateway, atLeast(1)).free()
    }

    @Test
    fun scanLoop_knownEpcDuplicate_updatesFoundCountOnlyOnce_withPersistentDedupe() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(
            listOf(
                TagEvent(epc = "EPC1", rssi = -45, source = TagSource.EPC, seenAt = 1000L),
                TagEvent(epc = "EPC1", rssi = -44, source = TagSource.EPC, seenAt = 1001L),
            ),
            emptyList()
        )
        whenever(sessionScanDao.insertScans(any())).thenReturn(listOf(1L, -1L))

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )

        // Wait until scan loop flushes at least one batch.
        verify(sessionScanDao, timeout(1500).times(1)).insertScans(any())
        engine.stopIncomplete(listener)

        verify(productStateDao, times(1)).updateProductFound(
            sessionId = any(),
            providerConnectionId = eq("custom_node"),
            productId = eq("p1"),
            foundCount = eq(1),
            status = eq("pending"),
        )
    }

    @Test
    fun scanLoop_unknownEpc_updatesUnknownCache_withoutAffectingProductFoundCounts() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(
            listOf(TagEvent(epc = "UNKNOWN1", rssi = -50, source = TagSource.EPC, seenAt = 2000L)),
            emptyList()
        )
        whenever(sessionScanDao.insertScans(any())).thenReturn(listOf(1L))

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )

        verify(sessionScanDao, timeout(1500).times(1)).insertScans(any())
        engine.stopIncomplete(listener)

        verify(unknownDao, times(1)).upsertAndEnforceCap(
            providerConnectionId = eq("custom_node"),
            epc = eq("UNKNOWN1"),
            now = any(),
            cap = eq(100),
        )
        // Unknown EPC should not increment known product found counts.
        verify(productStateDao, times(0)).updateProductFound(
            sessionId = any(),
            providerConnectionId = eq("custom_node"),
            productId = any(),
            foundCount = any(),
            status = any(),
        )
    }

    @Test
    fun resumeSession_seedsSeenSetFromPersistedScans_andPreventsDuplicateCountIncrement() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(sessionScanDao.getSeenEpcs("s1", "custom_node")).thenReturn(listOf("EPC1"))
        whenever(productStateDao.getStates("s1", "custom_node")).thenReturn(
            listOf(
                SessionProductStateEntity("s1", "custom_node", "p1", 1, 1, "pending"),
                SessionProductStateEntity("s1", "custom_node", "p2", 1, 0, "pending"),
            )
        )
        whenever(gateway.readBufferedTagEvents()).thenReturn(
            listOf(TagEvent(epc = "EPC1", rssi = -41, source = TagSource.EPC, seenAt = 3000L)),
            emptyList()
        )

        engine.resumeSession("s1", "custom_node", listener)

        // Event is already persisted as seen, so no insert attempt should happen for duplicate EPC1.
        verify(sessionScanDao, timeout(1500).times(0)).insertScans(any())
        verify(productStateDao, times(0)).updateProductFound(
            sessionId = eq("s1"),
            providerConnectionId = eq("custom_node"),
            productId = eq("p1"),
            foundCount = any(),
            status = any(),
        )
        engine.stopIncomplete(listener)
    }

    @Test
    fun stopIncomplete_stopsGatewayBeforeMarkingIncomplete() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(emptyList())

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )
        engine.stopIncomplete(listener)

        val inOrder = org.mockito.kotlin.inOrder(gateway, inventorySessionDao)
        inOrder.verify(gateway).stopInventory()
        inOrder.verify(inventorySessionDao).updateSessionState(
            sessionId = any(),
            state = eq("incomplete"),
            finishedAt = eq(null),
            updatedAt = any(),
        )
    }

    @Test
    fun startNewSession_invokesOnTabsUpdated_afterInventoryStarts() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(emptyList())

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )

        verify(listener, atLeast(1)).onTabsUpdated(any())
        engine.stopIncomplete(listener)
    }

    @Test
    fun resumeSession_invokesOnTabsUpdated_afterResume() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(emptyList())
        whenever(sessionScanDao.getSeenEpcs("s1", "custom_node")).thenReturn(emptyList())
        whenever(productStateDao.getStates("s1", "custom_node")).thenReturn(
            listOf(
                SessionProductStateEntity("s1", "custom_node", "p1", 1, 0, "pending"),
                SessionProductStateEntity("s1", "custom_node", "p2", 1, 0, "pending"),
            )
        )

        engine.resumeSession("s1", "custom_node", listener)

        verify(listener, atLeast(1)).onTabsUpdated(any())
        engine.stopIncomplete(listener)
    }

    @Test
    fun finishSession_invokesOnSessionFinished_andOnTabsUpdated() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(emptyList())

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )
        engine.finishSession(listener)

        verify(listener).onSessionFinished(any())
        verify(listener, atLeast(1)).onTabsUpdated(any())
    }

    @Test
    fun stopIncomplete_invokesOnTabsUpdated_afterIncompleteState() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(emptyList())

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )
        engine.stopIncomplete(listener)

        verify(listener).onSessionStopped(any(), eq(true))
        verify(listener, atLeast(1)).onTabsUpdated(any())
    }

    @Test
    fun scanLoop_twoUnknownSameEpcInOneBatch_updatesUnknownCacheTwice_evenWhenSecondInsertIgnored() {
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(
            listOf(
                TagEvent(epc = "UNKX", rssi = -50, source = TagSource.EPC, seenAt = 4000L),
                TagEvent(epc = "UNKX", rssi = -49, source = TagSource.EPC, seenAt = 4001L),
            ),
            emptyList()
        )
        whenever(sessionScanDao.insertScans(any())).thenReturn(listOf(1L, -1L))

        engine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener
        )

        verify(sessionScanDao, timeout(2500).times(1)).insertScans(any())
        verify(unknownDao, timeout(2500).times(2)).upsertAndEnforceCap(
            providerConnectionId = eq("custom_node"),
            epc = eq("UNKX"),
            now = any(),
            cap = eq(100),
        )
        engine.stopIncomplete(listener)
    }

    @Test
    fun finishSession_usesQuiescenceSleepUntilInsertingFlagClears() {
        var virtualNow = 0L
        val sleepCalls = AtomicInteger(0)
        val afterFirstSleep = CountDownLatch(1)
        whenever(gateway.init(context)).thenReturn(true)
        whenever(gateway.startInventory()).thenReturn(true)
        whenever(gateway.readBufferedTagEvents()).thenReturn(emptyList())

        val testEngine = InventorySessionEngine(
            db = db,
            gateway = gateway,
            context = context,
            quiescenceClock = { virtualNow },
            quiescenceSleep = { ms ->
                if (sleepCalls.getAndIncrement() == 0) {
                    afterFirstSleep.countDown()
                }
                virtualNow += ms
            },
        )

        testEngine.startNewSession(
            InventorySessionEngine.StartRequest("custom_node", "op", "loc"),
            listener,
        )
        insertingNowAtomic(testEngine).set(true)

        val worker = Thread { testEngine.finishSession(listener) }
        worker.start()
        assertTrue(afterFirstSleep.await(5, TimeUnit.SECONDS))
        insertingNowAtomic(testEngine).set(false)
        worker.join(10_000)

        verify(listener).onSessionFinished(any())
        assertTrue(sleepCalls.get() >= 1)
    }

    private fun insertingNowAtomic(engine: InventorySessionEngine): AtomicBoolean {
        val f = InventorySessionEngine::class.java.getDeclaredField("insertingNow")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(engine) as AtomicBoolean
    }
}

