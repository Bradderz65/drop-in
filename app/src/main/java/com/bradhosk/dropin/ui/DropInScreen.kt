package com.bradhosk.dropin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SignalCellular4Bar
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SignalCellularAlt1Bar
import androidx.compose.material.icons.rounded.SignalCellularAlt2Bar
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.bradhosk.dropin.DeviceOrientation
import com.bradhosk.dropin.model.PeerDevice
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val C = DropInColors

private enum class HomeTab { Peers, Network }

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

private fun ConnectionQuality.color(): Color = when (this) {
    ConnectionQuality.EXCELLENT -> Color(0xFF00E5A0)
    ConnectionQuality.GOOD -> Color(0xFF00E5A0)
    ConnectionQuality.FAIR -> Color(0xFFFFC536)
    ConnectionQuality.POOR -> Color(0xFFFF4D5A)
    ConnectionQuality.UNKNOWN -> Color(0xFF7A7A82)
}

private fun ConnectionQuality.icon(): ImageVector = when (this) {
    ConnectionQuality.EXCELLENT -> Icons.Rounded.SignalCellular4Bar
    ConnectionQuality.GOOD -> Icons.Rounded.SignalCellularAlt
    ConnectionQuality.FAIR -> Icons.Rounded.SignalCellularAlt2Bar
    ConnectionQuality.POOR -> Icons.Rounded.SignalCellularAlt1Bar
    ConnectionQuality.UNKNOWN -> Icons.Rounded.NetworkCheck
}

@Composable
private fun isWideLayout(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp > configuration.screenHeightDp ||
        configuration.screenWidthDp >= 720
}

@Composable
private fun scrollBottomPadding(): Dp {
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val lockLandscape = DeviceOrientation.shouldLockLandscape(LocalContext.current)
    val minBottom = if (lockLandscape) 56.dp else 16.dp
    return safeBottom.coerceAtLeast(minBottom) + 24.dp
}

@Composable
private fun CallDurationText(
    callMetrics: StateFlow<CallMetrics>,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    fontWeight: FontWeight? = null,
) {
    val metrics by callMetrics.collectAsState()
    Text(
        text = formatDuration(metrics.durationSeconds),
        style = style,
        color = color,
        fontWeight = fontWeight,
    )
}

