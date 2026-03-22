package com.bradhosk.dropin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bradhosk.dropin.model.PeerDevice
import kotlinx.coroutines.delay

@Composable
fun DropInScreen(
    state: DropInUiState,
    isFullscreen: Boolean,
    onConnect: (PeerDevice) -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onToggleSpeaker: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onSwapViews: () -> Unit,
    onSaveTailnetHost: (String) -> Unit,
    onToggleFullscreen: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    localVideo: @Composable (Modifier) -> Unit,
    remoteVideo: @Composable (Modifier) -> Unit,
) {
    var areControlsVisible by rememberSaveable(state.isInCall) { mutableStateOf(true) }
    var controlsInteractionCount by remember { mutableIntStateOf(0) }
    var tailnetHostDraft by rememberSaveable { mutableStateOf(state.savedTailnetHost) }

    LaunchedEffect(state.savedTailnetHost) {
        tailnetHostDraft = state.savedTailnetHost
    }

    fun revealControls() {
        areControlsVisible = true
        controlsInteractionCount++
    }

    LaunchedEffect(state.isInCall) {
        if (!state.isInCall) {
            areControlsVisible = true
        } else {
            revealControls()
        }
    }

    LaunchedEffect(state.isInCall, areControlsVisible, controlsInteractionCount) {
        if (!state.isInCall || !areControlsVisible) return@LaunchedEffect
        delay(5_000)
        areControlsVisible = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF06131F),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF06131F), Color(0xFF0A2235), Color(0xFF102336)),
                    ),
                )
                .padding(if (isFullscreen) 0.dp else 16.dp),
        ) {
            if (!isFullscreen) {
                Text(
                    text = "Drop In",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF91A3B7),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (state.isInCall) {
                Box(
                    modifier = if (isFullscreen) {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(0.dp))
                            .background(Color(0xFF13293D))
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color(0xFF13293D))
                    },
                ) {
                    val fullScreenModifier = Modifier
                        .matchParentSize()
                        .zIndex(0f)
                    val pipModifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .size(132.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF0F1E2A))
                        .zIndex(1f)
                    val hiddenModifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(1.dp)
                        .size(1.dp)
                        .zIndex(-1f)
                    val remoteShouldBePrimary = if (isFullscreen) {
                        state.hasRemoteVideo
                    } else {
                        state.isRemotePrimary
                    }
                    val remoteModifier = when {
                        isFullscreen && remoteShouldBePrimary -> fullScreenModifier
                        isFullscreen -> hiddenModifier
                        remoteShouldBePrimary -> fullScreenModifier
                        else -> pipModifier
                    }
                    val localModifier = when {
                        isFullscreen && remoteShouldBePrimary -> hiddenModifier
                        isFullscreen -> fullScreenModifier
                        remoteShouldBePrimary -> pipModifier
                        else -> fullScreenModifier
                    }

                    remoteVideo(remoteModifier)
                    localVideo(localModifier)

                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(state.isInCall) {
                                detectTapGestures(
                                    onTap = {
                                        if (state.isInCall) {
                                            revealControls()
                                        }
                                    },
                                )
                            },
                    )

                    androidx.compose.animation.AnimatedVisibility(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 18.dp),
                        visible = areControlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ToggleFab(
                                    enabled = state.isMicOn,
                                    onClick = {
                                        revealControls()
                                        onToggleMic(!state.isMicOn)
                                    },
                                    activeIcon = { Icon(Icons.Rounded.Mic, contentDescription = "Mute", tint = Color.White) },
                                    inactiveIcon = { Icon(Icons.Rounded.MicOff, contentDescription = "Unmute", tint = Color.White) },
                                )
                                ToggleFab(
                                    enabled = state.isCameraOn,
                                    onClick = {
                                        revealControls()
                                        onToggleCamera(!state.isCameraOn)
                                    },
                                    activeIcon = { Icon(Icons.Rounded.Videocam, contentDescription = "Disable camera", tint = Color.White) },
                                    inactiveIcon = { Icon(Icons.Rounded.VideocamOff, contentDescription = "Enable camera", tint = Color.White) },
                                )
                                FloatingActionButton(
                                    onClick = {
                                        revealControls()
                                        onSwitchCamera()
                                    },
                                    containerColor = Color(0xCC102336),
                                    shape = CircleShape,
                                ) {
                                    Icon(
                                        Icons.Rounded.Cameraswitch,
                                        contentDescription = if (state.isUsingFrontCamera) "Switch to back camera" else "Switch to front camera",
                                        tint = Color.White,
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FloatingActionButton(
                                    onClick = {
                                        revealControls()
                                        onSwapViews()
                                    },
                                    containerColor = Color(0xCC102336),
                                    shape = CircleShape,
                                ) {
                                    Icon(Icons.Rounded.SwapHoriz, contentDescription = "Swap video views", tint = Color.White)
                                }
                                ToggleFab(
                                    enabled = state.isSpeakerOn,
                                    onClick = {
                                        revealControls()
                                        onToggleSpeaker(!state.isSpeakerOn)
                                    },
                                    activeIcon = { Icon(Icons.Rounded.VolumeUp, contentDescription = "Use earpiece", tint = Color.White) },
                                    inactiveIcon = { Icon(Icons.Rounded.VolumeOff, contentDescription = "Use speaker", tint = Color.White) },
                                )
                                FloatingActionButton(
                                    onClick = {
                                        revealControls()
                                        onToggleFullscreen(!isFullscreen)
                                    },
                                    containerColor = Color(0xCC102336),
                                    shape = CircleShape,
                                ) {
                                    Icon(
                                        imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                        contentDescription = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
                                        tint = Color.White,
                                    )
                                }
                                FloatingActionButton(
                                    onClick = {
                                        revealControls()
                                        if (isFullscreen) {
                                            onToggleFullscreen(false)
                                        }
                                        onHangUp()
                                    },
                                    containerColor = Color(0xFFFF6B6B),
                                    shape = CircleShape,
                                ) {
                                    Icon(Icons.Rounded.CallEnd, contentDescription = "Hang up", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            if (!isFullscreen) {
                if (state.isInCall) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xCC18324A)),
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "Saved Tailscale Peer",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Save one Tailscale IP or hostname and it will appear in the peer list for quick connect.",
                                    color = Color(0xFF91A3B7),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedTextField(
                                    value = tailnetHostDraft,
                                    onValueChange = { tailnetHostDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Tailscale host or IP") },
                                    placeholder = { Text("100.x.y.z or machine-name") },
                                    singleLine = true,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { onSaveTailnetHost(tailnetHostDraft) }) {
                                        Text("Save")
                                    }
                                    Button(onClick = {
                                        tailnetHostDraft = ""
                                        onSaveTailnetHost("")
                                    }) {
                                        Text("Disable")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            text = "Available peers",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(state.devices, key = { it.serviceName }) { peer ->
                        PeerCard(peer = peer, onConnect = { onConnect(peer) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: PeerDevice,
    onConnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xCC18324A)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF39D98A)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = peer.displayName.take(1).uppercase(),
                    color = Color(0xFF062014),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("${peer.host}:${peer.port}", color = Color(0xFF91A3B7))
            }
            Button(onClick = onConnect) {
                Icon(Icons.Rounded.Call, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Drop in")
            }
        }
    }
}

@Composable
private fun ToggleFab(
    enabled: Boolean,
    onClick: () -> Unit,
    activeIcon: @Composable () -> Unit,
    inactiveIcon: @Composable () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (enabled) Color(0xCC102336) else Color(0xCC3A1B24),
        shape = CircleShape,
    ) {
        if (enabled) activeIcon() else inactiveIcon()
    }
}
