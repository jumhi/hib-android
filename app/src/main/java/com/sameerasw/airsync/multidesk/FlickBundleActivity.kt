package com.sameerasw.airsync.multidesk

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.sameerasw.airsync.presentation.ui.activities.FlickMedia
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.FlickBundleLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * H.I.B — a bundle a computer flicked to the phone (SPACE.md §14). The files land as a stack that fans out
 * into the same pattern the computers use (5 = two, one, two…); tap one to open it, long-press to share it,
 * or Share all / Save all (photos and videos into the gallery, Pictures/H.I.B and Movies/H.I.B).
 */
class FlickBundleActivity : ComponentActivity() {

    data class Item(val uri: Uri, val mime: String, val name: String, val kind: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val mimes = intent.getStringArrayListExtra(EXTRA_MIMES).orEmpty()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        val kinds = intent.getStringArrayListExtra(EXTRA_KINDS).orEmpty()
        val items = uris.mapIndexed { i, u ->
            Item(Uri.parse(u), mimes.getOrNull(i) ?: "*/*", names.getOrNull(i) ?: "file", kinds.getOrNull(i) ?: "file")
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Flicked to this phone"
        val notif = intent.getIntExtra(EXTRA_NOTIF, 0)
        if (notif != 0) runCatching { NotificationManagerCompat.from(this).cancel(notif) }
        if (items.isEmpty()) {
            finish()
            return
        }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_SHARE, false)) shareAll(this, items)

