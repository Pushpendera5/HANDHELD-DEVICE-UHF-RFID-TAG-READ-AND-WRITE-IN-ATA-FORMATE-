
package com.rfidsoftwares.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rfidsoftwares.data.local.entities.SyncOutboxEntity

@Dao
interface SyncOutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(job: SyncOutboxEntity): Long

    @Query("SELECT * FROM SyncOutbox WHERE jobId = :jobId LIMIT 1")
    fun getById(jobId: String): SyncOutboxEntity?

    @Query(
        "SELECT * FROM SyncOutbox " +
            "WHERE sessionId = :sessionId " +
            "ORDER BY updatedAt DESC " +
            "LIMIT 1"
    )
    fun getLatestJobForSession(sessionId: String): SyncOutboxEntity?

    @Query(
        "SELECT * FROM SyncOutbox " +
            "WHERE providerConnectionId = :providerConnectionId " +
            "AND state = 'conflicted' " +
            "ORDER BY updatedAt DESC " +
            "LIMIT 1"
    )
    fun getLatestConflictedJob(providerConnectionId: String): SyncOutboxEntity?

    @Query(
        "SELECT * FROM SyncOutbox " +
            "WHERE providerConnectionId = :providerConnectionId " +
            "AND state IN ('pending', 'retrying') " +
            "ORDER BY createdAt ASC " +
            "LIMIT :limit"
    )
    fun getRunnableJobs(providerConnectionId: String, limit: Int): List<SyncOutboxEntity>

    @Query(
        "UPDATE SyncOutbox SET state='retrying', updatedAt=:now, lastError='Recovered from interrupted running state' " +
            "WHERE state='running'"
    )
    fun recoverStuckRunningJobs(now: Long): Int

    @Query("UPDATE SyncOutbox SET state=:state, retryCount=:retryCount, lastCorrelationId=:corrId, lastError=:error, updatedAt=:now WHERE jobId=:jobId")
    fun updateJobState(
        jobId: String,
        state: String,
        retryCount: Int,
        corrId: String?,
        error: String?,
        now: Long,
    )

    @Query(
        "UPDATE SyncOutbox SET state='failed_permanent', lastError=:reason, updatedAt=:now " +
            "WHERE jobId=:jobId AND state='conflicted'"
    )
    fun discardConflictedJob(jobId: String, reason: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM SyncOutbox WHERE providerConnectionId = :providerConnectionId AND state = :state")
    fun countByState(providerConnectionId: String, state: String): Int

    @Query("SELECT MAX(updatedAt) FROM SyncOutbox WHERE providerConnectionId = :providerConnectionId")
    fun maxUpdatedAt(providerConnectionId: String): Long?

    @Query(
        "SELECT state, COUNT(*) as count FROM SyncOutbox WHERE providerConnectionId = :providerConnectionId GROUP BY state"
    )
    fun countGroupedByState(providerConnectionId: String): List<OutboxStateCount>

    @Query("DELETE FROM SyncOutbox WHERE providerConnectionId = :providerConnectionId")
    fun deleteByProvider(providerConnectionId: String)
}

