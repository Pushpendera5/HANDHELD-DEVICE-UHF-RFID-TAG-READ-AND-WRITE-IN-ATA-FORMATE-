
package com.rfidsoftwares.integration.error

/**
 * Adapter error hierarchy (Phase 3).
 *
 * Business/UI layers should rely on these types instead of raw HTTP/network exceptions.
 */
sealed class AdapterError(
    override val message: String,
    override val cause: Throwable? = null,
    /** Correlation-Id returned on failed HTTP responses (when the server sends one). */
    val responseCorrelationId: String? = null,
) : Exception(message, cause) {

    class NetworkError(
        message: String,
        cause: Throwable? = null,
        responseCorrelationId: String? = null,
    ) : AdapterError(message, cause, responseCorrelationId)

    class AuthError(
        message: String,
        cause: Throwable? = null,
        responseCorrelationId: String? = null,
    ) : AdapterError(message, cause, responseCorrelationId)

    class ValidationError(
        message: String,
        cause: Throwable? = null,
        responseCorrelationId: String? = null,
    ) : AdapterError(message, cause, responseCorrelationId)

    class ConflictError(
        message: String,
        cause: Throwable? = null,
        responseCorrelationId: String? = null,
    ) : AdapterError(message, cause, responseCorrelationId)

    class ServerError(
        message: String,
        cause: Throwable? = null,
        responseCorrelationId: String? = null,
    ) : AdapterError(message, cause, responseCorrelationId)
}

