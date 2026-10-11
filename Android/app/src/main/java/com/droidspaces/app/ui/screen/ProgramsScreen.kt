package com.droidspaces.app.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.ui.component.TerminalDialog
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.ProgramsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Programs, Web Services & Desktop Applications Installer Screen.
 * Catalog of Web servers, IDEs, GUI apps, desktop suites, web browsers, and AI runners.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramsScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToTerminalWithCommand: (String, String) -> Unit = { _, _ -> },
    opsViewModel: ContainerOperationsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var status by remember { mutableStateOf<ProgramsManager.ProgramStatus?>(null) }
    var selectedCategory by remember { mutableStateOf<String?>("All") }
    var searchQuery by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    val categories = listOf("All", "Web Services", "Development", "Productivity", "Media & Web")

    suspend fun refresh() {
        ProgramsManager.initialize(context)
        val info = ContainerManager.getContainerInfo(containerName)
        if (info != null) {
            container = info
            if (info.isRunning) {
                status = ProgramsManager.getStatus(containerName)
            }
        }
    }

    LaunchedEffect(containerName) {
        refresh()
        loading = false
        while (true) {
            delay(4000)
            if (container?.isRunning == true && !busy) {
                status = ProgramsManager.getStatus(containerName)
            }
        }
    }

    val current = container
    val currentStatus = status ?: ProgramsManager.ProgramStatus()
    val catalog = remember(currentStatus) { ProgramsManager.getProgramCatalog(currentStatus) }

    val filteredPrograms = remember(catalog, selectedCategory, searchQuery) {
        catalog.filter { prog ->
            val matchesCategory = selectedCategory == null || selectedCategory == "All" || prog.category == selectedCategory
            val matchesQuery = searchQuery.isBlank() ||
                prog.name.contains(searchQuery, ignoreCase = true) ||
                prog.description.contains(searchQuery, ignoreCase = true) ||
                prog.id.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }

    // Centralized Log Viewer Dialog
    if (opsViewModel.showLogViewerFor == containerName) {
        TerminalDialog(
            title = "Programs Operation Logs",
            logs = opsViewModel.logsFor(containerName),
            isBlocking = opsViewModel.runningOperationContainer == containerName,
            onDismiss = { opsViewModel.showLogViewerFor = null },
            onClear = {
                opsViewModel.clearLogsBuffer(containerName)
                scope.launch { refresh() }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Programs & Apps") },
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
        if (loading || current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header summary banner
            item {
                ProgramsHeroBanner(
                    installedCount = catalog.count { it.isInstalled },
                    totalCount = catalog.size
                )
            }

            // Search Bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search programs & services...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
            }

            // Category Filter Chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Programs list
            items(filteredPrograms, key = { it.id }) { prog ->
                ProgramCard(
                    program = prog,
                    busy = busy || opsViewModel.runningOperationContainer == containerName,
                    onInstall = {
                        scope.launch {
                            busy = true
                            opsViewModel.executeInstallProgram(
                                container = current,
                                programId = prog.id,
                                programName = prog.name,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { scope.launch { snackbarHostState.showSnackbar(it) } },
                                onSuccess = { scope.launch { snackbarHostState.showSnackbar(it) } }
                            )
                            busy = false
                        }
                    },
                    onUninstall = {
                        scope.launch {
                            busy = true
                            opsViewModel.executeUninstallProgram(
                                container = current,
                                programId = prog.id,
                                programName = prog.name,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { scope.launch { snackbarHostState.showSnackbar(it) } },
                                onSuccess = { scope.launch { snackbarHostState.showSnackbar(it) } }
                            )
                            busy = false
                        }
                    },
                    onStartService = {
                        scope.launch {
                            busy = true
                            opsViewModel.executeStartProgramService(
                                container = current,
                                programId = prog.id,
                                programName = prog.name,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { scope.launch { snackbarHostState.showSnackbar(it) } },
                                onSuccess = { scope.launch { snackbarHostState.showSnackbar(it) } }
                            )
                            busy = false
                        }
                    },
                    onStopService = {
                        scope.launch {
                            busy = true
                            opsViewModel.executeStopProgramService(
                                container = current,
                                programId = prog.id,
                                programName = prog.name,
                                onRefresh = { scope.launch { refresh() } },
                                onError = { scope.launch { snackbarHostState.showSnackbar(it) } },
                                onSuccess = { scope.launch { snackbarHostState.showSnackbar(it) } }
                            )
                            busy = false
                        }
                    },
                    onOpenBrowser = { port ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port"))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            scope.launch { snackbarHostState.showSnackbar("Could not open browser: ${e.message}") }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ProgramsHeroBanner(
    installedCount: Int,
    totalCount: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                }

                Column {
                    Text("Application Catalog", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("$installedCount of $totalCount apps installed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (installedCount > 0) Color(0xFF2E7D32).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "$installedCount / $totalCount",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (installedCount > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProgramCard(
    program: ProgramsManager.ProgramInfo,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onStartService: () -> Unit = {},
    onStopService: () -> Unit = {},
    onOpenBrowser: (Int) -> Unit = {}
) {
    val icon = when (program.id) {
        "code_server" -> Icons.Default.IntegrationInstructions
        "jupyter" -> Icons.Default.Science
        "filebrowser" -> Icons.Default.FolderSpecial
        "ollama" -> Icons.Default.SmartToy
        "antigravity" -> Icons.Default.AutoAwesome
        "libreoffice" -> Icons.Default.Description
        "chromium", "firefox" -> Icons.Default.Public
        "gimp" -> Icons.Default.Brush
        "vlc" -> Icons.Default.PlayCircle
        "caddy" -> Icons.Default.Dns
        "transmission" -> Icons.Default.CloudDownload
        "xfce" -> Icons.Default.DesktopWindows
        else -> Icons.Default.Extension
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(program.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            if (program.isInstalled) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF2E7D32).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        "INSTALLED",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }
                            if (program.isService && program.isInstalled) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (program.isRunning) Color(0xFF1976D2).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        if (program.isRunning) "RUNNING :${program.webPort ?: ""}" else "STOPPED",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (program.isRunning) Color(0xFF1976D2) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Text(program.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (program.isInstalled) {
                    OutlinedButton(
                        onClick = onUninstall,
                        enabled = !busy,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Remove", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        enabled = !busy,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Install", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // If it is a web service and installed, provide service controls and open web UI
            if (program.isInstalled && program.isService) {
                HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (program.isRunning) {
                        Button(
                            onClick = { program.webPort?.let { onOpenBrowser(it) } },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open Web UI", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            onClick = onStopService,
                            enabled = !busy,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("Stop Daemon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Button(
                            onClick = onStartService,
                            enabled = !busy,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start Service", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
