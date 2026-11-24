package com.sameerasw.airsync.multidesk

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.FlickBundleLayout
import kotlin.math.max
import kotlin.math.min

/**
 * H.I.B — the one notification for a bundle a computer flicked to the phone (SPACE.md §14): a quiet progress
 * line while the files come in, then a heads-up "5 photos from <name>" with a preview strip of them. Tapping
 * it (or "View all") opens [FlickBundleActivity]; "Share all" shares every file at once.
 */
object BundleNotifications {
    private const val TAG = "BundleNotifications"
    private const val PROGRESS_CHANNEL = "hib_flick_bundle_progress"
    private const val THUMB_PX = 320
    private const val STRIP_W = 1080
    private const val STRIP_H = 480

    private val lastProgress = HashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())

    fun progressId(bundleId: String) = ("bundle-progress$bundleId").hashCode()
    fun doneId(bundleId: String) = ("bundle$bundleId").hashCode()

    private fun ensureProgressChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(PROGRESS_CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, "Flicks arriving", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while a computer flicks several files to this phone"
                setShowBadge(false)
            }
        )
    }

    /** "Receiving 5 photos from <name> · 2 of 5", at most every 600 ms. */
    @SuppressLint("MissingPermission")
    fun progress(context: Context, snap: BundleCollector.Snapshot) {
        val now = System.currentTimeMillis()
        synchronized(lastProgress) {
            val last = lastProgress[snap.bundleId] ?: 0L
            if (now - last < 600 && snap.finishedCount == 0) return
            lastProgress[snap.bundleId] = now
        }
        ensureProgressChannel(context)
        val pct = (snap.fraction * 100).toInt().coerceIn(0, 100)
        val builder = NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Receiving ${snap.title}")
            .setContentText("${snap.finishedCount} of ${snap.expected} · $pct%")
            .setProgress(100, pct, snap.totalBytes <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        notify(context, progressId(snap.bundleId), builder)
    }

    /** Every file is in (or what could be): the heads-up with the strip. [thumbs] are in [snap] order. */
    @SuppressLint("MissingPermission")
    fun done(context: Context, snap: BundleCollector.Snapshot, thumbs: List<Bitmap?>) {
        synchronized(lastProgress) { lastProgress.remove(snap.bundleId) }
        runCatching { NotificationManagerCompat.from(context).cancel(progressId(snap.bundleId)) }
        val arrived = snap.items.withIndex().filter { it.value.ok == true }
        if (arrived.isEmpty()) {
            toast(context, "Couldn't receive ${snap.title}")
            return
        }
        val arrivedThumbs = arrived.map { thumbs.getOrNull(it.index) }
        val kinds = arrived.map { it.value.kind }
        val strip = runCatching { renderStrip(arrivedThumbs, kinds) }.getOrNull()
        val id = doneId(snap.bundleId)
        val view = FlickBundleActivity.pending(context, id, snap, share = false)
        val share = FlickBundleActivity.pending(context, id, snap, share = true)
        val builder = NotificationCompat.Builder(context, FlickNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(snap.title)
            .setContentText(snap.detail)
            .setSubText("Download/${PhoneFileReceiver.FOLDER}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setNumber(arrived.size)
            .setContentIntent(view)
            .addAction(0, "View all", view)
            .addAction(0, "Share all", share)
        arrivedThumbs.firstOrNull { it != null }?.let { builder.setLargeIcon(it) }
        if (strip != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(strip)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(snap.detail)
            )
        }
        // The notification may not exist yet when the bundle is tiny; the toast is for when H.I.B is open.
        FlickNotifications.ensure(context)
        notify(context, id, builder)
        toast(context, "${snap.title} · in Download/${PhoneFileReceiver.FOLDER}")
    }

    /** A small copy of a preview for the strip (the full one can be 720 px). */
    fun shrink(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= THUMB_PX) return source
        val s = THUMB_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source, (source.width * s).toInt().coerceAtLeast(1), (source.height * s).toInt().coerceAtLeast(1), true
        )
    }

    /**
     * The preview strip: up to five rounded tiles side by side (the bundle's first items), a play mark on
     * videos and "+N" on the last tile when more came than fit.
     */
    fun renderStrip(thumbs: List<Bitmap?>, kinds: List<String>): Bitmap? {
        val count = thumbs.size
        if (count == 0) return null
        val out = Bitmap.createBitmap(STRIP_W, STRIP_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val tiles = FlickBundleLayout.strip(count, STRIP_W.toFloat(), STRIP_H.toFloat(), pad = 0f, gap = 12f)
        val extra = FlickBundleLayout.stripOverflow(count)
        val radius = 28f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        tiles.forEachIndexed { i, box ->
            val rect = RectF(box.left, box.top, box.right, box.bottom)
            val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            val bmp = thumbs.getOrNull(i)
            if (bmp != null) {
                canvas.drawBitmap(bmp, centerCrop(bmp, rect), rect, paint)
            } else {
                paint.color = 0xFF1A2147.toInt()
                canvas.drawRect(rect, paint)
            }
            if (kinds.getOrNull(i) == "video" && !(extra > 0 && i == tiles.lastIndex)) drawPlay(canvas, rect)
            if (extra > 0 && i == tiles.lastIndex) {
                paint.color = Color.argb(150, 9, 12, 33)
                canvas.drawRect(rect, paint)
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = min(rect.width(), rect.height()) * 0.36f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val y = rect.centerY() - (text.descent() + text.ascent()) / 2
                canvas.drawText("+$extra", rect.centerX(), y, text)
            }
            canvas.restore()
            // A hairline so tiles read as cards on any shade.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(60, 255, 255, 255)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
        return out
    }

    private fun centerCrop(bmp: Bitmap, dst: RectF): Rect {
        val target = dst.width() / dst.height()
        val source = bmp.width.toFloat() / bmp.height
        return if (source > target) {
            val w = (bmp.height * target).toInt()
            val l = (bmp.width - w) / 2
            Rect(l, 0, l + w, bmp.height)
        } else {
            val h = (bmp.width / target).toInt()
            val t = (bmp.height - h) / 2
            Rect(0, t, bmp.width, t + h)
        }
    }

    private fun drawPlay(canvas: Canvas, rect: RectF) {
        val r = min(rect.width(), rect.height()) * 0.16f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        canvas.drawCircle(rect.centerX(), rect.centerY(), r, p)
        p.color = Color.WHITE
        val tri = Path().apply {
            moveTo(rect.centerX() - r * 0.35f, rect.centerY() - r * 0.5f)
            lineTo(rect.centerX() + r * 0.55f, rect.centerY())
            lineTo(rect.centerX() - r * 0.35f, rect.centerY() + r * 0.5f)
            close()
        }
        canvas.drawPath(tri, p)
    }

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Notifications not allowed: ${e.message}")
        }
    }

    private fun toast(context: Context, text: String) {
        if (!com.sameerasw.airsync.AirSyncApp.isAppForeground()) return
        main.post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
}
