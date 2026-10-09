
package com.rfidsoftwares.common.auth

/**
 * Phase 3: UI wiring helper.
 *
 * Stores the credentials entered on the login screen so ProviderAuthFlowFragment
 * can trigger the adapter auth call path.
 *
 * Note: this is in-memory only (Phase 4+ can replace with secure persistence).
 */
object AuthUiStateStore {
    var username: String? = null
    var password: String? = null
    var captchaToken: String? = null

    fun clear() {
        username = null
        password = null
        captchaToken = null
    }
}

