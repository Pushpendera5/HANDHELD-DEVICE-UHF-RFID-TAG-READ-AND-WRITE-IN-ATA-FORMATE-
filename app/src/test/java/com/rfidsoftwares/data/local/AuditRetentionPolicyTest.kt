package com.rfidsoftwares.data.local

import com.rfidsoftwares.data.local.dao.AuditLogDao
import com.rfidsoftwares.data.local.dao.IssueDao
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AuditRetentionPolicyTest {

    @Test
    fun enforce_whenOnlyActiveIssuesExceedCap_doesNotDeleteAnyIssue() {
        val db = mock<RfidSessionDatabase>()
        val issueDao = mock<IssueDao>()
        val auditDao = mock<AuditLogDao>()
        whenever(db.issueDao()).thenReturn(issueDao)
        whenever(db.auditLogDao()).thenReturn(auditDao)

        whenever(auditDao.deleteOlderThan(any())).thenReturn(0)
        whenever(auditDao.count()).thenReturn(0)
        whenever(issueDao.deleteInactiveOlderThan(any())).thenReturn(0)
        whenever(issueDao.count()).thenReturn(55)
        whenever(issueDao.oldestInactiveIssueIds(any())).thenReturn(emptyList())

        AuditRetentionPolicy.enforce(db)

        verify(issueDao, never()).deleteByIds(any())
    }

    @Test
    fun enforce_trimsDismissedIssuesOnly_untilUnderCap() {
        val db = mock<RfidSessionDatabase>()
        val issueDao = mock<IssueDao>()
        val auditDao = mock<AuditLogDao>()
        whenever(db.issueDao()).thenReturn(issueDao)
        whenever(db.auditLogDao()).thenReturn(auditDao)

        whenever(auditDao.deleteOlderThan(any())).thenReturn(0)
        whenever(auditDao.count()).thenReturn(0)
        whenever(issueDao.deleteInactiveOlderThan(any())).thenReturn(0)
        whenever(issueDao.count()).thenReturn(52, 50)
        whenever(issueDao.oldestInactiveIssueIds(2)).thenReturn(listOf("a", "b"))

        AuditRetentionPolicy.enforce(db)

        verify(issueDao).deleteByIds(listOf("a", "b"))
        verify(issueDao, never()).oldestIssueIds(any())
    }
}
