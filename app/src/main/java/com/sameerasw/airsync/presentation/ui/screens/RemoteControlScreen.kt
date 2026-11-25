package com.sameerasw.airsync.presentation.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DesktopAccessDisabled
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sameerasw.airsync.R
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.presentation.ui.components.KeyboardInputSheet
import com.sameerasw.airsync.presentation.ui.components.KeyboardModifiers
import com.sameerasw.airsync.presentation.ui.components.MacKeycodes
import com.sameerasw.airsync.presentation.ui.components.ModifierStatus
import com.sameerasw.airsync.remote.KEY_DELETE
import com.sameerasw.airsync.remote.KEY_RETURN
import com.sameerasw.airsync.remote.RemoteAction
import com.sameerasw.airsync.remote.RemoteAddrStore
import com.sameerasw.airsync.remote.RemoteCard
import com.sameerasw.airsync.remote.RemoteComputerChips
import com.sameerasw.airsync.remote.RemoteDisplayPicker
import com.sameerasw.airsync.remote.RemoteGestures
import com.sameerasw.airsync.remote.RemoteHello
import com.sameerasw.airsync.remote.RemoteHub
import com.sameerasw.airsync.remote.RemoteInput
import com.sameerasw.airsync.remote.RemoteInputSender
import com.sameerasw.airsync.remote.RemoteKeyStrip
import com.sameerasw.airsync.remote.RemoteLockedCard
import com.sameerasw.airsync.remote.RemoteMenu
import com.sameerasw.airsync.remote.RemotePicture
import com.sameerasw.airsync.remote.RemoteSettings
import com.sameerasw.airsync.remote.RemoteStatusRow
import com.sameerasw.airsync.remote.RemoteStream
import com.sameerasw.airsync.remote.RemoteUnlockFlow
import com.sameerasw.airsync.remote.RemoteUnreachableCard
import com.sameerasw.airsync.remote.RemoteViewState
import com.sameerasw.airsync.remote.ScreenStreamClient
import com.sameerasw.airsync.remote.ViewRect
import com.sameerasw.airsync.remote.phoneHasVpn
import com.sameerasw.airsync.remote.remoteTouchpad
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * H.I.B Remote (SPACE.md §15, design §5): pick a computer on the chips, see its screen live and drive it with the
 * picture as a touchpad; the phone keyboard opens by itself when a text field gets focus there; a locked computer
 * shows a card with Unlock. Without the picture (⋯ Picture off, or a computer that doesn't offer it) the old
 * trackpad layout is shown. Everything goes to the picked computer only, never to the one the phone points at.
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
    val dataStoreManager =
        remember { com.sameerasw.airsync.data.local.DataStoreManager.getInstance(context) }
    val isFlipped by dataStoreManager.isRemoteFlipped().collectAsState(initial = null)
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
    var display by remember(target) { mutableStateOf("") }
    var viewState by remember(target, display) { mutableStateOf(RemoteViewState()) }
    var lastViewChangeAt by remember { mutableLongStateOf(0L) }

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
    // Zoomed: the view follows the cursor (once the computer has caught up with the last view change).
    LaunchedEffect(stream) {
        stream.cursor.collect { c ->
            if (c.visible && System.currentTimeMillis() - lastViewChangeAt > 400) {
                val next = viewState.followCursor(c.x, c.y)
                if (next != viewState) viewState = next
            }
        }
    }

    // Input: to the picked computer only.
    val currentTarget by rememberUpdatedState(target)
    fun send(json: String) {
        val t = currentTarget
        if (t != null) RemoteHub.send(t, json) else WebSocketUtil.sendMessage(json)
    }
    val sender = remember(scope) { RemoteInputSender(scope) { send(it) } }
    DisposableEffect(sender) {
        sender.start()
        onDispose { sender.flush(); sender.stop() }
    }
    val gestures = remember { RemoteGestures() }
    gestures.speed = 1.8f * settings.pointerSpeed
    gestures.naturalScroll = settings.naturalScroll
    var padSize by remember { mutableStateOf(IntSize(1, 1)) }
    val onAction: (RemoteAction) -> Unit = { a ->
        when (a) {
            is RemoteAction.Zoom -> viewState = viewState.zoomBy(a.factor, a.cx / padSize.width, a.cy / padSize.height)
            is RemoteAction.Pan -> viewState = viewState.panBy(a.dx / padSize.width, a.dy / padSize.height)
            is RemoteAction.Click -> { HapticUtil.performLightTick(haptics); sender.act(a) }
            else -> sender.act(a)
        }
    }

    fun sendRemoteAction(
        action: String,
        value: Any? = null,
        extras: Map<String, Any> = emptyMap(),
        performHaptic: Boolean = true
    ) {
        scope.launch {
            try {
                if (performHaptic) HapticUtil.performLightTick(haptics)
                val data = JSONObject().put("action", action)
                if (value != null) data.put("value", value)
                extras.forEach { (k, v) -> if (v is List<*>) data.put(k, JSONArray(v)) else data.put(k, v) }
                send(JSONObject().put("type", "remoteControl").put("data", data).toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Keys: the strip's ⌘/⌥ (Ctrl/Alt) stick for the next key or letter.
    var sticky by remember { mutableStateOf(setOf<String>()) }
    fun sendKey(code: Int) {
        HapticUtil.performLightTick(haptics)
        send(RemoteInput.key(code, sticky.toList()))
        sticky = emptySet()
    }
    fun sendTyped(text: String) {
        val code = text.singleOrNull()?.let { MacKeycodes.getKeyCode(it) }
        if (sticky.isNotEmpty() && code != null) {
            val mods = sticky.toList() + if (text[0].isUpperCase()) listOf("shift") else emptyList()
            send(RemoteInput.key(code, mods))
            sticky = emptySet()
        } else {
            send(RemoteInput.type(text))
        }
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
    LaunchedEffect(wantPicture) { if (!wantPicture) { keyboardOpen = false; keyboardAuto = false } }

    // The legacy keyboard sheet (toolbar's keyboard button), now also aimed at the picked computer.
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
    if (showKeyboard) {
        KeyboardInputSheet(
            onDismiss = onDismissKeyboard,
            onType = { text, fromSystem, mods ->
                sendRemoteAction("type", extras = mapOf("text" to text, "modifiers" to mods), performHaptic = !fromSystem)
            },
            onKeyPress = { code, fromSystem, mods ->
                sendRemoteAction("keypress", extras = mapOf("keycode" to code, "modifiers" to mods), performHaptic = !fromSystem)
            },
            onClearModifiers = { activeModifiers = emptySet() },
            modifiers = sheetModifiers,
            onToggleModifier = { m -> activeModifiers = if (m in activeModifiers) activeModifiers - m else activeModifiers + m }
        )
    }

    // The phone keyboard takes the toolbar's room at the bottom when it is taller.
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val effectiveBottom = if (imeBottom > bottomPadding) imeBottom + 8.dp else bottomPadding

    if (isFlipped == null) return
    val flippedValue = isFlipped!!

    Column(
        modifier = modifier.padding(bottom = effectiveBottom),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ◀ [Mac] [PC-INTERN ●]  ▾ G9  ⋯
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
            if (pictureMode) {
                RemoteDisplayPicker(hello.displays, display) { id ->
                    HapticUtil.performLightTick(haptics)
                    display = id
                }
            }
            RemoteMenu(settings)
        }

        if (target == null) {
            RemoteCard(Icons.Rounded.DesktopAccessDisabled, "No computer yet", "Add a computer on Home to use the Remote.",
                modifier = Modifier.weight(1f))
        } else if (pictureMode || (!connected && settings.pictureOn)) {
            PictureArea(
                name = target,
                connected = connected,
                hello = hello,
                locked = remote?.locked == true,
                stream = stream,
                streamUi = streamUi,
                unlock = unlockUis[RemoteHub.key(target)],
                isWindows = isWindows,
                lastSeen = DesktopHub.pairedRecord(target)?.lastConnected,
                tailscaleHint = !connected && RemoteAddrStore.get(context, target).isNotEmpty() && !phoneHasVpn(context),
                onUnlock = { HapticUtil.performClick(haptics); RemoteUnlockFlow.start(context, target) },
                touchpad = Modifier
                    .onSizeChanged { padSize = IntSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
                    .remoteTouchpad(gestures, onAction),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)
            )
            RemoteStatusRow(
                ui = streamUi,
                live = streamUi.phase == RemoteStream.Phase.LIVE && streamUi.hasPicture,
                onLock = {
                    HapticUtil.performClick(haptics)
                    send(RemoteJsonLock)
                    if (RemoteUnlockFlow.unlockedThisSession(target)) onLeave()
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))
            RemoteKeyStrip(
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
                onToggle = { m -> HapticUtil.performLightTick(haptics); sticky = if (m in sticky) sticky - m else sticky + m },
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            HiddenKeyboard(
                open = keyboardOpen,
                secure = secureField,
                onText = { sendTyped(it) },
                onKey = { sendKey(it) },
                onClosed = { keyboardOpen = false; keyboardAuto = false }
            )
        } else {
            TrackpadLayout(
                flipped = flippedValue,
                notice = when {
                    hello != null && !hello.control -> "Viewing and control are turned off on $target"
                    else -> null
                },
                touchpad = Modifier.remoteTouchpad(gestures) { a -> if (a is RemoteAction.Click) HapticUtil.performLightTick(haptics); sender.act(a) },
                onAction = { sendRemoteAction(it) },
                onFlip = {
                    HapticUtil.performLightTick(haptics)
                    scope.launch { dataStoreManager.setRemoteFlipped(isFlipped != true) }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private val RemoteJsonLock = com.sameerasw.airsync.remote.RemoteJson.remoteControl("lock_screen")

/** What fills the picture area: the live picture, or a card saying why there is none. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PictureArea(
    name: String,
    connected: Boolean,
    hello: RemoteHello?,
    locked: Boolean,
    stream: RemoteStream,
    streamUi: RemoteStream.Ui,
    unlock: RemoteUnlockFlow.Ui?,
    isWindows: Boolean,
    lastSeen: Long?,
    tailscaleHint: Boolean,
    onUnlock: () -> Unit,
    touchpad: Modifier,
    modifier: Modifier
) {
    val pausedLocked = streamUi.phase == RemoteStream.Phase.PAUSED && streamUi.pausedReason == "locked"
    val macNag = streamUi.phase == RemoteStream.Phase.PAUSED && streamUi.pausedReason == "macPermission"
    val closed = streamUi.phase == RemoteStream.Phase.CLOSED
    val aspect = when {
        streamUi.w > 0 && streamUi.h > 0 -> streamUi.w.toFloat() / streamUi.h
        else -> hello?.displays?.let { ds ->
            (ds.firstOrNull { it.id == streamUi.display } ?: ds.firstOrNull { it.main } ?: ds.firstOrNull())
                ?.takeIf { it.w > 0 && it.h > 0 }?.let { it.w.toFloat() / it.h }
        } ?: (16f / 9f)
    }
    Box(modifier.fillMaxSize()) {
        // The picture (and its surface) stays put under the cards so a short pause doesn't reopen anything.
        if (connected) {
            Box(Modifier.fillMaxSize().then(if (!locked && !pausedLocked) touchpad else Modifier)) {
                RemotePicture(stream, aspect, Modifier.fillMaxSize())
            }
        }
        when {
            !connected -> RemoteUnreachableCard(name, tailscaleHint, lastSeen)
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
                new.length < old.length -> onKey(KEY_DELETE)
                new.length > old.length -> {
                    val added = new.substring(old.length.coerceAtMost(new.length)).replace(sentinel, "")
                    when {
                        added == "\n" -> onKey(KEY_RETURN)
                        added.isNotEmpty() -> {
                            val parts = added.split("\n")
                            parts.forEachIndexed { i, p ->
                                if (p.isNotEmpty()) onText(p)
                                if (i < parts.size - 1) onKey(KEY_RETURN)
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

/** The trackpad-only layout (Picture off, or a computer without the picture): trackpad, arrows, extra keys. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackpadLayout(
    flipped: Boolean,
    notice: String?,
    touchpad: Modifier,
    onAction: (String) -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier
) {
    val config = LocalConfiguration.current
    val isWide = config.orientation == Configuration.ORIENTATION_LANDSCAPE || config.screenWidthDp > 600

    @Composable
    fun ExtraKeys() {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3
        ) {
            OutlinedButton(onClick = { onAction("escape") }, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Esc") }
            OutlinedButton(onClick = { onAction("space") }, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Icons.Default.SpaceBar, "Space", modifier = Modifier.size(18.dp))
            }
            OutlinedButton(onClick = onFlip, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(
                    painter = painterResource(id = if (isWide) R.drawable.rounded_compare_arrows_24 else R.drawable.rounded_mobiledata_arrows_24),
                    contentDescription = "Flip Layout",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    @Composable
    fun DPad() {
        Box(
            modifier = Modifier.size(240.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            RemoteButton(onClick = { onAction("arrow_up") }, icon = Icons.Default.ArrowUpward,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
            RemoteButton(onClick = { onAction("arrow_down") }, icon = Icons.Default.ArrowDownward,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            RemoteButton(onClick = { onAction("arrow_left") }, icon = Icons.AutoMirrored.Filled.ArrowBack,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
            RemoteButton(onClick = { onAction("arrow_right") }, icon = Icons.AutoMirrored.Filled.ArrowForward,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
            FilledTonalIconButton(onClick = { onAction("enter") }, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Default.Circle, "Enter", modifier = Modifier.size(24.dp))
            }
        }
    }

    @Composable
    fun Trackpad(modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier.fillMaxWidth().then(touchpad),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceBright,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (notice != null) {
                    Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp))
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.rounded_drag_click_24),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).alpha(0.1f),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    AnimatedContent(
        targetState = flipped,
        modifier = modifier,
        transitionSpec = {
            if (isWide) {
                if (targetState) {
                    (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                }
            } else {
                if (targetState) {
                    (slideInVertically { it / 4 } + fadeIn()) togetherWith (slideOutVertically { -it / 4 } + fadeOut())
                } else {
                    (slideInVertically { -it / 4 } + fadeIn()) togetherWith (slideOutVertically { it / 4 } + fadeOut())
                }
            }.using(SizeTransform(clip = false))
        },
        label = "RemoteLayoutFlip"
    ) { flip ->
        if (isWide) {
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val keys: @Composable () -> Unit = {
                    Column(
                        modifier = Modifier.weight(0.4f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        ExtraKeys()
                        Spacer(modifier = Modifier.height(24.dp))
                        DPad()
                    }
                }
                if (flip) {
                    Trackpad(Modifier.weight(0.6f).fillMaxHeight())
                    keys()
                } else {
                    keys()
                    Trackpad(Modifier.weight(0.6f).fillMaxHeight())
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (flip) {
                    DPad()
                    ExtraKeys()
                    Trackpad(Modifier.weight(1f))
                } else {
                    Trackpad(Modifier.weight(1f))
                    DPad()
                    ExtraKeys()
                }
            }
        }
    }
}

@Composable
fun RemoteButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(56.dp)
    ) {
        Icon(icon, contentDescription = null)
    }
}
