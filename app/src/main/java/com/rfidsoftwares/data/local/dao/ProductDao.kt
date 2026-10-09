
package com.rfidsoftwares.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.OnConflictStrategy
import com.rfidsoftwares.data.local.entities.ProductEntity

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertProducts(products: List<ProductEntity>)

    @Query("SELECT * FROM Product WHERE providerConnectionId = :providerConnectionId")
    fun getProducts(providerConnectionId: String): List<ProductEntity>

    @Query("DELETE FROM Product WHERE providerConnectionId = :providerConnectionId")
    fun deleteByProvider(providerConnectionId: String)
}

