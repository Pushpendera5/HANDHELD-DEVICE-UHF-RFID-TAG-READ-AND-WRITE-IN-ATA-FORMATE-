
package com.rfidsoftwares.ui.screens

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.ViewGroup
import com.rfidsoftwares.R
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.common.prefs.AppPrefs
import com.rfidsoftwares.common.auth.ActiveProviderStore
import com.rfidsoftwares.common.config.FeatureFlags
import com.rfidsoftwares.data.local.AuditRetentionPolicy
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.integration.BackendAdapterProvider
import com.rfidsoftwares.integration.usecases.CatalogSyncUseCase
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.util.concurrent.Executors

class AdminDashboardFragment : BaseScreenFragment() {
    private var bg = Executors.newSingleThreadExecutor()

    private fun ensureBg() {
        // Fragment views can be destroyed/recreated while the Fragment instance stays alive.
        // If we shutdown the executor in onDestroyView, we must recreate it before submitting new work.
        if (bg.isShutdown || bg.isTerminated) {
            bg = Executors.newSingleThreadExecutor()
        }
    }

    override fun screenTitle(): String = "Admin Dashboard"
    override fun screenSubtitle(): String? = "Overview, scanning, support, and admin tools"
    override fun isTopLevelScreen(): Boolean = true

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        val body = inflater.inflate(R.layout.body_admin_dashboard, container, false)

        val providerId = ActiveProviderStore.activeProviderId
            ?: AppConfig.ProviderRegistry.providers.first().providerId
        val providerDisplay = AppConfig.ProviderRegistry.getById(providerId)?.displayName ?: providerId

        // Provider status.
        val providerStatusValue: TextView = body.findViewById(R.id.adminProviderStatusValue)
        providerStatusValue.text = "Connected to $providerDisplay"

        val issuesCard: MaterialCardView = body.findViewById(R.id.adminStatCardIssues)
        issuesCard.findViewById<TextView>(R.id.dashboardStatValue).text = "0"
        issuesCard.findViewById<TextView>(R.id.dashboardStatLabel).text = "Open issues"
        issuesCard.setOnClickListener {
            findNavController().navigate(R.id.action_global_issueCenterFragment)
        }

        val queueCard: MaterialCardView = body.findViewById(R.id.adminStatCardQueue)
        queueCard.findViewById<TextView>(R.id.dashboardStatValue).text = "0"
        queueCard.findViewById<TextView>(R.id.dashboardStatLabel).text = "Products cached"
        queueCard.setOnClickListener {
            findNavController().navigate(R.id.action_adminDashboard_to_findProductFragment)
        }

        val epcsCard: MaterialCardView = body.findViewById(R.id.adminStatCardEpcs)
        epcsCard.visibility = View.GONE

        val features = AppConfig.FeatureVisibility.featuresForRole(AppConfig.AppRole.ADMIN)
        val actionsContainer: LinearLayout = body.findViewById(R.id.adminActionRowsContainer)
        val rowSpacingPx = resources.getDimensionPixelSize(R.dimen.rfid_list_item_spacing)
        val columnGapPx = rowSpacingPx

