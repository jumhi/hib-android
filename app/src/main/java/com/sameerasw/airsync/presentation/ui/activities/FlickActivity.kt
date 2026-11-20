package com.sameerasw.airsync.presentation.ui.activities

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Base64
import android.util.Patterns
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.sameerasw.airsync.multidesk.AimMatcher
import com.sameerasw.airsync.multidesk.BubbleDevice
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.DeviceBubbleArc
import com.sameerasw.airsync.multidesk.FlickPointing
import com.sameerasw.airsync.multidesk.FlickScreenStore
import com.sameerasw.airsync.multidesk.HibCyan
import com.sameerasw.airsync.multidesk.HibInk
import com.sameerasw.airsync.multidesk.HibLime
import com.sameerasw.airsync.multidesk.HibPanel
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.multidesk.ScreenComputer
import com.sameerasw.airsync.multidesk.ScreenMap
import com.sameerasw.airsync.multidesk.ScreenTarget
import com.sameerasw.airsync.multidesk.TargetRule
import com.sameerasw.airsync.multidesk.label
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.FlickAim
import com.sameerasw.airsync.utils.FlickSender
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * H.I.B — Flick: point the phone at a screen and throw. A translucent overlay shows the shared content as
 * a card under a row of device bubbles (every connected computer; the target one glows). The target is
 * where the phone points (Point & Flick, SPACE.md §7): the aimed screen lights up on its computer and the
 * card lands there. Not pointing at a screen → the target rule (in use, then last used, then the main
 * computer). A one-time Point setup runs by itself when screens aren't mapped yet.
 *
 * Reachable from the system share sheet (anything) and from the Flick card in H.I.B (photos & videos,
 * files, the clipboard). With [EXTRA_POINT_SETUP] it only runs Point setup (Flick settings).
 */
class FlickActivity : ComponentActivity() {

    companion object {
        const val EXTRA_POINT_SETUP = "com.sameerasw.airsync.extra.POINT_SETUP"

        /** Opens Flick just to run Point setup again for every screen. */
        fun pointSetupIntent(context: Context): Intent =
            Intent(context, FlickActivity::class.java).putExtra(EXTRA_POINT_SETUP, true)
    }

    override fun onResume() {
        super.onResume()
        FlickAim.start(this)
        FlickPointing.resume()
    }

    override fun onPause() {
        FlickAim.stop()
        // Nothing stays lit on the computers while Flick isn't on screen (SPACE.md §7.4).
        FlickPointing.clearAll()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))

        val setupOnly = intent?.getBooleanExtra(EXTRA_POINT_SETUP, false) == true
        val items = if (setupOnly) emptyList() else FlickMedia.parseIntent(this, intent)
        if (!setupOnly && items.isEmpty()) {
            Toast.makeText(this, "Nothing to flick", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            AirSyncTheme(darkTheme = true) {
                FlickOverlay(initial = items, setupOnly = setupOnly, onFinish = { finish() })
            }
        }
    }
}

data class FlickItem(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,          // image | video | file | link | text
    val uri: Uri?,
    val name: String,
    val mime: String,
    val size: Long,
    val text: String? = null,
    val title: String? = null
)

private class FlickPreview(val image: ImageBitmap, val thumbnailBase64: String?)

internal class FlickHaptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    fun heavy() = play(VibrationEffect.EFFECT_HEAVY_CLICK)
    fun tick() = play(VibrationEffect.EFFECT_TICK)

    private fun play(effect: Int) {
        try {
            vibrator?.vibrate(VibrationEffect.createPredefined(effect))
        } catch (_: Exception) {
        }
    }
}

/** Screens the user skipped in Point setup this time the app is running, so it doesn't ask on every flick. */
internal object PointSetupMemory {
    private val skipped = mutableSetOf<String>()
    private fun key(t: ScreenTarget) = PairedDesktops.normalizeName(t.computer) + "\u0000" + t.screen

    @Synchronized fun skip(targets: List<ScreenTarget>) { skipped += targets.map(::key) }
    @Synchronized fun allSkipped(targets: List<ScreenTarget>): Boolean = targets.all { key(it) in skipped }
    @Synchronized fun clear() = skipped.clear()
}

