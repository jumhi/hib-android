package com.sameerasw.airsync.presentation.ui.activities

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.multidesk.AimMatcher
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.FlickPointing
import com.sameerasw.airsync.multidesk.FlickScreenStore
import com.sameerasw.airsync.multidesk.HibCyan
import com.sameerasw.airsync.multidesk.HibInk
import com.sameerasw.airsync.multidesk.HibLime
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.multidesk.PointSetupRun
import com.sameerasw.airsync.multidesk.ScreenTarget
import com.sameerasw.airsync.utils.FlickAim
import kotlinx.coroutines.delay

private const val SETUP_POLL_MS = 33L
private const val CAPTURED_HOLD_MS = 550L

/**
 * Point setup (SPACE.md §7.5): each screen lights up on its computer in turn ("Point your phone here");
 * the user points at it and holds still for about a second. Each captured screen is saved right away, so
 * a Skip keeps what was done. [onDone] gets the screens left unmapped when the user skipped (else empty).
 */
@Composable
internal fun PointSetupPanel(
    targets: List<ScreenTarget>,
    haptics: FlickHaptics,
    modifier: Modifier = Modifier,
    onCurrent: (ScreenTarget?) -> Unit,
    onDone: (skipped: List<ScreenTarget>) -> Unit
) {
    val context = LocalContext.current
    val run = remember(targets) { PointSetupRun(targets) }
    val total = targets.size
    var index by remember(run) { mutableIntStateOf(0) }
    var progress by remember(run) { mutableFloatStateOf(0f) }
    var blocked by remember(run) { mutableStateOf(false) }
    var noCompass by remember(run) { mutableStateOf(false) }
    var captured by remember(run) { mutableStateOf(false) }
    var finished by remember(run) { mutableStateOf(false) }
    var ended by remember(run) { mutableStateOf(false) }
    var shown by remember(run) { mutableStateOf(targets.firstOrNull()) }

    fun end(skipped: Boolean) {
        if (ended) return
        ended = true
        FlickPointing.endSetup()
        onCurrent(null)
        onDone(if (skipped) targets.drop(run.index) else emptyList())
    }

    LaunchedEffect(run) {
        while (!ended && !run.done) {
            val cur = run.current ?: break
            // Its computer went away: move on.
            if (DesktopHub.connectedIds().none { PairedDesktops.sameName(it, cur.computer) }) {
                run.skipCurrent()
                index = run.index
                continue
            }
            shown = cur
            onCurrent(cur)
            val reading = FlickAim.current()?.let { AimMatcher.Aim(it.first, it.second) }
            noCompass = reading == null
            val got = run.feed(reading, System.currentTimeMillis())
            if (got != null) {
                progress = 1f
                captured = true
                haptics.heavy()
                FlickPointing.setupAt(cur, index + 1, total, 1f, force = true)
                FlickScreenStore.save(context, listOf(got))
                delay(CAPTURED_HOLD_MS)
                if (ended) break
                captured = false
                progress = 0f
                blocked = false
                index = run.index
            } else {
                progress = run.progress
                blocked = run.blocked
                FlickPointing.setupAt(cur, run.index + 1, total, progress)
            }
            delay(SETUP_POLL_MS)
        }
        if (!ended) {
            finished = true
            FlickPointing.endSetup()
            onCurrent(null)
            delay(900)
            if (!ended) {
                ended = true
                onDone(emptyList())
            }
        }
    }

    val shownProgress by animateFloatAsState(progress, tween(80, easing = LinearEasing), label = "setupProgress")
    val pulse = rememberInfiniteTransition(label = "setupPulse")
    val breathe by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "setupBreathe"
    )
    val cur = shown

    Box(modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 40.dp)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SetupRing(
                progress = if (finished) 1f else shownProgress,
                done = captured || finished,
                breathe = breathe
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = when {
                    finished -> "All set"
                    cur == null -> ""
                    cur.screen.isEmpty() -> "Point at ${cur.computer}'s screen"
                    else -> "Point at the glowing screen"
                },
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            if (!finished && cur != null && cur.screen.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = cur.title(),
                    color = HibCyan,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    finished -> "Now point your phone at a screen and flick"
                    captured -> "Got it"
                    noCompass -> "Waiting for the compass… move the phone a little"
                    blocked -> "That one's done. Point at the next screen"
                    else -> "Hold still for a second · Screen ${index + 1} of $total"
                },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }

        if (!finished) {
            TextButton(
                onClick = {
                    haptics.tick()
                    end(skipped = true)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
            ) {
                Text("Skip", color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

/** The ring the user fills by holding still: cyan while filling, lime with a check when captured. */
@Composable
private fun SetupRing(progress: Float, done: Boolean, breathe: Float) {
    val doneAnim by animateFloatAsState(if (done) 1f else 0f, tween(180), label = "ringDone")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(176.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2f + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            // Soft halo that breathes while waiting.
            drawCircle(
                color = HibCyan.copy(alpha = 0.08f + 0.08f * breathe * (1f - doneAnim)),
                radius = size.minDimension / 2f
            )
            drawArc(
                color = Color.White.copy(alpha = 0.14f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke)
            )
            if (progress > 0f) {
                drawArc(
                    color = if (doneAnim > 0.5f) HibLime else HibCyan,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
            if (doneAnim > 0f) {
                drawCircle(
                    color = HibLime.copy(alpha = doneAnim),
                    radius = (arcSize.minDimension / 2f - stroke) * (0.6f + 0.4f * doneAnim)
                )
            }
        }
        if (doneAnim > 0.5f) {
            Icon(Icons.Rounded.Check, contentDescription = "Done", tint = HibInk, modifier = Modifier.size(64.dp))
        } else {
            Icon(
                Icons.Rounded.MyLocation,
                contentDescription = null,
                tint = HibCyan,
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        val s = 1f + 0.06f * breathe
                        scaleX = s
                        scaleY = s
                    }
            )
        }
    }
}
