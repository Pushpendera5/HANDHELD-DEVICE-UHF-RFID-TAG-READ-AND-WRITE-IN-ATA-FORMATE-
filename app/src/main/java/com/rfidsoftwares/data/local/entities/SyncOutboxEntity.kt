
package com.rfidsoftwares.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "SyncOutbox",
    indices = [
        Index(
            value = ["providerConnectionId", "idempotencyKey"],
            unique = true,
            name = "idx_outbox_provider_idempotency_unique",
        ),
        Index(
            value = ["providerConnectionId", "state"],
            name = "idx_outbox_provider_state",
        ),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey
    val jobId: String,
    val sessionId: String,
    val providerConnectionId: String,
    val type: String,
    val payload: String,
    val idempotencyKey: String,
    val state: String,
    val retryCount: Int,
    val lastCorrelationId: String?,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

