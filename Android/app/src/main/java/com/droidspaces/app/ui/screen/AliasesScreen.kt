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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidspaces.app.util.AliasManager
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import kotlinx.coroutines.launch

/**
 * Shell Aliases Management Screen.
 * Lists all active bash/zsh aliases, lets users add/delete aliases,
 * and automatically refreshes and sources aliases inside the container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliasesScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToTerminalWithCommand: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var aliases by remember { mutableStateOf<List<AliasManager.AliasItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<AliasManager.AliasItem?>(null) }
    var prefillPreset by remember { mutableStateOf<AliasManager.AliasItem?>(null) }

    suspend fun refresh() {
        AliasManager.initialize(context)
        val info = ContainerManager.getContainerInfo(containerName)
        if (info != null) {
            container = info
            val res = AliasManager.listAliases(containerName)
            aliases = res.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(containerName) {
        refresh()
        loading = false
    }

    val filteredAliases = remember(aliases, searchQuery) {
        if (searchQuery.isBlank()) aliases
        else aliases.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.command.contains(searchQuery, ignoreCase = true)
        }
    }

    val presets = remember { AliasManager.getRecommendedPresets() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Aliases & Functions") },
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
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    prefillPreset = null
                    showAddDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Alias")
            }
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Hero Banner
            item {
                AliasesHeroBanner(
                    aliasCount = aliases.size,
                    onAddAlias = {
                        prefillPreset = null
                        showAddDialog = true
                    },
                    onOpenTerminal = {
                        onNavigateToTerminalWithCommand(containerName, "alias")
                    }
                )
            }

            // Search Bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search aliases or commands...") },
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

            // Quick Preset Suggestions Row
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Quick Presets",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { preset ->
                            val alreadyAdded = aliases.any { it.name == preset.name }
                            SuggestionChip(
                                onClick = {
                                    prefillPreset = preset
                                    showAddDialog = true
                                },
                                label = {
                                    Text(
                                        preset.name,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                icon = {
                                    if (alreadyAdded) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF2E7D32))
                                    } else {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
            }

            // List of Aliases
            if (filteredAliases.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                if (searchQuery.isNotEmpty()) "No aliases match '$searchQuery'" else "No custom aliases found",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Add an alias to create shortcuts for your frequent shell commands.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filteredAliases, key = { it.name }) { item ->
                    AliasCard(
                        alias = item,
                        onCopy = {
                            clipboard.setText(AnnotatedString("alias ${item.name}='${item.command}'"))
                            scope.launch { snackbarHostState.showSnackbar("Copied alias ${item.name} to clipboard") }
                        },
                        onTestInTerminal = {
                            onNavigateToTerminalWithCommand(containerName, item.name)
                        },
                        onDelete = { deleteCandidate = item }
                    )
                }
            }
        }
    }

    // Add / Edit Alias Dialog
    if (showAddDialog) {
        AddAliasDialog(
            initialPreset = prefillPreset,
            onDismiss = {
                showAddDialog = false
                prefillPreset = null
            },
            onSave = { name, cmd ->
                scope.launch {
                    showAddDialog = false
                    prefillPreset = null
                    val res = AliasManager.addAlias(containerName, name, cmd)
                    if (res.isSuccess) {
                        snackbarHostState.showSnackbar("Alias '$name' added & sourced successfully ✓")
                        refresh()
                    } else {
                        snackbarHostState.showSnackbar("Failed to add alias: ${res.message}")
                    }
                }
            }
        )
    }

    // Delete Confirmation Dialog
    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Alias") },
            text = {
                Text("Are you sure you want to delete alias '${candidate.name}'? It will be removed and shells refreshed.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val name = candidate.name
                            deleteCandidate = null
                            val res = AliasManager.deleteAlias(containerName, name)
                            if (res.isSuccess) {
                                snackbarHostState.showSnackbar("Alias '$name' removed successfully")
                                refresh()
                            } else {
                                snackbarHostState.showSnackbar("Failed to delete alias: ${res.message}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AliasesHeroBanner(
    aliasCount: Int,
    onAddAlias: () -> Unit,
    onOpenTerminal: () -> Unit
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
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
                    Text("Shell Aliases", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("$aliasCount active aliases configured", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenTerminal) {
                    Icon(Icons.Default.Terminal, contentDescription = "Terminal", tint = MaterialTheme.colorScheme.primary)
                }
                FilledIconButton(
                    onClick = onAddAlias,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Alias", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun AliasCard(
    alias: AliasManager.AliasItem,
    onCopy: () -> Unit,
    onTestInTerminal: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = alias.name,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = alias.source.uppercase(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onTestInTerminal, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Test", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Command snippet box
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            ) {
                Text(
                    text = alias.command,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun AddAliasDialog(
    initialPreset: AliasManager.AliasItem? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, command: String) -> Unit
) {
    var name by remember(initialPreset) { mutableStateOf(initialPreset?.name ?: "") }
    var command by remember(initialPreset) { mutableStateOf(initialPreset?.command ?: "") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var commandError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialPreset != null) "Add Preset Alias" else "Add New Alias") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.trim()
                        nameError = if (name.contains(" ")) "Alias name cannot contain spaces" else null
                    },
                    label = { Text("Alias Name") },
                    placeholder = { Text("e.g. ll, gs, dc") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = command,
                    onValueChange = {
                        command = it
                        commandError = if (command.isBlank()) "Command cannot be empty" else null
                    },
                    label = { Text("Command") },
                    placeholder = { Text("e.g. eza -la --icons --git") },
                    isError = commandError != null,
                    supportingText = commandError?.let { { Text(it) } },
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Aliases are stored globally in /etc/profile.d/droidspaces_aliases.sh and loaded in both Bash and Zsh automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        nameError = "Name cannot be empty"
                        return@Button
                    }
                    if (command.isBlank()) {
                        commandError = "Command cannot be empty"
                        return@Button
                    }
                    onSave(name, command)
                },
                enabled = name.isNotBlank() && command.isNotBlank() && nameError == null
            ) {
                Text("Save & Source")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
