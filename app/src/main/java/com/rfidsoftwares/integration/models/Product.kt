
package com.rfidsoftwares.integration.models

data class Product(
    val id: String,
    val providerConnectionId: String,

    val sku: String?,
    val name: String,
    val barcodePrimary: String?,
    val status: String,

    val updatedAtEpochMs: Long,

    val image: String?,
    val description: String?,
    val meta: String?,
)

