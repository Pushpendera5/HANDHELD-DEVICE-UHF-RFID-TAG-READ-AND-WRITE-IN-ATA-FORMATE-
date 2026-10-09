
package com.rfidsoftwares.integration.auth

import android.content.Context

/**
 * Auth refresh outcomes for diagnostics: in-memory plus [load]/[persistSnapshot] across process restarts.
 */
object AuthRefreshTelemetry {

    private const val PREFS = "auth_refresh_telemetry"
    private const val KEY_ATTEMPT = "lastAttemptAtEpochMs"
    private const val KEY_OUTCOME = "lastOutcome"
    private const val KEY_ERR = "lastErrorSummary"
    /** -1 = never recorded, 0 = failure, 1 = success */
    private const val OUTCOME_UNSET = -1
    private const val OUTCOME_FAIL = 0
    private const val OUTCOME_OK = 1

    @Volatile
    var lastAttemptAtEpochMs: Long = 0L

    @Volatile
    var lastSuccess: Boolean? = null

    @Volatile
    var lastErrorSummary: String? = null

    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lastAttemptAtEpochMs = p.getLong(KEY_ATTEMPT, 0L)
        lastSuccess = when (p.getInt(KEY_OUTCOME, OUTCOME_UNSET)) {
            OUTCOME_OK -> true
            OUTCOME_FAIL -> false
            else -> null
        }
        lastErrorSummary = p.getString(KEY_ERR, null)?.takeIf { it.isNotBlank() }
    }

    fun recordSuccess() {
        lastAttemptAtEpochMs = System.currentTimeMillis()
        lastSuccess = true
        lastErrorSummary = null
        persistSnapshot()
    }

    fun recordFailure(summary: String?) {
        lastAttemptAtEpochMs = System.currentTimeMillis()
        lastSuccess = false
        lastErrorSummary = summary
        persistSnapshot()
    }

    private fun persistSnapshot() {
        runCatching {
            val ctx = com.rfidsoftwares.RfidInventoryApp.requireContext()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putLong(KEY_ATTEMPT, lastAttemptAtEpochMs)
                putInt(
                    KEY_OUTCOME,
                    when (lastSuccess) {
                        true -> OUTCOME_OK
                        false -> OUTCOME_FAIL
                        null -> OUTCOME_UNSET
                    },
                )
                if (lastErrorSummary.isNullOrBlank()) remove(KEY_ERR) else putString(KEY_ERR, lastErrorSummary)
                apply()
            }
        }
    }
}
