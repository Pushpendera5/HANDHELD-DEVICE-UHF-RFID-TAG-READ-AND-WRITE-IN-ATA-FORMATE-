
package com.rfidsoftwares.data.local

/**
 * Phase 5 audit + issue retention (count and time window).
 */
object AuditRetentionPolicy {
    const val AUDIT_MAX_RECORDS: Int = 100
    const val AUDIT_MAX_HOURS: Int = 6
    const val ISSUE_MAX_RECORDS: Int = 50

    fun enforce(db: RfidSessionDatabase) {
        val now = System.currentTimeMillis()
        val cutoff = now - AUDIT_MAX_HOURS * 3_600_000L

        val auditDao = db.auditLogDao()
        auditDao.deleteOlderThan(cutoff)
        trimAuditByCount(auditDao)

        val issueDao = db.issueDao()
        issueDao.deleteInactiveOlderThan(cutoff)
        trimIssuesByCount(issueDao)
    }

    private fun trimAuditByCount(dao: com.rfidsoftwares.data.local.dao.AuditLogDao) {
        val excess = dao.count() - AUDIT_MAX_RECORDS
        if (excess <= 0) return
        val ids = dao.oldestRowIds(excess)
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    /**
     * Only evicts **dismissed** (inactive) issues by count. Active issues are never deleted here so the
     * Issue Center cannot drop unread alerts just because the cap was hit.
     */
    private fun trimIssuesByCount(dao: com.rfidsoftwares.data.local.dao.IssueDao) {
        var excess = dao.count() - ISSUE_MAX_RECORDS
        if (excess <= 0) return
        while (excess > 0) {
            val batch = dao.oldestInactiveIssueIds(minOf(excess, 50))
            if (batch.isEmpty()) break
            dao.deleteByIds(batch)
            excess = dao.count() - ISSUE_MAX_RECORDS
        }
    }
}
