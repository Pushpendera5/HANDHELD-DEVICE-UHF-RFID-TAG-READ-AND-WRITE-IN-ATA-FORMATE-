
package com.rfidsoftwares.integration.models

data class Credential(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMs: Long?,
)

