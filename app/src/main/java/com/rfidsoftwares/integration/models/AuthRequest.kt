
package com.rfidsoftwares.integration.models

sealed class AuthRequest {
    data class DirectCredentialAuth(
        val username: String,
        val password: String,
    ) : AuthRequest()

    data class OAuthAuth(
        val code: String,
        val redirectUri: String,
    ) : AuthRequest()
}

