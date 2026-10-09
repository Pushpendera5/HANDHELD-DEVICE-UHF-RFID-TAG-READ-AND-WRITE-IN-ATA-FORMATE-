
package com.rfidsoftwares.common.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

object ThemeModePrefs {
    private const val PREFS_NAME = "rfid_inventory_theme_prefs"
    private const val KEY_DARK_ENABLED = "dark_enabled"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isDarkEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DARK_ENABLED, false)
    }

    fun setDarkEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK_ENABLED, enabled).apply()
    }

    fun apply(context: Context) {
        val dark = isDarkEnabled(context)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
        )
    }
}