private val FlickCyan = HibCyan
private const val LAUNCH_VELOCITY = 1200f   // px/s upward
private const val LAUNCH_DISTANCE = 0.28f   // of screen height
private val CARD_SHIFT = 56.dp              // the card sits a little below centre, under the bubbles
private const val SCREENS_WAIT_MS = 700L    // how long Flick waits for `screensInfo` before deciding on setup
private const val AIM_POLL_MS = 100L

@Composable
private fun FlickOverlay(initial: List<FlickItem>, setupOnly: Boolean, onFinish: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = remember { FlickHaptics(context) }

    val items = remember { mutableStateListOf<FlickItem>().apply { addAll(initial) } }
    val previews = remember { mutableStateMapOf<String, FlickPreview>() }

    val dim = remember { Animatable(0f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val grab = remember { Animatable(1f) }
    val enter = remember { Animatable(0f) }
    val throwScale = remember { Animatable(1f) }
    val throwAlpha = remember { Animatable(1f) }
    val pulse = remember { Animatable(0f) }
    var pulseId by remember { mutableStateOf<String?>(null) }
    var liveVy by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var showSent by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val pending = remember { mutableListOf<Deferred<Boolean>>() }
    val bubbleCenters = remember { mutableStateMapOf<String, Offset>() }

    // H.I.B Space: every connected computer is a bubble (an indicator: the target one glows).
    val allDesktops by DesktopHub.desktops.collectAsState()
    val online = allDesktops.filter { it.isConnected }
    val connected = online.isNotEmpty()
    val bubbles = online.map { BubbleDevice(it.name, it.name, it.platform, inUse = it.inUse, playing = it.playing) }

    // Point & Flick (SPACE.md §7): every screen of every connected computer, and where the phone points.
    val screensByComputer by DesktopHub.screens.collectAsState()
    val inUseId by DesktopHub.inUseId.collectAsState()
    val computers = online.map { ScreenComputer(it.name, screensByComputer[PairedDesktops.normalizeName(it.name)]) }
    val targets = remember(computers, inUseId) { ScreenMap.targets(computers, inUseId) }
    val savedFlow = remember { FlickScreenStore.flow(context) }
    val saved by savedFlow.collectAsState(initial = null)
    val compass = remember { FlickAim.isAvailable(context) }
    val unmapped = remember(computers, saved, inUseId) {
        if (saved == null) emptyList() else ScreenMap.unmapped(computers, saved!!, inUseId)
    }
    var setupTargets by remember { mutableStateOf<List<ScreenTarget>?>(null) }
    var setupCurrent by remember { mutableStateOf<ScreenTarget?>(null) }
    var aimed by remember { mutableStateOf<ScreenTarget?>(null) }
    val inSetup = setupTargets != null

    val savedNow by rememberUpdatedState(saved)
    val inSetupNow by rememberUpdatedState(inSetup)
    val launchingNow by rememberUpdatedState(launching)

    val pick = remember(allDesktops, aimed) { DesktopHub.targetPick(aimed = aimed?.computer) }
    // The aimed screen, when it is what decided the target.
    val aimScreen = aimed?.takeIf { a ->
        pick != null && pick.reason == TargetRule.Reason.AIM && PairedDesktops.sameName(a.computer, pick.id)
    }
    val litId = when {
        launching -> sentTo
        inSetup -> setupCurrent?.computer
        else -> pick?.id
    }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            dim.animateTo(0f, tween(180))
            onFinish()
        }
    }

    fun startSetup(list: List<ScreenTarget>) {
        if (list.isEmpty() || inSetupNow) return
        aimed = null
        FlickPointing.aimAt(null)
        setupTargets = list
    }

    LaunchedEffect(Unit) {
        dim.animateTo(1f, tween(220))
    }
    LaunchedEffect(Unit) {
        initial.forEach { item ->
            launch(Dispatchers.IO) {
                FlickMedia.loadPreview(context, item)?.let { bmp ->
                    val thumb = FlickMedia.encodeThumbnail(bmp)
                    previews[item.id] = FlickPreview(bmp.asImageBitmap(), thumb)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        FlickSender.landed.collect { haptics.tick() }
    }
    LaunchedEffect(items.firstOrNull()?.id) {
        enter.snapTo(0f)
        enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
    }

    // Ask every computer for its screens, then run Point setup when it's needed (SPACE.md §7.5).
    LaunchedEffect(Unit) {
        DesktopHub.requestScreens()
        if (!compass) {
            if (setupOnly) {
                Toast.makeText(context, "This phone has no compass, so pointing can't be set up", Toast.LENGTH_LONG).show()
                close()
            }
            return@LaunchedEffect
        }
        val started = System.currentTimeMillis()
        while (true) {
            val waited = System.currentTimeMillis() - started
            val cs = FlickPointing.computers()
            val settled = cs.isNotEmpty() && cs.all { it.screens != null }
            if (savedNow != null && (settled || waited >= SCREENS_WAIT_MS)) break
            if (waited >= 2_000L) break
            delay(50)
        }
        val cs = FlickPointing.computers()
        val inUse = DesktopHub.inUseId.value
        if (setupOnly) {
            val all = ScreenMap.targets(cs, inUse)
            if (all.size >= 2) {
                PointSetupMemory.clear()
                startSetup(all)
            } else {
                Toast.makeText(context, "Point setup needs two or more screens on connected computers", Toast.LENGTH_LONG).show()
                close()
            }
        } else if (!dragging && !launching) {
            val missing = ScreenMap.unmapped(cs, savedNow.orEmpty(), inUse)
            if (missing.isNotEmpty() && !PointSetupMemory.allSkipped(missing)) startSetup(missing)
        }
    }

    // Live aim: the nearest saved screen within 35° lights up on its computer (SPACE.md §7.4, §7.6).
    LaunchedEffect(compass) {
        if (!compass) return@LaunchedEffect
        while (true) {
            if (!inSetupNow) {
                val reading = FlickAim.current()?.let { AimMatcher.Aim(it.first, it.second) }
                val hit = ScreenMap.nearest(reading, savedNow.orEmpty(), FlickPointing.computers())
                val prev = aimed
                val same = (hit == null && prev == null) ||
                    (hit != null && prev != null && hit.sameAs(prev.computer, prev.screen))
                if (!same) {
                    aimed = hit
                    if (hit != null && !launchingNow) haptics.tick()
                }
                FlickPointing.aimAt(hit)
            }
            delay(AIM_POLL_MS)
        }
    }

    BackHandler { close() }

    fun fire(item: FlickItem, to: String, screen: String?, exitXRatio: Float, velocity: Float, angle: Float) {
        haptics.heavy()
        val preview = previews[item.id]
        pending += scope.async(Dispatchers.IO) {
            val started = FlickSender.sendFlickStart(
                id = item.id,
                kind = item.kind,
                name = item.name,
                mime = item.mime,
                size = item.size,
                exitXRatio = exitXRatio,
                velocity = velocity,
                angle = angle,
                thumbnailBase64 = preview?.thumbnailBase64,
                text = item.text,
                to = to,
                screen = screen
            )
            if (item.uri != null) {
                FlickSender.startFileTransfer(context, item.id, item.uri, item.name, item.mime, to = to)
            } else {
                started
            }
        }
    }

    /** Throws the top card into [targetId]'s bubble; it lands on [screen] there (null: the computer chooses). */
    fun launchCard(v: Offset, targetId: String, screen: String?) {
        val item = items.firstOrNull() ?: return
        launching = true
        sentTo = targetId
        FlickSender.target = targetId
        DesktopHub.noteUsed(targetId)
        scope.launch {
            val w = rootSize.width.toFloat().coerceAtLeast(1f)
            val h = rootSize.height.toFloat().coerceAtLeast(1f)
            val centre = Offset(w / 2f, h / 2f + with(density) { CARD_SHIFT.toPx() })
            val bubble = bubbleCenters[targetId] ?: Offset(w / 2f, h * 0.14f)
            val start = offset.value
            val end = bubble - centre
            val path = end - start
            val dist = path.getDistance().coerceAtLeast(1f)
            val mag = hypot(v.x, v.y)
            val speed = max(mag, 2600f)
            val durationMs = (dist / speed * 1000f).toInt().coerceIn(200, 380)
            val angle = Math.toDegrees(atan2(path.x, -path.y).toDouble()).toFloat()
            val exitX = ((w / 2f + start.x) / w).coerceIn(0f, 1f)
            var fired = false
            launch { throwScale.animateTo(0.12f, tween(durationMs, easing = FastOutLinearInEasing)) }
            launch { throwAlpha.animateTo(0f, tween(durationMs / 3, delayMillis = durationMs * 2 / 3)) }
            offset.animateTo(end, tween(durationMs, easing = LinearOutSlowInEasing)) {
                // The card is on its way: tell the computer now so its card can start flying in.
                if (!fired && (value - start).getDistance() >= dist * 0.3f) {
                    fired = true
                    fire(item, targetId, screen, exitX, max(mag, LAUNCH_VELOCITY), angle)
                }
            }
            if (!fired) fire(item, targetId, screen, exitX, max(mag, LAUNCH_VELOCITY), angle)

            // Landed in the bubble.
            haptics.tick()
            pulseId = targetId
            launch {
                pulse.snapTo(1f)
                pulse.animateTo(0f, tween(560))
            }
            items.removeAt(0)
            offset.snapTo(Offset.Zero)
            throwScale.snapTo(1f)
            throwAlpha.snapTo(1f)
            launching = false
            if (items.isEmpty()) {
                showSent = true
                // Keep the overlay (and its share grant) alive until every file stream is open.
                withTimeoutOrNull(15_000) { pending.forEach { it.await() } }
                delay(950)
                close()
            }
        }
    }

    fun release(v: Offset) {
        dragging = false
        scope.launch { grab.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) }
        liveVy = 0f
        if (launching || items.isEmpty()) return
        val h = rootSize.height.toFloat()
        val wantsLaunch = v.y < -LAUNCH_VELOCITY || (offset.value.y < -LAUNCH_DISTANCE * h && v.y < 400f)
        // Where the phone points right now decides (the target rule when it points at nothing).
        val target = if (wantsLaunch && connected) pick?.id else null
        if (target != null) {
            val screen = aimScreen?.screen?.takeIf { it.isNotEmpty() }
            launchCard(v, target, screen)
        } else {
            scope.launch {
                offset.animateTo(
                    Offset.Zero,
                    spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
                    initialVelocity = v
                )
            }
        }
    }

    val glowTarget = run {
        val h = rootSize.height.toFloat().coerceAtLeast(1f)
        val byVelocity = ((-liveVy - 500f) / (LAUNCH_VELOCITY - 500f)).coerceIn(0f, 1f)
        val byDistance = (-offset.value.y / (LAUNCH_DISTANCE * h)).coerceIn(0f, 1f)
        if (!connected) 0f else max(byVelocity, byDistance * byDistance)
    }
    val glow by animateFloatAsState(glowTarget, label = "glow")
    val cardsAlpha by animateFloatAsState(if (inSetup) 0f else 1f, tween(220), label = "cardsAlpha")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .background(
                Brush.verticalGradient(
                    listOf(
                        HibInk.copy(alpha = 0.92f * dim.value),
                        Color.Black.copy(alpha = 0.62f * dim.value),
                        Color.Black.copy(alpha = 0.7f * dim.value)
                    )
                )
            )
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        if (!launching && !inSetupNow && items.isNotEmpty()) {
                            dragging = true
                            scope.launch { grab.animateTo(1.05f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) }
                        }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity()
                        if (dragging) release(Offset(v.x, v.y))
                    },
                    onDragCancel = { if (dragging) release(Offset.Zero) },
                    onDrag = { change, amount ->
                        if (dragging && !launching && items.isNotEmpty()) {
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            scope.launch { offset.snapTo(offset.value + amount) }
                            liveVy = tracker.calculateVelocity().y
                        }
                    }
                )
            }
    ) {
        val cardWidth = with(density) { min((rootSize.width * 0.64f).toDp(), 300.dp) }
        val cardHeight = cardWidth * 1.3f

        // Header: title, where a flick goes now, and the device bubbles.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp)
                .graphicsLayer { alpha = dim.value },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Flick",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            inSetup -> "Point setup"
                            !connected -> "No computer connected"
                            launching && sentTo != null -> "→ $sentTo"
                            else -> "→ " + (aimScreen?.title() ?: pick?.id ?: "computer")
                        },
                        color = if (connected && !inSetup) FlickCyan else Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (connected && !inSetup) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!setupOnly) {
                    IconButton(onClick = {
                        haptics.tick()
                        context.startActivity(Intent(context, FlickSettingsActivity::class.java))
                    }) {
                        Icon(Icons.Rounded.Tune, contentDescription = "Flick settings", tint = Color.White.copy(alpha = 0.85f))
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            if (connected) {
                DeviceBubbleArc(
                    devices = bubbles,
                    litId = litId,
                    aimedId = if (inSetup) null else aimScreen?.computer,
                    pulseId = pulseId,
                    pulse = pulse.value,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    onPositioned = { id, p -> bubbleCenters[id] = p }
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = HibPanel.copy(alpha = 0.9f),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "No computer is connected. Open H.I.B to connect one.",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(Modifier.height(26.dp))

            if (connected && !inSetup && !setupOnly) {
                val reason = pick?.reason
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = null,
                        tint = FlickCyan,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { translationY = -glow * 6.dp.toPx() }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Flick up to send",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (reason != null && (online.size > 1 || targets.size > 1)) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, FlickCyan.copy(alpha = 0.6f), RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(reason.label(), color = FlickCyan, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                if (compass && targets.size > 1) {
                    if (unmapped.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (unmapped.size == 1) "One screen isn't set up for pointing" else "${unmapped.size} screens aren't set up for pointing",
                                color = Color.White.copy(alpha = 0.55f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = {
                                haptics.tick()
                                startSetup(unmapped)
                            }) {
                                Text("Set up", color = FlickCyan, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else {
                        Text(
                            text = "Point your phone at a screen to pick it",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            if (items.size > 1 && !inSetup) {
                Text(
                    text = "${items.size} items — one flick each",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Stack (items behind the top card), drawn back to front.
        val visible = items.take(3)
        for (i in visible.indices.reversed()) {
            val item = visible[i]
            val isTop = i == 0
            val depth = if (isTop) 1f - enter.value else i.toFloat()
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = CARD_SHIFT)
                    .size(cardWidth, cardHeight)
                    .graphicsLayer {
                        val s = 1f - 0.06f * depth
                        if (isTop) {
                            val o = offset.value
                            translationX = o.x
                            translationY = o.y + depth * 18.dp.toPx()
                            rotationZ = (o.x / size.width) * 14f * throwScale.value
                            scaleX = s * grab.value * throwScale.value
                            scaleY = s * grab.value * throwScale.value
                            alpha = dim.value * throwAlpha.value * cardsAlpha
                        } else {
                            translationY = depth * 18.dp.toPx()
                            scaleX = s
                            scaleY = s
                            alpha = dim.value * (1f - 0.25f * depth) * cardsAlpha
                        }
                    }
            ) {
                FlickCardFace(item, previews[item.id]?.image, if (isTop) glow else 0f)
            }
        }

        // Point setup (SPACE.md §7.5): over the card, with its own Skip.
        setupTargets?.let { list ->
            PointSetupPanel(
                targets = list,
                haptics = haptics,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = dim.value },
                onCurrent = { setupCurrent = it },
                onDone = { skipped ->
                    if (skipped.isNotEmpty()) PointSetupMemory.skip(skipped)
                    setupTargets = null
                    setupCurrent = null
                    if (setupOnly) close()
                }
            )
        }

        // Bottom: cancel
        if (!inSetup) {
            TextButton(
                onClick = { close() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .graphicsLayer { alpha = dim.value }
            ) {
                Text(if (setupOnly) "Close" else "Cancel", color = Color.White.copy(alpha = 0.8f))
            }
        }

        // "Sent to …"
        AnimatedVisibility(
            visible = showSent,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp)
        ) {
            Surface(shape = CircleShape, color = Color(0xFF101530), shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = HibLime)
                    Spacer(Modifier.width(8.dp))
                    Text("Sent to ${sentTo ?: "computer"}", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun FlickCardFace(item: FlickItem, image: ImageBitmap?, glow: Float) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(
                elevation = (18 + 22 * glow).dp,
                shape = shape,
                ambientColor = FlickCyan.copy(alpha = 0.25f + 0.75f * glow),
                spotColor = FlickCyan.copy(alpha = 0.25f + 0.75f * glow)
            )
            .clip(shape)
            .background(Color(0xFF131A38))
            .border(
                width = (1 + 2 * glow).dp,
                color = if (glow > 0.01f) FlickCyan.copy(alpha = 0.35f + 0.65f * glow) else Color.White.copy(alpha = 0.18f),
                shape = shape
            )
    ) {
        when {
            image != null && (item.kind == "image" || item.kind == "video") -> {
                Image(
                    bitmap = image,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (item.kind == "video") {
                    Icon(
                        Icons.Rounded.PlayCircle,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                    )
                }
                Text(
                    text = item.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            item.kind == "link" -> CardBody(
                icon = { Icon(Icons.Rounded.Link, null, tint = FlickCyan, modifier = Modifier.size(44.dp)) },
                title = item.title ?: Uri.parse(item.text ?: "").host ?: "Link",
                detail = item.text ?: ""
            )
            item.kind == "text" -> CardBody(
                icon = { Icon(Icons.Rounded.Notes, null, tint = FlickCyan, modifier = Modifier.size(44.dp)) },
                title = "Text",
                detail = item.text ?: "",
                detailLines = 9
            )
            else -> {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f }
                    )
                }
                CardBody(
                    icon = { Icon(Icons.Rounded.InsertDriveFile, null, tint = FlickCyan, modifier = Modifier.size(52.dp)) },
                    title = item.name,
                    detail = if (item.size > 0) Formatter.formatShortFileSize(LocalContext.current, item.size) else item.mime
                )
            }
        }
    }
}

@Composable
private fun CardBody(icon: @Composable () -> Unit, title: String, detail: String, detailLines: Int = 3) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = detailLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Intent parsing, previews and thumbnails for flick cards. */
object FlickMedia {
    private const val PREVIEW_PX = 720
    private const val THUMB_PX = 256
    private const val THUMB_MAX_B64 = 40_000

    fun parseIntent(context: Context, intent: Intent?): List<FlickItem> {
        intent ?: return emptyList()
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> uris += streamListExtra(intent)
        }
        if (uris.isEmpty()) {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
            }
        }
        if (uris.isNotEmpty()) {
            return uris.distinct().map { uri -> fileItem(context, uri, intent.type) }
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        val matcher = Patterns.WEB_URL.matcher(text)
        if (matcher.find()) {
            val url = matcher.group()
            // Mostly a link (Chrome shares "Title\nhttps://…"): treat as a link card.
            if (text.length - url.length < 160) {
                val full = if (url.contains("://")) url else "https://$url"
                val host = Uri.parse(full).host ?: full
                return listOf(
                    FlickItem(kind = "link", uri = null, name = host, mime = "text/uri-list", size = 0,
                        text = full, title = subject?.takeIf { it.isNotBlank() } ?: text.replace(url, "").trim().takeIf { it.isNotBlank() })
                )
            }
        }
        val clipped = text.take(FlickSender.MAX_TEXT_CHARS)
        return listOf(FlickItem(kind = "text", uri = null, name = "Text", mime = "text/plain",
            size = clipped.length.toLong(), text = clipped))
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun fileItem(context: Context, uri: Uri, fallbackType: String?): FlickItem {
        val resolver = context.contentResolver
        var name: String? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (_: Exception) {
        }
        val mime = try { resolver.getType(uri) } catch (_: Exception) { null }
            ?: fallbackType?.takeIf { !it.contains('*') }
            ?: "application/octet-stream"
        val kind = when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            else -> "file"
        }
        val finalName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "flick"
        return FlickItem(kind = kind, uri = uri, name = finalName, mime = mime, size = FlickSender.querySize(context, uri))
    }

    /** Card preview: image thumbnail, the video's first frame, or a provider thumbnail for documents. */
    fun loadPreview(context: Context, item: FlickItem): Bitmap? {
        val uri = item.uri ?: return null
        val bmp: Bitmap? = when (item.kind) {
            "video" -> videoFrame(context, uri) ?: providerThumb(context, uri)
            "image" -> providerThumb(context, uri) ?: decodeImage(context, uri)
            else -> providerThumb(context, uri)
        }
        return bmp?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }

    private fun providerThumb(context: Context, uri: Uri): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(PREVIEW_PX, PREVIEW_PX), null)
    } catch (_: Exception) {
        null
    }

    private fun decodeImage(context: Context, uri: Uri): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = PREVIEW_PX.toFloat() / max(w, h).coerceAtLeast(1)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        null
    }

    private fun videoFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PREVIEW_PX, PREVIEW_PX)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** ≤256 px JPEG, base64 ≤ 40 KB, for the `flickStart` message. */
    fun encodeThumbnail(source: Bitmap): String? = try {
        val scale = THUMB_PX.toFloat() / max(source.width, source.height).coerceAtLeast(1)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1), true)
        } else source
        var quality = 82
        var out: String
        do {
            val bytes = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
            out = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            quality -= 15
        } while (out.length > THUMB_MAX_B64 && quality >= 20)
        if (out.length <= THUMB_MAX_B64) out else null
    } catch (_: Exception) {
        null
    }
}
