package com.ssymba0.flashlight

import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Process
import java.util.concurrent.locks.LockSupport
import kotlin.math.floor
import kotlin.math.min

/**
 * Makes brightness between the flash's hardware steps by switching quickly between the
 * two nearest steps (or between off and step 1 below the lowest step). The share of time
 * spent on the brighter step sets how bright it looks.
 */
internal class Pulser(
    private val cameraManager: CameraManager,
    private val cameraId: String,
    private val maxLevel: Int,
    target: Float,
    private var current: Int,
    private val onFailure: () -> Unit,
) : Thread("torch-pulse") {

    companion object {
        private const val MIN_PERIOD_NS = 8_000_000.0   // fastest: 125 pulses a second
        private const val MAX_PERIOD_NS = 40_000_000.0  // slowest: 25 pulses a second
    }

    /** Wanted brightness on the hardware scale, 0..maxLevel, fractional. */
    @Volatile var target = target

    /** Measured pulses per second (0 while sitting exactly on a step). */
    @Volatile var pulseRate = 0f
        private set

    /** Average time one flash switch takes, in ms. */
    @Volatile var switchMs = 0f
        private set

    @Volatile private var running = true
    private var avgSwitchNs = 3_000_000.0

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        var cycles = 0
        var windowStart = System.nanoTime()
        try {
            while (running) {
                val x = target.coerceIn(0f, maxLevel.toFloat())
                val lo = floor(x).toInt().coerceAtMost(maxLevel)
                val duty = x - lo
                if (lo >= maxLevel || duty < 0.03f || duty > 0.97f) {
                    // Close enough to a real step: just hold it.
                    command(if (duty > 0.97f) lo + 1 else lo)
                    pulseRate = 0f
                    cycles = 0
                    windowStart = System.nanoTime()
                    LockSupport.parkNanos(20_000_000L)
                    continue
                }
                // Each phase must last longer than a switch takes, or the mix comes out wrong.
                val period = (avgSwitchNs * 1.5 / min(duty, 1f - duty)).coerceIn(MIN_PERIOD_NS, MAX_PERIOD_NS)
                val t0 = System.nanoTime()
                command(lo + 1)
                parkUntil(t0 + (period * duty).toLong())
                command(lo)
                parkUntil(t0 + period.toLong())

                cycles++
                val now = System.nanoTime()
                if (now - windowStart >= 1_000_000_000L) {
                    pulseRate = cycles * 1e9f / (now - windowStart)
                    cycles = 0
                    windowStart = now
                }
            }
        } catch (_: Exception) {
            if (running) onFailure()
        }
    }

    /** Stops pulsing and waits for the thread so it can't switch the light back on. */
    fun shutdown() {
        running = false
        LockSupport.unpark(this)
        try { join(500) } catch (_: InterruptedException) {}
    }

    private fun command(level: Int) {
        if (level == current) return
        val start = System.nanoTime()
        when {
            level <= 0 -> cameraManager.setTorchMode(cameraId, false)
            Build.VERSION.SDK_INT >= 33 && maxLevel > 1 -> cameraManager.turnOnTorchWithStrengthLevel(cameraId, level)
            else -> cameraManager.setTorchMode(cameraId, true)
        }
        avgSwitchNs = avgSwitchNs * 0.9 + (System.nanoTime() - start) * 0.1
        switchMs = (avgSwitchNs / 1e6).toFloat()
        current = level
    }

    private fun parkUntil(deadline: Long) {
        while (running) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return
            LockSupport.parkNanos(left)
        }
    }
}
