package com.sameerasw.airsync.presentation.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import com.sameerasw.airsync.utils.discovery.DiscoverySource
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.activities.QRScannerActivity
import com.sameerasw.airsync.presentation.ui.components.AirSyncFloatingToolbar
import com.sameerasw.airsync.presentation.ui.components.FloatingMediaPlayer
import com.sameerasw.airsync.presentation.ui.components.IPhoneMirrorOverlay
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer
import com.sameerasw.airsync.presentation.ui.components.SettingsView
import com.sameerasw.airsync.presentation.ui.components.buttons.ListExpandToggleButton
import com.sameerasw.airsync.home.DeviceActions
import com.sameerasw.airsync.home.UndoTicket
import com.sameerasw.airsync.multidesk.ComputerLiveStore
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairingQr
import com.sameerasw.airsync.multidesk.NearbyComputersCard
import com.sameerasw.airsync.multidesk.YourDevicesSection
import com.sameerasw.airsync.widget.WidgetModels
import com.sameerasw.airsync.presentation.ui.components.dialogs.ConnectionDialog
import com.sameerasw.airsync.presentation.ui.components.sheets.HelpSupportBottomSheet
import com.sameerasw.airsync.presentation.ui.composables.WelcomeScreen
import com.sameerasw.airsync.presentation.ui.models.AirSyncTab
import com.sameerasw.airsync.presentation.ui.modifiers.BlurDirection
import com.sameerasw.airsync.presentation.ui.modifiers.liquidRipple
import com.sameerasw.airsync.presentation.ui.modifiers.progressiveBlur
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.JsonUtil
import com.sameerasw.airsync.utils.IPhoneScreenMirrorManager
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import com.sameerasw.airsync.utils.WebSocketMessageHandler
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLDecoder

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun AirSyncMainScreen(
    modifier: Modifier = Modifier,
    initialIp: String? = null,
    initialPort: String? = null,
    showConnectionDialog: Boolean = false,
    pcName: String? = null,
    isPlus: Boolean = false,
    symmetricKey: String? = null,
    initialPage: Int = 0,
    onNavigateToApps: () -> Unit = {},
    onTitleChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: AirSyncViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
        AirSyncViewModel.create(context)
    }
    val uiState by viewModel.uiState.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    // H.I.B Home: every computer counts the same. The Remote and Clipboard tabs, the media player and the
    // Home cards appear when *any* computer is connected, not only the one on the legacy primary connection.
    val hubDesktops by DesktopHub.desktops.collectAsState()
    val anyComputer = uiState.isConnected || hubDesktops.any { it.isConnected }
    val anyComputerState = rememberUpdatedState(anyComputer)
    val snackbarHostState = remember { SnackbarHostState() }
    var showMore by rememberSaveable { mutableStateOf(false) }
    val versionName = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
    } catch (_: Exception) {
        "3.0.0"
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connectScrollState = rememberScrollState()
    val settingsScrollState = rememberScrollState()
    var hasProcessedQrDialog by remember { mutableStateOf(false) }
    var hasAppliedInitialTab by remember { mutableStateOf(false) }
    var isWelcomeDismissed by rememberSaveable { mutableStateOf(false) }
    var hasSeenWelcomeThisSession by rememberSaveable { mutableStateOf(false) }

    if (!uiState.isOnboardingCompleted) {
        hasSeenWelcomeThisSession = true
    }

    var activeSettingsCategory by rememberSaveable { mutableStateOf<String?>(null) }

    // Volume & Media state
    var volume by remember { mutableFloatStateOf(50f) }
    var isMuted by remember { mutableStateOf(false) }
    // Observe Mac Status
    val macStatus by MacDeviceStatusManager.macDeviceStatus.collectAsState()
    val albumArtBitmap by MacDeviceStatusManager.albumArt.collectAsState()
    val iphoneScreenFrame by IPhoneScreenMirrorManager.frame.collectAsState()
    val iphoneScreenActive by IPhoneScreenMirrorManager.isActive.collectAsState()
    var showIphoneMirror by rememberSaveable { mutableStateOf(false) }
    var iphoneViewerDismissed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(iphoneScreenActive) {
        if (iphoneScreenActive && !iphoneViewerDismissed) {
            showIphoneMirror = true
        } else if (!iphoneScreenActive) {
            showIphoneMirror = false
            iphoneViewerDismissed = false
        }
    }

    // The floating player shows (and controls) the computer you're using, or else whichever one is playing —
    // any computer, not only the one on the primary connection.
    val liveByComputer by ComputerLiveStore.live.collectAsState()
    val mediaComputer = remember(hubDesktops, liveByComputer) {
        WidgetModels.mediaComputer(
            hubDesktops.filter { it.isConnected }.map { it.name },
            liveByComputer,
            DesktopHub.targetPick()?.id
        )
    }
    val mediaOnPrimary = mediaComputer == null ||
        DesktopHub.primaryName?.let { com.sameerasw.airsync.multidesk.PairedDesktops.sameName(it, mediaComputer) } == true
    val mediaLive = ComputerLiveStore.of(mediaComputer)
    val shownMusic = if (mediaOnPrimary) macStatus?.music else mediaLive?.let { l ->
        com.sameerasw.airsync.domain.model.MacMusicInfo(
            isPlaying = l.playing, title = l.title, artist = l.artist,
            volume = l.volume.coerceAtLeast(0), isMuted = l.muted, albumArt = "", likeStatus = "none"
        )
    }
    val shownArt = if (mediaOnPrimary) albumArtBitmap else remember(mediaLive?.artLite) {
        mediaLive?.artLite?.let { b64 ->
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    // Sync volume and mute state with the shown computer's status updates
    LaunchedEffect(shownMusic) {
        shownMusic?.let { music ->
            volume = music.volume.toFloat()
            isMuted = music.isMuted
        }
    }

    // Volume updates from Mac
    DisposableEffect(Unit) {
        val callback = { newVolume: Int ->
            volume = newVolume.toFloat()
        }
        WebSocketMessageHandler.setOnMacVolumeCallback(callback)
        onDispose {
            WebSocketMessageHandler.setOnMacVolumeCallback(null)
        }
    }

    fun sendRemoteAction(action: String, value: Any? = null, pointed: Boolean = false) {
        scope.launch {
            try {
                HapticUtil.performLightTick(haptics)
                val json = JSONObject()
                json.put("type", "remoteControl")
                val data = JSONObject()
                data.put("action", action)
                if (value != null) data.put("value", value)
                json.put("data", data)
                // Point to act (SPACE.md §11): media controls go to the computer the phone points at while a
                // point-aware screen (the Remote, the open player…) is in front.
                if (pointed) com.sameerasw.airsync.multidesk.PointTarget.sendOrRoute(json.toString())
                else WebSocketUtil.sendMessage(json.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** The floating player's controls go to the computer whose media it shows (else the point target). */
    fun sendMediaAction(action: String, value: Any? = null) {
        val to = mediaComputer
        if (to == null) {
            sendRemoteAction(action, value, pointed = true)
            return
        }
        HapticUtil.performLightTick(haptics)
        val data = JSONObject().put("action", action)
        if (value != null) data.put("value", value)
        val json = JSONObject().put("type", "remoteControl").put("data", data).toString()
        if (!DesktopHub.sendTo(to, json)) sendRemoteAction(action, value, pointed = true)
    }

    /** Clearing the clipboard history is one tap on the FAB, so it comes with Undo. */
    fun clearHistoryWithUndo() {
        val before = uiState.clipboardHistory
        if (before.isEmpty()) return
        viewModel.clearClipboardHistory()
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val r = snackbarHostState.showSnackbar("Clipboard history cleared", actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) viewModel.restoreClipboardHistory(before)
        }
    }

    val pagerState =
        rememberPagerState(
            initialPage = initialPage,
            pageCount = { if (anyComputer) 4 else 2 })

    LaunchedEffect(pagerState.currentPage) {
        activeSettingsCategory = null
    }

    val navCallbackState = rememberUpdatedState(onNavigateToApps)
    LaunchedEffect(navCallbackState.value) {
    }
    var fabVisible by remember { mutableStateOf(true) }
    var fabExpanded by remember { mutableStateOf(true) }
    var showKeyboard by remember { mutableStateOf(false) } // State for Keyboard Sheet in Remote Tab
    var showHelpSheet by remember { mutableStateOf(false) }
    val onDismissHelp = { showHelpSheet = false }
    var loadingHapticsJob by remember { mutableStateOf<Job?>(null) }

    var rippleTrigger by remember { mutableStateOf(0) }
    var rippleOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    var wasConnected by remember { mutableStateOf(uiState.isConnected) }
    LaunchedEffect(uiState.isConnected) {
        if (uiState.isConnected && !wasConnected) {
            val originX = if (rootSize.width > 0) rootSize.width / 2f else 500f
            val originY = if (rootSize.height > 0) rootSize.height * 0.25f else 400f
            rippleOrigin = Offset(originX, originY)
            rippleTrigger++
        }
        wasConnected = uiState.isConnected
    }

    // Initial tab navigation logic
    LaunchedEffect(Unit) {
        if (!hasAppliedInitialTab) {
            if (initialPage != 0) {
                hasAppliedInitialTab = true
                return@LaunchedEffect
            }

            // Wait up to 2 seconds for initial connection (e.g. auto-reconnect on start)
            withTimeoutOrNull(2000) {
                snapshotFlow { anyComputerState.value }.filter { it }.first()
            }

            if (anyComputerState.value) {
                val targetPage = when (uiState.defaultTab) {
                    "connect" -> 0
                    "remote" -> 1
                    "clipboard" -> 2
                    "dynamic" -> {
                        // Check if music is playing on Mac
                        if (uiState.macDeviceStatus?.music?.isPlaying == true) 0 else 2
                    }

                    else -> 0
                }
                if (targetPage > 0 && targetPage < (if (anyComputerState.value) 4 else 2)) {
                    pagerState.scrollToPage(targetPage)
                }
            }
            hasAppliedInitialTab = true
        }
    }

    // For export/import flow
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    rememberNavController()

    fun connect(
        deviceId: String? = null,
        ipAddress: String = uiState.ipAddress,
        port: String = uiState.port,
        symmetricKey: String? = uiState.symmetricKey
    ) {
        // Check if critical permissions are missing
        val criticalPermissions =
            com.sameerasw.airsync.utils.PermissionUtil.getCriticalMissingPermissions(context)
        if (criticalPermissions.isNotEmpty()) {
            Toast.makeText(context, "Missing permissions", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.setConnectingDeviceId(deviceId)
        viewModel.setConnectionStatus(isConnected = false, isConnecting = true)
        viewModel.setUserManuallyDisconnected(false)

        scope.launch(Dispatchers.Main) {
            val result = withTimeoutOrNull(20000) {
                var connectionResult: Boolean? = null
                WebSocketUtil.connect(
                    context = context,
                    ipAddress = ipAddress,
                    port = port.toIntOrNull() ?: 6996,
                    symmetricKey = symmetricKey,
                    manualAttempt = true,
                    onHandshakeTimeout = {
                        scope.launch(Dispatchers.Main) {
                            try {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            } catch (_: Exception) {
                            }
                            viewModel.setConnectionStatus(isConnected = false, isConnecting = false)
                            WebSocketUtil.disconnect(context)
                            viewModel.showAuthFailure(
                                "Connection failed due to authentication failure. Please check the encryption key by re-scanning the QR code."
                            )
                        }
                    },
                    onConnectionStatus = { connected ->
                        connectionResult = connected
                    },
                    onMessage = { response ->
                        // Screen frames arrive many times a second with large payloads; work out
                        // the type cheaply here instead of parsing JSON on the main thread.
                        val responseType = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")
                            .find(response.take(256))?.groupValues?.get(1)
                            ?: if (response.contains("\"screenFrame\"")) "screenFrame" else "message"
                        if (responseType != "screenFrame") {
                            scope.launch(Dispatchers.Main) {
                                Log.d("AirSyncMainScreen", "Message received: $responseType")
                                viewModel.setResponse("Received: $responseType")
                            }
                        }
                    }
                )

                // Wait for the connection result
                while (connectionResult == null) {
                    delay(100)
                }
                connectionResult
            }

            if (result == null) {
                // Timeout occurred
                try {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                } catch (_: Exception) {
                }
                viewModel.setConnectionStatus(isConnected = false, isConnecting = false)
                WebSocketUtil.disconnect(context)
                Toast.makeText(context, "Connection Timed Out", Toast.LENGTH_SHORT).show()
                viewModel.setResponse("Connection Timed Out")
            } else {
                val connected = result
                viewModel.setConnectionStatus(isConnected = connected, isConnecting = false)
                if (connected) {
                    viewModel.setResponse("Connected successfully!")
                    val plusStatus = uiState.lastConnectedDevice?.isPlus ?: isPlus
                    viewModel.saveLastConnectedDevice(pcName, plusStatus, symmetricKey)
                } else {
                    viewModel.setResponse("Failed to connect")
                }
            }
        }
    }

    // CreateDocument launcher for export (MIME application/json)
    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            Toast.makeText(context, "Export cancelled", Toast.LENGTH_SHORT).show()
            viewModel.setLoading(false)
            return@rememberLauncherForActivityResult
        }

        // Write pendingExportJson to uri
        scope.launch(Dispatchers.IO) {
            try {
                val json = pendingExportJson
                if (json == null) {
                    // Nothing to write
                    viewModel.setLoading(false)
                    return@launch
                }
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Export successful", Toast.LENGTH_SHORT).show()
                    viewModel.setLoading(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    viewModel.setLoading(false)
                }
            }
        }
    }

    // OpenDocument launcher for import (allow picking JSON)
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Toast.makeText(context, "Import cancelled", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        viewModel.setLoading(true)
        scope.launch(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri)?.bufferedReader()
                    ?.use { it.readText() }
                if (input == null) {
                    scope.launch(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read file", Toast.LENGTH_LONG).show()
                        viewModel.setLoading(false)
                    }
                    return@launch
                }

                val success = viewModel.importDataFromJson(context, input)
                scope.launch(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(context, "Import successful", Toast.LENGTH_SHORT).show()
                        viewModel.initializeState(context)
                    } else {
                        Toast.makeText(context, "Import failed or invalid file", Toast.LENGTH_LONG)
                            .show()
                    }
                    viewModel.setLoading(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Import error: ${e.message}", Toast.LENGTH_LONG).show()
                    viewModel.setLoading(false)
                }
            }
        }
    }

    // QR Scanner launcher
    val qrScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val qrCode = result.data?.getStringExtra("QR_CODE")
            if (!qrCode.isNullOrEmpty()) {
                // Parse the QR code (expected format: airsync://ip:port?name=...&plus=...&key=...)
                try {
                    val uri = qrCode.toUri()

                    // Handle potential multiple IPs in host part (e.g., ip1,ip2,ip3)
                    var ip = uri.host ?: ""
                    var port = uri.port.takeIf { it != -1 }?.toString() ?: ""

                    if (ip.isEmpty() || port.isEmpty()) {
                        // Fallback manual parsing if URI host is null due to commas
                        val authority = qrCode.substringAfter("://").substringBefore("?")
                        if (authority.contains(":")) {
                            ip = authority.substringBeforeLast(":")
                            port = authority.substringAfterLast(":")
                        } else {
                            ip = authority
                        }
                    }

                    // Parse query parameters
                    var pcName: String? = null
                    var isPlus = false
                    var symmetricKey: String? = null

                    val queryPart = uri.toString().substringAfter('?', "")
                    if (queryPart.isNotEmpty()) {
                        val params = queryPart.split('?')
                        val paramMap = params.associate { param ->
                            val parts = param.split('=', limit = 2)
                            val key = parts.getOrNull(0) ?: ""
                            val value = parts.getOrNull(1) ?: ""
                            key to value
                        }
                        pcName = paramMap["name"]?.let { URLDecoder.decode(it, "UTF-8") }
                        isPlus = paramMap["plus"]?.toBooleanStrictOrNull() ?: false
                        symmetricKey = paramMap["key"]
                    }

                    if (ip.isNotEmpty() && port.isNotEmpty()) {
                        // Update UI state with scanned values
                        viewModel.updateIpAddress(ip)
                        viewModel.updatePort(port)
                        viewModel.updateManualPcName(pcName ?: "")
                        viewModel.updateManualIsPlus(isPlus)
                        if (!symmetricKey.isNullOrEmpty()) {
                            viewModel.updateSymmetricKey(symmetricKey)
                        }

                        // Trigger connection
                        scope.launch {
                            delay(300)  // Brief delay to ensure UI updates
                            connect()
                        }
                    } else {
                        Toast.makeText(context, "Invalid QR code format", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Failed to parse QR code: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    /**
     * Reconnects the computer on the legacy primary connection: the address for this network if known, else
     * the one discovery sees right now, else its last address. Used by a card's Connect / Resume and by Undo.
     */
    fun quickConnectPrimary() {
        val device = uiState.lastConnectedDevice ?: return
        val networkAware = viewModel.getNetworkAwareLastConnectedDevice()
        val seen = viewModel.discoveredDevices.value.firstOrNull {
            com.sameerasw.airsync.multidesk.PairedDesktops.sameName(it.name, device.name)
        }
        when {
            networkAware != null -> {
                viewModel.updateIpAddress(networkAware.ipAddress)
                viewModel.updatePort(networkAware.port)
                connect(ipAddress = networkAware.ipAddress, port = networkAware.port, symmetricKey = networkAware.symmetricKey)
            }
            seen != null -> {
                viewModel.updateIpAddress(seen.getBestIp())
                viewModel.updatePort(seen.port.toString())
                connect(deviceId = seen.id, ipAddress = seen.getBestIp(), port = seen.port.toString(), symmetricKey = device.symmetricKey)
            }
            else -> {
                viewModel.updateIpAddress(device.ipAddress)
                viewModel.updatePort(device.port)
                viewModel.updateSymmetricKey(device.symmetricKey)
                connect(ipAddress = device.ipAddress, port = device.port, symmetricKey = device.symmetricKey)
            }
        }
    }

    fun showMessage(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short)
        }
    }

    /** After a pause or forget: a snackbar with Undo (reconnect / restore) for a few seconds. */
    fun showUndo(ticket: UndoTicket) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val r = snackbarHostState.showSnackbar(ticket.message, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) {
                val ok = DeviceActions.undo(
                    context, ticket,
                    connectPrimary = { quickConnectPrimary() },
                    resumeAll = { viewModel.setAppPaused(context, false) }
                )
                if (!ok) showMessage("Too late to undo")
            } else {
                DeviceActions.dismiss(ticket)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initializeState(
            context,
            initialIp,
            initialPort,
            showConnectionDialog && !hasProcessedQrDialog,
            pcName,
            isPlus,
            symmetricKey
        )

        // Start network monitoring for dynamic Wi-Fi changes
        viewModel.startNetworkMonitoring(context)

        // Refresh permissions on app launch
        viewModel.refreshPermissions(context)
    }

    // Refresh permissions when app resumes from pause
    DisposableEffect(lifecycle) {
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions(context)
            }
        }
        lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
        }
    }

    // Mark QR dialog as processed when it's shown or when already connected
    LaunchedEffect(showConnectionDialog, uiState.isConnected) {
        if (showConnectionDialog) {
            if (uiState.isConnected) {
                // If already connected, don't show dialog
                hasProcessedQrDialog = true
            } else if (uiState.isDialogVisible) {
                // Dialog is being shown, mark as processed
                hasProcessedQrDialog = true
            }
        }
    }

    // Refresh permissions when returning from settings
    LaunchedEffect(uiState.showPermissionDialog) {
        if (!uiState.showPermissionDialog) {
            viewModel.refreshPermissions(context)
        }
    }

    // Hide FAB on scroll down, show on scroll up for the active tab
    LaunchedEffect(pagerState.currentPage) {
        val state = if (pagerState.currentPage == 0) connectScrollState else settingsScrollState
        val last = state.value
        snapshotFlow { state.value }.collect { value ->
            val delta = value - last
            if (delta > 2) fabVisible = false
            else if (delta < -2) fabVisible = true
        }
    }

    // Expand FAB on first launch and whenever variant changes, then collapse after 5s
    LaunchedEffect(anyComputer) {
        fabExpanded = true
        // Give users a hint for a short period, then collapse to icon-only
        delay(5000)
        fabExpanded = false
    }

    // Start/stop clipboard sync based on connection status and settings
    LaunchedEffect(uiState.isConnected, uiState.isClipboardSyncEnabled) {
        if (uiState.isConnected && uiState.isClipboardSyncEnabled) {
            // Register callback to track clipboard history
            ClipboardSyncManager.setOnClipboardSentCallback { text ->
                viewModel.addClipboardEntry(text, isFromPc = false)
            }
            ClipboardSyncManager.startSync(context)
        } else if (!uiState.isClipboardSyncEnabled) {
            // Only stop when the user turned sync off; the background service owns it otherwise.
            ClipboardSyncManager.stopSync(context)
        }
    }

    LaunchedEffect(Unit) {
        com.sameerasw.airsync.utils.WebSocketMessageHandler.setOnClipboardEntryCallback { text ->
            Log.d(
                "AirSyncMainScreen",
                "Incoming clipboard update via WebSocketMessageHandler: ${text.take(50)}"
            )
            viewModel.addClipboardEntry(text, isFromPc = true)
        }
    }

    // Start/stop loading haptics when connecting
    LaunchedEffect(uiState.isConnecting) {
        if (uiState.isConnecting) {
            loadingHapticsJob = HapticUtil.startLoadingHaptics(haptics, lifecycle)
        } else {
            loadingHapticsJob?.cancel()
            loadingHapticsJob = null
        }
    }

    // Auth failure dialog
    if (uiState.showAuthFailureDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissAuthFailure() },
            title = { Text("Connection failed") },
            text = {
                Text(uiState.authFailureMessage.ifEmpty {
                    "Authentication failed. Please re-scan the QR code on the device running H.I.B so the encryption key matches."
                })
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissAuthFailure() }) {
                    Text("OK")
                }
            }
        )
    }


    fun launchScanner(context: Context) {
        // Launch our custom QR Scanner Activity
        val scannerIntent = Intent(context, QRScannerActivity::class.java)
        qrScannerLauncher.launch(scannerIntent)
    }

    // Add computer: a new pairing next to the others (never replaces one).
    val addComputerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val raw = result.data?.getStringExtra("QR_CODE") ?: return@rememberLauncherForActivityResult
        val qr = PairingQr.parse(raw)
        if (qr?.name == null || qr.key == null) {
            showMessage("That isn't an H.I.B pairing code")
            return@rememberLauncherForActivityResult
        }
        DesktopHub.addFromQr(context, qr)
        showMessage("Added ${qr.name} — connecting…")
    }

    /**
     * Scan a pairing QR code. With no computer on the primary connection yet (or to re-pair that one) the
     * legacy flow pairs it; otherwise the computer is added next to the others.
     */
    fun scanToAdd(primary: Boolean) {
        if (primary || DesktopHub.primaryName == null) launchScanner(context)
        else addComputerLauncher.launch(Intent(context, QRScannerActivity::class.java))
    }


    fun sendMessage(message: String) {
        scope.launch {
            viewModel.setLoading(true)
            viewModel.setResponse("")

            if (!WebSocketUtil.isConnected()) {
                connect()
                delay(500)
            }

            val success = WebSocketUtil.sendMessage(message)
            if (success) {
                viewModel.setResponse("Message sent: $message")
            } else {
                viewModel.setResponse("Failed to send message")
            }
            viewModel.setLoading(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Leaving the screen must not stop syncing: the service keeps the watcher running.
            ClipboardSyncManager.setOnClipboardSentCallback(null)
        }
    }

    // Define tabs
    val tabs = remember(anyComputer) {
        if (anyComputer) {
            listOf(
                AirSyncTab(R.string.tab_connect, Icons.Rounded.Phonelink, 0),
                AirSyncTab(R.string.tab_remote, Icons.Rounded.Gamepad, 1),
                AirSyncTab(R.string.tab_clipboard, Icons.Rounded.ContentPaste, 2),
                AirSyncTab(R.string.tab_settings, Icons.Rounded.Settings, 3)
            )
        } else {
            listOf(
                AirSyncTab(R.string.tab_connect, Icons.Rounded.Phonelink, 0),
                AirSyncTab(R.string.tab_settings, Icons.Rounded.Settings, 1)
            )
        }
    }

    // Update title based on current tab
    LaunchedEffect(pagerState.currentPage, tabs) {
        val currentTab = tabs.getOrNull(pagerState.currentPage)
        if (currentTab != null) {
            val titleStr = context.getString(currentTab.title)
            val title = if (titleStr == "Connect") "H.I.B" else titleStr
            onTitleChange(title)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) { innerPadding ->
            val density = androidx.compose.ui.platform.LocalDensity.current
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscape =
                configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val statusBarHeightPx = with(density) { statusBarHeight.toPx() }
            val topSpacing = (statusBarHeight - 24.dp).coerceAtLeast(0.dp)

            // Track page changes for haptic feedback on swipe
            LaunchedEffect(pagerState.currentPage) {
                snapshotFlow { pagerState.currentPage }.collect { _ ->
                    HapticUtil.performLightTick(haptics)
                }
            }

            // Blur heights
            val bottomBlurHeightPx = with(density) {
                if (isLandscape) 100.dp.toPx() else 180.dp.toPx()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { rootSize = it }
            ) {
                HorizontalPager(
                    modifier = modifier
                        .fillMaxSize()
                        .liquidRipple(
                            trigger = rippleTrigger,
                            origin = rippleOrigin,
                            enabled = uiState.isRippleSettingEnabled
                        )
                        .progressiveBlur(
                            blurRadius = if (uiState.isBlurEnabled) 40f else 0f,
                            height = statusBarHeightPx * 1.15f,
                            direction = BlurDirection.TOP
                        )
                        .progressiveBlur(
                            blurRadius = if (uiState.isBlurEnabled) 40f else 0f,
                            height = bottomBlurHeightPx,
                            direction = BlurDirection.BOTTOM
                        ),
                    state = pagerState
                ) { page ->
                    when (page) {
                        0 -> {
                            // Connect tab content
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 0.dp)
                                    .verticalScroll(connectScrollState)
                                    .padding(horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {

                                Spacer(
                                    modifier = Modifier
                                        .height(topSpacing)
                                        .fillMaxWidth()
                                )

                                // Your devices: every computer is an equal card. Which one rides the legacy
                                // primary connection is an internal detail and never shows here.
                                YourDevicesSection(
                                    appPaused = uiState.isAppPaused,
                                    onScan = { primary -> scanToAdd(primary) },
                                    onConnectPrimary = { quickConnectPrimary() },
                                    onOpenRemote = { scope.launch { pagerState.animateScrollToPage(1) } },
                                    onPauseAll = { viewModel.setAppPaused(context, true) },
                                    onResumeAll = { viewModel.setAppPaused(context, false) },
                                    onUndoable = { showUndo(it) },
                                    onMessage = { showMessage(it) }
                                )

                                // Computers on this network that aren't paired yet: pairing needs their QR code.
                                val discoveredDevices by viewModel.discoveredDevices.collectAsState()
                                val nearby = discoveredDevices.filter { dd ->
                                    dd.type != "android" && hubDesktops.none { com.sameerasw.airsync.multidesk.PairedDesktops.sameName(it.name, dd.name) }
                                }
                                AnimatedVisibility(
                                    visible = !uiState.isAppPaused && uiState.isDeviceDiscoveryEnabled && nearby.isNotEmpty(),
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    NearbyComputersCard(
                                        names = nearby.map { it.name to it.type },
                                        onAdd = { scanToAdd(com.sameerasw.airsync.multidesk.DesktopHub.primaryName == null) }
                                    )
                                }

                                // For the computer you're using: Flick (point to choose), Scenes.
                                AnimatedVisibility(
                                    visible = anyComputer,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    RoundedCardContainer {
                                        // H.I.B: Flick (pick photos/videos/files, point at a screen and throw)
                                        com.sameerasw.airsync.presentation.ui.components.cards.FlickCard()
                                        // H.I.B: Scenes the computers marked "Show on phone"
                                        com.sameerasw.airsync.scenes.ScenesCard()
                                        // H.I.B: Do Not Disturb access prompt (hidden once granted)
                                        com.sameerasw.airsync.scenes.DndAccessPrompt()
                                    }
                                }

                                // More: the rarely used things, one tap away instead of always on screen.
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ListExpandToggleButton(
                                        isExpanded = showMore,
                                        onToggle = { showMore = !showMore },
                                        title = "More",
                                        description = "Desk Deck, Find my computer, widgets",
                                        iconRes = R.drawable.rounded_extension_24
                                    )
                                    AnimatedVisibility(
                                        visible = showMore,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        RoundedCardContainer {
                                            // H.I.B: Desk Deck for a phone on a stand (follows the computer you point at)
                                            if (anyComputer) com.sameerasw.airsync.deskdeck.DeskDeckCard()
                                            // H.I.B: Find my computer (nearby or over the internet)
                                            com.sameerasw.airsync.findmy.FindMyMacCard()
                                            // H.I.B: home-screen widgets
                                            com.sameerasw.airsync.widget.WidgetsCard()
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(200.dp))
                            }
                        }

                        1 -> {
                            if (anyComputer) {
                                // When connected: page 1 = Remote
                                RemoteControlScreen(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = statusBarHeight),
                                    showKeyboard = showKeyboard,
                                    onDismissKeyboard = { showKeyboard = false },
                                    isActive = pagerState.currentPage == 1,
                                    bottomPadding = if (isLandscape) 100.dp else 180.dp,
                                    onLeave = { scope.launch { pagerState.animateScrollToPage(0) } }
                                )
                            } else {
                                // When disconnected: page 1 = Settings
                                SettingsNavHost(
                                    context = context,
                                    uiState = uiState,
                                    deviceInfo = deviceInfo,
                                    versionName = versionName,
                                    viewModel = viewModel,
                                    scope = scope,
                                    activeCategory = activeSettingsCategory,
                                    onCategoryChange = { activeSettingsCategory = it },
                                    settingsScrollState = settingsScrollState,
                                    onSendMessage = { message -> sendMessage(message) },
                                    pendingExportJson = { json ->
                                        pendingExportJson = json
                                        createDocLauncher.launch("hib_settings_${System.currentTimeMillis()}.json")
                                    },
                                    onImport = { openDocLauncher.launch(arrayOf("application/json")) },
                                    onShowHelp = { showHelpSheet = true },
                                    onAvatarLongClickWithPosition = { pos ->
                                        rippleOrigin = pos
                                        rippleTrigger++
                                    },
                                    onRippleToggleEnabledWithPosition = { pos ->
                                        rippleOrigin = pos
                                        rippleTrigger++
                                    }
                                )
                            }
                        }

                        2 -> {
                            if (anyComputer) {
                                // When connected: page 2 = Clipboard
                                ClipboardScreen(
                                    clipboardHistory = uiState.clipboardHistory,
                                    isConnected = true,
                                    onSendText = { text ->
                                        viewModel.addClipboardEntry(text, isFromPc = false)
                                        val clipboardJson = JsonUtil.createClipboardUpdateJson(text)
                                        WebSocketUtil.sendMessage(clipboardJson)
                                    },
                                    onClearHistory = { viewModel.clearClipboardHistory() },
                                    isHistoryEnabled = uiState.isClipboardHistoryEnabled,
                                    onHistoryToggle = { viewModel.setClipboardHistoryEnabled(it) },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            top = topSpacing,
                                            bottom = if (isLandscape) 100.dp else 180.dp
                                        ),
                                )
                            } else {
                                Box(Modifier.fillMaxSize())
                            }
                        }

                        3 -> {
                            // Page 3 only exists when connected = Settings tab
                            SettingsNavHost(
                                context = context,
                                uiState = uiState,
                                deviceInfo = deviceInfo,
                                versionName = versionName,
                                viewModel = viewModel,
                                scope = scope,
                                activeCategory = activeSettingsCategory,
                                onCategoryChange = { activeSettingsCategory = it },
                                settingsScrollState = settingsScrollState,
                                onSendMessage = { message -> sendMessage(message) },
                                pendingExportJson = { json ->
                                    pendingExportJson = json
                                    createDocLauncher.launch("hib_settings_${System.currentTimeMillis()}.json")
                                },
                                onImport = { openDocLauncher.launch(arrayOf("application/json")) },
                                onShowHelp = { showHelpSheet = true },
                                onAvatarLongClickWithPosition = { pos ->
                                    rippleOrigin = pos
                                    rippleTrigger++
                                },
                                onRippleToggleEnabledWithPosition = { pos ->
                                    rippleOrigin = pos
                                    rippleTrigger++
                                }
                            )
                        }
                    }
                }

                // Adaptive Bottom Bars Container
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            bottom = WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding()
                        )
//                    .padding(bottom = 16.dp)
                        .zIndex(2f)
                ) {
                  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Undo after a pause / forget, and short confirmations, sit just above the bars.
                    SnackbarHost(snackbarHostState, modifier = Modifier.padding(horizontal = 8.dp))
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(
                                16.dp,
                                Alignment.CenterHorizontally
                            ),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            AnimatedVisibility(
                                visible = anyComputer,
                                enter = fadeIn() + expandHorizontally(),
                                exit = fadeOut() + shrinkHorizontally(),
                                modifier = Modifier.weight(1f)
                            ) {
                                FloatingMediaPlayer(
                                    musicInfo = shownMusic,
                                    albumArtBitmap = shownArt,
                                    volume = volume,
                                    isMuted = isMuted,
                                    onVolumeChange = {
                                        volume = it
                                        sendMediaAction("vol_set", it.toInt())
                                    },
                                    onToggleMute = {
                                        sendMediaAction("vol_mute")
                                        isMuted = !isMuted
                                    },
                                    onMediaAction = { sendMediaAction(it) },
                                    isRippleEnabled = uiState.isRippleSettingEnabled
                                )
                            }

                            AirSyncFloatingToolbar(
                                modifier = Modifier.zIndex(1f),
                                currentPage = pagerState.currentPage,
                                tabs = tabs,
                                onTabSelected = { index ->
                                    scope.launch {
                                        val distance =
                                            kotlin.math.abs(index - pagerState.currentPage)
                                        if (distance == 1) {
                                            pagerState.animateScrollToPage(index)
                                        } else {
                                            pagerState.scrollToPage(index)
                                        }
                                    }
                                },
                                floatingActionButton = {
                                    MainFAB(
                                        currentTab = tabs.getOrNull(pagerState.currentPage),
                                        isConnected = uiState.isConnected,
                                        isAppPaused = uiState.isAppPaused,
                                        activeSettingsCategory = activeSettingsCategory,
                                        onAction = { action ->
                                            when (action) {
                                                "keyboard" -> showKeyboard = !showKeyboard
                                                "clear_history" -> clearHistoryWithUndo()
                                                "scan" -> scanToAdd(DesktopHub.primaryName == null)
                                                "back" -> activeSettingsCategory = null
                                                "resume" -> viewModel.setAppPaused(context, false)
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    } else {
                        // Portrait: Stacked
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AnimatedVisibility(
                                visible = anyComputer,
                                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                            ) {
                                FloatingMediaPlayer(
                                    musicInfo = shownMusic,
                                    albumArtBitmap = shownArt,
                                    volume = volume,
                                    isMuted = isMuted,
                                    onVolumeChange = {
                                        volume = it
                                        sendMediaAction("vol_set", it.toInt())
                                    },
                                    onToggleMute = {
                                        sendMediaAction("vol_mute")
                                        isMuted = !isMuted
                                    },
                                    onMediaAction = { sendMediaAction(it) },
                                    isRippleEnabled = uiState.isRippleSettingEnabled
                                )
                            }

                            AirSyncFloatingToolbar(
                                modifier = Modifier.zIndex(1f),
                                currentPage = pagerState.currentPage,
                                tabs = tabs,
                                onTabSelected = { index ->
                                    scope.launch {
                                        val distance =
                                            kotlin.math.abs(index - pagerState.currentPage)
                                        if (distance == 1) {
                                            pagerState.animateScrollToPage(index)
                                        } else {
                                            pagerState.scrollToPage(index)
                                        }
                                    }
                                },
                                floatingActionButton = {
                                    MainFAB(
                                        currentTab = tabs.getOrNull(pagerState.currentPage),
                                        isConnected = uiState.isConnected,
                                        isAppPaused = uiState.isAppPaused,
                                        activeSettingsCategory = activeSettingsCategory,
                                        onAction = { action ->
                                            when (action) {
                                                "keyboard" -> showKeyboard = !showKeyboard
                                                "clear_history" -> clearHistoryWithUndo()
                                                "scan" -> scanToAdd(DesktopHub.primaryName == null)
                                                "back" -> activeSettingsCategory = null
                                                "resume" -> viewModel.setAppPaused(context, false)
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    }
                  }
                }
            }
        }

        // Dialogs
        if (uiState.isDialogVisible) {
            ConnectionDialog(
                deviceName = deviceInfo.name,
                localIp = deviceInfo.localIp,
                desktopIp = uiState.ipAddress,
                port = uiState.port,
                pcName = pcName ?: uiState.lastConnectedDevice?.name,
                isPlus = uiState.lastConnectedDevice?.isPlus ?: isPlus,
                onDismiss = { viewModel.setDialogVisible(false) },
                onConnect = {
                    viewModel.setDialogVisible(false)
                    connect()
                }
            )
        }


        // Help & Support Bottom Sheet
        if (showHelpSheet) {
            HelpSupportBottomSheet(
                onDismissRequest = onDismissHelp
            )
        }

        // Welcome Screen Overlay
        AnimatedVisibility(
            visible = (!uiState.isOnboardingCompleted || hasSeenWelcomeThisSession) && !isWelcomeDismissed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.zIndex(100f)
        ) {
            WelcomeScreen(
                viewModel = viewModel,
                onBeginClick = {
                    isWelcomeDismissed = true
                    viewModel.setOnboardingCompleted(true)
                }
            )
        }

        if (iphoneScreenActive && showIphoneMirror) {
            IPhoneMirrorOverlay(
                frame = iphoneScreenFrame,
                onDismiss = {
                    showIphoneMirror = false
                    iphoneViewerDismissed = true
                }
            )
        } else if (iphoneScreenActive && iphoneScreenFrame != null) {
            FilledTonalButton(
                onClick = {
                    iphoneViewerDismissed = false
                    showIphoneMirror = true
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp)
                    .zIndex(90f)
            ) {
                Text("Open iPhone Live View")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainFAB(
    currentTab: AirSyncTab?,
    isConnected: Boolean,
    isAppPaused: Boolean,
    activeSettingsCategory: String?,
    onAction: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current

    FloatingToolbarDefaults.StandardFloatingActionButton(
        onClick = {
            HapticUtil.performClick(haptics)
            if (currentTab?.title == R.string.tab_settings && activeSettingsCategory != null) {
                onAction("back")
            } else {
                when (currentTab?.title) {
                    R.string.tab_remote -> onAction("keyboard")
                    R.string.tab_clipboard -> onAction("clear_history")
                    else -> {
                        // Home: never a one-tap disconnect. Paused → Resume; else → Add computer.
                        if (isAppPaused) onAction("resume") else onAction("scan")
                    }
                }
            }
        }
    ) {
        if (currentTab?.title == R.string.tab_settings && activeSettingsCategory != null) {
            Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        } else {
            when (currentTab?.title) {
                R.string.tab_remote -> {
                    Icon(Icons.Rounded.Keyboard, contentDescription = "Keyboard")
                }

                R.string.tab_clipboard -> {
                    Icon(Icons.Rounded.Delete, contentDescription = "Clear History")
                }

                else -> {
                    if (isAppPaused) {
                        Icon(painter = painterResource(R.drawable.rounded_play_arrow_24), contentDescription = stringResource(R.string.resume))
                    } else {
                        Icon(imageVector = Icons.Filled.QrCodeScanner, contentDescription = "Add computer")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavHost(
    context: Context,
    uiState: com.sameerasw.airsync.domain.model.UiState,
    deviceInfo: com.sameerasw.airsync.domain.model.DeviceInfo,
    versionName: String?,
    viewModel: AirSyncViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    activeCategory: String?,
    onCategoryChange: (String?) -> Unit,
    settingsScrollState: androidx.compose.foundation.ScrollState,
    onSendMessage: (String) -> Unit,
    pendingExportJson: (String) -> Unit,
    onImport: () -> Unit,
    onShowHelp: () -> Unit,
    onAvatarLongClickWithPosition: ((Offset) -> Unit)? = null,
    onRippleToggleEnabledWithPosition: ((Offset) -> Unit)? = null
) {
    var predictiveBackScale by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var predictiveBackOffset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    LaunchedEffect(activeCategory) {
        if (activeCategory != null) {
            predictiveBackScale = 1f
            predictiveBackOffset = 0f
        }
    }

    val canGoBack = activeCategory != null

    androidx.activity.compose.PredictiveBackHandler(enabled = canGoBack) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                predictiveBackScale = 1f - (backEvent.progress * 0.08f)
                predictiveBackOffset = backEvent.progress * 120f
            }
            onCategoryChange(null)
        } catch (e: java.util.concurrent.CancellationException) {
            predictiveBackScale = 1f
            predictiveBackOffset = 0f
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main settings list
        SettingsView(
            modifier = Modifier.fillMaxSize(),
            context = context,
            innerPaddingBottom = 0.dp,
            uiState = uiState,
            deviceInfo = deviceInfo,
            versionName = versionName,
            viewModel = viewModel,
            activeCategory = null,
            onCategoryChange = onCategoryChange,
            scrollState = settingsScrollState,
            scope = scope,
            onSendMessage = onSendMessage,
            onExport = pendingExportJson,
            onImport = onImport,
            onResetOnboarding = { viewModel.resetOnboarding() },
            onShowHelp = onShowHelp,
            onToggleDeveloperMode = { viewModel.toggleDeveloperModeVisibility() },
            onAvatarLongClickWithPosition = onAvatarLongClickWithPosition,
            onRippleToggleEnabledWithPosition = onRippleToggleEnabledWithPosition
        )

        // Detail sub-page
        var lastNonNullCategory by remember { mutableStateOf("") }
        if (activeCategory != null) {
            lastNonNullCategory = activeCategory
        }
        val displayedCategory = activeCategory ?: lastNonNullCategory

        AnimatedVisibility(
            visible = activeCategory != null,
            enter = androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(220)
            ) + fadeIn(animationSpec = tween(220)),
            exit = androidx.compose.animation.slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(220)
            ) + fadeOut(animationSpec = tween(220))
        ) {
            if (displayedCategory.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .graphicsLayer {
                            scaleX = predictiveBackScale
                            scaleY = predictiveBackScale
                            translationX = predictiveBackOffset
                        }
                ) {
                    SettingsView(
                        modifier = Modifier.fillMaxSize(),
                        context = context,
                        innerPaddingBottom = 0.dp,
                        uiState = uiState,
                        deviceInfo = deviceInfo,
                        versionName = versionName,
                        viewModel = viewModel,
                        activeCategory = displayedCategory,
                        scrollState = rememberScrollState(),
                        scope = scope,
                        onSendMessage = onSendMessage,
                        onExport = pendingExportJson,
                        onImport = onImport,
                        onResetOnboarding = { viewModel.resetOnboarding() },
                        onShowHelp = onShowHelp,
                        onToggleDeveloperMode = { viewModel.toggleDeveloperModeVisibility() },
                        onAvatarLongClickWithPosition = onAvatarLongClickWithPosition,
                        onRippleToggleEnabledWithPosition = onRippleToggleEnabledWithPosition
                    )
                }
            }
        }
    }
}
