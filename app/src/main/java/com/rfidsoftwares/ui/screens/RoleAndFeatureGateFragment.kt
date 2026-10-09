
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import com.rfidsoftwares.R
import com.rfidsoftwares.ui.base.BaseScreenFragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController

class RoleAndFeatureGateFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Opening Dashboard"
    override fun screenSubtitle(): String? = "Choose the workspace you need for this session"
    override fun isTopLevelScreen(): Boolean = true

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_role_feature_gate, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adminButton: MaterialButton = view.findViewById(R.id.enterAsAdminButton)
        val userButton: MaterialButton = view.findViewById(R.id.enterAsUserButton)
        val navOptions = NavOptions.Builder()
            .setPopUpTo(R.id.roleAndFeatureGateFragment, true)
            .build()

        adminButton.setOnClickListener {
            findNavController().navigate(
                R.id.action_roleGate_to_adminDashboardFragment,
                null,
                navOptions
            )
        }
        userButton.setOnClickListener {
            findNavController().navigate(
                R.id.action_roleGate_to_userDashboardFragment,
                null,
                navOptions
            )
        }
    }
}

