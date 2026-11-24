package com.sameerasw.airsync.multidesk

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.home.CardStatus
import com.sameerasw.airsync.home.DeviceActions
import com.sameerasw.airsync.home.DeviceCardUi
import com.sameerasw.airsync.home.DeviceCards
import com.sameerasw.airsync.home.HomeFocus
import com.sameerasw.airsync.home.MenuAction
import com.sameerasw.airsync.home.PauseCopy
import com.sameerasw.airsync.home.PrimaryAction
import com.sameerasw.airsync.home.QuickAction
import com.sameerasw.airsync.home.UndoTicket
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.PhoneAudioRemote
import kotlinx.coroutines.launch

/**
 * H.I.B Home — "Your devices": every paired computer (Mac or Windows) as an equal card with its status,
 * battery, what it is playing and one-tap actions that go to *that* computer. Pausing and forgetting live
 * in each card's ⋯ menu behind a confirmation, and come with Undo. Adding a computer never unpairs others.
 * Which computer rides the legacy primary connection is an internal detail and never shows here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YourDevicesSection(
    appPaused: Boolean,
    /** Scan a pairing QR code: [primary] when it should (re)pair the computer on the primary connection. */
    onScan: (primary: Boolean) -> Unit,
    /** Connect the computer on the primary connection (Home knows the best address). */
    onConnectPrimary: () -> Unit,
    /** Switch to the Remote tab (its actions then go to the picked computer). */
    onOpenRemote: () -> Unit,
    /** Pause H.I.B as a whole (already confirmed). */
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    /** Show a snackbar with Undo for [UndoTicket]. */
    onUndoable: (UndoTicket) -> Unit,
    /** Show a plain snackbar. */
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val desktops by DesktopHub.desktops.collectAsState()
    val live by ComputerLiveStore.live.collectAsState()
    val playingOn by PhoneAudioRemote.playingOn.collectAsState()
    val ds = remember { DataStoreManager.getInstance(context) }
    val primaryPausedByUser by ds.getUserManuallyDisconnected().collectAsState(initial = false)
    val focus by HomeFocus.computer.collectAsState()

    LaunchedEffect(Unit) { DesktopHub.start(context); DesktopHub.refresh() }

    val cards = remember(desktops, live, playingOn, appPaused, primaryPausedByUser) {
        DeviceCards.build(desktops, live, playingOn, appPaused, primaryPausedByUser)
    }

    var pendingTarget by remember { mutableStateOf<String?>(null) }
    var confirmPause by remember { mutableStateOf<DeviceCardUi?>(null) }
    var confirmForget by remember { mutableStateOf<DeviceCardUi?>(null) }
    var confirmPauseAll by remember { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }

    val flickPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)) { uris: List<Uri> ->
        val to = pendingTarget ?: return@rememberLauncherForActivityResult
        DeviceActions.flick(context, to, uris)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val to = pendingTarget ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val n = DeviceActions.sendFiles(context, to, uris)
            onMessage(
                when (n) {
                    0 -> "Couldn't send to $to"
                    1 -> "Sending 1 file to $to"
                    else -> "Sending $n files to $to"
                }
            )
        }
    }

    fun quick(card: DeviceCardUi, action: QuickAction) {
        HapticUtil.performClick(haptics)
        HomeFocus.clear()
        when (action) {
            QuickAction.FLICK -> {
                pendingTarget = card.name
                flickPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            QuickAction.SEND_FILES -> {
                pendingTarget = card.name
                try {
                    filePicker.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    Toast.makeText(context, "No file picker on this phone", Toast.LENGTH_SHORT).show()
                }
            }
            QuickAction.DESK_DECK -> DeviceActions.openDeskDeck(context, card.name)
            QuickAction.REMOTE -> {
                DeviceActions.pickForRemote(card.name)
                onOpenRemote()
            }
            QuickAction.PLAY -> if (!DeviceActions.togglePlay(card.name)) onMessage("Couldn't reach ${card.name}")
        }
    }

    fun primary(card: DeviceCardUi, action: PrimaryAction) {
        HapticUtil.performClick(haptics)
        when (action) {
            PrimaryAction.CONNECT, PrimaryAction.RESUME -> DeviceActions.resumeComputer(context, card, onConnectPrimary)
            PrimaryAction.PAIR_AGAIN -> onScan(card.viaPrimaryLink)
            PrimaryAction.RESUME_ALL -> onResumeAll()
        }
    }

    fun menu(card: DeviceCardUi, action: MenuAction) {
        HapticUtil.performClick(haptics)
        when (action) {
            MenuAction.LOCK ->
                onMessage(if (DeviceActions.remoteControl(card.name, "lock_screen")) "Locking ${card.name}" else "Couldn't reach ${card.name}")
            MenuAction.SCREENSAVER ->
                onMessage(if (DeviceActions.remoteControl(card.name, "screensaver")) "Screensaver on ${card.name}" else "Couldn't reach ${card.name}")
            MenuAction.PAUSE -> confirmPause = card
            MenuAction.RESUME -> DeviceActions.resumeComputer(context, card, onConnectPrimary)
            MenuAction.FORGET -> confirmForget = card
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Header: the same for one computer or five; no computer is "the main one".
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Your devices", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                val summary = DeviceCards.summary(cards, appPaused)
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box {
                IconButton(onClick = { HapticUtil.performClick(haptics); headerMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More for every computer")
                }
                DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Add computer") },
                        leadingIcon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) },
                        onClick = { headerMenu = false; onScan(DesktopHub.primaryName == null) }
                    )
                    if (appPaused) {
                        DropdownMenuItem(
                            text = { Text("Resume H.I.B") },
                            leadingIcon = { Icon(Icons.Rounded.PlayCircleOutline, contentDescription = null) },
                            onClick = { headerMenu = false; onResumeAll() }
                        )
                    } else if (cards.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Pause H.I.B…") },
                            leadingIcon = { Icon(Icons.Rounded.PauseCircleOutline, contentDescription = null) },
                            onClick = { headerMenu = false; confirmPauseAll = true }
                        )
                    }
                }
            }
        }

        RoundedCardContainer {
            if (cards.isEmpty()) {
                EmptyDevicesCard(onScan = { HapticUtil.performClick(haptics); onScan(DesktopHub.primaryName == null) })
            } else {
                cards.forEach { card ->
                    DeviceCard(
                        card = card,
                        onQuick = { quick(card, it) },
                        onPrimary = { primary(card, it) },
                        onMenu = { menu(card, it) }
                    )
                }
                AddComputerRow(onClick = { HapticUtil.performClick(haptics); onScan(DesktopHub.primaryName == null) })
            }
        }
    }

    // A computer tapped in the Devices widget: its card, ready to act.
    val focused = focus?.let { f -> cards.firstOrNull { PairedDesktops.sameName(it.name, f) } }
    if (focused != null) {
        ModalBottomSheet(
            onDismissRequest = { HomeFocus.clear() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                RoundedCardContainer {
                    DeviceCard(
                        card = focused,
                        onQuick = { quick(focused, it) },
                        onPrimary = { HomeFocus.clear(); primary(focused, it) },
                        onMenu = { HomeFocus.clear(); menu(focused, it) }
                    )
                }
            }
        }
    }

    confirmPause?.let { card ->
        ConfirmSheet(
            icon = Icons.Rounded.PauseCircleOutline,
            title = PauseCopy.title(card.name),
            body = PauseCopy.body(card.name),
            confirm = PauseCopy.CONFIRM,
            onDismiss = { confirmPause = null },
            onConfirm = {
                confirmPause = null
                onUndoable(DeviceActions.pauseComputer(context, card))
            }
        )
    }

    if (confirmPauseAll) {
        ConfirmSheet(
            icon = Icons.Rounded.PauseCircleOutline,
            title = PauseCopy.PAUSE_ALL_TITLE,
            body = PauseCopy.PAUSE_ALL_BODY,
            confirm = PauseCopy.PAUSE_ALL_CONFIRM,
            onDismiss = { confirmPauseAll = false },
            onConfirm = {
                confirmPauseAll = false
                onPauseAll()
                onUndoable(DeviceActions.recordPauseAll())
            }
        )
    }

    confirmForget?.let { card ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(PauseCopy.forgetTitle(card.name)) },
            text = { Text(PauseCopy.forgetBody(card.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmForget = null
                        scope.launch { onUndoable(DeviceActions.forget(context, card)) }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text(PauseCopy.FORGET_CONFIRM) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text("Cancel") } }
        )
    }
}

