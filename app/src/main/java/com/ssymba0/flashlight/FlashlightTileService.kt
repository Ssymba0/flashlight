package com.ssymba0.flashlight

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to toggle at the last brightness, long-press to open the app. */
class FlashlightTileService : TileService() {

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
        Torch.toggle()
        updateTile()
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
