package com.agentifiers.amp.surfaces

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.agentifiers.amp.puck.voice.puckCallActive

/** Quick Settings **Call Puck**: one tap starts a voice call with Puck through [SurfaceActivity]. */
class PuckTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (puckCallActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (puckCallActive) "On a call" else null
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = SurfaceAction.CallPuck.intent(this).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
