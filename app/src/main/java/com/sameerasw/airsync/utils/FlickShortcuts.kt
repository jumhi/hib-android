package com.sameerasw.airsync.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.activities.FlickActivity

/**
 * H.I.B — Flick in the share sheet. Phones' galleries can't take a button from another app, so Flick comes to
 * them: a long-lived sharing shortcut (category [CATEGORY], matched by the `share-target` in
 * res/xml/shortcuts.xml) puts "Flick" with its own icon in the Direct Share row at the top of the share sheet,
 * for one photo or many. Every throw reports it used so the system keeps it near the front. From the launcher
 * (long-press H.I.B) it opens the photo picker straight into Flick.
 */
object FlickShortcuts {
    const val ID = "shortcut_flick"
    const val CATEGORY = "com.sameerasw.airsync.categories.FLICK"

    fun build(context: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel("Flick")
            .setLongLabel("Flick to your computer")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_flick_shortcut))
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .setRank(0)
            .setIntent(
                Intent(context, FlickActivity::class.java)
                    .setAction("com.sameerasw.airsync.FLICK")
                    .putExtra(FlickActivity.EXTRA_PICK, true)
            )
            .build()

    /** Publishes (or refreshes) the shortcut and tells the system it was just used. */
    fun reportUsed(context: Context) {
        try {
            ShortcutManagerCompat.pushDynamicShortcut(context.applicationContext, build(context.applicationContext))
        } catch (e: Exception) {
            Log.w("FlickShortcuts", "Couldn't publish the Flick shortcut: ${e.javaClass.simpleName}")
        }
    }

    /** [shortcuts] with Flick first, trimmed to what the launcher allows. */
    fun withFlick(context: Context, shortcuts: List<ShortcutInfoCompat>): List<ShortcutInfoCompat> {
        val max = runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }.getOrDefault(4).coerceAtLeast(1)
        return (listOf(build(context)) + shortcuts.filter { it.id != ID }).take(max)
    }
}
