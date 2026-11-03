package com.ssymba0.flashlight

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Wires the slider, power button and labels to [Torch]. Shared by the full-screen app
 * and the pop-up opened from the Quick Settings tile. [infoText] and [smoothText] are optional.
 */
class FlashlightControls(
    private val activity: Activity,
    private val slider: BrightnessSlider,
    private val power: PowerButton,
    private val percentText: TextView,
    private val infoText: TextView? = null,
    private val smoothText: TextView? = null,
    private val tileText: TextView? = null,
) {
    private var lastErrorToast = 0L
    private var lastTick = -1

    private val torchListener: () -> Unit = { syncFromTorch(animate = true) }

    // Refreshes the pulse rate shown under the button.
    private val refresh = object : Runnable {
        override fun run() {
            updateInfo()
            slider.postDelayed(this, 1000)
        }
    }

    init {
        Torch.init(activity)
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
        infoText?.setOnClickListener { showDetails() }
        smoothText?.setOnClickListener {
            Torch.smoothMode = !Torch.smoothMode
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            updateLabels()
        }
        tileText?.setOnClickListener {
            FlashlightTileService.setTapOpensSlider(activity, !FlashlightTileService.tapOpensSlider(activity))
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            updateLabels()
        }
        if (!Torch.hasFlash) {
            slider.isEnabled = false
            power.isEnabled = false
            slider.alpha = 0.4f
            power.alpha = 0.4f
        }
    }

    fun start() {
        Torch.addListener(torchListener)
        syncFromTorch(animate = false)
        if (infoText != null) slider.post(refresh)
    }

    fun stop() {
        slider.removeCallbacks(refresh)
        Torch.removeListener(torchListener)
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
        smoothText?.let {
            it.text = activity.getString(if (Torch.smoothMode) R.string.smooth_on else R.string.smooth_off)
            it.alpha = if (Torch.smoothMode) 1f else 0.6f
        }
        tileText?.text = activity.getString(
            if (FlashlightTileService.tapOpensSlider(activity)) R.string.tile_tap_slider else R.string.tile_tap_toggle
        )
        updateInfo()
    }

    private fun updateInfo() {
        val info = infoText ?: return
        info.text = when {
            !Torch.hasFlash -> activity.getString(R.string.no_flash)
            !Torch.isAvailable -> activity.getString(R.string.in_use)
            Torch.smoothMode && Torch.isPulsing ->
                activity.getString(R.string.pulsing, Torch.pulseRate.roundToInt())
            Torch.smoothMode -> activity.getString(R.string.smooth_info, Torch.maxLevel)
            !Torch.supportsLevels -> activity.getString(R.string.no_levels)
            else -> activity.getString(R.string.levels, Torch.maxLevel)
        } + "\n" + activity.getString(R.string.tap_for_details)
    }

    private fun labelFor(b: Float): String = when {
        b <= 0f -> activity.getString(R.string.off)
        !Torch.smoothMode && !Torch.supportsLevels -> activity.getString(R.string.on)
        else -> "${(b * 100f).roundToInt().coerceAtLeast(1)}%"
    }

    private fun showDetails() {
        val text = Torch.diagnostics()
        AlertDialog.Builder(activity)
            .setTitle(R.string.details)
            .setMessage(text)
            .setPositiveButton(R.string.copy) { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Flashlight details", text))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showError() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastErrorToast < 2500) return
        lastErrorToast = now
        Toast.makeText(activity, R.string.in_use, Toast.LENGTH_SHORT).show()
    }

    /** Asks once, so the "Turn off" notification shows while the light is on. */
    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < 33 || !Torch.isOn) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = activity.getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
