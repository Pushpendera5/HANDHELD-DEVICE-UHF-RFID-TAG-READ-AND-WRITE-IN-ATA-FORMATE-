
package com.rfidsoftwares.integration.config

object ApiCommonConfig {
    const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 10_000
    const val DEFAULT_READ_TIMEOUT_MS: Long = 30_000
    const val DEFAULT_WRITE_TIMEOUT_MS: Long = 30_000

    // Adapter-scoped base URL (Phase 3 requires base URL be centralized).
    // Replace this with real backend URL in a later configuration phase.
    const val BASE_URL: String = "https://example.invalid"

    // Header key for idempotency (backend contract). Phase 4 finalizes rules.
    const val IDEMPOTENCY_HEADER_KEY: String = "Idempotency-Key"

    // Adapter should ensure every outgoing request contains Correlation-Id.
    const val CORRELATION_ID_HEADER_KEY: String = "Correlation-Id"
}