@Composable
private fun CallQualityIcon(
    callMetrics: StateFlow<CallMetrics>,
    modifier: Modifier = Modifier,
) {
    val metrics by callMetrics.collectAsState()
    Icon(
        imageVector = metrics.connectionQuality.icon(),
        contentDescription = "Connection quality",
        modifier = modifier,
        tint = metrics.connectionQuality.color(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropInScreen(
    state: DropInUiState,
    callMetrics: StateFlow<CallMetrics>,
    isFullscreen: Boolean,
    onConnect: (PeerDevice) -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onToggleSpeaker: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onSwapViews: () -> Unit,
    onSaveTailnetHost: (String) -> Unit,
    onSaveTailnetRegistryUrl: (String) -> Unit,
    onToggleFullscreen: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    onRefresh: () -> Unit = {},
    onOpenHomeAssistant: () -> Unit = {},
    localVideo: @Composable (Modifier) -> Unit,
    remoteVideo: @Composable (Modifier) -> Unit,
) {
    var areControlsVisible by rememberSaveable(state.isInCall) { mutableStateOf(true) }
    var controlsInteractionCount by remember { mutableIntStateOf(0) }
    var tailnetHostDraft by rememberSaveable { mutableStateOf(state.savedTailnetHost) }
    var tailnetRegistryDraft by rememberSaveable { mutableStateOf(state.tailnetRegistryUrl) }
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Peers) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val wideLayout = isWideLayout()
    val lockLandscape = remember(context) { DeviceOrientation.shouldLockLandscape(context) }
    val pinControls = (lockLandscape || wideLayout) && !(isFullscreen && state.isInCall)

    LaunchedEffect(state.savedTailnetHost) { tailnetHostDraft = state.savedTailnetHost }
    LaunchedEffect(state.tailnetRegistryUrl) { tailnetRegistryDraft = state.tailnetRegistryUrl }

    fun revealControls() {
        areControlsVisible = true
        controlsInteractionCount++
    }

    LaunchedEffect(state.isInCall) {
        areControlsVisible = true
        if (state.isInCall) revealControls()
    }

    LaunchedEffect(state.isInCall, areControlsVisible, controlsInteractionCount, pinControls) {
        if (!state.isInCall || !areControlsVisible || pinControls) return@LaunchedEffect
        delay(5_000)
        areControlsVisible = false
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = C.background,
    ) {
        when {
            wideLayout -> {
                var showViewOptionsSheet by rememberSaveable { mutableStateOf(false) }
                var showNetworkSheet by rememberSaveable { mutableStateOf(false) }
                val viewOptionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val networkSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                if (isFullscreen && state.isInCall) {
                    InCallVideoBox(
                        modifier = Modifier.fillMaxSize(),
                        state = state,
                        callMetrics = callMetrics,
                        isFullscreen = true,
                        overlayControls = true,
                        accessibleTablet = true,
                        areControlsVisible = areControlsVisible,
                        onRevealControls = { revealControls() },
                        onToggleMic = onToggleMic,
                        onToggleCamera = onToggleCamera,
                        onToggleSpeaker = onToggleSpeaker,
                        onSwitchCamera = onSwitchCamera,
                        onSwapViews = onSwapViews,
                        onToggleFullscreen = onToggleFullscreen,
                        onHangUp = onHangUp,
                        onViewMore = { showViewOptionsSheet = true },
                        localVideo = localVideo,
                        remoteVideo = remoteVideo,
                    )
                } else {
                    TabletDropInLayout(
                        state = state,
                        callMetrics = callMetrics,
                        onShowViewOptionsSheet = { showViewOptionsSheet = true },
                        onShowNetworkSheet = { showNetworkSheet = true },
                        areControlsVisible = areControlsVisible,
                        onRevealControls = { revealControls() },
                        onConnect = onConnect,
                        onRefresh = onRefresh,
                        onToggleMic = onToggleMic,
                        onToggleCamera = onToggleCamera,
                        onToggleSpeaker = onToggleSpeaker,
                        onSwitchCamera = onSwitchCamera,
                        onSwapViews = onSwapViews,
                        onToggleFullscreen = onToggleFullscreen,
                        onHangUp = onHangUp,
                        onOpenHomeAssistant = onOpenHomeAssistant,
                        localVideo = localVideo,
                        remoteVideo = remoteVideo,
                    )
                }

                if (showViewOptionsSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showViewOptionsSheet = false },
                        sheetState = viewOptionsSheetState,
                        containerColor = C.surface,
                    ) {
                        CallViewOptionsSheet(
                            onSwitchCamera = onSwitchCamera,
                            onSwapViews = onSwapViews,
                            onDismiss = { showViewOptionsSheet = false },
                        )
                    }
                }
                if (showNetworkSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showNetworkSheet = false },
                        sheetState = networkSheetState,
                        containerColor = C.surface,
                    ) {
                        NetworkSettingsSheet(
                            state = state,
                            tailnetHostDraft = tailnetHostDraft,
                            tailnetRegistryDraft = tailnetRegistryDraft,
                            onTailnetHostDraftChange = { tailnetHostDraft = it },
                            onTailnetRegistryDraftChange = { tailnetRegistryDraft = it },
                            onSaveTailnetHost = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSaveTailnetHost(tailnetHostDraft)
                            },
                            onClearTailnetHost = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                tailnetHostDraft = ""
                                onSaveTailnetHost("")
                            },
                            onSaveTailnetRegistryUrl = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSaveTailnetRegistryUrl(tailnetRegistryDraft)
                            },
                            onClearTailnetRegistryUrl = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                tailnetRegistryDraft = ""
                                onSaveTailnetRegistryUrl("")
                            },
                            onDismiss = { showNetworkSheet = false },
                        )
                    }
                }
            }
            isFullscreen && state.isInCall -> {
                InCallVideoBox(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    callMetrics = callMetrics,
                    isFullscreen = true,
                    overlayControls = true,
                    accessibleTablet = false,
                    areControlsVisible = areControlsVisible,
                    onRevealControls = { revealControls() },
                    onToggleMic = onToggleMic,
                    onToggleCamera = onToggleCamera,
                    onToggleSpeaker = onToggleSpeaker,
                    onSwitchCamera = onSwitchCamera,
                    onSwapViews = onSwapViews,
                    onToggleFullscreen = onToggleFullscreen,
                    onHangUp = onHangUp,
                    localVideo = localVideo,
                    remoteVideo = remoteVideo,
                )
            }
            else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DropInTopBar(state = state, callMetrics = callMetrics, onRefresh = onRefresh)
                if (state.isInCall) {
                    CallStage(
                        modifier = Modifier.fillMaxWidth(),
                        state = state,
                        callMetrics = callMetrics,
                        isFullscreen = false,
                        overlayControls = false,
                        pinControls = pinControls,
                        areControlsVisible = areControlsVisible,
                        onRevealControls = { revealControls() },
                        onToggleMic = onToggleMic,
                        onToggleCamera = onToggleCamera,
                        onToggleSpeaker = onToggleSpeaker,
                        onSwitchCamera = onSwitchCamera,
                        onSwapViews = onSwapViews,
                        onToggleFullscreen = onToggleFullscreen,
                        onHangUp = onHangUp,
                        localVideo = localVideo,
                        remoteVideo = remoteVideo,
                    )
                }
                SidePanel(
                    modifier = Modifier.weight(1f),
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    state = state,
                    tailnetHostDraft = tailnetHostDraft,
                    tailnetRegistryDraft = tailnetRegistryDraft,
                    onTailnetHostDraftChange = { tailnetHostDraft = it },
                    onTailnetRegistryDraftChange = { tailnetRegistryDraft = it },
                    onSaveTailnetHost = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSaveTailnetHost(tailnetHostDraft)
                    },
                    onClearTailnetHost = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tailnetHostDraft = ""
                        onSaveTailnetHost("")
                    },
                    onSaveTailnetRegistryUrl = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSaveTailnetRegistryUrl(tailnetRegistryDraft)
                    },
                    onClearTailnetRegistryUrl = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tailnetRegistryDraft = ""
                        onSaveTailnetRegistryUrl("")
                    },
                    onConnect = onConnect,
                    onRefresh = onRefresh,
                )
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletDropInLayout(
    state: DropInUiState,
    callMetrics: StateFlow<CallMetrics>,
    onShowViewOptionsSheet: () -> Unit,
    onShowNetworkSheet: () -> Unit,
    areControlsVisible: Boolean,
    onRevealControls: () -> Unit,
    onConnect: (PeerDevice) -> Unit,
    onRefresh: () -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onToggleSpeaker: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onSwapViews: () -> Unit,
    onToggleFullscreen: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    onOpenHomeAssistant: () -> Unit,
    localVideo: @Composable (Modifier) -> Unit,
    remoteVideo: @Composable (Modifier) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TabletTopBar(state = state, callMetrics = callMetrics)
            if (state.isInCall) {
                CallStage(
                    modifier = Modifier.weight(1f),
                    state = state,
                    callMetrics = callMetrics,
                    isFullscreen = false,
                    overlayControls = false,
                    pinControls = true,
                    accessibleTablet = true,
                    areControlsVisible = areControlsVisible,
                    onRevealControls = onRevealControls,
                    onToggleMic = onToggleMic,
                    onToggleCamera = onToggleCamera,
                    onToggleSpeaker = onToggleSpeaker,
                    onSwitchCamera = onSwitchCamera,
                    onSwapViews = onSwapViews,
                    onToggleFullscreen = onToggleFullscreen,
                    onHangUp = onHangUp,
                    onViewMore = onShowViewOptionsSheet,
                    localVideo = localVideo,
                    remoteVideo = remoteVideo,
                )
            } else {
                IdlePanel(
                    modifier = Modifier.weight(1f),
                    state = state,
                    large = true,
                )
            }
        }
        TabletPeersPanel(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
            state = state,
            onConnect = onConnect,
            onRefresh = onRefresh,
            onNetworkSettings = onShowNetworkSheet,
            onHangUp = onHangUp,
            onOpenHomeAssistant = onOpenHomeAssistant,
        )
    }
}

