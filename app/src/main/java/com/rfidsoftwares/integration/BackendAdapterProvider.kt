
package com.rfidsoftwares.integration

import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.integration.adapterstubs.DisabledBackendAdapter
import com.rfidsoftwares.integration.capabilities.AdapterCapabilityMatrix
import com.rfidsoftwares.integration.config.ApiCommonConfig
import com.rfidsoftwares.integration.customnode.CustomNodeAdapter
import com.rfidsoftwares.integration.customnode.InMemoryCredentialStore
import com.rfidsoftwares.integration.models.Credential

/**
 * Phase 3: adapter provider for live integration surface.
 *
 * For now this keeps credentials in-memory (credentialStore shared across adapter instance)
 * so navigation between fragments doesn't lose the auth token.
 */
object BackendAdapterProvider {
    private val credentialStore = InMemoryCredentialStore()
    private val customNodeAdapter: BackendAdapter by lazy {
        CustomNodeAdapter(
            baseUrl = ApiCommonConfig.BASE_URL,
            credentialStore = credentialStore,
        )
    }

    fun getAdapter(providerId: String): BackendAdapter {
        return when (providerId) {
            AppConfig.ProviderRegistry.providers.first().providerId -> customNodeAdapter
            "custom_node" -> customNodeAdapter
            else -> DisabledBackendAdapter(
                providerId = providerId,
                capabilities = AdapterCapabilityMatrix(
                    supportsOauth = false,
                    supportsDirectAuth = false,
                    supportsCustomerCreate = false,
                    supportsEpcRegister = false,
                    supportsCheckout = false,
                    supportsAntiTheftFinalize = false,
                    supportsDiagnosticsUpload = false,
                )
            )
        }
    }

    fun clearCredentials() {
        credentialStore.clear()
    }
}

