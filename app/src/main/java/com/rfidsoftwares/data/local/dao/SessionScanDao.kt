
package com.rfidsoftwares.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rfidsoftwares.data.local.entities.SessionScanEntity

@Dao
interface SessionScanDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertScan(scan: SessionScanEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertScans(scans: List<SessionScanEntity>): List<Long>

    @Query(
        "SELECT epc FROM SessionScan " +
            "WHERE sessionId = :sessionId AND providerConnectionId = :providerConnectionId"
    )
    fun getSeenEpcs(sessionId: String, providerConnectionId: String): List<String>

    @Query(
        "SELECT * FROM SessionScan " +
            "WHERE sessionId = :sessionId AND providerConnectionId = :providerConnectionId"
    )
    fun getScans(sessionId: String, providerConnectionId: String): List<SessionScanEntity>

    @Query(
        "SELECT COUNT(*) FROM SessionScan " +
            "WHERE sessionId = :sessionId AND providerConnectionId = :providerConnectionId AND isKnown = 0"
    )
    fun countUnknownForSession(sessionId: String, providerConnectionId: String): Int

    @Query(
        "SELECT epc FROM SessionScan " +
            "WHERE sessionId = :sessionId AND providerConnectionId = :providerConnectionId AND isKnown = 0 " +
            "ORDER BY firstSeenAt DESC " +
            "LIMIT :limit"
    )
    fun getUnknownEpcsForSession(sessionId: String, providerConnectionId: String, limit: Int): List<String>

    @Query(
        "SELECT epc FROM SessionScan " +
            "WHERE sessionId = :sessionId AND providerConnectionId = :providerConnectionId " +
            "AND productId = :productId AND isKnown = 1"
    )
    fun getFoundKnownEpcsForProduct(sessionId: String, providerConnectionId: String, productId: String): List<String>

    @Query("DELETE FROM SessionScan WHERE providerConnectionId = :providerConnectionId")
    fun deleteByProvider(providerConnectionId: String)
}

