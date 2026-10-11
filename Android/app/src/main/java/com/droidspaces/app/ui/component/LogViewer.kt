package com.droidspaces.app.ui.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidspaces.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LogMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

/**
 * High-performance, unified log viewer component matching journalctl/Docker styling.
 */
@Composable
fun LogViewerContent(
    lines: List<String>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    autoScroll: Boolean = true,
    onScrollToBottom: () -> Unit = {}
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = LocalContext.current.getString(R.string.log_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D0D0D)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(lines.size) { idx ->
                        val line = lines[idx]
                        val color = when {
                            line.contains("error", ignoreCase = true) || line.contains("fail", ignoreCase = true) -> Color(0xFFEF5350)
                            line.contains("warn", ignoreCase = true) -> Color(0xFFFFCA28)
                            line.contains("success", ignoreCase = true) || line.contains("started", ignoreCase = true) || line.contains("ready", ignoreCase = true) -> Color(0xFF81C784)
                            else -> Color(0xFFE0E0E0)
                        }
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = LogMono, fontSize = 12.sp),
                            color = color,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !autoScroll,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = onScrollToBottom,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Jump to bottom")
                }
            }
        }
    }
}

/**
 * Unified Log Viewer Dialog for viewing live streaming logs (Wireless, Anland, Systemd, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerDialog(
    title: String,
    subtitle: String? = null,
    logFileName: String = "service.log",
    fetchLogs: suspend () -> String,
    pollIntervalMs: Long = 2000L,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var isLive by remember { mutableStateOf(true) }
    var autoScroll by remember { mutableStateOf(true) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

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
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(lines.joinToString("\n").toByteArray())
                }
                snackbarHostState.showSnackbar(context.getString(R.string.log_download_saved))
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.log_download_failed))
            }
        }
    }

    LaunchedEffect(isLive) {
        while (isLive) {
            val rawLogs = fetchLogs()
            lines = rawLogs.lines()
                .map { line ->
                    val crResolved = if (line.contains('\r')) {
                        line.split('\r').lastOrNull { it.isNotBlank() } ?: line
                    } else {
                        line
                    }
                    if (crResolved.length > 2000) crResolved.take(2000) + " … [truncated]" else crResolved
                }
                .filter { it.isNotBlank() }
            loading = false
            if (autoScroll) {
                scrollToBottom(animate = false)
            }
            delay(pollIntervalMs)
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (isProgrammaticScroll) return@LaunchedEffect
        if (lines.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        autoScroll = lastVisible == null || lastVisible >= lines.size - 2
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = LogMono), maxLines = 1)
                            Text(
                                text = if (subtitle != null) "$subtitle • ${if (isLive) context.getString(R.string.log_status_live) else context.getString(R.string.log_status_paused)}"
                                       else if (isLive) context.getString(R.string.log_status_live) else context.getString(R.string.log_status_paused),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isLive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(onClick = { isLive = !isLive }) {
                            Icon(
                                if (isLive) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isLive) "Pause" else "Resume"
                            )
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                            scope.launch { snackbarHostState.showSnackbar("Logs copied to clipboard") }
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
                        }
                        IconButton(onClick = {
                            downloadLauncher.launch(logFileName)
                        }) {
                            Icon(Icons.Default.Download, contentDescription = "Download log")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (loading && lines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
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
