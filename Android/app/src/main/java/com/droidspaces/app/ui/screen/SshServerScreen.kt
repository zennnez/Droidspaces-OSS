package com.droidspaces.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.R
import com.droidspaces.app.ui.component.DsDropdown
import com.droidspaces.app.ui.component.LogViewerDialog
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.ContainerUsersManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "SSH Server" management screen -- designed to match FileSharingScreen layout.
 * Displays individual thematic cards for Status, Connection Details, User & Authentication,
 * and Help/Guide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshServerScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    opsViewModel: ContainerOperationsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var status by remember { mutableStateOf<ContainerManager.SshStatus?>(null) }
    var availableUsers by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    var showKeysDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showLogViewer by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val updated = ContainerManager.getContainerInfo(containerName)
        if (updated != null) {
            container = updated
            status = ContainerManager.getSshStatus(context, updated)
            val fetched = ContainerUsersManager.getUsers(updated.name)
            val defaultU = updated.defaultUsername.takeIf { it.isNotBlank() }
            val base = listOfNotNull("root", defaultU)
            availableUsers = (base + fetched).distinct()
        }
    }

    LaunchedEffect(containerName) {
        refresh()
        loading = false
        while (true) {
            delay(3000)
            if (container?.isRunning == true) {
                val current = container
                if (current != null) {
                    status = ContainerManager.getSshStatus(context, current)
                }
            }
        }
    }

    val current = container

    if (showLogViewer) {
        LogViewerDialog(
            title = "OpenSSH Server Logs",
            subtitle = containerName,
            logFileName = "${containerName}_sshd.log",
            fetchLogs = {
                ContainerManager.getSshLogs(containerName)
            },
            onDismiss = { showLogViewer = false }
        )
    }

    if (showKeysDialog && current != null) {
        ManageSshKeysDialog(
            container = current,
            onDismiss = { showKeysDialog = false },
            onKeysChanged = { scope.launch { refresh() } },
            snackbarHostState = snackbarHostState
        )
    }

    if (showPasswordDialog && current != null) {
        val activeUser = if (current.sshUser.isNotBlank() && availableUsers.contains(current.sshUser)) {
            current.sshUser
        } else if (current.defaultUsername.isNotBlank() && availableUsers.contains(current.defaultUsername)) {
            current.defaultUsername
        } else {
            availableUsers.firstOrNull { it != "root" } ?: "root"
        }
        SetPasswordDialog(
            container = current,
            username = activeUser,
            onDismiss = { showPasswordDialog = false },
            onContainerChanged = { updated ->
                container = updated
                scope.launch { refresh() }
            },
            snackbarHostState = snackbarHostState
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("SSH Server") },
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

        val activeUser = if (current.sshUser.isNotBlank() && availableUsers.contains(current.sshUser)) {
            current.sshUser
        } else if (current.defaultUsername.isNotBlank() && availableUsers.contains(current.defaultUsername)) {
            current.defaultUsername
        } else {
            availableUsers.firstOrNull { it != "root" } ?: "root"
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Status Card
            item {
                StatusCard(
                    status = status,
                    enabled = current.sshEnabled,
                    containerRunning = current.isRunning,
                    busy = busy,
                    onToggle = { enabled ->
                        scope.launch {
                            busy = true
                            val updated = current.copy(sshEnabled = enabled)
                            val saveRes = ContainerManager.saveContainerConfig(context, updated)
                            if (saveRes.isSuccess) {
                                container = updated
                                if (updated.isRunning) {
                                    if (enabled) {
                                        ContainerManager.installSshServer(context, updated)
                                        ContainerManager.sshServiceControl(updated, "start")
                                    } else {
                                        ContainerManager.sshServiceControl(updated, "stop")
                                    }
                                }
                                refresh()
                                snackbarHostState.showSnackbar(
                                    if (enabled) "SSH Server enabled" else "SSH Server disabled"
                                )
                            } else {
                                snackbarHostState.showSnackbar("Failed to update SSH configuration")
                            }
                            busy = false
                        }
                    },
                    onStart = {
                        scope.launch {
                            busy = true
                            val res = ContainerManager.sshServiceControl(current, "start")
                            if (res.isSuccess) {
                                snackbarHostState.showSnackbar("SSH server started")
                            } else {
                                snackbarHostState.showSnackbar("Failed to start: ${res.exceptionOrNull()?.message}")
                            }
                            refresh()
                            busy = false
                        }
                    },
                    onStop = {
                        scope.launch {
                            busy = true
                            val res = ContainerManager.sshServiceControl(current, "stop")
                            if (res.isSuccess) {
                                snackbarHostState.showSnackbar("SSH server stopped")
                            } else {
                                snackbarHostState.showSnackbar("Failed to stop: ${res.exceptionOrNull()?.message}")
                            }
                            refresh()
                            busy = false
                        }
                    },
                    onRestart = {
                        scope.launch {
                            busy = true
                            val res = ContainerManager.sshServiceControl(current, "restart")
                            if (res.isSuccess) {
                                snackbarHostState.showSnackbar("SSH server restarted")
                            } else {
                                snackbarHostState.showSnackbar("Failed to restart: ${res.exceptionOrNull()?.message}")
                            }
                            refresh()
                            busy = false
                        }
                    }
                )
            }

            if (current.sshEnabled) {
                // 2. Connection Details Card
                item {
                    ConnectionDetailsCard(
                        container = current,
                        activeUser = activeUser,
                        status = status,
                        onCopy = { text, label ->
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbarHostState.showSnackbar("$label copied to clipboard") }
                        }
                    )
                }

                // 3. User & Authentication Card
                item {
                    UserAndAuthCard(
                        container = current,
                        activeUser = activeUser,
                        availableUsers = availableUsers,
                        busy = busy,
                        onSelectUser = { newUser ->
                            scope.launch {
                                busy = true
                                val updated = current.copy(sshUser = newUser)
                                ContainerManager.saveContainerConfig(context, updated)
                                ContainerManager.installSshServer(context, updated)
                                container = updated
                                refresh()
                                snackbarHostState.showSnackbar("SSH user updated to '$newUser'")
                                busy = false
                            }
                        },
                        onManageKeys = { showKeysDialog = true },
                        onSetPassword = { showPasswordDialog = true },
                        onTogglePasswordAuth = { allowPassword ->
                            scope.launch {
                                busy = true
                                val updated = current.copy(sshPasswordAuthEnabled = allowPassword)
                                ContainerManager.saveContainerConfig(context, updated)
                                if (updated.isRunning) {
                                    ContainerManager.syncSshPasswordAuth(updated)
                                }
                                container = updated
                                refresh()
                                snackbarHostState.showSnackbar(
                                    if (allowPassword) "Password authentication enabled" else "Password authentication disabled"
                                )
                                busy = false
                            }
                        }
                    )
                }

                // 4. LAN Only Access Setting Card
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
                                    "Restrict incoming SSH connections to private local network subnets only.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = current.sshLanOnly,
                                enabled = !busy,
                                onCheckedChange = { lanOnly ->
                                    scope.launch {
                                        busy = true
                                        val updated = current.copy(sshLanOnly = lanOnly)
                                        ContainerManager.saveContainerConfig(context, updated)
                                        if (updated.isRunning && updated.sshEnabled) {
                                            ContainerManager.installSshServer(context, updated)
                                            ContainerManager.sshServiceControl(updated, "restart")
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

                // 4. Setup / Installation Banner (if unconfigured)
                if (status?.configured == false && current.isRunning) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("OpenSSH Setup Required", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "OpenSSH server has not been configured inside this container yet. Tap below to automatically install packages and deploy sshd.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            val res = ContainerManager.installSshServer(context, current)
                                            if (res.isSuccess) {
                                                snackbarHostState.showSnackbar("SSH server installed successfully!")
                                            } else {
                                                snackbarHostState.showSnackbar("Installation failed: ${res.exceptionOrNull()?.message}")
                                            }
                                            refresh()
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Install & Configure SSH Server")
                                }
                            }
                        }
                    }
                }
            }

            // 5. Help / How this works Card
            item {
                InfoCard(sshPort = current.sshPort, activeUser = activeUser)
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: ContainerManager.SshStatus?,
    enabled: Boolean,
    containerRunning: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val isRunning = status?.serviceRunning == true
    val (label, color) = when {
        !enabled -> "Disabled" to MaterialTheme.colorScheme.onSurfaceVariant
        !containerRunning -> "Container stopped" to Color(0xFFFFA726)
        status == null -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        isRunning -> "Running" to Color(0xFF4CAF50)
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Switch(checked = enabled, onCheckedChange = onToggle)
                }
            }

            if (enabled && !containerRunning) {
                Text(
                    "Start the container to run the SSH server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (enabled && containerRunning && status?.configured == true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isRunning) {
                        OutlinedButton(
                            onClick = onStop,
                            enabled = !busy,
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
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start")
                        }
                    }

                    OutlinedButton(
                        onClick = onRestart,
                        enabled = !busy && isRunning,
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
}

@Composable
private fun ConnectionDetailsCard(
    container: ContainerInfo,
    activeUser: String,
    status: ContainerManager.SshStatus?,
    onCopy: (String, String) -> Unit,
) {
    val lanAddr = status?.lanAddress
    val rawHost = lanAddr?.substringBefore(':') ?: "127.0.0.1"
    val port = container.sshPort
    val sshCommand = "ssh $activeUser@$rawHost -p $port"

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
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("Connection Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        "Port $port",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Copyable Command Row
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
                        Text("SSH Command", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            sshCommand,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { onCopy(sshCommand, "SSH command") }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy command", modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Host & LAN Information
            if (lanAddr != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("LAN Host Address", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(lanAddr, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { onCopy(lanAddr, "LAN address") }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy LAN address", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            } else if (container.isRunning) {
                Text(
                    "Not on Wi-Fi -- connect to the local network to view client connection address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // mDNS Tag
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "mDNS: droidspaces-${container.name}.local",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun UserAndAuthCard(
    container: ContainerInfo,
    activeUser: String,
    availableUsers: List<String>,
    busy: Boolean,
    onSelectUser: (String) -> Unit,
    onManageKeys: () -> Unit,
    onSetPassword: () -> Unit,
    onTogglePasswordAuth: (Boolean) -> Unit,
) {
    val userOptions = (listOf(activeUser) + availableUsers).distinct()
    val hasKeys = container.sshAuthorizedKeys.isNotEmpty()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("User & Authentication", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            // User selector dropdown
            DsDropdown(
                label = "Login User",
                selected = activeUser,
                options = userOptions,
                displayName = { it },
                onSelect = onSelectUser,
                enabled = !busy
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Password Auth Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Password Authentication", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Allow logging in with user password", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = container.sshPasswordAuthEnabled,
                    onCheckedChange = onTogglePasswordAuth,
                    enabled = !busy
                )
            }

            // Password & Key Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onSetPassword,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Set Password", style = MaterialTheme.typography.labelMedium)
                }

                FilledTonalButton(
                    onClick = onManageKeys,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (hasKeys) "Keys (${container.sshAuthorizedKeys.size})" else "Add Keys",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(sshPort: Int, activeUser: String) {
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
            Text(
                "• Connect via terminal / PowerShell:\n" +
                "  ssh " + activeUser + "@<phone-ip> -p " + sshPort + "\n" +
                "• Public Key Authentication:\n" +
                "  Add your client public key (e.g. id_ed25519.pub) via 'Manage Keys' for secure passwordless login.\n" +
                "• Remote VS Code & Termius:\n" +
                "  Use the phone's LAN IP with port " + sshPort + " and username '" + activeUser + "'.\n" +
                "• LAN Only Forwarding:\n" +
                "  Ports are forwarded on private Wi-Fi networks. Containers can also advertise over mDNS automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SetPasswordDialog(
    container: ContainerInfo,
    username: String,
    onDismiss: () -> Unit,
    onContainerChanged: (ContainerInfo) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "SSH Password for '$username'",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Text(
                    text = if (container.isRunning) {
                        "Set a new password for user '$username' in container '${container.name}'."
                    } else {
                        "Start '${container.name}' to set or update live user passwords."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (container.isRunning) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("New Password") },
                        placeholder = { Text("Enter password") },
                        singleLine = true,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss, enabled = !isSaving) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                val updated = container.copy(sshPasswordAuthEnabled = true)
                                ContainerManager.saveContainerConfig(context, updated)
                                if (password.isNotBlank() && container.isRunning) {
                                    val res = withContext(Dispatchers.IO) {
                                        ContainerManager.setUserPassword(container.name, username, password)
                                    }
                                    if (res.isSuccess) {
                                        if (container.sshEnabled) {
                                            ContainerManager.syncSshPasswordAuth(updated)
                                        }
                                        onContainerChanged(updated)
                                        snackbarHostState.showSnackbar("Password updated for '$username'")
                                        onDismiss()
                                    } else {
                                        snackbarHostState.showSnackbar("Failed to set password: ${res.exceptionOrNull()?.message}")
                                    }
                                } else {
                                    onContainerChanged(updated)
                                    onDismiss()
                                }
                                isSaving = false
                            }
                        },
                        enabled = !isSaving && (password.isNotBlank() || !container.isRunning)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save Password")
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageSshKeysDialog(
    container: ContainerInfo,
    onDismiss: () -> Unit,
    onKeysChanged: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var newKey by remember { mutableStateOf("") }
    var isAdding by remember { mutableStateOf(false) }
    var currentContainer by remember { mutableStateOf(container) }
    var generatedKeyDetails by remember { mutableStateOf<ContainerManager.GeneratedSshKey?>(null) }

    Dialog(
        onDismissRequest = { if (!isAdding) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Authorized SSH Keys",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = onDismiss, enabled = !isAdding) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Text(
                    text = "Add public keys (id_ed25519.pub / id_rsa.pub) to allow passwordless SSH login.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = newKey,
                    onValueChange = { newKey = it },
                    label = { Text("Paste public key") },
                    placeholder = { Text("ssh-ed25519 AAAAC3... user@host") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    singleLine = false,
                    isError = newKey.isNotBlank() && !ContainerManager.isValidSshPublicKey(newKey)
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isAdding = true
                                val result = ContainerManager.generateSshKeypair(context, currentContainer, "${currentContainer.sshUser.ifBlank { "zen" }}@droidspaces-auto")
                                result.onSuccess { (updated, generatedKey) ->
                                    currentContainer = updated
                                    onKeysChanged()
                                    generatedKeyDetails = generatedKey
                                    snackbarHostState.showSnackbar("New SSH Keypair generated and authorized!")
                                }.onFailure { e ->
                                    snackbarHostState.showSnackbar("Key generation failed: ${e.message}")
                                }
                                isAdding = false
                            }
                        },
                        enabled = !isAdding,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generate Key")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isAdding = true
                                val result = ContainerManager.addSshKey(context, currentContainer, newKey)
                                result.onSuccess { updated ->
                                    currentContainer = updated
                                    newKey = ""
                                    onKeysChanged()
                                    snackbarHostState.showSnackbar("SSH key added and synced")
                                }.onFailure { e ->
                                    snackbarHostState.showSnackbar("Failed to add key: ${e.message}")
                                }
                                isAdding = false
                            }
                        },
                        enabled = !isAdding && ContainerManager.isValidSshPublicKey(newKey),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Key")
                    }
                }

                HorizontalDivider()

                if (currentContainer.sshAuthorizedKeys.isEmpty()) {
                    Text(
                        "No authorized keys added yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        items(currentContainer.sshAuthorizedKeys.size) { idx ->
                            val key = currentContainer.sshAuthorizedKeys[idx]
                            val parts = key.split(Regex(" +"))
                            val keyType = parts.getOrNull(0) ?: "key"
                            val comment = if (parts.size >= 3) parts.drop(2).joinToString(" ") else ""
                            val fingerprint = if (parts.size >= 2 && parts[1].length > 16) {
                                parts[1].take(8) + "..." + parts[1].takeLast(8)
                            } else key

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (comment.isNotBlank()) comment else keyType,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            "$keyType $fingerprint",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                val result = ContainerManager.removeSshKey(context, currentContainer, key)
                                                result.onSuccess { updated ->
                                                    currentContainer = updated
                                                    onKeysChanged()
                                                    snackbarHostState.showSnackbar("SSH key removed")
                                                }.onFailure { e ->
                                                    snackbarHostState.showSnackbar("Failed to remove key: ${e.message}")
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove key",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }

    if (generatedKeyDetails != null) {
        GeneratedKeyGuideDialog(
            container = container,
            keyDetails = generatedKeyDetails!!,
            onDismiss = { generatedKeyDetails = null },
            snackbarHostState = snackbarHostState
        )
    }
}

@Composable
private fun GeneratedKeyGuideDialog(
    container: ContainerInfo,
    keyDetails: ContainerManager.GeneratedSshKey,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val hostIp = ContainerManager.getPhoneLanIpAddress(context) ?: "<device-ip>"
    val user = container.sshUser.ifBlank { container.defaultUsername.ifBlank { "zen" } }
    val port = container.sshPort

    val sshCommand = "ssh -i ${keyDetails.keyName} $user@$hostIp -p $port"
    val sshConfigSnippet = """
Host droidspaces
    HostName $hostIp
    User $user
    Port $port
    IdentityFile ~/.ssh/${keyDetails.keyName}
    """.trimIndent()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Generated SSH Keypair", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }

                Text(
                    text = "Public key has been automatically added to authorized_keys in the container. Download or copy the private key to connect from your computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Quick Connect Command", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "chmod 600 ${keyDetails.keyName}\n$sshCommand",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("~/.ssh/config snippet", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = sshConfigSnippet,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val res = ContainerManager.exportKeyToDownloads(context, keyDetails.keyName, keyDetails.privateKey)
                            res.onSuccess { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }.onFailure { e ->
                                scope.launch { snackbarHostState.showSnackbar("Export failed: ${e.message}") }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download Key")
                    }

                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(keyDetails.privateKey))
                            scope.launch { snackbarHostState.showSnackbar("Private key copied to clipboard") }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy Key")
                    }
                }
            }
        }
    }
}
