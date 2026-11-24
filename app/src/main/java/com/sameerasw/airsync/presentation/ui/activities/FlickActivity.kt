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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.lifecycle.lifecycleScope
import com.sameerasw.airsync.utils.BundleProgress
import com.sameerasw.airsync.utils.BundleWords
import com.sameerasw.airsync.utils.FlickAim
import com.sameerasw.airsync.utils.FlickBundle
import com.sameerasw.airsync.utils.FlickBundleLayout
import com.sameerasw.airsync.utils.FlickSender
import com.sameerasw.airsync.utils.FlickShortcuts
import com.sameerasw.airsync.utils.Pt
import com.sameerasw.airsync.utils.RecentMedia
import com.sameerasw.airsync.widget.FlickWidget
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Several items are ONE bundle (SPACE.md §14): a fanned stack with a count badge. Tap it and it spreads out
 * into the bundle's pattern (5 = two, one, two…) where a tap leaves a card out; one flick throws them all,
 * the cards trailing the top one in a staggered arc, and the pill shows the whole bundle's progress.
 *
 * Reachable from the system share sheet (anything, also as a Direct Share target), the Flick card in H.I.B,
 * the Flick tile (the latest photo, [EXTRA_RECENT]) and the Flick widget. With [EXTRA_PICK] it opens the photo
 * picker first. With [EXTRA_POINT_SETUP] it only runs Point setup (Flick settings).
 */
class FlickActivity : ComponentActivity() {

