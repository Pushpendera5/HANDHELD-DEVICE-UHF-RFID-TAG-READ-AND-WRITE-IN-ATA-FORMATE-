package com.rfidsoftwares.issues

import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.dao.AuditLogDao
import com.rfidsoftwares.data.local.dao.IssueDao
import com.rfidsoftwares.data.local.entities.IssueEntity
import com.rfidsoftwares.integration.error.AdapterError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class IssueLogSupportTest {

    @Test
    fun recordFromAdapterError_prefersResponseCorrelationId_andKeepsClientInDetail() {
        val db = mock<RfidSessionDatabase>()
        val issueDao = mock<IssueDao>()
        val auditDao = mock<AuditLogDao>()
        whenever(db.issueDao()).thenReturn(issueDao)
        whenever(db.auditLogDao()).thenReturn(auditDao)
        whenever(auditDao.deleteOlderThan(any())).thenReturn(0)
        whenever(auditDao.count()).thenReturn(0)
        whenever(issueDao.deleteInactiveOlderThan(any())).thenReturn(0)
        whenever(issueDao.count()).thenReturn(0)

        val err = AdapterError.ServerError("boom", responseCorrelationId = "server-corr-99")
        IssueLogSupport.recordFromAdapterError(db, err, IssueCategories.SYNC, "client-corr-1")

        val captor = argumentCaptor<IssueEntity>()
        verify(issueDao).insert(captor.capture())
        assertEquals("server-corr-99", captor.firstValue.correlationId)
        assertTrue(captor.firstValue.detail!!.contains("client-corr-1"))
    }
}
