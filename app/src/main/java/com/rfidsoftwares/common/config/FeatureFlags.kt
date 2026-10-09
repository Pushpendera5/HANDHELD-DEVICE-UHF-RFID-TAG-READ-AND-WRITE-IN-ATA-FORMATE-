
package com.rfidsoftwares.common.config

import android.content.pm.ApplicationInfo
import com.rfidsoftwares.RfidInventoryApp

/**
 * Phase 8 feature flags.
 *
 * Defaults:
 * - `TEST_MODE_ENABLED` is ON for debug builds so the app works without backend/hardware.
 * - `UHF_TEST_MODE_ENABLED` is ON for debug builds to run deterministic mock scan sequences.
 *
 * Note: values are computed at runtime to avoid relying on `BuildConfig` generation.
 */
object FeatureFlags {
    /**
     * Primary switch: when OFF, real backend APIs and real UHF gateway must always be used.
     */
    val TEST_MODE_ENABLED: Boolean
        get() = isDebuggable() && AppConfig.TestModeConfig.ENABLED

    /**
     * Evaluated only when `TEST_MODE_ENABLED` is ON.
     */
    val UHF_TEST_MODE_ENABLED: Boolean
        get() = TEST_MODE_ENABLED && AppConfig.TestModeConfig.UHF_ENABLED

    val FORCE_AUTH_FAILURE_FIXTURE: Boolean
        get() = TEST_MODE_ENABLED && AppConfig.TestModeConfig.FORCE_AUTH_FAILURE_FIXTURE

    val FORCE_CHECKOUT_FAILURE_FIXTURE: Boolean
        get() = TEST_MODE_ENABLED && AppConfig.TestModeConfig.FORCE_CHECKOUT_FAILURE_FIXTURE

    private fun isDebuggable(): Boolean {
        // In unit/instrumentation contexts where ApplicationInfo isn't available, fail closed.
        val ctx = runCatching { RfidInventoryApp.AppContextHolder.requireContext() }.getOrNull()
            ?: return false
        return (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}

