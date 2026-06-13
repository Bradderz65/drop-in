package com.bradhosk.dropin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object DeviceOrientation {
    fun shouldLockLandscape(context: Context): Boolean {
        val model = Build.MODEL.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val device = Build.DEVICE.orEmpty()
        if (model.contains("echo", ignoreCase = true)) return true
        if (product.contains("crown", ignoreCase = true)) return true
        if (device.contains("crown", ignoreCase = true)) return true
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true

        val configuration = context.resources.configuration
        return configuration.smallestScreenWidthDp >= 480 &&
            configuration.screenWidthDp >= configuration.screenHeightDp
    }
}