/** One computer. The same layout for every computer; only "in use" adds a light accent. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceCard(
    card: DeviceCardUi,
    onQuick: (QuickAction) -> Unit,
    onPrimary: (PrimaryAction) -> Unit,
    onMenu: (MenuAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        border = if (card.highlighted) BorderStroke(1.5.dp, accent.copy(alpha = 0.55f)) else null
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (card.highlighted) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = deviceIcon(card.platform),
                            contentDescription = null,
                            tint = if (card.highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceBright)
                            .padding(2.5.dp)
                            .clip(CircleShape)
                            .background(statusColor(card.status))
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = card.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (card.status == CardStatus.IN_USE) {
                            Spacer(Modifier.width(6.dp))
                            InUsePill()
                        }
                    }
                    Text(
                        text = listOfNotNull(
                            card.statusLabel.takeIf { card.status != CardStatus.IN_USE },
                            card.kindLabel,
                            card.detail
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (card.status == CardStatus.NEEDS_PAIRING) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                card.battery?.let { BatteryChip(it, card.charging) }
                Box {
                    IconButton(onClick = { HapticUtil.performClick(haptics); menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${card.name}")
                    }
                    DeviceMenu(card, menuOpen, onDismiss = { menuOpen = false }) { menuOpen = false; onMenu(it) }
                }
            }

            val playingLine = card.nowPlaying ?: if (card.playingPhoneAudio) "Playing this phone's audio" else null
            AnimatedVisibility(
                visible = playingLine != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .padding(top = 12.dp, end = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(playingLine.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.padding(end = 12.dp)) {
                when {
                    card.quickActions.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        card.quickActions.forEach { a ->
                            QuickActionButton(
                                label = quickLabel(a, card),
                                painter = quickPainter(a),
                                description = quickDescription(a, card),
                                active = a == QuickAction.PLAY && card.playingPhoneAudio,
                                modifier = Modifier.weight(1f)
                            ) { onQuick(a) }
                        }
                    }
                    card.status == CardStatus.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    card.primaryAction != null -> {
                        val a = card.primaryAction
                        val strong = a == PrimaryAction.RESUME || a == PrimaryAction.RESUME_ALL || a == PrimaryAction.PAIR_AGAIN
                        if (strong) {
                            Button(onClick = { onPrimary(a) }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                                Text(primaryLabel(a), style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            OutlinedButton(onClick = { onPrimary(a) }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                                Text(primaryLabel(a), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceMenu(card: DeviceCardUi, expanded: Boolean, onDismiss: () -> Unit, onPick: (MenuAction) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            card.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        // The connection items sit apart from the everyday ones; Forget is red.
        val firstConnection = card.menu.indexOfFirst { it == MenuAction.PAUSE || it == MenuAction.RESUME || it == MenuAction.FORGET }
        card.menu.forEachIndexed { i, a ->
            if (i == firstConnection && i > 0) HorizontalDivider()
            val danger = a == MenuAction.FORGET
            DropdownMenuItem(
                text = { Text(menuLabel(a)) },
                leadingIcon = { Icon(menuIcon(a), contentDescription = null) },
                colors = if (danger) MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error
                ) else MenuDefaults.itemColors(),
                onClick = { onPick(a) }
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    painter: Painter,
    description: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(60.dp).semantics { contentDescription = description }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        ) {
            Icon(painter, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BatteryChip(level: Int, charging: Boolean) {
    Row(
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = "Battery $level percent" + if (charging) ", charging" else "" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.Battery5Bar,
            contentDescription = null,
            tint = if (level <= 15 && !charging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text("$level%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddComputerRow(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Add computer", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Scan the QR code in H.I.B on your Mac or Windows PC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyDevicesCard(onScan: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("mac", "windows").forEach { p ->
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) { Icon(deviceIcon(p), contentDescription = null, modifier = Modifier.size(28.dp)) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Add your first computer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Open H.I.B on your Mac or Windows PC and scan its QR code. You can add as many computers as you like.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onScan, contentPadding = PaddingValues(horizontal = 20.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan QR code")
            }
        }
    }
}

/**
 * Computers H.I.B can see on this network that aren't paired yet. Pairing needs the code they show, so a
 * tap opens the QR scanner (never a blind connect with the wrong key).
 */
