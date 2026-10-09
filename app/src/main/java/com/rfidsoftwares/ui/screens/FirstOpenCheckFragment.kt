
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.rfidsoftwares.R
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.common.prefs.AppPrefs
import com.rfidsoftwares.ui.base.BaseScreenFragment

class FirstOpenCheckFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Welcome"
    override fun screenSubtitle(): String? = "Checking setup"
    override fun isTopLevelScreen(): Boolean = true

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        val loading = inflater.inflate(R.layout.view_state_loading, container, false)
        val msg = loading.findViewById<TextView>(R.id.loadingMessage)
        msg.text = "Checking whether this is your first visit..."
        return loading
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = AppPrefs(requireContext())
        val firstOpen = prefs.isFirstOpen()

        if (firstOpen && AppConfig.LANDING_SLIDES_ENABLED) {
            findNavController().navigate(R.id.action_firstOpenCheck_to_landingSlidesFragment)
        } else {
            prefs.markFirstOpenDone()
            findNavController().navigate(R.id.action_firstOpenCheck_to_loginPageFragment)
        }
    }
}

