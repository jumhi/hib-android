package com.sameerasw.airsync.presentation.ui.components.cards

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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sameerasw.airsync.multidesk.AdbSetupMonitor
import com.sameerasw.airsync.multidesk.AdbSetupOpen
import com.sameerasw.airsync.multidesk.AdbSetupScreens
import com.sameerasw.airsync.utils.HapticUtil

/**
 * "Set up mirroring" (SPACE.md §8): the same three steps the computer shows, with buttons that open the
 * right Settings screen. Each step ticks itself off as the phone's settings change.
 */
@Composable
fun MirroringSetupCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceBright
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by AdbSetupMonitor.state.collectAsState()

    DisposableEffect(lifecycleOwner) {
        AdbSetupMonitor.refresh(context)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) AdbSetupMonitor.refresh(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun open(which: AdbSetupOpen) {
        HapticUtil.performClick(haptics)
        AdbSetupScreens.launch(context, which)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text("Set up mirroring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Once per computer. Your computer finds this phone by itself after that, no terminal needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SetupStep(
                number = 1,
                done = state.developerOptions,
                title = "Turn on Developer options",
                body = "${AdbSetupScreens.buildNumberPath} → tap Build number 7 times.",
                action = "Show me",
                onAction = { open(AdbSetupOpen.DEVELOPER) }
            )
            SetupStep(
                number = 2,
                done = state.developerOptions && state.wirelessDebugging,
                title = "Turn on Wireless debugging",
                body = "Developer options → Wireless debugging → On, then Allow on this network.",
                action = "Open it",
                enabled = state.developerOptions,
                onAction = { open(AdbSetupOpen.WIRELESS) }
            )
            SetupStep(
                number = 3,
                done = false,
                title = "Pair with your computer",
                body = "On your computer, open Set up mirroring. Then here: Wireless debugging → " +
                    "Pair device with QR code, and scan the code on your computer's screen.",
                action = "Open Wireless debugging",
                enabled = state.developerOptions && state.wirelessDebugging,
                onAction = { open(AdbSetupOpen.WIRELESS) }
            )
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    done: Boolean,
    title: String,
    body: String,
    action: String,
    enabled: Boolean = true,
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (done) "Done" else body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!done) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = onAction,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(action, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
