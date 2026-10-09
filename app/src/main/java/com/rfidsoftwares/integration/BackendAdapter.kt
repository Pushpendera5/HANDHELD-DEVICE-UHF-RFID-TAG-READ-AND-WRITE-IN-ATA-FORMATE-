
package com.rfidsoftwares.integration

import com.rfidsoftwares.integration.capabilities.AdapterCapabilityMatrix
import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.models.*

/**
 * Phase 3 backend adapter contract.
 *
 * Adapter methods must:
 * - add Correlation-Id to every outgoing request
 * - normalize errors into AdapterError subclasses
 * - map provider payloads into canonical models
 *
 * Outbox/retry/conflict logic is explicitly out of Phase 3 scope.
 */
interface BackendAdapter {

    val providerId: String
    val capabilities: AdapterCapabilityMatrix

    fun startAuth(request: AuthRequest, captchaToken: String?): Credential
    fun refreshToken(): Credential
    fun revoke(): Boolean
    fun getValidCredential(captchaToken: String? = null): Credential

    fun healthCheck(): HealthResult

    fun fetchProductsFull(providerConnectionId: String): List<Product>
    fun fetchProductsDelta(providerConnectionId: String, updatedSince: Long): List<Product>
    fun searchProducts(providerConnectionId: String, query: String): List<Product>

    /**
     * Phase 3 inventory-sync dependency:
     * fetch a complete canonical catalog snapshot including product EPC mappings.
     */
    fun fetchCatalogFull(providerConnectionId: String): ProductCatalogSnapshot
    fun fetchCatalogDelta(providerConnectionId: String, updatedSince: Long): ProductCatalogSnapshot

    fun registerEpc(
        providerConnectionId: String,
        epc: String,
        productId: String?,
    ): ProductEpc

    fun pushInventorySession(
        providerConnectionId: String,
        payload: InventoryPushPayload,
        idempotencyKey: String,
        correlationId: String? = null,
    )

    fun lookupCustomer(providerConnectionId: String, query: CustomerLookupQuery): Customer?
    fun createCustomer(providerConnectionId: String, payload: CustomerCreatePayload): Customer

    fun createCartFromEpcs(providerConnectionId: String, payload: CartFromEpcsPayload): Cart
    fun modifyCart(providerConnectionId: String, payload: CartModifyPayload): Cart
    fun generateCheckoutBill(providerConnectionId: String, payload: CheckoutBillPayload, idempotencyKey: String): CheckoutBill

    fun antiTheftUpdateTags(
        providerConnectionId: String,
        payload: AntiTheftUpdatePayload,
        idempotencyKey: String,
        correlationId: String? = null,
    ): Boolean

    fun uploadDiagnostics(providerConnectionId: String, payload: DiagnosticsUploadPayload): Boolean

    /** Diagnostics only — returns null when not authenticated. */
    fun peekCredential(): Credential? = null
}

/**
 * Adapter API must expose these exceptions via AdapterError types.
 */
@Suppress("unused")
private fun throwAdapterError(err: AdapterError): Nothing = throw err

