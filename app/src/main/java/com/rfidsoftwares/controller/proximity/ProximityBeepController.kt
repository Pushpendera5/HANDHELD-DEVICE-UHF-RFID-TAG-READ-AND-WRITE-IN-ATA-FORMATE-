
package com.rfidsoftwares.controller.proximity
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple tone-loop controller for EPC locating.
 *
 * - runs in a background thread
 * - supports dynamic interval updates (fast/slow beeps)
 * - no vibration (beep-only to match operator UX)
 */
class ProximityBeepController {

    data class Pattern(
        val intervalMs: Long,
        val toneDurationMs: Int,
        val toneType: Int,
    )

    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_ALARM, 100)
    } catch (_: Exception) {
        null
    }

    private val running = AtomicBoolean(false)
    private val loopThreadMs = AtomicLong(600L)
    private val toneDurationMs = AtomicInteger(150)
    private val toneType = AtomicInteger(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)

    private var loopThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        loopThread = Thread {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val toneLengthMs = toneDurationMs.get().coerceIn(40, 400)
                val intervalMs = loopThreadMs.get().coerceIn(120L, 2000L)
                val toneValue = toneType.get()
                toneGenerator?.startTone(toneValue, toneLengthMs)
                try {
                    Thread.sleep(intervalMs.coerceAtLeast(toneLengthMs.toLong() + 20L))
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun updateIntervalMs(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(120L, 2000L)
        loopThreadMs.set(clamped)
    }

    fun updatePattern(pattern: Pattern) {
        loopThreadMs.set(pattern.intervalMs.coerceIn(120L, 2000L))
        toneDurationMs.set(pattern.toneDurationMs.coerceIn(40, 400))
        toneType.set(pattern.toneType)
    }

    fun stop() {
        running.set(false)
        loopThread?.interrupt()
        loopThread = null
        try {
            toneGenerator?.stopTone()
        } catch (_: Exception) {
        }
    }

    fun release() {
        stop()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                toneGenerator?.release()
            }
        } catch (_: Exception) {
        }
    }
}

