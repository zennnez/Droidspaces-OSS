package com.droidspaces.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidspaces.app.R
import com.droidspaces.app.util.ContainerDockerManager
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DockerMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

enum class DockerTab { CONTAINERS, IMAGES, DAEMON }

/**
 * Full-featured Docker management screen with service-like container lifecycle control,
 * image management, daemon settings, and journalctl-style live logging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    onViewDockerLogs: (dockerContainerId: String, dockerContainerName: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableStateOf(DockerTab.CONTAINERS) }
    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var readiness by remember { mutableStateOf<ContainerManager.DockerReadiness?>(null) }
    var status by remember { mutableStateOf<ContainerManager.DockerStatus?>(null) }
    var dockerContainers by remember { mutableStateOf<List<ContainerDockerManager.DockerContainerInfo>>(emptyList()) }
    var dockerImages by remember { mutableStateOf<List<ContainerDockerManager.DockerImageInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    // Dialog & Inspection States
    var inspectData by remember { mutableStateOf<Pair<String, String>?>(null) } // Name to JSON
    var showRunDialog by remember { mutableStateOf(false) }
    var prefillImageForRun by remember { mutableStateOf("") }
    var showPullDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ContainerDockerManager.DockerContainerInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<ContainerDockerManager.DockerContainerInfo?>(null) }
    var deleteImageTarget by remember { mutableStateOf<ContainerDockerManager.DockerImageInfo?>(null) }
    var pruneConfirmType by remember { mutableStateOf<String?>(null) } // "containers" | "images" | "system"

    // Container Filters
    var containerSearchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, RUNNING, STOPPED, PAUSED

    suspend fun refresh() {
        val updated = ContainerManager.getContainerInfo(containerName)
        if (updated != null) {
            container = updated
            readiness = ContainerManager.getDockerReadiness(context, containerName)
            val st = ContainerManager.getDockerStatus(context, updated)
            status = st
            if (st.running) {
                dockerContainers = ContainerDockerManager.listContainers(context, updated.name).getOrDefault(emptyList())
                dockerImages = ContainerDockerManager.listImages(context, updated.name).getOrDefault(emptyList())
            } else {
                dockerContainers = emptyList()
                dockerImages = emptyList()
            }
        }
    }

    LaunchedEffect(containerName) {
        refresh()
        loading = false
        while (true) {
            delay(3500)
            if (container?.isRunning == true) {
                val current = container
                if (current != null) {
                    val st = ContainerManager.getDockerStatus(context, current)
                    status = st
                    if (st.running) {
                        dockerContainers = ContainerDockerManager.listContainers(context, current.name).getOrDefault(emptyList())
                        dockerImages = ContainerDockerManager.listImages(context, current.name).getOrDefault(emptyList())
                    }
                }
            }
        }
    }

    val current = container

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(context.getString(R.string.docker_card_title), fontWeight = FontWeight.Bold)
                            Text(
                                containerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
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

                PrimaryTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Tab(
                        selected = selectedTab == DockerTab.CONTAINERS,
                        onClick = { selectedTab = DockerTab.CONTAINERS },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Containers")
                                if (dockerContainers.isNotEmpty()) {
                                    Badge { Text(dockerContainers.size.toString()) }
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.Dns, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == DockerTab.IMAGES,
                        onClick = { selectedTab = DockerTab.IMAGES },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Images")
                                if (dockerImages.isNotEmpty()) {
                                    Badge { Text(dockerImages.size.toString()) }
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.Layers, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == DockerTab.DAEMON,
                        onClick = { selectedTab = DockerTab.DAEMON },
                        text = { Text("Daemon & Config") },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                    )
                }
            }
        }
    ) { padding ->
        if (loading || current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                DockerTab.CONTAINERS -> {
                    DockerContainersTab(
                        container = current,
                        status = status,
                        containers = dockerContainers,
                        readiness = readiness,
                        busy = busy,
                        searchQuery = containerSearchQuery,
                        onSearchQueryChange = { containerSearchQuery = it },
                        selectedFilter = selectedFilter,
                        onFilterSelected = { selectedFilter = it },
                        onStartDaemon = {
                            scope.launch {
                                busy = true
                                val res = ContainerManager.dockerServiceControl(context, current, "start")
                                if (res.isSuccess) snackbarHostState.showSnackbar("Docker daemon started")
                                else snackbarHostState.showSnackbar("Failed to start: ${res.exceptionOrNull()?.message}")
                                refresh()
                                busy = false
                            }
                        },
                        onInstallDocker = {
                            scope.launch {
                                busy = true
                                val res = ContainerManager.installDocker(context, current)
                                if (res.isSuccess) snackbarHostState.showSnackbar("Docker installed successfully!")
                                else snackbarHostState.showSnackbar("Install failed: ${res.exceptionOrNull()?.message}")
                                refresh()
                                busy = false
                            }
                        },
                        onNewContainer = {
                            prefillImageForRun = ""
                            showRunDialog = true
                        },
                        onPruneContainers = { pruneConfirmType = "containers" },
                        onStartContainer = { c ->
                            scope.launch {
                                busy = true
                                val res = ContainerDockerManager.startContainer(context, current.name, c.id)
                                if (res.isSuccess) snackbarHostState.showSnackbar("Started ${c.cleanName}")
                                else snackbarHostState.showSnackbar("Error: ${res.error.joinToString(" ")}")
                                refresh()
                                busy = false
                            }
                        },
                        onStopContainer = { c ->
                            scope.launch {
                                busy = true
                                val res = ContainerDockerManager.stopContainer(context, current.name, c.id)
                                if (res.isSuccess) snackbarHostState.showSnackbar("Stopped ${c.cleanName}")
                                else snackbarHostState.showSnackbar("Error: ${res.error.joinToString(" ")}")
                                refresh()
                                busy = false
                            }
                        },
                        onRestartContainer = { c ->
                            scope.launch {
                                busy = true
                                val res = ContainerDockerManager.restartContainer(context, current.name, c.id)
                                if (res.isSuccess) snackbarHostState.showSnackbar("Restarted ${c.cleanName}")
                                else snackbarHostState.showSnackbar("Error: ${res.error.joinToString(" ")}")
                                refresh()
                                busy = false
                            }
                        },
                        onPauseToggleContainer = { c ->
                            scope.launch {
                                busy = true
                                val res = if (c.isPaused) {
                                    ContainerDockerManager.unpauseContainer(context, current.name, c.id)
                                } else {
                                    ContainerDockerManager.pauseContainer(context, current.name, c.id)
                                }
                                if (res.isSuccess) snackbarHostState.showSnackbar(if (c.isPaused) "Unpaused ${c.cleanName}" else "Paused ${c.cleanName}")
                                else snackbarHostState.showSnackbar("Error: ${res.error.joinToString(" ")}")
                                refresh()
                                busy = false
                            }
                        },
                        onKillContainer = { c ->
                            scope.launch {
                                busy = true
                                val res = ContainerDockerManager.killContainer(context, current.name, c.id)
                                if (res.isSuccess) snackbarHostState.showSnackbar("Killed ${c.cleanName}")
                                else snackbarHostState.showSnackbar("Error: ${res.error.joinToString(" ")}")
                                refresh()
                                busy = false
                            }
                        },
                        onRenameContainer = { c -> renameTarget = c },
                        onDeleteContainer = { c -> deleteTarget = c },
                        onInspectContainer = { c ->
                            scope.launch {
                                busy = true
                                val res = ContainerDockerManager.inspectContainer(context, current.name, c.id)
                                busy = false
                                if (res.isSuccess) {
                                    inspectData = c.cleanName to (res.getOrNull() ?: "{}")
                                } else {
                                    snackbarHostState.showSnackbar("Failed to inspect: ${res.exceptionOrNull()?.message}")
                                }
                            }
                        },
                        onViewLogs = { c -> onViewDockerLogs(c.id, c.cleanName) },
                        onUpdateContainer = { c ->
                            scope.launch {
                                busy = true
                                snackbarHostState.showSnackbar("Pulling latest image for ${c.cleanName}...")
                                val res = ContainerDockerManager.updateContainerImage(context, current.name, c.cleanName, recreate = true)
                                busy = false
                                when (res.status) {
                                    "up_to_date" -> snackbarHostState.showSnackbar("${c.cleanName} is already on latest image (${res.newImageId.ifBlank { "up to date" }}) ✓")
                                    "updated" -> snackbarHostState.showSnackbar("Updated ${c.cleanName} to latest image (${res.newImageId}) and restarted ✓")
                                    "update_available" -> snackbarHostState.showSnackbar("New image downloaded for ${c.cleanName}")
                                    else -> snackbarHostState.showSnackbar("Update result: ${res.message.take(120)}")
                                }
                                refresh()
                            }
                        },
                        onUpdateAllContainers = {
                            scope.launch {
                                busy = true
                                snackbarHostState.showSnackbar("Checking updates for all containers...")
                                val list = ContainerDockerManager.updateAllContainers(context, current.name, recreate = true)
                                busy = false
                                val updatedCount = list.count { it.status == "updated" }
                                val upToDateCount = list.count { it.status == "up_to_date" }
                                snackbarHostState.showSnackbar("Update complete: $updatedCount updated, $upToDateCount up to date")
                                refresh()
                            }
                        }
                    )
                }

                DockerTab.IMAGES -> {
                    DockerImagesTab(
                        images = dockerImages,
                        busy = busy,
                        onPullImage = { showPullDialog = true },
                        onPruneImages = { pruneConfirmType = "images" },
                        onRunImage = { img ->
                            prefillImageForRun = img.fullName
                            showRunDialog = true
                        },
                        onDeleteImage = { img -> deleteImageTarget = img }
                    )
                }

                DockerTab.DAEMON -> {
                    DockerDaemonTab(
                        container = current,
                        status = status,
                        readiness = readiness,
                        busy = busy,
                        onStart = {
                            scope.launch {
                                busy = true
                                val res = ContainerManager.dockerServiceControl(context, current, "start")
                                if (res.isSuccess) snackbarHostState.showSnackbar("Docker daemon started")
                                else snackbarHostState.showSnackbar("Failed to start: ${res.exceptionOrNull()?.message}")
                                refresh()
                                busy = false
                            }
                        },
                        onStop = {
                            scope.launch {
                                busy = true
                                val res = ContainerManager.dockerServiceControl(context, current, "stop")
                                if (res.isSuccess) snackbarHostState.showSnackbar("Docker daemon stopped")
                                else snackbarHostState.showSnackbar("Failed to stop: ${res.exceptionOrNull()?.message}")
                                refresh()
                                busy = false
                            }
                        },
                        onRestart = {
                            scope.launch {
                                busy = true
                                val res = ContainerManager.dockerServiceControl(context, current, "restart")
                                if (res.isSuccess) snackbarHostState.showSnackbar("Docker daemon restarted")
                                else snackbarHostState.showSnackbar("Failed to restart: ${res.exceptionOrNull()?.message}")
                                refresh()
                                busy = false
                            }
                        },
                        onInstall = {
                            scope.launch {
                                busy = true
                                val res = ContainerManager.installDocker(context, current)
                                if (res.isSuccess) snackbarHostState.showSnackbar("Docker installed and configured successfully!")
                                else snackbarHostState.showSnackbar("Install failed: ${res.exceptionOrNull()?.message}")
                                refresh()
                                busy = false
                            }
                        },
                        onToggleForceVfs = { forceVfs ->
                            scope.launch {
                                busy = true
                                val res = ContainerManager.setDockerStorageDriver(context, current, forceVfs)
                                res.onSuccess { updated ->
                                    container = updated
                                    snackbarHostState.showSnackbar(if (forceVfs) "Storage driver set to VFS" else "Storage driver set to OverlayFS")
                                    refresh()
                                }.onFailure { e ->
                                    snackbarHostState.showSnackbar("Failed to change storage driver: ${e.message}")
                                }
                                busy = false
                            }
                        },
                        onCopy = { text, label ->
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbarHostState.showSnackbar("$label copied to clipboard") }
                        },
                        onToggleTcpExpose = { expose ->
                            scope.launch {
                                busy = true
                                val res = ContainerManager.setDockerTcpExpose(context, current, expose)
                                res.onSuccess { updated ->
                                    container = updated
                                    snackbarHostState.showSnackbar(if (expose) "Docker TCP API exposed on port ${updated.dockerTcpPort}" else "Docker TCP API disabled")
                                    refresh()
                                }.onFailure { e ->
                                    snackbarHostState.showSnackbar("Failed to update TCP expose: ${e.message}")
                                }
                                busy = false
                            }
                        },
                        onPruneSystem = { pruneConfirmType = "system" }
                    )
                }
            }
        }
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────

    // Inspect Bottom Sheet / Dialog
    inspectData?.let { (name, json) ->
        AlertDialog(
            onDismissRequest = { inspectData = null },
            title = { Text("Inspect: $name", fontFamily = DockerMono) },
            text = {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    Box(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            text = json,
                            fontFamily = DockerMono,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(json))
                    scope.launch { snackbarHostState.showSnackbar("Inspection JSON copied") }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = { inspectData = null }) { Text("Close") }
            }
        )
    }

    // Run Container Dialog
    if (showRunDialog) {
        DockerRunContainerDialog(
            prefillImage = prefillImageForRun,
            onDismiss = { showRunDialog = false },
            onConfirm = { image, name, ports, volumes, envs, restartPolicy, command ->
                showRunDialog = false
                scope.launch {
                    busy = true
                    val res = ContainerDockerManager.runContainer(
                        context = context,
                        containerName = containerName,
                        image = image,
                        name = name,
                        portMappings = ports,
                        volumeMappings = volumes,
                        envVars = envs,
                        restartPolicy = restartPolicy,
                        command = command
                    )
                    busy = false
                    if (res.isSuccess) {
                        snackbarHostState.showSnackbar("Container created and started")
                        refresh()
                    } else {
                        snackbarHostState.showSnackbar("Error: ${res.error.joinToString(" ")}")
                    }
                }
            }
        )
    }

    // Pull Image Dialog
    if (showPullDialog) {
        var pullImageName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPullDialog = false },
            title = { Text("Pull Docker Image") },
            text = {
                OutlinedTextField(
                    value = pullImageName,
                    onValueChange = { pullImageName = it },
                    label = { Text("Image (e.g. nginx:alpine, redis:latest)") },
                    placeholder = { Text("image:tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val img = pullImageName.trim()
                        showPullDialog = false
                        if (img.isNotBlank()) {
                            scope.launch {
                                busy = true
                                snackbarHostState.showSnackbar("Pulling $img in background...")
                                val res = ContainerDockerManager.pullImage(context, containerName, img)
                                busy = false
                                if (res.isSuccess) {
                                    snackbarHostState.showSnackbar("Successfully pulled $img")
                                    refresh()
                                } else {
                                    snackbarHostState.showSnackbar("Pull failed: ${res.error.joinToString(" ")}")
                                }
                            }
                        }
                    },
                    enabled = pullImageName.isNotBlank()
                ) {
                    Text("Pull")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPullDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename Container Dialog
    renameTarget?.let { target ->
        var newName by remember { mutableStateOf(target.cleanName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Container") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New Container Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newName.trim()
                        renameTarget = null
                        if (name.isNotBlank()) {
                            scope.launch {
                                busy = true
                                val res = ContainerDockerManager.renameContainer(context, containerName, target.id, name)
                                busy = false
                                if (res.isSuccess) {
                                    snackbarHostState.showSnackbar("Renamed to $name")
                                    refresh()
                                } else {
                                    snackbarHostState.showSnackbar("Rename failed: ${res.error.joinToString(" ")}")
                                }
                            }
                        }
                    },
                    enabled = newName.isNotBlank() && newName != target.cleanName
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Delete Container Dialog
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove Container?") },
            text = { Text("Are you sure you want to remove '${target.cleanName}' (${target.shortId})? Any non-volume data inside it will be permanently lost.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            busy = true
                            val res = ContainerDockerManager.removeContainer(context, containerName, target.id)
                            busy = false
                            if (res.isSuccess) {
                                snackbarHostState.showSnackbar("Removed ${target.cleanName}")
                                refresh()
                            } else {
                                snackbarHostState.showSnackbar("Failed to remove: ${res.error.joinToString(" ")}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Delete Image Dialog
    deleteImageTarget?.let { img ->
        AlertDialog(
            onDismissRequest = { deleteImageTarget = null },
            title = { Text("Remove Image?") },
            text = { Text("Are you sure you want to remove image '${img.fullName}' (${img.shortId})?") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteImageTarget = null
                        scope.launch {
                            busy = true
                            val res = ContainerDockerManager.removeImage(context, containerName, img.id)
                            busy = false
                            if (res.isSuccess) {
                                snackbarHostState.showSnackbar("Removed ${img.fullName}")
                                refresh()
                            } else {
                                snackbarHostState.showSnackbar("Failed to remove: ${res.error.joinToString(" ")}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteImageTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Prune Confirmation Dialog
    pruneConfirmType?.let { type ->
        val title = when (type) {
            "containers" -> "Prune Stopped Containers?"
            "images" -> "Prune Unused Images?"
            else -> "Prune Docker System?"
        }
        val msg = when (type) {
            "containers" -> "This will remove all stopped Docker containers. Containers currently running will not be affected."
            "images" -> "This will remove all unused Docker images that are not associated with existing containers."
            else -> "This will remove all stopped containers, unused networks, and dangling images."
        }
        AlertDialog(
            onDismissRequest = { pruneConfirmType = null },
            title = { Text(title) },
            text = { Text(msg) },
            confirmButton = {
                Button(
                    onClick = {
                        pruneConfirmType = null
                        scope.launch {
                            busy = true
                            val res = when (type) {
                                "containers" -> ContainerDockerManager.pruneContainers(context, containerName)
                                "images" -> ContainerDockerManager.pruneImages(context, containerName)
                                else -> ContainerDockerManager.pruneSystem(context, containerName)
                            }
                            busy = false
                            if (res.isSuccess) {
                                snackbarHostState.showSnackbar("Prune completed successfully")
                                refresh()
                            } else {
                                snackbarHostState.showSnackbar("Prune failed: ${res.error.joinToString(" ")}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Prune")
                }
            },
            dismissButton = {
                TextButton(onClick = { pruneConfirmType = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Tab 1: Containers Management ────────────────────────────────────────────

@Composable
private fun DockerContainersTab(
    container: ContainerInfo,
    status: ContainerManager.DockerStatus?,
    containers: List<ContainerDockerManager.DockerContainerInfo>,
    readiness: ContainerManager.DockerReadiness?,
    busy: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedFilter: String,
    onFilterSelected: (String) -> Unit,
    onStartDaemon: () -> Unit,
    onInstallDocker: () -> Unit,
    onNewContainer: () -> Unit,
    onPruneContainers: () -> Unit,
    onStartContainer: (ContainerDockerManager.DockerContainerInfo) -> Unit,
    onStopContainer: (ContainerDockerManager.DockerContainerInfo) -> Unit,
    onRestartContainer: (ContainerDockerManager.DockerContainerInfo) -> Unit,
    onPauseToggleContainer: (ContainerDockerManager.DockerContainerInfo) -> Unit,
    onKillContainer: (ContainerDockerManager.DockerContainerInfo) -> Unit,
    onRenameContainer: (ContainerDockerManager.DockerContainerInfo) -> Unit,
    onDeleteContainer: (ContainerDockerManager.DockerContainerInfo) -> Unit,
    onInspectContainer: (ContainerDockerManager.DockerContainerInfo) -> Unit,
    onViewLogs: (ContainerDockerManager.DockerContainerInfo) -> Unit,
    onUpdateContainer: (ContainerDockerManager.DockerContainerInfo) -> Unit,
    onUpdateAllContainers: () -> Unit
) {
    if (!container.isRunning) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Container is Stopped", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Start ${container.name} first to manage Docker containers.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    if (status?.installed != true) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("Docker Engine Not Installed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Install Docker Engine inside ${container.name} to run and manage containers.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onInstallDocker,
                enabled = !busy && readiness?.verdict != "blocked",
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Install Docker Engine")
            }
        }
        return
    }

    if (status.running != true) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("Docker Daemon is Stopped", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Start the Docker daemon service to manage containers.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onStartDaemon,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start Docker Daemon")
            }
        }
        return
    }

    val runningCount = remember(containers) { containers.count { it.isRunning } }
    val stoppedCount = remember(containers) { containers.count { it.isStopped } }
    val pausedCount = remember(containers) { containers.count { it.isPaused } }

    val filteredContainers = remember(containers, searchQuery, selectedFilter) {
        containers.filter { c ->
            val matchesFilter = when (selectedFilter) {
                "RUNNING" -> c.isRunning
                "STOPPED" -> c.isStopped
                "PAUSED" -> c.isPaused
                else -> true
            }
            val matchesSearch = searchQuery.isBlank() ||
                c.cleanName.contains(searchQuery, ignoreCase = true) ||
                c.image.contains(searchQuery, ignoreCase = true) ||
                c.id.contains(searchQuery, ignoreCase = true) ||
                c.ports.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Quick Action Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onNewContainer,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }

                OutlinedButton(
                    onClick = onUpdateAllContainers,
                    enabled = !busy && containers.isNotEmpty(),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Icon(Icons.Default.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Update All")
                }

                OutlinedButton(
                    onClick = onPruneContainers,
                    enabled = !busy && stoppedCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Prune")
                }
            }
        }

        // Search Bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search containers by name, image, ID, port...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
        }

        // Filter Chips (Systemd-style)
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { onFilterSelected("ALL") },
                        label = { Text("ALL (${containers.size})") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "RUNNING",
                        onClick = { onFilterSelected("RUNNING") },
                        leadingIcon = { Box(Modifier.size(8.dp).background(Color(0xFF4CAF50), CircleShape)) },
                        label = { Text("RUNNING ($runningCount)") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "STOPPED",
                        onClick = { onFilterSelected("STOPPED") },
                        leadingIcon = { Box(Modifier.size(8.dp).background(Color(0xFFEF5350), CircleShape)) },
                        label = { Text("STOPPED ($stoppedCount)") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "PAUSED",
                        onClick = { onFilterSelected("PAUSED") },
                        leadingIcon = { Box(Modifier.size(8.dp).background(Color(0xFFFFCA28), CircleShape)) },
                        label = { Text("PAUSED ($pausedCount)") }
                    )
                }
            }
        }

        // Container List Items
        if (filteredContainers.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.SearchOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No matching containers found", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(filteredContainers, key = { it.id }) { c ->
                DockerContainerCard(
                    container = c,
                    busy = busy,
                    onStart = { onStartContainer(c) },
                    onStop = { onStopContainer(c) },
                    onRestart = { onRestartContainer(c) },
                    onPauseToggle = { onPauseToggleContainer(c) },
                    onKill = { onKillContainer(c) },
                    onRename = { onRenameContainer(c) },
                    onDelete = { onDeleteContainer(c) },
                    onInspect = { onInspectContainer(c) },
                    onViewLogs = { onViewLogs(c) },
                    onUpdateImage = { onUpdateContainer(c) }
                )
            }
        }
    }
}

@Composable
private fun DockerContainerCard(
    container: ContainerDockerManager.DockerContainerInfo,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onPauseToggle: () -> Unit,
    onKill: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onInspect: () -> Unit,
    onViewLogs: () -> Unit,
    onUpdateImage: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val statusColor = when {
        container.isRunning -> Color(0xFF4CAF50)
        container.isPaused -> Color(0xFFFFCA28)
        container.isRestarting -> Color(0xFFAB47BC)
        else -> Color(0xFFEF5350)
    }

    val statusLabel = when {
        container.isRunning -> "RUNNING"
        container.isPaused -> "PAUSED"
        container.isRestarting -> "RESTARTING"
        else -> "STOPPED"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Top Row: Status Dot, Name, Status Badge, Overflow Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(10.dp).background(statusColor, CircleShape))
                    Text(
                        text = container.cleanName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("View Live Logs") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onViewLogs()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Pull & Update Image") },
                                leadingIcon = { Icon(Icons.Default.SystemUpdateAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showMenu = false
                                    onUpdateImage()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Inspect Details") },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onInspect()
                                }
                            )
                            if (container.isRunning) {
                                DropdownMenuItem(
                                    text = { Text("Restart") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        onRestart()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (container.isPaused) "Unpause" else "Pause") },
                                    leadingIcon = { Icon(if (container.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        onPauseToggle()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Force Kill") },
                                    leadingIcon = { Icon(Icons.Default.Block, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        onKill()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onRename()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Remove Container", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            // Image & ID
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Image: ${container.image}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = container.shortId,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = DockerMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Status message
            if (container.status.isNotBlank()) {
                Text(
                    text = container.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Ports mapping if present
            if (container.ports.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = container.ports,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = DockerMono),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Bottom Action Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onUpdateImage,
                    enabled = !busy,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.SystemUpdateAlt, contentDescription = "Update Image", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.width(4.dp))

                TextButton(
                    onClick = onViewLogs,
                    enabled = !busy
                ) {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Logs")
                }

                Spacer(Modifier.width(8.dp))

                if (container.isRunning) {
                    FilledTonalButton(
                        onClick = onStop,
                        enabled = !busy,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = !busy
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Start")
                    }
                }
            }
        }
    }
}

// ── Tab 2: Images Management ────────────────────────────────────────────────

@Composable
private fun DockerImagesTab(
    images: List<ContainerDockerManager.DockerImageInfo>,
    busy: Boolean,
    onPullImage: () -> Unit,
    onPruneImages: () -> Unit,
    onRunImage: (ContainerDockerManager.DockerImageInfo) -> Unit,
    onDeleteImage: (ContainerDockerManager.DockerImageInfo) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Quick Action Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPullImage,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pull Image")
                }

                OutlinedButton(
                    onClick = onPruneImages,
                    enabled = !busy && images.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Prune Unused")
                }
            }
        }

        if (images.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No Docker images downloaded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(images, key = { it.id }) { img ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = img.fullName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(img.shortId, style = MaterialTheme.typography.labelSmall.copy(fontFamily = DockerMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(img.size, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { onRunImage(img) }, enabled = !busy) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Run container from image", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { onDeleteImage(img) }, enabled = !busy) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete image", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tab 3: Daemon & Host Settings ───────────────────────────────────────────

@Composable
private fun DockerDaemonTab(
    container: ContainerInfo,
    status: ContainerManager.DockerStatus?,
    readiness: ContainerManager.DockerReadiness?,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onInstall: () -> Unit,
    onToggleForceVfs: (Boolean) -> Unit,
    onCopy: (String, String) -> Unit,
    onToggleTcpExpose: (Boolean) -> Unit,
    onPruneSystem: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            DockerStatusCard(
                status = status,
                containerRunning = container.isRunning,
                readiness = readiness,
                busy = busy,
                onStart = onStart,
                onStop = onStop,
                onRestart = onRestart,
                onInstall = onInstall
            )
        }

        item {
            DockerKernelReadinessCard(readiness = readiness)
        }

        item {
            DockerStorageDriverCard(
                container = container,
                status = status,
                busy = busy,
                onToggleForceVfs = onToggleForceVfs
            )
        }

        item {
            DockerEndpointsCard(
                container = container,
                busy = busy,
                onCopy = onCopy,
                onToggleTcpExpose = onToggleTcpExpose
            )
        }

        if (status?.running == true) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Text("System Maintenance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Clean up all stopped containers, unused networks, and dangling images across the Docker daemon.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = onPruneSystem,
                            enabled = !busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Prune Entire Docker System")
                        }
                    }
                }
            }
        }

        item {
            DockerInfoCard(tcpPort = container.dockerTcpPort)
        }
    }
}

// ── Run Container Dialog Composable ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DockerRunContainerDialog(
    prefillImage: String,
    onDismiss: () -> Unit,
    onConfirm: (image: String, name: String?, ports: List<String>, volumes: List<String>, envs: List<String>, restartPolicy: String, command: String?) -> Unit
) {
    var image by remember { mutableStateOf(prefillImage) }
    var name by remember { mutableStateOf("") }
    var portMappings by remember { mutableStateOf("") }
    var volumeMappings by remember { mutableStateOf("") }
    var envVars by remember { mutableStateOf("") }
    var restartPolicy by remember { mutableStateOf("no") }
    var command by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run New Container") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = image,
                    onValueChange = { image = it },
                    label = { Text("Image *") },
                    placeholder = { Text("nginx:alpine, redis:latest") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Container Name (Optional)") },
                    placeholder = { Text("my-web-app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = portMappings,
                    onValueChange = { portMappings = it },
                    label = { Text("Port Mappings (comma-separated)") },
                    placeholder = { Text("8080:80, 443:443") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = volumeMappings,
                    onValueChange = { volumeMappings = it },
                    label = { Text("Volume Binds (comma-separated)") },
                    placeholder = { Text("/host/path:/container/path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = envVars,
                    onValueChange = { envVars = it },
                    label = { Text("Environment Vars (comma-separated)") },
                    placeholder = { Text("PORT=80, NODE_ENV=production") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Custom Command (Optional)") },
                    placeholder = { Text("sh -c 'npm start'") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Restart Policy", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("no", "always", "unless-stopped").forEach { policy ->
                        FilterChip(
                            selected = restartPolicy == policy,
                            onClick = { restartPolicy = policy },
                            label = { Text(policy) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val img = image.trim()
                    val cName = name.trim().takeIf { it.isNotBlank() }
                    val ports = portMappings.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val volumes = volumeMappings.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val envs = envVars.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val cmd = command.trim().takeIf { it.isNotBlank() }
                    onConfirm(img, cName, ports, volumes, envs, restartPolicy, cmd)
                },
                enabled = image.isNotBlank()
            ) {
                Text("Run Container")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Shared Subcomponents ────────────────────────────────────────────────────

@Composable
private fun DockerStatusCard(
    status: ContainerManager.DockerStatus?,
    containerRunning: Boolean,
    readiness: ContainerManager.DockerReadiness?,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onInstall: () -> Unit
) {
    val isRunning = status?.running == true
    val isInstalled = status?.installed == true
    val (label, color) = when {
        !containerRunning -> "Container stopped" to Color(0xFFFFA726)
        status == null -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        !isInstalled -> "Not installed" to MaterialTheme.colorScheme.onSurfaceVariant
        isRunning -> "Running (${status.storageDriver ?: "active"})" to Color(0xFF4CAF50)
        else -> "Stopped" to Color(0xFFEF5350)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Docker Daemon Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            if (!containerRunning) {
                Text(
                    "Start the container to run the Docker Engine daemon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!isInstalled) {
                Button(
                    onClick = onInstall,
                    enabled = !busy && readiness?.verdict != "blocked",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Install Docker Engine")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isRunning) {
                        OutlinedButton(
                            onClick = onStop,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    } else {
                        Button(
                            onClick = onStart,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start")
                        }
                    }

                    OutlinedButton(
                        onClick = onRestart,
                        enabled = !busy && isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Restart")
                    }
                }
            }
        }
    }
}

@Composable
private fun DockerKernelReadinessCard(readiness: ContainerManager.DockerReadiness?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Kernel Readiness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (readiness != null) {
                val verdictText = when (readiness.verdict) {
                    "good" -> "Kernel is fully ready for Docker (overlay2, cgroup v2, user namespaces)"
                    "blocked" -> "User namespaces aren't supported by this kernel. Docker cannot start."
                    else -> "Docker will run with degraded features or slower fallback storage driver."
                }
                val verdictColor = when (readiness.verdict) {
                    "good" -> Color(0xFF4CAF50)
                    "blocked" -> MaterialTheme.colorScheme.error
                    else -> Color(0xFFFFA726)
                }

                Text(verdictText, style = MaterialTheme.typography.bodySmall, color = verdictColor, fontWeight = FontWeight.Medium)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "User Namespaces (CONFIG_USER_NS)" to readiness.userNs,
                        "OverlayFS (Kernel module / builtin)" to readiness.overlayFs,
                        "Cgroup v2 Support" to readiness.cgroupV2,
                        "Cgroup Namespaces" to readiness.cgroupNs
                    ).forEach { (feature, isSupported) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(feature, style = MaterialTheme.typography.bodySmall)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSupported) Color(0xFF4CAF50).copy(alpha = 0.12f) else MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    if (isSupported) "Supported" else "Missing",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSupported) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Text("Evaluating kernel features...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DockerStorageDriverCard(
    container: ContainerInfo,
    status: ContainerManager.DockerStatus?,
    busy: Boolean,
    onToggleForceVfs: (Boolean) -> Unit
) {
    val currentDriver = status?.storageDriver ?: if (container.dockerForceVfs) "vfs" else "overlay2"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("Storage Driver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        currentDriver.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Force VFS Storage Driver", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "Use VFS driver even if OverlayFS is available. Slower and uses more disk -- for kernel debugging only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = container.dockerForceVfs,
                    onCheckedChange = onToggleForceVfs,
                    enabled = !busy
                )
            }
        }
    }
}

@Composable
private fun DockerEndpointsCard(
    container: ContainerInfo,
    busy: Boolean,
    onCopy: (String, String) -> Unit,
    onToggleTcpExpose: (Boolean) -> Unit
) {
    val port = container.dockerTcpPort
    val tcpHost = "tcp://127.0.0.1:$port"
    val exportCmd = "export DOCKER_HOST=$tcpHost"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Endpoints & Connectivity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            // Local Unix Socket
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Local Unix Socket", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "/var/run/docker.sock",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(onClick = { onCopy("/var/run/docker.sock", "Docker socket") }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy socket", modifier = Modifier.size(18.dp))
                    }
                }
            }

            // TCP API Expose Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Expose Docker API over TCP", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Forward host port $port to dockerd TCP API", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = container.dockerExposeTcp,
                    onCheckedChange = onToggleTcpExpose,
                    enabled = !busy
                )
            }

            if (container.dockerExposeTcp) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("DOCKER_HOST Endpoint", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                exportCmd,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { onCopy(exportCmd, "DOCKER_HOST command") }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy command", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                ) {
                    Text(
                        "⚠️ Docker's TCP API has no built-in authentication. Anyone on your local network can execute root commands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DockerInfoCard(tcpPort: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("How this works", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                "• In-Container Docker CLI:\n" +
                "  Run containers natively via terminal using: docker run -d -p 8080:80 nginx\n" +
                "• Remote Docker CLI / VS Code Dev Containers:\n" +
                "  When TCP API is enabled, point your computer's Docker client to: export DOCKER_HOST=tcp://<phone-ip>:" + tcpPort + "\n" +
                "• Storage Driver:\n" +
                "  OverlayFS is used when available. If your kernel lacks OverlayFS, VFS driver provides guaranteed compatibility.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
