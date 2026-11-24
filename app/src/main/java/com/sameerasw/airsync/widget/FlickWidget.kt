package com.sameerasw.airsync.widget

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.RemoteViews
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.activities.FlickActivity
import com.sameerasw.airsync.presentation.ui.activities.FlickMediaAccessActivity
import com.sameerasw.airsync.utils.RecentMedia
import java.util.concurrent.Executors

/**
 * H.I.B — the Flick widget: the latest photos, videos and screenshots as thumbnails. Tap one to flick it (Flick
 * opens with it, pointed where the phone points); Select turns taps into picks and the big button flicks them
 * all as one bundle (SPACE.md §14). "+" opens the photo picker. Resizable: 1–3 rows of 3–4 (up to 9).
 *
 * The first tap without photo access opens [FlickMediaAccessActivity], which asks nicely. The widget follows
 * new photos through a content-triggered job ([FlickWidgetMediaJob]).
 */
class FlickWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        FlickWidget.renderAsync(context, appWidgetIds) { pending.finish() }
        FlickWidgetMediaJob.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        val pending = goAsync()
        FlickWidget.renderAsync(context, intArrayOf(appWidgetId)) { pending.finish() }
    }

    override fun onEnabled(context: Context) {
        FlickWidgetMediaJob.schedule(context)
    }

    override fun onDisabled(context: Context) {
        FlickWidgetMediaJob.cancel(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { FlickWidget.forget(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            FlickWidget.ACTION_SELECT -> {
                FlickWidget.toggleMode(context, id)
                val pending = goAsync()
                FlickWidget.renderAsync(context, intArrayOf(id)) { pending.finish() }
            }
            FlickWidget.ACTION_TOGGLE -> {
                intent.getStringExtra(FlickWidget.EXTRA_MEDIA)?.let { FlickWidget.toggle(context, id, it) }
                val pending = goAsync()
                FlickWidget.renderAsync(context, intArrayOf(id)) { pending.finish() }
            }
            FlickWidget.ACTION_REFRESH -> {
                val pending = goAsync()
                FlickWidget.renderAsync(context, FlickWidget.ids(context)) { pending.finish() }
            }
            else -> super.onReceive(context, intent)
        }
    }
}

object FlickWidget {
    private const val TAG = "FlickWidget"
    const val ACTION_SELECT = "com.sameerasw.airsync.widget.FLICK_SELECT"
    const val ACTION_TOGGLE = "com.sameerasw.airsync.widget.FLICK_TOGGLE"
    const val ACTION_REFRESH = "com.sameerasw.airsync.widget.FLICK_REFRESH"
    const val EXTRA_MEDIA = "com.sameerasw.airsync.widget.EXTRA_MEDIA"
    private const val PREFS = "hib_flick_widget"
    private const val SLOTS = 12
    private const val THUMB_PX = 240

    private val worker = Executors.newSingleThreadExecutor()

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, FlickWidgetProvider::class.java))

    /** Re-renders every Flick widget (new photos, access granted, a flick sent). */
    fun updateAll(context: Context) {
        val ids = ids(context)
        if (ids.isNotEmpty()) renderAsync(context, ids) {}
    }

    fun renderAsync(context: Context, ids: IntArray, done: () -> Unit) {
        val app = context.applicationContext
        worker.execute {
            try {
                val manager = AppWidgetManager.getInstance(app)
                ids.forEach { id ->
                    runCatching { manager.updateAppWidget(id, render(app, manager, id)) }
                        .onFailure { Log.w(TAG, "Widget $id: ${it.javaClass.simpleName}: ${it.message}") }
                }
            } finally {
                done()
            }
        }
    }

    // MARK: - selection (per widget)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selecting(context: Context, id: Int) = prefs(context).getBoolean("mode_$id", false)
    fun selection(context: Context, id: Int): Set<String> = prefs(context).getStringSet("sel_$id", emptySet()).orEmpty()

    fun toggleMode(context: Context, id: Int) {
        val on = !selecting(context, id)
        val edit = prefs(context).edit().putBoolean("mode_$id", on)
        if (!on) edit.remove("sel_$id")
        edit.apply()
    }

    fun toggle(context: Context, id: Int, uri: String) {
        val sel = selection(context, id).toMutableSet()
        if (!sel.remove(uri)) sel += uri
        prefs(context).edit().putStringSet("sel_$id", sel).apply()
    }

    /** Flick opened with this widget's picks: back to plain taps. */
    fun clearSelection(context: Context, id: Int) {
        prefs(context).edit().remove("sel_$id").putBoolean("mode_$id", false).apply()
        renderAsync(context, intArrayOf(id)) {}
    }

    fun forget(context: Context, id: Int) {
        prefs(context).edit().remove("sel_$id").remove("mode_$id").apply()
    }

    // MARK: - layout

    private fun size(manager: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = manager.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 280
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 240
        return w to h
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_flick)
        val (w, h) = size(manager, id)
        val (rows, cols) = FlickWidgetGrid.of(w, h)
        val shown = rows * cols
        val canRead = RecentMedia.canRead(context)
        val media = if (canRead) RecentMedia.latest(context, shown) else emptyList()
        val selecting = canRead && media.isNotEmpty() && selecting(context, id)
        val visibleUris = media.map { it.uri }.toSet()
        val picked = selection(context, id).filter { it in visibleUris || selecting }
        val pickedVisible = media.filter { it.uri in picked }

        // Rows and columns in use.
        for (r in 0 until 3) views.setViewVisibility(rowId(r), if (r < rows) View.VISIBLE else View.GONE)
        for (n in 0 until SLOTS) {
            val r = n / 4
            val c = n % 4
            views.setViewVisibility(slotId(n), if (r < rows && c < cols) View.VISIBLE else View.GONE)
        }

        // Header.
        views.setTextViewText(
            R.id.flick_widget_hint,
            when {
                !canRead -> ""
                media.isEmpty() -> "No photos yet"
                selecting && pickedVisible.isEmpty() -> "Tap photos to pick"
                selecting -> "${pickedVisible.size} picked"
                else -> "Tap one to flick it"
            }
        )
        views.setViewVisibility(R.id.flick_widget_select, if (canRead && media.isNotEmpty()) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.flick_widget_select, if (selecting) "Cancel" else "Select")
        views.setInt(
            R.id.flick_widget_select, "setBackgroundResource",
            if (selecting) R.drawable.widget_flick_pill_on else R.drawable.widget_flick_pill
        )
        views.setTextColor(
            R.id.flick_widget_select,
            context.getColor(if (selecting) R.color.hib_flick_ink else R.color.hib_flick_text)
        )
        views.setOnClickPendingIntent(R.id.flick_widget_select, broadcast(context, id, ACTION_SELECT, 1))
        views.setOnClickPendingIntent(R.id.flick_widget_more, activity(context, id, 2, FlickActivity.pickIntent(context)))

        // Empty state / access.
        val showEmpty = !canRead || media.isEmpty()
        views.setViewVisibility(R.id.flick_widget_empty, if (showEmpty) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.flick_widget_grid, if (showEmpty) View.INVISIBLE else View.VISIBLE)
        if (!canRead) {
            views.setTextViewText(R.id.flick_widget_empty, "Show your latest photos and screenshots here.\nTap to allow.")
            views.setOnClickPendingIntent(R.id.flick_widget_empty, activity(context, id, 3, FlickMediaAccessActivity.intent(context)))
        } else if (media.isEmpty()) {
            views.setTextViewText(R.id.flick_widget_empty, "No photos yet. Take one, or tap + to pick any file.")
            views.setOnClickPendingIntent(R.id.flick_widget_empty, activity(context, id, 3, FlickActivity.pickIntent(context)))
        }

        // Thumbnails.
        val px = THUMB_PX
        for (n in 0 until SLOTS) {
            val r = n / 4
            val c = n % 4
            val index = r * cols + c
            val m = if (r < rows && c < cols) media.getOrNull(index) else null
            if (m == null) {
                views.setImageViewBitmap(slotImage(n), null)
                listOf(slotPlay(n), slotCheck(n), slotRing(n), slotDim(n), slotTag(n)).forEach { views.setViewVisibility(it, View.GONE) }
                views.setOnClickPendingIntent(slotId(n), activity(context, id, 10 + n, FlickActivity.pickIntent(context)))
                continue
            }
            val thumb = thumbnail(context, m, px)
            if (thumb != null) views.setImageViewBitmap(slotImage(n), thumb) else views.setImageViewResource(slotImage(n), R.drawable.ic_flick_24)
            views.setContentDescription(slotImage(n), (if (m.kind == "video") "Video " else "Photo ") + m.name)
            views.setViewVisibility(slotPlay(n), if (m.kind == "video") View.VISIBLE else View.GONE)
            views.setViewVisibility(slotTag(n), if (m.isScreenshot && !selecting) View.VISIBLE else View.GONE)
            val on = m.uri in picked
            views.setViewVisibility(slotCheck(n), if (selecting) View.VISIBLE else View.GONE)
            views.setImageViewResource(slotCheck(n), if (on) R.drawable.widget_flick_check_on else R.drawable.widget_flick_check_off)
            views.setViewVisibility(slotRing(n), if (selecting && on) View.VISIBLE else View.GONE)
            views.setViewVisibility(slotDim(n), if (selecting && !on && pickedVisible.isNotEmpty()) View.VISIBLE else View.GONE)
            val click = if (selecting) {
                broadcast(context, id, ACTION_TOGGLE, 10 + n, m.uri)
            } else {
                activity(context, id, 10 + n, FlickActivity.mediaIntent(context, listOf(RecentMedia.uriOf(m)), id))
            }
            views.setOnClickPendingIntent(slotId(n), click)
        }

        // The big button: the picks as one bundle, else the latest one, else the picker.
        val (label, go) = when {
            selecting && pickedVisible.isNotEmpty() -> {
                val uris = media.filter { it.uri in picked }.map { RecentMedia.uriOf(it) }
                (if (uris.size == 1) "Flick 1" else "Flick ${uris.size} together") to
                    activity(context, id, 4, FlickActivity.mediaIntent(context, uris, id))
            }
            selecting -> "Pick photos above" to broadcast(context, id, ACTION_REFRESH, 4)
            media.isNotEmpty() -> (if (media.first().isScreenshot) "Flick latest screenshot" else "Flick latest") to
                activity(context, id, 4, FlickActivity.mediaIntent(context, listOf(RecentMedia.uriOf(media.first())), id))
            else -> "Pick & flick" to activity(context, id, 4, FlickActivity.pickIntent(context))
        }
        views.setTextViewText(R.id.flick_widget_go_text, label)
        views.setOnClickPendingIntent(R.id.flick_widget_go, go)
        return views
    }

    private fun thumbnail(context: Context, m: RecentMedia, px: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(RecentMedia.uriOf(m), Size(px, px), null)
    } catch (_: Exception) {
        null
    }

    private fun activity(context: Context, widgetId: Int, slot: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context, widgetId * 64 + slot, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun broadcast(context: Context, widgetId: Int, action: String, slot: Int, media: String? = null): PendingIntent {
        val i = Intent(context, FlickWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            if (media != null) putExtra(EXTRA_MEDIA, media)
        }
        return PendingIntent.getBroadcast(
            context, widgetId * 64 + slot + 32, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val ROWS = intArrayOf(R.id.flick_row_0, R.id.flick_row_1, R.id.flick_row_2)
    private val SLOT = intArrayOf(
        R.id.flick_slot_0, R.id.flick_slot_1, R.id.flick_slot_2, R.id.flick_slot_3,
        R.id.flick_slot_4, R.id.flick_slot_5, R.id.flick_slot_6, R.id.flick_slot_7,
        R.id.flick_slot_8, R.id.flick_slot_9, R.id.flick_slot_10, R.id.flick_slot_11
    )
    private val IMAGE = intArrayOf(
        R.id.flick_slot_0_image, R.id.flick_slot_1_image, R.id.flick_slot_2_image, R.id.flick_slot_3_image,
        R.id.flick_slot_4_image, R.id.flick_slot_5_image, R.id.flick_slot_6_image, R.id.flick_slot_7_image,
        R.id.flick_slot_8_image, R.id.flick_slot_9_image, R.id.flick_slot_10_image, R.id.flick_slot_11_image
    )
    private val PLAY = intArrayOf(
        R.id.flick_slot_0_play, R.id.flick_slot_1_play, R.id.flick_slot_2_play, R.id.flick_slot_3_play,
        R.id.flick_slot_4_play, R.id.flick_slot_5_play, R.id.flick_slot_6_play, R.id.flick_slot_7_play,
        R.id.flick_slot_8_play, R.id.flick_slot_9_play, R.id.flick_slot_10_play, R.id.flick_slot_11_play
    )
    private val CHECK = intArrayOf(
        R.id.flick_slot_0_check, R.id.flick_slot_1_check, R.id.flick_slot_2_check, R.id.flick_slot_3_check,
        R.id.flick_slot_4_check, R.id.flick_slot_5_check, R.id.flick_slot_6_check, R.id.flick_slot_7_check,
        R.id.flick_slot_8_check, R.id.flick_slot_9_check, R.id.flick_slot_10_check, R.id.flick_slot_11_check
    )
    private val RING = intArrayOf(
        R.id.flick_slot_0_ring, R.id.flick_slot_1_ring, R.id.flick_slot_2_ring, R.id.flick_slot_3_ring,
        R.id.flick_slot_4_ring, R.id.flick_slot_5_ring, R.id.flick_slot_6_ring, R.id.flick_slot_7_ring,
        R.id.flick_slot_8_ring, R.id.flick_slot_9_ring, R.id.flick_slot_10_ring, R.id.flick_slot_11_ring
    )
    private val DIM = intArrayOf(
        R.id.flick_slot_0_dim, R.id.flick_slot_1_dim, R.id.flick_slot_2_dim, R.id.flick_slot_3_dim,
        R.id.flick_slot_4_dim, R.id.flick_slot_5_dim, R.id.flick_slot_6_dim, R.id.flick_slot_7_dim,
        R.id.flick_slot_8_dim, R.id.flick_slot_9_dim, R.id.flick_slot_10_dim, R.id.flick_slot_11_dim
    )
    private val TAG_IDS = intArrayOf(
        R.id.flick_slot_0_tag, R.id.flick_slot_1_tag, R.id.flick_slot_2_tag, R.id.flick_slot_3_tag,
        R.id.flick_slot_4_tag, R.id.flick_slot_5_tag, R.id.flick_slot_6_tag, R.id.flick_slot_7_tag,
        R.id.flick_slot_8_tag, R.id.flick_slot_9_tag, R.id.flick_slot_10_tag, R.id.flick_slot_11_tag
    )

    private fun rowId(r: Int) = ROWS[r]
    private fun slotId(n: Int) = SLOT[n]
    private fun slotImage(n: Int) = IMAGE[n]
    private fun slotPlay(n: Int) = PLAY[n]
    private fun slotCheck(n: Int) = CHECK[n]
    private fun slotRing(n: Int) = RING[n]
    private fun slotDim(n: Int) = DIM[n]
    private fun slotTag(n: Int) = TAG_IDS[n]
}

/** The Flick widget's grid for its size (pure, unit-tested). */
object FlickWidgetGrid {
    const val MAX_SHOWN = 9

    /** Rows × columns for a widget of [widthDp]×[heightDp]: thumbnails at least ~64 dp, 3–4 across, 9 at most. */
    fun of(widthDp: Int, heightDp: Int): Pair<Int, Int> {
        val gridH = heightDp - 112 // header, button, padding
        val rows = (gridH / 64).coerceIn(1, 3)
        var cols = ((widthDp - 20) / 74).coerceIn(3, 4)
        if (rows * cols > MAX_SHOWN) cols = 3
        return rows to cols
    }
}

/**
 * Keeps the Flick widget on the newest photos: a job the system runs when MediaStore images or videos change
 * (a screenshot, a photo), which re-renders the widgets and waits for the next change.
 */
class FlickWidgetMediaJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        FlickWidget.renderAsync(applicationContext, FlickWidget.ids(applicationContext)) {
            schedule(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 0x464c4b // "FLK"

        fun schedule(context: Context) {
            if (FlickWidget.ids(context).isEmpty()) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, FlickWidgetMediaJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1_500)
                .setTriggerContentMaxDelay(10_000)
                .build()
            runCatching { scheduler.schedule(job) }.onFailure { Log.w("FlickWidget", "Can't watch for new photos: ${it.message}") }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
