package com.sameerasw.airsync.remote

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.text.format.DateUtils
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sameerasw.airsync.multidesk.DesktopHub
import kotlin.math.roundToInt

// MARK: - top row

/** The computers the Remote can drive; the picked one is filled. A dot marks the connected ones. */
@Composable
fun RemoteComputerChips(
    desktops: List<DesktopHub.DesktopStatus>,
    selected: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        desktops.forEach { d ->
            val isSel = selected != null && RemoteHub.key(selected) == RemoteHub.key(d.name)
            FilterChip(
                selected = isSel,
                onClick = { onPick(d.name) },
                label = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (d.isConnected) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

/** "▾ G9": which display of the computer the picture shows. Hidden with one display. */
@Composable
fun RemoteDisplayPicker(displays: List<RemoteDisplay>, current: String, onPick: (String) -> Unit) {
    if (displays.size < 2) return
    var open by remember { mutableStateOf(false) }
    val shown = displays.firstOrNull { it.id == current } ?: displays.firstOrNull { it.main } ?: displays.first()
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.DesktopWindows, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(shown.name.ifBlank { "Display" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            displays.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.name.ifBlank { "Display" } + if (d.main) " (main)" else "") },
                    leadingIcon = { if (d.id == shown.id) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { open = false; onPick(d.id) }
                )
            }
        }
    }
}

/** ⋯: Picture on/off, Quality, Natural scrolling, Pointer speed. */
@Composable
fun RemoteMenu(settings: RemoteSettings) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Remote options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (settings.pictureOn) "Picture off" else "Picture on") },
                leadingIcon = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null) },
                onClick = { open = false; settings.changePicture(!settings.pictureOn) }
            )
            HorizontalDivider()
            Text("Quality", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            RemoteSettings.Quality.entries.forEach { q ->
                DropdownMenuItem(
                    text = { Text(q.label) },
                    leadingIcon = { if (settings.quality == q) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { open = false; settings.changeQuality(q) }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Natural scrolling") },
                leadingIcon = { if (settings.naturalScroll) Icon(Icons.Rounded.Check, contentDescription = null) },
                onClick = { settings.changeNatural(!settings.naturalScroll) }
            )
            Text("Pointer speed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            listOf("Slower" to 0.7f, "Normal" to 1f, "Faster" to 1.4f).forEach { (label, v) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = {
                        if (kotlin.math.abs(settings.pointerSpeed - v) < 0.05f) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = { open = false; settings.changeSpeed(v) }
                )
            }
        }
    }
}

// MARK: - picture

/**
 * The live picture: a TextureView the decoder draws into, kept at the encoded picture's shape, with the
 * computer's cursor drawn on top from the frame headers (it is never in the video).
 */
