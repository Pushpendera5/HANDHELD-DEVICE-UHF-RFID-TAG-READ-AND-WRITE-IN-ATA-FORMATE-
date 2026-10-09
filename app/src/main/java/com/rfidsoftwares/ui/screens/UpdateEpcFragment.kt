
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.rfidsoftwares.R
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.common.sound.SoundBeeper
import com.rfidsoftwares.controller.epcupdate.UpdateEpcConfig
import com.rfidsoftwares.controller.epcupdate.UpdateEpcFormat
import com.rfidsoftwares.controller.epcupdate.UpdateEpcSessionPlanner
import com.rfidsoftwares.rfid.driver.MemoryBank
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.rfid.driver.RfidReaderDriver
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UpdateEpcFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Update EPC"
    override fun screenSubtitle(): String? = "Batch rewrite scanned tag EPCs with prefix and serial numbers"
    override fun allowOfflinePanel(): Boolean = false

    private var bg = Executors.newSingleThreadExecutor()
    private var driver: RfidReaderDriver? = null
    private var isDriverReady = false
    private val isSessionActive = AtomicBoolean(false)

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_update_epc, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBg()

        val prefixInput: TextInputEditText = view.findViewById(R.id.updateEpcPrefixInput)
        val serialInput: TextInputEditText = view.findViewById(R.id.updateEpcSerialInput)
        val formatGroup: RadioGroup = view.findViewById(R.id.updateEpcFormatGroup)
        val previewText: TextView = view.findViewById(R.id.updateEpcPreviewText)
        val currentPwdInput: TextInputEditText = view.findViewById(R.id.updateEpcCurrentPwdInput)
        val newPwdInput: TextInputEditText = view.findViewById(R.id.updateEpcNewPwdInput)
        val startBtn: MaterialButton = view.findViewById(R.id.updateEpcStartButton)
        val stopBtn: MaterialButton = view.findViewById(R.id.updateEpcStopButton)
        val statusText: TextView = view.findViewById(R.id.updateEpcStatusText)
        val countersText: TextView = view.findViewById(R.id.updateEpcCountersText)
        val lastText: TextView = view.findViewById(R.id.updateEpcLastText)

        prefixInput.setText("4142") // "AB" in HEX
        serialInput.setText("1001")
        formatGroup.check(R.id.updateEpc96Radio)

        fun updatePreview() {
            val prefix = prefixInput.text?.toString()?.trim().orEmpty()
            val serial = serialInput.text?.toString()?.trim().orEmpty()
            val format = if (formatGroup.checkedRadioButtonId == R.id.updateEpc128Radio) UpdateEpcFormat.EPC_128 else UpdateEpcFormat.EPC_96
            val preview = runCatching {
                val cfg = UpdateEpcConfig.parse(prefix.ifBlank { "00" }, serial.ifBlank { "1" }, format)
                val planner = UpdateEpcSessionPlanner(cfg)
                planner.nextEpc()
            }.getOrDefault("-")
            previewText.text = "Next EPC preview: $preview"
        }

        prefixInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
            override fun afterTextChanged(s: Editable?) {}
        })

        serialInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
            override fun afterTextChanged(s: Editable?) {}
        })

        formatGroup.setOnCheckedChangeListener { _, _ -> updatePreview() }
        updatePreview()

        statusText.text = "Initializing reader hardware..."

        // Eager Hardware Reader Initialization on Fragment Entry
        bg.execute {
            val d = RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId)
            driver = d
            val ok = try {
                d.init(requireContext().applicationContext)
            } catch (e: Exception) {
                false
            }
            isDriverReady = ok
            view.post {
                if (ok) {
                    statusText.text = "Reader ready (${d.displayName})."
                } else {
                    statusText.text = "Hardware init failed (${d.displayName})."
                }
            }
        }

        startBtn.setOnClickListener {
            if (isSessionActive.get()) {
                statusText.text = "Session already active."
                return@setOnClickListener
            }

            val prefix = prefixInput.text?.toString()?.trim().orEmpty()
            val serial = serialInput.text?.toString()?.trim().orEmpty()
            val format = if (formatGroup.checkedRadioButtonId == R.id.updateEpc128Radio) UpdateEpcFormat.EPC_128 else UpdateEpcFormat.EPC_96
            val pwd = currentPwdInput.text?.toString()?.trim()

            val config = runCatching {
                UpdateEpcConfig.parse(prefix, serial, format)
            }.getOrElse {
                statusText.text = "Configuration Error: ${it.message}"
                return@setOnClickListener
            }

            isSessionActive.set(true)
            statusText.text = "Batch EPC update session running..."

            bg.execute {
                val planner = UpdateEpcSessionPlanner(config)
                var updatedCount = 0
                var ignoredCount = 0
                var failedCount = 0

                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                }
                if (!isDriverReady) {
                    isSessionActive.set(false)
                    view.post { statusText.text = "Reader init failed: Check hardware." }
                    return@execute
                }

                d.startInventory()
                while (isSessionActive.get()) {
                    val events = d.readBufferedTagEvents()
                    for (event in events) {
                        if (planner.shouldIgnore(event.epc)) {
                            ignoredCount++
                            continue
                        }

                        val targetEpc = planner.nextEpc()
                        // Gen2 EPC memory bank starts at wordPtr 2
                        val ok = d.writeMemoryBank(
                            epc = event.epc,
                            bank = MemoryBank.EPC,
                            ptr = 2,
                            dataHex = targetEpc,
                            pwdHex = pwd
                        )

                        if (ok) {
                            updatedCount++
                            planner.markUpdated(event.epc, targetEpc)
                            if (UhfConfigPrefs.load(requireContext().applicationContext).soundEnabled) {
                                SoundBeeper.playBeep()
                            }
                            view.post {
                                countersText.text = "Updated: $updatedCount  Ignored: $ignoredCount  Failed: $failedCount"
                                lastText.text = "${event.epc} -> $targetEpc"
                                serialInput.setText(planner.nextSerialText())
                            }
                        } else {
                            failedCount++
                            view.post {
                                countersText.text = "Updated: $updatedCount  Ignored: $ignoredCount  Failed: $failedCount"
                                lastText.text = "Failed to update ${event.epc}"
                            }
                        }
                    }
                    Thread.sleep(100)
                }
                d.stopInventory()
            }
        }

        stopBtn.setOnClickListener {
            isSessionActive.set(false)
            statusText.text = "Batch EPC update session stopped."
        }
    }

    override fun onDestroyView() {
        isSessionActive.set(false)
        try {
            driver?.stopInventory()
            driver?.release()
        } catch (_: Exception) {
        }
        driver = null
        isDriverReady = false
        bg.shutdownNow()
        super.onDestroyView()
    }

    private fun ensureBg() {
        if (bg.isShutdown || bg.isTerminated) {
            bg = Executors.newSingleThreadExecutor()
        }
    }
}
