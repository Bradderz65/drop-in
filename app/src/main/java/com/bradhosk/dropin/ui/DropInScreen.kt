package com.bradhosk.dropin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bradhosk.dropin.model.PeerDevice

@Composable
fun DropInScreen(
    state: DropInUiState,
    onConnect: (PeerDevice) -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    localVideo: @Composable () -> Unit,
    remoteVideo: @Composable () -> Unit,
) {
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
                .padding(16.dp),
        ) {
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF13293D)),
                    ) {
                remoteVideo()
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .size(132.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF0F1E2A)),
                ) {
                    localVideo()
                }

                androidx.compose.animation.AnimatedVisibility(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp),
                    visible = true,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToggleFab(
                            enabled = state.isMicOn,
                            onClick = { onToggleMic(!state.isMicOn) },
                            activeIcon = { Icon(Icons.Rounded.Mic, contentDescription = "Mute", tint = Color.White) },
                            inactiveIcon = { Icon(Icons.Rounded.MicOff, contentDescription = "Unmute", tint = Color.White) },
                        )
                        ToggleFab(
                            enabled = state.isCameraOn,
                            onClick = { onToggleCamera(!state.isCameraOn) },
                            activeIcon = { Icon(Icons.Rounded.Videocam, contentDescription = "Disable camera", tint = Color.White) },
                            inactiveIcon = { Icon(Icons.Rounded.VideocamOff, contentDescription = "Enable camera", tint = Color.White) },
                        )
                        FloatingActionButton(
                            onClick = onHangUp,
                            containerColor = Color(0xFFFF6B6B),
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Rounded.CallEnd, contentDescription = "Hang up", tint = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Available on your local network",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.devices, key = { it.serviceName }) { peer ->
                    PeerCard(peer = peer, onConnect = { onConnect(peer) })
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
