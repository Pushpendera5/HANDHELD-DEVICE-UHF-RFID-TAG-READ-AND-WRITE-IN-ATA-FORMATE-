
package com.rfidsoftwares.common.prefs

import android.content.Context
import android.content.SharedPreferences

object LoginPrefs {

    private const val PREFS_NAME = "openrfid_login_prefs"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_REMEMBER_PASSWORD = "remember_password"
    private const val KEY_SAVED_USERNAME = "saved_username"
    private const val KEY_SAVED_PASSWORD = "saved_password"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isLoggedIn(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun setLoggedIn(context: Context, loggedIn: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_LOGGED_IN, loggedIn).apply()
    }

    fun isRememberPasswordEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REMEMBER_PASSWORD, true)
    }

    fun getSavedUsername(context: Context): String {
        return getPrefs(context).getString(KEY_SAVED_USERNAME, "admin") ?: "admin"
    }

    fun getSavedPassword(context: Context): String {
        val remember = isRememberPasswordEnabled(context)
        return if (remember) {
            getPrefs(context).getString(KEY_SAVED_PASSWORD, "admin") ?: "admin"
        } else {
            ""
        }
    }

    fun saveCredentials(context: Context, user: String, pass: String, remember: Boolean) {
        val editor = getPrefs(context).edit()
        editor.putBoolean(KEY_IS_LOGGED_IN, true)
        editor.putBoolean(KEY_REMEMBER_PASSWORD, remember)
        if (remember) {
            editor.putString(KEY_SAVED_USERNAME, user)
            editor.putString(KEY_SAVED_PASSWORD, pass)
        } else {
            editor.putString(KEY_SAVED_USERNAME, user)
            editor.remove(KEY_SAVED_PASSWORD)
        }
        editor.apply()
    }

    fun logout(context: Context) {
        val remember = isRememberPasswordEnabled(context)
        val editor = getPrefs(context).edit()
        editor.putBoolean(KEY_IS_LOGGED_IN, false)
        if (!remember) {
            editor.remove(KEY_SAVED_USERNAME)
            editor.remove(KEY_SAVED_PASSWORD)
        }
        editor.apply()
    }

    fun validate(user: String, pass: String): Boolean {
        return user.trim().equals("admin", ignoreCase = true) && pass == "admin"
    }
}
