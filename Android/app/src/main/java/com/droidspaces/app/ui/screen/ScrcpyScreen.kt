package com.droidspaces.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.R
import com.droidspaces.app.ui.component.LogViewerDialog
import com.droidspaces.app.ui.component.TerminalDialog
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.PreferencesManager
import com.droidspaces.app.util.ScrcpyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ScrcpyPreset(val label: String, val maxSize: Int, val bitrateKbps: Int, val maxFps: Int, val noAudio: Boolean, val turnScreenOff: Boolean, val stayAwake: Boolean) {
    BALANCED("Balanced", 1080, 8000, 60, false, false, false),
    HIGH_QUALITY("High Quality", 0, 16000, 60, false, false, false),
    LOW_LATENCY("Low Latency", 720, 4000, 60, true, false, false),
    SCREEN_OFF("Screen Off", 1080, 8000, 60, false, true, true)
}

/**
 * scrcpy loopback management screen -- mirrors this Android device's screen into
 * the container's desktop over ADB. Includes full terminal script runner, centralized
 * log viewer, stream parameter presets, and comprehensive diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrcpyScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToTerminalWithCommand: (String) -> Unit = {},
    opsViewModel: ContainerOperationsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var status by remember { mutableStateOf<ScrcpyManager.ScrcpyStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    // Stream & Display Settings
    var maxSize by remember { mutableIntStateOf(0) }
    var bitrateKbps by remember { mutableIntStateOf(8000) }
    var maxFps by remember { mutableIntStateOf(0) }
    var noAudio by remember { mutableStateOf(false) }
    var turnScreenOff by remember { mutableStateOf(false) }
    var stayAwake by remember { mutableStateOf(false) }
    var customArgs by remember { mutableStateOf("") }

    var selectedPreset by remember { mutableStateOf<ScrcpyPreset?>(ScrcpyPreset.BALANCED) }
    var showGuide by remember { mutableStateOf(false) }
    var showLogViewer by remember { mutableStateOf(false) }

    suspend fun refresh() {
        ScrcpyManager.initialize(context)
        val info = ContainerManager.getContainerInfo(containerName)
        if (info != null) {
            container = info
            status = ScrcpyManager.getStatus(containerName)
            if (loading) {
                maxSize = info.scrcpyMaxSize
                bitrateKbps = info.scrcpyBitrateKbps
            }
        }
    }

    LaunchedEffect(containerName) {
        refresh()
        loading = false
        while (true) {
            delay(3000)
            refresh()
        }
    }

    val current = container
    val currentStatus = status

    if (showLogViewer) {
        LogViewerDialog(
            title = "scrcpy Client Logs",
            subtitle = containerName,
            logFileName = "${containerName}_scrcpy.log",
            fetchLogs = {
                ScrcpyManager.getLog(containerName)
            },
            onDismiss = { showLogViewer = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.scrcpy_card_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onNavigateToTerminalWithCommand("scrcpy --help")
                    }) {
                        Icon(Icons.Default.Terminal, contentDescription = "Terminal")
                    }
                    IconButton(onClick = {
                        showLogViewer = true
                    }) {
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

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status & Quick Action Hero Section
            item {
                ScrcpyHeroCard(
                    status = currentStatus,
                    busy = busy || opsViewModel.runningOperationContainer == containerName,
                    onInstall = {
                        scope.launch {
                            opsViewModel.executeInstallScrcpy(
                                container = current,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                            )
                        }
                    },
                    onLaunch = {
                        scope.launch {
                            val effectiveInfo = current.copy(
                                scrcpyMaxSize = maxSize,
                                scrcpyBitrateKbps = bitrateKbps
                            )
                            opsViewModel.executeLaunchScrcpy(
                                container = effectiveInfo,
                                noAudio = noAudio,
                                turnScreenOff = turnScreenOff,
                                stayAwake = stayAwake,
                                maxFps = maxFps,
                                extraArgs = customArgs,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                            )
                        }
                    },
                    onStop = {
                        scope.launch {
                            opsViewModel.executeStopScrcpy(
                                container = current,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                            )
                        }
                    }
                )
            }

            // Quick Presets Card
            item {
                ScrcpyPresetsCard(
                    selectedPreset = selectedPreset,
                    onSelectPreset = { preset ->
                        selectedPreset = preset
                        maxSize = preset.maxSize
                        bitrateKbps = preset.bitrateKbps
                        maxFps = preset.maxFps
                        noAudio = preset.noAudio
                        turnScreenOff = preset.turnScreenOff
                        stayAwake = preset.stayAwake
                    }
                )
            }

            // Stream & Display Settings Card
            item {
                ScrcpySettingsCard(
                    maxSize = maxSize,
                    onMaxSizeChange = {
                        maxSize = it
                        selectedPreset = null
                    },
                    bitrateKbps = bitrateKbps,
                    onBitrateChange = {
                        bitrateKbps = it
                        selectedPreset = null
                    },
                    maxFps = maxFps,
                    onMaxFpsChange = {
                        maxFps = it
                        selectedPreset = null
                    },
                    noAudio = noAudio,
                    onNoAudioChange = {
                        noAudio = it
                        selectedPreset = null
                    },
                    turnScreenOff = turnScreenOff,
                    onTurnScreenOffChange = {
                        turnScreenOff = it
                        selectedPreset = null
                    },
                    stayAwake = stayAwake,
                    onStayAwakeChange = {
                        stayAwake = it
                        selectedPreset = null
                    },
                    onSave = {
                        scope.launch {
                            val updatedConfig = current.copy(
                                scrcpyMaxSize = maxSize,
                                scrcpyBitrateKbps = bitrateKbps
                            )
                            val res = ContainerManager.saveContainerConfig(context, updatedConfig)
                            if (res.isSuccess) {
                                container = updatedConfig
                                snackbarHostState.showSnackbar(context.getString(R.string.scrcpy_settings_saved))
                            } else {
                                snackbarHostState.showSnackbar(res.exceptionOrNull()?.message ?: "Failed to save settings")
                            }
                        }
                    }
                )
            }

            // Terminal & Custom Script Runner Card
            item {
                ScrcpyScriptRunnerCard(
                    customArgs = customArgs,
                    onCustomArgsChange = { customArgs = it },
                    onRunInBackground = { cmd ->
                        scope.launch {
                            val effectiveInfo = current.copy(
                                scrcpyMaxSize = maxSize,
                                scrcpyBitrateKbps = bitrateKbps
                            )
                            opsViewModel.executeLaunchScrcpy(
                                container = effectiveInfo,
                                noAudio = noAudio,
                                turnScreenOff = turnScreenOff,
                                stayAwake = stayAwake,
                                maxFps = maxFps,
                                extraArgs = cmd,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                            )
                        }
                    },
                    onRunInTerminal = { cmd ->
                        onNavigateToTerminalWithCommand(cmd)
                    }
                )
            }

            // In-app Guide Section
            item {
                ScrcpyGuideSection(expanded = showGuide, onToggle = { showGuide = !showGuide })
            }
        }

        // Live centralized terminal console for container operations
        opsViewModel.showLogViewerFor?.let { targetContainer ->
            if (targetContainer == containerName) {
                val memoryLogs = opsViewModel.logsFor(targetContainer)
                val prefsManager = remember { PreferencesManager.getInstance(context) }
                val cachedLogs = if (memoryLogs.isEmpty()) prefsManager.loadContainerLogs(targetContainer) else emptyList()
                val logs = memoryLogs.ifEmpty { cachedLogs }
                val isBlocking = opsViewModel.runningOperationContainer == targetContainer

                TerminalDialog(
                    title = context.getString(R.string.logs_title, targetContainer),
                    logs = logs,
                    onDismiss = {
                        opsViewModel.showLogViewerFor = null
                        scope.launch { refresh() }
                    },
                    onClear = {
                        opsViewModel.clearLogsBuffer(targetContainer)
                    },
                    isBlocking = isBlocking
                )
            }
        }
    }
}

@Composable
private fun ScrcpyHeroCard(
    status: ScrcpyManager.ScrcpyStatus?,
    busy: Boolean,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val isInstalled = status?.installed == true
    val isRunning = status?.running == true
    val hasDisplay = status?.displayMode != null && status.displayMode != "none"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row: Icon, Title, Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = when {
                            isRunning -> MaterialTheme.colorScheme.primaryContainer
                            isInstalled -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = when {
                                    isRunning -> MaterialTheme.colorScheme.primary
                                    isInstalled -> MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = context.getString(R.string.scrcpy_card_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                isRunning -> "Mirroring active (PID ${status?.pid ?: "?"})"
                                isInstalled -> "Ready to mirror screen & audio"
                                else -> "Client binary missing in container"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = when {
                        isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        isInstalled -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            isInstalled -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        }
                    )
                ) {
                    Text(
                        text = when {
                            isRunning -> "LIVE"
                            isInstalled -> "INSTALLED"
                            else -> "MISSING"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isRunning -> MaterialTheme.colorScheme.primary
                            isInstalled -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Diagnostic Status Badges Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusBadge(
                    label = "Display",
                    value = when (status?.displayMode) {
                        "anland" -> "Wayland"
                        "x11" -> "X11"
                        else -> "None"
                    },
                    isPositive = hasDisplay,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(
                    label = "ADB Target",
                    value = if (status?.adbConnected == true) "Connected" else "172.28.0.1",
                    isPositive = status?.adbConnected == true,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(
                    label = "Process",
                    value = if (isRunning) "PID ${status?.pid ?: "?"}" else "Stopped",
                    isPositive = isRunning,
                    modifier = Modifier.weight(1f)
                )
            }

            // Action Buttons
            if (!isInstalled) {
                Button(
                    onClick = onInstall,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(context.getString(R.string.scrcpy_install_button))
                    }
                }
            } else if (!isRunning) {
                Button(
                    onClick = onLaunch,
                    enabled = !busy && hasDisplay,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(context.getString(R.string.scrcpy_launch_button))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onStop,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.scrcpy_stop_button))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    label: String,
    value: String,
    isPositive: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ScrcpyPresetsCard(
    selectedPreset: ScrcpyPreset?,
    onSelectPreset: (ScrcpyPreset) -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(context.getString(R.string.scrcpy_presets_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScrcpyPreset.entries.forEach { preset ->
                    val isSelected = selectedPreset == preset
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        label = { Text(preset.label) },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrcpySettingsCard(
    maxSize: Int,
    onMaxSizeChange: (Int) -> Unit,
    bitrateKbps: Int,
    onBitrateChange: (Int) -> Unit,
    maxFps: Int,
    onMaxFpsChange: (Int) -> Unit,
    noAudio: Boolean,
    onNoAudioChange: (Boolean) -> Unit,
    turnScreenOff: Boolean,
    onTurnScreenOffChange: (Boolean) -> Unit,
    stayAwake: Boolean,
    onStayAwakeChange: (Boolean) -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(context.getString(R.string.scrcpy_stream_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            // Resolution Limit Chips & Input
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Max Resolution Limit", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0 to "Native", 720 to "720p", 1080 to "1080p", 1440 to "1440p", 2160 to "4K").forEach { (res, label) ->
                        FilterChip(
                            selected = maxSize == res,
                            onClick = { onMaxSizeChange(res) },
                            label = { Text(label) },
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }

            // Bitrate Slider & Value
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Video Bitrate", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("${bitrateKbps / 1000} Mbps (${bitrateKbps} kbps)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = (bitrateKbps / 1000).toFloat(),
                    onValueChange = { onBitrateChange((it.toInt() * 1000).coerceAtLeast(1000)) },
                    valueRange = 2f..32f,
                    steps = 14
                )
            }

            // Max FPS Selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(context.getString(R.string.scrcpy_max_fps_label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0 to "Unlimited", 30 to "30 FPS", 60 to "60 FPS", 90 to "90 FPS", 120 to "120 FPS").forEach { (fps, label) ->
                        FilterChip(
                            selected = maxFps == fps,
                            onClick = { onMaxFpsChange(fps) },
                            label = { Text(label) },
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Audio Forwarding Toggle
            SettingSwitchRow(
                title = context.getString(R.string.scrcpy_audio_forwarding),
                description = context.getString(R.string.scrcpy_audio_forwarding_desc),
                checked = !noAudio,
                onCheckedChange = { onNoAudioChange(!it) }
            )

            // Turn Screen Off Toggle
            SettingSwitchRow(
                title = context.getString(R.string.scrcpy_turn_screen_off),
                description = context.getString(R.string.scrcpy_turn_screen_off_desc),
                checked = turnScreenOff,
                onCheckedChange = onTurnScreenOffChange
            )

            // Stay Awake Toggle
            SettingSwitchRow(
                title = context.getString(R.string.scrcpy_stay_awake),
                description = context.getString(R.string.scrcpy_stay_awake_desc),
                checked = stayAwake,
                onCheckedChange = onStayAwakeChange
            )

            // Save Settings Button
            Button(
                onClick = onSave,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(context.getString(R.string.scrcpy_save_settings))
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ScrcpyScriptRunnerCard(
    customArgs: String,
    onCustomArgsChange: (String) -> Unit,
    onRunInBackground: (String) -> Unit,
    onRunInTerminal: (String) -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(context.getString(R.string.scrcpy_script_runner_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            OutlinedTextField(
                value = customArgs,
                onValueChange = onCustomArgsChange,
                label = { Text(context.getString(R.string.scrcpy_custom_args_label)) },
                placeholder = { Text(context.getString(R.string.scrcpy_custom_args_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Quick Diagnostic Snippets
            Text("Quick Diagnostics & Commands", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "adb devices -l" to "adb devices",
                    "adb connect 172.28.0.1:5555" to "adb connect",
                    "adb kill-server && adb start-server" to "restart adb",
                    "scrcpy --help" to "scrcpy help",
                    "scrcpy --show-touches" to "show touches"
                ).forEach { (cmd, label) ->
                    SuggestionChip(
                        onClick = { onRunInTerminal(cmd) },
                        label = { Text(label) },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            // Execution Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onRunInTerminal("scrcpy $customArgs") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.scrcpy_run_in_terminal))
                }

                Button(
                    onClick = { onRunInBackground(customArgs) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.scrcpy_run_in_background))
                }
            }
        }
    }
}

@Composable
private fun ScrcpyGuideSection(expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Guide & Troubleshooting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GuideStep("1. Loopback Screen Mirroring", "scrcpy connects to this phone's own adbd daemon on 172.28.0.1:5555. No USB cable, external Wi-Fi, or network setup is required.")
                    GuideStep("2. Auto-Authentication", "Droidspaces pre-authorizes the container's public ADB key directly into Android's adb_keys file for seamless connections.")
                    GuideStep("3. Display Backends", "Supports both native Wayland (Anland / KWin) and X11 (Termux:X11). Start your desktop session first so scrcpy has a display surface to open in.")
                    GuideStep("4. Useful Keyboard Shortcuts", "MOD key is Alt or Super: MOD+F (Fullscreen), MOD+H (Home), MOD+B (Back), MOD+S (App Switcher), MOD+O (Turn Device Screen Off/On).")
                    GuideStep("5. Remote / LAN Devices", "To mirror a different phone on the local network, use the Wireless Display screen with the scrcpy target IP option.")
                }
            }
        }
    }
}

@Composable
private fun GuideStep(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
