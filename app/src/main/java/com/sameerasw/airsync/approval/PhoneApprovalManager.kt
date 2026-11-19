package com.sameerasw.airsync.approval

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sameerasw.airsync.AirSyncApp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * H.I.B "Touch ID on Phone": the Mac asks the phone to approve an authentication request
 * (`approvalRequest` {id, title, detail, expiresInMs}); the user approves with BiometricPrompt
 * in [ApprovalActivity]; we answer with `approvalResponse` {id, approved, method}.
 *
 * Never auto-approves: [approveAuthenticated] is only called from BiometricPrompt's success callback.
 * Every request is answered exactly once (approve / deny / timeout / cancelled by the Mac).
 */
object PhoneApprovalManager {
    private const val TAG = "HIBPhoneApproval"
    private const val CHANNEL_ID = "hib_phone_approval"
    private const val NOTIFICATION_BASE_ID = 0x4A50
    const val EXTRA_ID = "approval_id"

    data class Request(
        val id: String,
        val title: String,
        val detail: String,
        val expiresAtElapsed: Long,
        val notificationId: Int
    )

    private val main = Handler(Looper.getMainLooper())
    private val _pending = MutableStateFlow<Map<String, Request>>(emptyMap())
    val pending = _pending.asStateFlow()
    private var nextNotificationOffset = 0

    fun get(id: String): Request? = _pending.value[id]

    /** `approvalRequest` from the Mac (OkHttp reader thread). */
    fun onRequest(context: Context, data: JSONObject) {
        val id = data.optString("id")
        if (id.isEmpty() || id.length > 128) return
        val title = data.optString("title").ifEmpty { "Approve on your Mac?" }.take(120)
        val detail = data.optString("detail").take(400)
        val expiresInMs = data.optLong("expiresInMs", 30_000L).coerceIn(5_000L, 60_000L)
        val app = context.applicationContext
        main.post { addOnMain(app, id, title, detail, expiresInMs) }
    }

    /** `approvalCancel` from the Mac: the request ended there (timeout, sudo interrupted, ...). */
    fun onCancel(context: Context, data: JSONObject) {
        val id = data.optString("id")
        val app = context.applicationContext
        main.post { removeOnMain(app, id) }
    }

    private fun addOnMain(context: Context, id: String, title: String, detail: String, expiresInMs: Long) {
        if (_pending.value.containsKey(id)) return
        val notificationId = NOTIFICATION_BASE_ID + (nextNotificationOffset++ % 16)
        val request = Request(id, title, detail, SystemClock.elapsedRealtime() + expiresInMs, notificationId)
        _pending.value = _pending.value + (id to request)
        main.postDelayed({ resolve(context, id, approved = false, method = "timeout") }, id, expiresInMs)
        Log.d(TAG, "Approval requested: $title")

        if (AirSyncApp.isAppForeground()) {
            runCatching { context.startActivity(activityIntent(context, id)) }
                .onFailure { showNotification(context, request) }
        } else {
            showNotification(context, request)
        }
    }

    /** Called only from BiometricPrompt's onAuthenticationSucceeded. */
    fun approveAuthenticated(context: Context, id: String, viaBiometric: Boolean) {
        resolve(context, id, approved = true, method = if (viaBiometric) "biometric" else "deviceCredential")
    }

    fun deny(context: Context, id: String) {
        resolve(context, id, approved = false, method = "denied")
    }

    private fun resolve(context: Context, id: String, approved: Boolean, method: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { resolve(context, id, approved, method) }
            return
        }
        val request = _pending.value[id] ?: return // one answer per request
        removeOnMain(context.applicationContext, id)
        if (approved && SystemClock.elapsedRealtime() > request.expiresAtElapsed) {
            sendResponse(id, false, "timeout")
            return
        }
        sendResponse(id, approved, method)
    }

    private fun removeOnMain(context: Context, id: String) {
        val request = _pending.value[id] ?: return
        _pending.value = _pending.value - id
        main.removeCallbacksAndMessages(id)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(request.notificationId)
    }

    private fun sendResponse(id: String, approved: Boolean, method: String) {
        Log.d(TAG, "Approval answered: approved=$approved method=$method")
        val message = JSONObject()
            .put("type", "approvalResponse")
            .put("data", JSONObject().put("id", id).put("approved", approved).put("method", method))
            .toString()
        runCatching { WebSocketUtil.sendMessage(message) }
    }

    fun activityIntent(context: Context, id: String): Intent =
        Intent(context, ApprovalActivity::class.java)
            .putExtra(EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun showNotification(context: Context, request: Request) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mac approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Approve sudo and other requests from your Mac with your fingerprint"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    enableVibration(true)
                }
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val approveIntent = PendingIntent.getActivity(
            context, request.notificationId, activityIntent(context, request.id), flags
        )
        val denyIntent = PendingIntent.getBroadcast(
            context, request.notificationId,
            Intent(context, ApprovalDenyReceiver::class.java).putExtra(EXTRA_ID, request.id), flags
        )
        val timeoutMs = (request.expiresAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(1_000L)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("${request.title}?")
            .setContentText(request.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.detail))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_name)
                    .setContentTitle("Approval request from your Mac")
                    .build()
            )
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(timeoutMs)
            .setContentIntent(approveIntent)
            .setFullScreenIntent(approveIntent, true)
            .addAction(0, "Deny", denyIntent)
            .addAction(0, "Approve", approveIntent)
            .build()
        runCatching { nm.notify(request.notificationId, notification) }
    }
}