@Composable
private fun TabletTopBar(
    state: DropInUiState,
    callMetrics: StateFlow<CallMetrics>,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "drop in",
                style = MaterialTheme.typography.headlineLarge,
                color = C.textPrimary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            StatusDot(state = state)
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (state.isInCall) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.bodyLarge,
                    color = C.textSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CallDurationText(
                    callMetrics = callMetrics,
                    style = MaterialTheme.typography.titleMedium,
                    color = C.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            Text(
                text = state.status,
                style = MaterialTheme.typography.bodyLarge,
                color = C.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletPeersPanel(
    modifier: Modifier = Modifier,
    state: DropInUiState,
    onConnect: (PeerDevice) -> Unit,
    onRefresh: () -> Unit,
    onNetworkSettings: () -> Unit,
    onHangUp: () -> Unit,
    onOpenHomeAssistant: () -> Unit,
) {
    val bottomPad = scrollBottomPadding()
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)),
    ) {
        Text(
            text = "Who to call",
            style = MaterialTheme.typography.headlineMedium,
            color = C.textPrimary,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp),
        )
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.recentPeers.isNotEmpty() && !state.isInCall) {
                    item { SectionLabel("Recent", large = true) }
                    itemsIndexed(
                        state.recentPeers,
                        key = { index, peer -> "recent-$index-${peer.serviceName}" },
                    ) { _, peer ->
                        PeerRow(peer = peer, large = true, subdued = true, onConnect = { onConnect(peer) })
                    }
                }
                item { SectionLabel("Available", large = true) }
                if (state.devices.isEmpty()) {
                    item { EmptyPeerState(large = true) }
                } else {
                    itemsIndexed(
                        state.devices,
                        key = { index, peer -> "peer-$index-${peer.serviceName}" },
                    ) { _, peer ->
                        PeerRow(peer = peer, large = true, onConnect = { onConnect(peer) })
                    }
                }
            }
        }
        if (state.isInCall) {
            LabeledActionButton(
                icon = Icons.Rounded.CallEnd,
                label = "End call",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHangUp()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 4.dp)
                    .defaultMinSize(minHeight = 80.dp),
                containerColor = C.danger,
            )
        }
        LabeledActionButton(
            icon = Icons.Rounded.Home,
            label = "Home Assistant",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onOpenHomeAssistant()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 4.dp)
                .defaultMinSize(minHeight = 76.dp),
            containerColor = C.surfaceAlt,
        )
        LabeledActionButton(
            icon = Icons.Rounded.Hub,
            label = "Network",
            onClick = onNetworkSettings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = bottomPad, top = 4.dp)
                .defaultMinSize(minHeight = 76.dp),
            containerColor = C.surfaceAlt,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallViewOptionsSheet(
    onSwitchCamera: () -> Unit,
    onSwapViews: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bottomPad = scrollBottomPadding()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "View options",
            style = MaterialTheme.typography.headlineMedium,
            color = C.textPrimary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LabeledActionButton(
                icon = Icons.Rounded.Cameraswitch,
                label = "Flip camera",
                onClick = {
                    onSwitchCamera()
                    onDismiss()
                },
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 88.dp),
            )
            LabeledActionButton(
                icon = Icons.Rounded.SwapHoriz,
                label = "Swap view",
                onClick = {
                    onSwapViews()
                    onDismiss()
                },
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 88.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkSettingsSheet(
    state: DropInUiState,
    tailnetHostDraft: String,
    tailnetRegistryDraft: String,
    onTailnetHostDraftChange: (String) -> Unit,
    onTailnetRegistryDraftChange: (String) -> Unit,
    onSaveTailnetHost: () -> Unit,
    onClearTailnetHost: () -> Unit,
    onSaveTailnetRegistryUrl: () -> Unit,
    onClearTailnetRegistryUrl: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bottomPad = scrollBottomPadding()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Network setup",
            style = MaterialTheme.typography.headlineMedium,
            color = C.textPrimary,
        )
        NetworkPanel(
            modifier = Modifier.fillMaxWidth(),
            localTailscaleAddress = state.localTailscaleAddress,
            registryDraft = tailnetRegistryDraft,
            peerDraft = tailnetHostDraft,
            onRegistryDraftChange = onTailnetRegistryDraftChange,
            onPeerDraftChange = onTailnetHostDraftChange,
            onSaveRegistry = {
                onSaveTailnetRegistryUrl()
                onDismiss()
            },
            onClearRegistry = onClearTailnetRegistryUrl,
            onSavePeer = {
                onSaveTailnetHost()
                onDismiss()
            },
            onClearPeer = onClearTailnetHost,
            large = true,
            contentPadding = PaddingValues(0.dp),
        )
        Text(
            text = "Use the same registry URL on every device to find each other over Tailscale.",
            style = MaterialTheme.typography.bodyLarge,
            color = C.textSecondary,
        )
    }
}

