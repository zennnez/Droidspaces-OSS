package com.droidspaces.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidspaces.app.R
import com.droidspaces.app.ui.component.DangerousWarningCard
import com.droidspaces.app.ui.component.DsTextFieldDefaults
import com.droidspaces.app.ui.component.EmptyState
import com.droidspaces.app.ui.util.LoadingIndicator
import com.droidspaces.app.ui.util.LoadingSize
import com.droidspaces.app.util.ContainerDiskUsageManager
import kotlinx.coroutines.launch

/**
 * Size-sorted, tree-browsable disk usage explorer for a single container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiskUsageScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentPath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<ContainerDiskUsageManager.DiskEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var pendingDelete by remember { mutableStateOf<ContainerDiskUsageManager.DiskEntry?>(null) }
    var isDeleting by remember { mutableStateOf(false) }

    fun goUp() {
        if (currentPath == "/") {
            onNavigateBack()
        } else {
            currentPath = currentPath.substringBeforeLast("/").ifEmpty { "/" }
        }
    }

    BackHandler { goUp() }

    LaunchedEffect(containerName, currentPath, refreshTrigger) {
        isLoading = true
        errorMessage = null
        val result = ContainerDiskUsageManager.listDirectory(containerName, currentPath)
        result.onSuccess {
            entries = it
            errorMessage = null
        }
        result.onFailure {
            errorMessage = it.message ?: context.getString(R.string.disk_usage_load_error)
        }
        isLoading = false
    }

    val pathSegments = remember(currentPath) {
        if (currentPath == "/") emptyList() else currentPath.trim('/').split("/")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = context.getString(R.string.disk_usage_explorer_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { goUp() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = context.getString(R.string.back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Breadcrumb - tap any segment to jump straight there.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                BreadcrumbSegment(
                    label = context.getString(R.string.disk_usage_root_label),
                    isCurrent = currentPath == "/",
                    onClick = { currentPath = "/" }
                )
                var accumulated = ""
                pathSegments.forEach { segment ->
                    accumulated = "$accumulated/$segment"
                    val target = accumulated
                    Icon(
                        Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BreadcrumbSegment(
                        label = segment,
                        isCurrent = target == currentPath,
                        onClick = { currentPath = target }
                    )
                }
            }

            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator(size = LoadingSize.Large)
                    }
                }
                errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = errorMessage ?: context.getString(R.string.disk_usage_load_error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = { refreshTrigger++ }) {
                                Text(context.getString(R.string.disk_usage_retry))
                            }
                        }
                    }
                }
                entries.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.Folder,
                        title = context.getString(R.string.disk_usage_empty_title),
                        description = context.getString(R.string.disk_usage_empty_description)
                    )
                }
                else -> {
                    val maxSize = entries.maxOf { it.sizeBytes }.coerceAtLeast(1L)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(entries, key = { it.path }) { entry ->
                            DiskEntryRow(
                                entry = entry,
                                fraction = (entry.sizeBytes.toFloat() / maxSize.toFloat()).coerceIn(0.02f, 1f),
                                onOpen = { if (entry.isDirectory) currentPath = entry.path },
                                onDelete = { pendingDelete = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        DeleteEntryDialog(
            entry = entry,
            isDeleting = isDeleting,
            onDismiss = { if (!isDeleting) pendingDelete = null },
            onConfirm = {
                isDeleting = true
                scope.launch {
                    val result = ContainerDiskUsageManager.deletePath(containerName, entry.path)
                    isDeleting = false
                    pendingDelete = null
                    if (result.isSuccess) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.disk_usage_delete_success, entry.name)
                        )
                        refreshTrigger++
                    } else {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.disk_usage_delete_failed, entry.name)
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun BreadcrumbSegment(label: String, isCurrent: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !isCurrent, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

/**
 * One row in the size-sorted list: icon, name (+ a thin relative-size bar), size,
 * and a delete action.
 */
@Composable
private fun DiskEntryRow(
    entry: ContainerDiskUsageManager.DiskEntry,
    fraction: Float,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = entry.isDirectory, onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatDiskBytes(entry.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (entry.isDirectory) {
                    Icon(
                        Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = LocalContext.current.getString(R.string.disk_usage_delete_confirm),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (entry.isDirectory) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary
                        )
                )
            }
        }
    }
}

@Composable
private fun DeleteEntryDialog(
    entry: ContainerDiskUsageManager.DiskEntry,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val isSensitive = remember(entry.path) { ContainerDiskUsageManager.isSensitivePath(entry.path) }
    var confirmText by remember { mutableStateOf("") }
    val confirmPhrase = context.getString(R.string.disk_usage_confirm_phrase)
    val canDelete = !isSensitive || confirmText == confirmPhrase

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = context.getString(R.string.disk_usage_delete_title, entry.name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = context.getString(R.string.disk_usage_delete_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isSensitive) {
                    DangerousWarningCard(
                        title = context.getString(R.string.disk_usage_delete_sensitive_title),
                        text = context.getString(R.string.disk_usage_delete_sensitive_message, entry.path)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = context.getString(R.string.disk_usage_confirm_instruction, confirmPhrase),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = confirmText,
                            onValueChange = { confirmText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(confirmPhrase) },
                            singleLine = true,
                            isError = confirmText.isNotEmpty() && confirmText != confirmPhrase,
                            shape = RoundedCornerShape(14.dp),
                            colors = DsTextFieldDefaults.surfaceColors()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isDeleting) {
                        Text(context.getString(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        enabled = canDelete && !isDeleting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        if (isDeleting) {
                            LoadingIndicator(
                                size = LoadingSize.Small,
                                color = MaterialTheme.colorScheme.onError
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(context.getString(R.string.disk_usage_deleting))
                        } else {
                            Text(context.getString(R.string.disk_usage_delete_confirm))
                        }
                    }
                }
            }
        }
    }
}

/** Formats a byte count as a human-readable string (GiB/MiB/KiB, one decimal above 1 unit). */
private fun formatDiskBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.1f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
