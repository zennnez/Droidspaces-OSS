package com.droidspaces.app.ui.screen

import com.droidspaces.app.ui.component.DsDialog
import com.droidspaces.app.ui.component.DsTextFieldDefaults

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droidspaces.app.ui.component.DsSnackbarHost
import com.droidspaces.app.ui.util.ProgressDialog
import com.droidspaces.app.ui.util.ErrorLogsDialog
import com.droidspaces.app.ui.util.LoadingIndicator
import com.droidspaces.app.ui.util.LoadingSize
import com.droidspaces.app.ui.util.AnimatedListEntry
import com.droidspaces.app.ui.util.rememberAnimatedEntries
import com.droidspaces.app.ui.util.showError
import com.droidspaces.app.ui.util.showSuccess
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.ui.viewmodel.SystemStatsViewModel
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.PreferencesManager
import com.droidspaces.app.util.FilePickerUtils
import com.droidspaces.app.ui.component.ContainerCard
import com.droidspaces.app.ui.component.ContainerCardActions
import com.droidspaces.app.ui.component.DialogFooterRow
import com.droidspaces.app.ui.component.TerminalDialog
import com.droidspaces.app.ui.component.ExportContainerDialog
import com.droidspaces.app.util.ValidationUtils
import com.droidspaces.app.ui.component.EmptyState
import com.droidspaces.app.ui.component.ErrorState
import com.droidspaces.app.ui.component.KernelUnsupportedState
import com.droidspaces.app.ui.component.RootUnavailableState
import com.droidspaces.app.ui.component.RootfsRepoSheet
import com.droidspaces.app.ui.component.ContainerSnapshotsDialog
import com.droidspaces.app.ui.component.StorageDestinationPicker
import com.droidspaces.app.ui.component.DsDropdown
import com.droidspaces.app.util.StorageChecker
import com.droidspaces.app.util.FilesystemCapabilityReport
import com.droidspaces.app.util.MoveVerificationMethod
import com.droidspaces.app.util.ContainerDiskUsageManager
import com.droidspaces.app.util.MoveStorageOperations
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import com.droidspaces.app.ui.viewmodel.ContainerViewModel
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.ui.viewmodel.UninstallState
import com.droidspaces.app.ui.viewmodel.SparseOperation
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.droidspaces.app.R
import com.droidspaces.app.ui.theme.DsIcons
import androidx.compose.ui.window.Dialog

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContainersScreen(
    isBackendAvailable: Boolean,
    isRootAvailable: Boolean = true,
    isKernelSupported: Boolean = true,
    onNavigateToInstallation: (Uri) -> Unit = {},
    onNavigateToEditContainer: (String) -> Unit = {},
    onNavigateToContainerDetails: (String) -> Unit = {},
    onNavigateToSnapshots: (String) -> Unit = {},
    containerViewModel: ContainerViewModel,
    expandedContainerName: String?,
    onExpandedContainerNameChange: (String?) -> Unit,
    emptyStateBottomInset: Dp = 0.dp
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val systemStatsViewModel: SystemStatsViewModel = viewModel()
    val prefsManager = PreferencesManager.getInstance(context)

    val snackbarHostState = remember { SnackbarHostState() }

    // Container lifecycle/maintenance operations + their state live in the ViewModel.
    val opsViewModel: ContainerOperationsViewModel = viewModel()

    // UI-only state (dialog triggers / pending pickers).
    var showUninstallConfirmation by remember { mutableStateOf<ContainerInfo?>(null) }
    var pendingSparseOperation by remember { mutableStateOf<SparseOperation?>(null) }
    var pendingRepairContainer by remember { mutableStateOf<ContainerInfo?>(null) }
    var pendingRepairDiskUsage by remember { mutableStateOf<com.droidspaces.app.util.ContainerDiskUsageManager.DiskUsage?>(null) }
    var pendingMoveStorageContainer by remember { mutableStateOf<ContainerInfo?>(null) }
    var pendingSnapshotsContainer by remember { mutableStateOf<ContainerInfo?>(null) }
    var pendingExportContainer by remember { mutableStateOf<ContainerInfo?>(null) }
    var pendingExportEnv by remember { mutableStateOf(false) }
    var askExportEnv by remember { mutableStateOf<ContainerInfo?>(null) }
    var showRepoSheet by remember { mutableStateOf(false) }
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }

    // File picker launcher - CreateDocument for saving the export archive
    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri: Uri? ->
        val container = pendingExportContainer
        val includeEnv = pendingExportEnv
        pendingExportContainer = null
        if (uri != null && container != null) {
            scope.launch {
                opsViewModel.executeExport(container, uri, includeEnv, onError = { msg -> scope.showError(snackbarHostState, msg) })
            }
        }
    }

    // File picker launcher - accept all files, validate internally
    // We don't filter in the picker (MIME types are unreliable for tar.xz/tar.gz)
    // FilePickerUtils handles proper filename extraction from any URI type (including recent files)
    // and validates that the file is a .tar.xz or .tar.gz file
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val (isValid, fileName) = FilePickerUtils.isValidTarball(context, uri)
                if (isValid && fileName != null) {
                    onNavigateToInstallation(uri)
                } else {
                    val errorMessage = if (fileName != null) {
                        context.getString(R.string.file_picker_error, fileName)
                    } else {
                        context.getString(R.string.file_picker_error_unknown)
                    }
                    scope.showError(snackbarHostState, errorMessage)
                }
            }
        }
    }

    // Get containers from ViewModel - single source of truth (KernelSU pattern)
    val containers = containerViewModel.containerList
    // Removed cards stay here until they finish leaving, so the empty state waits for them.
    val entries = rememberAnimatedEntries(containers) { it.name }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Show root unavailable state first, then backend unavailable, then content
        when {
            !isRootAvailable -> {
                RootUnavailableState(modifier = Modifier.padding(bottom = emptyStateBottomInset))
            }
            !isBackendAvailable -> {
                ErrorState(modifier = Modifier.padding(bottom = emptyStateBottomInset))
            }
            !isKernelSupported -> {
                KernelUnsupportedState(modifier = Modifier.padding(bottom = emptyStateBottomInset))
            }
            entries.isEmpty() -> {
                if (containerViewModel.isRefreshing) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(bottom = emptyStateBottomInset),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(size = LoadingSize.Large)
                    }
                } else {
                    EmptyState(
                        icon = DsIcons.Logo,
                        title = context.getString(R.string.no_containers_installed),
                        description = context.getString(R.string.install_container_description),
                        // Reserve the floating tab bar's space so the centered
                        // content sits in the visible region, not behind the bar.
                        modifier = Modifier.padding(bottom = emptyStateBottomInset)
                    )
                }
            }
            else -> {
                // Show container cards
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                onExpandedContainerNameChange(null)
                                fabMenuExpanded = false
                            }
                        )
                        .padding(horizontal = 16.dp),
                    // Each card carries its own 16dp gap (AnimatedListEntry), hence 104 + 16.
                    contentPadding = PaddingValues(top = 8.dp, bottom = 104.dp), // Clear floating tab bar
                ) {
                    items(entries, key = { it.key }) { entry ->
                        val container = entry.item
                        // Console button is always visible - logs persist for each container
                        val isRunning = opsViewModel.runningOperationContainer == container.name

                        AnimatedListEntry(entry) {
                            ContainerCard(
                                container = container,
                                isOperationRunning = isRunning,
                                isExpanded = expandedContainerName == container.name,
                                actions = ContainerCardActions(
                                onToggleExpand = {
                                    onExpandedContainerNameChange(if (expandedContainerName == container.name) null else container.name)
                                },
                                 onShowLogs = {
                                    opsViewModel.showLogViewerFor = container.name
                                },
                                onStart = {
                                    scope.launch {
                                        opsViewModel.executeOperation(
                                            container, "start",
                                            onRefresh = { containerViewModel.refresh() },
                                            onClearUsage = { systemStatsViewModel.clearContainerUsage(it) },
                                            onFailureSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long) } }
                                        )
                                    }
                                },
                                onStop = {
                                    scope.launch {
                                        opsViewModel.executeOperation(
                                            container, "stop",
                                            onRefresh = { containerViewModel.refresh() },
                                            onClearUsage = { systemStatsViewModel.clearContainerUsage(it) },
                                            onFailureSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long) } }
                                        )
                                    }
                                },
                                onRestart = {
                                    scope.launch {
                                        opsViewModel.executeOperation(
                                            container, "restart",
                                            onRefresh = { containerViewModel.refresh() },
                                            onClearUsage = { systemStatsViewModel.clearContainerUsage(it) },
                                            onFailureSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long) } }
                                        )
                                    }
                                },
                                onEdit = {
                                    onExpandedContainerNameChange(null)
                                    onNavigateToEditContainer(container.name)
                                },
                                onEnter = {
                                    onNavigateToContainerDetails(container.name)
                                },
                                onUninstall = {
                                    onExpandedContainerNameChange(null)
                                    showUninstallConfirmation = container
                                },
                                onMigrate = {
                                    onExpandedContainerNameChange(null)
                                    pendingSparseOperation = SparseOperation.Migrate(container)
                                },
                                onResize = {
                                    onExpandedContainerNameChange(null)
                                    pendingSparseOperation = SparseOperation.Resize(container)
                                },
                                onRepairAndCompact = {
                                    onExpandedContainerNameChange(null)
                                    pendingRepairContainer = container
                                    scope.launch {
                                        pendingRepairDiskUsage = com.droidspaces.app.util.ContainerDiskUsageManager.getCached(container.rootfsPath)
                                            ?: com.droidspaces.app.util.ContainerDiskUsageManager.getUsage(container.rootfsPath)
                                    }
                                },
                                onConvertToDir = {
                                    onExpandedContainerNameChange(null)
                                    pendingSparseOperation = SparseOperation.ConvertToDir(container)
                                },
                                onMoveStorage = {
                                    onExpandedContainerNameChange(null)
                                    pendingMoveStorageContainer = container
                                },
                                onSnapshots = {
                                    onExpandedContainerNameChange(null)
                                    pendingSnapshotsContainer = container
                                },
                                onExport = {
                                    onExpandedContainerNameChange(null)
                                    // Only a container with env vars has something to decide.
                                    if (ValidationUtils.countEnvVars(container.envFileContent) > 0) {
                                        askExportEnv = container
                                    } else {
                                        pendingExportEnv = false
                                        pendingExportContainer = container
                                        exportFileLauncher.launch(exportFileName(container))
                                    }
                                }
                                )
                            )
                        }
                    }
                }
            }
        }

        // One FAB, a Material 3 Expressive menu: the plus morphs into a cross and the two
        // install routes rise above it. Replaces the small FAB stacked on an extended one.
        if (isBackendAvailable && isRootAvailable && isKernelSupported) {
            BackHandler(fabMenuExpanded) { fabMenuExpanded = false }

            FloatingActionButtonMenu(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 24.dp, bottom = 88.dp), // Clear floating tab bar
                expanded = fabMenuExpanded,
                button = {
                    ToggleFloatingActionButton(
                        modifier = Modifier.semantics {
                            traversalIndex = -1f
                            contentDescription = context.getString(R.string.add_container)
                            stateDescription = context.getString(
                                if (fabMenuExpanded) R.string.fab_menu_expanded else R.string.fab_menu_collapsed
                            )
                        },
                        checked = fabMenuExpanded,
                        onCheckedChange = { fabMenuExpanded = !fabMenuExpanded }
                    ) {
                        val imageVector by remember {
                            derivedStateOf { if (checkedProgress > 0.5f) Icons.Default.Close else Icons.Default.Add }
                        }
                        Icon(
                            painter = rememberVectorPainter(imageVector),
                            contentDescription = null,
                            modifier = Modifier.animateIcon({ checkedProgress })
                        )
                    }
                }
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        fabMenuExpanded = false
                        showRepoSheet = true
                    },
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                    text = { Text(context.getString(R.string.repo_fab_label)) }
                )
                // Last item, nearest the thumb. TalkBack reaches the close button before the
                // first item, so the last one offers a close action of its own.
                FloatingActionButtonMenuItem(
                    modifier = Modifier.semantics {
                        isTraversalGroup = true
                        customActions = listOf(
                            CustomAccessibilityAction(context.getString(R.string.close_menu)) {
                                fabMenuExpanded = false
                                true
                            }
                        )
                    },
                    onClick = {
                        fabMenuExpanded = false
                        filePickerLauncher.launch("*/*")
                    },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    text = { Text(context.getString(R.string.install_from_file)) }
                )
            }
        }

        // Repo bottom sheet
        if (showRepoSheet) {
            val orientation = LocalConfiguration.current.orientation
            key(orientation) {
                RootfsRepoSheet(
                    onDismiss = { showRepoSheet = false },
                    onInstall = { uri -> onNavigateToInstallation(uri) }
                )
            }
        }

        // SNACKBAR LAYER (Highest Z-index in the root Box)
        DsSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
        )

        // Log viewer dialog - console stays open, user must close manually
        opsViewModel.showLogViewerFor?.let { containerName ->
            // Load logs from memory first, fallback to cache if empty
            val memoryLogs = opsViewModel.containerLogs[containerName]?.toList() ?: emptyList()
            val cachedLogs = if (memoryLogs.isEmpty()) {
                prefsManager.loadContainerLogs(containerName)
            } else {
                emptyList()
            }
            val logs = memoryLogs.ifEmpty { cachedLogs }
            val isBlocking = opsViewModel.runningOperationContainer == containerName // Blocking when operation is running
            TerminalDialog(
                title = context.getString(R.string.logs_title, containerName),
                logs = logs,
                onDismiss = {
                    opsViewModel.showLogViewerFor = null
                    // Refresh container status when console is closed (KernelSU pattern)
                    containerViewModel.refresh()
                },
                onClear = {
                    opsViewModel.clearLogsBuffer(containerName)
                },
                isBlocking = isBlocking // Block dismissal when operation is running
            )
        }

        askExportEnv?.let { container ->
            ExportContainerDialog(
                containerName = container.name,
                envCount = ValidationUtils.countEnvVars(container.envFileContent),
                onConfirm = { includeEnv ->
                    askExportEnv = null
                    pendingExportEnv = includeEnv
                    pendingExportContainer = container
                    exportFileLauncher.launch(exportFileName(container))
                },
                onDismiss = { askExportEnv = null }
            )
        }

        // Uninstall confirmation dialog
        showUninstallConfirmation?.let { container ->
            UninstallConfirmationDialog(
                containerName = container.name,
                onConfirm = {
                    showUninstallConfirmation = null
                    scope.launch {
                        opsViewModel.executeUninstall(
                            container,
                            onError = { msg -> scope.showError(snackbarHostState, msg) },
                            onSuccess = { msg -> scope.showSuccess(snackbarHostState, msg) },
                            onRefresh = { containerViewModel.refresh() }
                        )
                    }
                },
                onDismiss = {
                    showUninstallConfirmation = null
                }
            )
        }

        // Uninstall progress dialog
        (opsViewModel.uninstallState as? UninstallState.InProgress)?.let { state ->
            ProgressDialog(
                message = state.message
            )
        }

        // Uninstall logs dialog (only on failure)
        opsViewModel.uninstallLogsDialog?.let { logs ->
            ErrorLogsDialog(
                logs = logs,
                onDismiss = { opsViewModel.dismissUninstallLogs() }
            )
        }

        // Sparse operation dialog (Migrate, Resize, ConvertToDir)
        pendingSparseOperation?.let { op ->
            SparseOperationDialog(
                operation = op,
                onConfirm = { size, targetPath ->
                    val opToRun = when (op) {
                        is SparseOperation.Migrate -> op.copy(targetImagePath = targetPath)
                        is SparseOperation.Resize -> op
                        is SparseOperation.ConvertToDir -> op.copy(targetDirPath = targetPath)
                    }
                    pendingSparseOperation = null
                    scope.launch {
                        opsViewModel.executeSparseOperation(opToRun, size, onRefresh = { containerViewModel.refresh() })
                    }
                },
                onDismiss = { pendingSparseOperation = null }
            )
        }

        // Sparse repair & compact confirmation dialog
        pendingRepairContainer?.let { container ->
            RepairAndCompactDialog(
                container = container,
                diskUsage = pendingRepairDiskUsage,
                onConfirm = { runFstrim, compactToUtilized ->
                    pendingRepairContainer = null
                    scope.launch {
                        opsViewModel.executeRepairAndCompact(
                            container = container,
                            runFstrim = runFstrim,
                            compactToUtilized = compactToUtilized,
                            onRefresh = { containerViewModel.refresh() },
                            onError = { msg -> scope.showError(snackbarHostState, msg) },
                            onSuccess = { msg -> scope.showSuccess(snackbarHostState, msg) }
                        )
                    }
                },
                onDismiss = { pendingRepairContainer = null }
            )
        }

        // Snapshots & Rollback dialog
        pendingSnapshotsContainer?.let { container ->
            ContainerSnapshotsDialog(
                container = container,
                opsViewModel = opsViewModel,
                onDismiss = { pendingSnapshotsContainer = null },
                onRefresh = { containerViewModel.refresh() }
            )
        }

        // Move-storage location picker dialog
        pendingMoveStorageContainer?.let { container ->
            MoveStorageDialog(
                container = container,
                onConfirm = { newPath, verificationMethod, skipFreeSpaceCheck ->
                    pendingMoveStorageContainer = null
                    scope.launch {
                        opsViewModel.executeMoveStorage(
                            container,
                            newPath,
                            verificationMethod,
                            skipFreeSpaceCheck,
                            onError = { msg -> scope.showError(snackbarHostState, msg) },
                            onSuccess = { msg -> scope.showSuccess(snackbarHostState, msg) },
                            onRefresh = { containerViewModel.refresh() }
                        )
                    }
                },
                onDismiss = { pendingMoveStorageContainer = null }
            )
        }
    }
}