@Composable
private fun LabeledActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    containerColor: Color = C.surfaceAlt,
    contentColor: Color = Color.White,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isActive) containerColor else C.controlOff)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .defaultMinSize(minWidth = 76.dp, minHeight = 84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(32.dp),
            tint = contentColor,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun TabletEssentialCallControls(
    state: DropInUiState,
    onToggleMic: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onToggleSpeaker: (Boolean) -> Unit,
    onToggleFullscreen: (Boolean) -> Unit,
    onViewMore: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LabeledActionButton(
            icon = if (state.isMicOn) Icons.Rounded.Mic else Icons.Rounded.MicOff,
            label = if (state.isMicOn) "Mute" else "Unmute",
            isActive = state.isMicOn,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleMic(!state.isMicOn)
            },
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 92.dp),
        )
        LabeledActionButton(
            icon = if (state.isCameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
            label = if (state.isCameraOn) "Camera off" else "Camera on",
            isActive = state.isCameraOn,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleCamera(!state.isCameraOn)
            },
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 92.dp),
        )
        LabeledActionButton(
            icon = if (state.isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
            label = if (state.isSpeakerOn) "Speaker" else "Earpiece",
            isActive = state.isSpeakerOn,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleSpeaker(!state.isSpeakerOn)
            },
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 92.dp),
        )
        LabeledActionButton(
            icon = Icons.Rounded.Fullscreen,
            label = "Full screen",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleFullscreen(true)
            },
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 92.dp),
        )
        if (onViewMore != null) {
            LabeledActionButton(
                icon = Icons.Rounded.MoreHoriz,
                label = "View more",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onViewMore()
                },
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 92.dp),
            )
        }
    }
}

