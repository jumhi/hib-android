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
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.FlickSender
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
 * H.I.B — "Flick to Mac". A translucent overlay that shows the shared content as a card; flick it up off
 * the top of the screen and it flies onto the Mac's desktop. Reachable from the system share sheet
 * (photos, videos, files, links, text) and from the Flick card in H.I.B (photo picker).
 */
class FlickActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))

        val items = FlickMedia.parseIntent(this, intent)
        if (items.isEmpty()) {
            Toast.makeText(this, "Nothing to flick", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            AirSyncTheme(darkTheme = true) {
                FlickOverlay(initial = items, onFinish = { finish() })
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

private class FlickHaptics(context: Context) {
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

private val FlickCyan = Color(0xFF20D9FF)
private const val LAUNCH_VELOCITY = 1200f   // px/s upward
private const val LAUNCH_DISTANCE = 0.35f   // of screen height

@Composable
private fun FlickOverlay(initial: List<FlickItem>, onFinish: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = remember { FlickHaptics(context) }

    val items = remember { mutableStateListOf<FlickItem>().apply { addAll(initial) } }
    val previews = remember { mutableStateMapOf<String, FlickPreview>() }
    var macName by remember { mutableStateOf("your Mac") }
    var connected by remember { mutableStateOf(FlickSender.canSend()) }

    val dim = remember { Animatable(0f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val grab = remember { Animatable(1f) }
    val enter = remember { Animatable(0f) }
    var liveVy by remember { mutableFloatStateOf(0f) }
    var launching by remember { mutableStateOf(false) }
    var showSent by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val pending = remember { mutableListOf<Deferred<Boolean>>() }

    LaunchedEffect(Unit) {
        dim.animateTo(1f, tween(220))
    }
    LaunchedEffect(Unit) {
        macName = withContext(Dispatchers.IO) {
            try {
                DataStoreManager.getInstance(context).getLastConnectedDevice().first()?.name
            } catch (_: Exception) {
                null
            }
        }?.takeIf { it.isNotBlank() } ?: "your Mac"
    }
    LaunchedEffect(Unit) {
        while (true) {
            connected = FlickSender.canSend()
            delay(1000)
        }
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

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            dim.animateTo(0f, tween(180))
            onFinish()
        }
    }

    BackHandler { close() }

    fun fire(item: FlickItem, exitXRatio: Float, velocity: Float, angle: Float) {
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
                text = item.text
            )
            if (item.uri != null) {
                FlickSender.startFileTransfer(context, item.id, item.uri, item.name, item.mime)
            } else {
                started
            }
        }
    }

    fun launchCard(v: Offset) {
        val item = items.firstOrNull() ?: return
        launching = true
        scope.launch {
            val w = rootSize.width.toFloat().coerceAtLeast(1f)
            val h = rootSize.height.toFloat().coerceAtLeast(1f)
            val cardH = w * 0.68f * 1.3f
            val start = offset.value
            val mag = hypot(v.x, v.y)
            val speed = max(mag, 2400f)
            val dir = if (v.y < -200f && mag > 0f) Offset(v.x / mag, v.y / mag) else Offset(0f, -1f)
            val endY = -(h / 2f + cardH)
            val seconds = (start.y - endY) / (speed * -dir.y)
            val target = Offset(start.x + dir.x * speed * seconds, endY)
            val durationMs = (seconds * 1000f).toInt().coerceIn(90, 420)
            val angle = Math.toDegrees(atan2(dir.x, -dir.y).toDouble()).toFloat()
            var fired = false
            offset.animateTo(target, tween(durationMs, easing = LinearEasing)) {
                // The card's centre crossing the top edge is the moment it "leaves" the phone.
                if (!fired && h / 2f + value.y <= 0f) {
                    fired = true
                    fire(item, ((w / 2f + value.x) / w).coerceIn(0f, 1f), max(mag, LAUNCH_VELOCITY), angle)
                }
            }
            if (!fired) fire(item, ((w / 2f + offset.value.x) / w).coerceIn(0f, 1f), max(mag, LAUNCH_VELOCITY), angle)

            items.removeAt(0)
            offset.snapTo(Offset.Zero)
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
        scope.launch { grab.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) }
        liveVy = 0f
        if (launching || items.isEmpty()) return
        val h = rootSize.height.toFloat()
        val wantsLaunch = v.y < -LAUNCH_VELOCITY || (offset.value.y < -LAUNCH_DISTANCE * h && v.y < 0f)
        if (wantsLaunch && connected) {
            launchCard(v)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .background(Color.Black.copy(alpha = 0.6f * dim.value))
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        if (!launching && items.isNotEmpty()) {
                            scope.launch { grab.animateTo(1.05f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) }
                        }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity()
                        release(Offset(v.x, v.y))
                    },
                    onDragCancel = { release(Offset.Zero) },
                    onDrag = { change, amount ->
                        if (!launching && items.isNotEmpty()) {
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            scope.launch { offset.snapTo(offset.value + amount) }
                            liveVy = tracker.calculateVelocity().y
                        }
                    }
                )
            }
    ) {
        val cardWidth = with(density) { min((rootSize.width * 0.68f).toDp(), 320.dp) }
        val cardHeight = cardWidth * 1.3f

        // Hint
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 28.dp)
                .graphicsLayer { alpha = dim.value * (1f - glow * 0.4f) },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = null,
                tint = if (connected) FlickCyan else Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { translationY = -glow * 10.dp.toPx() }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (connected) "Flick up to send to $macName" else "Connect H.I.B to your Mac to flick",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (items.size > 1) {
                Text(
                    text = "${items.size} items — one flick each",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall
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
                    .size(cardWidth, cardHeight)
                    .graphicsLayer {
                        val s = 1f - 0.06f * depth
                        if (isTop) {
                            val o = offset.value
                            translationX = o.x
                            translationY = o.y + depth * 18.dp.toPx()
                            rotationZ = (o.x / size.width) * 14f
                            scaleX = s * grab.value
                            scaleY = s * grab.value
                            alpha = dim.value
                        } else {
                            translationY = depth * 18.dp.toPx()
                            scaleX = s
                            scaleY = s
                            alpha = dim.value * (1f - 0.25f * depth)
                        }
                    }
            ) {
                FlickCardFace(item, previews[item.id]?.image, if (isTop) glow else 0f)
            }
        }

        // Bottom: cancel
        TextButton(
            onClick = { close() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
                .graphicsLayer { alpha = dim.value }
        ) {
            Text("Cancel", color = Color.White.copy(alpha = 0.8f))
        }

        // "Sent ✓"
        AnimatedVisibility(
            visible = showSent,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 24.dp)
        ) {
            Surface(shape = CircleShape, color = Color(0xFF101530), shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFFBAFF38))
                    Spacer(Modifier.width(8.dp))
                    Text("Sent to $macName", color = Color.White, fontWeight = FontWeight.SemiBold)
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
