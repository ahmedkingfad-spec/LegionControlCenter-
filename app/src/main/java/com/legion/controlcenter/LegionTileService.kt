package com.legion.controlcenter

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class LegionTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (OverlayService.isPanelShowing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Legion Center"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_TOGGLE_PANEL
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
