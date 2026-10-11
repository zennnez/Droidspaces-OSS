package com.droidspaces.app.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.ui.component.LogViewerDialog
import com.droidspaces.app.ui.component.TerminalDialog
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.TailscaleManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tailscale & Headscale Management Screen.
 * Enhanced with interactive login URLs, latency diagnostics, advanced routing flags,
 * and embedded Docker Headscale controller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToTerminalWithCommand: (String, String) -> Unit = { _, _ -> },
    opsViewModel: ContainerOperationsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var status by remember { mutableStateOf<TailscaleManager.TailscaleStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    // Ping diagnostic
    var pingResult by remember { mutableStateOf<String?>(null) }
    var pinging by remember { mutableStateOf(false) }

    // Tailscale Up form
    var loginServer by remember { mutableStateOf("") }
    var authKey by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("") }
    var acceptRoutes by remember { mutableStateOf(true) }
    var enableSsh by remember { mutableStateOf(false) }
    var exitNode by remember { mutableStateOf("") }
    var advExitNode by remember { mutableStateOf(false) }
    var advRoutes by remember { mutableStateOf("") }
    var resetSettings by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    // Headscale Docker dialog
    var showHeadscaleDialog by remember { mutableStateOf(false) }
    var showLogViewer by remember { mutableStateOf(false) }

    suspend fun refresh() {
        TailscaleManager.initialize(context)
        val info = ContainerManager.getContainerInfo(containerName)
        if (info != null) {
            container = info
            if (info.isRunning) {
                status = TailscaleManager.getStatus(containerName)
            }
        }
    }

    LaunchedEffect(containerName) {
        refresh()
        loading = false
        while (true) {
            delay(4000)
            if (container?.isRunning == true && !busy) {
                status = TailscaleManager.getStatus(containerName)
            }
        }
    }

    val current = container
    val currentStatus = status

    // Live streaming log viewer dialog
    if (showLogViewer) {
        LogViewerDialog(
            title = "Tailscale / Headscale Logs",
            subtitle = containerName,
            logFileName = "${containerName}_tailscale.log",
            fetchLogs = {
                TailscaleManager.getLogs(containerName)
            },
            onDismiss = { showLogViewer = false }
        )
    }

    // Centralized Log Viewer Dialog
    if (opsViewModel.showLogViewerFor == containerName) {
        TerminalDialog(
            title = "Tailscale / Headscale Operation Logs",
            logs = opsViewModel.logsFor(containerName),
            isBlocking = opsViewModel.runningOperationContainer == containerName,
            onDismiss = { opsViewModel.showLogViewerFor = null },
            onClear = {
                opsViewModel.clearLogsBuffer(containerName)
                scope.launch { refresh() }
            }
        )
    }

    // Headscale in Docker Management Dialog
    if (showHeadscaleDialog && current != null) {
        HeadscaleDockerDialog(
            containerName = containerName,
            onDismiss = { showHeadscaleDialog = false },
            onNavigateToTerminal = onNavigateToTerminalWithCommand,
            snackbarHostState = snackbarHostState
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Tailscale & Headscale") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onNavigateToTerminalWithCommand(containerName, "tailscale status || tailscale --help")
                    }) {
                        Icon(Icons.Default.Terminal, contentDescription = "Terminal")
                    }
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

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Interactive Web Login URL Banner (if printed by tailscale up)
            if (currentStatus?.authUrl?.isNotBlank() == true) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Action Required: Complete Authentication", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "Tailscale is awaiting node approval. Tap below to log in with your browser.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentStatus.authUrl))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        scope.launch { snackbarHostState.showSnackbar("Could not open browser: ${e.message}") }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open Login in Browser")
                            }
                        }
                    }
                }
            }

            // Status Hero Card
            item {
                TailscaleHeroCard(
                    status = currentStatus,
                    busy = busy || opsViewModel.runningOperationContainer == containerName,
                    pingResult = pingResult,
                    pinging = pinging,
                    onPing = {
                        scope.launch {
                            pinging = true
                            val res = TailscaleManager.ping(containerName, "100.100.100.100")
                            pingResult = if (res.isSuccess) {
                                res.output.firstOrNull { it.contains("ms") || it.contains("pong") } ?: "Reachable ✓"
                            } else {
                                "Unreachable"
                            }
                            pinging = false
                        }
                    },
                    onInstall = {
                        scope.launch {
                            busy = true
                            opsViewModel.executeInstallTailscale(
                                container = current,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { scope.launch { snackbarHostState.showSnackbar(it) } },
                                onSuccess = { scope.launch { snackbarHostState.showSnackbar(it) } }
                            )
                            busy = false
                        }
                    },
                    onDisconnect = {
                        scope.launch {
                            busy = true
                            opsViewModel.executeTailscaleDown(
                                container = current,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { scope.launch { snackbarHostState.showSnackbar(it) } },
                                onSuccess = { scope.launch { snackbarHostState.showSnackbar(it) } }
                            )
                            busy = false
                        }
                    }
                )
            }

            // Connection & Up Controls
            item {
                TailscaleConnectCard(
                    loginServer = loginServer,
                    onLoginServerChange = { loginServer = it },
                    authKey = authKey,
                    onAuthKeyChange = { authKey = it },
                    hostname = hostname,
                    onHostnameChange = { hostname = it },
                    acceptRoutes = acceptRoutes,
                    onAcceptRoutesChange = { acceptRoutes = it },
                    enableSsh = enableSsh,
                    onEnableSshChange = { enableSsh = it },
                    exitNode = exitNode,
                    onExitNodeChange = { exitNode = it },
                    advExitNode = advExitNode,
                    onAdvExitNodeChange = { advExitNode = it },
                    advRoutes = advRoutes,
                    onAdvRoutesChange = { advRoutes = it },
                    resetSettings = resetSettings,
                    onResetSettingsChange = { resetSettings = it },
                    showAdvanced = showAdvanced,
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                    busy = busy || opsViewModel.runningOperationContainer == containerName,
                    onConnect = {
                        scope.launch {
                            busy = true
                            opsViewModel.executeTailscaleUp(
                                container = current,
                                loginServer = loginServer,
                                authKey = authKey,
                                hostname = hostname,
                                acceptRoutes = acceptRoutes,
                                enableSsh = enableSsh,
                                exitNode = exitNode,
                                advExitNode = advExitNode,
                                advRoutes = advRoutes,
                                reset = resetSettings,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { scope.launch { snackbarHostState.showSnackbar(it) } },
                                onSuccess = { scope.launch { snackbarHostState.showSnackbar(it) } }
                            )
                            busy = false
                        }
                    }
                )
            }

            // Docker Headscale Management Card
            item {
                HeadscaleIntegrationCard(
                    onOpenHeadscaleManager = { showHeadscaleDialog = true },
                    onNavigateToTerminal = onNavigateToTerminalWithCommand,
                    containerName = containerName
                )
            }

            // Quick Diagnostic Script Runner
            item {
                TailscaleScriptRunnerCard(
                    containerName = containerName,
                    onNavigateToTerminal = onNavigateToTerminalWithCommand,
                    onRunCommand = { cmd ->
                        scope.launch {
                            val logs = opsViewModel.logsFor(containerName)
                            opsViewModel.showLogViewerFor = containerName
                            logs.add(0 to "$ $cmd")
                            val res = TailscaleManager.runScript(containerName, cmd)
                            res.output.forEach { logs.add(1 to it) }
                            if (!res.isSuccess) res.error.forEach { logs.add(2 to it) }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TailscaleHeroCard(
    status: TailscaleManager.TailscaleStatus?,
    busy: Boolean,
    pingResult: String?,
    pinging: Boolean,
    onPing: () -> Unit,
    onInstall: () -> Unit,
    onDisconnect: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val isInstalled = status?.installed == true
    val isConnected = status?.connected == true

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
            // Header Row
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
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Column {
                        Text("Tailscale Mesh VPN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (isConnected) "Connected & Routing" else if (isInstalled) "Installed / Idle" else "Not Installed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isConnected) Color(0xFF2E7D32).copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = if (isConnected) "ONLINE" else if (isInstalled) "IDLE" else "OFFLINE",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                }
            }

            if (isConnected && status?.ip4?.isNotBlank() == true) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Tailscale IPv4", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(status.ip4, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(status.ip4)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy IP")
                        }
                    }
                }
            }

            if (isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = pingResult ?: "Latency Check",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    OutlinedButton(
                        onClick = onPing,
                        enabled = !pinging,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        if (pinging) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Ping MagicDNS", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
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
                        Text("Install Tailscale")
                    }
                }
            } else if (isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
private fun TailscaleConnectCard(
    loginServer: String,
    onLoginServerChange: (String) -> Unit,
    authKey: String,
    onAuthKeyChange: (String) -> Unit,
    hostname: String,
    onHostnameChange: (String) -> Unit,
    acceptRoutes: Boolean,
    onAcceptRoutesChange: (Boolean) -> Unit,
    enableSsh: Boolean,
    onEnableSshChange: (Boolean) -> Unit,
    exitNode: String,
    onExitNodeChange: (String) -> Unit,
    advExitNode: Boolean,
    onAdvExitNodeChange: (Boolean) -> Unit,
    advRoutes: String,
    onAdvRoutesChange: (String) -> Unit,
    resetSettings: Boolean,
    onResetSettingsChange: (Boolean) -> Unit,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    busy: Boolean,
    onConnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Connect / Tailscale Up Options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            OutlinedTextField(
                value = loginServer,
                onValueChange = onLoginServerChange,
                label = { Text("Headscale / Login Server URL (Optional)") },
                placeholder = { Text("https://headscale.example.com (blank for official Tailscale)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = authKey,
                onValueChange = onAuthKeyChange,
                label = { Text("Pre-auth Key / Authkey (Optional)") },
                placeholder = { Text("tskey-auth-... (for non-interactive login)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = hostname,
                onValueChange = onHostnameChange,
                label = { Text("Custom Node Hostname (Optional)") },
                placeholder = { Text("e.g. droidspaces-pad3") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Accept Subnet Routes", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Route traffic through tailnet subnet routers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = acceptRoutes, onCheckedChange = onAcceptRoutesChange)
            }

            TextButton(
                onClick = onToggleAdvanced,
                modifier = Modifier.align(Alignment.Start)
            ) {
                Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (showAdvanced) "Hide Advanced Routing Options" else "Show Advanced Routing Options")
            }

            if (showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Tailscale SSH", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Allow SSH access directly over MagicDNS (--ssh)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = enableSsh, onCheckedChange = onEnableSshChange)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Advertise Exit Node", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Act as internet gateway for other tailnet devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = advExitNode, onCheckedChange = onAdvExitNodeChange)
                    }

                    OutlinedTextField(
                        value = exitNode,
                        onValueChange = onExitNodeChange,
                        label = { Text("Use Specific Exit Node IP/Name (Optional)") },
                        placeholder = { Text("e.g. 100.x.y.z") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = advRoutes,
                        onValueChange = onAdvRoutesChange,
                        label = { Text("Advertise Subnet Routes (Optional)") },
                        placeholder = { Text("e.g. 192.168.1.0/24") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Reset Settings", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Reset previous tailscale up flags (--reset)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = resetSettings, onCheckedChange = onResetSettingsChange)
                    }
                }
            }

            Button(
                onClick = onConnect,
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Run 'tailscale up'")
            }
        }
    }
}

@Composable
private fun HeadscaleIntegrationCard(
    onOpenHeadscaleManager: () -> Unit,
    onNavigateToTerminal: (String, String) -> Unit,
    containerName: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Self-Hosted Headscale (Docker)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Text(
                "Manage a Headscale server running in Docker directly in-app, inspect nodes, list users, and generate pre-auth keys without needing an external web dashboard (Headplane).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onOpenHeadscaleManager,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Manage Headscale")
                }

                OutlinedButton(
                    onClick = { onNavigateToTerminal(containerName, "docker ps | grep -i headscale") },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Docker CLI")
                }
            }
        }
    }
}

