package com.sameerasw.airsync.presentation.ui.activities

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.multidesk.AimMatcher
import com.sameerasw.airsync.multidesk.BubbleDevice
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.DeviceBubbleArc
import com.sameerasw.airsync.multidesk.FlickAimStore
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.FlickAim
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * H.I.B — Flick settings. "Teach aim": for each computer, point the phone at it and tap its bubble; once
 * two or more are taught, pointing the phone at one picks it on the Flick screen (nearest within 35°).
 */
class FlickSettingsActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        FlickAim.start(this)
    }

    override fun onPause() {
        FlickAim.stop()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        setContent {
            AirSyncTheme {
                FlickSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlickSettingsScreen(onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Flick settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TeachAimCard()
            HowItPicksCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TeachAimCard() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val desktops by DesktopHub.desktops.collectAsState()
    val taughtFlow = remember { FlickAimStore.flow(context) }
    val taught by taughtFlow.collectAsState(initial = emptyMap())
    var reading by remember { mutableStateOf<AimMatcher.Aim?>(null) }
    var justTaught by remember { mutableStateOf<String?>(null) }
    val pulse = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            reading = FlickAim.current()?.let { AimMatcher.Aim(it.first, it.second) }
            delay(100)
        }
    }
    val aimed = AimMatcher.nearest(reading, taught)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Teach aim", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Stand where you usually flick. Point the top of your phone at a computer, then tap its bubble. " +
                    "Do it for each computer. With two or more taught, pointing at one picks it on the Flick screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Explore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = -(reading?.heading ?: 0f) }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = reading?.let { r ->
                        val e = r.elevation.roundToInt()
                        "Pointing ${r.heading.roundToInt()}° · ${if (e >= 0) "$e° up" else "${-e}° down"}"
                    } ?: "Waiting for the compass…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(18.dp))

            if (desktops.isEmpty()) {
                Text(
                    "Pair a computer first (Your devices → Add computer).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val bubbles = desktops.map { d ->
                    val isTaught = taught.keys.any { PairedDesktops.sameName(it, d.name) }
                    BubbleDevice(
                        id = d.name, name = d.name, platform = d.platform, inUse = d.inUse,
                        subtitle = if (isTaught) "Taught" else "Tap to teach"
                    )
                }
                DeviceBubbleArc(
                    devices = bubbles,
                    litId = justTaught ?: aimed,
                    aimedId = aimed,
                    pulseId = justTaught,
                    pulse = pulse.value,
                    arcDepth = 12.dp,
                    accent = MaterialTheme.colorScheme.primary,
                    onAccent = MaterialTheme.colorScheme.onPrimary,
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    onContainer = MaterialTheme.colorScheme.onSurface,
                    label = MaterialTheme.colorScheme.onSurface,
                    onTap = { id ->
                        val r = reading
                        if (r == null) {
                            Toast.makeText(context, "The compass isn't ready yet — move the phone a little", Toast.LENGTH_SHORT).show()
                            return@DeviceBubbleArc
                        }
                        HapticUtil.performClick(haptics)
                        scope.launch {
                            FlickAimStore.teach(context, id, r)
                            justTaught = id
                            pulse.snapTo(1f)
                            pulse.animateTo(0f, tween(600))
                            delay(400)
                            justTaught = null
                        }
                    }
                )
            }

            Spacer(Modifier.height(18.dp))
            val count = taught.size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.MyLocation,
                    contentDescription = null,
                    tint = if (count >= AimMatcher.MIN_TAUGHT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        count >= AimMatcher.MIN_TAUGHT && aimed != null -> "Aim is on — pointing at $aimed"
                        count >= AimMatcher.MIN_TAUGHT -> "Aim is on — not pointing at a computer"
                        count == 1 -> "Teach one more computer to turn aim on"
                        else -> "Teach two computers to turn aim on"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (taught.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                taught.keys.sortedBy { it.lowercase() }.forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        val a = taught[id]
                        Text(
                            text = "$id · ${a?.heading?.roundToInt() ?: 0}°",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { scope.launch { FlickAimStore.forget(context, id) } }) { Text("Forget") }
                    }
                }
                if (taught.size > 1) {
                    TextButton(onClick = { scope.launch { FlickAimStore.forgetAll(context) } }) { Text("Forget all") }
                }
            }
        }
    }
}

@Composable
private fun HowItPicksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Where a flick goes", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            listOf(
                "1. The bubble you tap or throw at",
                "2. The computer you point the phone at (with aim taught)",
                "3. The computer you're using right now (In use)",
                "4. The computer you used last",
                "5. Your main computer"
            ).forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Play on… and the Play on computer tile follow the same order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
