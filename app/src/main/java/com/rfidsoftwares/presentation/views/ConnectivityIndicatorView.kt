
package com.rfidsoftwares.presentation.views

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.core.content.ContextCompat
import com.rfidsoftwares.R

/**
 * Lightweight always-visible network status indicator (Phase 1 UX contract).
 *
 * Updates when connectivity changes and renders a dot + label.
 */
class ConnectivityIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var dotView: View? = null
    private var textView: TextView? = null

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_connectivity_indicator, this, true)
        dotView = findViewById(R.id.connectivityIndicatorDot)
        textView = findViewById(R.id.connectivityIndicatorText)
        // Avoid showing an incorrect "Offline" flash on the first frame.
        val initialOnline = connectivityManager?.let { isCurrentlyOnline(it) } ?: true
        setState(isOnline = initialOnline)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startMonitoring()
    }

    override fun onDetachedFromWindow() {
        stopMonitoring()
        super.onDetachedFromWindow()
    }

    private fun startMonitoring() {
        val cm = connectivityManager ?: return

        // Capture current state immediately.
        setState(isOnline = isCurrentlyOnline(cm))

        if (callback != null) return

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                setState(isOnline = true)
            }

            override fun onLost(network: android.net.Network) {
                // onLost can fire even if another network is active; recompute.
                setState(isOnline = isCurrentlyOnline(cm))
            }
        }

        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, callback!!)
        } catch (_: Exception) {
            // If the device restricts callbacks, keep the last known state.
        }
    }

    private fun stopMonitoring() {
        val cm = connectivityManager ?: return
        val cb = callback ?: return
        callback = null
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
            // Defensive: unregister can throw if never registered.
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

    private fun setState(isOnline: Boolean) {
        val dot = dotView ?: return
        val text = textView ?: return

        val dotColorRes = if (isOnline) R.color.rfid_online else R.color.rfid_offline
        dot.background?.setTint(ContextCompat.getColor(context, dotColorRes))

        text.text = if (isOnline) {
            context.getString(R.string.status_online)
        } else {
            context.getString(R.string.status_offline)
        }
        contentDescription = text.text
        dot.alpha = if (isOnline) 1.0f else 0.9f
    }
}