    companion object {
        const val EXTRA_POINT_SETUP = "com.sameerasw.airsync.extra.POINT_SETUP"
        /** Open the photo picker first (the Flick tile without photo access, the widget's "+"). */
        const val EXTRA_PICK = "com.sameerasw.airsync.extra.FLICK_PICK"
        /** Start with the newest photo, video or screenshot (the Flick tile). */
        const val EXTRA_RECENT = "com.sameerasw.airsync.extra.FLICK_RECENT"
        /** Opened from a Flick widget (its id): the widget's selection is cleared. */
        const val EXTRA_WIDGET_ID = "com.sameerasw.airsync.extra.FLICK_WIDGET_ID"
        private const val MAX_PICK = 50

        /** Opens Flick just to run Point setup again for every screen. */
        fun pointSetupIntent(context: Context): Intent =
            Intent(context, FlickActivity::class.java).putExtra(EXTRA_POINT_SETUP, true)

        fun pickIntent(context: Context): Intent =
            Intent(context, FlickActivity::class.java).putExtra(EXTRA_PICK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun recentIntent(context: Context): Intent =
            Intent(context, FlickActivity::class.java).putExtra(EXTRA_RECENT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Flick these (H.I.B's own media URIs, e.g. from the widget) as one card or one bundle. */
        fun mediaIntent(context: Context, uris: List<Uri>, widgetId: Int = 0): Intent =
            Intent(context, FlickActivity::class.java).apply {
                if (uris.size == 1) {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                } else {
                    action = Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
                type = "*/*"
                if (widgetId != 0) putExtra(EXTRA_WIDGET_ID, widgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    /** What's on the overlay; the photo picker can add to it. */
    private val items = mutableStateListOf<FlickItem>()
    private var picking by mutableStateOf(false)

    private val picker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)
    ) { uris: List<Uri> ->
        picking = false
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                val picked = withContext(Dispatchers.IO) { uris.distinct().map { FlickMedia.itemFor(this@FlickActivity, it) } }
                val known = items.mapNotNull { it.uri }.toSet()
                items.addAll(picked.filter { it.uri !in known })
            }
        } else if (items.isEmpty()) {
            finish()
        }
    }

    private fun openPicker() {
        if (picking) return
        picking = true
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        } catch (_: Exception) {
            picking = false
            Toast.makeText(this, "No photo picker on this phone", Toast.LENGTH_SHORT).show()
            if (items.isEmpty()) finish()
        }
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
        val wantsPick = intent?.getBooleanExtra(EXTRA_PICK, false) == true
        val wantsRecent = intent?.getBooleanExtra(EXTRA_RECENT, false) == true
        val shared = !setupOnly && FlickMedia.hasContent(intent)
        intent?.getIntExtra(EXTRA_WIDGET_ID, 0)?.takeIf { it != 0 }?.let { FlickWidget.clearSelection(this, it) }

        if (!setupOnly && !shared && !wantsPick && !wantsRecent) {
            Toast.makeText(this, "Nothing to flick", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (shared) {
            // Names, types and sizes take a query or three per item: off the main thread (a bundle can be 50).
            lifecycleScope.launch {
                val parsed = withContext(Dispatchers.IO) { FlickMedia.parseIntent(this@FlickActivity, intent) }
                if (parsed.isEmpty() && items.isEmpty()) {
                    Toast.makeText(this@FlickActivity, "Nothing to flick", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    items.addAll(0, parsed)
                }
            }
        } else if (savedInstanceState == null && !setupOnly) {
            if (wantsPick) {
                openPicker()
            } else if (wantsRecent) {
                lifecycleScope.launch {
                    val latest = withContext(Dispatchers.IO) {
                        RecentMedia.latest(this@FlickActivity, 1).firstOrNull()?.let {
                            FlickMedia.itemFor(this@FlickActivity, RecentMedia.uriOf(it))
                        }
                    }
                    if (latest != null) items.add(latest) else openPicker()
                }
            }
        }

        setContent {
            AirSyncTheme(darkTheme = true) {
                FlickOverlay(
                    items = items,
                    setupOnly = setupOnly,
                    picking = picking,
                    onPick = { openPicker() },
                    onFinish = { finish() }
                )
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
private const val TRAIL_MAX = 6             // cards drawn flying behind the top one in a bundle throw
private const val STAGGER_MS = 55           // between cards of a bundle throw
private val TRAIL_BEND = 26.dp              // sideways bend of each trailing card's arc
private val FAN_GAP = 12.dp
private const val FINISH_WAIT_MS = 60_000L  // the overlay waits this long for the files before it closes

/** Previews are big for the first cards (they're seen full size) and small for the rest of a bundle. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val previewLoader = Dispatchers.IO.limitedParallelism(3)

@Composable
private fun FlickOverlay(
    items: SnapshotStateList<FlickItem>,
    setupOnly: Boolean,
    picking: Boolean,
    onPick: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = remember { FlickHaptics(context) }

    val previews = remember { mutableStateMapOf<String, FlickPreview>() }
    val loading = remember { mutableSetOf<String>() }

    val dim = remember { Animatable(0f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val grab = remember { Animatable(1f) }
    val enter = remember { Animatable(0f) }
    val throwScale = remember { Animatable(1f) }
    val throwAlpha = remember { Animatable(1f) }
    val trail = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    var pulseId by remember { mutableStateOf<String?>(null) }
    var liveVy by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var showSent by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var headerBottom by remember { mutableFloatStateOf(0f) }
    val pending = remember { mutableListOf<Deferred<Boolean>>() }
    val bubbleCenters = remember { mutableStateMapOf<String, Offset>() }

    // The thrown bundle: its file ids (for progress), sizes and how many items went.
    var sentIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var sentSizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var sentCount by remember { mutableIntStateOf(0) }
    var sentNoun by remember { mutableStateOf("") }
    // What flies in the current throw (the top card plus up to TRAIL_MAX behind it).
    var flying by remember { mutableStateOf<List<FlickItem>>(emptyList()) }
    var throwFlightMs by remember { mutableIntStateOf(300) }
    var throwStart by remember { mutableStateOf(Offset.Zero) }
    var throwEnd by remember { mutableStateOf(Offset.Zero) }

    // Bundle: spread out (fan) or stacked; cards left out; the page of a big bundle.
    var fanned by remember { mutableStateOf(false) }
    val out = remember { mutableStateListOf<String>() }
    var fanPage by remember { mutableIntStateOf(0) }
    val fanSlots = FanPage.slots(items.size, fanPage)
    val fanAnims = remember(items.size, fanPage) { List(fanSlots) { Animatable(0f) } }
    var throwCount by remember { mutableIntStateOf(0) }

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
    val fannedNow by rememberUpdatedState(fanned)

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
    val isBundle = items.size > 1
    val includedCount = items.count { it.id !in out }

    // Progress of the thrown files (SPACE.md §14: the whole bundle).
    val transfers by FlickSender.transfers.collectAsState()
    val progress = remember(sentIds, sentSizes, transfers) { BundleProgress.of(sentIds, sentSizes, transfers) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            dim.animateTo(0f, tween(180))
            onFinish()
        }
    }

    /** Close now, but only once every file is open (the share grant ends with this screen). */
    fun hide() {
        if (closing) return
        scope.launch {
            withTimeoutOrNull(15_000) { pending.forEach { it.await() } }
            close()
        }
    }

    fun startSetup(list: List<ScreenTarget>) {
        if (list.isEmpty() || inSetupNow) return
        aimed = null
        FlickPointing.aimAt(null)
        setupTargets = list
    }

    fun setFanned(on: Boolean) {
        if (on == fanned && fanAnims.all { it.targetValue == if (on) 1f else 0f }) return
        fanned = on
        val anims = fanAnims
        anims.forEachIndexed { i, a ->
            scope.launch {
                delay((if (on) i else anims.size - 1 - i) * 38L)
                a.animateTo(
                    if (on) 1f else 0f,
                    spring(dampingRatio = if (on) 0.66f else 0.8f, stiffness = Spring.StiffnessMediumLow)
                )
            }
        }
    }

    /** Stack the cards again; the ones left out drop out of the bundle. */
    fun gather() {
        if (!fanned) return
        setFanned(false)
        scope.launch {
            delay(260L + 38L * fanAnims.size)
            if (out.isNotEmpty()) {
                val gone = out.toSet()
                items.removeAll { it.id in gone }
                out.clear()
                if (items.isEmpty()) close()
            }
            fanPage = 0
        }
    }

    LaunchedEffect(Unit) {
        dim.animateTo(1f, tween(220))
    }
    // Previews for every item (also ones the picker adds later), the first ones big.
    LaunchedEffect(items.toList().map { it.id }) {
        items.toList().forEachIndexed { i, item ->
            if (item.id in previews || !loading.add(item.id)) return@forEachIndexed
            launch(previewLoader) {
                FlickMedia.loadPreview(context, item, if (i < 4) FlickMedia.PREVIEW_PX else FlickMedia.SMALL_PREVIEW_PX)?.let { bmp ->
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
    // A new page (or more cards from the picker) while spread out: they spring into place one by one.
    LaunchedEffect(fanAnims) {
        if (!fannedNow) return@LaunchedEffect
        fanAnims.forEachIndexed { i, a ->
            launch {
                delay(i * 38L)
                a.animateTo(1f, spring(dampingRatio = 0.66f, stiffness = Spring.StiffnessMediumLow))
            }
        }
    }
    // A bundle that shrinks to one card is a single card again.
    LaunchedEffect(isBundle) {
        if (!isBundle && fanned) {
            fanned = false
            fanAnims.forEach { it.snapTo(0f) }
        }
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

    BackHandler {
        if (fanned) gather() else if (showSent) hide() else close()
    }

    /** Sends every item of [list] in one throw: all `flickStart`s first, then the files through the queue. */
    fun fire(list: List<FlickItem>, to: String, screen: String?, exitXRatio: Float, velocity: Float, angle: Float) {
        haptics.heavy()
        val tags = FlickBundle.tags(UUID.randomUUID().toString(), list.size)
        val snapshot = list.map { it to previews[it.id]?.thumbnailBase64 }
        pending += scope.async(Dispatchers.IO) {
            var ok = true
            snapshot.forEachIndexed { i, (item, thumb) ->
                val started = FlickSender.sendFlickStart(
                    id = item.id,
                    kind = item.kind,
                    name = item.name,
                    mime = item.mime,
                    size = item.size,
                    exitXRatio = exitXRatio,
                    velocity = velocity,
                    angle = angle,
                    thumbnailBase64 = thumb,
                    text = item.text,
                    to = to,
                    screen = screen,
                    bundle = tags[i]
                )
                if (item.uri == null) ok = ok && started
            }
            snapshot.forEach { (item, _) ->
                if (item.uri != null) {
                    ok = FlickSender.startFileTransfer(context, item.id, item.uri, item.name, item.mime, to = to) && ok
                }
            }
            ok
        }
    }

    /** Throws the stack into [targetId]'s bubble; it lands on [screen] there (null: the computer chooses). */
    fun launchCard(v: Offset, targetId: String, screen: String?) {
        val left = out.toSet()
        val bundle = items.filter { it.id !in left }.toList()
        if (bundle.isEmpty()) return
        launching = true
        throwCount = bundle.size
        sentTo = targetId
        FlickSender.target = targetId
        DesktopHub.noteUsed(targetId)
        FlickShortcuts.reportUsed(context)
        flying = bundle.take(1 + TRAIL_MAX)
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
            throwFlightMs = durationMs
            throwStart = start
            throwEnd = end
            val cards = flying.size
            val trailJob = launch {
                trail.snapTo(0f)
                if (cards > 1) {
                    var landed = 1
                    trail.animateTo(1f, tween(FlickBundleLayout.throwMs(cards, durationMs, STAGGER_MS), easing = LinearEasing)) {
                        // A little burst of ticks as the trailing cards land one after another.
                        val now = (1 until cards).count {
                            FlickBundleLayout.localT(value, it, cards, durationMs, STAGGER_MS) >= 1f
                        } + 1
                        if (now > landed) {
                            landed = now
                            haptics.tick()
                        }
                    }
                }
            }
            launch { throwScale.animateTo(0.12f, tween(durationMs, easing = FastOutLinearInEasing)) }
            launch { throwAlpha.animateTo(0f, tween(durationMs / 3, delayMillis = durationMs * 2 / 3)) }
            offset.animateTo(end, tween(durationMs, easing = LinearOutSlowInEasing)) {
                // The card is on its way: tell the computer now so its card can start flying in.
                if (!fired && (value - start).getDistance() >= dist * 0.3f) {
                    fired = true
                    fire(bundle, targetId, screen, exitX, max(mag, LAUNCH_VELOCITY), angle)
                }
            }
            if (!fired) fire(bundle, targetId, screen, exitX, max(mag, LAUNCH_VELOCITY), angle)
            trailJob.join()

            // Landed in the bubble.
            haptics.tick()
            pulseId = targetId
            launch {
                pulse.snapTo(1f)
                pulse.animateTo(0f, tween(560))
            }
            sentIds = bundle.filter { it.uri != null }.map { it.id }
            sentSizes = bundle.associate { it.id to it.size }
            sentCount = bundle.size
            sentNoun = BundleWords.noun(bundle.map { it.kind })
            val gone = bundle.map { it.id }.toSet() + left
            items.removeAll { it.id in gone }
            out.clear()
            flying = emptyList()
            offset.snapTo(Offset.Zero)
            throwScale.snapTo(1f)
            throwAlpha.snapTo(1f)
            trail.snapTo(0f)
            launching = false
            if (items.isEmpty()) {
                showSent = true
                // Keep the overlay (and its share grant) alive until every file stream is open, then show
                // the bundle's progress until it's all there (Hide leaves it sending in the background).
                withTimeoutOrNull(15_000) { pending.forEach { it.await() } }
                val ids = sentIds
                val sizes = sentSizes
                withTimeoutOrNull(FINISH_WAIT_MS) {
                    FlickSender.transfers.first { BundleProgress.of(ids, sizes, it).finished }
                }
                delay(1100)
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

    // Card geometry (px): the stacked card, and the fan's cells, centre and slots.
    val cardWidth = with(density) { min((rootSize.width * 0.64f).toDp(), 300.dp) }
    val cardHeight = cardWidth * 1.3f
    val cardWpx = with(density) { cardWidth.toPx() }
    val cardHpx = with(density) { cardHeight.toPx() }
    val stackCentre = Offset(rootSize.width / 2f, rootSize.height / 2f + with(density) { CARD_SHIFT.toPx() })
    val fanTop = headerBottom + with(density) { 12.dp.toPx() }
    val fanBottom = rootSize.height - with(density) { 104.dp.toPx() }
    val fanCentre = Offset(rootSize.width / 2f, (fanTop + fanBottom) / 2f)
    val fanCellW = with(density) {
        FlickBundleLayout.fitCell(
            fanSlots, rootSize.width - 32.dp.toPx(), (fanBottom - fanTop).coerceAtLeast(1f),
            FAN_GAP.toPx(), 1.3f, cardWpx * 0.62f
        )
    }
    val fanCells = with(density) { FlickBundleLayout.fan(fanSlots, fanCellW, fanCellW * 1.3f, FAN_GAP.toPx()) }
    val fanScale = if (cardWpx > 0f) fanCellW / cardWpx else 1f
    val pageItems = FanPage.items(items, fanPage)
    val plusCount = FanPage.plus(items.size, fanPage)

    /** Which fan slot is under [p] (or -1). */
    fun slotAt(p: Offset): Int {
        val hw = fanCellW / 2f
        val hh = fanCellW * 1.3f / 2f
        fanCells.forEachIndexed { i, c ->
            val x = fanCentre.x + c.x
            val y = fanCentre.y + c.y
            if (p.x in (x - hw)..(x + hw) && p.y in (y - hh)..(y + hh)) return i
        }
        return -1
    }

    fun onTap(p: Offset) {
        if (launching || inSetupNow || showSent) return
        if (fanned) {
            val slot = slotAt(p)
            when {
                slot < 0 -> gather()
                slot < pageItems.size -> {
                    val id = pageItems[slot].id
                    haptics.tick()
                    if (id in out) out.remove(id) else if (includedCount > 1) out.add(id) else {
                        Toast.makeText(context, "A bundle needs at least one card", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    // The "+N" card: the next page of a big bundle.
                    haptics.tick()
                    fanPage = (fanPage + 1) % FanPage.pages(items.size)
                }
            }
            return
        }
        if (!isBundle) return
        val hw = cardWpx / 2f
        val hh = cardHpx / 2f
        val c = stackCentre + offset.value
        if (p.x in (c.x - hw)..(c.x + hw) && p.y in (c.y - hh)..(c.y + hh)) {
            haptics.tick()
            setFanned(true)
        }
    }

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
                detectTapGestures(onTap = { onTap(it) })
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        if (!launching && !inSetupNow && items.isNotEmpty()) {
                            // Dragging a spread-out bundle gathers it and throws the lot.
                            if (fannedNow) gather()
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
        // Header: title, where a flick goes now, and the device bubbles.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp)
                .onGloballyPositioned { headerBottom = it.boundsInRoot().bottom }
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
                if (!setupOnly && !showSent) {
                    IconButton(onClick = {
                        haptics.tick()
                        onPick()
                    }) {
                        Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Add photos", tint = Color.White.copy(alpha = 0.85f))
                    }
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

            if (connected && !inSetup && !setupOnly && !fanned) {
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
                        text = if (isBundle) "Flick up to send all $includedCount" else "Flick up to send",
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
            if (isBundle && !inSetup && !launching) {
                Text(
                    text = if (fanned) {
                        "Tap a card to leave it out · tap outside to stack them"
                    } else {
                        BundleWords.noun(items.filter { it.id !in out }.map { it.kind }) + " · tap the stack to spread them out"
                    },
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // The cards, back to front. Stacked: the top card and a few peeking out; spread: the page's pattern;
        // thrown: the top card leads and the rest trail it in a staggered arc.
        val deck: List<FlickItem> = when {
            launching -> flying
            fanned || fanAnims.any { it.value > 0.001f } -> pageItems
            else -> items.take(1 + FlickBundleLayout.PEEK)
        }
        val shownCount = if (launching) flying.size else items.size
        for (i in deck.indices.reversed()) {
            val item = deck[i]
            val isTop = i == 0
            val pose = FlickBundleLayout.stackPose(i.coerceAtMost(FlickBundleLayout.PEEK))
            val stackIndex = items.indexOf(item).let { if (it < 0) i else it }
            val excluded = item.id in out
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = CARD_SHIFT)
                    .size(cardWidth, cardHeight)
                    .graphicsLayer {
                        val bundle = shownCount > 1
                        val entering = if (isTop) 1f - enter.value else 0f
                        if (launching && !isTop) {
                            // Trailing a bundle throw: a bent arc from where it sat to the bubble.
                            val cards = flying.size
                            val lt = FlickBundleLayout.localT(trail.value, i, cards, throwFlightMs, STAGGER_MS)
                            val eased = 1f - (1f - lt) * (1f - lt)
                            val from = Pt(pose.dx.dp.toPx() + throwStart.x * follow(i), pose.dy.dp.toPx() + throwStart.y * follow(i))
                            val p = FlickBundleLayout.trailPoint(from, Pt(throwEnd.x, throwEnd.y), eased, i, TRAIL_BEND.toPx())
                            translationX = p.x
                            translationY = p.y
                            rotationZ = pose.rotation + (if (i % 2 == 1) 28f else -28f) * lt
                            val s = pose.scale + (0.12f - pose.scale) * eased
                            scaleX = s
                            scaleY = s
                            val emerge = if (i > FlickBundleLayout.PEEK) (lt / 0.15f).coerceIn(0f, 1f) else 1f
                            val fade = if (lt > 0.66f) (1f - (lt - 0.66f) / 0.34f).coerceIn(0f, 1f) else 1f
                            alpha = dim.value * cardsAlpha * emerge * fade
                            return@graphicsLayer
                        }
                        // Spread-out progress of this card (only cards on the page spread).
                        val f = if (!launching && i < fanAnims.size && deck === pageItems) fanAnims[i].value else 0f
                        val stackDepth = if (deck === pageItems) stackIndex else i
                        val sPose = FlickBundleLayout.stackPose(stackDepth.coerceAtMost(FlickBundleLayout.PEEK))
                        val o = offset.value
                        // Stacked: peeking cards follow the drag a little behind the top one.
                        val stackX = if (bundle) sPose.dx.dp.toPx() + o.x * follow(stackDepth) else o.x
                        val stackY = if (bundle) sPose.dy.dp.toPx() + o.y * follow(stackDepth) else o.y + entering * 18.dp.toPx()
                        val slot = fanCells.getOrNull(i) ?: Pt(0f, 0f)
                        val fanX = fanCentre.x - stackCentre.x + slot.x
                        val fanY = fanCentre.y - stackCentre.y + slot.y
                        translationX = stackX + (fanX - stackX) * f
                        translationY = stackY + (fanY - stackY) * f
                        val stackRot = if (bundle) sPose.rotation else 0f
                        val throwRot = if (isTop && !bundle) (o.x / size.width) * 14f * throwScale.value else if (isTop) stackRot + (o.x / size.width) * 10f * throwScale.value else stackRot
                        rotationZ = throwRot * (1 - f)
                        val stackScale = (if (bundle) sPose.scale else 1f - 0.06f * entering) *
                            (if (isTop) grab.value * throwScale.value else 1f)
                        val s = stackScale + (fanScale - stackScale) * f
                        scaleX = s
                        scaleY = s
                        val hidden = if (stackDepth > FlickBundleLayout.PEEK) f else 1f
                        val dimOut = if (excluded) 1f - 0.62f * f else 1f
                        alpha = dim.value * cardsAlpha * hidden * dimOut * (if (isTop) throwAlpha.value else 1f)
                    }
            ) {
                FlickCardFace(item, previews[item.id]?.image, if (isTop) glow else glow * 0.4f)
                // Spread out: a check on each card that goes (tap to leave it out).
                val f = if (i < fanAnims.size && deck === pageItems && !launching) fanAnims[i].value else 0f
                if (f > 0.01f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp)
                            .size(44.dp)
                            .graphicsLayer { alpha = f; scaleX = 1f / fanScale.coerceAtLeast(0.3f) * 0.55f; scaleY = scaleX }
                            .clip(CircleShape)
                            .background(if (excluded) Color.Black.copy(alpha = 0.55f) else FlickCyan)
                            .border(2.dp, Color.White.copy(alpha = if (excluded) 0.6f else 0f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!excluded) Icon(Icons.Rounded.Check, contentDescription = "In the bundle", tint = HibInk, modifier = Modifier.size(28.dp))
                    }
                }
                // Stacked bundle: how many go in one flick.
                if (isTop && shownCount > 1 && !(fanned && !launching)) {
                    val badge = if (launching) throwCount else includedCount
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(40.dp)
                            .graphicsLayer { alpha = 1f - (fanAnims.maxOfOrNull { it.value } ?: 0f) }
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .background(HibLime),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$badge",
                            color = HibInk,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }

        // Spread out, a big bundle: the "+N" card turns the page.
        if (plusCount > 0 && (fanned || fanAnims.any { it.value > 0.001f }) && !launching) {
            val slot = fanCells.lastOrNull() ?: Pt(0f, 0f)
            val f = fanAnims.lastOrNull()?.value ?: 0f
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = CARD_SHIFT)
                    .size(cardWidth, cardHeight)
                    .graphicsLayer {
                        translationX = (fanCentre.x - stackCentre.x + slot.x) * f
                        translationY = (fanCentre.y - stackCentre.y + slot.y) * f
                        scaleX = 0.9f + (fanScale - 0.9f) * f
                        scaleY = scaleX
                        alpha = dim.value * f
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .background(HibPanel)
                    .border(2.dp, FlickCyan.copy(alpha = 0.6f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+$plusCount", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.displayMedium)
                    Text("more", color = FlickCyan, style = MaterialTheme.typography.titleLarge)
                }
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

        // Waiting for the photo picker (from the tile or the widget).
        if (items.isEmpty() && !showSent && !setupOnly && picking) {
            Text(
                "Pick photos or videos to flick",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Bottom: Cancel (Hide while the files are still going).
        if (!inSetup) {
            TextButton(
                onClick = { if (fanned) gather() else if (showSent) hide() else close() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .graphicsLayer { alpha = dim.value }
            ) {
                Text(
                    when {
                        setupOnly -> "Close"
                        fanned -> "Stack them"
                        showSent && !progress.finished -> "Hide · keeps sending"
                        showSent -> "Done"
                        else -> "Cancel"
                    },
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        // "Sending 5 to …" with the whole bundle's progress, then "Sent".
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
                    val where = sentTo ?: "computer"
                    when {
                        !progress.finished -> {
                            val shown by animateFloatAsState(progress.fraction, label = "bundleProgress")
                            CircularProgressIndicator(
                                progress = { shown },
                                modifier = Modifier.size(22.dp),
                                color = FlickCyan,
                                trackColor = Color.White.copy(alpha = 0.15f),
                                strokeWidth = 3.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (sentCount > 1) "Sending $sentNoun to $where" else "Sending to $where",
                                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (progress.total > 1) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${progress.done + progress.failed}/${progress.total}",
                                    color = FlickCyan, style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                        progress.failed > 0 -> {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Color(0xFFFFB74D))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Sent ${progress.done} of ${progress.total} to $where",
                                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        else -> {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = HibLime)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (sentCount > 1) "Sent $sentNoun to $where" else "Sent to $where",
                                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How far a peeking card follows the drag of the top one (a little lag per depth). */
private fun follow(depth: Int): Float = (1f - 0.1f * depth).coerceAtLeast(0.6f)

/** Pages of a big bundle when it's spread out: 8 cards and a "+N" card that turns the page. */
internal object FanPage {
    fun pages(count: Int): Int =
        if (count <= FlickBundleLayout.MAX_SLOTS) 1 else (count + FlickBundleLayout.PAGE - 1) / FlickBundleLayout.PAGE

    fun <T> items(all: List<T>, page: Int): List<T> =
        if (all.size <= FlickBundleLayout.MAX_SLOTS) all
        else all.drop(page * FlickBundleLayout.PAGE).take(FlickBundleLayout.PAGE)

    /** The "+N" on this page: everything not shown on it (0 when it all fits). */
    fun plus(count: Int, page: Int): Int =
        if (count <= FlickBundleLayout.MAX_SLOTS) 0
        else count - minOf(FlickBundleLayout.PAGE, count - page * FlickBundleLayout.PAGE)

    /** Fan slots on this page (cards plus the "+N" card). */
    fun slots(count: Int, page: Int): Int =
        if (count <= FlickBundleLayout.MAX_SLOTS) count
        else minOf(FlickBundleLayout.PAGE, count - page * FlickBundleLayout.PAGE) + 1
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
    const val PREVIEW_PX = 720
    /** Cards further down a bundle are only seen small (and as the computer's thumbnail). */
    const val SMALL_PREVIEW_PX = 360
    private const val THUMB_PX = 256
    private const val THUMB_MAX_B64 = 40_000

    /** True when [intent] carries something to flick (cheap: no queries). */
    fun hasContent(intent: Intent?): Boolean {
        intent ?: return false
        if (streamListExtra(intent).isNotEmpty() || streamExtra(intent) != null) return true
        val clip = intent.clipData
        if (clip != null && (0 until clip.itemCount).any { clip.getItemAt(it).uri != null }) return true
        return !intent.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()
    }

    fun parseIntent(context: Context, intent: Intent?): List<FlickItem> {
        intent ?: return emptyList()
        // Every URI the share carries: EXTRA_STREAM (one or a list, whatever the action says) and the
        // ClipData the system builds (and grants read access to) for each of them. Some galleries send one
        // and not the other, so both are read and merged.
        val uris = LinkedHashSet<Uri>()
        uris += streamListExtra(intent)
        streamExtra(intent)?.let { uris += it }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
        }
        if (uris.isNotEmpty()) {
            return uris.map { uri -> fileItem(context, uri, intent.type) }
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

    /** EXTRA_STREAM as one Uri (wrong-typed or absent → null; never throws). */
    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? = try {
        intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri
    } catch (_: Exception) {
        null
    }

    /**
     * EXTRA_STREAM as a list. The untyped getter on purpose: Android 13's typed one has a bug (androidx's
     * IntentCompat avoids it before 14), and some apps put a plain ArrayList<Parcelable> there.
     */
    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> = try {
        intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)?.filterIsInstance<Uri>().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /** A card for one URI H.I.B can read (picked, or its own media query). */
    fun itemFor(context: Context, uri: Uri): FlickItem = fileItem(context, uri, null)

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
    fun loadPreview(context: Context, item: FlickItem, px: Int = PREVIEW_PX): Bitmap? {
        val uri = item.uri ?: return null
        return previewFor(context, uri, item.kind, px)
    }

    /** A preview of any readable [uri] of [kind] ("image", "video" or anything else), at most [px] on a side. */
    fun previewFor(context: Context, uri: Uri, kind: String, px: Int = SMALL_PREVIEW_PX): Bitmap? {
        val bmp: Bitmap? = when (kind) {
            "video" -> videoFrame(context, uri, px) ?: providerThumb(context, uri, px)
            "image" -> providerThumb(context, uri, px) ?: decodeImage(context, uri, px)
            else -> providerThumb(context, uri, px)
        }
        return bmp?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }

    private fun providerThumb(context: Context, uri: Uri, px: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(px, px), null)
    } catch (_: Exception) {
        null
    }

    private fun decodeImage(context: Context, uri: Uri, px: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = px.toFloat() / max(w, h).coerceAtLeast(1)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        null
    }

    private fun videoFrame(context: Context, uri: Uri, px: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px)
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
