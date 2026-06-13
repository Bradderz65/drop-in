package com.bradhosk.dropin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.bradhosk.dropin.model.PeerDevice

object DeviceCapability {
    const val CLASS_STANDARD = "standard"
    const val CLASS_LIMITED = "limited"

    fun isLimitedDevice(context: Context): Boolean {
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

    fun localDeviceClass(context: Context): String =
        if (isLimitedDevice(context)) CLASS_LIMITED else CLASS_STANDARD
}

fun PeerDevice.effectiveDeviceClass(): String {
    if (deviceClass != DeviceCapability.CLASS_STANDARD && deviceClass.isNotBlank()) {
        return deviceClass
    }
    val blob = "$serviceName $displayName".lowercase()
    if (blob.contains("echo") ||
        blob.contains("crown") ||
        blob.contains("show") ||
        blob.contains("fire tv") ||
        blob.contains("firetv")
    ) {
        return DeviceCapability.CLASS_LIMITED
    }
    return DeviceCapability.CLASS_STANDARD
}
