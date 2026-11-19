package com.sameerasw.airsync.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.service.RingStopReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Ring My Phone: plays an alarm at full volume (alarm audio plays even on silent),
 * vibrates and flashes the torch until stopped from the phone or the Mac, or after 2 minutes.
 */
object PhoneRinger {
    private const val TAG = "HIBPhoneRinger"
    private const val CHANNEL_ID = "hib_ring_phone"
    private const val NOTIFICATION_ID = 0x4B1B
    private const val MAX_RING_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    private val _isRinging = MutableStateFlow(false)
    val isRinging = _isRinging.asStateFlow()

    private var player: MediaPlayer? = null
    private var previousAlarmVolume: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchOn = false
    private var torchCameraId: String? = null
    private var appContext: Context? = null

    private val autoStop = Runnable { appContext?.let { stop(it) } }
    private val torchBlink = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            if (!_isRinging.value) return
            setTorch(ctx, !torchOn)
            main.postDelayed(this, 350L)
        }
    }

    fun start(context: Context) {
        main.post { startOnMain(context.applicationContext) }
    }

    fun stop(context: Context) {
        main.post { stopOnMain(context.applicationContext) }
    }

    private fun startOnMain(context: Context) {
        if (_isRinging.value) return
        appContext = context
        _isRinging.value = true
        Log.d(TAG, "Ringing phone")

        wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HIB:RingPhone")
            .apply { acquire(MAX_RING_MS + 5_000L) }

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            previousAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }

        val soundUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, soundUri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.e(TAG, "Couldn't play ring sound", it) }.getOrNull()

        runCatching {
            val vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 900, 500), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            )
        }

        torchCameraId = runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        main.post(torchBlink)

        showNotification(context)
        main.postDelayed(autoStop, MAX_RING_MS)
        reportToMac(true)
    }

    private fun stopOnMain(context: Context) {
        if (!_isRinging.value) return
        _isRinging.value = false
        Log.d(TAG, "Stopped ringing")
        main.removeCallbacks(autoStop)
        main.removeCallbacks(torchBlink)

        player?.let { runCatching { it.stop() }; it.release() }
        player = null

        runCatching {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.cancel()
        }
        setTorch(context, false)

        previousAlarmVolume?.let { volume ->
            runCatching {
                (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
            }
        }
        previousAlarmVolume = null

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        reportToMac(false)
    }

    private fun setTorch(context: Context, on: Boolean) {
        val id = torchCameraId ?: return
        runCatching {
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on)
            torchOn = on
        }
    }

    private fun showNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ring My Phone", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shown while your Mac is ringing this phone"
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, RingStopReceiver::class.java), flags
        )
        val openIntent = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Your Mac is ringing this phone")
            .setContentText("Tap Stop to silence it")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(stopIntent)
            .setDeleteIntent(stopIntent)
            .setFullScreenIntent(openIntent, true)
            .addAction(0, "Stop", stopIntent)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private fun reportToMac(ringing: Boolean) {
        val message = JSONObject()
            .put("type", "ringState")
            .put("data", JSONObject().put("ringing", ringing))
            .toString()
        runCatching { WebSocketUtil.sendMessage(message) }
    }
}
