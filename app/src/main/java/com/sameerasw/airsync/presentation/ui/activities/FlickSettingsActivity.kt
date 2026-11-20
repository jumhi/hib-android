package com.sameerasw.airsync.presentation.ui.activities

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Button
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
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.FlickScreenStore
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.multidesk.ScreenComputer
import com.sameerasw.airsync.multidesk.ScreenMap
import com.sameerasw.airsync.multidesk.deviceIcon
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.FlickAim
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * H.I.B — Flick settings. Point & Flick (SPACE.md §7): every screen of every connected computer, whether
 * Point setup has mapped it, where the phone points now, and "Point setup again".
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
            PointAndFlickCard()
            HowItPicksCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PointAndFlickCard() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val desktops by DesktopHub.desktops.collectAsState()
    val screensByComputer by DesktopHub.screens.collectAsState()
    val inUseId by DesktopHub.inUseId.collectAsState()
    val savedFlow = remember { FlickScreenStore.flow(context) }
    val saved by savedFlow.collectAsState(initial = emptyList())
    val compass = remember { FlickAim.isAvailable(context) }
    var reading by remember { mutableStateOf<AimMatcher.Aim?>(null) }

    LaunchedEffect(Unit) {
        DesktopHub.requestScreens()
        while (true) {
            reading = FlickAim.current()?.let { AimMatcher.Aim(it.first, it.second) }
            delay(100)
        }
    }
    val computers = desktops.filter { it.isConnected }.map {
        ScreenComputer(it.name, screensByComputer[PairedDesktops.normalizeName(it.name)])
    }
    val targets = ScreenMap.targets(computers, inUseId)
    val aimed = ScreenMap.nearest(reading, saved, computers)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Point & Flick", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Point your phone at a screen and flick: the card lands on that screen, on whichever computer it " +
                    "belongs to. The screen glows while you point at it. Point setup maps where each screen is; " +
                    "it runs by itself the first time you flick.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            if (!compass) {
                Text(
                    "This phone has no compass, so Flick goes by the order below instead of pointing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Explore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = -(reading?.heading ?: 0f) }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        reading == null -> "Waiting for the compass…"
                        aimed != null -> "Pointing at ${aimed.title()}"
                        targets.size > 1 -> "Not pointing at a screen"
                        else -> {
                            val r = reading!!
                            val e = r.elevation.roundToInt()
                            "Pointing ${r.heading.roundToInt()}° · ${if (e >= 0) "$e° up" else "${-e}° down"}"
                        }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(14.dp))

            when {
                desktops.isEmpty() -> Text(
                    "Pair a computer first (Your devices → Add computer).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                computers.isEmpty() -> Text(
                    "Connect a computer to see its screens.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> targets.forEach { t ->
                    val isSet = ScreenMap.isSaved(t, saved)
                    val lit = aimed != null && t.sameAs(aimed.computer, aimed.screen)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        val platform = desktops.firstOrNull { PairedDesktops.sameName(it.name, t.computer) }?.platform
                        Icon(
                            deviceIcon(platform),
                            contentDescription = null,
                            tint = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = t.title(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val detail = ScreenMap.friendlyName(
                                com.sameerasw.airsync.multidesk.ComputerScreen(t.screen, t.displayName)
                            )?.takeIf { it != t.label && t.screensOnComputer > 1 }
                            if (detail != null) {
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(
                            text = if (isSet) "Set" else "Not set up",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            if (targets.size >= 2) {
                Button(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        context.startActivity(FlickActivity.pointSetupIntent(context))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Point setup again")
                }
            } else if (computers.isNotEmpty()) {
                Text(
                    "With one screen there's nothing to point at: every flick goes to it. Point setup starts once " +
                        "a second screen or computer is connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (saved.isNotEmpty()) {
                TextButton(onClick = { scope.launch { FlickScreenStore.forgetAll(context) } }) {
                    Text("Forget all screens")
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
                "1. The screen you point the phone at",
                "2. The computer you're using right now (In use)",
                "3. The computer you used last",
                "4. Your main computer"
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
