package com.sameerasw.airsync.deskdeck

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import com.sameerasw.airsync.utils.PhoneAudioRemote
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

/**
 * H.I.B Desk Deck: a full-screen, always-on control deck for the Mac.
 * Put the phone on a stand next to the Mac.
 */
class DeskDeckActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, DeskDeckActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            AirSyncTheme(darkTheme = true, pitchBlackTheme = true) {
                DeskDeckScreen(onClose = { finish() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DeskDeckRemote.setOpen(true)
    }

    override fun onStop() {
        DeskDeckRemote.setOpen(false)
        super.onStop()
    }
}

private val DeckBg = Color(0xFF000000)
private val TileBg = Color(0xFF101114)
private val CardBg = Color(0xFF0C0D10)
private val Dim = Color(0xFF8A8F98)

@Composable
private fun DeskDeckScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tiles by DeskDeckStore.tiles(context).collectAsState(initial = DeskDeckStore.defaultTiles)
    val count by DeskDeckStore.tileCount(context).collectAsState(initial = 9)
    val stats by DeskDeckRemote.stats.collectAsState()
    val result by DeskDeckRemote.lastResult.collectAsState()

    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Clock tick + keep the Mac streaming (re-announce if the stream went stale, e.g. after a reconnect).
    LaunchedEffect(Unit) {
        var sinceAnnounce = 0
        while (true) {
            now = System.currentTimeMillis()
            sinceAnnounce++
            val last = DeskDeckRemote.stats.value?.receivedAt ?: 0L
            if (sinceAnnounce >= 8 && now - last > 6000) {
                DeskDeckRemote.setOpen(true)
                sinceAnnounce = 0
            }
            delay(1000)
        }
    }

    val visible = tiles.take(count)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DeckBg)
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(0.42f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ClockBlock(now, onClose, onSettings = { showSettings = true })
                    NowPlayingCard(Modifier.fillMaxWidth())
                    EventCard(stats, now)
                    StatsRow(stats, now)
                }
                TileGrid(
                    tiles = visible,
                    columns = if (count > 9) 4 else 3,
                    modifier = Modifier.weight(0.58f).fillMaxHeight(),
                    onLongPress = { editingIndex = it }
                )
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ClockBlock(now, onClose, onSettings = { showSettings = true })
                NowPlayingCard(Modifier.fillMaxWidth())
                EventCard(stats, now)
                StatsRow(stats, now)
                TileGrid(
                    tiles = visible,
                    columns = 3,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onLongPress = { editingIndex = it }
                )
            }
        }

        // Error pill for failed actions
        val showError = result?.let { !it.ok && now - it.at < 4000 } == true
        AnimatedVisibility(
            visible = showError,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
        ) {
            Text(
                text = result?.error ?: "Action failed",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF3A1212))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }

    editingIndex?.let { index ->
        EditTileDialog(
            current = tiles.getOrNull(index) ?: DeckTile(DeckAction.LOCK),
            onDismiss = { editingIndex = null },
            onSave = { newTile ->
                val updated = tiles.toMutableList().also { it[index] = newTile }
                scope.launch { DeskDeckStore.saveTiles(context, updated) }
                editingIndex = null
            }
        )
    }

    if (showSettings) {
        DeckSettingsDialog(
            count = count,
            onDismiss = { showSettings = false },
            onSave = { c ->
                scope.launch { DeskDeckStore.saveTileCount(context, c) }
                showSettings = false
            }
        )
    }
}

@Composable
private fun ClockBlock(now: Long, onClose: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", Date(now)).toString()
    val date = DateFormat.format("EEEE, d MMMM", Date(now)).toString()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                time,
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 64.sp
            )
            Text(date, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "Deck settings", tint = Dim)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = "Close Desk Deck", tint = Dim)
        }
    }
}

@Composable
private fun NowPlayingCard(modifier: Modifier) {
    val status by MacDeviceStatusManager.macDeviceStatus.collectAsState()
    val art by MacDeviceStatusManager.albumArt.collectAsState()
    val music = status?.music
    val haptics = LocalHapticFeedback.current
    val hasMusic = music != null && (music.title.isNotBlank() || music.artist.isNotBlank())

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CardBg)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(TileBg),
            contentAlignment = Alignment.Center
        ) {
            val bmp = art
            if (bmp != null && hasMusic) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                if (hasMusic) music!!.title.ifBlank { "Unknown" } else "Nothing playing on Mac",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hasMusic && music!!.artist.isNotBlank()) {
                Text(music.artist, color = Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); DeskDeckRemote.sendAction("previous") }) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = Color.White)
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    DeskDeckRemote.sendAction("play_pause")
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (music?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "Play/Pause",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); DeskDeckRemote.sendAction("next") }) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White)
        }
    }
}

