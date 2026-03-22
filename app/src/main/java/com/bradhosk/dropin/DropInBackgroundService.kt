package com.bradhosk.dropin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bradhosk.dropin.data.DropInRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DropInBackgroundService : Service() {
    private val logTag = "DropInApp"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: DropInRuntime
    private var lastOfferFingerprint: String? = null

    override fun onCreate() {
        super.onCreate()
        runtime = DropInRuntime.getInstance(this)
        createNotificationChannel()
        runCatching {
            startForeground(NOTIFICATION_ID, buildNotification())
        }.onFailure { error ->
            Log.e(logTag, "background service startForeground failed", error)
            stopSelf()
            return
        }
        runtime.start()

        serviceScope.launch {
            runtime.pendingOffer.collectLatest { offer ->
                if (offer == null) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, buildNotification())
                    return@collectLatest
                }
                val fingerprint = "${offer.from}:${offer.sdp.hashCode()}"
                if (fingerprint == lastOfferFingerprint) return@collectLatest
                lastOfferFingerprint = fingerprint
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        title = getString(R.string.notification_incoming_title),
                        text = getString(R.string.notification_incoming_text, offer.from.removePrefix("dropin-")),
                    ),
                )
                startActivity(
                    MainActivity.createLaunchIntent(this@DropInBackgroundService).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        serviceScope.coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(
        title: String = getString(R.string.notification_service_title),
        text: String = getString(R.string.notification_service_text),
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            MainActivity.createLaunchIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "dropin_background"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, DropInBackgroundService::class.java),
            )
        }
    }
}
