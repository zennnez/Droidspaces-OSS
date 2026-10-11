package com.droidspaces.app.ui.screen

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
import com.droidspaces.app.util.PackagesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Packages, Shell Environment & Dev Runtimes Management Screen.
 * Includes category filters, search, 1-tap suite installers, and individual package actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToTerminalWithCommand: (String, String) -> Unit = { _, _ -> },
    opsViewModel: ContainerOperationsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var status by remember { mutableStateOf<PackagesManager.PackageStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    var selectedCategory by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }

    val categories = listOf("All", "CLI Tools", "Shell & Terminal", "Languages & SDKs", "AI & Tools")

    suspend fun refresh() {
        PackagesManager.initialize(context)
        val info = ContainerManager.getContainerInfo(containerName)
        if (info != null) {
            container = info
            if (info.isRunning) {
                status = PackagesManager.getStatus(containerName)
            }
        }
    }

    LaunchedEffect(containerName) {
        refresh()
        loading = false
        while (true) {
            delay(4000)
            if (container?.isRunning == true && !busy) {
                status = PackagesManager.getStatus(containerName)
            }
        }
    }

    val current = container
    val currentStatus = status ?: PackagesManager.PackageStatus()
    val allPackages = remember(currentStatus) { PackagesManager.getPackageCatalog(currentStatus) }

    val filteredPackages = remember(allPackages, selectedCategory, searchQuery) {
        allPackages.filter { pkg ->
            val matchesCat = selectedCategory == "All" || pkg.category == selectedCategory
            val matchesQuery = searchQuery.isBlank() ||
                pkg.name.contains(searchQuery, ignoreCase = true) ||
                pkg.description.contains(searchQuery, ignoreCase = true) ||
                pkg.id.contains(searchQuery, ignoreCase = true)
            matchesCat && matchesQuery
        }
    }

    // Centralized Log Viewer Dialog
    if (opsViewModel.showLogViewerFor == containerName) {
        TerminalDialog(
            title = "Package Setup Logs",
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
                title = { Text("Packages & Shell") },
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
            // Hero Card: Overview & Install All
            item {
                PackagesHeroCard(
                    status = currentStatus,
                    totalCount = allPackages.size,
                    installedCount = allPackages.count { it.isInstalled },
                    busy = busy || opsViewModel.runningOperationContainer == containerName,
                    onInstallAll = {
                        scope.launch {
                            container?.let {
                                opsViewModel.executeInstallPackage(
                                    container = it,
                                    subcommand = "install-all",
                                    label = "All Essential Packages",
                                    onRefresh = { scope.launch { refresh() } },
                                    onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                    onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                )
                            }
                        }
                    }
                )
            }

            // Quick Suite Actions Bar
            item {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {
                            scope.launch {
                                container?.let {
                                    opsViewModel.executeInstallPackage(
                                        container = it,
                                        subcommand = "install-cli",
                                        label = "CLI Suite",
                                        onRefresh = { scope.launch { refresh() } },
                                        onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                        onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                    )
                                }
                            }
                        },
                        label = { Text("Install CLI Suite") },
                        leadingIcon = { Icon(Icons.Default.Handyman, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        enabled = !busy && opsViewModel.runningOperationContainer == null
                    )

                    AssistChip(
                        onClick = {
                            scope.launch {
                                container?.let {
                                    opsViewModel.executeInstallPackage(
                                        container = it,
                                        subcommand = "install-runtimes",
                                        label = "Dev Runtimes",
                                        onRefresh = { scope.launch { refresh() } },
                                        onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                        onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                    )
                                }
                            }
                        },
                        label = { Text("Install Dev Runtimes") },
                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        enabled = !busy && opsViewModel.runningOperationContainer == null
                    )

                    AssistChip(
                        onClick = {
                            scope.launch {
                                container?.let {
                                    opsViewModel.executeSetupZsh(
                                        container = it,
                                        onRefresh = { scope.launch { refresh() } },
                                        onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                        onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                    )
                                }
                            }
                        },
                        label = { Text("Configure Oh My Zsh") },
                        leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        enabled = !busy && opsViewModel.runningOperationContainer == null
                    )
                }
            }

            // Search Bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search tools & runtimes...") },
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
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }

            // Package Items List
            items(filteredPackages, key = { it.id }) { pkg ->
                PackageListItemCard(
                    pkg = pkg,
                    busy = busy || opsViewModel.runningOperationContainer == containerName,
                    onInstall = {
                        scope.launch {
                            container?.let {
                                if (pkg.id == "ohmyzsh" || pkg.id == "zsh") {
                                    opsViewModel.executeSetupZsh(
                                        container = it,
                                        onRefresh = { scope.launch { refresh() } },
                                        onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                        onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                    )
                                } else {
                                    opsViewModel.executeInstallPackage(
                                        container = it,
                                        subcommand = "install-${pkg.id}",
                                        label = pkg.name,
                                        onRefresh = { scope.launch { refresh() } },
                                        onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                        onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                    )
                                }
                            }
                        }
                    },
                    onTestInTerminal = {
                        val testCmd = when (pkg.id) {
                            "eza" -> "eza -la"
                            "zoxide" -> "zoxide --version"
                            "fastfetch" -> "fastfetch"
                            "delta" -> "delta --version"
                            "bat" -> "bat --version || batcat --version"
                            "btop" -> "btop"
                            "fzf" -> "fzf --version"
                            "tmux" -> "tmux -V"
                            "python" -> "python3 --version && uv --version"
                            "node" -> "node --version && npm --version"
                            "bun" -> "bun --version"
                            "rust" -> "rustc --version && cargo --version"
                            "go" -> "go version"
                            "neovim" -> "nvim --version"
                            "starship" -> "starship --version"
                            "ollama" -> "ollama --version"
                            "gh" -> "gh --version"
                            "claude" -> "claude --version"
                            else -> "${pkg.id} --version"
                        }
                        onNavigateToTerminalWithCommand(containerName, testCmd)
                    }
                )
            }
        }
    }
}

@Composable
private fun PackagesHeroCard(
    status: PackagesManager.PackageStatus,
    totalCount: Int,
    installedCount: Int,
    busy: Boolean,
    onInstallAll: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        }
                    }

                    Column {
                        Text("Dev Environment & Packages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("$installedCount of $totalCount tools installed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            LinearProgressIndicator(
                progress = if (totalCount > 0) (installedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0f,
                modifier = Modifier.fillMaxWidth().height(6.dp)
            )

            Button(
                onClick = onInstallAll,
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Install All Essentials")
                }
            }
        }
    }
}

@Composable
private fun PackageListItemCard(
    pkg: PackagesManager.PackageItem,
    busy: Boolean,
    onInstall: () -> Unit,
    onTestInTerminal: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pkg.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (pkg.isInstalled) Color(0xFF2E7D32).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (pkg.isInstalled) "INSTALLED" else "AVAILABLE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (pkg.isInstalled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(pkg.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pkg.isInstalled) {
                    IconButton(onClick = onTestInTerminal, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Test in Terminal", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onInstall, enabled = !busy, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reinstall", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        enabled = !busy,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Install", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
