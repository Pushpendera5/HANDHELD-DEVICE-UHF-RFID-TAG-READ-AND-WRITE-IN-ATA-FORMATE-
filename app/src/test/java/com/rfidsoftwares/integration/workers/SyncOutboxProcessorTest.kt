package com.rfidsoftwares.integration.workers

import com.google.gson.Gson
import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.dao.AuditLogDao
import com.rfidsoftwares.data.local.dao.InventorySessionDao
import com.rfidsoftwares.data.local.dao.IssueDao
import com.rfidsoftwares.data.local.dao.SyncOutboxDao
import com.rfidsoftwares.data.local.entities.InventorySessionEntity
import com.rfidsoftwares.data.local.entities.IssueEntity
import com.rfidsoftwares.data.local.entities.SyncOutboxEntity
import com.rfidsoftwares.integration.BackendAdapter
import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.models.InventoryPushPayload
import com.rfidsoftwares.integration.models.ProductStatePush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SyncOutboxProcessorTest {

    private lateinit var db: RfidSessionDatabase
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var sessionDao: InventorySessionDao
    private lateinit var adapter: BackendAdapter
    private val gson = Gson()

    @Before
    fun setup() {
        db = mock()
        outboxDao = mock()
        sessionDao = mock()
        adapter = mock()

        whenever(db.syncOutboxDao()).thenReturn(outboxDao)
        whenever(db.inventorySessionDao()).thenReturn(sessionDao)
        whenever(outboxDao.getRunnableJobs(any(), any())).thenReturn(emptyList())
    }

    @Test
    fun processOnce_setsSucceeded_onSuccessfulPush() {
        val job = pendingJob()
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(listOf(job))
        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession("s1"))

        val shouldRetry = SyncOutboxProcessor(
            db = db,
            adapterResolver = { adapter },
            nowProvider = { 123L },
            corrIdProvider = { "corr-1" },
        ).processOnce()

        assertFalse(shouldRetry)
        verify(adapter).pushInventorySession(
            providerConnectionId = eq("custom_node"),
            payload = any(),
            idempotencyKey = eq("idem-1"),
            correlationId = eq("corr-1"),
        )
        verify(outboxDao).updateJobState(
            jobId = eq("job-1"),
            state = eq("succeeded"),
            retryCount = eq(1),
            corrId = eq("corr-1"),
            error = eq(null),
            now = eq(123L),
        )
    }

    @Test
    fun processOnce_setsRetrying_onTransientNetworkFailure() {
        val job = pendingJob()
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(listOf(job))
        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession("s1"))
        val shouldRetry = SyncOutboxProcessor(
            db = db,
            adapterResolver = { throw AdapterError.NetworkError("timeout") },
            nowProvider = { 200L },
            corrIdProvider = { "corr-2" },
        ).processOnce()

        assertTrue(shouldRetry)
        verify(outboxDao).updateJobState(
            jobId = eq("job-1"),
            state = eq("retrying"),
            retryCount = eq(1),
            corrId = eq("corr-2"),
            error = eq("timeout (Correlation-Id=corr-2)"),
            now = eq(200L),
        )
    }

    @Test
    fun processOnce_setsConflicted_onConflictError() {
        val job = pendingJob()
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(listOf(job))
        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession("s1"))
        val shouldRetry = SyncOutboxProcessor(
            db = db,
            adapterResolver = { throw AdapterError.ConflictError("marker drift") },
            nowProvider = { 300L },
            corrIdProvider = { "corr-3" },
        ).processOnce()

        assertFalse(shouldRetry)
        verify(outboxDao).updateJobState(
            jobId = eq("job-1"),
            state = eq("conflicted"),
            retryCount = eq(1),
            corrId = eq("corr-3"),
            error = eq("marker drift (Correlation-Id=corr-3)"),
            now = eq(300L),
        )
    }

    @Test
    fun processOnce_conflict_logsIssueWithResponseCorrelationId_whenPresent() {
        val issueDao = mock<IssueDao>()
        val auditDao = mock<AuditLogDao>()
        whenever(db.issueDao()).thenReturn(issueDao)
        whenever(db.auditLogDao()).thenReturn(auditDao)
        whenever(auditDao.deleteOlderThan(any())).thenReturn(0)
        whenever(auditDao.count()).thenReturn(0)
        whenever(issueDao.deleteInactiveOlderThan(any())).thenReturn(0)
        whenever(issueDao.count()).thenReturn(0)

        val job = pendingJob()
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(listOf(job))
        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession("s1"))

        SyncOutboxProcessor(
            db = db,
            adapterResolver = {
                throw AdapterError.ConflictError("drift", responseCorrelationId = "http-trace-77")
            },
            nowProvider = { 301L },
            corrIdProvider = { "worker-corr" },
        ).processOnce()

        val cap = argumentCaptor<IssueEntity>()
        verify(issueDao).insert(cap.capture())
        assertEquals("http-trace-77", cap.firstValue.correlationId)
    }

    @Test
    fun processOnce_callsRecoverStuckRunningJobs_beforeFetchingRunnableJobs() {
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(emptyList())

        SyncOutboxProcessor(
            db = db,
            adapterResolver = { adapter },
            nowProvider = { 777L },
            corrIdProvider = { "corr-r" },
        ).processOnce()

        val order = inOrder(outboxDao)
        order.verify(outboxDao).recoverStuckRunningJobs(eq(777L))
        order.verify(outboxDao).getRunnableJobs(eq("custom_node"), eq(20))
    }

    @Test
    fun processOnce_marksFailedPermanent_whenRetryCountReachesMaxRetries() {
        val job = pendingJob().copy(retryCount = 4)
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(listOf(job))
        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession("s1"))

        val shouldRetry = SyncOutboxProcessor(
            db = db,
            adapterResolver = { throw AdapterError.NetworkError("still down") },
            nowProvider = { 500L },
            corrIdProvider = { "corr-max" },
        ).processOnce()

        assertFalse(shouldRetry)
        verify(outboxDao).updateJobState(
            jobId = eq("job-1"),
            state = eq("failed_permanent"),
            retryCount = eq(5),
            corrId = eq("corr-max"),
            error = eq("still down (Correlation-Id=corr-max)"),
            now = eq(500L),
        )
    }

    @Test
    fun processOnce_setsRetrying_onTransientServerError() {
        val job = pendingJob()
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(listOf(job))
        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession("s1"))

        val shouldRetry = SyncOutboxProcessor(
            db = db,
            adapterResolver = { throw AdapterError.ServerError("502") },
            nowProvider = { 600L },
            corrIdProvider = { "corr-srv" },
        ).processOnce()

        assertTrue(shouldRetry)
        verify(outboxDao).updateJobState(
            jobId = eq("job-1"),
            state = eq("retrying"),
            retryCount = eq(1),
            corrId = eq("corr-srv"),
            error = eq("502 (Correlation-Id=corr-srv)"),
            now = eq(600L),
        )
    }

    @Test
    fun processOnce_setsFailedPermanent_onAuthError_withoutSchedulingRetry() {
        val job = pendingJob()
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(listOf(job))
        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession("s1"))

        val shouldRetry = SyncOutboxProcessor(
            db = db,
            adapterResolver = { throw AdapterError.AuthError("unauthorized") },
            nowProvider = { 700L },
            corrIdProvider = { "corr-auth" },
        ).processOnce()

        assertFalse(shouldRetry)
        verify(outboxDao).updateJobState(
            jobId = eq("job-1"),
            state = eq("failed_permanent"),
            retryCount = eq(1),
            corrId = eq("corr-auth"),
            error = eq("unauthorized (Correlation-Id=corr-auth)"),
            now = eq(700L),
        )
    }

    @Test
    fun processOnce_setsFailedPermanent_onValidationError() {
        val job = pendingJob()
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(listOf(job))
        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession("s1"))

        val shouldRetry = SyncOutboxProcessor(
            db = db,
            adapterResolver = { throw AdapterError.ValidationError("bad payload") },
            nowProvider = { 800L },
            corrIdProvider = { "corr-val" },
        ).processOnce()

        assertFalse(shouldRetry)
        verify(outboxDao).updateJobState(
            jobId = eq("job-1"),
            state = eq("failed_permanent"),
            retryCount = eq(1),
            corrId = eq("corr-val"),
            error = eq("bad payload (Correlation-Id=corr-val)"),
            now = eq(800L),
        )
    }

    @Test
    fun processOnce_setsFailedPermanent_onUnexpectedException() {
        val job = pendingJob()
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(listOf(job))
        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession("s1"))

        val shouldRetry = SyncOutboxProcessor(
            db = db,
            adapterResolver = { throw IllegalStateException("boom") },
            nowProvider = { 900L },
            corrIdProvider = { "corr-ex" },
        ).processOnce()

        assertFalse(shouldRetry)
        verify(outboxDao).updateJobState(
            jobId = eq("job-1"),
            state = eq("failed_permanent"),
            retryCount = eq(1),
            corrId = eq("corr-ex"),
            error = eq("boom (Correlation-Id=corr-ex)"),
            now = eq(900L),
        )
    }

    @Test
    fun processOnce_setsFailedPermanent_forIncompleteSession() {
        val job = pendingJob()
        whenever(outboxDao.getRunnableJobs(eq("custom_node"), eq(20))).thenReturn(listOf(job))
        whenever(sessionDao.getSession("s1")).thenReturn(finishedSession("s1").copy(state = "incomplete"))

        val shouldRetry = SyncOutboxProcessor(
            db = db,
            adapterResolver = { adapter },
            nowProvider = { 400L },
            corrIdProvider = { "corr-4" },
        ).processOnce()

        assertFalse(shouldRetry)
        verify(adapter, times(0)).pushInventorySession(any(), any(), any(), any())
        verify(outboxDao).updateJobState(
            jobId = eq("job-1"),
            state = eq("failed_permanent"),
            retryCount = eq(1),
            corrId = eq("corr-4"),
            error = eq("Blocked push for incomplete session (Correlation-Id=corr-4)"),
            now = eq(400L),
        )
    }

    private fun pendingJob(): SyncOutboxEntity {
        return SyncOutboxEntity(
            jobId = "job-1",
            sessionId = "s1",
            providerConnectionId = "custom_node",
            type = "INVENTORY_PUSH_SESSION",
            payload = gson.toJson(
                InventoryPushPayload(
                    sessionId = "s1",
                    providerConnectionId = "custom_node",
                    operatorId = "op",
                    locationId = "loc",
                    catalogSnapshotMarker = "p:1|e:1",
                    productStates = listOf(ProductStatePush("p1", 1, 1, "matched")),
                )
            ),
            idempotencyKey = "idem-1",
            state = "pending",
            retryCount = 0,
            lastCorrelationId = null,
            lastError = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }

    private fun finishedSession(id: String): InventorySessionEntity {
        return InventorySessionEntity(
            sessionId = id,
            providerConnectionId = "custom_node",
            operatorId = "op",
            locationId = "loc",
            startedAt = 1L,
            finishedAt = 2L,
            state = "finished",
            catalogSnapshotMarker = "p:1|e:1",
            updatedAt = 2L,
        )
    }
}

