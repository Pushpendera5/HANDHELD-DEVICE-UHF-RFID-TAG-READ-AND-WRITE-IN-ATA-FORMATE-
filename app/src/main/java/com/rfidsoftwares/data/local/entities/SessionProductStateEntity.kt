
package com.rfidsoftwares.data.local.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "SessionProductState",
    primaryKeys = ["sessionId", "providerConnectionId", "productId"],
    indices = [
        Index(value = ["sessionId"], name = "idx_productState_sessionId"),
        Index(value = ["providerConnectionId"], name = "idx_productState_provider"),
        Index(value = ["productId"], name = "idx_productState_productId"),
    ]
)
data class SessionProductStateEntity(
    val sessionId: String,
    val providerConnectionId: String,
    val productId: String,

    val expectedCount: Int,
    val foundCount: Int,

    val status: String,
)

