package com.droidspaces.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.R
import com.droidspaces.app.ui.component.EnvironmentVariablesDialog
import com.droidspaces.app.ui.component.SettingsRowCard
import com.droidspaces.app.ui.component.TerminalDialog
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.util.Constants
import com.droidspaces.app.util.ContainerCommandBuilder
import com.droidspaces.app.util.ContainerEnvironmentManager
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.PreferencesManager
import com.droidspaces.app.util.ValidationUtils
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdrenoTurnipScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    opsViewModel: ContainerOperationsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var hasTurnipDriver by remember { mutableStateOf(false) }
    var isConfigured by remember { mutableStateOf(false) }
    var vulkanDriverPath by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var showEnvDialog by remember { mutableStateOf(false) }
    var gpuEnvContent by remember { mutableStateOf("") }

    suspend fun checkStatus() {
        container = ContainerManager.getContainerInfo(containerName)
        val cur = container
        gpuEnvContent = ContainerEnvironmentManager.loadTurnipEnv(containerName)

        isConfigured = cur != null && cur.enableGpuMode && !cur.enableVirgl &&
            (gpuEnvContent.contains("MESA_LOADER_DRIVER_OVERRIDE=kgsl")) &&
            (gpuEnvContent.contains("TU_DEBUG=noconform"))

        val checkVulkanCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run 'ls /usr/lib/*/libvulkan_freedreno.so /usr/lib/libvulkan_freedreno.so /usr/local/lib/libvulkan_freedreno.so /usr/lib64/libvulkan_freedreno.so 2>/dev/null | head -n1'"
        val checkOut = withContext(Dispatchers.IO) {
            Shell.cmd(checkVulkanCmd).exec().out.firstOrNull()?.trim()
        }
        vulkanDriverPath = if (checkOut.isNullOrBlank()) null else checkOut
        hasTurnipDriver = vulkanDriverPath != null
    }

    LaunchedEffect(containerName) {
        checkStatus()
        loading = false
    }

    if (showEnvDialog) {
        EnvironmentVariablesDialog(
            initialContent = gpuEnvContent,
            onConfirm = { newContent ->
                scope.launch {
                    busy = true
                    ContainerEnvironmentManager.saveTurnipEnv(context, containerName, newContent)
                    checkStatus()
                    snackbarHostState.showSnackbar("Turnip GPU environment variables (.turnip.env) updated successfully ✓")
                    busy = false
                }
                showEnvDialog = false
            },
            onDismiss = { showEnvDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Adreno Turnip GPU",
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
                            loading = true
                            checkStatus()
                            loading = false
                            snackbarHostState.showSnackbar("Driver status refreshed")
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
            // 1. Status Header Card
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
                                    .background(
                                        if (isConfigured) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "Qualcomm Adreno Acceleration",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isConfigured) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline
                                            )
                                    )
                                    Text(
                                        text = if (isConfigured) "Configured" else "Off",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }
                                Text(
                                    text = if (isConfigured) "Near-native KGSL GPU acceleration active (VirGL bypassed)"
                                    else "Hardware acceleration bypassing VirGL is not configured",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Detailed Status rows
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TurnipStatusRow(
                                label = "Vulkan Driver",
                                value = if (hasTurnipDriver) "libvulkan_freedreno.so installed ✓" else "Not Installed",
                                isGood = hasTurnipDriver
                            )
                            TurnipStatusRow(
                                label = "Driver Path",
                                value = vulkanDriverPath ?: "None detected",
                                isMonospace = true,
                                isGood = vulkanDriverPath != null
                            )
                            TurnipStatusRow(
                                label = "Kernel Bridge",
                                value = if (isConfigured) "Direct KGSL (/dev/kgsl-3d0)" else "Default / Host VirGL",
                                isGood = isConfigured
                            )
                        }
                    }
                }
            }

            // 2. Primary Actions Card
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
                        Text(
                            text = "Actions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Auto-Configure Environment Button
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val cur = container
                                    if (cur != null) {
                                        var env = ContainerEnvironmentManager.loadTurnipEnv(containerName)
                                        if (!env.contains("MESA_LOADER_DRIVER_OVERRIDE=")) {
                                            env = if (env.isBlank()) "MESA_LOADER_DRIVER_OVERRIDE=kgsl" else "$env\nMESA_LOADER_DRIVER_OVERRIDE=kgsl"
                                        }
                                        if (!env.contains("TU_DEBUG=")) {
                                            env = if (env.isBlank()) "TU_DEBUG=noconform" else "$env\nTU_DEBUG=noconform"
                                        }
                                        if (!env.contains("LIBGL_DRI3_ENABLE=")) {
                                            env = if (env.isBlank()) "LIBGL_DRI3_ENABLE=1" else "$env\nLIBGL_DRI3_ENABLE=1"
                                        }
                                        ContainerEnvironmentManager.saveTurnipEnv(context, containerName, env)
                                        val updated = cur.copy(
                                            enableGpuMode = true,
                                            enableVirgl = false
                                        )
                                        val res = ContainerManager.saveContainerConfig(context, updated)
                                        if (res.isSuccess) {
                                            container = updated
                                            checkStatus()
                                            snackbarHostState.showSnackbar("Adreno Turnip environment configured ✓")
                                        } else {
                                            snackbarHostState.showSnackbar("Failed to update container configuration")
                                        }
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isConfigured) "Re-Configure GPU Environment" else "Auto-Configure GPU Environment", fontWeight = FontWeight.SemiBold)
                        }

                        // Auto-Install Turnip Drivers Button
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    container?.let {
                                        opsViewModel.executeInstallTurnipDrivers(
                                            container = it,
                                            onRefresh = { scope.launch { checkStatus() } },
                                            onError = { err: String -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                            onSuccess = { msg: String -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                        )
                                    }
                                }
                            },
                            enabled = !busy,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Auto-Install Turnip Driver Packages", fontWeight = FontWeight.SemiBold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Verify Vulkan Button
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val testCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run 'vulkaninfo --summary 2>&1 || ls -la /usr/lib/*/libvulkan_freedreno.so /usr/lib/libvulkan_freedreno.so 2>&1'"
                                        val out = withContext(Dispatchers.IO) { Shell.cmd(testCmd).exec().out }
                                        busy = false
                                        val summary = out.firstOrNull { it.contains("GPU") || it.contains("driver") || it.contains("libvulkan") } ?: "Vulkan driver check completed"
                                        snackbarHostState.showSnackbar(summary)
                                    }
                                },
                                enabled = !busy,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Verify Driver", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }

                            // Revert to VirGL Button
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val cur = container
                                        if (cur != null) {
                                            val updated = cur.copy(
                                                enableGpuMode = false,
                                                enableVirgl = true
                                            )
                                            ContainerManager.saveContainerConfig(context, updated)
                                            container = updated
                                            checkStatus()
                                            snackbarHostState.showSnackbar("Reverted to VirGL default")
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Revert to VirGL", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // 3. Environment Variables & Information Card
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
                        Text(
                            text = "Mesa & GPU Environment",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val envCount = ValidationUtils.countEnvVars(gpuEnvContent)
                        val envSubtitle = if (envCount > 0) {
                            context.getString(R.string.environment_variables_configured, envCount)
                        } else {
                            context.getString(R.string.not_configured)
                        }

                        SettingsRowCard(
                            title = "GPU & Driver Environment",
                            subtitle = envSubtitle,
                            description = "Mesa overrides, Vulkan debug flags, and driver settings (.turnip.env)",
                            icon = Icons.Default.Code,
                            onClick = { showEnvDialog = true }
                        )

                        Text(
                            text = "Turnip uses the host kernel's KGSL subsystem to provide hardware Vulkan and OpenGL acceleration on Qualcomm Snapdragon Adreno 6xx / 7xx / 8xx GPUs without intermediate translation layers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Live terminal console for container operations (such as Install Turnip)
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
                        scope.launch { checkStatus() }
                    },
                    onClear = {
                        opsViewModel.clearLogsBuffer(targetContainer)
                    },
                    isBlocking = isBlocking
                )
            }
        }
    }
}

@Composable
private fun TurnipStatusRow(
    label: String,
    value: String,
    isGood: Boolean,
    isMonospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (isMonospace) MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isGood) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