@Composable
private fun DropInTopBar(
    state: DropInUiState,
    callMetrics: StateFlow<CallMetrics>,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "drop in",
                    style = MaterialTheme.typography.headlineMedium,
                    color = C.textPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusDot(state = state)
            }
            Spacer(modifier = Modifier.height(2.dp))
            if (state.isInCall) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = C.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CallDurationText(
                        callMetrics = callMetrics,
                        style = MaterialTheme.typography.labelLarge,
                        color = C.accent,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    CallQualityIcon(
                        callMetrics = callMetrics,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = C.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "Refresh peers",
                tint = C.textSecondary,
            )
        }
    }
}

@Composable
private fun IdlePanel(
    modifier: Modifier = Modifier,
    state: DropInUiState,
    large: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.People,
                contentDescription = null,
                tint = C.accent.copy(alpha = 0.7f),
                modifier = Modifier.size(if (large) 52.dp else 40.dp),
            )
            Text(
                text = "Ready to connect",
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge,
                color = C.textPrimary,
            )
            Text(
                text = state.status,
                style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
                color = C.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SidePanel(
    modifier: Modifier = Modifier,
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    state: DropInUiState,
    tailnetHostDraft: String,
    tailnetRegistryDraft: String,
    onTailnetHostDraftChange: (String) -> Unit,
    onTailnetRegistryDraftChange: (String) -> Unit,
    onSaveTailnetHost: () -> Unit,
    onClearTailnetHost: () -> Unit,
    onSaveTailnetRegistryUrl: () -> Unit,
    onClearTailnetRegistryUrl: () -> Unit,
    onConnect: (PeerDevice) -> Unit,
    onRefresh: () -> Unit,
) {
    val tabs = HomeTab.entries
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)),
    ) {
        PrimaryTabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = Color.Transparent,
            contentColor = C.accent,
            divider = { HorizontalDivider(color = C.border) },
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = {
                        Text(
                            text = when (tab) {
                                HomeTab.Peers -> "Peers"
                                HomeTab.Network -> "Network"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = when (tab) {
                                HomeTab.Peers -> Icons.Rounded.People
                                HomeTab.Network -> Icons.Rounded.Hub
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        when (selectedTab) {
            HomeTab.Peers -> PeersPanel(
                modifier = Modifier.fillMaxSize(),
                state = state,
                onConnect = onConnect,
                onRefresh = onRefresh,
            )
            HomeTab.Network -> NetworkPanel(
                modifier = Modifier.fillMaxSize(),
                localTailscaleAddress = state.localTailscaleAddress,
                registryDraft = tailnetRegistryDraft,
                peerDraft = tailnetHostDraft,
                onRegistryDraftChange = onTailnetRegistryDraftChange,
                onPeerDraftChange = onTailnetHostDraftChange,
                onSaveRegistry = onSaveTailnetRegistryUrl,
                onClearRegistry = onClearTailnetRegistryUrl,
                onSavePeer = onSaveTailnetHost,
                onClearPeer = onClearTailnetHost,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeersPanel(
    modifier: Modifier = Modifier,
    state: DropInUiState,
    onConnect: (PeerDevice) -> Unit,
    onRefresh: () -> Unit,
) {
    val bottomPad = scrollBottomPadding()
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = bottomPad),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.recentPeers.isNotEmpty() && !state.isInCall) {
                item {
                    SectionLabel("Recent")
                }
                itemsIndexed(
                    state.recentPeers,
                    key = { index, peer -> "recent-$index-${peer.serviceName}" },
                ) { _, peer ->
                    PeerRow(peer = peer, subdued = true, onConnect = { onConnect(peer) })
                }
                item { Spacer(modifier = Modifier.height(4.dp)) }
            }

            item { SectionLabel("Available") }

            if (state.devices.isEmpty()) {
                item { EmptyPeerState() }
            } else {
                itemsIndexed(
                    state.devices,
                    key = { index, peer -> "peer-$index-${peer.serviceName}" },
                ) { _, peer ->
                    PeerRow(peer = peer, onConnect = { onConnect(peer) })
                }
            }
        }
    }
}

@Composable
private fun NetworkPanel(
    modifier: Modifier = Modifier,
    localTailscaleAddress: String?,
    registryDraft: String,
    peerDraft: String,
    onRegistryDraftChange: (String) -> Unit,
    onPeerDraftChange: (String) -> Unit,
    onSaveRegistry: () -> Unit,
    onClearRegistry: () -> Unit,
    onSavePeer: () -> Unit,
    onClearPeer: () -> Unit,
    large: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 0.dp),
) {
    val bottomPad = scrollBottomPadding()
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NetworkStatusCard(localTailscaleAddress = localTailscaleAddress, large = large)
        SettingsField(
            label = "Registry URL",
            value = registryDraft,
            onValueChange = onRegistryDraftChange,
            placeholder = "http://100.x.y.z:8989",
            onSave = onSaveRegistry,
            onClear = onClearRegistry,
            large = large,
        )
        SettingsField(
            label = "Quick peer",
            value = peerDraft,
            onValueChange = onPeerDraftChange,
            placeholder = "100.x.y.z",
            onSave = onSavePeer,
            onClear = onClearPeer,
            large = large,
        )
        Text(
            text = "Point every device at the same registry URL to discover each other over Tailscale.",
            style = MaterialTheme.typography.bodySmall,
            color = C.textSecondary,
        )
    }
}

@Composable
private fun NetworkStatusCard(
    localTailscaleAddress: String?,
    large: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.surfaceAlt)
            .padding(if (large) 18.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (large) 48.dp else 40.dp)
                .clip(CircleShape)
                .background(C.accentDim),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Hub,
                contentDescription = null,
                tint = C.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Tailscale",
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = C.textPrimary,
            )
            Text(
                text = localTailscaleAddress ?: "Not connected on this device",
                style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
                color = if (localTailscaleAddress != null) C.accent else C.textSecondary,
            )
        }
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSave: () -> Unit,
    onClear: () -> Unit,
    large: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            color = C.textSecondary,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = if (large) 64.dp else 56.dp),
            textStyle = if (large) {
                MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            placeholder = {
                Text(placeholder, color = C.textSecondary.copy(alpha = 0.5f))
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors(),
            trailingIcon = {
                Row {
                    if (value.isNotBlank()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear",
                                tint = C.textSecondary,
                            )
                        }
                    }
                    IconButton(onClick = onSave) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Save",
                            tint = C.accent,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.accent,
    unfocusedBorderColor = C.border,
    cursorColor = C.accent,
    focusedTextColor = C.textPrimary,
    unfocusedTextColor = C.textPrimary,
)

@Composable
private fun SectionLabel(text: String, large: Boolean = false) {
    Text(
        text = if (large) text else text.uppercase(),
        style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
        color = C.textSecondary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun CallStage(
    modifier: Modifier = Modifier,
    state: DropInUiState,
    callMetrics: StateFlow<CallMetrics>,
    isFullscreen: Boolean,
    overlayControls: Boolean,
    pinControls: Boolean,
    accessibleTablet: Boolean = false,
    areControlsVisible: Boolean,
    onRevealControls: () -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onToggleSpeaker: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onSwapViews: () -> Unit,
    onToggleFullscreen: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    onViewMore: (() -> Unit)? = null,
    localVideo: @Composable (Modifier) -> Unit,
    remoteVideo: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InCallVideoBox(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (pinControls) {
                        Modifier.weight(1f)
                    } else {
                        Modifier
                            .heightIn(max = inCallVideoMaxHeight())
                            .aspectRatio(16f / 9f)
                    },
                ),
            state = state,
            callMetrics = callMetrics,
            isFullscreen = isFullscreen,
            overlayControls = overlayControls,
            accessibleTablet = accessibleTablet,
            areControlsVisible = areControlsVisible,
            onRevealControls = onRevealControls,
            onToggleMic = onToggleMic,
            onToggleCamera = onToggleCamera,
            onToggleSpeaker = onToggleSpeaker,
            onSwitchCamera = onSwitchCamera,
            onSwapViews = onSwapViews,
            onToggleFullscreen = onToggleFullscreen,
            onHangUp = onHangUp,
            localVideo = localVideo,
            remoteVideo = remoteVideo,
        )
        AnimatedVisibility(
            visible = pinControls || areControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (accessibleTablet) {
                TabletEssentialCallControls(
                    state = state,
                    onToggleMic = onToggleMic,
                    onToggleCamera = onToggleCamera,
                    onToggleSpeaker = onToggleSpeaker,
                    onToggleFullscreen = onToggleFullscreen,
                    onViewMore = onViewMore,
                )
            } else {
                InCallControlBar(
                    state = state,
                    isFullscreen = isFullscreen,
                    onRevealControls = onRevealControls,
                    onToggleMic = onToggleMic,
                    onToggleCamera = onToggleCamera,
                    onToggleSpeaker = onToggleSpeaker,
                    onSwitchCamera = onSwitchCamera,
                    onSwapViews = onSwapViews,
                    onToggleFullscreen = onToggleFullscreen,
                    onHangUp = onHangUp,
                )
            }
        }
    }
}

@Composable
private fun inCallVideoMaxHeight(): Dp {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val heightFraction = if (landscape) 0.34f else 0.32f
    return min(configuration.screenHeightDp * heightFraction, 280f).dp
}

@Composable
private fun InCallControlBar(
    state: DropInUiState,
    isFullscreen: Boolean,
    onRevealControls: () -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onToggleSpeaker: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onSwapViews: () -> Unit,
    onToggleFullscreen: (Boolean) -> Unit,
    onHangUp: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(16.dp))
            .background(C.surfaceAlt)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(
            icon = if (state.isMicOn) Icons.Rounded.Mic else Icons.Rounded.MicOff,
            label = "Mic",
            isActive = state.isMicOn,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRevealControls()
                onToggleMic(!state.isMicOn)
            },
        )
        ControlButton(
            icon = if (state.isCameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
            label = "Camera",
            isActive = state.isCameraOn,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRevealControls()
                onToggleCamera(!state.isCameraOn)
            },
        )
        ControlButton(
            icon = Icons.Rounded.Cameraswitch,
            label = "Flip",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRevealControls()
                onSwitchCamera()
            },
        )
        ControlButton(
            icon = if (state.isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
            label = "Speaker",
            isActive = state.isSpeakerOn,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRevealControls()
                onToggleSpeaker(!state.isSpeakerOn)
            },
        )
        ControlButton(
            icon = Icons.Rounded.SwapHoriz,
            label = "Swap",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRevealControls()
                onSwapViews()
            },
        )
        ControlButton(
            icon = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            label = "Screen",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRevealControls()
                onToggleFullscreen(!isFullscreen)
            },
        )
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRevealControls()
                if (isFullscreen) onToggleFullscreen(false)
                onHangUp()
            },
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = C.danger,
                contentColor = Color.White,
            ),
        ) {
            Icon(Icons.Rounded.CallEnd, contentDescription = "Hang up")
        }
    }
}

