
package com.rfidsoftwares.rfid.driver

import android.os.Build

/**
 * Hardware factory that resolves the active RFID driver across all supported handheld vendors:
 * Chainway, Seuic, ATID, Zebra, Honeywell, and Mock Software Reader.
 *
 * Automatically detects hardware using `Build.MODEL` and `Build.MANUFACTURER`, or respects
 * an explicit driver selection set by the user.
 */
object RfidDriverFactory {

    val availableDrivers: List<RfidReaderDriver> = listOf(
        ChainwayC72Driver(),
        SeuicAutoid9uDriver(),
        AtidAt911nDriver(),
        ZebraRfidApi3Driver(),
        HoneywellMobilityDriver(),
        MockRfidDriver()
    )

    /**
     * Resolves the appropriate native driver based on driverId override or system hardware detection.
     */
    fun createDriver(requestedDriverId: String? = null): RfidReaderDriver {
        if (!requestedDriverId.isNullOrBlank() && !requestedDriverId.equals("auto", ignoreCase = true)) {
            val matched = availableDrivers.firstOrNull { it.driverId.equals(requestedDriverId, ignoreCase = true) }
            if (matched != null) return matched
        }

        val model = Build.MODEL.uppercase()
        val manufacturer = Build.MANUFACTURER.uppercase()

        return when {
            model.contains("C72") || model.contains("C71") || model.contains("C66") || manufacturer.contains("CHAINWAY") -> {
                ChainwayC72Driver()
            }
            model.contains("AUTOID") || model.contains("SEUIC") || manufacturer.contains("SEUIC") -> {
                SeuicAutoid9uDriver()
            }
            model.contains("AT911") || model.contains("AT870") || model.contains("AT388") || manufacturer.contains("ATID") -> {
                AtidAt911nDriver()
            }
            model.contains("MC33") || model.contains("TC2") || model.contains("TC5") || model.contains("TC7") || model.contains("TC8") || model.contains("RFD") || manufacturer.contains("ZEBRA") -> {
                ZebraRfidApi3Driver()
            }
            model.contains("CT40") || model.contains("CT60") || model.contains("EDA") || model.contains("IH25") || manufacturer.contains("HONEYWELL") -> {
                HoneywellMobilityDriver()
            }
            else -> {
                MockRfidDriver()
            }
        }
    }
}
