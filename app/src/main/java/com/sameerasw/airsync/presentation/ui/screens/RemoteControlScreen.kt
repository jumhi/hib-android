package com.sameerasw.airsync.presentation.ui.screens

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DesktopAccessDisabled
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.presentation.ui.components.KeyboardInputSheet
import com.sameerasw.airsync.presentation.ui.components.KeyboardModifiers
import com.sameerasw.airsync.presentation.ui.components.ModifierStatus
import com.sameerasw.airsync.remote.AbsMap
import com.sameerasw.airsync.remote.AppSwitcher
import com.sameerasw.airsync.remote.Combo
import com.sameerasw.airsync.remote.DirectGestures
import com.sameerasw.airsync.remote.KeyCompose
import com.sameerasw.airsync.remote.MacKey
import com.sameerasw.airsync.remote.PadAction
import com.sameerasw.airsync.remote.PictureGestures
import com.sameerasw.airsync.remote.PointerAccel
import com.sameerasw.airsync.remote.RemoteAddComboDialog
import com.sameerasw.airsync.remote.RemoteAddrStore
import com.sameerasw.airsync.remote.RemoteCard
import com.sameerasw.airsync.remote.RemoteComputerChips
import com.sameerasw.airsync.remote.RemoteDisplayPicker
import com.sameerasw.airsync.remote.RemoteHello
import com.sameerasw.airsync.remote.RemoteHub
import com.sameerasw.airsync.remote.RemoteInput
import com.sameerasw.airsync.remote.RemoteInputSender
import com.sameerasw.airsync.remote.RemoteJson
import com.sameerasw.airsync.remote.RemoteKeyBar
import com.sameerasw.airsync.remote.RemoteLockedCard
import com.sameerasw.airsync.remote.RemoteMenu
import com.sameerasw.airsync.remote.RemoteModeSwitch
import com.sameerasw.airsync.remote.RemotePicture
import com.sameerasw.airsync.remote.RemoteSettings
import com.sameerasw.airsync.remote.RemoteSettings.ControlMode
import com.sameerasw.airsync.remote.RemoteShortcutRow
import com.sameerasw.airsync.remote.RemoteStatusRow
import com.sameerasw.airsync.remote.RemoteStream
import com.sameerasw.airsync.remote.RemoteTouchpadSurface
import com.sameerasw.airsync.remote.RemoteUnlockFlow
import com.sameerasw.airsync.remote.RemoteUnreachableCard
import com.sameerasw.airsync.remote.RemoteViewState
import com.sameerasw.airsync.remote.ScreenStreamClient
import com.sameerasw.airsync.remote.ScrollInertia
import com.sameerasw.airsync.remote.Shortcuts
import com.sameerasw.airsync.remote.StickyMods
import com.sameerasw.airsync.remote.TouchpadGestures
import com.sameerasw.airsync.remote.ViewRect
import com.sameerasw.airsync.remote.padInput
import com.sameerasw.airsync.remote.phoneHasVpn
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * H.I.B Remote (SPACE.md §15, §15.5; design §5): pick a computer on the chips and drive it in one of two modes,
 * remembered per computer:
 * - **Touchpad** (default): the live picture on top (tap = bigger, swipe down = smaller, pinch = zoom) and a big
 *   dedicated touchpad below it: acceleration, tap / two-finger / drag gestures, three-finger app switching.
 * - **Direct**: the picture fills the Remote; tap where you want and the cursor jumps there and clicks.
 * Under both: the shortcut row (⌘Tab held, Copy, Paste… and your own) and the key bar with sticky modifiers.
 * Everything goes to the picked computer only, never to the one the phone points at.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemoteControlScreen(
    modifier: Modifier = Modifier,
    showKeyboard: Boolean,
    onDismissKeyboard: () -> Unit,
    /** This page is the one on screen (the picture only streams then). */
    isActive: Boolean = true,
    /** Space kept free at the bottom for the app's toolbar; the phone keyboard replaces it when taller. */
    bottomPadding: Dp = 0.dp,
    /** Leave the Remote (after Lock on a computer this phone unlocked). */
    onLeave: () -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings = remember { RemoteSettings(context) }

    // The computer this Remote drives: picked on the chips (or by a card's Remote button).
    val desktops by DesktopHub.desktops.collectAsState()
    val picked by RemoteHub.selected.collectAsState()
    val remotes by RemoteHub.computers.collectAsState()
    val unlockUis by RemoteUnlockFlow.ui.collectAsState()
    val inUse by DesktopHub.inUseId.collectAsState()
    val target: String? = remember(picked, desktops, inUse) {
        desktops.firstOrNull { d -> picked != null && PairedDesktops.sameName(d.name, picked!!) }?.name
            ?: desktops.firstOrNull { it.isConnected && inUse != null && PairedDesktops.sameName(it.name, inUse!!) }?.name
            ?: desktops.firstOrNull { it.isConnected }?.name
            ?: desktops.firstOrNull()?.name
    }
    val status = desktops.firstOrNull { target != null && PairedDesktops.sameName(it.name, target) }
    val connected = status?.isConnected == true
    val remote = target?.let { remotes[RemoteHub.key(it)] }
    val hello = remote?.hello
    val isWindows = (hello?.platform?.ifBlank { null } ?: status?.platform) == "windows"
    val locked = remote?.locked == true

    // Ask what the computer offers whenever it (re)connects or is picked.
    LaunchedEffect(target, connected) {
        if (target != null && connected) RemoteHub.requestHello(target)
    }

    // The picture streams only while this page is in front and the app is resumed (screen off → closed).
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val offersPicture = hello != null && hello.screen && hello.control
    val pictureMode = settings.pictureOn && offersPicture
    // Direct needs the picture; without it the Remote is a touchpad.
    val mode = if (pictureMode && target != null) settings.mode(target) else ControlMode.TOUCHPAD
    var display by remember(target) { mutableStateOf("") }
    var viewState by remember(target, display) { mutableStateOf(RemoteViewState()) }
    var lastViewChangeAt by remember { mutableLongStateOf(0L) }
    var expanded by remember(target, mode) { mutableStateOf(false) }

    val stream = remember { RemoteStream(context) }
    DisposableEffect(stream) { onDispose { stream.dispose() } }
    val streamUi by stream.ui.collectAsState()

    val (maxW, maxH, fps) = settings.streamSize(context)
    val wantPicture = isActive && resumed && pictureMode && connected
    LaunchedEffect(wantPicture, target, display, maxW, maxH, fps) {
        val t = target
        stream.want(if (wantPicture && t != null) RemoteStream.Want(t, display, maxW, maxH, fps) else null)
    }

    // The zoomed view goes to the computer (it re-crops, so zoomed text stays sharp), at most 20 times a second.
    LaunchedEffect(stream) {
        var sent = ViewRect.FULL
        while (true) {
            delay(50)
            val v = viewState.view
            if (RemoteViewState.differs(v, sent)) {
                stream.setView(v)
                sent = v
                lastViewChangeAt = System.currentTimeMillis()
            }
        }
    }
    // Touchpad mode, zoomed: the view follows the cursor (once the computer has caught up with the last view
    // change). Direct mode doesn't, so the picture never slides under the finger.
    LaunchedEffect(stream, mode) {
        if (mode != ControlMode.TOUCHPAD) return@LaunchedEffect
        stream.cursor.collect { c ->
            if (c.visible && System.currentTimeMillis() - lastViewChangeAt > 400) {
                val next = viewState.followCursor(c.x, c.y)
                if (next != viewState) viewState = next
            }
        }
    }

    // The picture's shape (the encoded view keeps the display's shape) and the display it shows.
    val shownDisplay = hello?.displays?.let { ds ->
        ds.firstOrNull { it.id == streamUi.display.ifBlank { display } } ?: ds.firstOrNull { it.main } ?: ds.firstOrNull()
    }
    val aspect = when {
        streamUi.w > 0 && streamUi.h > 0 -> streamUi.w.toFloat() / streamUi.h
        shownDisplay != null && shownDisplay.w > 0 && shownDisplay.h > 0 -> shownDisplay.w.toFloat() / shownDisplay.h
        else -> 16f / 9f
    }
    val absDisplay = streamUi.display.ifBlank { display }

    // Input: to the picked computer only.
    val currentTarget by rememberUpdatedState(target)
    fun send(json: String) {
        val t = currentTarget
        if (t != null) RemoteHub.send(t, json) else WebSocketUtil.sendMessage(json)
    }
    val sender = remember(scope) { RemoteInputSender(scope) { send(it) } }

    val pad = remember { TouchpadGestures() }
    pad.speed = settings.pointerSpeed
    pad.naturalScroll = settings.naturalScroll
    pad.reach = PointerAccel.reachFor(shownDisplay?.w ?: 0)
    val direct = remember { DirectGestures() }
    direct.naturalScroll = settings.naturalScroll
    val pictureGestures = remember { PictureGestures() }

    // Keys: sticky modifiers on the key bar, the held app switcher.
    var sticky by remember(target) { mutableStateOf(StickyMods()) }
    val switcher = remember(target, isWindows) { AppSwitcher(isWindows) }
    var switcherOpen by remember(switcher) { mutableStateOf(false) }
    var chipHolding by remember(switcher) { mutableStateOf(false) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    fun stepSwitcher(forward: Boolean) {
        HapticUtil.performLightTick(haptics)
        sender.raw(switcher.step(forward))
        switcherOpen = true
    }
    fun endSwitcher() {
        sender.raw(switcher.end())
        switcherOpen = false
    }
    fun click(button: String) {
        HapticUtil.performLightTick(haptics)
        val (mods, next) = sticky.consume()
        sticky = next
        sender.click(button, KeyCompose.holdAround(mods))
    }
    fun stopFling() { flingJob?.cancel(); flingJob = null }
    fun fling(vx: Float, vy: Float) {
        stopFling()
        flingJob = scope.launch {
            var x = vx
            var y = vy
            var last = SystemClock.uptimeMillis()
            while (hypot(x, y) > ScrollInertia.STOP) {
                delay(16)
                val now = SystemClock.uptimeMillis()
                val (dx, nx) = ScrollInertia.step(x, now - last)
                val (dy, ny) = ScrollInertia.step(y, now - last)
                last = now
                sender.scroll(dx, dy)
                x = nx; y = ny
            }
        }
    }

    // The touch box of the picture, in dp, for direct taps, pinch and pan.
    var boxW by remember { mutableFloatStateOf(1f) }
    var boxH by remember { mutableFloatStateOf(1f) }
    fun zoomOrPan(a: PadAction) {
        when (a) {
            is PadAction.Zoom -> {
                val (fx, fy) = AbsMap.toPicture(a.cx, a.cy, boxW, boxH, aspect)
                viewState = viewState.zoomBy(a.factor, fx, fy)
            }
            is PadAction.Pan -> {
                val f = AbsMap.fit(boxW, boxH, aspect)
                if (f.width > 0f && f.height > 0f) viewState = viewState.panBy(a.dx / f.width, a.dy / f.height)
            }
            else -> {}
        }
    }

    val onPad: (PadAction) -> Unit = { a ->
        when (a) {
            is PadAction.Move -> sender.move(a.dx, a.dy)
            // While "Switch apps" is held, a tap steps (a two-finger tap steps back).
            is PadAction.Click -> if (chipHolding) stepSwitcher(a.button == PadAction.LEFT) else click(a.button)
            is PadAction.Button -> {
                if (a.down) HapticUtil.performClick(haptics)
                sender.button(a.button, a.down)
            }
            is PadAction.Scroll -> sender.scroll(a.dx, a.dy)
            is PadAction.Fling -> fling(a.vx, a.vy)
            is PadAction.Switch -> stepSwitcher(a.forward)
            PadAction.SwitchEnd -> if (!chipHolding) endSwitcher()
            else -> {}
        }
    }
    val onDirect: (PadAction) -> Unit = { a ->
        when (a) {
            is PadAction.MoveTo -> {
                val (x, y) = AbsMap.toDisplay(a.x, a.y, boxW, boxH, aspect, viewState.view)
                sender.moveAbs(x, y, absDisplay)
            }
            is PadAction.Zoom, is PadAction.Pan -> zoomOrPan(a)
            else -> onPad(a)
        }
    }
    val onPicture: (PadAction) -> Unit = { a ->
        when (a) {
            PadAction.Expand -> if (!expanded) { HapticUtil.performLightTick(haptics); expanded = true }
            PadAction.Shrink -> if (expanded) { HapticUtil.performLightTick(haptics); expanded = false }
            else -> zoomOrPan(a)
        }
    }

    // Nothing stays held when the mode changes or the app goes to the background…
    fun releaseAll() {
        stopFling()
        (pad.cancel() + direct.cancel()).forEach { a -> if (a is PadAction.Button) sender.button(a.button, a.down) }
        chipHolding = false
        endSwitcher()
        sender.flush()
    }
    LaunchedEffect(isActive, resumed) { if (!isActive || !resumed) releaseAll() }
    // …or the computer changes / the Remote goes away (the releases go to the computer that was being driven).
    DisposableEffect(target, switcher) {
        val t = target
        val sw = switcher
        onDispose {
            flingJob?.cancel()
            sender.discard()
            val ups = (pad.cancel() + direct.cancel()).filterIsInstance<PadAction.Button>().map { RemoteInput.button(it.button, it.down) }
            if (t != null) (ups + sw.end()).forEach { RemoteHub.send(t, it) }
        }
    }

    fun sendKey(code: Int) {
        HapticUtil.performLightTick(haptics)
        when {
            switcher.open && code == MacKey.TAB -> stepSwitcher(true)
            switcher.open -> sender.raw(switcher.tap(code))
            else -> {
                val (msg, next) = KeyCompose.key(code, sticky)
                sticky = next
                sender.raw(msg)
            }
        }
    }
    fun sendTyped(text: String) {
        val (msgs, next) = KeyCompose.typed(text, sticky)
        sticky = next
        sender.raw(msgs)
    }
    fun sendCombo(c: Combo) {
        HapticUtil.performLightTick(haptics)
        if (switcher.open && c.mods.isEmpty()) { sender.raw(switcher.tap(c.keycode)); return }
        val (mods, next) = sticky.consume()
        sticky = next
        sender.raw(Shortcuts.messages(Combo(c.keycode, (c.mods + mods).distinct())))
    }

    // The phone keyboard: opens by itself when the computer reports a text field got focus (design §6.5).
    var keyboardOpen by remember { mutableStateOf(false) }
    var keyboardAuto by remember { mutableStateOf(false) }
    var secureField by remember { mutableStateOf(false) }
    LaunchedEffect(remote?.focusSeq, wantPicture) {
        val f = remote?.focus ?: return@LaunchedEffect
        if (!wantPicture) return@LaunchedEffect
        if (f.text) {
            secureField = f.secure
            if (!keyboardOpen) { keyboardOpen = true; keyboardAuto = true }
        } else if (keyboardAuto) {
            keyboardOpen = false; keyboardAuto = false
        }
    }
    LaunchedEffect(wantPicture) { if (!wantPicture && keyboardAuto) { keyboardOpen = false; keyboardAuto = false } }

    // The legacy keyboard sheet (toolbar's keyboard button), also aimed at the picked computer.
    var activeModifiers by remember { mutableStateOf(setOf<String>()) }
    val sheetModifiers = remember(activeModifiers) {
        KeyboardModifiers(
            shift = ModifierStatus(activeModifiers.contains("shift")),
            ctrl = ModifierStatus(activeModifiers.contains("ctrl")),
            option = ModifierStatus(activeModifiers.contains("option")),
            command = ModifierStatus(activeModifiers.contains("command")),
            fn = ModifierStatus(activeModifiers.contains("fn"))
        )
    }
    fun sendSheet(action: String, extras: Map<String, Any>, haptic: Boolean) {
        if (haptic) HapticUtil.performLightTick(haptics)
        val data = JSONObject().put("action", action)
        extras.forEach { (k, v) -> if (v is List<*>) data.put(k, JSONArray(v)) else data.put(k, v) }
        sender.raw(JSONObject().put("type", "remoteControl").put("data", data).toString())
    }
    if (showKeyboard) {
        KeyboardInputSheet(
            onDismiss = onDismissKeyboard,
            onType = { text, fromSystem, mods -> sendSheet("type", mapOf("text" to text, "modifiers" to mods), !fromSystem) },
            onKeyPress = { code, fromSystem, mods -> sendSheet("keypress", mapOf("keycode" to code, "modifiers" to mods), !fromSystem) },
            onClearModifiers = { activeModifiers = emptySet() },
            modifiers = sheetModifiers,
            onToggleModifier = { m -> activeModifiers = if (m in activeModifiers) activeModifiers - m else activeModifiers + m }
        )
    }

    var addingCombo by remember { mutableStateOf(false) }
    if (addingCombo && target != null) {
        RemoteAddComboDialog(
            isWindows = isWindows,
            onDismiss = { addingCombo = false },
            onSave = { c ->
                addingCombo = false
                settings.changeCustomCombos(target, (settings.customCombos(target) + c).distinctBy { it.mods.toSet() to it.keycode })
            }
        )
    }

    // The phone keyboard takes the toolbar's room at the bottom when it is taller.
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val effectiveBottom = if (imeBottom > bottomPadding) imeBottom + 8.dp else bottomPadding

    Column(
        modifier = modifier.padding(bottom = effectiveBottom),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // [Mac] [PC-INTERN ●]  ▾ G9  (Touchpad | Direct)  ⋯
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RemoteComputerChips(
                desktops = desktops,
                selected = target,
                onPick = { name ->
                    HapticUtil.performLightTick(haptics)
                    RemoteHub.select(name)
                },
                modifier = Modifier.weight(1f)
            )
            if (pictureMode && connected) {
                RemoteDisplayPicker(hello.displays, display) { id ->
                    HapticUtil.performLightTick(haptics)
                    display = id
                }
                Spacer(Modifier.width(4.dp))
                RemoteModeSwitch(mode) { m ->
                    if (target != null && m != mode) {
                        HapticUtil.performToggleOn(haptics)
                        releaseAll()
                        settings.changeMode(target, m)
                    }
                }
            }
            RemoteMenu(settings)
        }

        when {
            target == null -> RemoteCard(
                Icons.Rounded.DesktopAccessDisabled, "No computer yet", "Add a computer on Home to use the Remote.",
                modifier = Modifier.weight(1f)
            )
            !connected -> Box(Modifier.weight(1f).fillMaxWidth()) {
                RemoteUnreachableCard(
                    target,
                    tailscaleHint = RemoteAddrStore.get(context, target).isNotEmpty() && !phoneHasVpn(context),
                    lastSeen = DesktopHub.pairedRecord(target)?.lastConnected
                )
            }
            else -> {
                val pausedLocked = streamUi.phase == RemoteStream.Phase.PAUSED && streamUi.pausedReason == "locked"
                val bigCard = locked || pausedLocked || streamUi.phase == RemoteStream.Phase.CLOSED
                val notice = if (hello != null && !hello.control) "Viewing and control are turned off on $target" else null
                val trackBox = Modifier.onSizeChanged {
                    with(density) {
                        boxW = it.width.toDp().value.coerceAtLeast(1f)
                        boxH = it.height.toDp().value.coerceAtLeast(1f)
                    }
                }
                val unlockUi = unlockUis[RemoteHub.key(target)]

                @Composable
                fun Picture(touch: Modifier, modifier: Modifier) = PictureArea(
                    name = target,
                    hello = hello,
                    locked = locked,
                    stream = stream,
                    streamUi = streamUi,
                    aspect = aspect,
                    unlock = unlockUi,
                    isWindows = isWindows,
                    onUnlock = { HapticUtil.performClick(haptics); RemoteUnlockFlow.start(context, target) },
                    touch = trackBox.then(touch),
                    modifier = modifier
                )

                @Composable
                fun Status() = RemoteStatusRow(
                    ui = streamUi,
                    live = pictureMode && streamUi.phase == RemoteStream.Phase.LIVE && streamUi.hasPicture,
                    onLock = {
                        HapticUtil.performClick(haptics)
                        sender.raw(RemoteJsonLock)
                        if (RemoteUnlockFlow.unlockedThisSession(target)) onLeave()
                    },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                @Composable
                fun Touchpad(modifier: Modifier, compact: Boolean) = RemoteTouchpadSurface(
                    isWindows = isWindows,
                    switcherOpen = switcherOpen,
                    notice = notice,
                    compact = compact,
                    modifier = modifier.padInput(target, pad, onDown = { stopFling() }, onAction = onPad)
                )

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val roomW = maxWidth
                    val roomH = maxHeight
                    val landscape = roomW > roomH
                    when {
                        mode == ControlMode.DIRECT -> Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                            Picture(
                                touch = Modifier.padInput(target, direct, onDown = { stopFling() }, onAction = onDirect),
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                            )
                            Status()
                        }
                        !pictureMode -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                            if (locked) LockedCardSlot(target, hello, unlockUi, context)
                            Status()
                            Spacer(Modifier.height(4.dp))
                            Touchpad(Modifier.fillMaxWidth().weight(1f), compact = false)
                        }
                        landscape -> Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Column(Modifier.weight(if (expanded || bigCard) 2.4f else 1.3f).fillMaxHeight()) {
                                Picture(
                                    touch = Modifier.padInput(target, pictureGestures, onAction = onPicture),
                                    modifier = Modifier.weight(1f)
                                )
                                Status()
                            }
                            Spacer(Modifier.width(8.dp))
                            Touchpad(Modifier.weight(1f).fillMaxHeight(), compact = expanded)
                        }
                        else -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                            // Small: as tall as the picture needs, at most 42 % of the room. Big (tapped, or a
                            // card such as Unlock): all but a compact touchpad.
                            val big = expanded || bigCard
                            val small = min(roomW / aspect.coerceAtLeast(0.2f) + 8.dp, roomH * 0.42f)
                            Picture(
                                touch = Modifier.padInput(target, pictureGestures, onAction = onPicture),
                                modifier = if (big) Modifier.weight(1f).padding(vertical = 4.dp)
                                else Modifier.fillMaxWidth().height(small).padding(vertical = 4.dp)
                            )
                            Status()
                            Spacer(Modifier.height(4.dp))
                            Touchpad(
                                if (big) Modifier.fillMaxWidth().height(COMPACT_PAD) else Modifier.fillMaxWidth().weight(1f),
                                compact = big
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                RemoteShortcutRow(
                    isWindows = isWindows,
                    custom = settings.customCombos(target),
                    switcherOpen = switcherOpen,
                    onCombo = { sendCombo(it) },
                    onSwitchPress = { chipHolding = true; stepSwitcher(true) },
                    onSwitchRelease = { chipHolding = false; endSwitcher() },
                    onAdd = { addingCombo = true },
                    onRemove = { c -> settings.changeCustomCombos(target, settings.customCombos(target) - c) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Spacer(Modifier.height(6.dp))
                RemoteKeyBar(
                    isWindows = isWindows,
                    keyboardOpen = keyboardOpen,
                    sticky = sticky,
                    onKeyboard = {
                        HapticUtil.performLightTick(haptics)
                        keyboardOpen = !keyboardOpen
                        keyboardAuto = false
                        if (keyboardOpen) secureField = remote?.focus?.secure == true
                    },
                    onKey = { sendKey(it) },
                    onMod = { m -> HapticUtil.performLightTick(haptics); sticky = sticky.tap(m, SystemClock.uptimeMillis()) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HiddenKeyboard(
                    open = keyboardOpen,
                    secure = secureField,
                    onText = { sendTyped(it) },
                    onKey = { sendKey(it) },
                    onClosed = { keyboardOpen = false; keyboardAuto = false }
                )
            }
        }
    }
}

private val COMPACT_PAD = 150.dp

private val RemoteJsonLock = RemoteJson.remoteControl("lock_screen")

/** Picture off: the locked card above the touchpad. */
@Composable
private fun LockedCardSlot(name: String, hello: RemoteHello?, unlock: RemoteUnlockFlow.Ui?, context: Context) {
    Box(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
        RemoteLockedCard(name, hello, unlock) { RemoteUnlockFlow.start(context, name) }
    }
}

/** What fills the picture area: the live picture (with [touch] on it), or a card saying why there is none. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PictureArea(
    name: String,
    hello: RemoteHello?,
    locked: Boolean,
    stream: RemoteStream,
    streamUi: RemoteStream.Ui,
    aspect: Float,
    unlock: RemoteUnlockFlow.Ui?,
    isWindows: Boolean,
    onUnlock: () -> Unit,
    touch: Modifier,
    modifier: Modifier
) {
    val pausedLocked = streamUi.phase == RemoteStream.Phase.PAUSED && streamUi.pausedReason == "locked"
    val macNag = streamUi.phase == RemoteStream.Phase.PAUSED && streamUi.pausedReason == "macPermission"
    val closed = streamUi.phase == RemoteStream.Phase.CLOSED
    Box(modifier.fillMaxSize()) {
        // The picture (and its surface) stays put under the cards so a short pause doesn't reopen anything.
        Box(Modifier.fillMaxSize().then(if (!locked && !pausedLocked) touch else Modifier)) {
            RemotePicture(stream, aspect, Modifier.fillMaxSize())
        }
        when {
            locked || pausedLocked -> RemoteLockedCard(name, hello, unlock, onUnlock)
            macNag -> NoticeOverPicture(
                Icons.Rounded.Security,
                "Your Mac is asking to keep allowing screen recording. Control still works; the picture comes back after someone clicks Allow."
            )
            closed && streamUi.closeCode == ScreenStreamClient.CODE_NOT_PERMITTED -> RemoteCard(
                Icons.Rounded.Security,
                if (isWindows) "$name doesn't allow screen capture" else "$name needs Screen Recording for H.I.B Input",
                if (isWindows) "Control still works." else "On $name, open H.I.B Settings → Phone remote and turn on Screen Recording for H.I.B Input. Control still works."
            )
            closed && streamUi.closeCode == ScreenStreamClient.CODE_STOPPED -> RemoteCard(
                Icons.Rounded.StopCircle, "Viewing was stopped on $name",
                "Someone pressed Stop there. You can try again in a minute.",
                action = "Try again", onAction = { stream.retryNow() }
            )
            closed && streamUi.closeCode == ScreenStreamClient.CODE_BUSY -> RemoteCard(
                Icons.Rounded.PeopleAlt, "Another phone is viewing $name", "The picture comes back when it stops."
            )
            closed && streamUi.closeCode == ScreenStreamClient.CODE_BAD_KEY -> RemoteCard(
                Icons.Rounded.Key, "Pairing expired", "Scan $name's QR code again."
            )
            closed -> RemoteCard(Icons.Rounded.Block, "Can't show $name's screen right now", "Trying again…", busy = true)
            !streamUi.hasPicture -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun NoticeOverPicture(icon: ImageVector, text: String) {
    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopCenter) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * An invisible text field that holds the phone keyboard. Text goes out as it is typed (commit by commit);
 * Backspace and Return become key presses. Password fields get a no-suggestions, no-learning keyboard.
 */
@Composable
private fun HiddenKeyboard(
    open: Boolean,
    secure: Boolean,
    onText: (String) -> Unit,
    onKey: (Int) -> Unit,
    onClosed: () -> Unit
) {
    val sentinel = "​"
    var value by remember { mutableStateOf(TextFieldValue(sentinel, TextRange(sentinel.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(open, secure) {
        if (open) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else if (focused) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    // The user closed the keyboard (back gesture): the Remote follows.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var wasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (wasVisible && !imeVisible && open) onClosed()
        wasVisible = imeVisible
    }
    BasicTextField(
        value = value,
        onValueChange = { nv ->
            val old = value.text
            val new = nv.text
            when {
                new.length < old.length -> onKey(MacKey.DELETE)
                new.length > old.length -> {
                    val added = new.substring(old.length.coerceAtMost(new.length)).replace(sentinel, "")
                    when {
                        added == "\n" -> onKey(MacKey.RETURN)
                        added.isNotEmpty() -> {
                            val parts = added.split("\n")
                            parts.forEachIndexed { i, p ->
                                if (p.isNotEmpty()) onText(p)
                                if (i < parts.size - 1) onKey(MacKey.RETURN)
                            }
                        }
                    }
                }
            }
            value = TextFieldValue(sentinel, TextRange(sentinel.length))
        },
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = if (secure) KeyboardType.Password else KeyboardType.Text,
            imeAction = ImeAction.None
        ),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
    )
}
