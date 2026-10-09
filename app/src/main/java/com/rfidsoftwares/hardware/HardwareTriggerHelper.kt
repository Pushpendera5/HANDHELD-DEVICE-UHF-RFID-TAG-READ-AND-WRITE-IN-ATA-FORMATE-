
package com.rfidsoftwares.hardware

import android.view.KeyEvent

/**
 * Centralized hardware trigger and pistol-grip key detector across
 * Zebra TC20/TC21/TC26/TC27/TC53/TC58/MC3300/RFD40, Chainway C72/C66, Honeywell, and ATID.
 */
object HardwareTriggerHelper {

    /**
     * Checks if the given Android KeyEvent keyCode corresponds to a physical scan or pistol trigger button.
     */
    fun isTriggerKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1, // 103 (Zebra right trigger / pistol grip)
            KeyEvent.KEYCODE_BUTTON_L1, // 102 (Zebra left trigger)
            KeyEvent.KEYCODE_BUTTON_R2, // 105
            KeyEvent.KEYCODE_BUTTON_L2, // 104
            KeyEvent.KEYCODE_F1,        // 131 (Chainway / standard function key)
            KeyEvent.KEYCODE_F2,        // 132
            KeyEvent.KEYCODE_F3,        // 133
            KeyEvent.KEYCODE_F4,        // 134
            KeyEvent.KEYCODE_F5,        // 135
            139,                        // Zebra side grip key
            280,                        // Chainway C72 scan key
            284,                        // Honeywell scan key
            293,                        // Zebra Gun Grip Trigger (KEYCODE_SCANCODE)
            294,                        // Zebra Sled / secondary gun trigger
            301,                        // Pistol grip trigger
            302,                        // Pistol grip trigger
            10036 -> true               // Zebra / Honeywell proprietary trigger
            else -> false
        }
    }

    /**
     * Global callback invoked when an RFID sled or reader driver emits a physical hardware trigger event.
     * `pressed`: true when trigger pulled down, false when released.
     */
    var triggerListener: ((pressed: Boolean) -> Unit)? = null
}
