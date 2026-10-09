
package com.rfidsoftwares.integration.adapterstubs

import com.rfidsoftwares.integration.capabilities.WooCapabilityMatrix

/**
 * Woo adapter template stub (disabled by default in Phase 3).
 */
class WooAdapter(
    // Later phases will wire baseUrl + credential storage here.
) : DisabledBackendAdapter(
    providerId = "woo",
    capabilities = WooCapabilityMatrix.MATRIX,
)

