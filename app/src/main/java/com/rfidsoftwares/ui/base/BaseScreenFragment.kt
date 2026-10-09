
package com.rfidsoftwares.ui.base

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfidsoftwares.R
import com.rfidsoftwares.common.prefs.LoginPrefs
import com.rfidsoftwares.common.prefs.ThemeModePrefs

/**
 * Shared app shell:
 * - standard single-line app bar
 * - explicit back navigation when appropriate
 * - quick access to issues and theme mode
 * - optional offline state panel
 * - consistent container for each screen's body view
 */
abstract class BaseScreenFragment : Fragment() {

    protected abstract fun screenTitle(): String
    protected open fun screenSubtitle(): String? = null
    protected open fun allowOfflinePanel(): Boolean = true
    protected open fun isTopLevelScreen(): Boolean = false
    protected open fun showHeader(): Boolean = true
    protected open fun handleBackNavigation() {
        findNavController().navigateUp()
    }

    abstract fun createBody(inflater: LayoutInflater, container: FrameLayout): View

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var offlinePanelRoot: View? = null
    private var offlinePanelDismissed: Boolean = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_screen_shell, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val headerView: View = view.findViewById(R.id.appHeader)
        val titleView: TextView = headerView.findViewById(R.id.appHeaderTitle)
        val subtitleView: TextView = headerView.findViewById(R.id.appHeaderSubtitle)
        val backButton: ImageButton = headerView.findViewById(R.id.appHeaderBackButton)
        val issueButton: ImageButton = headerView.findViewById(R.id.issueCenterButton)
        val themeButton: ImageButton = headerView.findViewById(R.id.themeModeButton)
        val logoutButton: ImageButton = headerView.findViewById(R.id.logoutButton)
        val actionSlot: FrameLayout = headerView.findViewById(R.id.appHeaderActionSlot)

        // Hide the entire header on screens that don't need it (e.g. login).
        headerView.visibility = if (showHeader()) View.VISIBLE else View.GONE

        titleView.text = screenTitle()
        subtitleView.visibility = View.GONE

        backButton.visibility = if (isTopLevelScreen()) View.GONE else View.VISIBLE
        backButton.setOnClickListener {
            handleBackNavigation()
        }

        actionSlot.visibility = View.GONE

        issueButton.setOnClickListener {
            val navController = findNavController()
            if (navController.currentDestination?.id != R.id.issueCenterFragment) {
                navController.navigate(R.id.action_global_issueCenterFragment)
            }
        }

        updateThemeButtonDescription(themeButton, ThemeModePrefs.isDarkEnabled(requireContext()))
        themeButton.setOnClickListener {
            val isDark = !ThemeModePrefs.isDarkEnabled(requireContext())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.theme_change_title)
                .setMessage(R.string.theme_change_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply) { _, _ ->
                    updateThemeButtonDescription(themeButton, isDark)
                    ThemeModePrefs.setDarkEnabled(requireContext(), isDark)
                    ThemeModePrefs.apply(requireContext())
                    activity?.recreate()
                }
                .show()
        }

        // Red tint for the logout/power-off icon
        logoutButton.setColorFilter(
            android.graphics.Color.parseColor("#EF4444"),
            android.graphics.PorterDuff.Mode.SRC_IN
        )

        logoutButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Yes") { _, _ ->
                    LoginPrefs.logout(requireContext())
                    val navOptions = androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(findNavController().graph.id, true)
                        .build()
                    findNavController().navigate(R.id.loginPageFragment, null, navOptions)
                }
                .setNegativeButton("No", null)
                .show()
        }

        val screenContainer: FrameLayout = view.findViewById(R.id.screenContainer)
        val inflater = layoutInflater

        // Render the screen body.
        val body = createBody(inflater, screenContainer)
        screenContainer.addView(body)

        // Offline state panel: shown on top of the content when needed.
        if (allowOfflinePanel()) {
            offlinePanelRoot = inflater.inflate(R.layout.view_state_panel, screenContainer, false)
            val offlineTitle: TextView = offlinePanelRoot!!.findViewById(R.id.statePanelTitle)
            val offlineMsg: TextView = offlinePanelRoot!!.findViewById(R.id.statePanelMessage)
            val actionBtn: Button = offlinePanelRoot!!.findViewById(R.id.statePanelActionButton)
            val closeBtn: Button = offlinePanelRoot!!.findViewById(R.id.statePanelCloseButton)

            offlineTitle.text = getString(R.string.offline_title)
            offlineMsg.text = getString(R.string.offline_message)
            actionBtn.text = getString(R.string.action_retry)
            actionBtn.setOnClickListener {
                offlinePanelDismissed = false
                val cm = connectivityManager
                val online = cm?.let { isCurrentlyOnline(it) } ?: false
                setOfflineVisible(view = offlinePanelRoot!!, isVisible = !online)
            }

            closeBtn.visibility = View.VISIBLE
            closeBtn.setOnClickListener {
                offlinePanelDismissed = true
                setOfflineVisible(view = offlinePanelRoot!!, isVisible = false)
            }

            // Add after the body so it visually overlays.
            screenContainer.addView(offlinePanelRoot)
            // Initialize based on actual connectivity; don't flash the offline warning.
            offlinePanelDismissed = false
            updateOfflineVisibilityAndStartMonitoring()
        }
    }

    override fun onDestroyView() {
        stopNetworkCallback()
        offlinePanelRoot = null
        super.onDestroyView()
    }

    private fun updateOfflineVisibilityAndStartMonitoring() {
        val cm = connectivityManager ?: return
        val online = isCurrentlyOnline(cm)
        setOfflineVisible(view = offlinePanelRoot!!, isVisible = !online && !offlinePanelDismissed)

        if (networkCallback != null) return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Network is back; allow the warning to show again on the next offline event.
                offlinePanelDismissed = false
                setOfflineVisible(view = offlinePanelRoot!!, isVisible = false)
            }

            override fun onLost(network: android.net.Network) {
                val onlineNow = isCurrentlyOnline(cm)
                offlinePanelDismissed = false
                setOfflineVisible(view = offlinePanelRoot!!, isVisible = !onlineNow && !offlinePanelDismissed)
            }
        }

        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {
            // If callbacks fail, keep last known state.
        }
    }

    private fun stopNetworkCallback() {
        val cm = connectivityManager ?: return
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
            // Defensive: ignore.
        }
    }

    private fun isCurrentlyOnline(cm: ConnectivityManager): Boolean {
        return try {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun setOfflineVisible(view: View, isVisible: Boolean) {
        view.visibility = if (isVisible) View.VISIBLE else View.GONE
    }

    private fun updateThemeButtonDescription(button: ImageButton, isDark: Boolean) {
        if (isDark) {
            button.setImageResource(R.drawable.ic_theme_sun)
            button.contentDescription = getString(R.string.action_switch_to_light)
        } else {
            button.setImageResource(R.drawable.ic_theme_moon)
            button.contentDescription = getString(R.string.action_switch_to_dark)
        }
    }
}

