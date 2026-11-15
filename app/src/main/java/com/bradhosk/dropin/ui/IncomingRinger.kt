package com.bradhosk.dropin.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log

/**
 * Plays the device's ringtone on a loop while an incoming call waits for Answer or Decline.
 * It uses the ringtone audio usage, so a phone set to silent stays silent.
 */
class IncomingRinger(context: Context) {
    private val appContext = context.applicationContext
    private var ringtone: Ringtone? = null

    fun start() {
        stop()
        runCatching {
            // Devices without telephony (tablets, smart displays) may have no ringtone set.
            val uri = listOf(RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_ALARM)
                .firstNotNullOfOrNull { type -> RingtoneManager.getActualDefaultRingtoneUri(appContext, type) }
                ?: return
            ringtone = RingtoneManager.getRingtone(appContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
        }.onFailure { error ->
            Log.w("DropInApp", "could not play ringtone", error)
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }
}