@Composable
fun NearbyComputersCard(names: List<Pair<String, String>>, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Nearby",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp)
        )
        RoundedCardContainer {
            names.forEach { (name, type) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) { Icon(deviceIcon(type), contentDescription = null, modifier = Modifier.size(22.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Not paired · tap to scan its QR code",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Pair $name", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A short sheet that says what an action will do before it happens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    icon: ImageVector,
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f).height(48.dp)) { Text(confirm, maxLines = 1) }
            }
        }
    }
}

private fun statusColor(s: CardStatus): Color = when (s) {
    CardStatus.IN_USE -> HibLime
    CardStatus.CONNECTED -> Color(0xFF34C759)
    CardStatus.CONNECTING -> Color(0xFFFFB300)
    CardStatus.NEEDS_PAIRING -> Color(0xFFE53935)
    CardStatus.OFFLINE, CardStatus.PAUSED -> Color(0xFF9E9E9E)
}

private fun quickLabel(a: QuickAction, card: DeviceCardUi) = when (a) {
    QuickAction.FLICK -> "Flick"
    QuickAction.SEND_FILES -> "Files"
    QuickAction.DESK_DECK -> "Deck"
    QuickAction.REMOTE -> "Remote"
    QuickAction.PLAY -> if (card.playingPhoneAudio) "Playing" else "Play"
}

