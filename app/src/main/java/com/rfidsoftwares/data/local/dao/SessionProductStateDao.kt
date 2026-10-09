
package com.rfidsoftwares.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.rfidsoftwares.data.local.entities.SessionProductStateEntity
import com.rfidsoftwares.data.local.projections.InventoryProductStateRow

@Dao
interface SessionProductStateDao {

    @Insert
    fun insertProductStates(states: List<SessionProductStateEntity>)

    @Query(
        "UPDATE SessionProductState " +
            "SET foundCount = :foundCount, status = :status " +
            "WHERE sessionId = :sessionId AND providerConnectionId = :providerConnectionId AND productId = :productId"
    )
    fun updateProductFound(sessionId: String, providerConnectionId: String, productId: String, foundCount: Int, status: String)

    @Query(
        "SELECT * FROM SessionProductState " +
            "WHERE sessionId = :sessionId AND providerConnectionId = :providerConnectionId"
    )
    fun getStates(sessionId: String, providerConnectionId: String): List<SessionProductStateEntity>

    @Query(
        "SELECT " +
            "s.productId AS productId, " +
            "p.name AS productName, " +
            "p.sku AS sku, " +
            "p.status AS productStatus, " +
            "s.expectedCount AS expectedCount, " +
            "s.foundCount AS foundCount, " +
            "s.status AS sessionStatus " +
            "FROM SessionProductState s " +
            "INNER JOIN Product p ON p.id = s.productId AND p.providerConnectionId = s.providerConnectionId " +
            "WHERE s.sessionId = :sessionId AND s.providerConnectionId = :providerConnectionId " +
            "ORDER BY " +
            "CASE " +
                "WHEN s.foundCount = 0 THEN 0 " +
                "WHEN s.foundCount < s.expectedCount THEN 1 " +
                "ELSE 2 " +
            "END, " +
            "LOWER(p.name) ASC"
    )
    fun getInventoryRows(sessionId: String, providerConnectionId: String): List<InventoryProductStateRow>

    @Query("DELETE FROM SessionProductState WHERE providerConnectionId = :providerConnectionId")
    fun deleteByProvider(providerConnectionId: String)
}

