package com.sameerasw.airsync.scenes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.SyncManager
import org.json.JSONObject
import kotlin.math.roundToInt

/** Executes `phoneAction` requests from the Mac and replies with `phoneActionResult`. */
object ScenePhoneActions {
    private const val TAG = "HIBScenePhoneActions"
    private const val CHANNEL_ID = "hib_scenes"

    private class ActionFailed(message: String) : Exception(message)

    /** Must be called on the main thread. Never throws. */
    fun perform(context: Context, data: JSONObject) {
        val requestId = data.optString("requestId")
        val action = data.optString("action")
        val result = JSONObject().put("requestId", requestId).put("action", action)
        try {
            val message = when (action) {
                "dnd" -> setDnd(context, data.optBoolean("enabled", true))
                "ringer" -> setRinger(context, data.optString("mode", "normal"))
                "volume" -> setVolume(context, data.optInt("percent", 50))
                "openApp" -> openApp(context, data.optString("package"))
                "notify" -> notify(context, data.optString("title", "H.I.B"), data.optString("body"))
                else -> throw ActionFailed("Unknown phone action: $action")
            }
            result.put("ok", true).put("message", message)
        } catch (e: SecurityException) {
            result.put("ok", false).put("error", "Phone blocked it: ${e.message ?: "permission missing"}")
        } catch (e: Exception) {
            result.put("ok", false).put("error", e.message ?: "Failed")
        }
        Log.d(TAG, "phoneAction $action -> $result")
        ScenesRemote.sendJson("phoneActionResult", result)
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun hasPolicyAccess(context: Context): Boolean =
        notificationManager(context).isNotificationPolicyAccessGranted

    private fun setDnd(context: Context, enabled: Boolean): String {
        val nm = notificationManager(context)
        if (!nm.isNotificationPolicyAccessGranted) {
            throw ActionFailed("Grant H.I.B “Do Not Disturb access” on the phone (Permissions screen).")
        }
        nm.setInterruptionFilter(
            if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return if (enabled) "Phone Do Not Disturb on" else "Phone Do Not Disturb off"
    }

    private fun setRinger(context: Context, mode: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val target = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        if (target == AudioManager.RINGER_MODE_SILENT && !hasPolicyAccess(context)) {
            throw ActionFailed("Silent mode needs “Do Not Disturb access” for H.I.B on the phone.")
        }
        am.ringerMode = target
        return "Phone ringer: $mode"
    }

    private fun setVolume(context: Context, percent: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (percent.coerceIn(0, 100) / 100.0 * max).roundToInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        SyncManager.onVolumeChanged(context)
        return "Phone media volume ${percent.coerceIn(0, 100)}%"
    }

    private fun openApp(context: Context, pkg: String): String {
        if (pkg.isBlank()) throw ActionFailed("No app package.")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw ActionFailed("App $pkg isn't installed (or has no launcher).")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            "Opened $pkg on phone"
        } catch (e: Exception) {
            // Android may block background activity starts; offer a tap-to-open notification instead.
            val label = try {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg }
            postNotification(context, "Open $label", "Tap to open $label (requested by a scene on your computer)", intent)
            "Android blocked opening $label in the background; posted a tap-to-open notification"
        }
    }

    private fun notify(context: Context, title: String, body: String): String {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        postNotification(context, title.ifBlank { "H.I.B" }, body, launch)
        return "Posted phone notification"
    }

    private fun postNotification(context: Context, title: String, body: String, intent: Intent?) {
        val nm = notificationManager(context)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Scenes", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notifications from H.I.B Scenes run on your computer"
                }
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.rounded_star_shine_24)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (intent != null) {
            val pi = PendingIntent.getActivity(
                context, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pi)
        }
        if (!nm.areNotificationsEnabled()) throw ActionFailed("Notifications are off for H.I.B on the phone.")
        nm.notify((System.currentTimeMillis() % 100_000).toInt() + 0x5C00, builder.build())
    }
}
