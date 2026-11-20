package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.InUsePill
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.multidesk.deviceIcon
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.PhoneAudioRemote

/**
 * "Play on…": sends whatever this phone is playing to one computer's speakers (SPACE.md §3.5).
 * The default is the target rule (the computer in use, else the last one used, else the main one);
 * the ▾ menu picks another. Starting on one computer stops it on the others.
 * The computer does the streaming over ADB, so Android shows no screen-share prompt.
 */
@Composable
fun PlayOnCard(modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val playingOn by PhoneAudioRemote.playingOn.collectAsState()
    val error by PhoneAudioRemote.error.collectAsState()
    val desktops by DesktopHub.desktops.collectAsState()
    val online = desktops.filter { it.isConnected }
    val pick = remember(desktops) { DesktopHub.targetPick() }
    var menu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        if (playingOn != null) PhoneAudioRemote.stop()
                        else pick?.let { PhoneAudioRemote.playOn(it.id) }
                    },
                    enabled = playingOn != null || pick != null,
                    colors = if (playingOn != null) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.rounded_music_cast_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = when {
                            playingOn != null -> "Playing on $playingOn — tap to stop"
                            pick != null -> "Play on ${pick.id}"
                            else -> "Play on computer"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (online.size > 1 || (online.isNotEmpty() && playingOn != null)) {
                    Spacer(Modifier.width(8.dp))
                    Box {
                        FilledTonalIconButton(
                            onClick = { HapticUtil.performClick(haptics); menu = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.ExpandMore, contentDescription = "Play on…")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Text(
                                "Play on…",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            online.forEach { d ->
                                val isPlaying = playingOn != null && PairedDesktops.sameName(playingOn!!, d.name)
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                            if (d.inUse) {
                                                Spacer(Modifier.width(8.dp))
                                                InUsePill()
                                            }
                                        }
                                    },
                                    leadingIcon = { Icon(deviceIcon(d.platform), contentDescription = null) },
                                    trailingIcon = if (isPlaying) {
                                        { Icon(Icons.Rounded.CheckCircle, contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary) }
                                    } else null,
                                    onClick = {
                                        menu = false
                                        HapticUtil.performClick(haptics)
                                        PhoneAudioRemote.playOn(d.name)
                                    }
                                )
                            }
                            if (playingOn != null) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Stop") },
                                    leadingIcon = { Icon(Icons.Rounded.Stop, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        PhoneAudioRemote.stop()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            if (playingOn == null && pick != null && online.size > 1) {
                Text(
                    text = "Plays on the computer you're using. Tap ▾ to choose another.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** One computer row for the Play chooser (QS tile long-press). */
@Composable
fun PlayTargetRow(
    name: String,
    platform: String,
    inUse: Boolean,
    playing: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (playing) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(deviceIcon(platform), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = when {
                    playing -> "Playing now"
                    isDefault -> "Default"
                    else -> "Connected"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (inUse) InUsePill()
    }
}
