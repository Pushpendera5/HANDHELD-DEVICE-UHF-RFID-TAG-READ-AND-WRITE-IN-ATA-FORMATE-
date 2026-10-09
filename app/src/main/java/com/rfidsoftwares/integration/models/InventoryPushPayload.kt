
package com.rfidsoftwares.integration.models

/**
 * Phase 3 inventory sync push payload (provider-specific mapping belongs in adapter).
 *
 * This model is intentionally minimal for now; Phase 4 will define reconciliation-ready payload shape.
 */
data class InventoryPushPayload(
    val sessionId: String,
    val providerConnectionId: String,
    val operatorId: String,
    val locationId: String,
    val catalogSnapshotMarker: String? = null,
    val productStates: List<ProductStatePush>,
)

data class ProductStatePush(
    val productId: String,
    val expectedCount: Int,
    val foundCount: Int,
    val status: String,
)

