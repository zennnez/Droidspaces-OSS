package com.droidspaces.app.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidspaces.app.R
import com.droidspaces.app.ui.component.LogMono
import com.droidspaces.app.ui.component.LogViewerContent
import com.droidspaces.app.util.DockerLogManager
import kotlinx.coroutines.launch

/**
 * Live, scrollable Docker container log viewer (journalctl-style).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerContainerLogsScreen(
    containerName: String,
    dockerContainerId: String,
    dockerContainerName: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLive by remember { mutableStateOf(true) }
    var showTimestamps by remember { mutableStateOf(true) }
    var autoScroll by remember { mutableStateOf(true) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    suspend fun scrollToBottom(animate: Boolean) {
        if (lines.isEmpty()) return
        isProgrammaticScroll = true
        try {
            val target = lines.size - 1
            if (animate) listState.animateScrollToItem(target) else listState.scrollToItem(target)
        } finally {
            isProgrammaticScroll = false
        }
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = DockerLogManager.fetchFullLogForDownload(containerName, dockerContainerId, showTimestamps)
            if (!result.isSuccess) {
                snackbarHostState.showSnackbar(result.error ?: "Failed to download logs")
                return@launch
            }
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(result.lines.joinToString("\n").toByteArray())
                }
                snackbarHostState.showSnackbar(context.getString(R.string.log_download_saved))
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: "Failed to save logs")
            }
        }
    }

    // Initial load
    LaunchedEffect(containerName, dockerContainerId, showTimestamps) {
        loading = true
        loadError = null
        val result = DockerLogManager.fetchLogs(containerName, dockerContainerId, showTimestamps = showTimestamps)
        lines = result.lines
        loadError = if (!result.isSuccess) result.error else null
        loading = false
        scrollToBottom(animate = false)
    }

    // Live tail
    LaunchedEffect(containerName, dockerContainerId, isLive, showTimestamps) {
        if (!isLive || loading) return@LaunchedEffect
        DockerLogManager.streamNewLines(containerName, dockerContainerId, showTimestamps).collect { newLine ->
            lines = lines + newLine
            if (autoScroll) scrollToBottom(animate = false)
        }
    }

    // Auto-scroll handler
    LaunchedEffect(listState.isScrollInProgress) {
        if (isProgrammaticScroll) return@LaunchedEffect
        if (listState.isScrollInProgress) {
            autoScroll = false
            return@LaunchedEffect
        }
        if (lines.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        autoScroll = lastVisible == null || lastVisible >= lines.size - 2
    }

    val filteredIndices = remember(lines, searchQuery) {
        if (searchQuery.isBlank()) emptySet()
        else lines.mapIndexedNotNull { index, line -> if (line.contains(searchQuery, ignoreCase = true)) index else null }.toSet()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            dockerContainerName.ifBlank { dockerContainerId.take(12) },
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = LogMono),
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                Modifier.size(8.dp).background(
                                    if (isLive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    CircleShape
                                )
                            )
                            Text(
                                if (isLive) "LIVE STREAMING" else "PAUSED",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isLive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isLive = !isLive }) {
                        Icon(
                            if (isLive) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isLive) "Pause stream" else "Resume stream"
                        )
                    }
                    IconButton(onClick = { showTimestamps = !showTimestamps }) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = "Toggle Timestamps",
                            tint = if (showTimestamps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = {
                        val text = lines.joinToString("\n")
                        clipboard.setText(AnnotatedString(text))
                        scope.launch { snackbarHostState.showSnackbar("Logs copied to clipboard") }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
                    }
                    IconButton(onClick = {
                        val filename = "docker_${dockerContainerName.replace(Regex("[^a-zA-Z0-9_-]"), "_")}_logs.txt"
                        downloadLauncher.launch(filename)
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!autoScroll && lines.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { scope.launch { scrollToBottom(animate = true) } },
                    modifier = Modifier.size(44.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom", modifier = Modifier.size(20.dp))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            AnimatedVisibility(visible = showSearch) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search logs...") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            }
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (loadError != null) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Error Loading Docker Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(loadError ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (lines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No log entries found for this container.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LogViewerContent(
                        lines = lines,
                        listState = listState,
                        autoScroll = autoScroll,
                        onScrollToBottom = {
                            scope.launch {
                                scrollToBottom(animate = true)
                                autoScroll = true
                            }
                        }
                    )
                }
            }
        }
    }
}
