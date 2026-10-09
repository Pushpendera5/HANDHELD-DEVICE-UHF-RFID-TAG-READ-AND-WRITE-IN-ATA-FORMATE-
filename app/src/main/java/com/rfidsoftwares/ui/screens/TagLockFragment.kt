
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.rfidsoftwares.R
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.common.sound.SoundBeeper
import com.rfidsoftwares.hardware.HardwareTriggerHelper
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.rfid.driver.RfidReaderDriver
import com.rfidsoftwares.ui.HardwareKeyHandler
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.util.concurrent.Executors

class TagLockFragment : BaseScreenFragment(), HardwareKeyHandler {

    override fun screenTitle(): String = "Tag Security"
    override fun screenSubtitle(): String? = null
    override fun allowOfflinePanel(): Boolean = false

    private var bg = Executors.newSingleThreadExecutor()
    private var driver: RfidReaderDriver? = null
    private var isDriverReady = false

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_tag_lock, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBg()

        val epcInput: TextInputEditText = view.findViewById(R.id.tagLockEpcInput)
        val pwdInput: TextInputEditText = view.findViewById(R.id.tagLockAccessPwdInput)
        val statusText: TextView = view.findViewById(R.id.tagLockStatusText)
        val scanBtn: MaterialButton = view.findViewById(R.id.tagLockScanButton)
        val lockBtn: MaterialButton = view.findViewById(R.id.tagLockButton)
        val unlockBtn: MaterialButton = view.findViewById(R.id.tagUnlockButton)

        // Pre-populate target EPC if passed from navigation
        val prefilledEpc = arguments?.getString("target_epc") ?: arguments?.getString("epc")
        if (!prefilledEpc.isNullOrBlank()) {
            epcInput.setText(prefilledEpc)
            statusText.text = "Target tag loaded: $prefilledEpc"
        }

        scanBtn.setOnClickListener {
            scanBtn.isEnabled = false
            scanBtn.text = "Scanning..."
            statusText.text = "Scanning nearest tag..."

            bg.execute {
                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                }
                if (!isDriverReady) {
                    view.post {
                        scanBtn.isEnabled = true
                        scanBtn.text = "Scan Target Tag"
                        statusText.text = "Reader init failed: Check connection."
                    }
                    return@execute
                }

                val snap = runCatching {
                    d.startInventory()
                    Thread.sleep(350)
                    val tags = d.readBufferedTagEvents()
                    d.stopInventory()
                    tags.maxByOrNull { it.rssi ?: -999 } ?: tags.firstOrNull()
                }.getOrNull()

                val epc = snap?.epc
                view.post {
                    scanBtn.isEnabled = true
                    scanBtn.text = "Scan Target Tag"
                    if (!epc.isNullOrBlank()) {
                        if (UhfConfigPrefs.load(requireContext()).soundEnabled) {
                            SoundBeeper.playBeep()
                        }
                        epcInput.setText(epc)
                        statusText.text = "Target tag scanned: $epc"
                    } else {
                        statusText.text = "No tag found. Move closer and try again."
                    }
                }
            }
        }

        lockBtn.setOnClickListener {
            val epc = epcInput.text?.toString()?.trim()
            if (epc.isNullOrBlank()) {
                statusText.text = "Please scan or enter a target EPC first."
                return@setOnClickListener
            }

            val pwd = pwdInput.text?.toString()?.trim()
            lockBtn.isEnabled = false
            unlockBtn.isEnabled = false
            statusText.text = "Sending lock command to tag $epc..."

            bg.execute {
                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                }
                if (!isDriverReady) {
                    view.post {
                        lockBtn.isEnabled = true
                        unlockBtn.isEnabled = true
                        statusText.text = "Reader init failed: Check connection."
                    }
                    return@execute
                }

                val ok = runCatching {
                    d.setLockState(epc = epc, accessPasswordHex = pwd, lock = true)
                }.getOrElse { false }

                view.post {
                    lockBtn.isEnabled = true
                    unlockBtn.isEnabled = true
                    if (ok) {
                        if (UhfConfigPrefs.load(requireContext()).soundEnabled) {
                            SoundBeeper.playBeep()
                        }
                        statusText.text = "Tag successfully locked: $epc"
                    } else {
                        statusText.text = "Lock failed. Ensure tag is in range and password is correct."
                    }
                }
            }
        }

        unlockBtn.setOnClickListener {
            val epc = epcInput.text?.toString()?.trim()
            if (epc.isNullOrBlank()) {
                statusText.text = "Please scan or enter a target EPC first."
                return@setOnClickListener
            }

            val pwd = pwdInput.text?.toString()?.trim()
            lockBtn.isEnabled = false
            unlockBtn.isEnabled = false
            statusText.text = "Sending unlock command to tag $epc..."

            bg.execute {
                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                }
                if (!isDriverReady) {
                    view.post {
                        lockBtn.isEnabled = true
                        unlockBtn.isEnabled = true
                        statusText.text = "Reader init failed: Check connection."
                    }
                    return@execute
                }

                val ok = runCatching {
                    d.setLockState(epc = epc, accessPasswordHex = pwd, lock = false)
                }.getOrElse { false }

                view.post {
                    lockBtn.isEnabled = true
                    unlockBtn.isEnabled = true
                    if (ok) {
                        if (UhfConfigPrefs.load(requireContext()).soundEnabled) {
                            SoundBeeper.playBeep()
                        }
                        statusText.text = "Tag successfully unlocked: $epc"
                    } else {
                        statusText.text = "Unlock failed. Ensure tag is in range and password is correct."
                    }
                }
            }
        }
    }

    override fun onHardwareKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!HardwareTriggerHelper.isTriggerKey(keyCode)) return false
        if (event.repeatCount > 0) return true
        activity?.runOnUiThread {
            view?.findViewById<MaterialButton>(R.id.tagLockScanButton)?.performClick()
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        HardwareTriggerHelper.triggerListener = { pressed ->
            if (pressed) {
                activity?.runOnUiThread {
                    view?.findViewById<MaterialButton>(R.id.tagLockScanButton)?.performClick()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        HardwareTriggerHelper.triggerListener = null
    }

    override fun onDestroyView() {
        try {
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
