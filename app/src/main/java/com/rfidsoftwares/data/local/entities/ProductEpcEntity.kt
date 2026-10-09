
package com.rfidsoftwares.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ProductEpc",
    primaryKeys = ["epc", "providerConnectionId"],
    indices = [
        Index(value = ["providerConnectionId"], name = "idx_epc_provider"),
        Index(value = ["productId"], name = "idx_epc_productId"),
        Index(value = ["epc"], name = "idx_epc_value"),
    ]
)
data class ProductEpcEntity(
    val epc: String,
    val providerConnectionId: String,

    val productId: String,
    val state: String,

    val createdAt: Long,
    val updatedAt: Long,
)

