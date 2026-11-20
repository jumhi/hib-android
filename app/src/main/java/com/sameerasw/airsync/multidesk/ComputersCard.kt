package com.sameerasw.airsync.multidesk

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.presentation.ui.activities.QRScannerActivity
import com.sameerasw.airsync.utils.HapticUtil

/**
 * H.I.B Space — "Your devices" on the main screen: every paired Mac / Windows PC with its live state
 * (In use, Connected, Off, Offline), what it's doing (playing the phone's audio), an on/off switch per
 * extra computer, remove, and "Add computer" (scan its QR code). Adding a computer never unpairs the others.
 */
@Composable
fun ComputersCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val desktops by DesktopHub.desktops.collectAsState()
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { DesktopHub.start(context); DesktopHub.refresh() }

    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val raw = result.data?.getStringExtra("QR_CODE") ?: return@rememberLauncherForActivityResult
        val qr = PairingQr.parse(raw)
        if (qr?.name == null || qr.key == null) {
            Toast.makeText(context, "That isn't an H.I.B pairing code", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        DesktopHub.addFromQr(context, qr)
        Toast.makeText(context, "Added ${qr.name} — connecting…", Toast.LENGTH_SHORT).show()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val online = desktops.count { it.isConnected }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Your devices",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = when {
                        desktops.isEmpty() -> ""
                        desktops.any { it.inUse } -> "Using ${desktops.first { it.inUse }.name}"
                        else -> "$online of ${desktops.size} connected"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))

            desktops.forEach { d ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (d.inUse) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = deviceIcon(d.platform),
                                contentDescription = null,
                                tint = if (d.inUse) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Box(
                            Modifier.size(11.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceBright).padding(2.dp)
                                .clip(CircleShape).background(statusColor(d))
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = d.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (d.inUse) {
                                Spacer(Modifier.width(6.dp))
                                InUsePill()
                            }
                        }
                        Text(
                            text = statusLine(d),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!d.isPrimary) {
                        Switch(
                            checked = d.state != "off",
                            onCheckedChange = { on ->
                                HapticUtil.performClick(haptics)
                                DesktopHub.setEnabled(context, d.name, on)
                            }
                        )
                    }
                    IconButton(onClick = { confirmRemove = d.name }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove ${d.name}")
                    }
                }
            }

            Button(
                onClick = {
                    HapticUtil.performClick(haptics)
                    scanner.launch(Intent(context, QRScannerActivity::class.java))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(44.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Add computer", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = "Your phone, Mac and Windows PC work as one. Notifications, clipboard, calls and battery go to every computer; Flick, Play and files go to the one you're using unless you pick another.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)
            )
        }
    }

    confirmRemove?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove $name?") },
            text = { Text("The phone forgets this computer's pairing. Scan its QR code to add it again.") },
            confirmButton = {
                TextButton(onClick = {
                    DesktopHub.remove(context, name)
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } }
        )
    }
}

private fun statusColor(d: DesktopHub.DesktopStatus): Color = when {
    d.inUse -> HibLime
    d.state == "connected" -> Color(0xFF34C759)
    d.state == "connecting" -> Color(0xFFFFB300)
    d.state == "authFailed" -> Color(0xFFE53935)
    else -> Color(0xFF9E9E9E)
}

/** "In use · Mac · Playing", "Connected · Windows · main", "Off", "Offline" … */
private fun statusLine(d: DesktopHub.DesktopStatus): String {
    val kind = if (d.platform == "windows") "Windows" else "Mac"
    val st = when (d.state) {
        "connected" -> if (d.inUse) "In use" else "Connected"
        "connecting" -> "Connecting…"
        "authFailed" -> "Pairing expired — scan its QR again"
        "off" -> "Off"
        else -> "Offline"
    }
    val parts = mutableListOf(st, kind)
    if (d.isPrimary) parts += "main"
    if (d.playing) parts += "playing phone audio"
    if (d.state == "connected" && !d.inUse && d.idleSeconds in 90..(48 * 3600)) parts += "idle ${idle(d.idleSeconds)}"
    return parts.joinToString(" · ")
}

private fun idle(seconds: Int): String = when {
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h"
}