@Composable
fun RemotePicture(stream: RemoteStream, aspect: Float, modifier: Modifier = Modifier) {
    val cursor by stream.cursor.collectAsState()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val boxAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else aspect
        val fitWidth = aspect >= boxAspect
        Box(
            modifier = Modifier
                .then(if (fitWidth) Modifier.fillMaxWidth() else Modifier.height(maxHeight))
                .aspectRatio(aspect, matchHeightConstraintsFirst = !fitWidth)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = true
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null
                            // A new view (mode switch, layout change) only re-binds the decoder; the /screen
                            // session doesn't depend on it (SPACE.md §15.3).
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                surface = Surface(st).also { stream.attachSurface(it) }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                surface?.let { stream.detachSurface(it); it.release() }
                                surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                }
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wPx = constraints.maxWidth
                val hPx = constraints.maxHeight
                val inView = cursor.visible && cursor.x in -0.01f..1.01f && cursor.y in -0.01f..1.01f
                if (inView) {
                    CursorArrow(
                        Modifier.offset { IntOffset((cursor.x * wPx).roundToInt(), (cursor.y * hPx).roundToInt()) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CursorArrow(modifier: Modifier) {
    Canvas(modifier.size(18.dp, 24.dp)) {
        val s = size.width / 12f
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 17f * s)
            lineTo(4.2f * s, 13f * s)
            lineTo(7f * s, 19.5f * s)
            lineTo(9.6f * s, 18.4f * s)
            lineTo(6.9f * s, 12f * s)
            lineTo(12f * s, 12f * s)
            close()
        }
        drawPath(p, Color.White)
        drawPath(p, Color.Black, style = Stroke(width = 1.4f * s))
    }
}

// MARK: - cards over the picture area

/** A centred card with an icon, a title, a line of help and up to one button. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemoteCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String? = null,
    busy: Boolean = false,
    action: String? = null,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onAction: () -> Unit = {},
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 420.dp)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(28.dp))
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (busy) LoadingIndicator(Modifier.size(40.dp))
                else Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (!body.isNullOrBlank()) {
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                if (action != null) {
                    Spacer(Modifier.height(2.dp))
                    Button(onClick = onAction, modifier = Modifier.height(52.dp)) {
                        if (actionIcon != null) {
                            Icon(actionIcon, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(action, style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (secondary != null) TextButton(onClick = onSecondary) { Text(secondary) }
            }
        }
    }
}

/** The locked card (design §5): Unlock with the phone's fingerprint, or why it can't. */
@Composable
fun RemoteLockedCard(name: String, hello: RemoteHello?, unlock: RemoteUnlockFlow.Ui?, onUnlock: () -> Unit) {
    val busy = unlock?.step?.let {
        it == UnlockFlowState.Step.REQUESTING || it == UnlockFlowState.Step.CHALLENGED ||
            it == UnlockFlowState.Step.PROMPTING || it == UnlockFlowState.Step.APPROVING
    } == true
    val done = unlock?.step == UnlockFlowState.Step.DONE
    when {
        busy || done -> RemoteCard(Icons.Rounded.Lock, "Unlocking $name…", busy = true)
        hello?.unlock == RemoteHello.UNLOCK_NEEDS_SETUP -> RemoteCard(
            Icons.Rounded.Lock, "$name is locked",
            "Set up Unlock from phone on $name first: in H.I.B on $name, open Settings → Phone remote → Unlock from phone."
        )
        hello?.unlock == RemoteHello.UNLOCK_MANAGED -> RemoteCard(
            Icons.Rounded.Lock, "$name is locked",
            "Your organisation manages $name, so Unlock from phone isn't available there. Unlock it at the computer."
        )
        hello?.unlock == RemoteHello.UNLOCK_UNSUPPORTED || hello == null -> RemoteCard(
            Icons.Rounded.Lock, "$name is locked", "Unlock from phone isn't available on $name yet. Unlock it at the computer."
        )
        else -> RemoteCard(
            Icons.Rounded.Lock, "$name is locked", unlock?.message,
            action = "Unlock", actionIcon = Icons.Rounded.Fingerprint, onAction = onUnlock
        )
    }
}

/** Can't reach the computer: asleep, or (away from home) Tailscale is off on the phone. */
@Composable
fun RemoteUnreachableCard(name: String, tailscaleHint: Boolean, lastSeen: Long?) {
    val context = LocalContext.current
    if (tailscaleHint) {
        RemoteCard(
            Icons.Rounded.CloudOff, "Turn on Tailscale to reach your computers from here",
            "$name is on your Tailscale network, but Tailscale is off on this phone.",
            action = "Open Tailscale", onAction = { openTailscale(context) }
        )
    } else {
        val seen = lastSeen?.takeIf { it > 0 }?.let {
            "Last seen " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        }
        RemoteCard(Icons.Rounded.CloudOff, "Can't reach $name. It may be asleep.", seen)
    }
}

fun openTailscale(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
    val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE"))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

/** A VPN (Tailscale on Android is one) is up on the phone. */
fun phoneHasVpn(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
}

// MARK: - status row and key strip

/** "23 ms · 60 fps" · route chip · Lock. */
@Composable
fun RemoteStatusRow(ui: RemoteStream.Ui, live: Boolean, onLock: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(visible = live, enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val readout = buildString {
                    if (ui.rttMs > 0) append("${ui.rttMs} ms")
                    if (ui.fps > 0) { if (isNotEmpty()) append(" · "); append("${ui.fps} fps") }
                }
                if (readout.isNotEmpty()) {
                    Text(readout, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                }
                val route = when {
                    ui.route != ScreenStreamClient.ROUTE_TAILSCALE -> "Home Wi-Fi"
                    ui.relayed -> "Tailscale · relayed"
                    else -> "Tailscale"
                }
                Text(
                    route,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        FilledTonalIconButton(onClick = onLock) { Icon(Icons.Rounded.Lock, contentDescription = "Lock") }
    }
}

/** Swaps between the states of the picture area with a short fade. */
@Composable
fun <T> RemoteFade(state: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(targetState = state, transitionSpec = { fadeIn() togetherWith fadeOut() }, modifier = modifier, label = "remoteArea") {
        content(it)
    }
}
