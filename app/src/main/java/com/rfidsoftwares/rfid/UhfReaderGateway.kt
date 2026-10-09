
package com.rfidsoftwares.rfid

import android.content.Context

/**
 * Phase 2 RFID gateway abstraction.
 *
 * Encapsulates the Chainway SDK lifecycle and provides buffered tag reads for the scan loop.
 */
interface UhfReaderGateway {
    fun init(context: Context): Boolean
    fun startInventory(): Boolean
    fun stopInventory(): Boolean
    fun free(): Boolean

    /**
     * Reads whatever tags are currently buffered in the SDK.
     * Returns an empty list when no tag is available.
     */
    fun readBufferedTagEvents(): List<TagEvent>

    /**
     * Phase 5 diagnostics: power/region/battery when the native SDK exposes them.
     */
    fun readDiagnosticsSummary(): ReaderDiagnosticsSummary
}

