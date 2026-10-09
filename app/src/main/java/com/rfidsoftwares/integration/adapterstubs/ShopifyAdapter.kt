
package com.rfidsoftwares.integration.adapterstubs

import com.rfidsoftwares.integration.capabilities.ShopifyCapabilityMatrix

/**
 * Shopify adapter template stub (disabled by default in Phase 3).
 */
class ShopifyAdapter(
    // Later phases will wire baseUrl + credential storage here.
) : DisabledBackendAdapter(
    providerId = "shopify",
    capabilities = ShopifyCapabilityMatrix.MATRIX,
)

