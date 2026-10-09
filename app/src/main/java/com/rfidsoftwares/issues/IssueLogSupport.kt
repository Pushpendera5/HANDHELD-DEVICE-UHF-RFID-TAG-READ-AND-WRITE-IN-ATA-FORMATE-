
package com.rfidsoftwares.issues

import com.rfidsoftwares.data.local.AuditRetentionPolicy
import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.entities.AuditLogEntity
import com.rfidsoftwares.data.local.entities.IssueEntity
import com.rfidsoftwares.integration.error.AdapterError
import java.util.UUID

object IssueActions {
    const val RETRY_SYNC = "RETRY_SYNC"
    const val OPEN_DIAGNOSTICS = "OPEN_DIAGNOSTICS"
    const val CONFLICT_GUIDANCE = "CONFLICT_GUIDANCE"
    const val NONE = "NONE"
}

object IssueCategories {
    const val AUTH = "auth"
    const val NETWORK = "network"
    const val RFID = "rfid"
    const val SYNC = "sync"
    const val CONFLICT = "conflict"
    const val GENERAL = "general"
}

object IssueLogSupport {

    /** Inventory outbox reached a terminal or conflicted state (best-effort; safe if DB is partial in tests). */
    fun recordOutboxFailure(
        db: RfidSessionDatabase,
        providerConnectionId: String,
        jobId: String,
        sessionId: String,
        terminalState: String,
        message: String,
        correlationId: String?,
    ) {
        val (severity, action) = when (terminalState) {
            "conflicted" -> "warning" to IssueActions.CONFLICT_GUIDANCE
            else -> "error" to IssueActions.RETRY_SYNC
        }
        insertIssue(
            db,
            IssueEntity(
                issueId = UUID.randomUUID().toString(),
                severity = severity,
                category = IssueCategories.SYNC,
                message = "Sync outbox $terminalState: $message",
                correlationId = correlationId,
                detail = "jobId=$jobId · sessionId=$sessionId · provider=$providerConnectionId",
                createdAt = System.currentTimeMillis(),
                active = true,
                suggestedAction = action,
            ),
        )
    }

    fun insertIssue(db: RfidSessionDatabase, issue: IssueEntity) {
        db.issueDao().insert(issue)
        AuditRetentionPolicy.enforce(db)
    }

    fun insertAudit(db: RfidSessionDatabase, entry: AuditLogEntity) {
        db.auditLogDao().insert(entry)
        AuditRetentionPolicy.enforce(db)
    }

    fun recordFromAdapterError(
        db: RfidSessionDatabase,
        e: AdapterError,
        category: String,
        correlationId: String?,
    ) {
        val (severity, action) = when (e) {
            is AdapterError.NetworkError -> "error" to IssueActions.RETRY_SYNC
            is AdapterError.AuthError -> "error" to IssueActions.RETRY_SYNC
            is AdapterError.ConflictError -> "warning" to IssueActions.CONFLICT_GUIDANCE
            is AdapterError.ValidationError -> "warning" to IssueActions.NONE
            is AdapterError.ServerError -> "error" to IssueActions.NONE
        }
        val effectiveCorr = e.responseCorrelationId ?: correlationId
        val detailParts = buildList {
            e.cause?.message?.let { add(it) }
            if (!correlationId.isNullOrBlank() &&
                !e.responseCorrelationId.isNullOrBlank() &&
                correlationId != e.responseCorrelationId
            ) {
                add("Client request Correlation-Id: $correlationId")
            }
        }
        insertIssue(
            db,
            IssueEntity(
                issueId = UUID.randomUUID().toString(),
                severity = severity,
                category = category,
                message = e.message.ifBlank { "Adapter error" },
                correlationId = effectiveCorr,
                detail = detailParts.joinToString("\n").ifBlank { null },
                createdAt = System.currentTimeMillis(),
                active = true,
                suggestedAction = action,
            ),
        )
    }
}