private fun quickDescription(a: QuickAction, card: DeviceCardUi) = when (a) {
    QuickAction.FLICK -> "Flick photos to ${card.name}"
    QuickAction.SEND_FILES -> "Send files to ${card.name}"
    QuickAction.DESK_DECK -> "Desk Deck for ${card.name}"
    QuickAction.REMOTE -> "Remote for ${card.name}"
    QuickAction.PLAY -> if (card.playingPhoneAudio) "Stop playing on ${card.name}" else "Play this phone's audio on ${card.name}"
}

@Composable
private fun quickPainter(a: QuickAction): Painter = when (a) {
    QuickAction.FLICK -> rememberVectorPainter(Icons.Rounded.SwipeUp)
    QuickAction.SEND_FILES -> rememberVectorPainter(Icons.Rounded.UploadFile)
    QuickAction.DESK_DECK -> rememberVectorPainter(Icons.Rounded.Dashboard)
    QuickAction.REMOTE -> rememberVectorPainter(Icons.Rounded.Gamepad)
    QuickAction.PLAY -> painterResource(R.drawable.rounded_music_cast_24)
}

private fun primaryLabel(a: PrimaryAction) = when (a) {
    PrimaryAction.CONNECT -> "Connect"
    PrimaryAction.RESUME -> "Resume connection"
    PrimaryAction.PAIR_AGAIN -> "Scan its QR code to pair again"
    PrimaryAction.RESUME_ALL -> "Resume H.I.B"
}

private fun menuLabel(a: MenuAction) = when (a) {
    MenuAction.LOCK -> "Lock"
    MenuAction.SCREENSAVER -> "Start screensaver"
    MenuAction.PAUSE -> "Pause connection…"
    MenuAction.RESUME -> "Resume connection"
    MenuAction.FORGET -> "Forget this computer…"
}

private fun menuIcon(a: MenuAction): ImageVector = when (a) {
    MenuAction.LOCK -> Icons.Rounded.Lock
    MenuAction.SCREENSAVER -> Icons.Rounded.Wallpaper
    MenuAction.PAUSE -> Icons.Rounded.PauseCircleOutline
    MenuAction.RESUME -> Icons.Rounded.PlayCircleOutline
    MenuAction.FORGET -> Icons.Rounded.DeleteOutline
}
