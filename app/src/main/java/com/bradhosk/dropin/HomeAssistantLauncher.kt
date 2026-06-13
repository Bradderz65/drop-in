package com.bradhosk.dropin

import android.app.Activity
import android.content.Intent

object HomeAssistantLauncher {
    const val PACKAGE_NAME = "io.homeassistant.companion.android"

    fun openAndBackground(activity: Activity): Boolean {
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        activity.startActivity(launchIntent)
        activity.moveTaskToBack(true)
        return true
    }
}
