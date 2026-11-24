package com.sameerasw.airsync.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.sameerasw.airsync.R
import com.sameerasw.airsync.home.DeviceCards

/**
 * H.I.B "Devices" widget: every computer with its live status, in the same order and words as Home. A tap
 * on a computer opens H.I.B on that computer's quick actions; the header opens Home.
 * Short widgets show a row of computer bubbles instead of the list.
 */
class DevicesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, mgr, id)
    }

    companion object {
        private val ROWS = intArrayOf(R.id.hw_row0, R.id.hw_row1, R.id.hw_row2, R.id.hw_row3)
        private val ROW_NAME = intArrayOf(R.id.hw_row0_name, R.id.hw_row1_name, R.id.hw_row2_name, R.id.hw_row3_name)
        private val ROW_SUB = intArrayOf(R.id.hw_row0_sub, R.id.hw_row1_sub, R.id.hw_row2_sub, R.id.hw_row3_sub)
        private val ROW_BATT = intArrayOf(R.id.hw_row0_batt, R.id.hw_row1_batt, R.id.hw_row2_batt, R.id.hw_row3_batt)
        private val ROW_ICON = intArrayOf(R.id.hw_row0_icon, R.id.hw_row1_icon, R.id.hw_row2_icon, R.id.hw_row3_icon)
        private val ROW_CIRCLE = intArrayOf(R.id.hw_row0_circle, R.id.hw_row1_circle, R.id.hw_row2_circle, R.id.hw_row3_circle)
        private val ROW_DOT = intArrayOf(R.id.hw_row0_dot, R.id.hw_row1_dot, R.id.hw_row2_dot, R.id.hw_row3_dot)

        private val SLOTS = intArrayOf(R.id.hw_slot0, R.id.hw_slot1, R.id.hw_slot2, R.id.hw_slot3)
        private val SLOT_NAME = intArrayOf(R.id.hw_slot0_name, R.id.hw_slot1_name, R.id.hw_slot2_name, R.id.hw_slot3_name)
        private val SLOT_ICON = intArrayOf(R.id.hw_slot0_icon, R.id.hw_slot1_icon, R.id.hw_slot2_icon, R.id.hw_slot3_icon)
        private val SLOT_CIRCLE = intArrayOf(R.id.hw_slot0_circle, R.id.hw_slot1_circle, R.id.hw_slot2_circle, R.id.hw_slot3_circle)
        private val SLOT_DOT = intArrayOf(R.id.hw_slot0_dot, R.id.hw_slot1_dot, R.id.hw_slot2_dot, R.id.hw_slot3_dot)

        fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val (_, h) = HibWidgets.sizeDp(mgr, id, 250, 110)
            val cards = HibWidgets.cards()
            val compact = h < 96
            val views = if (compact) compact(context, id, cards) else list(context, id, cards, h)
            HibWidgets.background(views)
            mgr.updateAppWidget(id, views)
        }

        private fun list(context: Context, id: Int, cards: List<com.sameerasw.airsync.home.DeviceCardUi>, h: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_hib_devices)
            val state = WidgetModels.devices(cards, minOf(WidgetModels.rowsFor(h), ROWS.size), DeviceCards.summary(cards, HibWidgets.appPaused))
            v.setTextViewText(R.id.hw_summary, state.summary)
            v.setOnClickPendingIntent(R.id.hw_header, HibWidgets.openApp(context, id * 16))
            ROWS.indices.forEach { i ->
                val row = state.rows.getOrNull(i)
                if (row == null) {
                    v.setViewVisibility(ROWS[i], View.GONE)
                    return@forEach
                }
                v.setViewVisibility(ROWS[i], View.VISIBLE)
                v.setTextViewText(ROW_NAME[i], row.name)
                v.setTextViewText(ROW_SUB[i], row.subtitle)
                v.setImageViewResource(ROW_ICON[i], HibWidgets.platformIcon(row.platform))
                v.setImageViewResource(ROW_CIRCLE[i], if (row.highlighted) R.drawable.hib_widget_circle_accent else R.drawable.hib_widget_circle)
                HibWidgets.tint(context, v, ROW_DOT[i], HibWidgets.dotColor(row.dot))
                v.setInt(ROWS[i], "setBackgroundResource", if (row.highlighted) R.drawable.hib_widget_row_highlight else R.drawable.hib_widget_row)
                if (row.battery != null) {
                    v.setViewVisibility(ROW_BATT[i], View.VISIBLE)
                    v.setTextViewText(ROW_BATT[i], "${row.battery}%")
                } else {
                    v.setViewVisibility(ROW_BATT[i], View.GONE)
                }
                v.setContentDescription(ROWS[i], "${row.name}, ${row.subtitle}. Opens its quick actions.")
                v.setOnClickPendingIntent(ROWS[i], HibWidgets.openApp(context, id * 16 + 1 + i, row.name))
            }
            if (state.more > 0) {
                v.setViewVisibility(R.id.hw_more, View.VISIBLE)
                v.setTextViewText(R.id.hw_more, "+${state.more} more — open H.I.B")
                v.setOnClickPendingIntent(R.id.hw_more, HibWidgets.openApp(context, id * 16 + 8))
            } else {
                v.setViewVisibility(R.id.hw_more, View.GONE)
            }
            if (state.empty != null) {
                v.setViewVisibility(R.id.hw_empty, View.VISIBLE)
                v.setTextViewText(R.id.hw_empty, state.empty)
                v.setOnClickPendingIntent(R.id.hw_empty, HibWidgets.openApp(context, id * 16 + 9))
            } else {
                v.setViewVisibility(R.id.hw_empty, View.GONE)
            }
            return v
        }

        private fun compact(context: Context, id: Int, cards: List<com.sameerasw.airsync.home.DeviceCardUi>): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_hib_devices_compact)
            val state = WidgetModels.devices(cards, SLOTS.size, "")
            SLOTS.indices.forEach { i ->
                val row = state.rows.getOrNull(i)
                if (row == null) {
                    v.setViewVisibility(SLOTS[i], View.GONE)
                    return@forEach
                }
                v.setViewVisibility(SLOTS[i], View.VISIBLE)
                v.setTextViewText(SLOT_NAME[i], row.name)
                v.setImageViewResource(SLOT_ICON[i], HibWidgets.platformIcon(row.platform))
                v.setImageViewResource(SLOT_CIRCLE[i], if (row.highlighted) R.drawable.hib_widget_circle_accent else R.drawable.hib_widget_circle)
                HibWidgets.tint(context, v, SLOT_DOT[i], HibWidgets.dotColor(row.dot))
                v.setContentDescription(SLOTS[i], "${row.name}, ${row.subtitle}")
                v.setOnClickPendingIntent(SLOTS[i], HibWidgets.openApp(context, id * 16 + 1 + i, row.name))
            }
            if (state.empty != null) {
                v.setViewVisibility(R.id.hw_empty, View.VISIBLE)
                v.setOnClickPendingIntent(R.id.hw_empty, HibWidgets.openApp(context, id * 16 + 9))
            } else {
                v.setViewVisibility(R.id.hw_empty, View.GONE)
            }
            return v
        }
    }
}
