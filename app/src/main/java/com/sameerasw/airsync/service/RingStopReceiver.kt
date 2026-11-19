package com.sameerasw.airsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sameerasw.airsync.utils.PhoneRinger

/** Stop button / tap / swipe on the "Your Mac is ringing this phone" notification. */
class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PhoneRinger.stop(context)
    }
}
