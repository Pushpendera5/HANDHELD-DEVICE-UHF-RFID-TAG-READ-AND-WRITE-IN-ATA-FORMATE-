
package com.rfidsoftwares.rfid

/**
 * Best-effort reader/SDK snapshot for the diagnostics screen.
 * Values may be null when the underlying SDK does not expose them.
 */
data class ReaderDiagnosticsSummary(
    val sdkReady: Boolean,
    val readerOpen: Boolean,
    val powerDbm: Int?,
    val regionOrFrequency: String?,
    val batteryNote: String?,
    val detailLine: String,
)
