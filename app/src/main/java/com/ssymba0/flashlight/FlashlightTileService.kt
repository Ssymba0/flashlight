package com.ssymba0.flashlight

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: tap to toggle at the last brightness, long-press for the pop-up slider.
 * Tapping can be set to open the slider instead (see [tapOpensSlider]).
 */
class FlashlightTileService : TileService() {

    companion object {
        private const val PREFS = "ui"
        private const val KEY_TAP_OPENS_SLIDER = "tile_tap_opens_slider"

        fun tapOpensSlider(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TAP_OPENS_SLIDER, false)

        fun setTapOpensSlider(context: Context, value: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_TAP_OPENS_SLIDER, value).apply()
    }

    private val listener: () -> Unit = { updateTile() }

    override fun onStartListening() {
        Torch.init(this)
        Torch.addListener(listener)
        updateTile()
    }

    override fun onStopListening() {
        Torch.removeListener(listener)
    }

    override fun onClick() {
        Torch.init(this)
        if (tapOpensSlider(this)) openSlider() else Torch.toggle()
        updateTile()
    }

    private fun openSlider() {
        val intent = Intent(this, PopupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = when {
            !Torch.hasFlash || !Torch.isAvailable -> Tile.STATE_UNAVAILABLE
            Torch.isOn -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            Torch.isOn && Torch.supportsLevels -> "${Torch.percent}%"
            Torch.isOn -> getString(R.string.on)
            else -> getString(R.string.off)
        }
        tile.updateTile()
    }
}
