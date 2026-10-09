
package com.rfidsoftwares.integration.models

data class Cart(
    val id: String,
    val providerConnectionId: String,
    val lineItems: List<CartLineItem>,
    val total: Money?,
)

data class CartLineItem(
    val productId: String?,
    val quantity: Int,
    val title: String?,
    val meta: String?,
)

data class Money(
    val currency: String?,
    val amount: Double?,
)

data class CartFromEpcsPayload(
    val cartClientRequestId: String,
    val providerConnectionId: String,
    val epcs: List<String>,
)

data class CartModifyPayload(
    val cartId: String,
    val providerConnectionId: String,
    val cartClientRequestId: String,
    val modifications: List<CartLineModification>,
)

data class CustomerCreatePayload(
    val phone: String?,
    val email: String?,
    val name: String?,
    val meta: String?,
)

data class CartLineModification(
    val lineIndex: Int,
    val quantity: Int,
)

data class CheckoutBillPayload(
    val cartId: String,
    val providerConnectionId: String,
    val checkoutClientRequestId: String,
    val paymentType: String?,
)

data class CheckoutBill(
    val billId: String,
    val providerConnectionId: String,
    val amount: Money?,
    val status: String?,
)

data class AntiTheftUpdatePayload(
    val providerConnectionId: String,
    val tagsToUpdate: List<TagUpdate>,
)

data class TagUpdate(
    val epc: String,
    val billedState: String?,
    val meta: String?,
)

data class DiagnosticsUploadPayload(
    val providerConnectionId: String,
    val readerDiagnosticsJson: String,
)

