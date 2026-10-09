
package com.rfidsoftwares.rfid.driver

import android.content.Context
import com.rfidsoftwares.rfid.ReaderDiagnosticsSummary
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.UhfReaderGateway

/**
 * Adapter that bridges any `RfidReaderDriver` to the legacy `UhfReaderGateway` interface.
 */
class DriverUhfReaderGatewayAdapter(
    val driver: RfidReaderDriver = RfidDriverFactory.createDriver()
) : UhfReaderGateway {

    override fun init(context: Context): Boolean {
        return driver.init(context)
    }

    override fun startInventory(): Boolean {
        return driver.startInventory()
    }

    override fun stopInventory(): Boolean {
        return driver.stopInventory()
    }

    override fun free(): Boolean {
        return driver.release()
    }

    override fun readBufferedTagEvents(): List<TagEvent> {
        return driver.readBufferedTagEvents()
    }

    override fun readDiagnosticsSummary(): ReaderDiagnosticsSummary {
        return driver.readDiagnosticsSummary()
    }
}
