
package com.rfidsoftwares.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "InventorySession",
    indices = [
        Index(value = ["providerConnectionId"], name = "idx_session_provider"),
        Index(value = ["startedAt"], name = "idx_session_startedAt"),
        Index(value = ["updatedAt"], name = "idx_session_updatedAt"),
    ]
)
data class InventorySessionEntity(
    @PrimaryKey
    val sessionId: String,

    val providerConnectionId: String,
    val operatorId: String,
    val locationId: String,

    val startedAt: Long,
    val finishedAt: Long?,
    val state: String,
    val catalogSnapshotMarker: String?,

    val updatedAt: Long,
)

