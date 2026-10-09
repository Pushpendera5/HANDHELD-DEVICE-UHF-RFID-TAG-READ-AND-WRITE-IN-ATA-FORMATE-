
package com.rfidsoftwares.integration.capabilities

object ShopifyCapabilityMatrix {
    val MATRIX: AdapterCapabilityMatrix = AdapterCapabilityMatrix(
        supportsOauth = false,
        supportsDirectAuth = false,
        supportsCustomerCreate = false,
        supportsEpcRegister = false,
        supportsCheckout = false,
        supportsAntiTheftFinalize = false,
        supportsDiagnosticsUpload = false,
    )
}

