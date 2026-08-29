package com.bradhosk.dropin

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bradhosk.dropin.ui.CallVideoHost
import com.bradhosk.dropin.ui.DropInScreen
import com.bradhosk.dropin.ui.DropInTheme
import com.bradhosk.dropin.ui.DropInViewModel

class MainActivity : ComponentActivity() {
    companion object {
        private const val KEY_FULLSCREEN = "fullscreen"

        fun createLaunchIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }

    private val viewModel: DropInViewModel by viewModels()
    private lateinit var videoHost: CallVideoHost
    private var permissionsGranted = false
    private var localMediaStarted = false
    private var isFullscreen by mutableStateOf(false)
    private val lockLandscape: Boolean by lazy { DeviceOrientation.shouldLockLandscape(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        permissionsGranted = permissions.values.all { it } || hasRequiredPermissions()
        if (permissionsGranted) {
            viewModel.refreshPeers()
        }
        maybeStartLocalMedia()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockscreen()
        isFullscreen = savedInstanceState?.getBoolean(KEY_FULLSCREEN) ?: false
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#0E0E10")),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#0E0E10")),
        )
        if (lockLandscape) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            applyImmersiveNavigation()
        }
        DropInBackgroundService.start(this)
        requestRequiredPermissions()
        applyOrientationLock()
        applyFullscreen(isFullscreen)

        videoHost = CallVideoHost(this) { local, remote ->
            viewModel.dropInManager.initializeRenderers(local, remote)
            maybeStartLocalMedia()
        }

        setContent {
            DropInTheme {
                val state by viewModel.uiState.collectAsState()
                LaunchedEffect(state.isInCall) {
                    if (!state.isInCall) {
                        exitFullscreenUi()
                    } else {
                        maybeStartLocalMedia()
                    }
                }
                DropInScreen(
                    state = state,
                    callMetrics = viewModel.callMetrics,
                    isFullscreen = isFullscreen,
                    onConnect = viewModel::connectToPeer,
                    onToggleMic = viewModel::setMicEnabled,
                    onToggleCamera = viewModel::setCameraEnabled,
                    onToggleSpeaker = viewModel::setSpeakerEnabled,
                    onSwitchCamera = viewModel::switchCamera,
                    onSwapViews = viewModel::swapVideoViews,
                    onSaveTailnetHost = viewModel::setSavedTailnetHost,
                    onSaveTailnetRegistryUrl = viewModel::setTailnetRegistryUrl,
                    onToggleFullscreen = ::applyFullscreen,
                    onRefresh = viewModel::refreshPeers,
                    onHangUp = { viewModel.hangUp() },
                    onOpenHomeAssistant = ::openHomeAssistant,
                    localVideo = { modifier -> videoHost.Local(modifier) },
                    remoteVideo = { modifier -> videoHost.Remote(modifier) },
                )
            }
        }
    }

    override fun onDestroy() {
        if (::videoHost.isInitialized) {
            viewModel.dropInManager.detachRenderers()
            videoHost.release()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        showOverLockscreen()
        applyOrientationLock()
        if (lockLandscape && !isFullscreen) {
            applyImmersiveNavigation()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_FULLSCREEN, isFullscreen)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) {
            applyFullscreen(true)
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = requiredPermissions()
        permissionsGranted = permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (permissionsGranted) {
            maybeStartLocalMedia()
            return
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): List<String> =
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    private fun maybeStartLocalMedia() {
        if (!permissionsGranted) return
        if (!localMediaStarted) {
            localMediaStarted = true
            viewModel.startLocalMedia()
        }
    }

    private fun applyFullscreen(enabled: Boolean) {
        if (!enabled) {
            exitFullscreenUi()
            return
        }
        isFullscreen = enabled
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitFullscreenUi() {
        isFullscreen = false
        applyOrientationLock()
        if (lockLandscape) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            applyImmersiveNavigation()
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun applyImmersiveNavigation() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
            show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun applyOrientationLock() {
        requestedOrientation = if (lockLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun showOverLockscreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        keyguardManager?.requestDismissKeyguard(this, null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun openHomeAssistant() {
        if (HomeAssistantLauncher.openAndBackground(this)) return
        Toast.makeText(
            this,
            "Home Assistant app is not installed",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
