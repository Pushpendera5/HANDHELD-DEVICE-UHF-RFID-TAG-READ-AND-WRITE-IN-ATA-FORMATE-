
package com.rfidsoftwares.ui.screens
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.rfidsoftwares.R
import androidx.navigation.fragment.findNavController
import com.rfidsoftwares.common.prefs.AppPrefs
import com.rfidsoftwares.ui.base.BaseScreenFragment
import android.widget.Button

class LandingSlidesFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Welcome"
    override fun screenSubtitle(): String? = "A quick tour before you start"
    override fun isTopLevelScreen(): Boolean = true

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        val body = inflater.inflate(R.layout.body_landing_slides, container, false)
        val continueBtn = body.findViewById<Button>(R.id.landingContinueButton)
        continueBtn.setOnClickListener {
            // First-run landing slides are shown only once.
            val prefs = AppPrefs(requireContext())
            prefs.markFirstOpenDone()
            findNavController().navigate(R.id.action_landingSlides_to_loginPageFragment)
        }
        return body
    }
}

