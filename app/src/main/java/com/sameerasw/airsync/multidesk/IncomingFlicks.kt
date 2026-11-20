package com.sameerasw.airsync.multidesk

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.ClipboardSyncManager
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * H.I.B Space — Flicks from a computer to the phone (SPACE.md §3.4).
 *
 * A computer sends `flickStart` first. Text is copied ("Copied from <from>"), a link gets a notification
 * with Open, and for files the metadata (who sent it, thumbnail) waits here until [PhoneFileReceiver]
 * finishes the transfer tagged with the same `flickId`; then the phone posts a heads-up
 * "Flick from <from>" with a preview, Open and Share.
 */
object IncomingFlicks {
    private const val TAG = "IncomingFlicks"
    private const val KEEP_MS = 30 * 60 * 1000L

    data class Meta(
        val id: String,
        val kind: String,
        val name: String,
        val mime: String,
        val size: Long,
        val from: String,
        val fromPlatform: String,
        val thumbnail: String?,
        val at: Long
    )

    private val metas = ConcurrentHashMap<String, Meta>()

    fun onFlickStart(context: Context, link: DesktopLink, data: JSONObject) {
        val id = data.optString("id").ifBlank { UUID.randomUUID().toString() }
        val kind = data.optString("kind").ifBlank { "file" }
        val from = data.optString("from").ifBlank { link.name }
        when (kind) {
            "text" -> {
                val text = data.optString("text")
                if (text.isEmpty()) return
                ClipboardSyncManager.handleClipboardUpdate(context, text)
                FlickNotifications.showText(context, from, text)
            }
            "link" -> {
                val url = data.optString("text").ifBlank { data.optString("url") }.trim()
                if (url.isEmpty()) return
                FlickNotifications.showLink(context, from, url)
            }
            else -> {
                prune()
                metas[id] = Meta(
                    id = id,
                    kind = kind,
                    name = data.optString("name"),
                    mime = data.optString("mime"),
                    size = data.optLong("size", 0L),
                    from = from,
                    fromPlatform = data.optString("fromPlatform").ifBlank { link.platform },
                    thumbnail = data.optString("thumbnail").ifBlank { null },
                    at = System.currentTimeMillis()
                )
                Log.d(TAG, "Flick ($kind) coming from $from")
            }
        }
    }

    fun meta(id: String?): Meta? = id?.let { metas[it] }

    fun forget(id: String?) {
        if (id != null) metas.remove(id)
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        metas.values.filter { now - it.at > KEEP_MS }.forEach { metas.remove(it.id) }
    }

    /** A small preview for the notification: the image itself, a video frame, or the sender's thumbnail. */
    fun preview(file: File, mime: String, thumbnail: String?): Bitmap? {
        val fromFile = try {
            when {
                mime.startsWith("image/") -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val longest = max(info.size.width, info.size.height).coerceAtLeast(1)
                    if (longest > PREVIEW_PX) {
                        val s = PREVIEW_PX.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * s).toInt().coerceAtLeast(1),
                            (info.size.height * s).toInt().coerceAtLeast(1)
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                mime.startsWith("video/") -> {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(file.absolutePath)
                        r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PREVIEW_PX, PREVIEW_PX)
                    } finally {
                        runCatching { r.release() }
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (fromFile != null) return fromFile
        if (thumbnail.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(thumbnail, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /**
     * A URI other apps can open for a file published to Download/H.I.B: a FileProvider URI on the real
     * file when the phone can see it (the name may have been changed on a clash), else the MediaStore URI.
     */
    fun shareableUri(context: Context, mediaUri: Uri): Uri {
        return try {
            var display: String? = null
            var relative: String? = null
            context.contentResolver.query(
                mediaUri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    display = c.getString(0)
                    relative = c.getString(1)
                }
            }
            val name = display ?: return mediaUri
            val rel = (relative ?: "${Environment.DIRECTORY_DOWNLOADS}/${PhoneFileReceiver.FOLDER}/").trimEnd('/')
            @Suppress("DEPRECATION")
            val file = File(File(Environment.getExternalStorageDirectory(), rel), name)
            if (!file.exists()) return mediaUri
            FileProvider.getUriForFile(context, "${context.packageName}.clipboard-files", file)
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to the MediaStore URI: ${e.javaClass.simpleName}")
            mediaUri
        }
    }

    private const val PREVIEW_PX = 720
}

/** Notifications for things computers flick to the phone. */
object FlickNotifications {
    const val CHANNEL_ID = "hib_flick_incoming"

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Flicks from your computers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Files, photos, links and text flicked to this phone from a computer"
                setShowBadge(true)
            }
        )
    }

    /**
     * The heads-up for a file that arrived: [title] is "Flick from <from>" (or similar), the body is the
     * file name, images get a big preview, and there are Open and Share actions. Tapping opens the file.
     */
    @SuppressLint("MissingPermission")
    fun showFile(context: Context, notifId: Int, title: String, name: String, mime: String, uri: Uri, picture: Bitmap?) {
        ensureChannel(context)
        val open = ReceivedFileActivity.pending(context, notifId, uri, mime, name, share = false)
        val share = ReceivedFileActivity.pending(context, notifId, uri, mime, name, share = true)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(name)
            .setSubText("Download/${PhoneFileReceiver.FOLDER}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Open", open)
            .addAction(0, "Share", share)
        if (picture != null) {
            builder.setLargeIcon(picture)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(name)
            )
        }
        notify(context, notifId, builder)
    }

    @SuppressLint("MissingPermission")
    fun showText(context: Context, from: String, text: String) {
        ensureChannel(context)
        val id = ("txt" + text.hashCode() + System.currentTimeMillis()).hashCode()
        val preview = text.take(600)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Copied from $from")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
        notify(context, id, builder)
    }

    @SuppressLint("MissingPermission")
    fun showLink(context: Context, from: String, url: String) {
        ensureChannel(context)
        val id = ("lnk$url").hashCode()
        val full = if (url.contains("://")) url else "https://$url"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(full)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(
            context, id, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Flick from $from")
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Open", open)
        notify(context, id, builder)
    }

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            Log.w("FlickNotifications", "Notifications not allowed: ${e.message}")
        }
    }
}

/**
 * Opens or shares a received file from its notification, granting the other app read access, and
 * falls back to a chooser (or a toast) when no app handles the type.
 */
class ReceivedFileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val mime = intent.type ?: "*/*"
        val name = intent.getStringExtra(EXTRA_NAME) ?: "file"
        val notifId = intent.getIntExtra(EXTRA_NOTIF, 0)
        if (notifId != 0) runCatching { NotificationManagerCompat.from(this).cancel(notifId) }
        if (uri == null) {
            finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_SHARE, false)) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(send, "Share $name").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        } else {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(view)
            } catch (_: ActivityNotFoundException) {
                try {
                    val any = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(any, "Open $name").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {
                    Toast.makeText(this, "No app on this phone opens $name", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open $name", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        private const val EXTRA_SHARE = "hib_share"
        private const val EXTRA_NAME = "hib_name"
        private const val EXTRA_NOTIF = "hib_notif"

        fun pending(context: Context, notifId: Int, uri: Uri, mime: String, name: String, share: Boolean): PendingIntent {
            val i = Intent(context, ReceivedFileActivity::class.java).apply {
                setDataAndType(uri, mime.ifBlank { "*/*" })
                putExtra(EXTRA_SHARE, share)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_NOTIF, notifId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(
                context, notifId * 2 + if (share) 1 else 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
