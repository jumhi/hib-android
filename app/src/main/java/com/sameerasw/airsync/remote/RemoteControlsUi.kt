package com.sameerasw.airsync.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R

// MARK: - mode switch (top bar)

/** Touchpad | Direct (SPACE.md §15.5): an icon pill; the picked mode also shows its name. */
@Composable
fun RemoteModeSwitch(mode: RemoteSettings.ControlMode, onMode: (RemoteSettings.ControlMode) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeSegment(Icons.Rounded.Mouse, "Touchpad", mode == RemoteSettings.ControlMode.TOUCHPAD) {
                onMode(RemoteSettings.ControlMode.TOUCHPAD)
            }
            ModeSegment(Icons.Rounded.TouchApp, "Direct", mode == RemoteSettings.ControlMode.DIRECT) {
                onMode(RemoteSettings.ControlMode.DIRECT)
            }
        }
    }
}

@Composable
private fun ModeSegment(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "modeBg"
    )
    Surface(onClick = onClick, shape = CircleShape, color = bg) {
        Row(
            Modifier.height(36.dp).padding(horizontal = if (selected) 12.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, contentDescription = "$label mode", modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(visible = selected, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

// MARK: - touchpad surface

/**
 * The big touchpad (SPACE.md §15.5): a quiet surface with a one-line reminder of the gestures. While the app
 * switcher is held it says how to step through the apps.
 */
@Composable
fun RemoteTouchpadSurface(
    isWindows: Boolean,
    switcherOpen: Boolean,
    notice: String?,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val border by animateColorAsState(
        if (switcherOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        label = "padBorder"
    )
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(if (switcherOpen) 2.dp else 1.dp, border, RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center
    ) {
        when {
            switcherOpen -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap or swipe for the next app · let go to switch",
                    style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            notice != null -> Text(
                notice, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)
            )
            else -> Icon(
                painter = painterResource(id = R.drawable.rounded_drag_click_24),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 28.dp else 44.dp).alpha(0.12f),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        if (!switcherOpen && notice == null && !compact) {
            Text(
                "Tap: click · 2 fingers: scroll, tap = right-click · Double-tap & hold: drag · " +
                    "3 fingers ↔: ${if (isWindows) "Alt+Tab" else "⌘Tab"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

// MARK: - shortcut row

/**
 * The shortcut row (SPACE.md §15.5). "Switch apps" stays pinned at the start (outside the scrolling list, so a
 * scroll never presses it): press and hold keeps ⌘/Alt held while you tap the touchpad or Tab, let go to switch.
 * The rest scroll: Copy … arrows (hold to repeat), the user's own combos, and "+" to add one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteShortcutRow(
    isWindows: Boolean,
    custom: List<CustomCombo>,
    switcherOpen: Boolean,
    onCombo: (Combo) -> Unit,
    onSwitchPress: () -> Unit,
    onSwitchRelease: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (CustomCombo) -> Unit,
    modifier: Modifier = Modifier
) {
    val row = remember(isWindows) { Shortcuts.row(isWindows) }
    val switch = row.first { it.switcher }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HoldChip(switch, held = switcherOpen, onPress = onSwitchPress, onRelease = onSwitchRelease)
        Spacer(Modifier.width(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 4.dp),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
        ) {
            items(row.filter { !it.switcher }, key = { it.id }) { item ->
                val combo = item.combo ?: return@items
                if (item.arrow) RepeatChip(item, onFire = { onCombo(combo) })
                else ShortcutChip(item.title, item.keys, onClick = { onCombo(combo) })
            }
            items(custom, key = { "c:" + it.mods.joinToString() + it.keycode }) { c ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    ShortcutChip(
                        c.label(isWindows), "Yours",
                        onClick = { onCombo(c.combo) }, onLongClick = { menu = true }, accent = true
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Remove ${c.label(isWindows)}") }, onClick = { menu = false; onRemove(c) })
                    }
                }
            }
            item(key = "add") {
                Surface(
                    onClick = onAdd, shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(Modifier.size(CHIP_H), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add a shortcut")
                    }
                }
            }
        }
    }
}

private val CHIP_H = 52.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutChip(title: String, keys: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, accent: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (accent) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(
            Modifier.height(CHIP_H).widthIn(min = 56.dp).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                color = if (accent) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface
            )
            if (keys.isNotEmpty()) {
                Text(
                    keys, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (accent) MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Press = held (ⓘ the app switcher): [onPress] on finger down, [onRelease] on up or cancel. */
@Composable
private fun HoldChip(item: ShortcutItem, held: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val bg by animateColorAsState(
        if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, label = "holdBg"
    )
    val fg = if (held) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        shape = RoundedCornerShape(16.dp), color = bg,
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                press()
                waitForUpOrCancellation()
                release()
            }
        }
    ) {
        Row(Modifier.height(CHIP_H).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(item.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
                Text(if (held) "Holding ${item.keys.substringBefore("Tab").trimEnd('+')}" else "Hold · " + item.keys,
                    style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f), maxLines = 1)
            }
        }
    }
}

/** An arrow: a tap presses once; holding repeats (after a pause, like a keyboard). A row scroll cancels it. */
@Composable
private fun RepeatChip(item: ShortcutItem, onFire: () -> Unit) {
    val fire by rememberUpdatedState(onFire)
    val icon = when (item.id) {
        "left" -> Icons.AutoMirrored.Rounded.ArrowBack
        "right" -> Icons.AutoMirrored.Rounded.ArrowForward
        "up" -> Icons.Rounded.ArrowUpward
        else -> Icons.Rounded.ArrowDownward
    }
    var pressed by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (pressed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                // null = still held after the pause; Lift(null) = the row took the gesture (a scroll).
                val first = withTimeoutOrNull(REPEAT_DELAY_MS) { Lift(waitForUpOrCancellation()) }
                when {
                    first == null -> {
                        fire()
                        while (withTimeoutOrNull(REPEAT_EVERY_MS) { Lift(waitForUpOrCancellation()) } == null) fire()
                    }
                    first.up != null -> fire()
                }
                pressed = false
            }
        }
    ) {
        Box(Modifier.size(CHIP_H), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = item.id.replaceFirstChar { it.uppercase() }, modifier = Modifier.size(22.dp))
        }
    }
}

private const val REPEAT_DELAY_MS = 380L
private const val REPEAT_EVERY_MS = 70L

// MARK: - key bar

/**
 * [⌨][Esc][Tab][⌘][⌥][⇧][⌃][⌫][↵] (Ctrl Alt Shift Win on a PC). Modifiers are sticky (SPACE.md §15.5): tap = the
 * next key only (tinted), double-tap = locked (filled) until tapped again.
 */
@Composable
fun RemoteKeyBar(
    isWindows: Boolean,
    keyboardOpen: Boolean,
    sticky: StickyMods,
    onKeyboard: () -> Unit,
    onKey: (Int) -> Unit,
    onMod: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BarKey(state = if (keyboardOpen) StickyMods.State.ONCE else StickyMods.State.OFF, onClick = onKeyboard) {
            Icon(Icons.Rounded.Keyboard, contentDescription = if (keyboardOpen) "Hide keyboard" else "Show keyboard", modifier = Modifier.size(20.dp))
        }
        BarKey(onClick = { onKey(MacKey.ESCAPE) }) { Text("Esc") }
        BarKey(onClick = { onKey(MacKey.TAB) }) { Text("Tab") }
        ModKey.entries.forEach { m ->
            val wire = m.wire(isWindows)
            BarKey(state = sticky.state(wire), onClick = { onMod(wire) }, minWidth = 44.dp) {
                Text(m.label(isWindows), fontWeight = FontWeight.SemiBold)
            }
        }
        BarKey(onClick = { onKey(MacKey.DELETE) }) {
            Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Delete", modifier = Modifier.size(18.dp))
        }
        BarKey(onClick = { onKey(MacKey.RETURN) }) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, contentDescription = "Return", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun BarKey(
    state: StickyMods.State = StickyMods.State.OFF,
    onClick: () -> Unit,
    minWidth: androidx.compose.ui.unit.Dp = 40.dp,
    content: @Composable () -> Unit
) {
    val (bg, fg) = when (state) {
        StickyMods.State.OFF -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurface
        StickyMods.State.ONCE -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        StickyMods.State.LOCKED -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    }
    Surface(
        onClick = onClick, shape = RoundedCornerShape(12.dp), color = bg, contentColor = fg,
        border = if (state == StickyMods.State.OFF) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    ) {
        Box(Modifier.height(44.dp).widthIn(min = minWidth).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            content()
            if (state == StickyMods.State.LOCKED) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(width = 14.dp, height = 3.dp)
                        .clip(CircleShape).background(fg)
                )
            }
        }
    }
}

// MARK: - add a shortcut

/** "+": pick modifiers and one key; saved for this computer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteAddComboDialog(isWindows: Boolean, onDismiss: () -> Unit, onSave: (CustomCombo) -> Unit) {
    var mods by remember { mutableStateOf(listOf<String>()) }
    var char by remember { mutableStateOf("") }
    var special by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val stroke = char.firstOrNull()?.let { UsKeyboard.stroke(it) }
    val key: Pair<String, Int>? = special ?: stroke?.let { char.uppercase() to it.keycode }
    val allMods = if (stroke?.shift == true && special == null && "shift" !in mods) mods + "shift" else mods
    val result = key?.let { CustomCombo(allMods, it.second, it.first) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModKey.entries.forEach { m ->
                        val w = m.wire(isWindows)
                        FilterChip(
                            selected = w in mods,
                            onClick = { mods = if (w in mods) mods - w else mods + w },
                            label = { Text(m.label(isWindows)) }
                        )
                    }
                }
                OutlinedTextField(
                    value = char,
                    onValueChange = { v -> char = v.takeLast(1); if (char.isNotEmpty()) special = null },
                    label = { Text("Key") },
                    placeholder = { Text("A letter, number or symbol") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    isError = char.isNotEmpty() && stroke == null,
                    supportingText = { if (char.isNotEmpty() && stroke == null) Text("Use a key from a US keyboard") }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CustomCombo.SPECIAL_KEYS.forEach { k ->
                        FilterChip(
                            selected = special == k,
                            onClick = { special = if (special == k) null else k; if (special != null) char = "" },
                            label = { Text(k.first) }
                        )
                    }
                }
                if (result != null) {
                    Text(
                        result.label(isWindows), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { result?.let(onSave) }, enabled = result != null) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private class Lift(val up: PointerInputChange?)
