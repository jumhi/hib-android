package com.sameerasw.airsync.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.sameerasw.airsync.R
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.presentation.ui.activities.PlayOnChooserActivity
import com.sameerasw.airsync.utils.PhoneAudioRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile "Play on computer" (SPACE.md §4): tap plays the phone's audio on the target-rule
 * computer (the one in use, else the last used, else the main one), or stops it if it's playing.
 * Long-press opens the chooser ([PlayOnChooserActivity], via MainActivity's QS_TILE_PREFERENCES handling).
 */
class PlayOnComputerTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = scope.launch {
            combine(PhoneAudioRemote.playingOn, DesktopHub.desktops) { _, _ -> Unit }.collect { updateTile() }
        }
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
        val playing = PhoneAudioRemote.playingOn.value
        if (playing == null && DesktopHub.targetPick() == null) {
            // Nothing to play on: open the chooser, which explains and offers to open H.I.B.
            openChooser()
            return
        }
        PhoneAudioRemote.toggle()
        updateTile()
    }

    private fun openChooser() {
        val intent = PlayOnChooserActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            runCatching { startActivityAndCollapse(intent) }
                .onFailure { Toast.makeText(this, "No computer is connected", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val playing = PhoneAudioRemote.playingOn.value
        val target = DesktopHub.targetPick()?.id
        tile.label = "Play on computer"
        tile.subtitle = when {
            playing != null -> "Playing on $playing"
            target != null -> target
            else -> "No computer connected"
        }
        tile.state = if (playing != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.rounded_music_cast_24)
        tile.contentDescription = tile.subtitle?.let { "Play on computer, $it" } ?: "Play on computer"
        tile.updateTile()
    }

    companion object {
        /** True for the long-press intent the system sends for this tile. */
        fun isTilePreferences(intent: Intent?): Boolean {
            if (intent?.action != TileService.ACTION_QS_TILE_PREFERENCES) return false
            val cn: ComponentName? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
            }
            return cn?.className == PlayOnComputerTileService::class.java.name
        }
    }
}
