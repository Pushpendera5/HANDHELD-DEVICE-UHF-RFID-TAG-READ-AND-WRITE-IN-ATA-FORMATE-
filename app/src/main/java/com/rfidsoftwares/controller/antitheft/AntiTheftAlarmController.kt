
package com.rfidsoftwares.controller.antitheft

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous alarm loop for anti-theft warnings. Must be stopped when leaving the screen.
 */
class AntiTheftAlarmController(context: Context) {

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var loopThread: Thread? = null
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_ALARM, 100)
    } catch (_: Exception) {
        null
    }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = appContext.getSystemService(VibratorManager::class.java)
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ContextCompat.getSystemService(appContext, Vibrator::class.java)
    }

    fun startLoop() {
        if (!running.compareAndSet(false, true)) return
        loopThread = Thread {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 180)
                buzzShort()
                try {
                    Thread.sleep(420)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stopLoop() {
        running.set(false)
        loopThread?.interrupt()
        loopThread = null
        try {
            toneGenerator?.stopTone()
        } catch (_: Exception) {
        }
    }

    /** Release native audio resources when the host screen is destroyed. */
    fun release() {
        stopLoop()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                toneGenerator?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun buzzShort() {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(120)
            }
        } catch (_: Exception) {
        }
    }
}
