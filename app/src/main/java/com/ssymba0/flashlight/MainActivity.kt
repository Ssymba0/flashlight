package com.ssymba0.flashlight

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var slider: BrightnessSlider
    private lateinit var power: PowerButton
    private lateinit var percentText: TextView
    private lateinit var infoText: TextView

    private var lastErrorToast = 0L
    private var lastTick = -1

    private val torchListener: () -> Unit = { syncFromTorch(animate = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Torch.init(this)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        slider = findViewById(R.id.slider)
        power = findViewById(R.id.power)
        percentText = findViewById(R.id.percent)
        infoText = findViewById(R.id.info)

        slider.onValueChange = { v -> onSliderMoved(v) }
        slider.onRelease = {
            // Settle on the step the hardware is actually at.
            slider.setValue(Torch.fraction, animate = true)
            updateLabels()
            maybeAskForNotifications()
        }
        power.setOnClickListener {
            if (!Torch.toggle()) showError()
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            maybeAskForNotifications()
        }

        if (!Torch.hasFlash) {
            slider.isEnabled = false
            power.isEnabled = false
            slider.alpha = 0.4f
            power.alpha = 0.4f
        }
    }

    override fun onStart() {
        super.onStart()
        Torch.addListener(torchListener)
        syncFromTorch(animate = false)
    }

    override fun onStop() {
        Torch.removeListener(torchListener)
        super.onStop()
    }

    private fun levelFor(v: Float): Int =
        if (v <= 0f) 0 else ceil(v * Torch.maxLevel).toInt().coerceIn(1, Torch.maxLevel)

    private fun onSliderMoved(v: Float) {
        val target = levelFor(v)
        val current = if (Torch.isOn) Torch.level else 0
        if (target != current && !Torch.setLevel(target)) showError()
        tick(target)
        percentText.text = labelFor(target)
    }

    /** A light haptic tick per brightness step (capped at ~20 ticks over the full range). */
    private fun tick(level: Int) {
        val max = Torch.maxLevel
        val bucket = if (max <= 20) level else ceil(level * 20f / max).toInt()
        if (bucket != lastTick) {
            if (lastTick != -1) slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastTick = bucket
        }
    }

    private fun syncFromTorch(animate: Boolean) {
        power.isOn = Torch.isOn
        if (!slider.isTracking) {
            slider.setValue(Torch.fraction, animate)
            lastTick = -1
            updateLabels()
        }
    }

    private fun updateLabels() {
        percentText.text = labelFor(if (Torch.isOn) Torch.level else 0)
        infoText.text = when {
            !Torch.hasFlash -> getString(R.string.no_flash)
            !Torch.isAvailable -> getString(R.string.in_use)
            !Torch.supportsLevels -> getString(R.string.no_levels)
            else -> getString(R.string.levels, Torch.maxLevel)
        }
    }

    private fun labelFor(level: Int): String = when {
        level <= 0 -> getString(R.string.off)
        !Torch.supportsLevels -> getString(R.string.on)
        else -> "${(level * 100f / Torch.maxLevel).roundToInt()}%"
    }

    private fun showError() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastErrorToast < 2500) return
        lastErrorToast = now
        Toast.makeText(this, R.string.in_use, Toast.LENGTH_SHORT).show()
    }

    /** Asks once, so the "Turn off" notification shows while the light is on. */
    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < 33 || !Torch.isOn) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
