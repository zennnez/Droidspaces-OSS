package com.droidspaces.app.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live, leak-proof Docker container log streaming and snapshots (journalctl-style).
 */
object DockerLogManager {

    private const val POLL_INTERVAL_MS = 1000L
    private const val INITIAL_LINES = 300
    private const val MAX_DOWNLOAD_LINES = 50_000

    data class LogFetchResult(val lines: List<String>, val error: String?) {
        val isSuccess: Boolean get() = error == null
    }

    private fun runCmd(containerName: String, dockerId: String, args: String): String {
        val quotedName = ContainerCommandBuilder.quote(containerName)
        val quotedId = ContainerCommandBuilder.quote(dockerId)
        return "${Constants.DROIDSPACES_BINARY_PATH} --name=$quotedName run " +
            "'docker logs $args $quotedId 2>&1'"
    }

    /**
     * One-shot fetch of up to [maxLines] most recent log lines.
     */
    suspend fun fetchLogs(
        containerName: String,
        dockerId: String,
        maxLines: Int = INITIAL_LINES,
        showTimestamps: Boolean = true
    ): LogFetchResult = withContext(Dispatchers.IO) {
        val tsFlag = if (showTimestamps) "-t" else ""
        val cmd = runCmd(containerName, dockerId, "--tail $maxLines $tsFlag")
        try {
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess || result.out.isNotEmpty()) {
                LogFetchResult(result.out, null)
            } else {
                val err = result.err.joinToString("\n").ifBlank {
                    "docker logs exited with code ${result.code}"
                }
                LogFetchResult(emptyList(), err)
            }
        } catch (e: Exception) {
            LogFetchResult(emptyList(), e.message ?: "Unknown error")
        }
    }

    /**
     * Full fetch for the "Download as log" action.
     */
    suspend fun fetchFullLogForDownload(
        containerName: String,
        dockerId: String,
        showTimestamps: Boolean = true
    ): LogFetchResult = fetchLogs(containerName, dockerId, MAX_DOWNLOAD_LINES, showTimestamps)

    /**
     * Emits each new log line as it's written, starting from "now".
     * Cleans up background follower on flow cancellation.
     */
    fun streamNewLines(
        containerName: String,
        dockerId: String,
        showTimestamps: Boolean = true
    ): Flow<String> = callbackFlow {
        val token = "ds_dlog_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val tmpFile = "/tmp/.$token.log"
        val quotedName = ContainerCommandBuilder.quote(containerName)
        val quotedId = ContainerCommandBuilder.quote(dockerId)
        val binary = Constants.DROIDSPACES_BINARY_PATH
        val busybox = Constants.BUSYBOX_BINARY_PATH
        val tsFlag = if (showTimestamps) "-t" else ""

        val startCmd = "\"$binary\" --name=$quotedName run " +
            "'($busybox setsid sh -c \"env DS_DOCKER_LOG_TOKEN=$token docker logs -f --tail 0 $tsFlag $quotedId > $tmpFile 2>&1\" " +
            "|| setsid sh -c \"env DS_DOCKER_LOG_TOKEN=$token docker logs -f --tail 0 $tsFlag $quotedId > $tmpFile 2>&1\" " +
            "|| env DS_DOCKER_LOG_TOKEN=$token docker logs -f --tail 0 $tsFlag $quotedId > $tmpFile 2>&1 &) ; " +
            "sleep 0.3 ; echo STARTED'"

        val startResult = Shell.cmd(startCmd).exec()
        if (!startResult.isSuccess) {
            trySend("[log-stream] Failed to start live tail: ${startResult.err.joinToString(" ").ifBlank { "exit ${startResult.code}" }}")
            close()
            return@callbackFlow
        }

        var lastSize = 0L
        val pollJob = launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                try {
                    val sizeCmd = "\"$binary\" --name=$quotedName run 'wc -c < $tmpFile 2>/dev/null || echo 0'"
                    val sizeResult = Shell.cmd(sizeCmd).exec()
                    val size = sizeResult.out.firstOrNull()?.trim()?.toLongOrNull() ?: continue

                    when {
                        size > lastSize -> {
                            val tailCmd = "\"$binary\" --name=$quotedName run 'tail -c +${lastSize + 1} $tmpFile 2>/dev/null'"
                            val tailResult = Shell.cmd(tailCmd).exec()
                            tailResult.out.forEach { line -> trySend(line) }
                            lastSize = size
                        }
                        size < lastSize -> {
                            lastSize = 0
                        }
                    }
                } catch (e: Exception) {
                    // Ignore transient errors
                }
            }
        }

        awaitClose {
            pollJob.cancel()
            Shell.cmd(
                "\"$binary\" --name=$quotedName run " +
                    "'pkill -f \"DS_DOCKER_LOG_TOKEN=$token\" 2>/dev/null; rm -f $tmpFile 2>/dev/null; true'"
            ).submit()
        }
    }.flowOn(Dispatchers.IO)
}
