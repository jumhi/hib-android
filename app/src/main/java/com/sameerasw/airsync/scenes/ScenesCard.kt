package com.sameerasw.airsync.scenes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sameerasw.airsync.R
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.multidesk.PointChoice
import com.sameerasw.airsync.multidesk.PointLabels
import com.sameerasw.airsync.multidesk.PointPress
import com.sameerasw.airsync.multidesk.PointPressAim
import com.sameerasw.airsync.multidesk.PointTargetChip
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Scenes" card: one button per scene the connected computers marked "Show on phone". Hidden when there are
 * none. Point to act (SPACE.md §11): nothing tracks while Home is open; pressing a scene reads where the phone
 * points, runs it on that computer, glows that screen once ("Scenes") and shows "→ computer" for a moment.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScenesCard(modifier: Modifier = Modifier) {
    val byComputer by ScenesRemote.scenesBy.collectAsState()
    val running by ScenesRemote.running.collectAsState()
    val desktops by DesktopHub.desktops.collectAsState()
    val connected = desktops.filter { it.isConnected }.map { it.name }
    val owners = connected.associateWith { n -> byComputer[PairedDesktops.normalizeName(n)].orEmpty().map { it.id } }
    val unknown = byComputer[""].orEmpty()
    val scenes = (connected.flatMap { byComputer[PairedDesktops.normalizeName(it)].orEmpty() } + unknown).distinctBy { it.id }
    if (scenes.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The aim is read only when a scene is pressed (the compass starts on finger down).
    val press = remember { PointPressAim(context) }
    DisposableEffect(press) { onDispose { press.cancel() } }
    var picked by remember { mutableStateOf<PointChoice?>(null) }
    LaunchedEffect(picked) {
        if (picked != null) {
            delay(PointPress.CHIP_MS)
            picked = null
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "Scenes",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                PointTargetChip(computer = picked?.computer, aimed = picked?.aimed == true)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                scenes.forEach { scene -> key(scene.id) {
                    val isRunning = scene.id in running
                    val interaction = remember { MutableInteractionSource() }
                    LaunchedEffect(interaction) {
                        interaction.interactions.collect {
                            when (it) {
                                is PressInteraction.Press -> press.down()
                                is PressInteraction.Cancel -> press.cancel()
                            }
                        }
                    }
                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            scope.launch {
                                val choice = press.pick(PointLabels.SCENES)
                                picked = choice
                                val to = PointPress.sceneTarget(choice, scene.id, owners, unknown.map { it.id })
                                val message = when {
                                    choice == null -> if (ScenesRemote.run(scene.id)) null else "Your computer isn't connected"
                                    to == null -> "“${scene.name}” isn't on ${choice.computer}"
                                    !ScenesRemote.run(scene.id, to) -> "Couldn't reach $to"
                                    else -> null
                                }
                                message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                            }
                        },
                        interactionSource = interaction,
                        enabled = !isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                painter = painterResource(id = iconFor(scene.icon)),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(scene.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                } }
            }
        }
    }
}

/** Maps the Mac's SF Symbol name to a bundled drawable. */
private fun iconFor(sfSymbol: String): Int = when {
    sfSymbol.contains("moon") || sfSymbol.contains("bed") -> R.drawable.rounded_dark_mode_24
    sfSymbol.contains("lock") -> R.drawable.rounded_lock_24
    sfSymbol.contains("bell") -> R.drawable.rounded_notifications_active_24
    sfSymbol.contains("headphones") || sfSymbol.contains("music") || sfSymbol.contains("hifi") -> R.drawable.rounded_music_cast_24
    sfSymbol.contains("wifi") -> R.drawable.rounded_android_wifi_3_bar_24
    sfSymbol.contains("sun") -> R.drawable.rounded_brightness_7_24
    sfSymbol.contains("tv") || sfSymbol.contains("display") -> R.drawable.rounded_smart_display_24
    sfSymbol.contains("gamecontroller") -> R.drawable.rounded_gamepad_circle_up_24
    sfSymbol.contains("figure") || sfSymbol.contains("person") -> R.drawable.rounded_devices_24
    else -> R.drawable.rounded_star_shine_24
}

// MARK: - Permissions (shown on the Permissions screen)

object ScenesPermissions {
    const val DND = "Do Not Disturb Access"
    const val LOCATION = "Location (Wi-Fi Name)"

    fun hasFineLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Optional permissions Scenes would like; appended to the Permissions screen's optional list. */
    fun missing(context: Context): List<String> {
        val list = mutableListOf<String>()
        if (!ScenePhoneActions.hasPolicyAccess(context)) list.add(DND)
        if (!hasFineLocation(context) || !hasBackgroundLocation(context)) list.add(LOCATION)
        return list
    }
}

/** One Permissions-screen row for a Scenes permission (see [ScenesPermissions]). */
@Composable
fun ScenesPermissionRow(permission: String) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) openAppSettings(context)
    }

    val description = when (permission) {
        ScenesPermissions.DND -> "Lets Scenes on your computer turn Do Not Disturb on/off and set silent mode"
        else -> if (!ScenesPermissions.hasFineLocation(context))
            "Lets Scenes see which Wi-Fi network you're on (optional)"
        else "Choose “Allow all the time” so Wi-Fi names work in the background"
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraSmall) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = permission,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            OutlinedButton(
                onClick = {
                    HapticUtil.performClick(haptics)
                    when (permission) {
                        ScenesPermissions.DND -> {
                            try {
                                val direct = Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS")
                                    .setData(Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (android.os.Build.VERSION.SDK_INT < 35 ||
                                    runCatching { context.startActivity(direct) }.isFailure
                                ) {
                                    context.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            } catch (e: Exception) {
                                openAppSettings(context)
                            }
                        }
                        else -> {
                            if (!ScenesPermissions.hasFineLocation(context)) {
                                fineLauncher.launch(
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                )
                            } else {
                                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        }
                    }
                },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Open")
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) { }
}
