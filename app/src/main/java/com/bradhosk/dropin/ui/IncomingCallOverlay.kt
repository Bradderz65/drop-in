package com.bradhosk.dropin.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bradhosk.dropin.model.PeerDevice

private val C = DropInColors

/**
 * Covers the whole screen while someone is calling. The call is not connected, and this
 * device's camera and microphone are not shared, until Answer is pressed.
 *
 * Built for someone who may not be comfortable with touchscreens: one question, the caller's
 * name in large type, and two big buttons set well apart so the wrong one is hard to hit.
 */
@Composable
fun IncomingCallOverlay(
    caller: PeerDevice,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val pulse = rememberInfiniteTransition(label = "ring")
    val avatarScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "avatar",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(C.background)
            // Swallow touches so nothing underneath can be pressed by accident.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .scale(avatarScale)
                    .clip(CircleShape)
                    .background(C.accentDim),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = caller.displayName.take(1).uppercase(),
                    color = C.accent,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = caller.displayName,
                color = C.textPrimary,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "is calling you",
                color = C.textSecondary,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                CallChoiceButton(
                    label = "Decline",
                    icon = Icons.Rounded.CallEnd,
                    container = C.danger,
                    content = Color.White,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDecline()
                    },
                )
                CallChoiceButton(
                    label = "Answer",
                    icon = Icons.Rounded.Call,
                    container = C.accent,
                    content = C.background,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAnswer()
                    },
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "They can't see or hear you until you press Answer",
                color = C.textSecondary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Shown from the moment a call is placed (or answered) until it connects, so there is always
 * one obvious way to back out. Without it a ringing call had no cancel control at all.
 */
@Composable
fun CallProgressOverlay(
    peer: PeerDevice,
    status: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(C.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(112.dp).clip(CircleShape).background(C.accentDim),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = peer.displayName.take(1).uppercase(),
                    color = C.accent,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = peer.displayName,
                color = C.textPrimary,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = status,
                color = C.textSecondary,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            CallChoiceButton(
                label = "Cancel",
                icon = Icons.Rounded.CallEnd,
                container = C.danger,
                content = Color.White,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCancel()
                },
            )
        }
    }
}

@Composable
private fun CallChoiceButton(
    label: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 132.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(container)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(vertical = 20.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
