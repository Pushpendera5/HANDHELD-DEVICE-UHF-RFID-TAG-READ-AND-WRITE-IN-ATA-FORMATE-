
package com.rfidsoftwares.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "IssueRecord",
    indices = [
        Index(value = ["active", "createdAt"], name = "idx_issue_active_created"),
        Index(value = ["createdAt"], name = "idx_issue_created"),
    ],
)
data class IssueEntity(
    @PrimaryKey
    val issueId: String,
    val severity: String,
    val category: String,
    val message: String,
    val correlationId: String?,
    val detail: String?,
    val createdAt: Long,
    val active: Boolean,
    /** RETRY_SYNC, OPEN_DIAGNOSTICS, CONFLICT_GUIDANCE, NONE */
    val suggestedAction: String,
)
