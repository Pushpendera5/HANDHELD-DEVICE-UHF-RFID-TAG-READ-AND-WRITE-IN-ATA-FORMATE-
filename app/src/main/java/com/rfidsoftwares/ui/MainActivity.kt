
package com.rfidsoftwares.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import com.rfidsoftwares.R
import com.rfidsoftwares.common.prefs.ThemeModePrefs

interface HardwareKeyHandler {
    fun onHardwareKeyDown(keyCode: Int, event: KeyEvent): Boolean
    fun onHardwareKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
}

class MainActivity : AppCompatActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply persisted theme before activity inflates any views.
        ThemeModePrefs.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(com.rfidsoftwares.R.layout.activity_main)

        checkAndRequestBluetoothPermissions()
    }

    private fun checkAndRequestBluetoothPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        setZebraDataWedgeState(enabled = false)
    }

    override fun onPause() {
        setZebraDataWedgeState(enabled = true)
        super.onPause()
    }

    private fun setZebraDataWedgeState(enabled: Boolean) {
        try {
            val intent = android.content.Intent("com.symbol.datawedge.api.ACTION").apply {
                putExtra("com.symbol.datawedge.api.ENABLE_DATAWEDGE", enabled)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("MainActivity", "dispatchKeyEvent: action=${event.action}, keyCode=${event.keyCode}")
        val navHost = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment
        val currentFragment = navHost?.childFragmentManager?.fragments?.firstOrNull { it.isResumed && it.isVisible }
            ?: navHost?.childFragmentManager?.primaryNavigationFragment
        if (currentFragment is HardwareKeyHandler) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (currentFragment.onHardwareKeyDown(event.keyCode, event)) return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (currentFragment.onHardwareKeyUp(event.keyCode, event)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

