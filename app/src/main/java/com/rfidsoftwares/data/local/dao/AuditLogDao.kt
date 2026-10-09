
package com.rfidsoftwares.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.rfidsoftwares.data.local.entities.AuditLogEntity

@Dao
interface AuditLogDao {

    @Insert
    fun insert(entry: AuditLogEntity): Long

    @Query("SELECT * FROM AuditLog ORDER BY createdAt DESC LIMIT :limit")
    fun listRecent(limit: Int): List<AuditLogEntity>

    @Query("DELETE FROM AuditLog WHERE createdAt < :cutoffEpochMs")
    fun deleteOlderThan(cutoffEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM AuditLog")
    fun count(): Int

    @Query("SELECT id FROM AuditLog ORDER BY createdAt ASC LIMIT :limit")
    fun oldestRowIds(limit: Int): List<Long>

    @Query("DELETE FROM AuditLog WHERE id IN (:ids)")
    fun deleteByIds(ids: List<Long>): Int
}
