
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfidsoftwares.R
import com.rfidsoftwares.common.auth.ActiveProviderStore
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.common.prefs.AppPrefs
import com.rfidsoftwares.ui.base.BaseScreenFragment

class UserDashboardFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "User Dashboard"
    override fun screenSubtitle(): String? = "Your day-to-day scan and lookup tools"
    override fun isTopLevelScreen(): Boolean = true

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        val body = inflater.inflate(R.layout.body_dashboard, container, false)
        val providerStatus: TextView = body.findViewById(R.id.dashboardProviderStatusText)
        val featureList: LinearLayout = body.findViewById(R.id.dashboardFeatureList)

        val providerId = ActiveProviderStore.activeProviderId
            ?: AppConfig.ProviderRegistry.providers.first().providerId
        val providerDisplay = AppConfig.ProviderRegistry.getById(providerId)?.displayName ?: providerId
        providerStatus.text = "Connected to $providerDisplay"

        featureList.addView(
            featureRow(
                inflater = inflater,
                title = "Issue Center",
                subtitle = "Review problems and recommended actions",
                enabled = true
            ) { findNavController().navigate(R.id.action_global_issueCenterFragment) }
        )

        addFeatureRows(inflater, featureList)
        return body
    }

    private fun addFeatureRows(inflater: LayoutInflater, featureList: LinearLayout) {
        val features = AppConfig.FeatureVisibility.featuresForRole(AppConfig.AppRole.USER)

        if (AppConfig.FeatureKeys.INVENTORY_SYNC in features) {
            featureList.addView(
                featureRow(inflater, "Inventory Sync", "Start or resume a stock take session") {
                    findNavController().navigate(R.id.action_userDashboard_to_inventorySyncFragment)
                }
            )
        }
        if (AppConfig.FeatureKeys.FIND_PRODUCT in features) {
            featureList.addView(
                featureRow(inflater, "Find Product", "Search cached products and locate EPCs") {
                    findNavController().navigate(R.id.action_userDashboard_to_findProductFragment)
                }
            )
        }
        if (AppConfig.FeatureKeys.CHECKOUT_BILLING in features) {
            featureList.addView(
                featureRow(inflater, "Checkout & Billing", "Build a cart and continue checkout") {
                    findNavController().navigate(R.id.action_userDashboard_to_checkoutBillingFragment)
                }
            )
        }
        if (AppConfig.FeatureKeys.ANTI_THEFT in features) {
            featureList.addView(
                featureRow(inflater, "Anti-Theft", "Scan the floor and verify active tags") {
                    findNavController().navigate(R.id.action_userDashboard_to_antiTheftFragment)
                }
            )
        }
        if (AppConfig.FeatureKeys.DIAGNOSTICS in features) {
            featureList.addView(
                featureRow(inflater, "Diagnostics", "Check reader, network, and sync status") {
                    findNavController().navigate(R.id.action_userDashboard_to_diagnosticsFragment)
                }
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!AppConfig.DashboardFirstOpenPromptConfig.ENABLED) return

        val prefs = AppPrefs(requireContext())
        val role = AppConfig.AppRole.USER
        if (prefs.isDashboardFirstOpenPromptSeen(role)) return

        val runLabel = "Start inventory"
        val laterLabel = AppConfig.DashboardFirstOpenPromptConfig.LATER_LABEL

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Start your first task")
            .setMessage("Would you like to open Inventory Sync now?")
            .setPositiveButton(runLabel) { _, _ ->
                prefs.markDashboardFirstOpenPromptSeen(role)
                findNavController().navigate(R.id.action_userDashboard_to_inventorySyncFragment)
            }
            .setNegativeButton(laterLabel) { _, _ ->
                prefs.markDashboardFirstOpenPromptSeen(role)
            }
            .setCancelable(true)
            .show()
    }

    private fun featureRow(
        inflater: LayoutInflater,
        title: String,
        subtitle: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): View {
        val v = inflater.inflate(R.layout.view_feature_entry_row, null, false)
        val t: TextView = v.findViewById(R.id.featureRowTitle)
        val s: TextView = v.findViewById(R.id.featureRowSubtitle)
        val btn: MaterialButton = v.findViewById(R.id.featureRowButton)
        t.text = title
        s.text = subtitle
        btn.isEnabled = enabled
        btn.text = if (enabled) "Open" else "Unavailable"
        v.isEnabled = enabled
        v.alpha = if (enabled) 1.0f else 0.45f
        btn.setOnClickListener { if (enabled) onClick() }
        return v
    }
}

