
package com.rfidsoftwares.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rfidsoftwares.data.local.entities.UnknownEpcCacheEntity

@Dao
interface UnknownEpcCacheDao {

    @Query(
        "SELECT * FROM UnknownEpcCache " +
            "WHERE providerConnectionId = :providerConnectionId AND epc = :epc " +
            "LIMIT 1"
    )
    fun getUnknown(providerConnectionId: String, epc: String): UnknownEpcCacheEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertUnknown(cache: UnknownEpcCacheEntity)

    @Query(
        "UPDATE UnknownEpcCache " +
            "SET lastSeenAt = :lastSeenAt, seenCount = seenCount + 1 " +
            "WHERE providerConnectionId = :providerConnectionId AND epc = :epc"
    )
    fun updateSeen(providerConnectionId: String, epc: String, lastSeenAt: Long)

    @Query(
        "SELECT epc FROM UnknownEpcCache " +
            "WHERE providerConnectionId = :providerConnectionId " +
            "ORDER BY lastSeenAt DESC, firstSeenAt DESC " +
            "LIMIT :limit"
    )
    fun getUnknownEpcsOrdered(providerConnectionId: String, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM UnknownEpcCache WHERE providerConnectionId = :providerConnectionId")
    fun countUnknown(providerConnectionId: String): Int

    @Query(
        "SELECT * FROM UnknownEpcCache " +
            "WHERE providerConnectionId = :providerConnectionId " +
            "ORDER BY lastSeenAt ASC, firstSeenAt ASC"
    )
    fun getAllUnknownOrderedOldestFirst(providerConnectionId: String): List<UnknownEpcCacheEntity>

    @Query("DELETE FROM UnknownEpcCache WHERE providerConnectionId = :providerConnectionId AND epc IN (:epcs)")
    fun deleteByEpcs(providerConnectionId: String, epcs: List<String>)

    @Query("DELETE FROM UnknownEpcCache WHERE providerConnectionId = :providerConnectionId")
    fun deleteByProvider(providerConnectionId: String)

    /**
     * Atomic: upsert + deterministic cap eviction (oldest by lastSeenAt, tie by firstSeenAt).
     */
    @Transaction
    fun upsertAndEnforceCap(
        providerConnectionId: String,
        epc: String,
        now: Long,
        cap: Int = 100,
    ) {
        val existing = getUnknown(providerConnectionId, epc)
        if (existing != null) {
            updateSeen(providerConnectionId, epc, lastSeenAt = now)
        } else {
            insertUnknown(
                UnknownEpcCacheEntity(
                    providerConnectionId = providerConnectionId,
                    epc = epc,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    seenCount = 1,
                )
            )
        }

        val all = getAllUnknownOrderedOldestFirst(providerConnectionId)
        if (all.size > cap) {
            // Evict oldest items so we keep the newest `cap` entries.
            val excess = all.size - cap
            val toDelete = all.take(excess).map { it.epc }
            if (toDelete.isNotEmpty()) deleteByEpcs(providerConnectionId, toDelete)
        }
    }
}

