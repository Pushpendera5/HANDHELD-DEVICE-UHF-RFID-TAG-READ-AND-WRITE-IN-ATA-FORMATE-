
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.rfidsoftwares.R
import com.rfidsoftwares.common.prefs.UhfConfig
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.rfid.driver.DriverUhfReaderGatewayAdapter
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.util.concurrent.Executors

class UhfConfigFragment : BaseScreenFragment() {

    private var bg = Executors.newSingleThreadExecutor()

    override fun screenTitle(): String = "UHF Configuration"
    override fun screenSubtitle(): String? = "Select hardware reader driver, region, power output, and audio feedback"
    override fun allowOfflinePanel(): Boolean = false

    private val driverOptions = listOf(
        DriverOption("Auto-Detect Hardware (System Model)", "auto"),
        DriverOption("Chainway C72 / C71 / C66", "chainway_c72"),
        DriverOption("Seuic AUTOID9U / Cruise", "seuic_autoid9u"),
        DriverOption("ATID AT911N / AT870 / AT388", "atid_at911n"),
        DriverOption("Zebra MC3300R / RFD8500 / RFD40 / RFD90", "zebra_rfid_api3"),
        DriverOption("Honeywell Mobility CT40 / EDA51 / IH25", "honeywell_mobility"),
        DriverOption("Mock Reader (Software Emulator)", "mock")
    )

    private data class DriverOption(val label: String, val driverId: String) {
        override fun toString(): String = label
    }

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_uhf_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBg()

        val driverSpinner: MaterialAutoCompleteTextView = view.findViewById(R.id.uhfDriverSpinner)
        val regionInput: TextInputEditText = view.findViewById(R.id.uhfRegionInput)
        val powerInput: TextInputEditText = view.findViewById(R.id.uhfPowerInput)
        val soundSwitch: SwitchMaterial = view.findViewById(R.id.uhfSoundSwitch)
        val statusText: TextView = view.findViewById(R.id.uhfConfigStatusText)
        val saveApply: MaterialButton = view.findViewById(R.id.uhfSaveApplyButton)

        // Setup Driver Dropdown Adapter
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, driverOptions)
        driverSpinner.setAdapter(adapter)

        val cfg = UhfConfigPrefs.load(requireContext())
        val initialDriverId = cfg.driverId ?: "auto"
        val selectedOption = driverOptions.firstOrNull { it.driverId.equals(initialDriverId, ignoreCase = true) } ?: driverOptions.first()
        driverSpinner.setText(selectedOption.label, false)

        regionInput.setText(cfg.regionCode ?: "")
        powerInput.setText((cfg.powerDbm ?: 30).toString())
        soundSwitch.isChecked = cfg.soundEnabled

        statusText.text = "Current settings loaded. Select a driver profile and tap Save."

        saveApply.setOnClickListener {
            val cfgNow = UhfConfigPrefs.load(requireContext())
            val driverIdToSave = cfgNow.driverId ?: "auto"

            val region = regionInput.text?.toString()?.trim().orEmpty()
            val power = powerInput.text?.toString()?.trim()?.toIntOrNull()
            val sound = soundSwitch.isChecked

            if (power == null) {
                statusText.text = "Enter power as a whole number in dBm (e.g. 15 to 30)."
                return@setOnClickListener
            }

            val toSave = UhfConfig(
                regionCode = region.ifBlank { null },
                powerDbm = power,
                driverId = if (driverIdToSave == "auto") null else driverIdToSave,
                soundEnabled = sound
            )
            UhfConfigPrefs.save(requireContext(), toSave)
            statusText.text = "Applying antenna settings..."

            bg.execute {
                val driver = RfidDriverFactory.createDriver(toSave.driverId)
                val ok = try {
                    driver.init(requireContext().applicationContext)
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    driver.setPower(power)
                }
                try {
                    driver.release()
                } catch (_: Exception) {}

                view.post {
                    statusText.text = if (ok) {
                        "Settings saved: Antenna power set to ${power} dBm."
                    } else {
                        "Settings saved (Power: ${power} dBm)."
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        bg.shutdownNow()
        super.onDestroyView()
    }

    private fun ensureBg() {
        if (bg.isShutdown || bg.isTerminated) {
            bg = Executors.newSingleThreadExecutor()
        }
    }
}
