
package com.rfidsoftwares.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rfidsoftwares.data.local.entities.IssueEntity

@Dao
interface IssueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(issue: IssueEntity)

    @Query("SELECT * FROM IssueRecord WHERE active = 1 ORDER BY createdAt DESC LIMIT 200")
    fun listActive(): List<IssueEntity>

    @Query("SELECT * FROM IssueRecord ORDER BY createdAt DESC LIMIT 500")
    fun listAllRecent(): List<IssueEntity>

    @Query("UPDATE IssueRecord SET active = 0 WHERE issueId = :issueId")
    fun dismiss(issueId: String): Int

    @Query("DELETE FROM IssueRecord WHERE createdAt < :cutoffEpochMs AND active = 0")
    fun deleteInactiveOlderThan(cutoffEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM IssueRecord")
    fun count(): Int

    @Query("SELECT issueId FROM IssueRecord ORDER BY createdAt ASC LIMIT :limit")
    fun oldestIssueIds(limit: Int): List<String>

    @Query("SELECT issueId FROM IssueRecord WHERE active = 0 ORDER BY createdAt ASC LIMIT :limit")
    fun oldestInactiveIssueIds(limit: Int): List<String>

    @Query("DELETE FROM IssueRecord WHERE issueId IN (:ids)")
    fun deleteByIds(ids: List<String>): Int
}
