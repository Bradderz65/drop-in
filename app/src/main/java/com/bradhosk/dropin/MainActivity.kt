package com.bradhosk.dropin

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bradhosk.dropin.ui.DropInScreen
import com.bradhosk.dropin.ui.DropInViewModel
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {
    private val viewModel: DropInViewModel by viewModels()
    private var permissionsGranted = false
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
        maybeStartLocalMedia()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRequiredPermissions()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(),
            ) {
                val state by viewModel.uiState.collectAsState()
                DropInScreen(
                    state = state,
                    onConnect = viewModel::connectToPeer,
                    onToggleMic = viewModel::setMicEnabled,
                    onToggleCamera = viewModel::setCameraEnabled,
                    onHangUp = viewModel::hangUp,
                    localVideo = { VideoRenderer { renderer -> onLocalRendererCreated(renderer) } },
                    remoteVideo = { VideoRenderer { renderer -> onRemoteRendererCreated(renderer) } },
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
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
        onRendererCreated: (SurfaceViewRenderer) -> Unit,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceViewRenderer(context).also(onRendererCreated)
            },
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
}
