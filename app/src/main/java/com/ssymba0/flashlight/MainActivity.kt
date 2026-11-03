package com.ssymba0.flashlight

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
    private lateinit var smoothText: TextView

    private var lastErrorToast = 0L
    private var lastTick = -1

    private val torchListener: () -> Unit = { syncFromTorch(animate = true) }

    // Refreshes the pulse rate shown under the button.
    private val refresh = object : Runnable {
        override fun run() {
            updateInfo()
            infoText.postDelayed(this, 1000)
        }
    }

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
        smoothText = findViewById(R.id.smooth)
        smoothText.setOnClickListener {
            Torch.smoothMode = !Torch.smoothMode
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            updateLabels()
        }

        slider.onValueChange = { v -> onSliderMoved(v) }
        slider.onRelease = {
            // In step mode, settle on the step the hardware is actually at.
            slider.setValue(Torch.fraction, animate = true)
            updateLabels()
            maybeAskForNotifications()
        }
        power.setOnClickListener {
            if (!Torch.toggle()) showError()
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            maybeAskForNotifications()
        }

        infoText.setOnClickListener { showDetails() }

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
        infoText.post(refresh)
    }

    override fun onStop() {
        infoText.removeCallbacks(refresh)
        Torch.removeListener(torchListener)
        super.onStop()
    }

    private fun onSliderMoved(v: Float) {
        if (!Torch.setBrightness(v)) showError()
        tick(v)
        percentText.text = labelFor(Torch.effectiveBrightness(v))
    }

    /** A light haptic tick per hardware step, or every 5% in smooth mode. */
    private fun tick(v: Float) {
        val steps = if (Torch.smoothMode || Torch.maxLevel > 20) 20 else Torch.maxLevel
        val bucket = if (v <= 0f) 0 else ceil(v * steps).toInt()
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
        percentText.text = labelFor(Torch.fraction)
        smoothText.text = getString(if (Torch.smoothMode) R.string.smooth_on else R.string.smooth_off)
        smoothText.alpha = if (Torch.smoothMode) 1f else 0.6f
        updateInfo()
    }

    private fun updateInfo() {
        infoText.text = when {
            !Torch.hasFlash -> getString(R.string.no_flash)
            !Torch.isAvailable -> getString(R.string.in_use)
            Torch.smoothMode && Torch.isPulsing ->
                getString(R.string.pulsing, Torch.pulseRate.roundToInt())
            Torch.smoothMode -> getString(R.string.smooth_info, Torch.maxLevel)
            !Torch.supportsLevels -> getString(R.string.no_levels)
            else -> getString(R.string.levels, Torch.maxLevel)
        } + "\n" + getString(R.string.tap_for_details)
    }

    private fun labelFor(b: Float): String = when {
        b <= 0f -> getString(R.string.off)
        !Torch.smoothMode && !Torch.supportsLevels -> getString(R.string.on)
        else -> "${(b * 100f).roundToInt().coerceAtLeast(1)}%"
    }

    private fun showDetails() {
        val text = Torch.diagnostics()
        AlertDialog.Builder(this)
            .setTitle(R.string.details)
            .setMessage(text)
            .setPositiveButton(R.string.copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Flashlight details", text))
            }
            .setNegativeButton(R.string.close, null)
            .show()
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
