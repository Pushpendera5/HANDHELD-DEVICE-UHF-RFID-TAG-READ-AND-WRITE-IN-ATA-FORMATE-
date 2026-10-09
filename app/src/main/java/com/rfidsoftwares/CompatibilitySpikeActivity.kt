
package com.rfidsoftwares

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/**
 * Phase 0 compatibility spike:
 * Reflectively validates Chainway UHF lifecycle calls on a physical C72 device:
 * `getInstance()` -> `init()` -> `startInventoryTag()` -> `stopInventory()` -> `free()`.
 */
class CompatibilitySpikeActivity : Activity() {
    private var mReader: Any? = null
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            text = "Initializing UHF reader..."
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }
        setContentView(statusView)

        Thread {
            val logTag = "UHF_COMPAT_SPIKE"
            fun updateStatus(text: String) {
                runOnUiThread { statusView.text = text }
            }

            try {
                updateStatus("Creating reader instance...")
                val reader = try {
                    val clazz = Class.forName("com.rscja.deviceapi.RFIDWithUHFUART")
                    clazz.getMethod("getInstance").invoke(null)
                } catch (e: Exception) {
                    Log.e(logTag, "getInstance failed", e)
                    null
                }

                if (reader == null) {
                    updateStatus("getInstance() failed. Check SDK/jar availability.")
                    return@Thread
                }

                mReader = reader

                updateStatus("Calling init()...")
                val initOk = try {
                    val m = reader.javaClass.methods.firstOrNull { it.name == "init" }
                    (m?.invoke(reader) as? Boolean) == true
                } catch (e: Exception) {
                    Log.e(logTag, "init() threw", e)
                    false
                }
                updateStatus("init() => $initOk")
                if (!initOk) return@Thread

                updateStatus("Calling startInventoryTag()...")
                val startOk = try {
                    val m = reader.javaClass.methods.firstOrNull { it.name == "startInventoryTag" }
                    (m?.invoke(reader) as? Boolean) == true
                } catch (e: Exception) {
                    Log.e(logTag, "startInventoryTag() threw", e)
                    false
                }
                updateStatus("startInventoryTag() => $startOk")
                if (!startOk) return@Thread

                Thread.sleep(3000)

                updateStatus("Calling stopInventory()...")
                val stopOk = try {
                    val m = reader.javaClass.methods.firstOrNull { it.name == "stopInventory" }
                    (m?.invoke(reader) as? Boolean) == true
                } catch (e: Exception) {
                    Log.e(logTag, "stopInventory() threw", e)
                    false
                }
                updateStatus("stopInventory() => $stopOk")
            } catch (e: Exception) {
                Log.e("UHF_COMPAT_SPIKE", "Compatibility spike failed", e)
                updateStatus("Compatibility spike failed: ${e.message ?: e::class.java.name}")
            }
        }.start()
    }

    override fun onDestroy() {
        try {
            val r = mReader
            if (r != null) {
                val m = r.javaClass.methods.firstOrNull { it.name == "free" }
                m?.invoke(r)
            }
        } catch (e: Exception) {
            Log.e("UHF_COMPAT_SPIKE", "free() threw", e)
        }
        mReader = null
        super.onDestroy()
    }
}
