
package com.rfidsoftwares.common.prefs

import android.content.Context

data class UhfConfig(
    val regionCode: String?,
    val powerDbm: Int?,
    val driverId: String? = null,
    val soundEnabled: Boolean = true,
)

/**
 * Persisted UHF reader configuration edited from the UI.
 */
object UhfConfigPrefs {
    private const val PREFS_NAME = "rfid_inventory_uhf_prefs"
    private const val KEY_REGION = "region_code"
    private const val KEY_POWER_DBM = "power_dbm"
    private const val KEY_DRIVER_ID = "driver_id"
    private const val KEY_SOUND_ENABLED = "sound_enabled"

    fun load(context: Context): UhfConfig {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val region = p.getString(KEY_REGION, null)?.takeIf { it.isNotBlank() }
        val power = if (p.contains(KEY_POWER_DBM)) p.getInt(KEY_POWER_DBM, 30) else null
        val driver = p.getString(KEY_DRIVER_ID, null)?.takeIf { it.isNotBlank() }
        val sound = p.getBoolean(KEY_SOUND_ENABLED, true)
        return UhfConfig(regionCode = region, powerDbm = power, driverId = driver, soundEnabled = sound)
    }

    fun save(context: Context, config: UhfConfig) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        p.edit().apply {
            if (config.regionCode.isNullOrBlank()) remove(KEY_REGION) else putString(KEY_REGION, config.regionCode)
            if (config.powerDbm == null) remove(KEY_POWER_DBM) else putInt(KEY_POWER_DBM, config.powerDbm)
            if (config.driverId.isNullOrBlank()) remove(KEY_DRIVER_ID) else putString(KEY_DRIVER_ID, config.driverId)
            putBoolean(KEY_SOUND_ENABLED, config.soundEnabled)
            apply()
        }
    }
}