@Composable
private fun InCallVideoBox(
    modifier: Modifier,
    state: DropInUiState,
    callMetrics: StateFlow<CallMetrics>,
    isFullscreen: Boolean,
    overlayControls: Boolean,
    accessibleTablet: Boolean = false,
    areControlsVisible: Boolean,
    onRevealControls: () -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onToggleSpeaker: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onSwapViews: () -> Unit,
    onToggleFullscreen: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    onViewMore: (() -> Unit)? = null,
    localVideo: @Composable (Modifier) -> Unit,
    remoteVideo: @Composable (Modifier) -> Unit,
) {
    val cornerRadius = if (isFullscreen) 0.dp else 18.dp
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color(0xFF111113)),
    ) {
        val fullScreenMod = Modifier.matchParentSize().zIndex(0f)
        val pipMod = Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp)
            .size(108.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(C.surface)
            .zIndex(1f)
        val hiddenMod = Modifier
            .align(Alignment.BottomStart)
            .size(1.dp)
            .zIndex(-1f)

        val remoteShouldBePrimary = if (isFullscreen) state.hasRemoteVideo else state.isRemotePrimary
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

        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(state.isInCall) {
                    detectTapGestures(onTap = { onRevealControls() })
                },
        )

        if (isFullscreen) {
            AnimatedVisibility(
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
                    CallDurationText(
                        callMetrics = callMetrics,
                        style = MaterialTheme.typography.bodyMedium,
                        color = C.accent,
                        fontWeight = FontWeight.Medium,
                    )
                    CallQualityIcon(
                        callMetrics = callMetrics,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (overlayControls) {
            AnimatedVisibility(
                modifier = Modifier
                    .align(
                        if (isFullscreen && accessibleTablet) {
                            Alignment.BottomEnd
                        } else {
                            Alignment.BottomCenter
                        },
                    )
                    .padding(
                        end = if (isFullscreen && accessibleTablet) 20.dp else 0.dp,
                        bottom = if (isFullscreen && accessibleTablet) {
                            scrollBottomPadding().coerceAtMost(32.dp)
                        } else {
                            16.dp
                        },
                    ),
                visible = areControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                if (isFullscreen && accessibleTablet) {
                    FullscreenExitButton(
                        onExitFullscreen = { onToggleFullscreen(false) },
                    )
                } else if (accessibleTablet) {
                    TabletEssentialCallControls(
                        state = state,
                        onToggleMic = onToggleMic,
                        onToggleCamera = onToggleCamera,
                        onToggleSpeaker = onToggleSpeaker,
                        onToggleFullscreen = onToggleFullscreen,
                        onViewMore = onViewMore,
                    )
                } else {
                    InCallControlBar(
                        state = state,
                        isFullscreen = isFullscreen,
                        onRevealControls = onRevealControls,
                        onToggleMic = onToggleMic,
                        onToggleCamera = onToggleCamera,
                        onToggleSpeaker = onToggleSpeaker,
                        onSwitchCamera = onSwitchCamera,
                        onSwapViews = onSwapViews,
                        onToggleFullscreen = onToggleFullscreen,
                        onHangUp = onHangUp,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenExitButton(
    onExitFullscreen: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onExitFullscreen()
        },
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color(0xCC1A1A1E)),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
        ),
    ) {
        Icon(
            imageVector = Icons.Rounded.FullscreenExit,
            contentDescription = "Exit full screen",
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (isActive) Color.Transparent else C.controlOff,
            contentColor = Color.White,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun StatusDot(state: DropInUiState) {
    val dotColor = when {
        state.isInCall -> C.accent
        state.status.contains("Connecting", ignoreCase = true) -> Color(0xFFFFC536)
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
            .size(8.dp)
            .scale(pulseScale)
            .alpha(pulseAlpha)
            .clip(CircleShape)
            .background(dotColor),
    )
}

@Composable
private fun EmptyPeerState(large: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (large) 36.dp else 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.SearchOff,
            contentDescription = null,
            tint = C.textSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = "No devices found",
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyMedium,
            color = C.textSecondary,
        )
        Text(
            text = "Pull down to refresh",
            style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
            color = C.textSecondary.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun PeerRow(
    peer: PeerDevice,
    large: Boolean = false,
    subdued: Boolean = false,
    onConnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (subdued) 0.7f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(C.surfaceAlt)
            .clickable(onClick = onConnect)
            .padding(
                horizontal = if (large) 16.dp else 12.dp,
                vertical = if (large) 16.dp else 10.dp,
            )
            .defaultMinSize(minHeight = if (large) 84.dp else 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (large) 48.dp else 36.dp)
                .clip(CircleShape)
                .background(C.accentDim),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = peer.displayName.take(1).uppercase(),
                color = C.accent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.displayName,
                color = C.textPrimary,
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!large) {
                Text(
                    text = "${peer.host}:${peer.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = C.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (large) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Connect",
                    style = MaterialTheme.typography.titleMedium,
                    color = C.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Connect",
                tint = C.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
