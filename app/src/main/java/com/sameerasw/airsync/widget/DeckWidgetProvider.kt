package com.sameerasw.airsync.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import com.sameerasw.airsync.R
import com.sameerasw.airsync.deskdeck.DeskDeckRemote

/**
 * H.I.B "Desk Deck" widget: four Desk Deck buttons (you pick them: lock, play/pause, volume, screenshot…)
 * that go to the computer you're using, as the Desk Deck does. 2×2 on a square widget, one row on a wide one.
 */
class DeckWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, mgr, id)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { DeckWidgetStore.remove(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_DECK || !HibWidgets.isOurs(context, intent)) return
        val action = intent.getStringExtra(HibWidgets.EXTRA_ACTION) ?: return
        if (HibWidgets.target() == null) {
            Toast.makeText(context, "No computer connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (!DeskDeckRemote.sendAction(action)) {
            Toast.makeText(context, "Couldn't reach ${HibWidgets.target() ?: "your computer"}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_DECK = "com.sameerasw.airsync.widget.DECK"
        private val TILES = intArrayOf(R.id.hw_deck0, R.id.hw_deck1, R.id.hw_deck2, R.id.hw_deck3)
        private val ICONS = intArrayOf(R.id.hw_deck0_icon, R.id.hw_deck1_icon, R.id.hw_deck2_icon, R.id.hw_deck3_icon)
        private val LABELS = intArrayOf(R.id.hw_deck0_label, R.id.hw_deck1_label, R.id.hw_deck2_label, R.id.hw_deck3_label)

        fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val (w, h) = HibWidgets.sizeDp(mgr, id, 180, 180)
            // Wide and short: one row of four; otherwise a 2×2 grid.
            val row = w >= 2 * h || h < 100
            val v = RemoteViews(context.packageName, if (row) R.layout.widget_hib_deck_row else R.layout.widget_hib_deck)
            val state = WidgetModels.deck(DeckWidgetStore.load(context, id), HibWidgets.target())
            HibWidgets.background(v)
            v.setTextViewText(R.id.hw_header_text, state.header)
            val small = (if (row) w / 4 else w / 2) < 64 || (if (row) h else h / 2) < 56
            state.buttons.forEachIndexed { i, b ->
                v.setImageViewResource(ICONS[i], HibWidgets.deckIcon(b.icon))
                v.setTextViewText(LABELS[i], b.label)
                v.setViewVisibility(LABELS[i], if (small) android.view.View.GONE else android.view.View.VISIBLE)
                v.setContentDescription(TILES[i], if (state.target != null) "${b.label} on ${state.target}" else b.label)
                v.setFloat(TILES[i], "setAlpha", if (state.target != null) 1f else 0.55f)
                v.setOnClickPendingIntent(
                    TILES[i],
                    HibWidgets.broadcast(context, DeckWidgetProvider::class.java, ACTION_DECK, id * 16 + i, HibWidgets.EXTRA_ACTION to b.actionId)
                )
            }
            v.setOnClickPendingIntent(R.id.hw_configure, DeckWidgetConfigActivity.pendingIntent(context, id))
            mgr.updateAppWidget(id, v)
        }
    }
}

/** Which four actions each Desk Deck widget shows (per widget). */
object DeckWidgetStore {
    private const val PREFS = "hib_widgets"
    private fun key(id: Int) = "deck_$id"

    fun load(context: Context, id: Int): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(id), null)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: WidgetModels.DEFAULT_DECK

    fun save(context: Context, id: Int, actions: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(id), actions.joinToString(",")).apply()
    }

    fun remove(context: Context, id: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(id)).apply()
    }
}
