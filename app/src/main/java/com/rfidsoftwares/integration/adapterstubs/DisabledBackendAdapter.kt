
package com.rfidsoftwares.integration.adapterstubs

import com.rfidsoftwares.integration.BackendAdapter
import com.rfidsoftwares.integration.capabilities.AdapterCapabilityMatrix
import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.models.*

/**
 * Template adapter stub for providers not implemented in Phase 3.
 *
 * Intentionally disabled: later phases will replace these stubs with real HTTP+mapper logic.
 */
open class DisabledBackendAdapter(
    override val providerId: String,
    override val capabilities: AdapterCapabilityMatrix,
) : BackendAdapter {

    private fun disabled(): Nothing =
        throw AdapterError.ValidationError("Adapter '$providerId' is disabled/not implemented in Phase 3.")

    override fun startAuth(request: AuthRequest, captchaToken: String?): Credential = disabled()
    override fun refreshToken(): Credential = disabled()
    override fun revoke(): Boolean = disabled()
    override fun getValidCredential(captchaToken: String?): Credential = disabled()
    override fun healthCheck(): HealthResult = disabled()

    override fun fetchProductsFull(providerConnectionId: String): List<Product> = disabled()
    override fun fetchProductsDelta(providerConnectionId: String, updatedSince: Long): List<Product> = disabled()
    override fun searchProducts(providerConnectionId: String, query: String): List<Product> = disabled()

    override fun fetchCatalogFull(providerConnectionId: String): ProductCatalogSnapshot = disabled()
    override fun fetchCatalogDelta(providerConnectionId: String, updatedSince: Long): ProductCatalogSnapshot = disabled()

    override fun registerEpc(
        providerConnectionId: String,
        epc: String,
        productId: String?,
    ): ProductEpc = disabled()

    override fun pushInventorySession(
        providerConnectionId: String,
        payload: InventoryPushPayload,
        idempotencyKey: String,
        correlationId: String?,
    ) = disabled()

    override fun lookupCustomer(providerConnectionId: String, query: CustomerLookupQuery): Customer? = disabled()
    override fun createCustomer(providerConnectionId: String, payload: CustomerCreatePayload): Customer = disabled()

    override fun createCartFromEpcs(providerConnectionId: String, payload: CartFromEpcsPayload): Cart = disabled()
    override fun modifyCart(providerConnectionId: String, payload: CartModifyPayload): Cart = disabled()
    override fun generateCheckoutBill(providerConnectionId: String, payload: CheckoutBillPayload, idempotencyKey: String): CheckoutBill = disabled()

    override fun antiTheftUpdateTags(
        providerConnectionId: String,
        payload: AntiTheftUpdatePayload,
        idempotencyKey: String,
        correlationId: String?,
    ): Boolean = disabled()
    override fun uploadDiagnostics(providerConnectionId: String, payload: DiagnosticsUploadPayload): Boolean = disabled()
}

