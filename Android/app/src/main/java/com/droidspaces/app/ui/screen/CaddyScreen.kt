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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidspaces.app.ui.component.LogViewerDialog
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.util.CaddyManager
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.ContainerOSInfoManager
import kotlinx.coroutines.launch

/**
 * Caddy Web & Reverse Proxy Management Screen.
 * Provides service lifecycle management across Host and Docker,
 * live Caddyfile editor with syntax validation and zero-downtime reloading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaddyScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToTerminalWithCommand: (String, String) -> Unit = { _, _ -> },
    opsViewModel: ContainerOperationsViewModel? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var status by remember { mutableStateOf(CaddyManager.CaddyStatus()) }
    var caddyfileContent by remember { mutableStateOf("") }
    var originalCaddyfile by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var validationResult by remember { mutableStateOf<CaddyManager.ValidationResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    var showLogsDialog by remember { mutableStateOf(false) }
    var caddyLogs by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }

    suspend fun refresh() {
        CaddyManager.initialize(context)
        val info = ContainerManager.getContainerInfo(containerName)
        if (info != null) {
            container = info
            if (info.isRunning) {
                status = CaddyManager.getStatus(containerName)
                val cfg = CaddyManager.getCaddyfile(containerName)
                if (caddyfileContent.isEmpty() || caddyfileContent == originalCaddyfile) {
                    caddyfileContent = cfg
                    originalCaddyfile = cfg
                }
            }
        }
    }

    LaunchedEffect(containerName) {
        refresh()
        loading = false
    }

    val hasUnsavedChanges = remember(caddyfileContent, originalCaddyfile) {
        caddyfileContent != originalCaddyfile
    }

    val presets = remember { CaddyManager.getPresetSnippets() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Caddy Reverse Proxy") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (status.isRunning) {
                        IconButton(onClick = {
                            val ip = ContainerOSInfoManager.getCachedOSInfo(containerName, context)?.ipAddress ?: "127.0.0.1"
                            val url = "http://$ip:80"
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Open Web UI", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { showLogsDialog = true }) {
                        Icon(Icons.Default.Article, contentDescription = "Logs")
                    }
                    IconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
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
            // Status Hero Card
            item {
                CaddyHeroCard(
                    status = status,
                    busy = busy,
                    onStart = {
                        scope.launch {
                            busy = true
                            CaddyManager.controlService(containerName, "start")
                            refresh()
                            busy = false
                            snackbarHostState.showSnackbar("Caddy started")
                        }
                    },
                    onStop = {
                        scope.launch {
                            busy = true
                            CaddyManager.controlService(containerName, "stop")
                            refresh()
                            busy = false
                            snackbarHostState.showSnackbar("Caddy stopped")
                        }
                    },
                    onRestart = {
                        scope.launch {
                            busy = true
                            CaddyManager.controlService(containerName, "restart")
                            refresh()
                            busy = false
                            snackbarHostState.showSnackbar("Caddy restarted")
                        }
                    },
                    onReload = {
                        scope.launch {
                            busy = true
                            CaddyManager.controlService(containerName, "reload")
                            refresh()
                            busy = false
                            snackbarHostState.showSnackbar("Caddy reloaded zero-downtime ✓")
                        }
                    }
                )
            }

            // Install banner if not installed
            if (!status.isInstalled) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Install Caddy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Caddy is an enterprise-ready, open source web server and reverse proxy with automatic HTTPS.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            CaddyManager.installHost(containerName)
                                            refresh()
                                            busy = false
                                            snackbarHostState.showSnackbar("Caddy installed on host")
                                        }
                                    },
                                    enabled = !busy
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Install on Host")
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            CaddyManager.installDocker(containerName)
                                            refresh()
                                            busy = false
                                            snackbarHostState.showSnackbar("Caddy container launched in Docker")
                                        }
                                    },
                                    enabled = !busy
                                ) {
                                    Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Run in Docker")
                                }
                            }
                        }
                    }
                }
            }

            // Live Caddyfile Editor
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Caddyfile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(status.configPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val res = CaddyManager.validateCaddyfile(containerName, caddyfileContent)
                                            validationResult = res
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Validate", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // Presets Row
                        Text("Presets", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            presets.forEach { (name, snippet) ->
                                SuggestionChip(
                                    onClick = {
                                        caddyfileContent = snippet
                                        validationResult = null
                                    },
                                    label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }

                        // Validation Message
                        validationResult?.let { res ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = if (res.isValid) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                border = BorderStroke(1.dp, if (res.isValid) Color(0xFF81C784) else Color(0xFFE57373))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        if (res.isValid) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (res.isValid) Color(0xFF2E7D32) else Color(0xFFC62828),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        res.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (res.isValid) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                                    )
                                }
                            }
                        }

                        // Editor Text Field
                        OutlinedTextField(
                            value = caddyfileContent,
                            onValueChange = {
                                caddyfileContent = it
                                validationResult = null
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 400.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasUnsavedChanges) {
                                TextButton(onClick = { caddyfileContent = originalCaddyfile }) {
                                    Text("Discard Changes")
                                }
                                Spacer(Modifier.width(8.dp))
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val res = CaddyManager.saveAndReloadCaddyfile(containerName, caddyfileContent)
                                        if (res.isSuccess) {
                                            originalCaddyfile = caddyfileContent
                                            snackbarHostState.showSnackbar("Caddyfile saved & reloaded zero-downtime ✓")
                                            refresh()
                                        } else {
                                            snackbarHostState.showSnackbar("Failed to reload: ${res.error}")
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save & Reload")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLogsDialog) {
        LogViewerDialog(
            title = "Caddy Reverse Proxy Logs",
            subtitle = containerName,
            logFileName = "${containerName}_caddy.log",
            fetchLogs = {
                val lines = CaddyManager.getLogs(containerName, 150)
                if (lines.isEmpty()) "[Caddy] No log entries recorded yet." else lines.joinToString("\n")
            },
            onDismiss = { showLogsDialog = false }
        )
    }
}

@Composable
private fun CaddyHeroCard(
    status: CaddyManager.CaddyStatus,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onReload: () -> Unit
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = if (status.isRunning) Color(0xFF2E7D32).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                tint = if (status.isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Column {
                        Text("Caddy Server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (status.ports.isNotBlank()) "Listening on ${status.ports}" else if (status.isRunning) "Running" else "Stopped",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Mode badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            status.mode.uppercase(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    // Status badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (status.isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            if (status.isRunning) "ACTIVE" else "STOPPED",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (status.isRunning) Color.White else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Controls Row
            if (status.isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (status.isRunning) {
                        OutlinedButton(
                            onClick = onStop,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop")
                        }
                        OutlinedButton(
                            onClick = onRestart,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Restart")
                        }
                        Button(
                            onClick = onReload,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Autorenew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reload")
                        }
                    } else {
                        Button(
                            onClick = onStart,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start Caddy")
                        }
                    }
                }
            }
        }
    }
}