/** <name>_yyyyMMdd_HHmmss.tar.gz, the name the save dialog suggests. */
private fun exportFileName(container: ContainerInfo): String {
    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
    return "${container.name}_${timestamp}.tar.gz"
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SparseOperationDialog(
    operation: SparseOperation,
    onConfirm: (sizeGb: Int, customPath: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val container = when (operation) {
        is SparseOperation.Migrate -> operation.container
        is SparseOperation.Resize -> operation.container
        is SparseOperation.ConvertToDir -> operation.container
    }

    val defaultPath = remember(operation) {
        when (operation) {
            is SparseOperation.Migrate -> {
                if (container.rootfsPath.endsWith(".img")) container.rootfsPath
                else "${container.rootfsPath.removeSuffix("/")}.img"
            }
            is SparseOperation.ConvertToDir -> {
                if (container.rootfsPath.endsWith(".img")) container.rootfsPath.removeSuffix(".img")
                else "${container.rootfsPath}_dir"
            }
            is SparseOperation.Resize -> container.rootfsPath
        }
    }

    var customPath by remember { mutableStateOf(defaultPath) }
    var detectedVolumes by remember { mutableStateOf<List<String>>(emptyList()) }
    var sharedStoragePath by remember { mutableStateOf<String?>(null) }

    var sizeText by remember { mutableStateOf((container.sparseImageSizeGB ?: 8).toString()) }
    val size = sizeText.toIntOrNull()
    val isSizeValid = size != null && size in 4..512

    var freeSpaceGB by remember { mutableStateOf<Int?>(null) }
    var fsReport by remember { mutableStateOf<FilesystemCapabilityReport?>(null) }

    LaunchedEffect(Unit) {
        detectedVolumes = StorageChecker.listStorageVolumes()
        sharedStoragePath = StorageChecker.getSharedStoragePath()
    }

    LaunchedEffect(customPath, operation) {
        if (customPath.isNotBlank()) {
            val parentPath = if (customPath.endsWith(".img") || !customPath.endsWith("/")) {
                java.io.File(customPath).parent ?: "/"
            } else {
                customPath
            }
            freeSpaceGB = StorageChecker.getFreeSpaceGB(parentPath)
            if (operation is SparseOperation.ConvertToDir) {
                fsReport = StorageChecker.inspectFilesystemCapabilities(parentPath, context)
            }
        }
    }

    val isPathValid = customPath.isNotBlank() && customPath.startsWith("/")
    val canConfirm = when (operation) {
        is SparseOperation.Migrate -> isSizeValid && isPathValid
        is SparseOperation.Resize -> isSizeValid
        is SparseOperation.ConvertToDir -> isPathValid
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .imePadding(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        tonalElevation = 0.dp
                    ) {
                        Box(modifier = Modifier.padding(10.dp)) {
                            Icon(
                                imageVector = when (operation) {
                                    is SparseOperation.Migrate -> Icons.Default.Save
                                    is SparseOperation.ConvertToDir -> Icons.Default.FolderOpen
                                    is SparseOperation.Resize -> Icons.Default.AspectRatio
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = when (operation) {
                                is SparseOperation.Migrate -> "Convert to Sparse Image"
                                is SparseOperation.ConvertToDir -> "Convert to Directory"
                                is SparseOperation.Resize -> "Resize Sparse Image"
                            },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Container: ${container.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = when (operation) {
                        is SparseOperation.Migrate -> "Pack directory rootfs into a standalone sparse ext4 image file."
                        is SparseOperation.ConvertToDir -> "Unpack sparse ext4 image rootfs into a directory for native POSIX execution."
                        is SparseOperation.Resize -> "Change the maximum virtual disk allocation for this sparse ext4 image."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (operation is SparseOperation.Migrate || operation is SparseOperation.Resize) {
                    OutlinedTextField(
                        value = sizeText,
                        onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) sizeText = it },
                        label = { Text("Image Size (GB)") },
                        placeholder = { Text(context.getString(R.string.size_range_4_512_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = DsTextFieldDefaults.colors(),
                        isError = !isSizeValid && sizeText.isNotEmpty(),
                        supportingText = {
                            if (!isSizeValid && sizeText.isNotEmpty()) {
                                Text(context.getString(R.string.enter_size_between_4_512_gb))
                            } else if (freeSpaceGB != null) {
                                Text("Available storage: ~$freeSpaceGB GB free")
                            }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )
                }

                if (operation is SparseOperation.Migrate || operation is SparseOperation.ConvertToDir) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (operation is SparseOperation.Migrate) "Destination Image Location" else "Destination Directory Location",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        StorageDestinationPicker(
                            path = customPath,
                            onPathChange = { customPath = it },
                            label = if (operation is SparseOperation.Migrate) "Image file path" else "Target directory path",
                            placeholder = if (operation is SparseOperation.Migrate) "/data/local/ubuntu/rootfs.img" else "/data/local/ubuntu/rootfs",
                            colors = DsTextFieldDefaults.colors(),
                            dialogTitle = if (operation is SparseOperation.Migrate) "Select Image Location" else "Select Target Location"
                        )

                        if (detectedVolumes.isNotEmpty() || sharedStoragePath != null) {
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                sharedStoragePath?.let { shared ->
                                    AssistChip(
                                        onClick = {
                                            customPath = if (operation is SparseOperation.Migrate) "$shared/Droidspaces/${container.name}/rootfs.img"
                                            else "$shared/Droidspaces/${container.name}/rootfs"
                                        },
                                        label = { Text("This device's storage") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    )
                                }
                                detectedVolumes.forEach { volume ->
                                    AssistChip(
                                        onClick = {
                                            customPath = if (operation is SparseOperation.Migrate) "$volume/Droidspaces/${container.name}/rootfs.img"
                                            else "$volume/Droidspaces/${container.name}/rootfs"
                                        },
                                        label = { Text(volume.substringAfterLast("/")) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (operation is SparseOperation.ConvertToDir) {
                    val report = fsReport
                    if (report != null && !report.isFullyCompatibleWithDirectoryMode) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Filesystem Warning: ${report.fsType.uppercase()}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = "Destination filesystem may not support Linux POSIX permissions, symlinks, or special devices. Directory mode works best on internal storage or ext4/f2fs drives.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    } else if (report != null && report.isFullyCompatibleWithDirectoryMode) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF2E7D32).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Destination filesystem (${report.fsType}) supports directory mode ✓",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(context.getString(R.string.cancel))
                    }

                    Button(
                        onClick = {
                            val finalSize = size ?: 8
                            val finalPath = customPath.trim().takeIf { it.isNotBlank() }
                            onConfirm(finalSize, finalPath)
                        },
                        enabled = canConfirm,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when (operation) {
                                is SparseOperation.Migrate -> "Convert"
                                is SparseOperation.ConvertToDir -> "Convert"
                                is SparseOperation.Resize -> "Resize"
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Local validation state for [MoveStorageDialog]'s custom path field. */
private sealed class MoveStoragePathCheck {
    data object Idle : MoveStoragePathCheck()
    data object Checking : MoveStoragePathCheck()
    data object Valid : MoveStoragePathCheck()
    data class Invalid(val reason: String) : MoveStoragePathCheck()
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MoveStorageDialog(
    container: com.droidspaces.app.util.ContainerInfo,
    onConfirm: (newPath: String?, verificationMethod: com.droidspaces.app.util.MoveVerificationMethod, skipFreeSpaceCheck: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val dialogShape = RoundedCornerShape(24.dp)
    val currentDir = container.rootfsPath.substringBeforeLast("/", "")
    var useCustomLocation by remember { mutableStateOf(false) }
    var verificationMethod by remember { mutableStateOf(com.droidspaces.app.util.MoveVerificationMethod.SHA256) }
    var skipFreeSpaceCheck by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var customPath by remember { mutableStateOf("") }
    var detectedVolumes by remember { mutableStateOf<List<String>>(emptyList()) }
    var sharedStoragePath by remember { mutableStateOf<String?>(null) }
    var pathCheck by remember { mutableStateOf<MoveStoragePathCheck>(MoveStoragePathCheck.Idle) }

    LaunchedEffect(Unit) {
        detectedVolumes = com.droidspaces.app.util.StorageChecker.listStorageVolumes()
        sharedStoragePath = com.droidspaces.app.util.StorageChecker.getSharedStoragePath()
    }

    LaunchedEffect(customPath, useCustomLocation) {
        if (!useCustomLocation || customPath.trim().isEmpty()) {
            pathCheck = MoveStoragePathCheck.Idle
            return@LaunchedEffect
        }
        pathCheck = MoveStoragePathCheck.Checking
        kotlinx.coroutines.delay(500)
        val result = com.droidspaces.app.util.StorageChecker.validateWritablePath(customPath.trim())
        pathCheck = if (result.isSuccess) {
            if (!container.useSparseImage) {
                val report = com.droidspaces.app.util.StorageChecker.inspectFilesystemCapabilities(customPath.trim(), context)
                if (!report.isFullyCompatibleWithDirectoryMode) {
                    MoveStoragePathCheck.Invalid("Incompatible '${report.fsType}' filesystem. Directory mode requires POSIX ext4/f2fs. Migrate container to sparse image first.")
                } else {
                    MoveStoragePathCheck.Valid
                }
            } else {
                MoveStoragePathCheck.Valid
            }
        } else {
            MoveStoragePathCheck.Invalid(result.exceptionOrNull()?.message ?: "Can't write to this path")
        }
    }

    val isValid = !useCustomLocation ||
        (customPath.trim().isNotEmpty() && pathCheck !is MoveStoragePathCheck.Invalid)

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .imePadding(),
            shape = dialogShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Move Storage", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Move \"${container.name}\"'s data to a different location. The container will be stopped first if it's running.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Current location: $currentDir",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                if (!container.useSparseImage) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    ) {
                        Text(
                            "This container uses directory-mode rootfs. Moving it to a FAT32/exFAT SD card or USB drive will break it -- only move it to another ext4/f2fs-formatted location.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !useCustomLocation, onClick = { useCustomLocation = false })
                    Text("Internal storage (default)", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = useCustomLocation, onClick = { useCustomLocation = true })
                    Text("External / custom location", style = MaterialTheme.typography.bodyMedium)
                }

                if (useCustomLocation) {
                    StorageDestinationPicker(
                        path = customPath,
                        onPathChange = { customPath = it },
                        placeholder = "/storage/1234-5678/Droidspaces",
                        isError = pathCheck is MoveStoragePathCheck.Invalid,
                        trailingStatusIcon = {
                            when (pathCheck) {
                                is MoveStoragePathCheck.Checking -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                is MoveStoragePathCheck.Valid -> Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                is MoveStoragePathCheck.Invalid -> Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                else -> {}
                            }
                        },
                        colors = DsTextFieldDefaults.colors(),
                        dialogTitle = "Select Storage Location"
                    )

                    if (detectedVolumes.isNotEmpty() || sharedStoragePath != null) {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sharedStoragePath?.let { shared ->
                                AssistChip(
                                    onClick = { customPath = "$shared/Droidspaces" },
                                    label = { Text("This device's storage") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                            }
                            detectedVolumes.forEach { volume ->
                                AssistChip(
                                    onClick = { customPath = "$volume/Droidspaces" },
                                    label = { Text(volume.substringAfterLast("/")) }
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Verify copy before deleting original",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when (verificationMethod) {
                            MoveVerificationMethod.SIZE ->
                                "Fastest -- compares sizes only. Won't catch a corrupted file that's the same size."
                            MoveVerificationMethod.SHA256 ->
                                "Most thorough -- hashes every line/file individually and can point to exactly which one differs."
                            MoveVerificationMethod.TAR ->
                                "Balanced -- hashes all file contents in one pass. Much faster than per-file hashing, but can't name which file differs."
                            MoveVerificationMethod.NONE ->
                                "Skipped -- only trusts the copy command's own exit code. Not recommended."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    DsDropdown(
                        label = "Verification Method",
                        selected = verificationMethod,
                        options = MoveVerificationMethod.entries,
                        displayName = { method ->
                            when (method) {
                                MoveVerificationMethod.SIZE -> "Size only"
                                MoveVerificationMethod.SHA256 -> "Checksum (per line)"
                                MoveVerificationMethod.TAR -> "Checksum (combined)"
                                MoveVerificationMethod.NONE -> "None"
                            }
                        },
                        onSelect = { verificationMethod = it }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { skipFreeSpaceCheck = !skipFreeSpaceCheck }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Checkbox(
                        checked = skipFreeSpaceCheck,
                        onCheckedChange = { skipFreeSpaceCheck = it }
                    )
                    Column {
                        Text("Skip Free-Space Check", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Bypass capacity precheck if destination reports inaccurate free space.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onDismiss),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        tonalElevation = 0.dp
                    ) {
                        Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                            Text("Cancel", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(
                            enabled = isValid,
                            onClick = { onConfirm(if (useCustomLocation) customPath.trim() else null, verificationMethod, skipFreeSpaceCheck) }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        tonalElevation = 0.dp
                    ) {
                        Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Move",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isValid) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepairAndCompactDialog(
    container: ContainerInfo,
    diskUsage: ContainerDiskUsageManager.DiskUsage?,
    onConfirm: (runFstrim: Boolean, compactToUtilized: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val dialogShape = RoundedCornerShape(24.dp)
    var runFstrim by remember { mutableStateOf(true) }
    var compactToUtilized by remember { mutableStateOf(false) }

    val utilizedText = diskUsage?.let {
        MoveStorageOperations.formatBytes(it.usedBytes)
    } ?: "utilized size"

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .imePadding(),
            shape = dialogShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "Repair & Compact Image",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                Text(
                    "Check filesystem integrity with e2fsck, zero-out unused blocks, and compact the sparse rootfs image for \"${container.name}\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (diskUsage != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Physical Disk Footprint",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    MoveStorageOperations.formatBytes(diskUsage.usedBytes),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "Apparent Capacity",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    MoveStorageOperations.formatBytes(diskUsage.totalBytes),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                if (container.isRunning) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "The container will be temporarily stopped to safely perform offline filesystem maintenance and restarted automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { runFstrim = !runFstrim }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Checkbox(
                        checked = runFstrim,
                        onCheckedChange = { runFstrim = it }
                    )
                    Column {
                        Text(
                            "Run fstrim",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Discard and zero-out freed filesystem blocks to maximize reclaimed space",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { compactToUtilized = !compactToUtilized }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Checkbox(
                        checked = compactToUtilized,
                        onCheckedChange = { compactToUtilized = it }
                    )
                    Column {
                        Text(
                            "Compact image capacity to utilized size ($utilizedText)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Shrink virtual image capacity down to match current actual content",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onDismiss),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        tonalElevation = 0.dp
                    ) {
                        Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Cancel",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = { onConfirm(runFstrim, compactToUtilized) }),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        tonalElevation = 0.dp
                    ) {
                        Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Repair & Compact",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun UninstallConfirmationDialog(
    containerName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var confirmText by remember { mutableStateOf("") }
    val isConfirmed = confirmText == containerName

    DsDialog(
        onDismiss = onDismiss,
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
        footer = {
            DialogFooterRow(
                dismissLabel = context.getString(R.string.cancel),
                confirmLabel = context.getString(R.string.uninstall),
                onDismiss = onDismiss,
                onConfirm = onConfirm,
                confirmEnabled = isConfirmed,
                destructive = true
            )
        }
    ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Warning, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp)
                )
                Text(
                    text = context.getString(R.string.uninstall_container_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = buildAnnotatedString {
                    val template = context.getString(R.string.uninstall_container_message)
                    val parts = template.split("%1\$s")
                    append(parts.getOrElse(0) { "" })
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(containerName) }
                    append(parts.getOrElse(1) { "" })
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = context.getString(R.string.type_container_name_to_confirm),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(containerName) },
                    singleLine = true,
                    isError = confirmText.isNotEmpty() && !isConfirmed,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        focusedBorderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    )
                )
            }
    }
    }

