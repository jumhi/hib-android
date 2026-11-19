package com.sameerasw.airsync.findmy

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.utils.HapticUtil

/** "Find my Mac" card on the main screen: always visible, works nearby or over the internet. */
@Composable
fun FindMyMacCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val ringing by FindMyHandler.macRinging.collectAsState()
    val macLocation by FindMyHandler.macLocation.collectAsState()
    val status by FindMyHandler.status.collectAsState()

    LaunchedEffect(Unit) { FindMyRelayClient.schedulePollJob(context) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Find my Mac", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        FindMyHandler.ringMac(context, !ringing)
                    },
                    colors = if (ringing) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ) else ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (ringing) "Stop ringing" else "Ring Mac", maxLines = 1)
                }
                Button(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        FindMyHandler.locateMac(context)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Where's my Mac?", maxLines = 1)
                }
            }

            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }

            macLocation?.let { report ->
                Spacer(Modifier.height(6.dp))
                val name = report.macName ?: "Your Mac"
                val ago = DateUtils.getRelativeTimeSpanString(report.timestamp).toString()
                if (report.lat != null && report.lon != null) {
                    val accuracy = report.accuracyM?.let { " (±${it.toInt()} m)" } ?: ""
                    Text("$name was located $ago$accuracy", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        val label = Uri.encode(name)
                        val uri = Uri.parse("geo:${report.lat},${report.lon}?q=${report.lat},${report.lon}($label)")
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("Show on map") }
                } else {
                    Text(report.error ?: "$name didn't share a location", style = MaterialTheme.typography.bodyMedium)
                }
                report.networkName?.let {
                    Text("Wi-Fi: $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
