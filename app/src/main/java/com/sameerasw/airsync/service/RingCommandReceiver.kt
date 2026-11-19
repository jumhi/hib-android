package com.sameerasw.airsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sameerasw.airsync.utils.PhoneRinger

/**
 * ADB fallback for Ring My Phone, used by the Mac when the normal Wi-Fi/Bluetooth link is down.
 * Protected by android.permission.DUMP in the manifest, which only the adb shell and the system
 * hold, so other apps on the phone can't trigger it.
 */
class RingCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RING -> PhoneRinger.start(context)
            ACTION_STOP -> PhoneRinger.stop(context)
        }
    }

    companion object {
        const val ACTION_RING = "com.sameerasw.airsync.RING_PHONE"
        const val ACTION_STOP = "com.sameerasw.airsync.STOP_RING_PHONE"
    }
}