@Composable
private fun TailscaleScriptRunnerCard(
    containerName: String,
    onNavigateToTerminal: (String, String) -> Unit,
    onRunCommand: (String) -> Unit
) {
    var customCommand by remember { mutableStateOf("") }
    val snippets = listOf("status", "ip -4", "netcheck", "ping 100.100.100.100", "version")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Tailscale Diagnostic Runner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                snippets.forEach { snippet ->
                    SuggestionChip(
                        onClick = { customCommand = "tailscale $snippet" },
                        label = { Text(snippet, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            OutlinedTextField(
                value = customCommand,
                onValueChange = { customCommand = it },
                label = { Text("Command") },
                placeholder = { Text("tailscale status --json") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val cmd = customCommand.ifBlank { "tailscale status" }
                        onRunCommand(cmd)
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Run with Logs")
                }

                OutlinedButton(
                    onClick = {
                        val cmd = customCommand.ifBlank { "tailscale status" }
                        onNavigateToTerminal(containerName, cmd)
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Terminal")
                }
            }
        }
    }
}

@Composable
private fun HeadscaleDockerDialog(
    containerName: String,
    onDismiss: () -> Unit,
    onNavigateToTerminal: (String, String) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var output by remember { mutableStateOf("Loading Headscale details...") }
    var targetUser by remember { mutableStateOf("default") }
    var newUser by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("24h") }
    var reusable by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }

    fun refreshHeadscale() {
        scope.launch {
            val nodes = TailscaleManager.listHeadscaleNodes(containerName)
            val users = TailscaleManager.listHeadscaleUsers(containerName)
            output = "=== USERS ===\n" + (users.joinToString("\n").ifBlank { "(none)" }) +
                "\n\n=== NODES ===\n" + (nodes.joinToString("\n").ifBlank { "(none)" })
        }
    }

    LaunchedEffect(Unit) {
        refreshHeadscale()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Headscale Docker Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                ) {
                    Text(
                        text = output,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newUser,
                        onValueChange = { newUser = it },
                        label = { Text("Create User") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newUser.isNotBlank()) {
                                scope.launch {
                                    isBusy = true
                                    TailscaleManager.createHeadscaleUser(containerName, newUser.trim())
                                    snackbarHostState.showSnackbar("User '$newUser' created")
                                    newUser = ""
                                    refreshHeadscale()
                                    isBusy = false
                                }
                            }
                        },
                        enabled = !isBusy && newUser.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Text("Add")
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = targetUser,
                        onValueChange = { targetUser = it },
                        label = { Text("User for Key") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = expiry,
                        onValueChange = { expiry = it },
                        label = { Text("Expiry") },
                        modifier = Modifier.width(100.dp),
                        singleLine = true
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reusable Pre-auth Key", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = reusable, onCheckedChange = { reusable = it })
                }

                Button(
                    onClick = {
                        scope.launch {
                            isBusy = true
                            val res = TailscaleManager.createHeadscalePreauthKey(
                                containerName,
                                targetUser.ifBlank { "default" },
                                expiry.ifBlank { "24h" },
                                reusable
                            )
                            val key = res.output.firstOrNull { it.isNotBlank() && !it.contains("create") } ?: res.output.joinToString("\n")
                            output = "=== PRE-AUTH KEY CREATED ===\n$key\n\n$output"
                            clipboard.setText(AnnotatedString(key))
                            snackbarHostState.showSnackbar("Pre-auth key generated and copied to clipboard!")
                            isBusy = false
                        }
                    },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Generate & Copy Pre-auth Key")
                }
            }
        }
    }
}
