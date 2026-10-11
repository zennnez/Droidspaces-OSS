package com.droidspaces.app.util

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages process operations and HTOP-style inspection inside containers.
 * Executes commands using droidspaces run.
 */
object ContainerProcessManager {
    private const val TAG = "ContainerProcessManager"

    /**
     * Process information from ps output with full HTOP fields.
     */
    data class ProcessInfo(
        val pid: Int,
        val user: String? = null,
        val pri: String? = null,
        val ni: String? = null,
        val virt: String? = null,
        val res: String? = null,
        val state: String? = null,
        val cpu: Double? = null,
        val mem: Double? = null,
        val time: String? = null,
        val command: String = ""
    )

    /**
     * System-wide container metrics for HTOP header.
     */
    data class HtopMetrics(
        val totalTasks: Int = 0,
        val runningTasks: Int = 0,
        val sleepingTasks: Int = 0,
        val stoppedTasks: Int = 0,
        val zombieTasks: Int = 0,
        val cpuPercent: Double = 0.0,
        val ramUsedMb: Long = 0L,
        val ramTotalMb: Long = 0L,
        val ramPercent: Double = 0.0,
        val swapUsedMb: Long = 0L,
        val swapTotalMb: Long = 0L,
        val uptime: String = "",
        val loadAverage: String = ""
    )

    /**
     * Get process list from container with priority, memory, CPU and state fields.
     */
    suspend fun getProcessList(containerName: String): List<ProcessInfo> = withContext(Dispatchers.IO) {
        try {
            // Try standard ps -eo with full columns, fallback to ps -aux / ps w
            val psCmd = "ps -eo pid,user,pri,ni,vsz,rss,stat,time,%cpu,%mem,args 2>/dev/null || ps -aux 2>/dev/null || ps w 2>/dev/null"
            val result = Shell.cmd(
                "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run '$psCmd'"
            ).exec()

            if (!result.isSuccess || result.out.isEmpty()) {
                Log.w(TAG, "Failed to get process list for $containerName")
                return@withContext emptyList()
            }

            // Filter out our own ps and wrapper commands
            val filteredOutput = result.out.filter { line ->
                val trimmed = line.trim().lowercase()
                !trimmed.contains("ps -eo") &&
                !trimmed.contains("ps -aux") &&
                !trimmed.contains("ps w") &&
                !trimmed.contains("grep -v")
            }

            parsePsOutput(filteredOutput)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting process list for $containerName", e)
            emptyList()
        }
    }

    /**
     * Get live HTOP metrics (RAM, Swap, Tasks, Load, Uptime) for a container.
     */
    suspend fun getHtopMetrics(containerName: String): HtopMetrics = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch live container metrics (CPU, RAM, Uptime) via unified Droidspaces daemon usage command
            val usageCommand = ContainerCommandBuilder.buildUsageCommand(containerName)
            val usageResult = Shell.cmd(usageCommand).exec()

            var uptimeStr = ""
            var daemonCpuPercent = 0.0
            var ramUsedKb = 0L
            var ramTotalKb = 0L

