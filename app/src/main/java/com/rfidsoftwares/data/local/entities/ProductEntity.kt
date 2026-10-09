
package com.rfidsoftwares.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "Product",
    indices = [
        Index(value = ["providerConnectionId"], name = "idx_product_provider"),
        Index(value = ["updatedAt"], name = "idx_product_updatedAt"),
    ]
)
data class ProductEntity(
    @PrimaryKey
    val id: String,

    val providerConnectionId: String,
    val sku: String?,
    val name: String,
    val barcodePrimary: String?,
    val status: String,
    val updatedAt: Long,

    val image: String?,
    val description: String?,
    val meta: String?,
)

