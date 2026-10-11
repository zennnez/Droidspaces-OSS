package com.droidspaces.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidspaces.app.util.*
import kotlinx.coroutines.launch

/**
 * "File Sharing" screen -- lets the user turn the Android<->Linux file bridge
 * on or off for this container, see whether it's actually connected right
 * now, manage which folders are shared, and manually check for files sent
 * from Linux via Thunar's "Send to Android" action.
 *
 * All the real work (resolving paths, writing bind_mounts, checking live
 * mount state) lives in [FileBridgeManager] / [OutboxImportChecker] -- this
 * screen is just a thin, refreshable view over that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSharingScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var config by remember { mutableStateOf(FileBridgeConfig()) }
    var status by remember { mutableStateOf<FileBridgeStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }

    suspend fun refresh() {
        config = FileBridgeManager.loadConfig(containerName)
        status = FileBridgeManager.getStatus(containerName)
    }

    LaunchedEffect(containerName) {
        refresh()
        loading = false
        // Best-effort: pick up anything already waiting in the outbox the
        // moment the user opens this screen (see OutboxImportChecker's doc
        // for why this isn't a live background watch).
        OutboxImportChecker.checkNow(context, containerName)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            showAddDialog = true
        }
    }

    if (showAddDialog && pendingUri != null) {
        AddFolderDialog(
            defaultReadOnly = config.readOnlyDefault,
            onConfirm = { label, readOnly ->
                val uri = pendingUri!!
                showAddDialog = false
                pendingUri = null
                scope.launch {
                    busy = true
                    val result = FileBridgeManager.addFolderFromUri(context, containerName, uri, label, readOnly)
                    busy = false
                    result.onSuccess {
                        refresh()
                        snackbarHostState.showSnackbar("Added \"$label\"")
                    }.onFailure { e ->
                        snackbarHostState.showSnackbar(e.message ?: "Couldn't add that folder")
                    }
                }
            },
            onDismiss = {
                showAddDialog = false
                pendingUri = null
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("File Sharing") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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
            item {
                StatusCard(
                    status = status,
                    enabled = config.enabled,
                    busy = busy,
                    onToggle = { enabled ->
                        scope.launch {
                            busy = true
                            val result = FileBridgeManager.setEnabled(context, containerName, enabled)
                            busy = false
                            result.onSuccess {
                                refresh()
                                snackbarHostState.showSnackbar(
                                    if (enabled) "File bridge enabled -- restart the container to apply"
                                    else "File bridge disabled -- restart the container to apply"
                                )
                            }.onFailure { e ->
                                snackbarHostState.showSnackbar(e.message ?: "Failed to update file bridge")
                            }
                        }
                    }
                )
            }

            if (config.enabled) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Shared folders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        FilledTonalButton(onClick = { folderPickerLauncher.launch(null) }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Folder")
                        }
                    }
                }

                if (config.folders.isEmpty()) {
                    item {
                        Text(
                            "No folders shared yet. Tap \"Add Folder\" to pick something from Android to expose in Linux.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(config.folders, key = { it.id }) { folder ->
                        val liveState = status?.folders?.firstOrNull { it.first.id == folder.id }?.second
                        SharedFolderRow(
                            folder = folder,
                            liveState = liveState,
                            onToggleReadOnly = { ro ->
                                scope.launch {
                                    FileBridgeManager.setFolderReadOnly(context, containerName, folder.id, ro)
                                    refresh()
                                }
                            },
                            onRemove = {
                                scope.launch {
                                    FileBridgeManager.removeFolder(context, containerName, folder.id)
                                    refresh()
                                    snackbarHostState.showSnackbar("Removed \"${folder.label}\"")
                                }
                            }
                        )
                    }
                }

                item {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val imported = OutboxImportChecker.checkNow(context, containerName)
                                snackbarHostState.showSnackbar(
                                    if (imported > 0) "Found $imported new file(s) from Linux" else "No new files from Linux"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Check for files sent from Linux")
                    }
                }

                item {
                    InfoCard()
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: FileBridgeStatus?,
    enabled: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val (label, color) = when {
        !enabled -> "Disabled" to MaterialTheme.colorScheme.onSurfaceVariant
        status == null -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        status.isFullyConnected -> "Connected" to Color(0xFF4CAF50)
        !status.containerRunning -> "Container not running" to Color(0xFFFFA726)
        else -> "Not fully connected" to Color(0xFFEF5350)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
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
            if (enabled && status?.containerRunning == false) {
                Text(
                    "Start the container to connect the bridge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SharedFolderRow(
    folder: SharedFolder,
    liveState: FolderLiveState?,
    onToggleReadOnly: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        when (folder.type) {
                            SharedFolderType.INTERNAL -> Icons.Default.PhoneAndroid
                            SharedFolderType.REMOVABLE -> Icons.Default.SdStorage
                            SharedFolderType.USER_SELECTED -> Icons.Default.Folder
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(folder.label, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                }
            }
            Text("Android: ${folder.androidPath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Linux: ${folder.linuxDest}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val stateText = when (liveState) {
                    FolderLiveState.MOUNTED -> "Mounted"
                    FolderLiveState.NOT_MOUNTED -> "Not mounted (restart container)"
                    else -> "Unknown"
                }
                val stateColor = when (liveState) {
                    FolderLiveState.MOUNTED -> Color(0xFF4CAF50)
                    FolderLiveState.NOT_MOUNTED -> Color(0xFFEF5350)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(stateText, style = MaterialTheme.typography.labelMedium, color = stateColor)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Read-only", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = folder.readOnly, onCheckedChange = onToggleReadOnly, modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun AddFolderDialog(
    defaultReadOnly: Boolean,
    onConfirm: (label: String, readOnly: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf("") }
    var readOnly by remember { mutableStateOf(defaultReadOnly) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("e.g. Photos") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = readOnly, onCheckedChange = { readOnly = it })
                    Text("Read-only (Linux can't modify these files)")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label.ifBlank { "Shared Folder" }, readOnly) }
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun InfoCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How this works", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                "• Android → Linux: use the share sheet on any file and pick \"Open in Droidspaces\", " +
                    "or drop files into the folders listed above.\n" +
                    "• Linux → Android: right-click a file in Thunar and choose \"Send to Android\", " +
                    "then use \"Check for files sent from Linux\" above (or reopen this screen) to pick it up.\n" +
                    "• Changes to shared folders take effect the next time the container is restarted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
