
package com.rfidsoftwares.common.config

/**
 * Phase 1: centralized, config-driven defaults for UI shells and early onboarding.
 *
 * Heavy business logic (network adapters/RFID/session engine/RBAC) is intentionally NOT implemented here.
 */
object AppConfig {

    const val LANDING_SLIDES_ENABLED: Boolean = true

    // CAPTCHA is config-driven: if enabled but key missing, auth submit is blocked with a safe UX error.
    object CaptchaConfig {
        const val CAPTCHA_ENABLED: Boolean = false
        // Phase 1 default is a placeholder key so the UI flow is usable.
        // Later you can set this to empty to verify the “missing key” blocked-state UX.
        const val CAPTCHA_SITE_KEY: String = ""

        // Phase 3 adapter behavior switches (so you can turn CAPTCHA requirement on/off per operation).
        // If `CAPTCHA_ENABLED` is false, these flags are ignored.
        const val REQUIRE_CAPTCHA_ON_LOGIN: Boolean = false
        const val REQUIRE_CAPTCHA_ON_REFRESH: Boolean = false
    }

    /**
     * Phase 8 test-mode behavior controls.
     *
     * These switches are only evaluated when running a debuggable build.
     */
    object TestModeConfig {
        const val ENABLED: Boolean = false
        // For real-device testing we typically want the mock UHF gateway disabled.
        // Keep TEST_MODE_ENABLED unchanged unless you also want to bypass fixtures for HTTP.
        const val UHF_ENABLED: Boolean = false

        // Failure-fixture routing switches (for deterministic negative-path testing).
        const val FORCE_AUTH_FAILURE_FIXTURE: Boolean = false
        const val FORCE_CHECKOUT_FAILURE_FIXTURE: Boolean = false
    }

    /**
     * Bootstrap admin credential policy:
     * - Phase 1 only documents/configures constraints and defaults
     * - Phase 6 implements the actual RBAC/admin enrollment logic.
     */
    object BootstrapAdminPolicy {
        const val BOOTSTRAP_USERNAME: String = "admin"
        const val MIN_PASSWORD_LENGTH: Int = 10
        const val REQUIRE_CONFIRMATION_ON_FIRST_RUN: Boolean = true
    }

    object DashboardFirstOpenPromptConfig {
        const val ENABLED: Boolean = true
        const val RUN_INVENTORY_SYNC_LABEL: String = "Run Inventory Sync Now"
        const val LATER_LABEL: String = "Later"
    }

    const val APP_NAME: String = "OpenRFID Handheld Android"

    object SyncReliabilityConfig {
        const val CLIENT_ID: String = "openrfid_handheld_android"
        const val INVENTORY_PUSH_OPERATION_TYPE: String = "INVENTORY_PUSH_SESSION"
        const val OUTBOX_MAX_RETRIES: Int = 5
    }

    enum class AppRole { ADMIN, USER }

    enum class AuthMode { DIRECT, OAUTH }

    data class ProviderDefinition(
        val providerId: String,
        val displayName: String,
        val isImplementedInPhase1: Boolean,
        val supportsDirectAuth: Boolean,
        val supportsOauth: Boolean,
    )

    object ProviderRegistry {
        val providers: List<ProviderDefinition> = listOf(
            ProviderDefinition(
                providerId = "custom_node",
                displayName = "Custom Node",
                isImplementedInPhase1 = true,
                supportsDirectAuth = true,
                supportsOauth = true,
            ),
            ProviderDefinition(
                providerId = "shopify",
                displayName = "Shopify",
                isImplementedInPhase1 = false,
                supportsDirectAuth = false,
                supportsOauth = false,
            ),
            ProviderDefinition(
                providerId = "woo",
                displayName = "WooCommerce",
                isImplementedInPhase1 = false,
                supportsDirectAuth = false,
                supportsOauth = false,
            ),
            ProviderDefinition(
                providerId = "zoho",
                displayName = "Zoho",
                isImplementedInPhase1 = false,
                supportsDirectAuth = false,
                supportsOauth = false,
            ),
        )

        fun getById(providerId: String?): ProviderDefinition? {
            if (providerId.isNullOrBlank()) return null
            return providers.firstOrNull { it.providerId == providerId }
        }
    }

    object FeatureKeys {
        const val INVENTORY_SYNC = "inventory_sync"
        const val FIND_PRODUCT = "find_product"
        const val REGISTER_EPC = "register_epc"
        const val CHECKOUT_BILLING = "checkout_billing"
        const val ANTI_THEFT = "anti_theft"
        const val DIAGNOSTICS = "diagnostics"
        const val ROLE_MANAGEMENT = "role_management"
    }

    object FeatureVisibility {
        fun featuresForRole(role: AppRole): Set<String> {
            return when (role) {
                AppRole.ADMIN -> setOf(
                    FeatureKeys.INVENTORY_SYNC,
                    FeatureKeys.FIND_PRODUCT,
                    FeatureKeys.REGISTER_EPC,
                    FeatureKeys.CHECKOUT_BILLING,
                    FeatureKeys.ANTI_THEFT,
                    FeatureKeys.DIAGNOSTICS,
                    FeatureKeys.ROLE_MANAGEMENT,
                )
                AppRole.USER -> setOf(
                    FeatureKeys.INVENTORY_SYNC,
                    FeatureKeys.FIND_PRODUCT,
                    FeatureKeys.CHECKOUT_BILLING,
                    FeatureKeys.ANTI_THEFT,
                    FeatureKeys.DIAGNOSTICS,
                )
            }
        }
    }
}

