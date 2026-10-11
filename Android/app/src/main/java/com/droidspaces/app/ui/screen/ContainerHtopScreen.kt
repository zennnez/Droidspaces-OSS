package com.droidspaces.app.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidspaces.app.util.ContainerProcessManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ProcessSortField {
    PID, USER, CPU, MEM, TIME, COMMAND
}

/**
 * HTOP-style Process & System Resource Monitor for containers.
 * Shows live CPU/RAM/Swap meters, interactive process table, sorting, search,
 * and quick process signals (Kill, Stop, Continue, Renice).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerHtopScreen(
    containerName: String,
    initialSort: String = "cpu",
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var sortField by remember {
        mutableStateOf(if (initialSort.equals("mem", ignoreCase = true)) ProcessSortField.MEM else ProcessSortField.CPU)
    }
    var sortAscending by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isLivePaused by remember { mutableStateOf(false) }

    var metrics by remember { mutableStateOf(ContainerProcessManager.HtopMetrics()) }
    var processes by remember { mutableStateOf<List<ContainerProcessManager.ProcessInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedProcess by remember { mutableStateOf<ContainerProcessManager.ProcessInfo?>(null) }
    var reniceDialogProcess by remember { mutableStateOf<ContainerProcessManager.ProcessInfo?>(null) }
    var killConfirmProcess by remember { mutableStateOf<ContainerProcessManager.ProcessInfo?>(null) }

    suspend fun refreshData() {
        val m = ContainerProcessManager.getHtopMetrics(containerName)
        val p = ContainerProcessManager.getProcessList(containerName)
        metrics = m
        processes = p
        loading = false
    }

    LaunchedEffect(containerName) {
        refreshData()
        while (true) {
            delay(2000)
            if (!isLivePaused) {
                refreshData()
            }
        }
    }

    val filteredSortedProcesses = remember(processes, searchQuery, sortField, sortAscending) {
        var list = processes
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter {
                it.command.lowercase().contains(q) ||
                it.pid.toString().contains(q) ||
                (it.user?.lowercase()?.contains(q) == true)
            }
        }
        when (sortField) {
            ProcessSortField.PID -> if (sortAscending) list.sortedBy { it.pid } else list.sortedByDescending { it.pid }
            ProcessSortField.USER -> if (sortAscending) list.sortedBy { it.user ?: "" } else list.sortedByDescending { it.user ?: "" }
            ProcessSortField.CPU -> if (sortAscending) list.sortedBy { it.cpu ?: 0.0 } else list.sortedByDescending { it.cpu ?: 0.0 }
            ProcessSortField.MEM -> if (sortAscending) list.sortedBy { it.mem ?: 0.0 } else list.sortedByDescending { it.mem ?: 0.0 }
            ProcessSortField.TIME -> if (sortAscending) list.sortedBy { it.time ?: "" } else list.sortedByDescending { it.time ?: "" }
            ProcessSortField.COMMAND -> if (sortAscending) list.sortedBy { it.command } else list.sortedByDescending { it.command }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HTOP Monitor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(containerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isLivePaused = !isLivePaused }) {
                        Icon(
                            if (isLivePaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isLivePaused) "Resume" else "Pause",
                            tint = if (isLivePaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { scope.launch { refreshData() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 1. HTOP System Meters (CPU, RAM, Swap, Tasks, Load)
            HtopHeaderMeters(metrics = metrics)

            // 2. Search & Quick Filters Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filter processes...", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )

                    // Sort order toggle indicator
                    FilledTonalIconButton(
                        onClick = { sortAscending = !sortAscending },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = if (sortAscending) "Ascending" else "Descending",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 3. Table Column Headers
            HtopTableHeaders(
                currentSort = sortField,
                sortAscending = sortAscending,
                onSelectSort = { field ->
                    if (sortField == field) {
                        sortAscending = !sortAscending
                    } else {
                        sortField = field
                        sortAscending = false
                    }
                }
            )

            // 4. Process List
            if (loading && processes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredSortedProcesses.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matching processes found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredSortedProcesses, key = { it.pid }) { proc ->
                        HtopProcessRow(
                            proc = proc,
                            onClick = { selectedProcess = proc }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }

    // Process Action Bottom Sheet / Dialog
    selectedProcess?.let { proc ->
        HtopProcessActionSheet(
            proc = proc,
            onDismiss = { selectedProcess = null },
            onKill = { killConfirmProcess = proc },
            onPause = {
                scope.launch {
                    val ok = ContainerProcessManager.pauseProcess(containerName, proc.pid)
                    if (ok) snackbarHostState.showSnackbar("Process ${proc.pid} paused (SIGSTOP)")
                    else snackbarHostState.showSnackbar("Failed to pause process ${proc.pid}")
                    refreshData()
                    selectedProcess = null
                }
            },
            onResume = {
                scope.launch {
                    val ok = ContainerProcessManager.resumeProcess(containerName, proc.pid)
                    if (ok) snackbarHostState.showSnackbar("Process ${proc.pid} resumed (SIGCONT)")
                    else snackbarHostState.showSnackbar("Failed to resume process ${proc.pid}")
                    refreshData()
                    selectedProcess = null
                }
            },
            onRenice = {
                reniceDialogProcess = proc
                selectedProcess = null
            },
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
                scope.launch { snackbarHostState.showSnackbar("Command copied") }
            }
        )
    }

    // Kill Confirmation Dialog
    killConfirmProcess?.let { proc ->
        AlertDialog(
            onDismissRequest = { killConfirmProcess = null },
            title = { Text("Kill Process ${proc.pid}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Command: ${proc.command}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("Choose signal to send to the process:")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val ok = ContainerProcessManager.killProcess(containerName, proc.pid, force = true)
                            if (ok) snackbarHostState.showSnackbar("Process ${proc.pid} killed (SIGKILL)")
                            else snackbarHostState.showSnackbar("Failed to kill process")
                            refreshData()
                            killConfirmProcess = null
                            selectedProcess = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Force Kill (SIGKILL)")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val ok = ContainerProcessManager.killProcess(containerName, proc.pid, force = false)
                            if (ok) snackbarHostState.showSnackbar("SIGTERM sent to ${proc.pid}")
                            else snackbarHostState.showSnackbar("Failed to terminate process")
                            refreshData()
                            killConfirmProcess = null
                            selectedProcess = null
                        }
                    }
                ) {
                    Text("Terminate (SIGTERM)")
                }
            }
        )
    }

    // Renice Dialog
    reniceDialogProcess?.let { proc ->
        var niceVal by remember { mutableStateOf(proc.ni?.toIntOrNull() ?: 0) }
        AlertDialog(
            onDismissRequest = { reniceDialogProcess = null },
            title = { Text("Renice Process ${proc.pid}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Nice value: $niceVal (-20 high priority to 19 low priority)")
                    Slider(
                        value = niceVal.toFloat(),
                        onValueChange = { niceVal = it.toInt() },
                        valueRange = -20f..19f,
                        steps = 38
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val ok = ContainerProcessManager.reniceProcess(containerName, proc.pid, niceVal)
                        if (ok) snackbarHostState.showSnackbar("Process ${proc.pid} reniced to $niceVal")
                        else snackbarHostState.showSnackbar("Failed to renice process")
                        refreshData()
                        reniceDialogProcess = null
                    }
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { reniceDialogProcess = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HtopHeaderMeters(metrics: ContainerProcessManager.HtopMetrics) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // CPU & RAM Bars
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // CPU Meter
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("CPU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(String.format("%.1f%%", metrics.cpuPercent), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { (metrics.cpuPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = when {
                            metrics.cpuPercent > 80.0 -> MaterialTheme.colorScheme.error
                            metrics.cpuPercent > 50.0 -> Color(0xFFFFA726)
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // RAM Meter
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("MEM", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        Text("${metrics.ramUsedMb}/${metrics.ramTotalMb}M (${String.format("%.1f%%", metrics.ramPercent)})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { (metrics.ramPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            // Summary Badges: Tasks, Load Avg, Uptime
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tasks: ${metrics.totalTasks}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text("(${metrics.runningTasks} running, ${metrics.sleepingTasks} slp)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (metrics.loadAverage.isNotBlank()) {
                    Text("Load: ${metrics.loadAverage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HtopTableHeaders(
    currentSort: ProcessSortField,
    sortAscending: Boolean,
    onSelectSort: (ProcessSortField) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderCell("PID", 54.dp, currentSort == ProcessSortField.PID, sortAscending) { onSelectSort(ProcessSortField.PID) }
            HeaderCell("USER", 60.dp, currentSort == ProcessSortField.USER, sortAscending) { onSelectSort(ProcessSortField.USER) }
            HeaderCell("PRI", 34.dp, false, false) {}
            HeaderCell("RES", 48.dp, false, false) {}
            HeaderCell("%CPU", 50.dp, currentSort == ProcessSortField.CPU, sortAscending) { onSelectSort(ProcessSortField.CPU) }
            HeaderCell("%MEM", 50.dp, currentSort == ProcessSortField.MEM, sortAscending) { onSelectSort(ProcessSortField.MEM) }
            HeaderCell("TIME+", 54.dp, currentSort == ProcessSortField.TIME, sortAscending) { onSelectSort(ProcessSortField.TIME) }
            HeaderCell("Command", Modifier.weight(1f), currentSort == ProcessSortField.COMMAND, sortAscending) { onSelectSort(ProcessSortField.COMMAND) }
        }
    }
}

@Composable
private fun HeaderCell(
    title: String,
    width: androidx.compose.ui.unit.Dp,
    isActive: Boolean,
    isAscending: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(width)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isActive) {
            Icon(
                if (isAscending) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun HeaderCell(
    title: String,
    modifier: Modifier,
    isActive: Boolean,
    isAscending: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isActive) {
            Icon(
                if (isAscending) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun HtopProcessRow(
    proc: ContainerProcessManager.ProcessInfo,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // PID
        Text(
            text = "${proc.pid}",
            modifier = Modifier.width(54.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // USER
        Text(
            text = proc.user ?: "-",
            modifier = Modifier.width(60.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // PRI
        Text(
            text = proc.pri ?: "-",
            modifier = Modifier.width(34.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // RES
        Text(
            text = proc.res ?: "-",
            modifier = Modifier.width(48.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // %CPU
        val cpuVal = proc.cpu ?: 0.0
        Text(
            text = if (cpuVal > 0.0) String.format("%.1f", cpuVal) else "0.0",
            modifier = Modifier.width(50.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = if (cpuVal > 1.0) FontWeight.Bold else FontWeight.Normal,
            color = if (cpuVal > 10.0) MaterialTheme.colorScheme.error else if (cpuVal > 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )

        // %MEM
        val memVal = proc.mem ?: 0.0
        Text(
            text = if (memVal > 0.0) String.format("%.1f", memVal) else "0.0",
            modifier = Modifier.width(50.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = if (memVal > 1.0) FontWeight.Bold else FontWeight.Normal,
            color = if (memVal > 10.0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
        )

        // TIME
        Text(
            text = proc.time ?: "-",
            modifier = Modifier.width(54.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Command
        Text(
            text = proc.command,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HtopProcessActionSheet(
    proc: ContainerProcessManager.ProcessInfo,
    onDismiss: () -> Unit,
    onKill: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRenice: () -> Unit,
    onCopy: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Process ${proc.pid}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "User: ${proc.user ?: "unknown"} | State: ${proc.state ?: "S"} | Nice: ${proc.ni ?: "0"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "CPU ${proc.cpu ?: 0.0}% | MEM ${proc.mem ?: 0.0}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Command Box
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = proc.command,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onCopy(proc.command) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Command", modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Quick Actions Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onKill,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Dangerous, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Kill")
                }

                OutlinedButton(
                    onClick = if (proc.state?.startsWith("T") == true) onResume else onPause,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (proc.state?.startsWith("T") == true) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (proc.state?.startsWith("T") == true) "Resume" else "Pause")
                }

                FilledTonalButton(
                    onClick = onRenice,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Renice")
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}
