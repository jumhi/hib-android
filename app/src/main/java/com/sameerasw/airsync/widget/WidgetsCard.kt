package com.sameerasw.airsync.widget

import android.appwidget.AppWidgetProvider
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.utils.HapticUtil

/** The H.I.B widgets, each with "Add" (Android asks where to put it). Home → More and Settings → Widgets. */
@Composable
fun WidgetsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    data class W(val title: String, val text: String, val icon: ImageVector, val cls: Class<out AppWidgetProvider>)
    val all = listOf(
        W("Devices", "Every computer and its status", Icons.Rounded.Devices, DevicesWidgetProvider::class.java),
        W("Media & Play on", "What's playing, controls, Play on…", Icons.Rounded.MusicNote, MediaWidgetProvider::class.java),
        W("Desk Deck", "Four buttons you choose", Icons.Rounded.Dashboard, DeckWidgetProvider::class.java),
        W("Clipboard", "Send or get copied text", Icons.Rounded.ContentPaste, ClipboardWidgetProvider::class.java),
        W("Computer in use", "Battery and what's playing", Icons.Rounded.LaptopMac, AirSyncWidgetProvider::class.java)
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(
                "Home screen widgets",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                "They follow the computer you're using. You can also add them from your home screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            all.forEach { w ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) { Icon(w.icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.title, style = MaterialTheme.typography.bodyLarge)
                        Text(w.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = {
                        HapticUtil.performClick(haptics)
                        if (!HibWidgets.requestPin(context, w.cls)) {
                            Toast.makeText(context, "Long-press your home screen → Widgets → H.I.B", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Add") }
                }
            }
        }
    }
}