        setContent {
            AirSyncTheme(darkTheme = true) {
                BundleViewer(title = title, items = items, onBack = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_URIS = "hib_bundle_uris"
        private const val EXTRA_MIMES = "hib_bundle_mimes"
        private const val EXTRA_NAMES = "hib_bundle_names"
        private const val EXTRA_KINDS = "hib_bundle_kinds"
        private const val EXTRA_TITLE = "hib_bundle_title"
        private const val EXTRA_SHARE = "hib_bundle_share"
        private const val EXTRA_NOTIF = "hib_bundle_notif"

        fun intent(context: Context, title: String, items: List<BundleCollector.Item>, notifId: Int, share: Boolean): Intent {
            val ok = items.filter { it.ok == true && it.uri != null }
            return Intent(context, FlickBundleActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_URIS, ArrayList(ok.map { it.uri!! }))
                putStringArrayListExtra(EXTRA_MIMES, ArrayList(ok.map { it.mime.ifBlank { "*/*" } }))
                putStringArrayListExtra(EXTRA_NAMES, ArrayList(ok.map { it.name.ifBlank { "file" } }))
                putStringArrayListExtra(EXTRA_KINDS, ArrayList(ok.map { it.kind }))
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SHARE, share)
                putExtra(EXTRA_NOTIF, notifId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun pending(context: Context, notifId: Int, snap: BundleCollector.Snapshot, share: Boolean): PendingIntent =
            PendingIntent.getActivity(
                context, notifId * 2 + if (share) 1 else 0,
                intent(context, snap.title, snap.items, notifId, share),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun shareAll(context: Context, items: List<Item>) {
            if (items.isEmpty()) return
            val type = when {
                items.all { it.kind == "image" } -> "image/*"
                items.all { it.kind == "video" } -> "video/*"
                else -> "*/*"
            }
            val clip = ClipData.newRawUri(items.first().name, items.first().uri).apply {
                items.drop(1).forEach { addItem(ClipData.Item(it.uri)) }
            }
            val send = if (items.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, items.first().uri)
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
            }.apply {
                this.type = if (items.size == 1) items.first().mime else type
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(send, "Share ${items.size}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }

        fun open(context: Context, item: Item) {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, item.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(view)
            } catch (_: ActivityNotFoundException) {
                try {
                    val any = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(item.uri, "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(any, "Open ${item.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {
                    Toast.makeText(context, "No app on this phone opens ${item.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't open ${item.name}", Toast.LENGTH_SHORT).show()
            }
        }

        /** Copies the photos and videos into the gallery (Pictures/H.I.B, Movies/H.I.B); returns how many. */
        fun saveToGallery(context: Context, items: List<Item>): Int {
            val resolver = context.contentResolver
            var saved = 0
            for (item in items) {
                val (collection, dir) = when (item.kind) {
                    "image" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/${PhoneFileReceiver.FOLDER}"
                    "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/${PhoneFileReceiver.FOLDER}"
                    else -> continue
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val target = runCatching { resolver.insert(collection, values) }.getOrNull() ?: continue
                val ok = runCatching {
                    resolver.openInputStream(item.uri)?.use { input ->
                        resolver.openOutputStream(target)?.use { out -> input.copyTo(out, 256 * 1024) }
                    } != null
                }.getOrDefault(false)
                if (ok) {
                    resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    saved++
                } else {
                    runCatching { resolver.delete(target, null, null) }
                }
            }
            return saved
        }
    }
}

@Composable
private fun BundleViewer(title: String, items: List<FlickBundleActivity.Item>, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumbs = remember { mutableStateMapOf<Int, ImageBitmap>() }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(items) {
        items.forEachIndexed { i, item ->
            launch(Dispatchers.IO) {
                FlickMedia.previewFor(context, item.uri, item.kind)?.let { thumbs[i] = it.asImageBitmap() }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(HibInk, Color(0xFF0E1330), HibInk)))
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title, color = Color.White, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Download/${PhoneFileReceiver.FOLDER} · tap one to open it, hold to share it",
                        color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (items.size <= FlickBundleLayout.MAX_SLOTS) {
                    FannedCards(items, thumbs)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(items) { i, item ->
                            BundleCard(item, thumbs[i], Modifier.fillMaxWidth().aspectRatio(0.8f))
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = { FlickBundleActivity.shareAll(context, items) },
                    colors = ButtonDefaults.buttonColors(containerColor = HibCyan, contentColor = HibInk),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share all", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { FlickBundleActivity.saveToGallery(context, items) }
                            saving = false
                            saved = n > 0
                            val media = items.count { it.kind == "image" || it.kind == "video" }
                            val text = when {
                                media == 0 -> "These files are already in Download/${PhoneFileReceiver.FOLDER}"
                                n == media -> "Saved $n to the gallery (Pictures/${PhoneFileReceiver.FOLDER})"
                                n > 0 -> "Saved $n of $media to the gallery"
                                else -> "Couldn't save to the gallery"
                            }
                            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    when {
                        saving -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        saved -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = HibLime, modifier = Modifier.size(18.dp))
                        else -> Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (saved) "Saved" else "Save all", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The bundle as a stack that springs open into its pattern, one card after another. */
@Composable
private fun FannedCards(items: List<FlickBundleActivity.Item>, thumbs: Map<Int, ImageBitmap>) {
    val density = LocalDensity.current
    val opens = remember(items.size) { List(items.size) { Animatable(0f) } }
    LaunchedEffect(items.size) {
        delay(180)
        opens.forEachIndexed { i, a ->
            launch {
                delay(i * 55L)
                a.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessLow))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val gap = 12.dp
        val aspect = 1.25f
        val cellW: Dp = with(density) {
            FlickBundleLayout.fitCell(
                items.size, maxWidth.toPx(), maxHeight.toPx(), gap.toPx(), aspect, 220.dp.toPx()
            ).toDp()
        }
        val cellH = cellW * aspect
        val slots = with(density) { FlickBundleLayout.fan(items.size, cellW.toPx(), cellH.toPx(), gap.toPx()) }
        for (i in items.indices.reversed()) {
            val pose = FlickBundleLayout.stackPose(i.coerceAtMost(FlickBundleLayout.PEEK))
            val slot = slots.getOrNull(i) ?: continue
            BundleCard(
                items[i], thumbs[i],
                Modifier
                    .size(cellW, cellH)
                    .graphicsLayer {
                        val p = opens[i].value
                        translationX = slot.x * p + pose.dx.dp.toPx() * (1 - p)
                        translationY = slot.y * p + pose.dy.dp.toPx() * (1 - p)
                        rotationZ = pose.rotation * (1 - p)
                        val s = pose.scale + (1f - pose.scale) * p
                        scaleX = s
                        scaleY = s
                    }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BundleCard(item: FlickBundleActivity.Item, thumb: ImageBitmap?, modifier: Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .shadow(14.dp, shape, ambientColor = HibCyan.copy(alpha = 0.25f), spotColor = HibCyan.copy(alpha = 0.25f))
            .clip(shape)
            .background(HibPanel)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .combinedClickable(
                onClick = { FlickBundleActivity.open(context, item) },
                onLongClick = { FlickBundleActivity.shareAll(context, listOf(item)) }
            )
    ) {
        if (thumb != null) {
            Image(thumb, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Rounded.InsertDriveFile, contentDescription = null, tint = HibCyan,
                modifier = Modifier.align(Alignment.Center).size(36.dp)
            )
        }
        if (item.kind == "video") {
            Icon(
                Icons.Rounded.PlayCircle, contentDescription = null, tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.Center).size(44.dp)
            )
        }
        if (thumb == null || item.kind != "image") {
            Text(
                item.name, color = Color.White, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}
