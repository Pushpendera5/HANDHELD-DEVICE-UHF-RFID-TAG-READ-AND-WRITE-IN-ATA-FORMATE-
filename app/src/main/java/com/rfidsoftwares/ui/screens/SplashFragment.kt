
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.navigation.fragment.findNavController
import com.rfidsoftwares.R
import com.rfidsoftwares.ui.base.BaseScreenFragment

class SplashFragment : BaseScreenFragment() {

    private val handler = Handler(Looper.getMainLooper())

    override fun screenTitle(): String = "OpenRFID Handheld"
    override fun screenSubtitle(): String? = "Preparing your universal suite"
    override fun isTopLevelScreen(): Boolean = true

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_splash, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val isLoggedIn = com.rfidsoftwares.common.prefs.LoginPrefs.isLoggedIn(requireContext())
        handler.postDelayed(
            {
                if (isLoggedIn) {
                    findNavController().navigate(R.id.action_splash_to_modeSelectorFragment)
                } else {
                    findNavController().navigate(R.id.action_splash_to_loginPageFragment)
                }
            },
            750
        )
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }
}
