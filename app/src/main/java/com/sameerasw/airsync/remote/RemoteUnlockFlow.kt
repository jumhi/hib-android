package com.sameerasw.airsync.remote

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
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.app.NotificationCompat
import com.sameerasw.airsync.AirSyncApp
import com.sameerasw.airsync.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Unlock from phone (SPACE.md §15.1, design §6.6), the phone side:
 *  - unlock: `unlockRequest` → `unlockChallenge{nonce}` → BiometricPrompt with a CryptoObject on this computer's
 *    Keystore key ([RemoteUnlockActivity]) → `unlockApprove{nonce, sig}` → `unlockResult`;
 *  - enrolment: `unlockEnrollRequest{name}` (the computer's own setup is open) → "Allow unlocking <name> with your
 *    fingerprint?" → prompt → new key → `unlockEnroll{phoneId, publicKey}` → `unlockEnrollResult`.
 * Main thread only for state changes; inbound calls hop to it. Never logs nonces, signatures or keys.
 */
object RemoteUnlockFlow {
    private const val TAG = "RemoteUnlock"
    private const val ENROLL_WINDOW_MS = 120_000L
    private const val CHANNEL_ID = "hib_phone_approval"
    private const val ENROLL_NOTIFICATION_ID = 0x4A70

    /** What the locked card shows for one computer. */
    data class Ui(val step: UnlockFlowState.Step, val message: String?)

    private val main = Handler(Looper.getMainLooper())
    private val flows = HashMap<String, UnlockFlowState>()
    private val names = HashMap<String, String>()
    private val _ui = MutableStateFlow<Map<String, Ui>>(emptyMap())
    val ui: StateFlow<Map<String, Ui>> = _ui.asStateFlow()

    /** Computers this phone unlocked in this app session (the Lock button then leaves the Remote). */
    private val unlockedHere = HashSet<String>()

    private data class PendingEnroll(val computer: String, val displayName: String, val at: Long)
    @Volatile private var pendingEnroll: PendingEnroll? = null

    fun uiFor(name: String): Ui? = _ui.value[RemoteHub.key(name)]

    fun unlockedThisSession(name: String): Boolean = RemoteHub.key(name) in unlockedHere

    fun clearMessage(name: String) = onMain {
        val f = flows[RemoteHub.key(name)] ?: return@onMain
        if (!f.busy) { f.reset(); publish(name, f) }
    }

    // MARK: - unlock

    /** The locked card's Unlock button. */
    fun start(context: Context, name: String) = onMain {
        val k = RemoteHub.key(name)
        val f = flows.getOrPut(k) { UnlockFlowState(name) }
        names[k] = name
        val now = SystemClock.elapsedRealtime()
        if (f.busy && !f.timedOut(now)) return@onMain
        val hello = RemoteHub.computer(name)?.hello
        val error = when {
            hello?.unlock == RemoteHello.UNLOCK_NEEDS_SETUP -> UnlockFlowState.ERR_NOT_SET_UP
            !hasKey(name) -> UnlockFlowState.ERR_NO_KEY
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) !=
                BiometricManager.BIOMETRIC_SUCCESS -> UnlockFlowState.ERR_NO_LOCK_SCREEN
            else -> null
        }
        f.request(now)
        if (error != null) {
            f.onPromptFailed(error)
            publish(name, f)
            return@onMain
        }
        if (!RemoteHub.send(name, RemoteJson.unlockRequest())) f.onPromptFailed(UnlockFlowState.ERR_NO_ANSWER)
        publish(name, f)
        watch(name)
    }

    fun onChallenge(context: Context, name: String, nonce: String) = onMain {
        val f = flows[RemoteHub.key(name)] ?: return@onMain
        if (!f.onChallenge(nonce, SystemClock.elapsedRealtime())) return@onMain
        publish(name, f)
        val app = context.applicationContext
        val intent = RemoteUnlockActivity.unlockIntent(app, name)
        runCatching { app.startActivity(intent) }.onFailure {
            f.onPromptFailed(UnlockFlowState.ERR_CANCELLED)
            publish(name, f)
        }
    }

    /** The activity is about to show the prompt; returns the nonce it must sign (null: nothing to do). */
    internal fun promptShown(name: String): String? {
        val f = flows[RemoteHub.key(name)] ?: return null
        f.promptShown(SystemClock.elapsedRealtime())
        publish(name, f)
        return f.nonce
    }

    /** The prompt succeeded and the Keystore signed `signedBytes(id, nonce)`. Sends `unlockApprove`. */
    internal fun onSigned(name: String, nonce: String, signatureDer: ByteArray) {
        val f = flows[RemoteHub.key(name)] ?: return
        if (f.nonce != nonce) return
        val n = f.onSigned(SystemClock.elapsedRealtime())
        if (n != null) {
            if (!RemoteHub.send(name, RemoteJson.unlockApprove(n, UnlockSigning.signatureBase64(signatureDer)))) {
                f.onPromptFailed(UnlockFlowState.ERR_NO_ANSWER)
            }
        }
        publish(name, f)
    }

    internal fun onPromptFailed(name: String, code: String) {
        val f = flows[RemoteHub.key(name)] ?: return
        if (!f.busy) return
        f.onPromptFailed(code)
        publish(name, f)
    }

    fun onResult(name: String, result: UnlockResult) = onMain {
        val f = flows[RemoteHub.key(name)] ?: return@onMain
        f.onResult(result)
        if (result.ok) unlockedHere += RemoteHub.key(name)
        Log.d(TAG, "Unlock $name: ${if (result.ok) "ok" else result.error ?: "failed"}")
        publish(name, f)
    }

    /** The computer unlocked (by us or by someone at it): a finished attempt's message goes away. */
    fun onLockState(name: String, locked: Boolean) = onMain {
        val f = flows[RemoteHub.key(name)] ?: return@onMain
        if (!locked && (f.step == UnlockFlowState.Step.DONE || f.step == UnlockFlowState.Step.APPROVING)) {
            if (f.step == UnlockFlowState.Step.APPROVING) unlockedHere += RemoteHub.key(name)
            f.reset()
            publish(name, f)
        }
    }

    /** Key for this computer: under its hello id, or (enrolled before the hello came) under its name. */
    internal fun keyIdFor(name: String): String? {
        val id = RemoteHub.computerId(name)
        return when {
            RemoteUnlockKeys.has(id) -> id
            RemoteUnlockKeys.has(name) -> name
            else -> null
        }
    }

    private fun hasKey(name: String) = keyIdFor(name) != null

    private fun watch(name: String) {
        val k = RemoteHub.key(name)
        main.postDelayed(object : Runnable {
            override fun run() {
                val f = flows[k] ?: return
                if (!f.busy) return
                if (f.expireIfStale(SystemClock.elapsedRealtime())) { publish(name, f); return }
                main.postDelayed(this, 1_000L)
            }
        }, 1_000L)
    }

    private fun publish(name: String, f: UnlockFlowState) {
        val k = RemoteHub.key(name)
        val display = names[k] ?: name
        _ui.update { it + (k to Ui(f.step, UnlockFlowState.message(f.error, display))) }
    }

    // MARK: - enrolment

    fun onEnrollRequest(context: Context, computer: String, displayName: String) = onMain {
        pendingEnroll = PendingEnroll(computer, displayName, SystemClock.elapsedRealtime())
        val app = context.applicationContext
        val intent = RemoteUnlockActivity.enrollIntent(app, computer)
        if (AirSyncApp.isAppForeground()) {
            runCatching { app.startActivity(intent) }.onFailure { notifyEnroll(app, displayName, intent) }
        } else {
            notifyEnroll(app, displayName, intent)
        }
    }

    /** Still inside the computer's 2-minute setup window? Returns its display name. */
    internal fun enrollPending(computer: String): String? {
        val p = pendingEnroll ?: return null
        if (!RemoteHub.key(p.computer).equals(RemoteHub.key(computer))) return null
        if (SystemClock.elapsedRealtime() - p.at > ENROLL_WINDOW_MS) return null
        return p.displayName
    }

    /** Consent given and the prompt passed: make the key and send it. False when it couldn't be sent. */
    internal fun completeEnroll(context: Context, computer: String): Boolean {
        if (enrollPending(computer) == null) return false
        pendingEnroll = null
        cancelEnrollNotification(context)
        return try {
            val id = RemoteHub.computerId(computer)
            val spki = RemoteUnlockKeys.create(id)
            RemoteHub.send(computer, RemoteJson.unlockEnroll(RemoteUnlockKeys.phoneId(context), UnlockSigning.publicKeyBase64(spki)))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't make the unlock key: ${e.javaClass.simpleName}")
            false
        }
    }

    internal fun declineEnroll(context: Context) {
        pendingEnroll = null
        cancelEnrollNotification(context)
    }

    fun onEnrollResult(context: Context, computer: String, ok: Boolean) = onMain {
        val app = context.applicationContext
        val text = if (ok) "You can now unlock $computer from this phone" else "Couldn't set up unlock on $computer"
        Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        // A fresh key: an old "set up again" message is stale.
        flows[RemoteHub.key(computer)]?.let { if (!it.busy) { it.reset(); publish(computer, it) } }
    }

    private fun notifyEnroll(context: Context, name: String, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Computer approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Approve sudo and other requests from your computer with your fingerprint"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
        val pi = PendingIntent.getActivity(
            context, ENROLL_NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Unlock $name from this phone?")
            .setContentText("Tap to allow unlocking $name with your fingerprint")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(ENROLL_WINDOW_MS)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(ENROLL_NOTIFICATION_ID, n) }
    }

    private fun cancelEnrollNotification(context: Context) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ENROLL_NOTIFICATION_ID)
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}
