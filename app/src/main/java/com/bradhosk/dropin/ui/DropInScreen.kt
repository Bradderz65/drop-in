package com.bradhosk.dropin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SignalCellular4Bar
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SignalCellularAlt1Bar
import androidx.compose.material.icons.rounded.SignalCellularAlt2Bar
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bradhosk.dropin.model.PeerDevice
import kotlinx.coroutines.delay

// ── Shorthand palette ────────────────────────────────────────
private val C = DropInColors

// ── Helpers ──────────────────────────────────────────────────
private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

private fun ConnectionQuality.color(): Color = when (this) {
    ConnectionQuality.EXCELLENT -> Color(0xFF00E5A0)
    ConnectionQuality.GOOD      -> Color(0xFF00E5A0)
    ConnectionQuality.FAIR      -> Color(0xFFFFC536)
    ConnectionQuality.POOR      -> Color(0xFFFF4D5A)
    ConnectionQuality.UNKNOWN   -> Color(0xFF7A7A82)
}

private fun ConnectionQuality.icon(): ImageVector = when (this) {
    ConnectionQuality.EXCELLENT -> Icons.Rounded.SignalCellular4Bar
    ConnectionQuality.GOOD      -> Icons.Rounded.SignalCellularAlt
    ConnectionQuality.FAIR      -> Icons.Rounded.SignalCellularAlt2Bar
    ConnectionQuality.POOR      -> Icons.Rounded.SignalCellularAlt1Bar
    ConnectionQuality.UNKNOWN   -> Icons.Rounded.NetworkCheck
}

// ── Main screen ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
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
    onRefresh: () -> Unit = {},
    localVideo: @Composable (Modifier) -> Unit,
    remoteVideo: @Composable (Modifier) -> Unit,
) {
    var areControlsVisible by rememberSaveable(state.isInCall) { mutableStateOf(true) }
    var controlsInteractionCount by remember { mutableIntStateOf(0) }
    var tailnetHostDraft by rememberSaveable { mutableStateOf(state.savedTailnetHost) }
    val haptic = LocalHapticFeedback.current

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
            // ── Header ───────────────────────────────────────
            if (!isFullscreen) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "drop in",
                        style = MaterialTheme.typography.headlineLarge,
                        color = C.textPrimary,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    StatusDot(state = state)
                }
                Spacer(modifier = Modifier.height(2.dp))

                // Status line (show timer if in call)
                if (state.isInCall) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = C.textSecondary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = formatDuration(state.callDurationSeconds),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = C.accent,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = state.connectionQuality.icon(),
                            contentDescription = "Connection quality",
                            modifier = Modifier.size(16.dp),
                            tint = state.connectionQuality.color(),
                        )
                    }
                } else {
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = C.textSecondary,
                    )
                }
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

                    // ── Fullscreen quality + timer overlay ───
                    if (isFullscreen) {
                        androidx.compose.animation.AnimatedVisibility(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp),
                            visible = areControlsVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .background(Color(0xD91A1A1E), RoundedCornerShape(50))
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = formatDuration(state.callDurationSeconds),
                                    color = C.accent,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Icon(
                                    imageVector = state.connectionQuality.icon(),
                                    contentDescription = "Quality",
                                    modifier = Modifier.size(16.dp),
                                    tint = state.connectionQuality.color(),
                                )
                            }
                        }
                    }

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
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    revealControls()
                                    onToggleMic(!state.isMicOn)
                                },
                            )
                            ControlPill(
                                icon = if (state.isCameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                label = if (state.isCameraOn) "Cam off" else "Cam on",
                                isActive = state.isCameraOn,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    revealControls()
                                    onToggleCamera(!state.isCameraOn)
                                },
                            )
                            ControlPill(
                                icon = Icons.Rounded.Cameraswitch,
                                label = "Flip",
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    revealControls()
                                    onSwitchCamera()
                                },
                            )
                            ControlPill(
                                icon = if (state.isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                                label = if (state.isSpeakerOn) "Earpiece" else "Speaker",
                                isActive = state.isSpeakerOn,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    revealControls()
                                    onToggleSpeaker(!state.isSpeakerOn)
                                },
                            )
                            ControlPill(
                                icon = Icons.Rounded.SwapHoriz,
                                label = "Swap",
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    revealControls()
                                    onSwapViews()
                                },
                            )
                            ControlPill(
                                icon = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                label = if (isFullscreen) "Shrink" else "Expand",
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    revealControls()
                                    onToggleFullscreen(!isFullscreen)
                                },
                            )
                            // Hang-up
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Tailscale config card
                        item {
                            TailscaleCard(
                                draft = tailnetHostDraft,
                                onDraftChange = { tailnetHostDraft = it },
                                onSave = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSaveTailnetHost(tailnetHostDraft)
                                },
                                onDisable = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    tailnetHostDraft = ""
                                    onSaveTailnetHost("")
                                },
                            )
                        }

                        // ── Recent peers ─────────────────────
                        if (state.recentPeers.isNotEmpty() && !state.isInCall) {
                            item {
                                Text(
                                    text = "RECENT",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            itemsIndexed(state.recentPeers, key = { _, p -> "recent-${p.serviceName}" }) { index, peer ->
                                StaggeredFadeIn(index = index) {
                                    PeerCard(
                                        peer = peer,
                                        onConnect = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onConnect(peer)
                                        },
                                        isMuted = true, // visually subdued
                                    )
                                }
                            }
                        }

                        // ── Section header ───────────────────
                        item {
                            Text(
                                text = "PEERS",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }

                        // ── Empty state ──────────────────────
                        if (state.devices.isEmpty()) {
                            item {
                                EmptyPeerState()
                            }
                        }

                        // ── Peer cards with staggered animation
                        itemsIndexed(state.devices, key = { _, p -> p.serviceName }) { index, peer ->
                            StaggeredFadeIn(index = index) {
                                PeerCard(
                                    peer = peer,
                                    onConnect = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onConnect(peer)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Status indicator dot ─────────────────────────────────────
@Composable
private fun StatusDot(state: DropInUiState) {
    val dotColor = when {
        state.isInCall -> C.accent
        state.status.contains("Connecting", ignoreCase = true) -> Color(0xFFFFC536) // amber
        state.status.contains("failed", ignoreCase = true) ||
                state.status.contains("Could not", ignoreCase = true) -> C.danger
        else -> C.accent
    }

    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Box(
        modifier = Modifier
            .size(10.dp)
            .scale(pulseScale)
            .alpha(pulseAlpha)
            .clip(CircleShape)
            .background(dotColor),
    )
}

// ── Empty state ──────────────────────────────────────────────
@Composable
private fun EmptyPeerState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.SearchOff,
            contentDescription = null,
            tint = C.textSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "No devices nearby",
            style = MaterialTheme.typography.bodyLarge,
            color = C.textSecondary,
        )
        Text(
            text = "Pull down to refresh",
            style = MaterialTheme.typography.bodySmall,
            color = C.textSecondary.copy(alpha = 0.5f),
        )
    }
}

// ── Staggered fade-in animation ──────────────────────────────
@Composable
private fun StaggeredFadeIn(
    index: Int,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 60L) // stagger by 60ms per card
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "staggerAlpha",
    )
    Box(modifier = Modifier.alpha(alpha)) {
        content()
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
    isMuted: Boolean = false,
) {
    val contentAlpha = if (isMuted) 0.6f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(contentAlpha)
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

// ── Single control button ────────────────────────────────────
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
