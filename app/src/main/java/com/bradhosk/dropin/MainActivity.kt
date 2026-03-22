package com.bradhosk.dropin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bradhosk.dropin.ui.DropInScreen
import com.bradhosk.dropin.ui.DropInTheme
import com.bradhosk.dropin.ui.DropInViewModel
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {
    companion object {
        private const val KEY_FULLSCREEN = "fullscreen"

        fun createLaunchIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }

    private val viewModel: DropInViewModel by viewModels()
    private var permissionsGranted = false
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var isFullscreen by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
        maybeStartLocalMedia()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isFullscreen = savedInstanceState?.getBoolean(KEY_FULLSCREEN) ?: false
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#0E0E10")),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#0E0E10")),
        )
        DropInBackgroundService.start(this)
        requestRequiredPermissions()
        applyFullscreen(isFullscreen)

        setContent {
            DropInTheme {
                val state by viewModel.uiState.collectAsState()
                LaunchedEffect(state.isInCall) {
                    if (!state.isInCall) {
                        exitFullscreenToPortrait()
                    }
                }
                DropInScreen(
                    state = state,
                    isFullscreen = isFullscreen,
                    onConnect = viewModel::connectToPeer,
                    onToggleMic = viewModel::setMicEnabled,
                    onToggleCamera = viewModel::setCameraEnabled,
                    onToggleSpeaker = viewModel::setSpeakerEnabled,
                    onSwitchCamera = viewModel::switchCamera,
                    onSwapViews = viewModel::swapVideoViews,
                    onSaveTailnetHost = viewModel::setSavedTailnetHost,
                    onToggleFullscreen = ::applyFullscreen,
                    onRefresh = viewModel::refreshPeers,
                    onHangUp = viewModel::hangUp,
                    localVideo = { modifier -> VideoRenderer("local", modifier) { renderer -> onLocalRendererCreated(renderer) } },
                    remoteVideo = { modifier -> VideoRenderer("remote", modifier) { renderer -> onRemoteRendererCreated(renderer) } },
                )
            }
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
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun maybeStartLocalMedia() {
        val local = localRenderer
        val remote = remoteRenderer
        if (!permissionsGranted || local == null || remote == null) return
        viewModel.dropInManager.initializeRenderers(local, remote)
        viewModel.startLocalMedia()
    }

    @Composable
    private fun VideoRenderer(
        tag: String,
        modifier: Modifier,
        onRendererCreated: (SurfaceViewRenderer) -> Unit,
    ) {
        val context = LocalContext.current
        val renderer = remember(tag) {
            SurfaceViewRenderer(context).also(onRendererCreated)
        }
        AndroidView(
            modifier = modifier,
            factory = { renderer },
            update = { onRendererCreated(it) },
        )
    }

    private fun onLocalRendererCreated(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        maybeStartLocalMedia()
    }

    private fun onRemoteRendererCreated(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        maybeStartLocalMedia()
    }

    private fun applyFullscreen(enabled: Boolean) {
        if (!enabled) {
            exitFullscreenToPortrait()
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

    private fun exitFullscreenToPortrait() {
        isFullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
