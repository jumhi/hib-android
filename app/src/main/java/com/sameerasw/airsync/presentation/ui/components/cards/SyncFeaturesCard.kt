package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.sameerasw.airsync.clip.ClipFocusReader
import com.sameerasw.airsync.clip.ClipLogWatcher
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R

@Composable
fun ClipboardFeaturesCard(
    isClipboardSyncEnabled: Boolean,
    onToggleClipboardSync: (Boolean) -> Unit,
    // Continue Browsing props
    isContinueBrowsingEnabled: Boolean,
    onToggleContinueBrowsing: (Boolean) -> Unit,
    // Control the UI enabled state and subtitle for Continue Browsing
    isContinueBrowsingToggleEnabled: Boolean,
    continueBrowsingSubtitle: String,
    // Keep previous link props
    isKeepPreviousLinkEnabled: Boolean,
    onToggleKeepPreviousLink: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        IconToggleItem(
            iconRes = R.drawable.ic_clipboard_24,
            title = "Clipboard Sync",
            description = "Update Android clipboard automatically",
            isChecked = isClipboardSyncEnabled,
            onCheckedChange = onToggleClipboardSync
        )
        if (isClipboardSyncEnabled) BackgroundCopiesItem()
        IconToggleItem(
            iconRes = R.drawable.outline_open_in_browser_24,
            title = "Continue browsing",
            description = continueBrowsingSubtitle,
            isChecked = isContinueBrowsingEnabled,
            onCheckedChange = onToggleContinueBrowsing,
            enabled = isContinueBrowsingToggleEnabled
        )
        IconToggleItem(
            iconRes = R.drawable.rounded_history_24,
            title = "Keep previous link",
            description = "Without replacing",
            isChecked = isKeepPreviousLinkEnabled,
            onCheckedChange = onToggleKeepPreviousLink,
            enabled = isContinueBrowsingToggleEnabled
        )
    }
}
/**
 * SPACE.md §16.3: whether copies made in other apps (H.I.B in the background) reach the computers,
 * and what is missing. The computer grants both permissions over ADB.
 */
@Composable
private fun BackgroundCopiesItem() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ClipLogWatcher.state(context)) }
    var logs by remember { mutableStateOf(ClipLogWatcher.canReadLogs(context)) }
    var overlay by remember { mutableStateOf(ClipFocusReader.canOverlay(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            state = ClipLogWatcher.state(context)
            logs = ClipLogWatcher.canReadLogs(context)
            overlay = ClipFocusReader.canOverlay(context)
            delay(2_000)
        }
    }
    val description = when (state) {
        ClipLogWatcher.State.RUNNING ->
            "On. Copies made in any app go to your computers."
        ClipLogWatcher.State.WAITING_FOR_FRONT ->
            "Set up. If Android asks \u201cAllow H.I.B to access all device logs?\u201d, choose Allow."
        else -> buildString {
            append("Copies made in other apps sync once ADB has set this up. ")
            append("Connect a computer with ADB and it sets this up for you. ")
            append("Log access: ").append(if (logs) "granted" else "not yet").append(" \u00b7 ")
            append("Display over other apps: ").append(if (overlay) "allowed" else "not yet").append(". ")
            append("If Android asks \u201cAllow H.I.B to access all device logs?\u201d, choose Allow. ")
            append("Until then, copies sync when you open H.I.B, or with Send clipboard.")
        }
    }
    IconToggleItem(
        iconRes = R.drawable.ic_clipboard_24,
        title = "Copies made in other apps",
        description = description,
        showToggle = false
    )
}
