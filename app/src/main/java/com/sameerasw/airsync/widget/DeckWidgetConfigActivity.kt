package com.sameerasw.airsync.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.deskdeck.DeckAction
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.HapticUtil

/**
 * Picks the four buttons of a Desk Deck widget. Opens when the widget is placed (Android 11) and from the
 * widget's settings button / long-press "Settings" (Android 12+).
 */
class DeckWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves a new widget with the default buttons rather than none.
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val initial = WidgetModels.deckButtons(DeckWidgetStore.load(this, id)).map { it.actionId }
        setContent {
            AirSyncTheme {
                DeckPicker(
                    initial = initial,
                    onCancel = { finish() },
                    onDone = { picked ->
                        DeckWidgetStore.save(this, id, picked)
                        DeckWidgetProvider.update(this, AppWidgetManager.getInstance(this), id)
                        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        fun pendingIntent(context: Context, id: Int): PendingIntent {
            val intent = Intent(context, DeckWidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("hib-widget://deck-config/$id")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

@Composable
private fun DeckPicker(initial: List<String>, onCancel: () -> Unit, onDone: (List<String>) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val picked = remember { mutableStateListOf<String>().apply { addAll(initial) } }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Dashboard, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("Desk Deck widget", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Pick 4 buttons. They go to the computer you're using.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // The four slots, in order.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                (0 until 4).forEach { i ->
                    val id = picked.getOrNull(i)
                    val action = id?.let { DeckAction.fromId(it) }
                    val label = WidgetModels.DECK_ACTIONS.firstOrNull { it.actionId == id }?.label
                    Surface(
                        onClick = { if (id != null) { HapticUtil.performClick(haptics); picked.remove(id) } },
                        shape = RoundedCornerShape(16.dp),
                        color = if (id != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.weight(1f).height(72.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            if (action != null) {
                                Icon(action.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                                Text(label.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Text(
                "Tap a slot to empty it, then pick another button below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
            )
            Spacer(Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(WidgetModels.DECK_ACTIONS, key = { it.actionId }) { b ->
                    val action = DeckAction.fromId(b.actionId)
                    val index = picked.indexOf(b.actionId)
                    val selected = index >= 0
                    Surface(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            if (selected) picked.remove(b.actionId)
                            else if (picked.size < 4) picked.add(b.actionId)
                            else { picked.removeAt(picked.lastIndex); picked.add(b.actionId) }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceBright,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.height(84.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(6.dp)
                        ) {
                            action?.let { Icon(it.icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (selected) "${index + 1} · ${b.label}" else b.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
                Button(
                    onClick = { onDone(WidgetModels.deckButtons(picked.toList()).map { it.actionId }) },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text(if (picked.size == 4) "Done" else "Done (fill with defaults)", maxLines = 1) }
            }
        }
    }
}
