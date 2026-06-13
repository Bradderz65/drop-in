package com.bradhosk.dropin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DropInBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d("DropInApp", "boot completed; starting background service")
        DropInBackgroundService.start(context)
    }
}
