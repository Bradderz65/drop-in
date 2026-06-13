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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.bradhosk.dropin.DeviceCapability
import com.bradhosk.dropin.data.DropInRuntime
import com.bradhosk.dropin.data.IceCandidatePayload
import com.bradhosk.dropin.data.PeerSignalingClient
import com.bradhosk.dropin.data.SignalEnvelope
import com.bradhosk.dropin.data.SignalType
import com.bradhosk.dropin.webrtc.DropInManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class DropInBackgroundService : Service() {
    private val logTag = "DropInApp"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: DropInRuntime
    private var dropInManager: DropInManager? = null
    private val signalingClient = PeerSignalingClient()
    private var activePeerServiceName: String? = null
    private var isAnsweringOffer = false

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "background service onCreate")
        if (!enterForeground()) {
            stopSelf()
            return
        }

        runtime = DropInRuntime.getInstance(this)
        runtime.start()

        serviceScope.launch {
            runtime.incomingOffers.collect { offer ->
                Log.d(logTag, "background wake for incoming offer from=${offer.from}")
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(
                    INCOMING_NOTIFICATION_ID,
                    buildIncomingCallNotification(
                        title = getString(R.string.notification_incoming_title),
                        text = getString(R.string.notification_incoming_text, offer.from.removePrefix("dropin-")),
                    ),
                )
                if (!isAppVisible()) {
                    wakeAndOpenApp()
                } else {
                    Log.d(logTag, "incoming offer while app visible; skip wake/open")
                }
            }
        }

        serviceScope.launch {
            runtime.pendingOffer.collectLatest { offer ->
                if (offer == null) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(INCOMING_NOTIFICATION_ID)
                    notificationManager.notify(NOTIFICATION_ID, buildServiceNotification())
                    return@collectLatest
                }
                delay(AUTO_ANSWER_UI_GRACE_MS)
                if (runtime.pendingOffer.value != offer) return@collectLatest
                if (isAppVisible()) {
                    Log.d(logTag, "pending offer while app visible; foreground UI will answer")
                    return@collectLatest
                }
                runtime.consumePendingOffer()
                answerOfferInBackground(offer)
            }
        }

        serviceScope.launch {
            runtime.signals.collect { signal ->
                when (signal.type) {
                    SignalType.ICE -> {
                        if (signal.from != activePeerServiceName) return@collect
                        val candidate = signal.candidate ?: return@collect
                        managerOrNull()?.addIceCandidate(
                            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdpCandidate),
                        )
                    }
                    SignalType.HANGUP -> {
                        if (signal.from != activePeerServiceName) return@collect
                        Log.d(logTag, "background auto-answer hangup from=${signal.from}")
                        endBackgroundCall()
                    }
                    SignalType.OFFER,
                    SignalType.ANSWER,
                    -> Unit
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(logTag, "background service onStartCommand flags=$flags startId=$startId")
        enterForeground()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(logTag, "background service onTaskRemoved")
        start(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(logTag, "background service onDestroy")
        serviceScope.coroutineContext.cancel()
        endBackgroundCall()
        dropInManager?.release()
        dropInManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground(): Boolean {
        createNotificationChannel()
        return runCatching {
            startForeground(NOTIFICATION_ID, buildServiceNotification())
        }.onFailure { error ->
            Log.e(logTag, "background service startForeground failed", error)
        }.isSuccess
    }

    private fun buildServiceNotification(
        title: String = getString(R.string.notification_service_title),
        text: String = getString(R.string.notification_service_text),
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            MainActivity.createLaunchIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun buildIncomingCallNotification(
        title: String,
        text: String,
    ): Notification {
        val incomingIntent = PendingIntent.getActivity(
            this,
            1,
            MainActivity.createLaunchIntent(this).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, INCOMING_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(false)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(incomingIntent)
            .setFullScreenIntent(incomingIntent, true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val incomingChannel = NotificationChannel(
            INCOMING_CHANNEL_ID,
            "Incoming drop-ins",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming drop-in calls"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(serviceChannel)
        notificationManager.createNotificationChannel(incomingChannel)
    }

    private fun wakeAndOpenApp() {
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$packageName:incoming-drop-in",
            )
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(logTag, "wake lock acquired for incoming drop-in")
        }.onFailure { error ->
            Log.w(logTag, "wake for incoming drop-in failed", error)
        }

        runCatching {
            startActivity(
                MainActivity.createLaunchIntent(this).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
            Log.d(logTag, "started activity for incoming drop-in")
        }.onFailure { error ->
            Log.w(logTag, "open app for incoming drop-in failed", error)
        }
    }

    private fun isAppVisible(): Boolean {
        val lifecycleState = ProcessLifecycleOwner.get().lifecycle.currentState
        return lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }

    private fun managerOrNull(): DropInManager? = dropInManager

    private fun requireManager(): DropInManager {
        return dropInManager ?: DropInManager(this).also { manager ->
            dropInManager = manager
            manager.onIceCandidateDiscovered = { candidate ->
                activePeerServiceName?.let { target ->
                    val signal = candidate.toSignalEnvelope(target)
                    signalingClient.send(signal)
                    runtime.sendLocal(signal)
                }
            }
        }
    }

    private fun answerOfferInBackground(offer: SignalEnvelope) {
        if (isAnsweringOffer) {
            Log.d(logTag, "background auto-answer already in progress; ignoring offer from=${offer.from}")
            return
        }
        Log.d(logTag, "background auto-answer offer from=${offer.from} remote=${offer.remoteHost}")
        isAnsweringOffer = true
        activePeerServiceName = offer.from
        val manager = requireManager()
        manager.prepareForCall(offer.deviceClass ?: DeviceCapability.CLASS_STANDARD)
        manager.endCall()
        signalingClient.disconnect()

        val hasLocalCamera = manager.startLocalMedia()
        Log.d(logTag, "background auto-answer localCamera=$hasLocalCamera")
        if (!offer.remoteHost.isNullOrBlank()) {
            signalingClient.connect(offer.remoteHost, DEFAULT_SIGNALING_PORT)
        } else {
            Log.w(logTag, "background auto-answer no remote host for fallback signaling")
        }

        manager.createPeerConnection {
            Log.d(logTag, "background auto-answer connected peer=${offer.from}")
        }
        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offer.sdp.orEmpty())
        manager.setRemoteDescription(remoteOffer) {
            manager.createAnswer { answer ->
                Log.d(logTag, "background auto-answer sending answer to=${offer.from}")
                val response = SignalEnvelope(
                    type = SignalType.ANSWER,
                    from = runtime.localPeerId,
                    to = offer.from,
                    sdp = answer.description,
                    sdpType = answer.type.canonicalForm(),
                    deviceClass = manager.localDeviceClass(),
                )
                signalingClient.send(response)
                runtime.sendLocal(response)
                isAnsweringOffer = false
            }
        }
    }

    private fun endBackgroundCall() {
        activePeerServiceName = null
        isAnsweringOffer = false
        signalingClient.disconnect()
        dropInManager?.endCall()
    }

    private fun IceCandidate.toSignalEnvelope(target: String) = SignalEnvelope(
        type = SignalType.ICE,
        from = runtime.localPeerId,
        to = target,
        candidate = IceCandidatePayload(
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex,
            sdpCandidate = sdp,
        ),
    )

    private fun SessionDescription.Type.canonicalForm(): String = when (this) {
        SessionDescription.Type.OFFER -> "offer"
        SessionDescription.Type.ANSWER -> "answer"
        SessionDescription.Type.PRANSWER -> "pranswer"
        SessionDescription.Type.ROLLBACK -> "rollback"
    }

    companion object {
        private const val CHANNEL_ID = "dropin_background"
        private const val INCOMING_CHANNEL_ID = "dropin_incoming_calls"
        private const val NOTIFICATION_ID = 1001
        private const val INCOMING_NOTIFICATION_ID = 1002
        private const val AUTO_ANSWER_UI_GRACE_MS = 300L
        private const val DEFAULT_SIGNALING_PORT = 8989
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, DropInBackgroundService::class.java),
            )
        }
    }
}
