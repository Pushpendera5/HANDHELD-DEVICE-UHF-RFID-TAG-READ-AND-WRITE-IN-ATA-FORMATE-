
package com.rfidsoftwares.ui.screens

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.rfidsoftwares.R
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.ui.base.BaseScreenFragment

class ModeSelectorFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "OpenRFID Suite"
    override fun screenSubtitle(): String? = "Choose an operation mode to start"
    override fun isTopLevelScreen(): Boolean = true
    override fun allowOfflinePanel(): Boolean = false

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_mode_selector, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cardOpenRfidSuite: View? = view.findViewById(R.id.cardOpenRfidSuite)
        val appHeader: View? = requireActivity().findViewById(R.id.appHeader)

        // Triple-tap to navigate to separate Zebra RFID Connector / UHF Settings page
        var tapCount = 0
        var lastTapTime = 0L

        val tripleTapListener = View.OnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime > 1500) {
                tapCount = 0
            }
            lastTapTime = now
            tapCount++

            if (tapCount == 2) {
                Toast.makeText(context, "Tap 1 more time to open Zebra RFID Connector", Toast.LENGTH_SHORT).show()
            } else if (tapCount >= 3) {
                tapCount = 0
                findNavController().navigate(R.id.action_modeSelector_to_uhfConfigFragment)
            }
        }

        cardOpenRfidSuite?.setOnClickListener(tripleTapListener)
        appHeader?.setOnClickListener(tripleTapListener)
        requireActivity().findViewById<View?>(R.id.appHeaderTitle)?.setOnClickListener(tripleTapListener)

        // RFID Operations
        view.findViewById<MaterialCardView>(R.id.btnQuickReadTag).setOnClickListener {
            findNavController().navigate(R.id.action_modeSelector_to_readTagFragment)
        }

        view.findViewById<MaterialCardView>(R.id.btnQuickWriteTag).setOnClickListener {
            findNavController().navigate(R.id.action_modeSelector_to_writeTagFragment)
        }

        view.findViewById<MaterialCardView>(R.id.btnQuickTagLock).setOnClickListener {
            findNavController().navigate(R.id.action_modeSelector_to_tagLockFragment)
        }

        view.findViewById<MaterialCardView>(R.id.btnQuickDiagnostics).setOnClickListener {
            findNavController().navigate(R.id.action_modeSelector_to_diagnosticsFragment)
        }
    }
}
