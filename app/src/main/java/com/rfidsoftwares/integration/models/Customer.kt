
package com.rfidsoftwares.integration.models

data class Customer(
    val id: String,
    val providerConnectionId: String,
    val phone: String?,
    val email: String?,
    val name: String?,
    val meta: String?,
)

data class CustomerLookupQuery(
    val id: String? = null,
    val phone: String? = null,
    val email: String? = null,
)

