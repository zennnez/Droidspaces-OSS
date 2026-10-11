package com.droidspaces.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import com.droidspaces.app.R
import com.droidspaces.app.service.TerminalSessionService
import com.droidspaces.app.util.ContainerCommandBuilder
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.ContainerOSInfoManager
import com.droidspaces.app.util.ContainerUsersManager
import com.droidspaces.app.util.ContainerOperationExecutor
import com.droidspaces.app.util.FilePickerUtils
import com.droidspaces.app.util.PreferencesManager
import com.droidspaces.app.util.SystemInfoManager
import com.droidspaces.app.util.ViewModelLogger
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

import com.droidspaces.app.util.ContainerDiskUsageManager
import com.droidspaces.app.util.ContainerInstaller
import com.droidspaces.app.util.ContainerSnapshot
import com.droidspaces.app.util.ContainerSnapshotManager
import com.droidspaces.app.util.MoveStorageOperations
import com.droidspaces.app.util.MoveStoragePhase
import com.droidspaces.app.util.MoveVerificationMethod

/** Progress state for the stop/uninstall flow (drives the ProgressDialog). */
sealed class UninstallState {
    data object Idle : UninstallState()
    data class InProgress(val containerName: String, val message: String) : UninstallState()
}

/** A pending sparse-image operation awaiting user parameters. */
sealed class SparseOperation {
    data class Migrate(val container: ContainerInfo, val targetImagePath: String? = null) : SparseOperation()
    data class Resize(val container: ContainerInfo) : SparseOperation()
    data class ConvertToDir(val container: ContainerInfo, val targetDirPath: String? = null) : SparseOperation()
}

/**
 * Owns the container lifecycle/maintenance operations (start/stop/restart,
 * export, uninstall, sparse migrate/resize) that used to live as suspend
 * functions inside `ContainersScreen`. Root shell, asset deploy, command
 * building and content-URI streaming now live here; the composable observes the
 * state below and passes UI concerns (snackbar, list refresh, usage-cache clear)
 * back as callbacks so behavior is identical.
 */
class ContainerOperationsViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext get() = getApplication<Application>()
    private val prefsManager get() = PreferencesManager.getInstance(appContext)

    /** Container whose operation is currently running (shows the terminal icon / blocks the log dialog). */
    var runningOperationContainer by mutableStateOf<String?>(null)
        private set

    /** Per-container streaming log buffers. */
    var containerLogs by mutableStateOf<Map<String, SnapshotStateList<Pair<Int, String>>>>(emptyMap())
        private set

    /** Container whose log viewer dialog is open (set from UI and from operations). */
    var showLogViewerFor by mutableStateOf<String?>(null)

    /** Last container whose operation failed (kept for parity with prior behavior). */
    var lastErrorContainer by mutableStateOf<String?>(null)
        private set

    /** Stop/uninstall progress. */
    var uninstallState by mutableStateOf<UninstallState>(UninstallState.Idle)
        private set

    /** Non-null when an uninstall failed and its logs should be shown (dismissed from UI). */
    var uninstallLogsDialog by mutableStateOf<List<String>?>(null)

    fun dismissUninstallLogs() { uninstallLogsDialog = null }

    /** Get-or-create the streaming log buffer for [name] (matches the previous inline pattern). */
    fun logsFor(name: String): SnapshotStateList<Pair<Int, String>> {
        containerLogs[name]?.let { return it }
        val newLogs = mutableStateListOf<Pair<Int, String>>()
        containerLogs = containerLogs.toMutableMap().apply { put(name, newLogs) }
        return newLogs
    }

    /** Clear the in-memory + cached logs for [name] (log dialog "clear" action). */
    fun clearLogsBuffer(name: String) {
        val buffer = containerLogs[name] ?: mutableStateListOf<Pair<Int, String>>().also {
            containerLogs = containerLogs.toMutableMap().apply { put(name, it) }
        }
        buffer.clear()
        prefsManager.clearContainerLogs(name)
        containerLogs = containerLogs.toMutableMap()
    }

    private fun string(resId: Int, vararg args: Any): String =
        if (args.isEmpty()) appContext.getString(resId) else appContext.getString(resId, *args)

    // ── Export ────────────────────────────────────────────────────────────────
    suspend fun executeExport(
        container: ContainerInfo,
        outputUri: Uri,
        includeEnv: Boolean,
        onError: (String) -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)

        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }

        var scriptFile: File? = null
        var tempArchive: File? = null
        try {
            val isRunning = ContainerManager.checkContainerStatus(container.name).first
            if (isRunning) {
                uninstallState = UninstallState.InProgress(container.name, string(R.string.stopping_container))
                val stopCommand = ContainerCommandBuilder.buildStopCommand(container)
                val stopResult = ContainerOperationExecutor.executeCommand(stopCommand, "stop", logger)
                uninstallState = UninstallState.Idle
                if (!stopResult) {
                    logger.e(string(R.string.failed_to_stop_container, container.name))
                    onError(string(R.string.failed_to_stop_container, container.name))
                    return
                }
            }

            logs.clear()
            showLogViewerFor = container.name
            logger.i(string(R.string.starting_export))

            val deployed = File("${appContext.cacheDir}/export_container.sh")
            scriptFile = deployed
            appContext.assets.open("export_container.sh").use { input ->
                deployed.outputStream().use { out: OutputStream -> input.copyTo(out) }
            }
            Shell.cmd("chmod 755 ${ContainerCommandBuilder.quote(deployed.absolutePath)}").exec()

            // Write straight into the picked document when its provider exposes a file.
            // Only a pipe-backed one (cloud storage) still goes through cacheDir.
            val output = FilePickerUtils.realPath(appContext, outputUri, "w")
                ?: File("${appContext.cacheDir}/${container.name}_export_tmp.tar.gz")
                    .also { it.delete(); tempArchive = it }.absolutePath

            val cmd = "${ContainerCommandBuilder.quote(deployed.absolutePath)} " +
                "${ContainerCommandBuilder.quote(container.name)} " +
                ContainerCommandBuilder.quote(output) +
                if (includeEnv) " --with-env" else ""
            val success = ContainerOperationExecutor.executeCommand(
                command = cmd,
                operation = "export",
                logger = logger,
                skipHeader = true,
                operationCompletedMessage = string(R.string.operation_completed_success)
            )

            val staged = tempArchive
            if (!success) {
                logger.e(string(R.string.export_container_failed, container.name))
                onError(string(R.string.export_container_failed, container.name))
            } else if (staged != null && staged.length() > 0) {
                logger.i("Writing archive to destination...")
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(outputUri)?.use { out ->
                        staged.inputStream().use { it.copyTo(out) }
                    }
                }
                logger.i("Done! Archive written successfully.")
            }
        } catch (e: Exception) {
            logger.e("Export error: ${e.message}")
            logger.e(e.stackTraceToString())
            onError(string(R.string.export_container_failed, container.name))
        } finally {
            scriptFile?.delete()
            tempArchive?.delete()
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    // ── Uninstall ───────────────────────────────────────────────────────────────
    suspend fun executeUninstall(
        container: ContainerInfo,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onRefresh: () -> Unit,
    ) {
        val collectedLogs = mutableListOf<String>()
        val logger = ViewModelLogger { _, message -> collectedLogs.add(message) }.apply { verbose = true }

        try {
            val isRunning = ContainerManager.checkContainerStatus(container.name).first
            if (isRunning) {
                uninstallState = UninstallState.InProgress(container.name, string(R.string.stopping_container))
                val stopCommand = ContainerCommandBuilder.buildStopCommand(container)
                val stopResult = ContainerOperationExecutor.executeCommand(stopCommand, "stop", logger)
                if (!stopResult) {
                    uninstallState = UninstallState.Idle
                    if (collectedLogs.isNotEmpty()) uninstallLogsDialog = collectedLogs
                    else onError(string(R.string.failed_to_stop_container, container.name))
                    onRefresh()
                    return
                }
            }

            uninstallState = UninstallState.InProgress(container.name, string(R.string.uninstalling_container))
            val result = ContainerManager.uninstallContainer(container, logger)
            uninstallState = UninstallState.Idle

            if (result.isFailure) {
                if (collectedLogs.isNotEmpty()) uninstallLogsDialog = collectedLogs
                else onError(string(R.string.failed_to_uninstall_container, container.name))
                onRefresh()
            } else {
                ContainerOSInfoManager.clearCache(container.name, appContext)
                ContainerUsersManager.clearCache(container.name)
                onSuccess(string(R.string.container_uninstalled_success, container.name))
                onRefresh()
            }
        } catch (e: Exception) {
            uninstallState = UninstallState.Idle
            collectedLogs.add("Exception: ${e.message}")
            collectedLogs.add(e.stackTraceToString())
            if (collectedLogs.isNotEmpty()) uninstallLogsDialog = collectedLogs
            else onError(string(R.string.failed_to_uninstall_container, container.name))
            onRefresh()
        }
    }

    // ── Move Storage ──────────────────────────────────────────────────────────
    suspend fun executeMoveStorage(
        container: ContainerInfo,
        newStorageDir: String?,
        verificationMethod: MoveVerificationMethod = MoveVerificationMethod.SHA256,
        skipFreeSpaceCheck: Boolean = false,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onRefresh: () -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        showLogViewerFor = container.name

        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }

        try {
            logger.i("Initiating storage relocation for ${container.name}...")
            val isRunning = ContainerManager.checkContainerStatus(container.name).first
            if (isRunning) {
                logger.i(string(R.string.stopping_container))
                val stopCommand = ContainerCommandBuilder.buildStopCommand(container)
                val stopResult = ContainerOperationExecutor.executeCommand(stopCommand, "stop", logger)
                if (!stopResult) {
                    logger.e(string(R.string.failed_to_stop_container, container.name))
                    onError(string(R.string.failed_to_stop_container, container.name))
                    onRefresh()
                    return
                }
            }

            logger.i("Starting storage move to ${newStorageDir ?: "internal storage"}...")
            val result = ContainerManager.moveContainerStorage(
                appContext, container, newStorageDir, logger,
                verificationMethod = verificationMethod,
                skipFreeSpaceCheck = skipFreeSpaceCheck,
                onProgress = { phase, percent, _, _, detail ->
                    val phaseLabel = when (phase) {
                        MoveStoragePhase.COPYING -> "[COPY]"
                        MoveStoragePhase.VERIFYING -> "[VERIFY]"
                        MoveStoragePhase.DELETING -> "[CLEANUP]"
                    }
                    val msg = if (percent != null) "$phaseLabel $detail ($percent%)" else "$phaseLabel $detail"
                    logger.logImmediate(android.util.Log.INFO, msg)
                }
            )

            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "Failed to move ${container.name}'s storage"
                logger.e(msg)
                onError(msg)
            } else {
                val dest = result.getOrNull()
                logger.i("Successfully moved ${container.name} to $dest")
                onSuccess("Moved ${container.name} to $dest")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Exception during move: ${e.message}")
            logger.e(e.stackTraceToString())
            onError("Failed to move ${container.name}'s storage: ${e.message}")
            onRefresh()
        } finally {
            runningOperationContainer = null
        }
    }

    // ── Start / Stop / Restart ────────────────────────────────────────────────
    suspend fun executeOperation(
        container: ContainerInfo,
        operation: String,
        onRefresh: () -> Unit,
        onClearUsage: (String) -> Unit,
        onFailureSnackbar: (String) -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        showLogViewerFor = container.name

        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }

        try {
            val effectiveContainer = if (operation == "start" || operation == "restart") {
                ContainerManager.autoDetectAndRemapContainerPath(appContext, container, logger)
            } else {
                container
            }

            if (operation == "stop" || operation == "restart") {
                appContext.startService(
                    Intent(appContext, TerminalSessionService::class.java).apply {
                        action = TerminalSessionService.ACTION_STOP_CONTAINER_SESSIONS
                        putExtra(TerminalSessionService.EXTRA_CONTAINER_NAME, effectiveContainer.name)
                    }
                )
                onClearUsage(effectiveContainer.name)
            }

            val command = when (operation) {
                "start" -> ContainerCommandBuilder.buildStartCommand(effectiveContainer)
                "stop" -> ContainerCommandBuilder.buildStopCommand(effectiveContainer)
                "restart" -> ContainerCommandBuilder.buildRestartCommand(effectiveContainer)
                else -> {
                    runningOperationContainer = null
                    return
                }
            }

            val success = ContainerOperationExecutor.executeCommand(
                command = command,
                operation = operation,
                logger = logger,
                operationCompletedMessage = string(R.string.operation_completed_success)
            )

            if (!success) {
                lastErrorContainer = container.name
                logger.e("")
                logger.e(string(R.string.operation_failed))
                onFailureSnackbar(string(R.string.failure_in_operation, operation, container.name))
                onRefresh()
                SystemInfoManager.refreshSELinuxStatus()
            } else {
                lastErrorContainer = null
                onRefresh()
                SystemInfoManager.refreshSELinuxStatus()
            }
        } catch (e: Exception) {
            logger.e("Error: ${e.message}")
            logger.e(e.stackTraceToString())
            lastErrorContainer = container.name
            onFailureSnackbar(string(R.string.failure_in_operation, operation, container.name))
            onRefresh()
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    // ── Sparse migrate / resize ─────────────────────────────────────────────────
    suspend fun executeSparseOperation(
        operation: SparseOperation,
        sizeGb: Int = 8,
        onRefresh: () -> Unit,
    ) {
        val container = when (operation) {
            is SparseOperation.Migrate -> operation.container
            is SparseOperation.Resize -> operation.container
            is SparseOperation.ConvertToDir -> operation.container
        }

        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)

        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }

        var scriptFile: File? = null
        try {
            val isRunning = ContainerManager.checkContainerStatus(container.name).first
            if (isRunning) {
                uninstallState = UninstallState.InProgress(container.name, string(R.string.stopping_container))
                val stopCommand = ContainerCommandBuilder.buildStopCommand(container)
                val stopResult = ContainerOperationExecutor.executeCommand(stopCommand, "stop", logger)
                uninstallState = UninstallState.Idle
                if (!stopResult) {
                    logger.e(string(R.string.failed_to_stop_container, container.name))
                    return
                }
                delay(1000)
            }

            logs.clear()
            showLogViewerFor = container.name

            val deployedFile = File("${appContext.cacheDir}/sparsemgr.sh")
            scriptFile = deployedFile
            appContext.assets.open("sparsemgr.sh").use { input ->
                deployedFile.outputStream().use { output: OutputStream -> input.copyTo(output) }
            }
            Shell.cmd("chmod 755 ${ContainerCommandBuilder.quote(deployedFile.absolutePath)}").exec()

            // container.config is the only record of where the rootfs actually is; a
            // container installed to a custom location cannot be derived from its name.
            val rootfsPath = ContainerManager.readRootfsPath(container.name)
            if (rootfsPath == null) {
                logger.e("Could not read rootfs_path from ${container.name}'s container.config")
                return
            }
            val rootfsParent = rootfsPath.substringBeforeLast('/')
            val qScript = ContainerCommandBuilder.quote(deployedFile.absolutePath)

            val cmd = when (operation) {
                is SparseOperation.Migrate -> {
                    logger.i(string(R.string.starting_migration))
                    val targetImg = operation.targetImagePath?.takeIf { it.isNotBlank() }
                    if (targetImg != null) {
                        "$qScript -d ${ContainerCommandBuilder.quote(rootfsParent)} -o ${ContainerCommandBuilder.quote(targetImg)} migrate $sizeGb"
                    } else {
                        "$qScript -d ${ContainerCommandBuilder.quote(rootfsParent)} migrate $sizeGb"
                    }
                }
                is SparseOperation.Resize -> {
                    if (rootfsPath.substringAfterLast('/') != "rootfs.img") {
                        logger.e("Rootfs at $rootfsPath is not a sparse image; cannot resize.")
                        return
                    }
                    logger.i(string(R.string.starting_resizing))
                    "$qScript -i ${ContainerCommandBuilder.quote(rootfsPath)} resize $sizeGb --yes"
                }
                is SparseOperation.ConvertToDir -> {
                    logger.i("Starting conversion from sparse image to directory...")
                    val targetDir = operation.targetDirPath?.takeIf { it.isNotBlank() } ?: "$rootfsParent/rootfs"
                    "$qScript -i ${ContainerCommandBuilder.quote(rootfsPath)} -d ${ContainerCommandBuilder.quote(targetDir)} unpack"
                }
            }

            val success = ContainerOperationExecutor.executeCommand(
                command = cmd,
                operation = "sparse_op",
                logger = logger,
                skipHeader = true,
                operationCompletedMessage = string(R.string.operation_completed_success)
            )

            if (success) {
                logger.i("Updating container configuration...")
                val updatedConfig = when (operation) {
                    is SparseOperation.Migrate -> {
                        val finalImgPath = operation.targetImagePath?.takeIf { it.isNotBlank() } ?: "$rootfsParent/rootfs.img"
                        container.copy(
                            useSparseImage = true,
                            sparseImageSizeGB = sizeGb,
                            rootfsPath = finalImgPath
                        )
                    }
                    is SparseOperation.Resize -> {
                        container.copy(sparseImageSizeGB = sizeGb)
                    }
                    is SparseOperation.ConvertToDir -> {
                        val targetDir = operation.targetDirPath?.takeIf { it.isNotBlank() } ?: "$rootfsParent/rootfs"
                        ContainerInstaller.applyPostExtractionFixes(appContext, targetDir, logger)
                        container.copy(
                            useSparseImage = false,
                            sparseImageSizeGB = null,
                            rootfsPath = targetDir
                        )
                    }
                }
                val configResult = withContext(Dispatchers.IO) {
                    ContainerManager.updateContainerConfig(appContext, container.name, updatedConfig)
                }
                if (configResult.isSuccess) {
                    logger.i("Configuration updated successfully ✓")
                    onRefresh()
                } else {
                    logger.w("Warning: Failed to update container.config: ${configResult.exceptionOrNull()?.message}")
                }
            } else {
                logger.e(string(R.string.operation_failed))
            }
        } catch (e: Exception) {
            logger.e("Error during sparse operation: ${e.message}")
            logger.e(e.stackTraceToString())
        } finally {
            scriptFile?.delete()
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    // ── Repair & Compact Sparse Image ────────────────────────────────────────
    suspend fun executeRepairAndCompact(
        container: ContainerInfo,
        runFstrim: Boolean,
        compactToUtilized: Boolean,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)

        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }

        var scriptFile: File? = null
        try {
            val isRunning = ContainerManager.checkContainerStatus(container.name).first
            if (isRunning) {
                logger.i("Stopping container '${container.name}' before filesystem maintenance...")
                val stopCommand = ContainerCommandBuilder.buildStopCommand(container)
                val stopResult = ContainerOperationExecutor.executeCommand(stopCommand, "stop", logger)
                if (!stopResult) {
                    logger.e("Failed to stop container '${container.name}'.")
                    onError("Failed to stop container before maintenance")
                    return
                }
                delay(1000)
            }

            logs.clear()
            showLogViewerFor = container.name
            logger.i("Starting Repair & Compact for '${container.name}'...")
            if (runFstrim) logger.i("Option: Discard unused free blocks (fstrim)")
            if (compactToUtilized) logger.i("Option: Compact virtual capacity down to utilized size")

            val beforeUsage = ContainerDiskUsageManager.getUsage(container.rootfsPath)
            if (beforeUsage != null) {
                logger.i("Current allocated storage: ${MoveStorageOperations.formatBytes(beforeUsage.usedBytes)}")
                logger.i("Current virtual capacity: ${MoveStorageOperations.formatBytes(beforeUsage.totalBytes)}")
            }

            val deployedFile = File("${appContext.cacheDir}/sparsemgr.sh")
            scriptFile = deployedFile
            appContext.assets.open("sparsemgr.sh").use { input ->
                deployedFile.outputStream().use { output: OutputStream -> input.copyTo(output) }
            }
            Shell.cmd("chmod 755 ${ContainerCommandBuilder.quote(deployedFile.absolutePath)}").exec()

            var flags = "--yes"
            if (runFstrim) flags += " --fstrim"
            if (compactToUtilized) flags += " --shrink"

            val cmd = "${ContainerCommandBuilder.quote(deployedFile.absolutePath)} -i ${ContainerCommandBuilder.quote(container.rootfsPath)} compact $flags"
            val success = ContainerOperationExecutor.executeCommand(
                command = cmd,
                operation = "repair_compact",
                logger = logger,
                skipHeader = true,
                operationCompletedMessage = "Filesystem check & compaction completed"
            )

            if (success) {
                delay(300)
                val afterUsage = ContainerDiskUsageManager.getUsage(container.rootfsPath)
                if (beforeUsage != null && afterUsage != null) {
                    val beforeUsed = MoveStorageOperations.formatBytes(beforeUsage.usedBytes)
                    val afterUsed = MoveStorageOperations.formatBytes(afterUsage.usedBytes)
                    val beforeCap = MoveStorageOperations.formatBytes(beforeUsage.totalBytes)
                    val afterCap = MoveStorageOperations.formatBytes(afterUsage.totalBytes)
                    logger.i("Storage Footprint: $beforeUsed -> $afterUsed")
                    logger.i("Virtual Capacity:  $beforeCap -> $afterCap")

                    if (container.useSparseImage && afterUsage.totalBytes > 0) {
                        val newSizeGb = Math.max(1, ((afterUsage.totalBytes + 1024L * 1024L * 1024L - 1) / (1024L * 1024L * 1024L)).toInt())
                        logger.i("Updating container configuration with current capacity: ${newSizeGb}GB...")
                        val updatedConfig = container.copy(sparseImageSizeGB = newSizeGb)
                        withContext(Dispatchers.IO) {
                            ContainerManager.updateContainerConfig(appContext, container.name, updatedConfig)
                        }
                    }
                }
                logger.i("Maintenance completed successfully ✓")
                onSuccess("Image repaired and compacted successfully")
            } else {
                logger.e("Repair & Compact failed.")
                onError("Failed to repair and compact container image")
            }

            if (isRunning) {
                logger.i("Restarting container '${container.name}'...")
                val startCommand = ContainerCommandBuilder.buildStartCommand(container)
                ContainerOperationExecutor.executeCommand(startCommand, "start", logger)
            }

            onRefresh()
        } catch (e: Exception) {
            logger.e("Error during repair & compact: ${e.message}")
            logger.e(e.stackTraceToString())
            onError("Error during maintenance: ${e.message}")
        } finally {
            scriptFile?.delete()
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    // ── Snapshots & Rollback ─────────────────────────────────────────────────
    suspend fun executeCreateSnapshot(
        container: ContainerInfo,
        label: String,
        description: String = "",
        enableCompression: Boolean = false,
        compressionLevel: Int = 6,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }
        showLogViewerFor = container.name

        try {
            val isRunning = ContainerManager.checkContainerStatus(container.name).first
            if (isRunning) {
                logger.i("Stopping container '${container.name}' before creating snapshot...")
                val stopCommand = ContainerCommandBuilder.buildStopCommand(container)
                val stopResult = ContainerOperationExecutor.executeCommand(stopCommand, "stop", logger)
                if (!stopResult) {
                    logger.e("Failed to stop container '${container.name}'.")
                    onError("Failed to stop container for snapshot")
                    return
                }
                delay(1000)
            }

            logger.i("Creating snapshot '$label' for '${container.name}' (compression: $enableCompression, level: $compressionLevel)...")
            val success = withContext(Dispatchers.IO) {
                ContainerSnapshotManager.createSnapshot(
                    container = container,
                    label = label,
                    description = description,
                    enableCompression = enableCompression,
                    compressionLevel = compressionLevel,
                    onProgress = { logger.logImmediate(android.util.Log.INFO, it) }
                )
            }

            if (success) {
                logger.i("Snapshot created successfully with SHA-256 integrity hash ✓")
                onSuccess("Snapshot created successfully")
            } else {
                logger.e("Failed to create snapshot.")
                onError("Failed to create container snapshot")
            }

            if (isRunning) {
                logger.i("Restarting container '${container.name}'...")
                val startCommand = ContainerCommandBuilder.buildStartCommand(container)
                ContainerOperationExecutor.executeCommand(startCommand, "start", logger)
            }

            onRefresh()
        } catch (e: Exception) {
            logger.e("Error creating snapshot: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeRestoreSnapshot(
        container: ContainerInfo,
        snapshot: ContainerSnapshot,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }
        showLogViewerFor = container.name

        try {
            val isRunning = ContainerManager.checkContainerStatus(container.name).first
            if (isRunning) {
                logger.i("Stopping container '${container.name}' before rollback...")
                val stopCommand = ContainerCommandBuilder.buildStopCommand(container)
                val stopResult = ContainerOperationExecutor.executeCommand(stopCommand, "stop", logger)
                if (!stopResult) {
                    logger.e("Failed to stop container '${container.name}'.")
                    onError("Failed to stop container for rollback")
                    return
                }
                delay(1000)
            }

            logger.i("Verifying checksum and rolling back container '${container.name}' to snapshot '${snapshot.label}' (${snapshot.formattedDate})...")
            val restoreResult = withContext(Dispatchers.IO) {
                ContainerSnapshotManager.restoreSnapshot(
                    container = container,
                    snapshot = snapshot,
                    onProgress = { logger.logImmediate(android.util.Log.INFO, it) }
                )
            }

            if (restoreResult.isSuccess) {
                logger.i("Rollback completed successfully ✓")
                onSuccess("Container rolled back to snapshot successfully")
            } else {
                val errorMsg = restoreResult.exceptionOrNull()?.message ?: "Failed to restore snapshot"
                logger.e("Rollback aborted: $errorMsg")
                onError(errorMsg)
            }

            if (isRunning) {
                logger.i("Restarting container '${container.name}'...")
                val startCommand = ContainerCommandBuilder.buildStartCommand(container)
                ContainerOperationExecutor.executeCommand(startCommand, "start", logger)
            }

            onRefresh()
        } catch (e: Exception) {
            logger.e("Error restoring snapshot: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeDeleteSnapshot(
        container: ContainerInfo,
        snapshotId: String,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        try {
            val success = withContext(Dispatchers.IO) {
                ContainerSnapshotManager.deleteSnapshot(container, snapshotId)
            }
            if (success) {
                onSuccess("Snapshot deleted")
                onRefresh()
            } else {
                onError("Failed to delete snapshot")
            }
        } catch (e: Exception) {
            onError("Error deleting snapshot: ${e.message}")
        }
    }
}
