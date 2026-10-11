package com.droidspaces.app.ui.screen

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.R
import com.droidspaces.app.ui.component.DsTextFieldDefaults
import com.droidspaces.app.ui.component.TerminalDialog
import com.droidspaces.app.ui.util.LoadingIndicator
import com.droidspaces.app.ui.util.LoadingSize
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.ContainerSnapshot
import com.droidspaces.app.util.ContainerSnapshotManager
import com.droidspaces.app.util.MoveStorageOperations
import com.droidspaces.app.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotsScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    opsViewModel: ContainerOperationsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var snapshots by remember { mutableStateOf<List<ContainerSnapshot>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Creation State
    var showCreateForm by remember { mutableStateOf(false) }
    var newSnapshotLabel by remember { mutableStateOf("") }
    var newSnapshotDesc by remember { mutableStateOf("") }
    var enableCompression by remember { mutableStateOf(false) }
    var compressionLevel by remember { mutableFloatStateOf(6f) }
    var isCreating by remember { mutableStateOf(false) }

    // Verification map (snapshot ID -> status string)
    var verifiedStatusMap by remember { mutableStateOf<Map<String, Pair<Boolean, String>>>(emptyMap()) }
    var verifyingId by remember { mutableStateOf<String?>(null) }

    // Confirmation Dialogs
    var confirmRollbackSnapshot by remember { mutableStateOf<ContainerSnapshot?>(null) }
    var confirmDeleteSnapshot by remember { mutableStateOf<ContainerSnapshot?>(null) }

    suspend fun reloadData() {
        container = ContainerManager.getContainerInfo(containerName)
        val cur = container
        if (cur != null) {
            snapshots = withContext(Dispatchers.IO) {
                ContainerSnapshotManager.listSnapshots(cur)
            }
        }
    }

    LaunchedEffect(containerName) {
        isLoading = true
        reloadData()
        isLoading = false
    }

    val totalBytes = snapshots.sumOf { it.sizeBytes }
    val latestDate = snapshots.firstOrNull()?.formattedDate ?: "None"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Snapshots & Rollback",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = containerName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            isLoading = true
                            reloadData()
                            isLoading = false
                            snackbarHostState.showSnackbar("Snapshots refreshed")
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Summary Status Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Container Recovery Points",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (snapshots.isEmpty()) "No snapshots saved yet" else "${snapshots.size} snapshot(s) available for rollback",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Total Snapshots", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${snapshots.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Disk Usage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(MoveStorageOperations.formatBytes(totalBytes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Latest Point", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(latestDate, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            // 2. Create Snapshot Form Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (!isCreating) showCreateForm = !showCreateForm },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(
                                    imageVector = Icons.Default.AddCircleOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = "Create New Snapshot",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                imageVector = if (showCreateForm) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        AnimatedVisibility(
                            visible = showCreateForm,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                OutlinedTextField(
                                    value = newSnapshotLabel,
                                    onValueChange = { newSnapshotLabel = it },
                                    label = { Text("Snapshot Label") },
                                    placeholder = { Text("e.g. Clean Baseline, Before CUDA install") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = DsTextFieldDefaults.colors(),
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isCreating
                                )

                                OutlinedTextField(
                                    value = newSnapshotDesc,
                                    onValueChange = { newSnapshotDesc = it },
                                    label = { Text("Description (Optional)") },
                                    placeholder = { Text("Notes about packages or installed state...") },
                                    maxLines = 3,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = DsTextFieldDefaults.colors(),
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isCreating
                                )

                                // Compression Option Box
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "GZIP Compression (.img.gz / .tar.gz)",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = if (enableCompression) "Level ${compressionLevel.toInt()}: Higher level saves more storage space" else "Uncompressed: Faster snapshot capture & restore",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = enableCompression,
                                                onCheckedChange = { enableCompression = it },
                                                enabled = !isCreating
                                            )
                                        }

                                        if (enableCompression) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                            ) {
                                                Text(
                                                    "1 (Fast)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Slider(
                                                    value = compressionLevel,
                                                    onValueChange = { compressionLevel = it },
                                                    valueRange = 1f..9f,
                                                    steps = 7,
                                                    modifier = Modifier.weight(1f),
                                                    enabled = !isCreating
                                                )
                                                Text(
                                                    "9 (Max)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        val cur = container ?: return@Button
                                        val label = newSnapshotLabel.trim().ifBlank { "Snapshot" }
                                        isCreating = true
                                        scope.launch {
                                            opsViewModel.executeCreateSnapshot(
                                                container = cur,
                                                label = label,
                                                description = newSnapshotDesc.trim(),
                                                enableCompression = enableCompression,
                                                compressionLevel = compressionLevel.toInt(),
                                                onRefresh = {
                                                    newSnapshotLabel = ""
                                                    newSnapshotDesc = ""
                                                    showCreateForm = false
                                                    scope.launch { reloadData() }
                                                },
                                                onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                                onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                            )
                                            isCreating = false
                                        }
                                    },
                                    enabled = !isCreating,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    if (isCreating) {
                                        LoadingIndicator(size = LoadingSize.Small, color = MaterialTheme.colorScheme.onPrimary)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Creating Snapshot & Checksum...")
                                    } else {
                                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Create Snapshot", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Snapshots List Heading
            item {
                Text(
                    text = "Saved Snapshots (${snapshots.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 4. Snapshots Items
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(size = LoadingSize.Medium)
                    }
                }
            } else if (snapshots.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.HistoryToggleOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "No snapshots recorded yet",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Create a snapshot above to backup the current container state.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                items(snapshots, key = { it.id }) { snap ->
                    val isVerifying = verifyingId == snap.id
                    val verifyResult = verifiedStatusMap[snap.id]

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(18.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.CameraAlt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = snap.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (snap.isCompressed) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Text(
                                                    text = "GZIP L${snap.compressionLevel}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${snap.formattedDate} • ${snap.formattedSize}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (snap.description.isNotBlank()) {
                                Text(
                                    text = snap.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                                )
                            }

                            if (snap.sha256Hash.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "SHA-256: ${snap.sha256Hash.take(12)}...${snap.sha256Hash.takeLast(6)}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (verifyResult != null) {
                                            Text(
                                                text = if (verifyResult.first) "Valid ✓" else "Mismatch!",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (verifyResult.first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }

                            // Action buttons row (Rollback, Verify Checksum, Delete)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Rollback Button
                                Button(
                                    onClick = { confirmRollbackSnapshot = snap },
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.weight(1.2f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Rollback", fontWeight = FontWeight.SemiBold)
                                }

                                // Verify Checksum Button
                                OutlinedButton(
                                    onClick = {
                                        verifyingId = snap.id
                                        scope.launch {
                                            val (ok, err) = withContext(Dispatchers.IO) {
                                                ContainerSnapshotManager.verifyChecksum(snap)
                                            }
                                            verifyingId = null
                                            verifiedStatusMap = verifiedStatusMap + (snap.id to (ok to (err ?: "Checksum verified successfully ✓")))
                                            snackbarHostState.showSnackbar(if (ok) "SHA-256 Checksum Verified ✓" else "Integrity verification failed: $err")
                                        }
                                    },
                                    enabled = !isVerifying,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.weight(1.1f)
                                ) {
                                    if (isVerifying) {
                                        LoadingIndicator(size = LoadingSize.Small)
                                    } else {
                                        Icon(Icons.Default.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Verify", fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                // Delete Button
                                OutlinedButton(
                                    onClick = { confirmDeleteSnapshot = snap },
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.weight(0.9f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Rollback Confirmation Dialog
        confirmRollbackSnapshot?.let { snap ->
            val cur = container
            AlertDialog(
                onDismissRequest = { confirmRollbackSnapshot = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                icon = {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = {
                    Text(
                        "Rollback Container?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Are you sure you want to rollback '$containerName' to snapshot '${snap.label}'?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Date: ${snap.formattedDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                Text("Size: ${snap.formattedSize}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                if (snap.sha256Hash.isNotBlank()) {
                                    Text(
                                        "SHA-256: ${snap.sha256Hash.take(16)}...",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Text(
                            "Note: All container filesystem modifications made after this snapshot was created will be reverted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val toRestore = confirmRollbackSnapshot
                            confirmRollbackSnapshot = null
                            if (toRestore != null && cur != null) {
                                scope.launch {
                                    opsViewModel.executeRestoreSnapshot(
                                        container = cur,
                                        snapshot = toRestore,
                                        onRefresh = { scope.launch { reloadData() } },
                                        onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                        onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Confirm Rollback", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmRollbackSnapshot = null },
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete Confirmation Dialog
        confirmDeleteSnapshot?.let { snap ->
            val cur = container
            AlertDialog(
                onDismissRequest = { confirmDeleteSnapshot = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                icon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = {
                    Text(
                        "Delete Snapshot?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Text(
                        "Permanently delete snapshot '${snap.label}' (${snap.formattedDate})? This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val toDelete = confirmDeleteSnapshot
                            confirmDeleteSnapshot = null
                            if (toDelete != null && cur != null) {
                                scope.launch {
                                    opsViewModel.executeDeleteSnapshot(
                                        container = cur,
                                        snapshotId = toDelete.id,
                                        onRefresh = { scope.launch { reloadData() } },
                                        onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                        onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Delete", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmDeleteSnapshot = null },
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Live terminal log viewer for snapshot operations
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
                        scope.launch { reloadData() }
                    },
                    onClear = { opsViewModel.clearLogsBuffer(targetContainer) },
                    isBlocking = isBlocking
                )
            }
        }
    }
}
