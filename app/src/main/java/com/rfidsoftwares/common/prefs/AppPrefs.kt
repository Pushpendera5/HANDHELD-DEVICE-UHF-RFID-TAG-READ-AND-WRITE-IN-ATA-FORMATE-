
package com.rfidsoftwares.common.prefs

import android.content.Context
import android.content.SharedPreferences
import com.rfidsoftwares.common.config.AppConfig

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isFirstOpen(): Boolean = !prefs.getBoolean(KEY_FIRST_OPEN_DONE, false)

    fun markFirstOpenDone() {
        prefs.edit().putBoolean(KEY_FIRST_OPEN_DONE, true).apply()
    }

    fun isDashboardFirstOpenPromptSeen(role: AppConfig.AppRole): Boolean {
        val key = dashboardPromptKey(role)
        return prefs.getBoolean(key, false)
    }

    fun markDashboardFirstOpenPromptSeen(role: AppConfig.AppRole) {
        prefs.edit().putBoolean(dashboardPromptKey(role), true).apply()
    }

    private fun dashboardPromptKey(role: AppConfig.AppRole): String {
        return when (role) {
            AppConfig.AppRole.ADMIN -> KEY_DASHBOARD_PROMPT_SEEN_ADMIN
            AppConfig.AppRole.USER -> KEY_DASHBOARD_PROMPT_SEEN_USER
        }
    }

    companion object {
        private const val PREFS_NAME = "rfid_inventory_app_prefs"
        private const val KEY_FIRST_OPEN_DONE = "first_open_done"

        private const val KEY_DASHBOARD_PROMPT_SEEN_ADMIN = "dashboard_prompt_seen_admin"
        private const val KEY_DASHBOARD_PROMPT_SEEN_USER = "dashboard_prompt_seen_user"
    }
}

