package com.sameerasw.airsync.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sameerasw.airsync.R
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.presentation.ui.activities.FlickActivity
import com.sameerasw.airsync.utils.RecentMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile "Flick": just took a screenshot? Pull down, tap Flick, and it's ready to throw (Flick opens
 * with the newest photo, video or screenshot; the photo picker when H.I.B can't see photos). Point the phone at
 * a screen and flick up as usual.
 */
class FlickTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = scope.launch { DesktopHub.desktops.collect { updateTile() } }
        updateTile()
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val intent = if (RecentMedia.canRead(this)) FlickActivity.recentIntent(this) else FlickActivity.pickIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0x464c, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            runCatching { startActivityAndCollapse(intent) }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val target = DesktopHub.targetPick()?.id
        tile.label = getString(R.string.flick_tile_label)
        tile.subtitle = when {
            target == null -> "No computer connected"
            RecentMedia.canRead(this) -> "Latest photo → $target"
            else -> "Pick → $target"
        }
        tile.state = if (target != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_flick_24)
        tile.contentDescription = "Flick, ${tile.subtitle}"
        tile.updateTile()
    }
}