        val actions: List<DashboardAction> = listOf(
            DashboardAction(
                title = "Start Task List",
                subtitle = "Open the task launcher and pick a workflow",
                iconRes = android.R.drawable.ic_menu_agenda,
                enabled = true,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_inventorySyncFragment) }
            ),
            DashboardAction(
                title = "Inventory Sync",
                subtitle = "Start or resume a stock take session",
                iconRes = android.R.drawable.ic_menu_recent_history,
                enabled = AppConfig.FeatureKeys.INVENTORY_SYNC in features,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_inventorySyncFragment) }
            ),
            DashboardAction(
                title = "Missing Items",
                subtitle = "View and locate tags that were not found",
                iconRes = android.R.drawable.ic_menu_search,
                enabled = true,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_missingItemsFragment) }
            ),
            DashboardAction(
                title = "Find Product",
                subtitle = "Search cached products and locate EPCs",
                iconRes = android.R.drawable.ic_menu_search,
                enabled = AppConfig.FeatureKeys.FIND_PRODUCT in features,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_findProductFragment) }
            ),
            DashboardAction(
                title = "Register EPC",
                subtitle = "Assign a tag to a product record",
                iconRes = android.R.drawable.ic_input_add,
                enabled = AppConfig.FeatureKeys.REGISTER_EPC in features,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_registerEpcFragment) }
            ),
            DashboardAction(
                title = "Checkout & Billing",
                subtitle = "Build a cart and continue checkout",
                iconRes = android.R.drawable.ic_menu_send,
                enabled = AppConfig.FeatureKeys.CHECKOUT_BILLING in features,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_checkoutBillingFragment) }
            ),
            DashboardAction(
                title = "Anti-Theft",
                subtitle = "Scan the floor and verify active tags",
                iconRes = android.R.drawable.ic_lock_lock,
                enabled = AppConfig.FeatureKeys.ANTI_THEFT in features,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_antiTheftFragment) }
            ),
            DashboardAction(
                title = "Diagnostics",
                subtitle = "Check reader, network, and sync status",
                iconRes = android.R.drawable.ic_menu_info_details,
                enabled = AppConfig.FeatureKeys.DIAGNOSTICS in features,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_diagnosticsFragment) }
            ),
            DashboardAction(
                title = "UHF Config",
                subtitle = "Adjust reader region and power",
                iconRes = android.R.drawable.ic_menu_manage,
                enabled = true,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_uhfConfigFragment) }
            ),
            DashboardAction(
                title = "Read Tag",
                subtitle = "Scan and read EPC/TID/USER memory",
                iconRes = android.R.drawable.ic_menu_view,
                enabled = true,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_readTagFragment) }
            ),
            DashboardAction(
                title = "Write Tag",
                subtitle = "Write tag data and manage password lock",
                iconRes = android.R.drawable.ic_menu_edit,
                enabled = true,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_writeTagFragment) }
            ),
            DashboardAction(
                title = "Role Management",
                subtitle = "Review access management options",
                iconRes = android.R.drawable.ic_menu_manage,
                enabled = AppConfig.FeatureKeys.ROLE_MANAGEMENT in features,
                onClick = { findNavController().navigate(R.id.action_adminDashboard_to_roleManagementFragment) }
            ),
            DashboardAction(
                title = "Issue Center",
                subtitle = "Review problems and recommended actions",
                iconRes = R.drawable.ic_card_diagnostics,
                enabled = true,
                onClick = { findNavController().navigate(R.id.action_global_issueCenterFragment) }
            ),
        )

        for (i in actions.indices step 2) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (i == 0) 0 else rowSpacingPx
                }
            }

            val left = buildActionCard(inflater, actions[i])
            row.addView(
                left,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = columnGapPx
                }
            )

            val right = if (i + 1 < actions.size) buildActionCard(inflater, actions[i + 1]) else null
            if (right != null) {
                row.addView(
                    right,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = columnGapPx
                    }
                )
            }

            actionsContainer.addView(row)
        }

        return body
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBg()

        refreshStats(view)

        if (!AppConfig.DashboardFirstOpenPromptConfig.ENABLED) return

        val prefs = AppPrefs(requireContext())
        val role = AppConfig.AppRole.ADMIN
        if (prefs.isDashboardFirstOpenPromptSeen(role)) return

        val runLabel = "Start inventory"
        val laterLabel = AppConfig.DashboardFirstOpenPromptConfig.LATER_LABEL

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Start your first task")
            .setMessage("Would you like to open Inventory Sync now?")
            .setPositiveButton(runLabel) { _, _ ->
                prefs.markDashboardFirstOpenPromptSeen(role)
                findNavController().navigate(R.id.action_adminDashboard_to_inventorySyncFragment)
            }
            .setNegativeButton(laterLabel) { _, _ ->
                prefs.markDashboardFirstOpenPromptSeen(role)
            }
            .setCancelable(true)
            .show()
    }

    private fun refreshStats(rootView: View) {
        val providerId =
            ActiveProviderStore.activeProviderId ?: AppConfig.ProviderRegistry.providers.first().providerId
        val db = RfidSessionDbProvider.getInstance(requireContext())

        bg.execute {
            val initialProductsCount = db.productDao().getProducts(providerId).size
            if (initialProductsCount == 0 && FeatureFlags.TEST_MODE_ENABLED) {
                val adapter = BackendAdapterProvider.getAdapter(providerId)
                CatalogSyncUseCase(adapter).syncCatalog(providerConnectionId = providerId, db = db)
            }

            AuditRetentionPolicy.enforce(db)
            val unresolvedIssuesCount = db.issueDao().listActive().size
            val productsCount = db.productDao().getProducts(providerId).size

            rootView.post {
                val issuesCard: MaterialCardView? = rootView.findViewById(R.id.adminStatCardIssues)
                val queueCard: MaterialCardView? = rootView.findViewById(R.id.adminStatCardQueue)
                issuesCard?.findViewById<TextView>(R.id.dashboardStatValue)?.text =
                    unresolvedIssuesCount.toString()
                queueCard?.findViewById<TextView>(R.id.dashboardStatValue)?.text =
                    productsCount.toString()
            }
        }
    }

    override fun onDestroyView() {
        bg.shutdownNow()
        super.onDestroyView()
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
        val btn: com.google.android.material.button.MaterialButton = v.findViewById(R.id.featureRowButton)
        t.text = title
        s.text = subtitle
        btn.isEnabled = enabled
        btn.text = if (enabled) "Open" else "Unavailable"
        v.isEnabled = enabled
        v.alpha = if (enabled) 1.0f else 0.45f
        btn.setOnClickListener { if (enabled) onClick() }
        return v
    }

    private data class DashboardAction(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val enabled: Boolean,
        val onClick: () -> Unit,
    )

    private fun buildActionCard(inflater: LayoutInflater, action: DashboardAction): MaterialCardView {
        val card = inflater.inflate(R.layout.view_dashboard_action_card, null, false) as MaterialCardView

        val icon: ImageView = card.findViewById(R.id.dashboardActionIcon)
        val title: TextView = card.findViewById(R.id.dashboardActionTitle)
        val subtitle: TextView = card.findViewById(R.id.dashboardActionSubtitle)

        icon.setImageResource(action.iconRes)
        title.text = action.title
        subtitle.text = action.subtitle

        card.isEnabled = action.enabled
        card.alpha = if (action.enabled) 1.0f else 0.45f
        card.isClickable = action.enabled
        card.isFocusable = action.enabled

        card.setOnClickListener {
            if (action.enabled) action.onClick()
        }

        return card
    }
}