            if (usageResult.isSuccess && usageResult.out.isNotEmpty()) {
                usageResult.out.forEach { line ->
                    val parts = line.trim().split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        when (key) {
                            "UPTIME" -> uptimeStr = value.takeIf { it != "NONE" && it.isNotEmpty() }.orEmpty()
                            "CPU_PERMILL" -> {
                                val permill = value.toDoubleOrNull() ?: 0.0
                                daemonCpuPercent = (permill / 10.0).coerceIn(0.0, 100.0)
                            }
                            "RAM_USED_KB" -> ramUsedKb = value.toLongOrNull() ?: 0L
                            "RAM_TOTAL_KB" -> ramTotalKb = value.toLongOrNull() ?: 0L
                        }
                    }
                }
            }

            // 2. Fetch loadavg and swap info from container environment
            val auxCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run " +
                "'cat /proc/loadavg 2>/dev/null | cut -d\" \" -f1-3; grep -E \"^(SwapTotal|SwapFree):\" /proc/meminfo 2>/dev/null || true'"
            val auxResult = Shell.cmd(auxCmd).exec()

            var loadAvg = ""
            var swapTotalKb = 0L
            var swapFreeKb = 0L

            for (line in auxResult.out) {
                val trimmed = line.trim()
                if (trimmed.startsWith("SwapTotal:")) {
                    swapTotalKb = trimmed.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                } else if (trimmed.startsWith("SwapFree:")) {
                    swapFreeKb = trimmed.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                } else if (trimmed.matches(Regex("""^[\d.]+\s+[\d.]+\s+[\d.]+.*"""))) {
                    loadAvg = trimmed.split(Regex("\\s+")).take(3).joinToString(" ")
                }
            }

            val ramTotal = if (ramTotalKb > 0L) ramTotalKb / 1024L else 0L
            val ramUsed = if (ramUsedKb > 0L) ramUsedKb / 1024L else 0L
            val ramPct = if (ramTotalKb > 0L) {
                ((ramUsedKb.toDouble() / ramTotalKb.toDouble()) * 100.0).coerceIn(0.0, 100.0)
            } else 0.0

            val swapTotal = swapTotalKb / 1024L
            val swapUsed = (swapTotalKb - swapFreeKb).coerceAtLeast(0L) / 1024L

            // 3. Process list for task summary
            val processes = getProcessList(containerName)
            var running = 0
            var sleeping = 0
            var stopped = 0
            var zombie = 0
            var cpuSum = 0.0

            for (p in processes) {
                cpuSum += (p.cpu ?: 0.0)
                when (p.state?.firstOrNull()?.uppercaseChar()) {
                    'R' -> running++
                    'S', 'I', 'D' -> sleeping++
                    'T' -> stopped++
                    'Z' -> zombie++
                    else -> sleeping++
                }
            }

            val effectiveCpu = if (daemonCpuPercent > 0.0) daemonCpuPercent else cpuSum.coerceIn(0.0, 100.0)

            HtopMetrics(
                totalTasks = processes.size,
                runningTasks = running,
                sleepingTasks = sleeping,
                stoppedTasks = stopped,
                zombieTasks = zombie,
                cpuPercent = effectiveCpu,
                ramUsedMb = ramUsed,
                ramTotalMb = ramTotal,
                ramPercent = ramPct,
                swapUsedMb = swapUsed,
                swapTotalMb = swapTotal,
                uptime = uptimeStr,
                loadAverage = loadAvg
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting HTOP metrics for $containerName", e)
            HtopMetrics()
        }
    }

    /**
     * Send POSIX signal to a process inside container (TERM, KILL, STOP, CONT, HUP, etc.).
     */
    suspend fun sendSignal(containerName: String, pid: Int, signal: String = "TERM"): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(
                "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run kill -$signal $pid"
            ).exec()
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Error sending signal $signal to process $pid in $containerName", e)
            false
        }
    }

    /**
     * Change nice / priority of a process inside container (-20 to 19).
     */
    suspend fun reniceProcess(containerName: String, pid: Int, nice: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(
                "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run renice $nice -p $pid"
            ).exec()
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Error renicing process $pid in $containerName", e)
            false
        }
    }

    /**
     * Kill a process in container (SIGTERM or SIGKILL).
     */
    suspend fun killProcess(containerName: String, pid: Int, force: Boolean = false): Boolean =
        sendSignal(containerName, pid, if (force) "KILL" else "TERM")

    /**
     * Pause a process in container (SIGSTOP).
     */
    suspend fun pauseProcess(containerName: String, pid: Int): Boolean =
        sendSignal(containerName, pid, "STOP")

    /**
     * Resume a paused process in container (SIGCONT).
     */
    suspend fun resumeProcess(containerName: String, pid: Int): Boolean =
        sendSignal(containerName, pid, "CONT")

    /**
     * Parse ps output into ProcessInfo list with full columns.
     */
    private fun parsePsOutput(output: List<String>): List<ProcessInfo> {
        if (output.isEmpty()) return emptyList()

        val processes = mutableListOf<ProcessInfo>()
        val headerLine = output.firstOrNull() ?: return emptyList()

        val parts = headerLine.trim().split(Regex("\\s+"))
        val pidIdx = parts.indexOfFirst { it.equals("PID", ignoreCase = true) }.takeIf { it >= 0 }
        val userIdx = parts.indexOfFirst { it.equals("USER", ignoreCase = true) }.takeIf { it >= 0 }
        val priIdx = parts.indexOfFirst { it.equals("PRI", ignoreCase = true) }.takeIf { it >= 0 }
        val niIdx = parts.indexOfFirst { it.equals("NI", ignoreCase = true) }.takeIf { it >= 0 }
        val vszIdx = parts.indexOfFirst { it.equals("VSZ", ignoreCase = true) || it.equals("VIRT", ignoreCase = true) }.takeIf { it >= 0 }
        val rssIdx = parts.indexOfFirst { it.equals("RSS", ignoreCase = true) || it.equals("RES", ignoreCase = true) }.takeIf { it >= 0 }
        val statIdx = parts.indexOfFirst { it.equals("STAT", ignoreCase = true) || it.equals("S", ignoreCase = true) }.takeIf { it >= 0 }
        val timeIdx = parts.indexOfFirst { it.equals("TIME", ignoreCase = true) || it.equals("TIME+", ignoreCase = true) }.takeIf { it >= 0 }
        val cpuIdx = parts.indexOfFirst { it.contains("%CPU", ignoreCase = true) || it.equals("CPU", ignoreCase = true) }.takeIf { it >= 0 }
        val memIdx = parts.indexOfFirst { it.contains("%MEM", ignoreCase = true) || it.equals("MEM", ignoreCase = true) }.takeIf { it >= 0 }
        val cmdIdx = parts.indexOfFirst { it.equals("ARGS", ignoreCase = true) || it.equals("COMMAND", ignoreCase = true) || it.equals("CMD", ignoreCase = true) }.takeIf { it >= 0 } ?: (parts.size - 1)

        for (i in 1 until output.size) {
            val line = output[i].trim()
            if (line.isEmpty()) continue

            try {
                val lineParts = line.split(Regex("\\s+"))
                if (lineParts.size < 3) continue

                val pid = if (pidIdx != null && pidIdx < lineParts.size) {
                    lineParts[pidIdx].toIntOrNull()
                } else {
                    lineParts[0].toIntOrNull()
                } ?: continue

                val user = if (userIdx != null && userIdx < lineParts.size) lineParts[userIdx] else null
                val pri = if (priIdx != null && priIdx < lineParts.size) lineParts[priIdx] else null
                val ni = if (niIdx != null && niIdx < lineParts.size) lineParts[niIdx] else null
                val virt = if (vszIdx != null && vszIdx < lineParts.size) formatMemKb(lineParts[vszIdx].toLongOrNull()) else null
                val res = if (rssIdx != null && rssIdx < lineParts.size) formatMemKb(lineParts[rssIdx].toLongOrNull()) else null
                val state = if (statIdx != null && statIdx < lineParts.size) lineParts[statIdx] else null
                val time = if (timeIdx != null && timeIdx < lineParts.size) lineParts[timeIdx] else null
                val cpu = if (cpuIdx != null && cpuIdx < lineParts.size) lineParts[cpuIdx].toDoubleOrNull() else null
                val mem = if (memIdx != null && memIdx < lineParts.size) lineParts[memIdx].toDoubleOrNull() else null

                val command = if (cmdIdx < lineParts.size) {
                    lineParts.subList(cmdIdx, lineParts.size).joinToString(" ")
                } else {
                    lineParts.lastOrNull() ?: ""
                }

                processes.add(
                    ProcessInfo(
                        pid = pid,
                        user = user,
                        pri = pri,
                        ni = ni,
                        virt = virt,
                        res = res,
                        state = state,
                        cpu = cpu,
                        mem = mem,
                        time = time,
                        command = command
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse process line: $line", e)
            }
        }

        return processes
    }

    private fun formatMemKb(kb: Long?): String {
        if (kb == null || kb <= 0) return "-"
        return when {
            kb >= 1024 * 1024 -> String.format("%.1fG", kb / (1024.0 * 1024.0))
            kb >= 1024 -> "${kb / 1024}M"
            else -> "${kb}K"
        }
    }

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m ${secs}s"
            else -> "${minutes}m ${secs}s"
        }
    }
}