@Composable
private fun EventCard(stats: DeskDeckRemote.MacStats?, now: Long) {
    val ev = stats?.nextEvent ?: return
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    val fmt = if (is24) "HH:mm" else "h:mm a"
    val startText = DateFormat.format(fmt, Date(ev.start)).toString()
    val endText = DateFormat.format(fmt, Date(ev.end)).toString()
    val minutes = (ev.start - now) / 60000
    val relative = when {
        now >= ev.start -> "Now"
        minutes < 60 -> "in ${minutes.coerceAtLeast(1)} min"
        minutes < 24 * 60 -> "in ${minutes / 60} h ${minutes % 60} min"
        else -> DateFormat.format("EEE", Date(ev.start)).toString()
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(ev.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull("$startText – $endText", ev.location).joinToString(" · "),
                color = Dim,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(relative, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatsRow(stats: DeskDeckRemote.MacStats?, now: Long) {
    val stale = stats == null || now - stats.receivedAt > 6000
    if (stale) {
        Text(
            "Waiting for your Mac…",
            color = Dim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        return
    }
    val s = stats!!
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatChip("CPU", "${s.cpu}%", Modifier.weight(1f), warn = s.cpu >= 85)
        StatChip("MEM", "${s.mem}%", Modifier.weight(1f), warn = s.memPressure != "normal")
        if (s.battery >= 0) {
            StatChip(if (s.charging) "BAT ⚡" else "BAT", "${s.battery}%", Modifier.weight(1f), warn = s.battery <= 15 && !s.charging)
        }
        StatChip("UP", formatUptime(s.uptimeSec), Modifier.weight(1f))
        StatChip("NET", "↑${formatRate(s.netUpKBs)}\n↓${formatRate(s.netDownKBs)}", Modifier.weight(1.2f))
    }
}

private fun formatUptime(sec: Long): String {
    val d = sec / 86400
    val h = (sec % 86400) / 3600
    val m = (sec % 3600) / 60
    return if (d > 0) "${d}d ${h}h" else "${h}h ${m}m"
}

private fun formatRate(kbs: Int): String =
    if (kbs >= 1024) String.format(java.util.Locale.US, "%.1fM", kbs / 1024f) else "${kbs}K"

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier, warn: Boolean = false) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(CardBg).padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = Dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(
            value,
            color = if (warn) Color(0xFFFF6B6B) else Color.White,
            fontSize = if (value.contains('\n')) 11.sp else 16.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 13.sp
        )
    }
}

@Composable
private fun TileGrid(
    tiles: List<DeckTile>,
    columns: Int,
    modifier: Modifier,
    onLongPress: (Int) -> Unit
) {
    val rows = tiles.chunked(columns)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { c, tile ->
                    DeckTileView(
                        tile = tile,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onLongPress = { onLongPress(r * columns + c) }
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckTileView(tile: DeckTile, modifier: Modifier, onLongPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "tileScale")
    val audioActive by PhoneAudioRemote.isActive.collectAsState()
    val active = tile.action == DeckAction.PHONE_AUDIO && audioActive
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(28.dp))
            .background(if (active) accent.copy(alpha = 0.22f) else TileBg)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    DeskDeckRemote.sendAction(tile.action.id, tile.arg)
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(tile.action.icon, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            tile.title,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EditTileDialog(current: DeckTile, onDismiss: () -> Unit, onSave: (DeckTile) -> Unit) {
    var selected by remember { mutableStateOf(current.action) }
    var arg by remember { mutableStateOf(current.arg ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tile action") },
        text = {
            Column {
                LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                    items(DeckAction.entries.toList()) { action ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = action }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == action, onClick = { selected = action })
                            Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(action.label)
                        }
                    }
                }
                when (selected.argKind) {
                    DeckArgKind.MAC_APP -> OutlinedTextField(
                        value = arg, onValueChange = { arg = it },
                        label = { Text("Mac app name (e.g. Safari)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    DeckArgKind.SHORTCUT -> OutlinedTextField(
                        value = arg, onValueChange = { arg = it },
                        label = {
                            Text(
                                if (selected == DeckAction.DND) "Shortcut that toggles Focus/DND"
                                else "Apple Shortcut name"
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    DeckArgKind.NONE -> {}
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = if (selected.argKind == DeckArgKind.NONE) null else arg.trim().ifBlank { null }
                onSave(DeckTile(selected, a))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DeckSettingsDialog(count: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(count) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Desk Deck") },
        text = {
            Column {
                Text("Tiles shown: $value")
                Slider(
                    value = value.toFloat(),
                    onValueChange = { value = it.toInt() },
                    valueRange = DeskDeckStore.MIN_TILES.toFloat()..DeskDeckStore.MAX_TILES.toFloat(),
                    steps = DeskDeckStore.MAX_TILES - DeskDeckStore.MIN_TILES - 1
                )
                Text(
                    "Long-press any tile to change what it does.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
