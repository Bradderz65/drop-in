package com.bradhosk.dropin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bradhosk.dropin.model.PeerDevice
import kotlinx.coroutines.delay

// ── Shorthand palette access ────────────────────────────────
private val C = DropInColors

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
        color = C.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreen) 0.dp else 20.dp),
        ) {
            // ── Header (hidden in fullscreen) ────────────────
            if (!isFullscreen) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "drop in",
                    style = MaterialTheme.typography.headlineLarge,
                    color = C.textPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = C.textSecondary,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ── In-call video area ───────────────────────────
            if (state.isInCall) {
                Box(
                    modifier = if (isFullscreen) {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(0.dp))
                            .background(Color(0xFF111113))
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF111113))
                    },
                ) {
                    // Video modifiers
                    val fullScreenMod = Modifier
                        .matchParentSize()
                        .zIndex(0f)
                    val pipMod = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(120.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(C.surface)
                        .zIndex(1f)
                    val hiddenMod = Modifier
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
                        isFullscreen && remoteShouldBePrimary -> fullScreenMod
                        isFullscreen -> hiddenMod
                        remoteShouldBePrimary -> fullScreenMod
                        else -> pipMod
                    }
                    val localModifier = when {
                        isFullscreen && remoteShouldBePrimary -> hiddenMod
                        isFullscreen -> fullScreenMod
                        remoteShouldBePrimary -> pipMod
                        else -> fullScreenMod
                    }

                    remoteVideo(remoteModifier)
                    localVideo(localModifier)

                    // Tap to reveal controls
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

                    // ── Frosted control pill ─────────────────
                    androidx.compose.animation.AnimatedVisibility(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                        visible = areControlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Row(
                            modifier = Modifier
                                .background(
                                    color = Color(0xD91A1A1E),
                                    shape = RoundedCornerShape(50),
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ControlPill(
                                icon = if (state.isMicOn) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                                label = if (state.isMicOn) "Mute" else "Unmute",
                                isActive = state.isMicOn,
                                onClick = {
                                    revealControls()
                                    onToggleMic(!state.isMicOn)
                                },
                            )
                            ControlPill(
                                icon = if (state.isCameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                label = if (state.isCameraOn) "Cam off" else "Cam on",
                                isActive = state.isCameraOn,
                                onClick = {
                                    revealControls()
                                    onToggleCamera(!state.isCameraOn)
                                },
                            )
                            ControlPill(
                                icon = Icons.Rounded.Cameraswitch,
                                label = "Flip",
                                onClick = {
                                    revealControls()
                                    onSwitchCamera()
                                },
                            )
                            ControlPill(
                                icon = if (state.isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                                label = if (state.isSpeakerOn) "Earpiece" else "Speaker",
                                isActive = state.isSpeakerOn,
                                onClick = {
                                    revealControls()
                                    onToggleSpeaker(!state.isSpeakerOn)
                                },
                            )
                            ControlPill(
                                icon = Icons.Rounded.SwapHoriz,
                                label = "Swap",
                                onClick = {
                                    revealControls()
                                    onSwapViews()
                                },
                            )
                            ControlPill(
                                icon = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                label = if (isFullscreen) "Shrink" else "Expand",
                                onClick = {
                                    revealControls()
                                    onToggleFullscreen(!isFullscreen)
                                },
                            )
                            // Hang-up — danger accent
                            IconButton(
                                onClick = {
                                    revealControls()
                                    if (isFullscreen) onToggleFullscreen(false)
                                    onHangUp()
                                },
                                modifier = Modifier.size(42.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = C.danger,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(
                                    Icons.Rounded.CallEnd,
                                    contentDescription = "Hang up",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ── Peer list (hidden in fullscreen) ─────────────
            if (!isFullscreen) {
                if (state.isInCall) {
                    Spacer(modifier = Modifier.height(20.dp))
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Tailscale config card
                    item {
                        TailscaleCard(
                            draft = tailnetHostDraft,
                            onDraftChange = { tailnetHostDraft = it },
                            onSave = { onSaveTailnetHost(tailnetHostDraft) },
                            onDisable = {
                                tailnetHostDraft = ""
                                onSaveTailnetHost("")
                            },
                        )
                    }
                    // Section header
                    item {
                        Text(
                            text = "PEERS",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
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

// ── Tailscale config card ────────────────────────────────────
@Composable
private fun TailscaleCard(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onDisable: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(C.surface, RoundedCornerShape(16.dp))
            .border(1.dp, C.border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Tailscale Peer",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = C.textPrimary,
        )
        Text(
            text = "Save a Tailscale IP or hostname for quick connect.",
            style = MaterialTheme.typography.bodySmall,
            color = C.textSecondary,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Host or IP", color = C.textSecondary) },
            placeholder = { Text("100.x.y.z", color = C.textSecondary.copy(alpha = 0.5f)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.accent,
                unfocusedBorderColor = C.border,
                cursorColor = C.accent,
                focusedLabelColor = C.accent,
                focusedTextColor = C.textPrimary,
                unfocusedTextColor = C.textPrimary,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onSave,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.accent),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(C.accent.copy(alpha = 0.4f)),
                ),
            ) {
                Text("Save", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onDisable,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.textSecondary),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(C.border),
                ),
            ) {
                Text("Clear", fontSize = 13.sp)
            }
        }
    }
}

// ── Peer card ────────────────────────────────────────────────
@Composable
private fun PeerCard(
    peer: PeerDevice,
    onConnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(C.surface, RoundedCornerShape(14.dp))
            .border(1.dp, C.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Monogram avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(C.accentDim),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = peer.displayName.take(1).uppercase(),
                color = C.accent,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.displayName,
                color = C.textPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${peer.host}:${peer.port}",
                style = MaterialTheme.typography.bodySmall,
                color = C.textSecondary,
            )
        }
        OutlinedButton(
            onClick = onConnect,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = C.accent),
            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                brush = androidx.compose.ui.graphics.SolidColor(C.accent.copy(alpha = 0.4f)),
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("Connect", fontSize = 13.sp)
        }
    }
}

// ── Single control button inside the pill bar ────────────────
@Composable
private fun ControlPill(
    icon: ImageVector,
    label: String,
    isActive: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (isActive) Color.Transparent else C.controlOff,
            contentColor = Color.White,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
        )
    }
}
