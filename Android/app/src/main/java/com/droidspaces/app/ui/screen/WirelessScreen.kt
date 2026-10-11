package com.droidspaces.app.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidspaces.app.R
import com.droidspaces.app.ui.component.LogViewerDialog
import com.droidspaces.app.ui.component.SunshineMoonlightCard
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.DisplayLaunchHelper
import com.droidspaces.app.util.SunshineManager
import com.droidspaces.app.util.WirelessDisplayManager
import com.droidspaces.app.util.WirelessStreamManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LogMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

/**
 * "Wireless Display & Stream" management screen -- redesigned to follow FileSharingScreen layout.
 * Features top-level cards for Mode Switching, Status, Hardware Encoder / Display info,
 * Copyable Connection Addresses, Password/Auth, and Help guides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    // Stream State
    var streamStatus by remember { mutableStateOf<WirelessStreamManager.StreamStatus?>(null) }
    var streamCaps by remember { mutableStateOf<WirelessStreamManager.Capabilities?>(null) }
    var streamAddress by remember { mutableStateOf<String?>(null) }

    // VNC State
    var vncStatus by remember { mutableStateOf<WirelessDisplayManager.VncStatus?>(null) }
    var vncAddress by remember { mutableStateOf<String?>(null) }
    var vncPassword by remember { mutableStateOf("") }
    var vncPasswordVisible by remember { mutableStateOf(false) }

    var showLogViewer by remember { mutableStateOf(false) }
    var logViewerType by remember { mutableStateOf("auto") }

    suspend fun refresh() {
        WirelessDisplayManager.initialize(context)
        WirelessStreamManager.initialize(context)
        val info = ContainerManager.getContainerInfo(containerName)
        if (info != null) {
            container = info
            val effectiveStream = info.enableAnland || info.wirelessDisplayMode == "stream"
            if (effectiveStream) {
                streamStatus = WirelessStreamManager.getStatus(containerName)
                streamCaps = WirelessStreamManager.detectCapabilities(containerName)
                streamAddress = if (info.isRunning) {
                    WirelessStreamManager.getConnectionAddress(containerName, info.streamPort, context)
                } else null
            } else {
                vncStatus = WirelessDisplayManager.getStatus(containerName)
                vncAddress = if (info.isRunning) {
                    WirelessDisplayManager.getConnectionAddress(containerName, info.vncPort, context)
                } else null
            }
        }
    }

    LaunchedEffect(containerName) {
        refresh()
        loading = false
        while (true) {
            delay(3000)
            if (container?.isRunning == true) {
                refresh()
            }
        }
    }

    val current = container

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val isStream = current?.enableAnland == true || current?.wirelessDisplayMode == "stream"
                    Text(if (isStream) context.getString(R.string.stream_card_title) else context.getString(R.string.wireless_display))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showLogViewer = true }) {
                        Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "View Logs")
                    }
                    IconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (loading || current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val isStreamMode = current.enableAnland || current.wirelessDisplayMode == "stream"

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Mode Switcher Card (Hidden if Anland enabled or locked to Wayland)
            item {
                ModeSwitcherCard(
                    isStreamMode = isStreamMode,
                    isAnlandEnabled = current.enableAnland,
                    busy = busy,
                    onSwitchMode = { targetMode ->
                        scope.launch {
                            busy = true
                            if (current.isRunning) {
                                if (isStreamMode) {
                                    WirelessStreamManager.disable(containerName)
                                } else {
                                    WirelessDisplayManager.disable(containerName)
                                }
                            }
                            val updated = current.copy(wirelessDisplayMode = targetMode)
                            val res = ContainerManager.saveContainerConfig(context, updated)
                            if (res.isSuccess) {
                                container = updated
                                refresh()
                                snackbarHostState.showSnackbar(
                                    if (targetMode == "stream") "Switched to H.264 Stream mode" else "Switched to VNC Display mode"
                                )
                            } else {
                                snackbarHostState.showSnackbar("Failed to switch wireless mode")
                            }
                            busy = false
                        }
                    }
                )
            }

            // 2. Status Card
            item {
                if (isStreamMode) {
                    StreamStatusCard(
                        status = streamStatus,
                        containerRunning = current.isRunning,
                        busy = busy,
                        onStart = {
                            scope.launch {
                                busy = true
                                val res = WirelessStreamManager.enable(
                                    containerName,
                                    current.streamPort,
                                    current.vncLanOnly,
                                    current.streamFps,
                                    current.streamBitrateKbps,
                                    current.streamPreset,
                                    current.streamResolution,
                                    current.streamEngine
                                )
                                if (res.isSuccess) snackbarHostState.showSnackbar("Stream started")
                                else snackbarHostState.showSnackbar("Failed to start stream: ${res.message}")
                                refresh()
                                busy = false
                            }
                        },
                        onStop = {
                            scope.launch {
                                busy = true
                                val res = WirelessStreamManager.disable(containerName)
                                if (res.isSuccess) snackbarHostState.showSnackbar("Stream stopped")
                                else snackbarHostState.showSnackbar("Failed to stop stream: ${res.message}")
                                refresh()
                                busy = false
                            }
                        },
                        onRestart = {
                            scope.launch {
                                busy = true
                                val res = WirelessStreamManager.restart(
                                    containerName,
                                    current.streamPort,
                                    current.vncLanOnly,
                                    current.streamFps,
                                    current.streamBitrateKbps,
                                    current.streamPreset,
                                    current.streamResolution,
                                    current.streamEngine
                                )
                                if (res.isSuccess) snackbarHostState.showSnackbar("Stream restarted")
                                else snackbarHostState.showSnackbar("Failed to restart stream: ${res.message}")
                                refresh()
                                busy = false
                            }
                        }
                    )
                } else {
                    VncStatusCard(
                        status = vncStatus,
                        containerRunning = current.isRunning,
                        busy = busy,
                        onStart = {
                            scope.launch {
                                busy = true
                                val res = WirelessDisplayManager.enable(containerName, current.vncPort, current.vncLanOnly)
                                if (res.isSuccess) snackbarHostState.showSnackbar("VNC server started")
                                else snackbarHostState.showSnackbar("Failed to start VNC: ${res.message}")
                                refresh()
                                busy = false
                            }
                        },
                        onStop = {
                            scope.launch {
                                busy = true
                                val res = WirelessDisplayManager.disable(containerName)
                                if (res.isSuccess) snackbarHostState.showSnackbar("VNC server stopped")
                                else snackbarHostState.showSnackbar("Failed to stop VNC: ${res.message}")
                                refresh()
                                busy = false
                            }
                        },
                        onRestart = {
                            scope.launch {
                                busy = true
                                val res = WirelessDisplayManager.restart(containerName, current.vncPort, current.vncLanOnly)
                                if (res.isSuccess) snackbarHostState.showSnackbar("VNC server restarted")
                                else snackbarHostState.showSnackbar("Failed to restart VNC: ${res.message}")
                                refresh()
                                busy = false
                            }
                        }
                    )
                }
            }

            // 3. Hardware Encoder & Display Info Card
            item {
                if (isStreamMode) {
                    StreamEncoderAndDisplayCard(
                        container = current,
                        caps = streamCaps,
                        status = streamStatus,
                        busy = busy,
                        onUpdateEngine = { newEngine ->
                            scope.launch {
                                busy = true
                                val updated = current.copy(streamEngine = newEngine)
                                ContainerManager.saveContainerConfig(context, updated)
                                if (updated.isRunning && streamStatus?.running == true) {
                                    WirelessStreamManager.restart(
                                        containerName,
                                        updated.streamPort,
                                        updated.vncLanOnly,
                                        updated.streamFps,
                                        updated.streamBitrateKbps,
                                        updated.streamPreset,
                                        updated.streamResolution,
                                        updated.streamEngine
                                    )
                                }
                                container = updated
                                refresh()
                                snackbarHostState.showSnackbar(
                                    when (newEngine) {
                                        "hw" -> "Hardware encoder (V4L2 M2M) selected"
                                        "sw" -> "Software encoder (libx264) selected"
                                        else -> "Auto encoder detection selected"
                                    }
                                )
                                busy = false
                            }
                        },
                        onUpdateSettings = { fps, bitrateKbps, preset, resolution ->
                            scope.launch {
                                busy = true
                                val updated = current.copy(
                                    streamFps = fps,
                                    streamBitrateKbps = bitrateKbps,
                                    streamPreset = preset,
                                    streamResolution = resolution
                                )
                                ContainerManager.saveContainerConfig(context, updated)
                                if (updated.isRunning && streamStatus?.running == true) {
                                    WirelessStreamManager.restart(
                                        containerName,
                                        updated.streamPort,
                                        updated.vncLanOnly,
                                        updated.streamFps,
                                        updated.streamBitrateKbps,
                                        updated.streamPreset,
                                        updated.streamResolution,
                                        updated.streamEngine
                                    )
                                }
                                container = updated
                                refresh()
                                snackbarHostState.showSnackbar("Video stream settings updated")
                                busy = false
                            }
                        }
                    )
                } else {
                    VncDisplayDetailsCard(
                        container = current,
                        status = vncStatus
                    )
                }
            }

            // 4. Connection Address Card
            item {
                val addr = if (isStreamMode) streamAddress else vncAddress
                ConnectionAddressCard(
                    isStreamMode = isStreamMode,
                    address = addr,
                    lanOnly = current.vncLanOnly,
                    containerRunning = current.isRunning,
                    onCopy = { text, label ->
                        clipboard.setText(AnnotatedString(text))
                        scope.launch { snackbarHostState.showSnackbar("$label copied to clipboard") }
                    },
                    onOpenBrowser = {
                        val url = "http://$addr"
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            scope.launch { snackbarHostState.showSnackbar("Could not open browser: ${e.message}") }
                        }
                    }
                )
            }

            // 5. LAN Only Access Setting Card
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "LAN Only Access",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Restrict incoming display connections to private local network subnets only.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = current.vncLanOnly,
                            enabled = !busy,
                            onCheckedChange = { lanOnly ->
                                scope.launch {
                                    busy = true
                                    val updated = current.copy(vncLanOnly = lanOnly)
                                    ContainerManager.saveContainerConfig(context, updated)
                                    if (updated.isRunning) {
                                        if (isStreamMode) {
                                            WirelessStreamManager.restart(
                                                containerName,
                                                updated.streamPort,
                                                updated.vncLanOnly,
                                                updated.streamFps,
                                                updated.streamBitrateKbps,
                                                updated.streamPreset,
                                                updated.streamResolution
                                            )
                                        } else {
                                            WirelessDisplayManager.restart(containerName, updated.vncPort, updated.vncLanOnly)
                                        }
                                    }
                                    container = updated
                                    refresh()
                                    snackbarHostState.showSnackbar(
                                        if (lanOnly) "LAN Only access enabled" else "Open access enabled"
                                    )
                                    busy = false
                                }
                            }
                        )
                    }
                }
            }

            // 6. Password Card (VNC Mode Only)
            if (!isStreamMode) {
                item {
                    VncPasswordCard(
                        status = vncStatus,
                        containerRunning = current.isRunning,
                        password = vncPassword,
                        passwordVisible = vncPasswordVisible,
                        busy = busy,
                        onPasswordChange = { vncPassword = it },
                        onTogglePasswordVisibility = { vncPasswordVisible = !vncPasswordVisible },
                        onSavePassword = {
                            if (vncPassword.length < 6) {
                                scope.launch { snackbarHostState.showSnackbar("Password must be at least 6 characters") }
                                return@VncPasswordCard
                            }
                            scope.launch {
                                busy = true
                                val res = WirelessDisplayManager.setPassword(containerName, vncPassword)
                                if (res.isSuccess) {
                                    snackbarHostState.showSnackbar("VNC password saved")
                                    vncPassword = ""
                                    refresh()
                                } else {
                                    snackbarHostState.showSnackbar("Failed to set VNC password")
                                }
                                busy = false
                            }
                        }
                    )
                }
            }

            // 7. Installation Banner (if uninstalled)
            val isUninstalled = if (isStreamMode) streamStatus?.installed == false else vncStatus?.installed == false
            if (isUninstalled && current.isRunning) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    if (isStreamMode) "Streaming Dependencies Required" else "x11vnc Setup Required",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                if (isStreamMode) "FFmpeg or GStreamer streaming tools are not yet installed in this container."
                                else "x11vnc server package is not installed in this container.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val isOk = if (isStreamMode) WirelessStreamManager.installDeps(containerName).isSuccess else WirelessDisplayManager.installX11vnc(containerName).isSuccess
                                        if (isOk) {
                                            snackbarHostState.showSnackbar("Installed successfully!")
                                        } else {
                                            snackbarHostState.showSnackbar("Installation failed")
                                        }
                                        refresh()
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isStreamMode) "Install Streaming Tools" else "Install x11vnc")
                            }
                        }
                    }
                }
            }

            // 8. Sunshine (Moonlight Game & Desktop Streaming) Card
            item {
                SunshineMoonlightCard(
                    containerName = containerName,
                    isContainerRunning = current.isRunning,
                    onViewLogs = {
                        logViewerType = "sunshine"
                        showLogViewer = true
                    },
                    onRefresh = { scope.launch { refresh() } },
                    snackbarHostState = snackbarHostState
                )
            }

            // 9. Info / How this works Card
            item {
                WirelessInfoCard(isStreamMode = isStreamMode, port = if (isStreamMode) current.streamPort else current.vncPort)
            }
        }
    }

    if (showLogViewer) {
        val isStream = current?.enableAnland == true || current?.wirelessDisplayMode == "stream"
        val logTitle = when (logViewerType) {
            "sunshine" -> "Sunshine Streaming Logs"
            else -> if (isStream) "H.264 Stream Encoder Logs" else "x11vnc Server Logs"
        }
        val logFileName = "${containerName}_${when (logViewerType) {
            "sunshine" -> "sunshine"
            else -> if (isStream) "stream" else "vnc"
        }}.log"

        LogViewerDialog(
            title = logTitle,
            subtitle = containerName,
            logFileName = logFileName,
            fetchLogs = {
                when (logViewerType) {
                    "sunshine" -> SunshineManager.getLogs(containerName)
                    else -> if (isStream) WirelessStreamManager.getLogs(containerName) else WirelessDisplayManager.getLogs(containerName)
                }
            },
            onDismiss = { showLogViewer = false }
        )
    }
}

@Composable
private fun ModeSwitcherCard(
    isStreamMode: Boolean,
    isAnlandEnabled: Boolean,
    busy: Boolean,
    onSwitchMode: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Active Display Engine",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (isAnlandEnabled) "Anland Wayland Display Daemon"
                        else if (isStreamMode) "Low-Latency H.264 Stream"
                        else "Standard VNC Display (x11vnc)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        if (isAnlandEnabled) "WAYLAND" else if (isStreamMode) "STREAM" else "VNC",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (isAnlandEnabled) {
                Text(
                    "Pure Wayland compositors (KWin/Anland) stream via PipeWire/DMA-BUF over broker socket /run/display.sock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    if (isStreamMode) "H.264 stream delivers lowest latency and hardware video acceleration for browser & VLC viewing."
                    else "Standard VNC protocol provides broad compatibility across AVNC, RealVNC, TigerVNC, and desktop clients.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FilledTonalButton(
                    onClick = { onSwitchMode(if (isStreamMode) "vnc" else "stream") },
                    enabled = !busy,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (isStreamMode) Icons.Default.Cast else Icons.Default.Devices,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isStreamMode) "Switch to VNC Display (x11vnc)" else "Switch to H.264 Stream (gst/ffmpeg)",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamStatusCard(
    status: WirelessStreamManager.StreamStatus?,
    containerRunning: Boolean,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    val isRunning = status?.running == true
    val (label, color) = when {
        !containerRunning -> "Container stopped" to Color(0xFFFFA726)
        status == null -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        !status.installed -> "Not installed" to MaterialTheme.colorScheme.onSurfaceVariant
        isRunning -> (if (status.display != null) "Running on display ${status.display}" else "Running") to Color(0xFF4CAF50)
        else -> "Stopped" to Color(0xFFEF5350)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Stream Service Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRunning) {
                    OutlinedButton(
                        onClick = onStop,
                        enabled = !busy,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = !busy && containerRunning,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start Stream")
                    }
                }

                OutlinedButton(
                    onClick = onRestart,
                    enabled = !busy && isRunning,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restart")
                }
            }
        }
    }
}

@Composable
private fun VncStatusCard(
    status: WirelessDisplayManager.VncStatus?,
    containerRunning: Boolean,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    val isRunning = status?.running == true
    val (label, color) = when {
        !containerRunning -> "Container stopped" to Color(0xFFFFA726)
        status == null -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        !status.installed -> "x11vnc not installed" to MaterialTheme.colorScheme.onSurfaceVariant
        isRunning && !status.hasPassword -> "Running (No password)" to Color(0xFF4CAF50)
        isRunning -> (if (status.display != null) "Running on display ${status.display}" else "Running") to Color(0xFF4CAF50)
        !status.hasPassword -> "Stopped (No password set)" to Color(0xFFFFB74D)
        else -> "Stopped" to Color(0xFFEF5350)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("VNC Server Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRunning) {
                    OutlinedButton(
                        onClick = onStop,
                        enabled = !busy,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = !busy && containerRunning,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start VNC")
                    }
                }

                OutlinedButton(
                    onClick = onRestart,
                    enabled = !busy && isRunning,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restart")
                }
            }
        }
    }
}

@Composable
private fun <T> SingleChoiceSettingDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.forEach { (value, label) ->
                    val isCur = value == selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            },
                        color = if (isCur) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCur) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            if (isCur) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun StreamEncoderAndDisplayCard(
    container: ContainerInfo,
    caps: WirelessStreamManager.Capabilities?,
    status: WirelessStreamManager.StreamStatus?,
    busy: Boolean = false,
    onUpdateEngine: (String) -> Unit = {},
    onUpdateSettings: (fps: Int, bitrateKbps: Int, preset: String, resolution: String) -> Unit = { _, _, _, _ -> }
) {
    var showFpsDialog by remember { mutableStateOf(false) }
    var showBitrateDialog by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var showResolutionDialog by remember { mutableStateOf(false) }

    if (showFpsDialog) {
        SingleChoiceSettingDialog(
            title = "Select Framerate (FPS)",
            options = listOf(
                15 to "15 FPS (Battery Saver)",
                24 to "24 FPS (Cinematic)",
                30 to "30 FPS (Standard Balanced)",
                45 to "45 FPS (Smooth)",
                60 to "60 FPS (High Performance)",
                90 to "90 FPS (Ultra Smooth)",
                120 to "120 FPS (Max Refresh)"
            ),
            selected = container.streamFps,
            onSelect = { onUpdateSettings(it, container.streamBitrateKbps, container.streamPreset, container.streamResolution) },
            onDismiss = { showFpsDialog = false }
        )
    }

    if (showBitrateDialog) {
        SingleChoiceSettingDialog(
            title = "Select Video Bitrate",
            options = listOf(
                1000 to "1000 kbps (1 Mbps - Low Bandwidth)",
                2000 to "2000 kbps (2 Mbps - Balanced)",
                4000 to "4000 kbps (4 Mbps - Standard HD)",
                6000 to "6000 kbps (6 Mbps - High Quality)",
                8000 to "8000 kbps (8 Mbps - Ultra Quality)",
                12000 to "12000 kbps (12 Mbps - Max Sharpness)",
                16000 to "16000 kbps (16 Mbps - LAN Direct)"
            ),
            selected = container.streamBitrateKbps,
            onSelect = { onUpdateSettings(container.streamFps, it, container.streamPreset, container.streamResolution) },
            onDismiss = { showBitrateDialog = false }
        )
    }

    if (showPresetDialog) {
        SingleChoiceSettingDialog(
            title = "Select Encoder Preset",
            options = listOf(
                "low-latency" to "low-latency (Lowest Delay)",
                "ultrafast" to "ultrafast (Minimum CPU Usage)",
                "superfast" to "superfast (Fast Encoding)",
                "veryfast" to "veryfast (Balanced Default)",
                "faster" to "faster (Better Quality)",
                "fast" to "fast (High Quality)"
            ),
            selected = container.streamPreset,
            onSelect = { onUpdateSettings(container.streamFps, container.streamBitrateKbps, it, container.streamResolution) },
            onDismiss = { showPresetDialog = false }
        )
    }

    if (showResolutionDialog) {
        SingleChoiceSettingDialog(
            title = "Select Stream Resolution",
            options = listOf(
                "native" to "Native Container Resolution",
                "1920x1080" to "1080p FHD (1920x1080)",
                "1600x900" to "900p HD+ (1600x900)",
                "1280x720" to "720p HD (1280x720)",
                "1024x768" to "XGA 4:3 (1024x768)",
                "800x600" to "SVGA (800x600)"
            ),
            selected = container.streamResolution,
            onSelect = { onUpdateSettings(container.streamFps, container.streamBitrateKbps, container.streamPreset, it) },
            onDismiss = { showResolutionDialog = false }
        )
    }

    val engineLabel = when (status?.engine ?: caps?.chosenEngine) {
        "ffmpeg-hw" -> "Hardware H.264 (V4L2 M2M)"
        "gstreamer-sw" -> "GStreamer (XDamage capture)"
        "ffmpeg-sw" -> "Software H.264 (libx264)"
        else -> if (caps?.hwEncoderAvailable == true) "Hardware Encoder Available" else "Software Encoder"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Encoder & Video Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Active Live Engine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(engineLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Engine Mode Switcher (Auto / Hardware / Software)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Encoder Mode Selection", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val engines = listOf(
                        "auto" to "Auto",
                        "hw" to "Hardware (HW)",
                        "sw" to "Software (SW)"
                    )
                    engines.forEach { (key, label) ->
                        val isSelected = container.streamEngine == key
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = !busy) { onUpdateEngine(key) },
                            shape = RoundedCornerShape(50),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // Video Stream Customizable Parameters (Framerate, Bitrate, Preset, Resolution)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { showFpsDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Framerate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${container.streamFps} FPS", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Edit FPS", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { showBitrateDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Bitrate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${container.streamBitrateKbps} kbps", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Edit Bitrate", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { showPresetDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Preset", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(container.streamPreset, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Edit Preset", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { showResolutionDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Resolution", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(container.streamResolution, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Edit Resolution", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (status?.running == true && (!status.liveFps.isNullOrBlank() || !status.liveBitrateKbps.isNullOrBlank())) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Live Stats", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            status.liveFps?.let { fps ->
                                Text("$fps fps", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            status.liveBitrateKbps?.let { br ->
                                Text("$br kbps", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VncDisplayDetailsCard(
    container: ContainerInfo,
    status: WirelessDisplayManager.VncStatus?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Display & Port Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("X11 Display", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(status?.display ?: "Auto-detect", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("VNC Port", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("" + container.vncPort, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionAddressCard(
    isStreamMode: Boolean,
    address: String?,
    lanOnly: Boolean,
    containerRunning: Boolean,
    onCopy: (String, String) -> Unit,
    onOpenBrowser: () -> Unit
) {
    val fullUrl = if (address != null) {
        if (isStreamMode) "http://$address" else "vnc://$address"
    } else null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("Connection Address", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            if (fullUrl != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (isStreamMode) "Stream URL" else "VNC Target Address", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                address ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { onCopy(address ?: "", "Connection address") }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy address", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onCopy(fullUrl, "Full URL") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Address")
                    }

                    if (isStreamMode) {
                        Button(
                            onClick = onOpenBrowser,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open in Browser")
                        }
                    }
                }
            } else {
                Text(
                    if (!containerRunning) "Start the container to generate a live LAN connection address."
                    else "Connect device to Wi-Fi to view local network connection address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VncPasswordCard(
    status: WirelessDisplayManager.VncStatus?,
    containerRunning: Boolean,
    password: String,
    passwordVisible: Boolean,
    busy: Boolean,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSavePassword: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("VNC Authentication Password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Text(
                if (status?.hasPassword == true) "VNC password is configured. Enter a new password below to change it."
                else "A password (min 6 characters) is required to authenticate VNC clients.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (containerRunning) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("VNC Password (min 6 chars)") },
                    placeholder = { Text("Enter password") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = onTogglePasswordVisibility) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                )

                Button(
                    onClick = onSavePassword,
                    enabled = !busy && password.length >= 6,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save VNC Password")
                }
            } else {
                Text("Start the container to configure VNC passwords.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WirelessInfoCard(isStreamMode: Boolean, port: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("How this works", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            if (isStreamMode) {
                Text(
                    "• Browser Viewing:\n" +
                    "  Open http://<phone-ip>:" + port + " directly in any desktop or mobile browser to view live desktop mirror.\n" +
                    "• VLC / FFplay Player:\n" +
                    "  Play directly using: ffplay tcp://<phone-ip>:" + port + "\n" +
                    "• Ultra-Low Latency:\n" +
                    "  Uses hardware video encoder when available, with latency presets tailored for responsive interactivity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "• VNC Clients:\n" +
                    "  Connect using AVNC (Android), RealVNC Viewer, TigerVNC, or Remmina (Linux).\n" +
                    "• Target Address:\n" +
                    "  Enter <phone-ip>:" + port + " and the configured VNC password.\n" +
                    "• Display Mirroring:\n" +
                    "  Mirrors display :0 (Termux:X11 / XFCE) with interactive touch, keyboard, and mouse control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
