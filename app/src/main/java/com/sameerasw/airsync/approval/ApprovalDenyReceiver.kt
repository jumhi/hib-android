package com.sameerasw.airsync.approval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Deny" action on the Mac approval notification. Not exported. */
class ApprovalDenyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(PhoneApprovalManager.EXTRA_ID) ?: return
        PhoneApprovalManager.deny(context, id)
    }
}
