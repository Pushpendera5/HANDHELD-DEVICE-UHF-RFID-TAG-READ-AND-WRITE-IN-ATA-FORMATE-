
package com.rfidsoftwares.integration.adapterstubs

import com.rfidsoftwares.integration.capabilities.ZohoCapabilityMatrix

/**
 * Zoho adapter template stub (disabled by default in Phase 3).
 */
class ZohoAdapter(
    // Later phases will wire baseUrl + credential storage here.
) : DisabledBackendAdapter(
    providerId = "zoho",
    capabilities = ZohoCapabilityMatrix.MATRIX,
)

