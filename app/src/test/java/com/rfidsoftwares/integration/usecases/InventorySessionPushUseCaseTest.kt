package com.rfidsoftwares.integration.usecases

import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.dao.InventorySessionDao
import com.rfidsoftwares.data.local.dao.SessionProductStateDao
import com.rfidsoftwares.data.local.dao.SyncOutboxDao
import com.rfidsoftwares.data.local.entities.InventorySessionEntity
import com.rfidsoftwares.data.local.entities.SessionProductStateEntity
import com.rfidsoftwares.integration.BackendAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class InventorySessionPushUseCaseTest {

    private lateinit var adapter: BackendAdapter
    private lateinit var db: RfidSessionDatabase
    private lateinit var sessionDao: InventorySessionDao
    private lateinit var stateDao: SessionProductStateDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var useCase: InventorySessionPushUseCase

    @Before
    fun setup() {
        adapter = mock()
        db = mock()
        sessionDao = mock()
        stateDao = mock()
        outboxDao = mock()

        whenever(db.inventorySessionDao()).thenReturn(sessionDao)
        whenever(db.sessionProductStateDao()).thenReturn(stateDao)
        whenever(db.syncOutboxDao()).thenReturn(outboxDao)

        useCase = InventorySessionPushUseCase(adapter)
    }

    @Test
    fun computePushData_isDeterministic_forSameSessionAndStates() {
        val session = finishedSession()
        val states = listOf(
            SessionProductStateEntity("s1", "custom_node", "p2", 4, 3, "mismatch"),
            SessionProductStateEntity("s1", "custom_node", "p1", 2, 2, "matched"),
        )
        whenever(sessionDao.getSession("s1")).thenReturn(session)
        whenever(stateDao.getStates("s1", "custom_node")).thenReturn(states)

        val first = useCase.computePushData("s1", db)
        val second = useCase.computePushData("s1", db)

        assertEquals(first.idempotencyKey, second.idempotencyKey)
        assertEquals(first.payload, second.payload)
        assertTrue(first.hasMismatch)
        assertEquals(1, first.mismatchCount)
    }

    @Test
    fun computePushData_changesKey_whenPayloadChanges() {
        val session = finishedSession()
        whenever(sessionDao.getSession("s1")).thenReturn(session)

        whenever(stateDao.getStates("s1", "custom_node")).thenReturn(
            listOf(SessionProductStateEntity("s1", "custom_node", "p1", 2, 2, "matched"))
        )
        val keyA = useCase.computePushData("s1", db).idempotencyKey

        whenever(stateDao.getStates("s1", "custom_node")).thenReturn(
            listOf(SessionProductStateEntity("s1", "custom_node", "p1", 2, 1, "mismatch"))
        )
        val keyB = useCase.computePushData("s1", db).idempotencyKey

        assertNotEquals(keyA, keyB)
    }

    @Test
    fun enqueueOutboxJob_onlyForFinishedSession_andRespectsInsertIgnore() {
        whenever(stateDao.getStates(any(), any())).thenReturn(
            listOf(SessionProductStateEntity("s1", "custom_node", "p1", 2, 1, "mismatch"))
        )

        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession())
        whenever(outboxDao.insertIgnore(any())).thenReturn(1L).thenReturn(0L)

        val createdFirst = useCase.enqueueOutboxJob("s1", db)
        val createdSecond = useCase.enqueueOutboxJob("s1", db)

        assertTrue(createdFirst)
        assertFalse(createdSecond)

        whenever(sessionDao.getSession("s2")).thenReturn(finishedSession().copy(sessionId = "s2", state = "incomplete"))
        val createdIncomplete = useCase.enqueueOutboxJob("s2", db)
        assertFalse(createdIncomplete)
    }

    private fun finishedSession(): InventorySessionEntity {
        val now = System.currentTimeMillis()
        return InventorySessionEntity(
            sessionId = "s1",
            providerConnectionId = "custom_node",
            operatorId = "admin",
            locationId = "default",
            startedAt = now - 10_000,
            finishedAt = now,
            state = "finished",
            catalogSnapshotMarker = "p:123|e:456",
            updatedAt = now,
        )
    }
}

