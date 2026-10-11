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
import com.droidspaces.app.util.AnlandUtils
import com.droidspaces.app.util.Constants
import com.droidspaces.app.util.ContainerEnvironmentManager
import com.droidspaces.app.util.ScrcpyManager
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

    // ── Turnip GPU Auto-Installation ─────────────────────────────────────────
    suspend fun executeInstallTurnipDrivers(
        container: ContainerInfo,
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
            if (!isRunning) {
                logger.i("Starting container '${container.name}' to install GPU drivers...")
                val startCommand = ContainerCommandBuilder.buildStartCommand(container)
                val startResult = ContainerOperationExecutor.executeCommand(startCommand, "start", logger)
                if (!startResult) {
                    logger.e("Failed to start container '${container.name}'.")
                    onError("Failed to start container")
                    return
                }
                delay(1000)
            }

            logger.i("Deploying Adreno Turnip GPU Driver Auto-Installer...")
            val scriptContent = appContext.assets.open("install_turnip.sh").bufferedReader().use { it.readText() }
            val b64 = android.util.Base64.encodeToString(scriptContent.toByteArray(), android.util.Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $b64 | base64 -d | sh'"

            val success = ContainerOperationExecutor.executeCommand(
                command = cmd,
                operation = "install_turnip",
                logger = logger,
                skipHeader = true,
                operationCompletedMessage = "Adreno Turnip GPU drivers installed successfully"
            )

            if (success) {
                logger.i("Updating container hardware configuration...")
                var env = container.envFileContent.orEmpty()
                if (!env.contains("MESA_LOADER_DRIVER_OVERRIDE=")) {
                    env = if (env.isBlank()) "MESA_LOADER_DRIVER_OVERRIDE=kgsl" else "$env\nMESA_LOADER_DRIVER_OVERRIDE=kgsl"
                }
                if (!env.contains("TU_DEBUG=")) {
                    env = if (env.isBlank()) "TU_DEBUG=noconform" else "$env\nTU_DEBUG=noconform"
                }
                val updated = container.copy(
                    enableGpuMode = true,
                    enableTermuxX11 = true,
                    enableVirgl = false,
                    envFileContent = env
                )
                withContext(Dispatchers.IO) {
                    ContainerManager.updateContainerConfig(appContext, container.name, updated)
                }
                logger.i("Hardware configuration updated ✓")
                onSuccess("Turnip GPU drivers installed and configured successfully")
            } else {
                logger.e("Turnip driver installation failed.")
                onError("Failed to install Turnip GPU drivers")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Error installing GPU drivers: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeInstallScrcpy(
        container: ContainerInfo,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit
    ) {
        runningOperationContainer = container.name
        showLogViewerFor = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }

        try {
            logger.i("=== Install scrcpy and ADB in container '${container.name}' ===")
            if (!container.isRunning) {
                logger.i("Container is stopped. Starting container...")
                val startCommand = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} start"
                val startResult = ContainerOperationExecutor.executeCommand(startCommand, "start", logger)
                if (!startResult) {
                    logger.e("Failed to start container '${container.name}'.")
                    onError("Failed to start container")
                    return
                }
                delay(1000)
            }

            logger.i("Deploying scrcpy control script...")
            val scriptContent = appContext.assets.open("scrcpy-setup.sh").bufferedReader().use { it.readText() }
                .replace("\r\n", "\n").replace("\r", "\n")
            val b64 = android.util.Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val installCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} " +
                "run 'mkdir -p /usr/local/sbin && echo \"$b64\" | base64 -d > /usr/local/sbin/droidspaces-scrcpy && " +
                "chmod 755 /usr/local/sbin/droidspaces-scrcpy && /usr/local/sbin/droidspaces-scrcpy install'"

            val success = ContainerOperationExecutor.executeCommand(installCmd, "install_scrcpy", logger)
            if (success) {
                logger.i("scrcpy and ADB installed successfully ✓")
                onSuccess("scrcpy installed successfully")
            } else {
                logger.e("Failed to install scrcpy")
                onError("Failed to install scrcpy")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Error installing scrcpy: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeLaunchScrcpy(
        container: ContainerInfo,
        noAudio: Boolean = false,
        turnScreenOff: Boolean = false,
        stayAwake: Boolean = false,
        maxFps: Int = 0,
        extraArgs: String = "",
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit
    ) {
        runningOperationContainer = container.name
        showLogViewerFor = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }

        try {
            logger.i("=== Launch scrcpy loopback mirroring in container '${container.name}' ===")
            if (!container.isRunning) {
                logger.i("Container is stopped. Starting container...")
                val startCommand = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} start"
                val startResult = ContainerOperationExecutor.executeCommand(startCommand, "start", logger)
                if (!startResult) {
                    logger.e("Failed to start container '${container.name}'.")
                    onError("Failed to start container")
                    return
                }
                delay(1000)
            }

            logger.i("1. Ensuring host ADB is running on TCP port ${Constants.SCRCPY_ADB_PORT}...")
            val adbRes = ScrcpyManager.ensureAdbTcpip(Constants.SCRCPY_ADB_PORT)
            if (!adbRes.isSuccess) {
                logger.e("Failed to enable ADB TCP/IP mode on host: ${adbRes.message}")
                onError("Failed to enable ADB TCP/IP mode")
                return
            }
            logger.i("  Host ADB TCP/IP active ✓")

            logger.i("2. Pre-authorizing container ADB key...")
            ScrcpyManager.authorizeContainerAdbKey(container.name)
            logger.i("  Container ADB key authorized ✓")

            logger.i("3. Deploying and launching scrcpy inside desktop session...")
            val scriptContent = appContext.assets.open("scrcpy-setup.sh").bufferedReader().use { it.readText() }
                .replace("\r\n", "\n").replace("\r", "\n")
            val b64 = android.util.Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val launchCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} " +
                "run 'mkdir -p /usr/local/sbin && echo \"$b64\" | base64 -d > /usr/local/sbin/droidspaces-scrcpy && " +
                "chmod 755 /usr/local/sbin/droidspaces-scrcpy && " +
                "/usr/local/sbin/droidspaces-scrcpy launch ${Constants.SCRCPY_ADB_PORT} ${container.scrcpyMaxSize} ${container.scrcpyBitrateKbps} " +
                "${if (noAudio) "1" else "0"} ${if (turnScreenOff) "1" else "0"} ${if (stayAwake) "1" else "0"} $maxFps ${ContainerCommandBuilder.quote(extraArgs)}'"

            val success = ContainerOperationExecutor.executeCommand(launchCmd, "launch_scrcpy", logger)
            if (success) {
                logger.i("scrcpy mirroring launched successfully ✓")
                onSuccess("scrcpy launched successfully")
            } else {
                logger.e("Failed to launch scrcpy")
                onError("Failed to launch scrcpy")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Error launching scrcpy: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeStopScrcpy(
        container: ContainerInfo,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit
    ) {
        runningOperationContainer = container.name
        showLogViewerFor = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }

        try {
            logger.i("=== Stop scrcpy in container '${container.name}' ===")
            val stopCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} " +
                "run '/usr/local/sbin/droidspaces-scrcpy stop-now'"
            val success = ContainerOperationExecutor.executeCommand(stopCmd, "stop_scrcpy", logger)
            if (success) {
                logger.i("scrcpy stopped ✓")
                onSuccess("scrcpy stopped successfully")
            } else {
                logger.e("Failed to stop scrcpy")
                onError("Failed to stop scrcpy")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Error stopping scrcpy: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeInstallAnlandKde(
        container: ContainerInfo,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }
        try {
            showLogViewerFor = container.name
            logger.i("Installing Anland KDE Desktop Environment in container '${container.name}'...")
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run '${AnlandUtils.INSTALL_COMMAND}'"
            val success = ContainerOperationExecutor.executeCommand(cmd, "install_anland_kde", logger, skipHeader = true)
            if (success) {
                logger.i("Anland KDE installed successfully ✓")
                onSuccess("Anland KDE installed successfully")
            } else {
                logger.e("Failed to install Anland KDE")
                onError("Failed to install Anland KDE")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Error: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeInstallWaylandDesktop(
        container: ContainerInfo,
        desktopName: String,
        installCommand: String,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }
        try {
            showLogViewerFor = container.name
            logger.i("Installing $desktopName Desktop in container '${container.name}'...")
            val b64 = android.util.Base64.encodeToString(installCommand.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $b64 | base64 -d | sh'"
            val success = ContainerOperationExecutor.executeCommand(cmd, "install_desktop", logger, skipHeader = true)
            if (success) {
                logger.i("$desktopName installed successfully ✓")
                onSuccess("$desktopName installed successfully")
            } else {
                logger.e("Failed to install $desktopName")
                onError("Failed to install $desktopName")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Error: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeInstallUsbManager(
        container: ContainerInfo,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }
        try {
            showLogViewerFor = container.name
            logger.i("Installing Droidspaces USB Manager in container '${container.name}'...")
            val scriptContent = appContext.assets.open("install-usb-manager.sh").bufferedReader().use { it.readText() }
                .replace("\r\n", "\n").replace("\r", "\n")
            val b64 = android.util.Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val userArg = if (container.defaultUsername.isNotBlank()) " --user ${container.defaultUsername}" else ""
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $b64 | base64 -d | bash -s --$userArg'"
            val success = ContainerOperationExecutor.executeCommand(cmd, "install_usb_manager", logger, skipHeader = true)
            if (success) {
                logger.i("USB Manager installed successfully ✓")
                onSuccess("USB Manager installed successfully")
            } else {
                logger.e("Failed to install USB Manager")
                onError("Failed to install USB Manager")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Error: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeInstallKdeKeyboard(
        container: ContainerInfo,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }
        try {
            showLogViewerFor = container.name
            logger.i("Installing KDE Plasma Virtual Keyboard in container '${container.name}'...")
            val isRunning = ContainerManager.checkContainerStatus(container.name).first
            if (!isRunning) {
                logger.i("Starting container '${container.name}'...")
                val startCommand = ContainerCommandBuilder.buildStartCommand(container)
                val startResult = ContainerOperationExecutor.executeCommand(startCommand, "start", logger)
                if (!startResult) {
                    logger.e("Failed to start container '${container.name}'.")
                    onError("Failed to start container")
                    return
                }
                delay(1000)
            }
            val scriptContent = appContext.assets.open("install-kde-keyboard.sh").bufferedReader().use { it.readText() }
                .replace("\r\n", "\n").replace("\r", "\n")
            val b64 = android.util.Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $b64 | base64 -d | sh'"
            val success = ContainerOperationExecutor.executeCommand(cmd, "install_kde_keyboard", logger, skipHeader = true)
            if (success) {
                logger.i("KDE Plasma Virtual Keyboard installed successfully ✓")
                onSuccess("KDE Plasma Keyboard installed successfully")
            } else {
                logger.e("Failed to install KDE Plasma Keyboard")
                onError("Failed to install KDE Plasma Keyboard")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Error: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeInstallVboard(
        container: ContainerInfo,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        runningOperationContainer = container.name
        val logs = logsFor(container.name)
        logs.clear()
        prefsManager.clearContainerLogs(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }
        try {
            showLogViewerFor = container.name
            logger.i("Installing vboard Virtual Keyboard in container '${container.name}'...")
            val isRunning = ContainerManager.checkContainerStatus(container.name).first
            if (!isRunning) {
                logger.i("Starting container '${container.name}'...")
                val startCommand = ContainerCommandBuilder.buildStartCommand(container)
                val startResult = ContainerOperationExecutor.executeCommand(startCommand, "start", logger)
                if (!startResult) {
                    logger.e("Failed to start container '${container.name}'.")
                    onError("Failed to start container")
                    return
                }
                delay(1000)
            }
            val scriptContent = appContext.assets.open("install-vboard.sh").bufferedReader().use { it.readText() }
                .replace("\r\n", "\n").replace("\r", "\n")
            val b64 = android.util.Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $b64 | base64 -d | sh'"
            val success = ContainerOperationExecutor.executeCommand(cmd, "install_vboard", logger, skipHeader = true)
            if (success) {
                logger.i("vboard Virtual Keyboard installed successfully ✓")
                onSuccess("vboard installed successfully")
            } else {
                logger.e("Failed to install vboard")
                onError("Failed to install vboard")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Error: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }

    suspend fun executeConfigureAnlandKeyboard(
        container: ContainerInfo,
        keyboardType: String,
        onRefresh: () -> Unit,
        onError: (String) -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        val logs = logsFor(container.name)
        val logger = ViewModelLogger { level, message -> logs.add(level to message) }.apply { verbose = true }
        try {
            val isRunning = ContainerManager.checkContainerStatus(container.name).first
            if (isRunning) {
                val script = if (keyboardType == "kde") {
                    "mkdir -p /etc/profile.d /etc/xdg/autostart && " +
                    "printf 'export QT_IM_MODULE=maliitph\\nexport XMODIFIERS=@im=maliit\\n' > /etc/profile.d/anland_keyboard.sh && " +
                    "[ -f /etc/xdg/autostart/vboard.desktop ] && sed -i 's/Hidden=false/Hidden=true/g' /etc/xdg/autostart/vboard.desktop || true; " +
                    "[ -f /etc/xdg/autostart/kde-virtual-keyboard.desktop ] && sed -i 's/Hidden=true/Hidden=false/g' /etc/xdg/autostart/kde-virtual-keyboard.desktop || true"
                } else {
                    "mkdir -p /etc/profile.d /etc/xdg/autostart && " +
                    "printf 'export WAYLAND_KEYBOARD=vboard\\n' > /etc/profile.d/anland_keyboard.sh && " +
                    "[ -f /etc/xdg/autostart/kde-virtual-keyboard.desktop ] && sed -i 's/Hidden=false/Hidden=true/g' /etc/xdg/autostart/kde-virtual-keyboard.desktop || true; " +
                    "[ -f /etc/xdg/autostart/vboard.desktop ] && sed -i 's/Hidden=true/Hidden=false/g' /etc/xdg/autostart/vboard.desktop || true"
                }
                val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run '$script'"
                ContainerOperationExecutor.executeCommand(cmd, "config_keyboard", logger, skipHeader = true)
            }
            onSuccess("Active keyboard set to ${if (keyboardType == "kde") "KDE Plasma Keyboard" else "vboard"} ✓")
            onRefresh()
        } catch (e: Exception) {
            onError("Error: ${e.message}")
        }
    }

    suspend fun executeAutoConfigureAnlandEnvironment(
        container: ContainerInfo,
        hasSnapdragonFix: Boolean,
        selectedDesktopServiceName: String? = null,
        selectedDesktopStartCommand: String? = null,
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
            if (!isRunning) {
                logger.i("Starting container '${container.name}' to configure environment...")
                val startCommand = ContainerCommandBuilder.buildStartCommand(container)
                val startResult = ContainerOperationExecutor.executeCommand(startCommand, "start", logger)
                if (!startResult) {
                    logger.e("Failed to start container '${container.name}'.")
                    onError("Failed to start container")
                    return
                }
                delay(1000)
            }

            logger.i("Configuring Wayland and Anland environment variables in .anland.env...")
            val anlandEnv = buildString {
                appendLine("XCURSOR_SIZE=48")
                appendLine("WAYLAND_DISPLAY=wayland-0")
                appendLine("QT_QPA_PLATFORM=wayland")
                appendLine("QT_AUTO_SCREEN_SCALE_FACTOR=1")
                appendLine("GDK_BACKEND=wayland,x11")
                appendLine("CLUTTER_BACKEND=wayland")
                appendLine("SDL_VIDEODRIVER=wayland")
                appendLine("XDG_SESSION_TYPE=wayland")
                appendLine("MOZ_ENABLE_WAYLAND=1")
                appendLine("ANLAND=1")
                appendLine("ANLAND_SOCKET=/run/display.sock")
                appendLine("ANLAND_DRM_DEVICE=/dev/dri/renderD128")
                appendLine("XWAYLAND_GBM_DEVICE=/dev/dri/renderD128")
                appendLine("MESA_LOADER_DRIVER_OVERRIDE=kgsl")
                appendLine("GALLIUM_DRIVER=kgsl")
                appendLine("FD_FORCE_KGSL=1")
                if (hasSnapdragonFix) {
                    appendLine("FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1")
                    appendLine("TU_DEBUG=noconform")
                }
            }.trim()

            ContainerEnvironmentManager.saveAnlandEnv(
                context = appContext,
                containerName = container.name,
                content = anlandEnv
            )
            val success = ContainerEnvironmentManager.syncToContainer(appContext, container.name)
            logger.i("Synced modular environment to /etc/environment:")
            logger.i(anlandEnv)

            val profileScript = """
                mkdir -p /etc/profile.d 2>/dev/null || true
                cat > /etc/profile.d/anland.sh <<'EOF'
[ -z "${'$'}WAYLAND_DISPLAY" ] && export WAYLAND_DISPLAY=wayland-0
[ -z "${'$'}QT_QPA_PLATFORM" ] && export QT_QPA_PLATFORM=wayland
[ -z "${'$'}ANLAND" ] && export ANLAND=1
[ -z "${'$'}ANLAND_SOCKET" ] && export ANLAND_SOCKET=/run/display.sock
[ -z "${'$'}ANLAND_DRM_DEVICE" ] && export ANLAND_DRM_DEVICE=/dev/dri/renderD128
_UID="${'$'}(id -u 2>/dev/null)"
_UN="${'$'}(id -un 2>/dev/null || echo user)"
if [ -d "/run/user/${'$'}_UID" ]; then
    export XDG_RUNTIME_DIR="/run/user/${'$'}_UID"
elif mkdir -p -m 0700 "/run/user/${'$'}_UID" 2>/dev/null; then
    export XDG_RUNTIME_DIR="/run/user/${'$'}_UID"
else
    export XDG_RUNTIME_DIR="/tmp/runtime-${'$'}_UN"
    mkdir -p -m 0700 "${'$'}XDG_RUNTIME_DIR" 2>/dev/null || true
fi
ln -sfn "${'$'}XDG_RUNTIME_DIR" "/tmp/runtime-${'$'}_UN" 2>/dev/null || true
EOF
                chmod 0755 /etc/profile.d/anland.sh 2>/dev/null || true

                # Sanitize /etc/environment: remove any bad export keywords or stale DISPLAY / modifier overrides
                sed -i 's/^[[:space:]]*export[[:space:]]\+//g' /etc/environment 2>/dev/null || true
                sed -i '/^export /d' /etc/environment 2>/dev/null || true
                sed -i '/^XDG_RUNTIME_DIR=/d' /etc/environment 2>/dev/null || true
                sed -i '/^DISPLAY=/d' /etc/environment 2>/dev/null || true
                sed -i '/^KWIN_DRM_USE_MODIFIERS=/d' /etc/environment 2>/dev/null || true

                # Permissions
                chmod 1777 /tmp 2>/dev/null || true
                mkdir -p -m 1777 /tmp/.X11-unix /tmp/.ICE-unix 2>/dev/null || true
                chmod 0666 /dev/dri/renderD128 /dev/dri/card* /dev/kgsl-3d0 2>/dev/null || true
                chmod 0777 /run/display.sock 2>/dev/null || true

                # Unlock users and fix group memberships + ~/.bashrc
                for u in ${'$'}(awk -F: '${'$'}3 >= 1000 && ${'$'}3 < 65534 {print ${'$'}1}' /etc/passwd 2>/dev/null); do
                    uid_num=${'$'}(id -u "${'$'}u" 2>/dev/null)
                    [ -z "${'$'}uid_num" ] && continue
                    usermod -aG shadow,video,render,audio "${'$'}u" 2>/dev/null || true
                    if grep -q "^${'$'}u:!" /etc/shadow 2>/dev/null; then
                        echo "${'$'}u:1234" | chpasswd 2>/dev/null || true
                    fi
                    mkdir -p -m 0755 /run/user 2>/dev/null || true
                    mkdir -p -m 0700 "/run/user/${'$'}uid_num" "/tmp/runtime-${'$'}u" 2>/dev/null || true
                    chown -R "${'$'}u:${'$'}u" "/run/user/${'$'}uid_num" "/tmp/runtime-${'$'}u" 2>/dev/null || true
                    chmod 0700 "/run/user/${'$'}uid_num" "/tmp/runtime-${'$'}u" 2>/dev/null || true
                    ln -sfn "/run/user/${'$'}uid_num" "/tmp/runtime-${'$'}u" 2>/dev/null || true

                    if [ -f "/home/${'$'}u/.bashrc" ]; then
                        sed -i '/^export XDG_RUNTIME_DIR=\/tmp/d' "/home/${'$'}u/.bashrc" 2>/dev/null || true
                        grep -q "XDG_RUNTIME_DIR=" "/home/${'$'}u/.bashrc" 2>/dev/null || echo 'export XDG_RUNTIME_DIR=/run/user/${'$'}(id -u)' >> "/home/${'$'}u/.bashrc"
                        chown "${'$'}u:${'$'}u" "/home/${'$'}u/.bashrc" 2>/dev/null || true
                    fi
                done

                # Install noctalia-launch wrapper
                cat > /usr/local/bin/noctalia-launch <<'EOF_LAUNCH'
#!/bin/bash
if [ -z "${'$'}XDG_RUNTIME_DIR" ]; then
    if [ -d "/run/user/${'$'}(id -u 2>/dev/null)" ]; then
        export XDG_RUNTIME_DIR="/run/user/${'$'}(id -u 2>/dev/null)"
    elif [ -d "/tmp/runtime-${'$'}(id -un 2>/dev/null)" ]; then
        export XDG_RUNTIME_DIR="/tmp/runtime-${'$'}(id -un 2>/dev/null)"
    elif [ -d "/run/user/1000" ]; then
        export XDG_RUNTIME_DIR="/run/user/1000"
    else
        export XDG_RUNTIME_DIR="/tmp/runtime-${'$'}(id -un 2>/dev/null || echo user)"
        mkdir -p -m 0700 "${'$'}XDG_RUNTIME_DIR" 2>/dev/null || true
    fi
fi

for i in ${'$'}(seq 1 120); do
    for sock in "${'$'}XDG_RUNTIME_DIR"/wayland-* /run/user/*/wayland-* /tmp/runtime-*/wayland-*; do
        [ -e "${'$'}sock" ] || continue
        case "${'$'}sock" in *.lock) continue ;; esac
        [ -S "${'$'}sock" ] || continue
        export XDG_RUNTIME_DIR="${'$'}(dirname "${'$'}sock")"
        export WAYLAND_DISPLAY="${'$'}(basename "${'$'}sock")"
        exec /usr/local/bin/noctalia
    done
    sleep 1
done
exit 1
EOF_LAUNCH
                chmod +x /usr/local/bin/noctalia-launch 2>/dev/null || true
            """.trimIndent()
            val b64Profile = android.util.Base64.encodeToString(profileScript.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val profileCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $b64Profile | base64 -d | sh'"
            Shell.cmd(profileCmd).exec()

            val sName = selectedDesktopServiceName ?: "plasma-wayland.service"
            val configuredUser = container.defaultUsername.ifBlank { container.sshUser }
            val svcScript = when (sName) {
                "plasma-wayland.service", "plasma-mobile.service" -> {
                    val binary = if (sName == "plasma-mobile.service") "startplasmamobile" else "startplasma-wayland"
                    """
                        TARGET_USER="${configuredUser}"
                        if [ -z "${'$'}TARGET_USER" ]; then
                            TARGET_USER=${'$'}(awk -F: '${'$'}3 >= 1000 && ${'$'}3 < 65534 {print ${'$'}1; exit}' /etc/passwd 2>/dev/null || echo "1000")
                        fi
                        [ -z "${'$'}TARGET_USER" ] && TARGET_USER="1000"

                        mkdir -p /etc/systemd/system 2>/dev/null || true
                        cat > "/etc/systemd/system/$sName" <<EOF
[Unit]
Description=Start $sName
After=network.target display-manager.service
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=${'$'}TARGET_USER
PAMName=login
EnvironmentFile=-/etc/environment
ExecStart=/bin/bash -lc '$binary'
Restart=on-failure
RestartSec=2s

[Install]
WantedBy=multi-user.target
EOF
                        chmod 644 "/etc/systemd/system/$sName"
                        if command -v systemctl >/dev/null 2>&1; then
                            systemctl daemon-reload 2>/dev/null || true
                            systemctl enable "$sName" 2>&1 || true
                        fi
                        echo "Configured $sName autostart for user '${'$'}TARGET_USER'."
                    """.trimIndent()
                }
                "niri.service" -> {
                    """
                        TARGET_USER="${configuredUser}"
                        if [ -z "${'$'}TARGET_USER" ]; then
                            TARGET_USER=${'$'}(awk -F: '${'$'}3 >= 1000 && ${'$'}3 < 65534 {print ${'$'}1; exit}' /etc/passwd 2>/dev/null || echo "1000")
                        fi
                        [ -z "${'$'}TARGET_USER" ] && TARGET_USER="1000"

                        mkdir -p /etc/systemd/system 2>/dev/null || true
                        cat > "/etc/systemd/system/niri.service" <<EOF
[Unit]
Description=Niri Compositor (Anland Backend)
Documentation=https://github.com/niri-wm/niri
After=network.target display-manager.service
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=${'$'}TARGET_USER
PAMName=login
EnvironmentFile=-/etc/environment
Environment=ANLAND_SOCKET=/run/display.sock
Environment=ANLAND_DRM_DEVICE=/dev/dri/renderD128
Environment=XWAYLAND_GBM_DEVICE=/dev/dri/renderD128
Environment=MESA_LOADER_DRIVER_OVERRIDE=kgsl
Environment=GALLIUM_DRIVER=kgsl
Environment=FD_FORCE_KGSL=1
Environment=XDG_SESSION_TYPE=wayland
Environment=XDG_CURRENT_DESKTOP=niri
Environment=QT_QPA_PLATFORM=wayland
Environment=GTK_THEME=Adwaita:dark

ExecStartPre=/bin/bash -lc 'for i in ${'$'}(seq 1 60); do [ -S /run/display.sock ] && exit 0; sleep 1; done; exit 1'
ExecStart=/bin/bash -lc 'export XDG_RUNTIME_DIR=/tmp/runtime-${'$'}(id -un); mkdir -p -m 0700 "${'$'}XDG_RUNTIME_DIR"; rm -f "${'$'}XDG_RUNTIME_DIR"/wayland-* "${'$'}XDG_RUNTIME_DIR"/niri.* 2>/dev/null || true; if command -v niri >/dev/null 2>&1; then niri --anland; else /usr/local/bin/niri --anland; fi'
Restart=on-failure
RestartSec=2
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
                        chmod 644 "/etc/systemd/system/niri.service"

                        cat > "/etc/systemd/system/noctalia.service" <<EOF
[Unit]
Description=Noctalia Shell (Wayland)
Documentation=https://github.com/noctalia-dev/noctalia
After=niri.service
Wants=niri.service

[Service]
Type=simple
User=${'$'}TARGET_USER
PAMName=login
Environment=QT_QPA_PLATFORM=wayland
Environment=GTK_THEME=Adwaita:dark
ExecStart=/usr/local/bin/noctalia-launch
Restart=on-failure
RestartSec=2
StartLimitIntervalSec=60
StartLimitBurst=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
                        chmod 644 "/etc/systemd/system/noctalia.service"

                        if command -v systemctl >/dev/null 2>&1; then
                            systemctl daemon-reload 2>/dev/null || true
                            systemctl enable niri.service 2>&1 || true
                            systemctl enable noctalia.service 2>&1 || true
                        fi
                        echo "Configured niri and noctalia autostart for user '${'$'}TARGET_USER'."
                    """.trimIndent()
                }
                else -> {
                    val sCmd = selectedDesktopStartCommand ?: "export XDG_RUNTIME_DIR=/tmp/runtime-\$(id -un); startplasma-wayland"
                    """
                        TARGET_USER="${configuredUser}"
                        if [ -z "${'$'}TARGET_USER" ]; then
                            TARGET_USER=${'$'}(awk -F: '${'$'}3 >= 1000 && ${'$'}3 < 65534 {print ${'$'}1; exit}' /etc/passwd 2>/dev/null || echo "1000")
                        fi
                        [ -z "${'$'}TARGET_USER" ] && TARGET_USER="1000"

                        mkdir -p /etc/systemd/system 2>/dev/null || true
                        cat > "/etc/systemd/system/$sName" <<EOF
[Unit]
Description=Start $sName Desktop Environment
After=network.target display-manager.service
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=${'$'}TARGET_USER
PAMName=login
EnvironmentFile=-/etc/environment
ExecStart=/bin/bash -lc '$sCmd'
Restart=on-failure
RestartSec=2s

[Install]
WantedBy=multi-user.target
EOF
                        chmod 644 "/etc/systemd/system/$sName"
                        if command -v systemctl >/dev/null 2>&1; then
                            systemctl daemon-reload 2>/dev/null || true
                            systemctl enable "$sName" 2>&1 || true
                        fi
                        echo "Configured $sName autostart for user '${'$'}TARGET_USER'."
                    """.trimIndent()
                }
            }
            val b64Svc = android.util.Base64.encodeToString(svcScript.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val svcCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $b64Svc | base64 -d | sh'"
            ContainerOperationExecutor.executeCommand(svcCmd, "configure_anland_service", logger, skipHeader = true)

            if (success) {
                logger.i("Anland Wayland environment and autostart service configured ✓")
                onSuccess("Anland auto-configured successfully")
            } else {
                logger.e("Failed to configure Anland environment")
                onError("Failed to configure Anland environment")
            }
            onRefresh()
        } catch (e: Exception) {
            logger.e("Error configuring Anland environment: ${e.message}")
            onError("Error: ${e.message}")
        } finally {
            prefsManager.saveContainerLogs(container.name, logs.toList())
            delay(500)
            runningOperationContainer = null
        }
    }
}
