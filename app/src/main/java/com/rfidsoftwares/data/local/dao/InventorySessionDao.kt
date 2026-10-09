
package com.rfidsoftwares.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.rfidsoftwares.data.local.entities.InventorySessionEntity

@Dao
interface InventorySessionDao {

    @Insert
    fun insertSession(session: InventorySessionEntity)

    @Query(
        "SELECT * FROM InventorySession " +
            "WHERE providerConnectionId = :providerConnectionId " +
            "AND state != 'finished' " +
            "ORDER BY startedAt DESC " +
            "LIMIT 1"
    )
    fun getActiveSession(providerConnectionId: String): InventorySessionEntity?

    @Query("SELECT * FROM InventorySession WHERE sessionId = :sessionId LIMIT 1")
    fun getSession(sessionId: String): InventorySessionEntity?

    @Query("UPDATE InventorySession SET state = :state, finishedAt = :finishedAt, updatedAt = :updatedAt WHERE sessionId = :sessionId")
    fun updateSessionState(
        sessionId: String,
        state: String,
        finishedAt: Long?,
        updatedAt: Long,
    )

    @Query("DELETE FROM InventorySession WHERE providerConnectionId = :providerConnectionId")
    fun deleteByProvider(providerConnectionId: String)

    @Update
    fun updateSession(session: InventorySessionEntity)
}

