package com.sameerasw.airsync.presentation.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.multidesk.PointLabels
import com.sameerasw.airsync.multidesk.PointTargetChip
import com.sameerasw.airsync.multidesk.rememberPointTarget
import com.sameerasw.airsync.presentation.ui.components.cards.PlayTargetRow
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.PhoneAudioRemote

/**
 * "Play on…" chooser (long-press on the "Play on computer" Quick Settings tile): pick which connected
 * computer plays the phone's audio, or stop it. Point to act (SPACE.md §11): while it is open, the default
 * is the computer the phone points at (else the target rule).
 */
class PlayOnChooserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            AirSyncTheme {
                PlayOnChooser(
                    onDone = { finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, PlayOnChooserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun PlayOnChooser(onDone: () -> Unit, onOpenApp: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val desktops by DesktopHub.desktops.collectAsState()
    val playingOn by PhoneAudioRemote.playingOn.collectAsState()
    val online = desktops.filter { it.isConnected }
    val pointed by rememberPointTarget(PointLabels.MEDIA)
    val rulePick = remember(desktops) { DesktopHub.targetPick() }
    val defaultId = pointed?.computer ?: rulePick?.id

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 32.dp, bottomEnd = 32.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_music_cast_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Play on…", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(10.dp))
                            PointTargetChip(computer = defaultId, aimed = pointed?.aimed == true)
                        }
                        Text(
                            text = playingOn?.let { "Playing on $it" } ?: "Phone audio plays on one computer at a time",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (online.isEmpty()) {
                    Text(
                        "No computer is connected.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onOpenApp) { Text("Open H.I.B") }
                } else {
                    online.forEach { d ->
                        PlayTargetRow(
                            name = d.name,
                            platform = d.platform,
                            inUse = d.inUse,
                            playing = d.playing,
                            isDefault = defaultId?.let { PairedDesktops.sameName(it, d.name) } == true,
                            onClick = {
                                HapticUtil.performClick(haptics)
                                PhoneAudioRemote.playOn(d.name)
                                onDone()
                            }
                        )
                    }
                    if (playingOn != null) {
                        Button(
                            onClick = {
                                PhoneAudioRemote.stop()
                                onDone()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Stop playing")
                        }
                    }
                }
                TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}
