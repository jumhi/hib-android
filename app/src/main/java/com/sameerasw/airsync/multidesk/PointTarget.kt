package com.sameerasw.airsync.multidesk

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sameerasw.airsync.utils.FlickAim
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * H.I.B Point to act (SPACE.md §11): the one shared point target. While a point-aware screen (Desk Deck, the
 * Remote, Scenes, the media controls, Play on…) is in front, aim tracking runs; the target is the computer that
 * owns the aimed screen (nearest within 35°), else the target rule. The aimed screen glows on its computer with
 * that screen's label (`flickAim{screen,label}`), and every action from those screens goes to the target only.
 *
 * Screens join with [rememberPointTarget] (Compose, lifecycle-aware) or [hold]/[release].
 */
object PointTarget {
    private const val POLL_MS = 100L
    /** How long a fresh start waits for the first compass reading and the screen map before it decides. */
    private const val SETTLE_MS = 400L

    private val lock = Any()
    private val session = PointSession()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    /** The current run has its first reading (or waited long enough): choices go out from here on. */
    @Volatile private var settled = false

    /** The saved screen map; kept between runs so a later start decides at once. Null until first loaded. */
    @Volatile private var saved: List<SavedScreenAim>? = null

    private val _choice = MutableStateFlow<PointChoice?>(null)

    /** The live target while a point-aware screen is open; null when none is (or before it has settled). */
    val choice: StateFlow<PointChoice?> = _choice.asStateFlow()

    /** True while a point-aware screen is in front. */
    val isTracking: Boolean get() = synchronized(lock) { session.active }

    /** A point-aware screen came to the front. Call on the main thread; balance with [release]. */
    fun hold(context: Context, label: String): Long {
        val app = context.applicationContext
        val (token, first) = synchronized(lock) {
            val first = !session.active
            session.hold(label) to first
        }
        if (first) start(app) else tick()  // the newest screen names the light
        return token
    }

    /** That screen left the front; tracking stops after the last one. */
    fun release(token: Long) {
        val last = synchronized(lock) { session.release(token) }
        if (last) stop() else if (isTracking) tick()
    }

    /** Where an action goes now: the live target while tracking, else the target rule. Null: nothing connected. */
    fun targetId(): String? = synchronized(lock) { session.targetId { DesktopHub.targetPick()?.id } }

    /** Sends to the target computer only. With nothing connected it falls back to the usual route (BLE). */
    fun send(json: String): Boolean {
        val id = targetId() ?: return WebSocketUtil.sendMessage(json)
        return DesktopHub.sendTo(id, json)
    }

    /** While a point-aware screen is open: to the target only. Otherwise the usual route (the main computer). */
    fun sendOrRoute(json: String): Boolean = if (isTracking) send(json) else WebSocketUtil.sendMessage(json)

    private fun start(context: Context) {
        FlickAim.start(context)
        val compass = FlickAim.isAvailable(context)
        DesktopHub.requestScreens()
        job?.cancel()
        settled = false
        job = scope.launch {
            launch { FlickScreenStore.flow(context).collect { saved = it } }
            val started = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                if (!settled) {
                    val waited = now - started >= SETTLE_MS
                    val haveAim = !compass || FlickAim.current() != null
                    settled = waited || (haveAim && saved != null)
                }
                if (settled) tick(now)
                delay(POLL_MS)
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        settled = false
        FlickAim.stop()
        val sends = synchronized(lock) { session.stop(DesktopHub.connectedIds()) }
        _choice.value = null
        sends.forEach { DesktopHub.sendToComputer(it.computer, it.json()) }
    }

    private fun tick(now: Long = System.currentTimeMillis()) {
        if (!settled) return
        val reading = FlickAim.current()?.let { AimMatcher.Aim(it.first, it.second) }
        val next = PointPick.choose(reading, saved.orEmpty(), FlickPointing.computers(), DesktopHub.candidates())
        val sends = synchronized(lock) {
            if (!session.active) return
            session.update(next, now)
        }
        if (_choice.value != next) _choice.value = next
        sends.forEach { DesktopHub.sendToComputer(it.computer, it.json()) }
    }
}

/**
 * Keeps point tracking on while this screen is in the foreground (resumed) and [enabled], with [label] on the
 * aimed screen. Returns the live target (null while not tracking or before it has settled).
 */
@Composable
fun rememberPointTarget(label: String, enabled: Boolean = true): State<PointChoice?> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, label, enabled) {
        var token: Long? = null
        fun hold() {
            if (enabled && token == null) token = PointTarget.hold(context, label)
        }
        fun release() {
            token?.let { PointTarget.release(it) }
            token = null
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> hold()
                Lifecycle.Event.ON_PAUSE -> release()
                else -> {}
            }
        }
        // Replays ON_RESUME when the screen is already in front.
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            release()
        }
    }
    return PointTarget.choice.collectAsState()
}

/** The chip only says something when there is a choice: two or more computers connected. */
@Composable
fun pointChipWanted(): Boolean {
    val desktops by DesktopHub.desktops.collectAsState()
    return desktops.count { it.isConnected } >= 2
}

/**
 * "→ <computer>": where this screen's actions go right now (SPACE.md §11.1). Updates live with a light haptic
 * tick when the computer changes; the target icon shows when pointing is what decided it. Hidden when only one
 * computer is connected or [computer] is null.
 */
@Composable
fun PointTargetChip(
    computer: String?,
    aimed: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val wanted = pointChipWanted()
    val visible = wanted && computer != null
    val haptics = LocalHapticFeedback.current
    val last = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(computer, visible) {
        val before = last[0]
        if (visible && computer != null && before != null && !PairedDesktops.sameName(before, computer)) {
            HapticUtil.performLightTick(haptics)
        }
        if (computer != null) last[0] = computer
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val name = computer ?: last[0].orEmpty()
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, accent.copy(alpha = if (aimed) 0.9f else 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .semantics { contentDescription = "Actions go to $name" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(visible = aimed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                }
            }
            AnimatedContent(
                targetState = name,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "pointTarget"
            ) { n ->
                Text(
                    text = "→ $n",
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
