package com.droidspaces.app.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wireless display support: mirrors the container's EXISTING XFCE/X11 session
 * (already bridged into the container by Termux:X11 as DISPLAY=:N) over VNC
 * on the LAN, using x11vnc. This manager never starts XFCE, never touches
 * Termux:X11, and never creates a second X session -- see
 * `wireless_display.sh` (deployed into the container) for the actual
 * DISPLAY-discovery and start/stop logic.
 *
 * Lifecycle model: a small watcher loop runs *inside* the container as a
 * systemd/OpenRC service (so it survives container restarts on its own,
 * independent of the Android app being open) and starts/stops x11vnc as the
 * X11 socket for the discovered DISPLAY appears/disappears. This manager's
 * job is deploying that watcher, wiring it into whichever init system the
 * container has, and giving the UI install/enable/disable/status/address.
 *
 * The VNC password is never stored on the Android side (encrypted or
 * otherwise) -- it only lives transiently in Compose state while the user
 * types it, then is streamed once into the container and converted to
 * x11vnc's own obscured on-disk format (mode 600, root-only). That's the
 * only at-rest copy, which is a stronger posture than keeping a second copy
 * here just to save the user retyping it.
 */
object WirelessDisplayManager {
    private const val TAG = "WirelessDisplayManager"

    private const val SERVICE_NAME = Constants.VNC_SERVICE_NAME
    private const val SCRIPT_PATH = Constants.VNC_CONTROL_SCRIPT_PATH
    private const val SYSTEMD_UNIT_PATH = "/etc/systemd/system/$SERVICE_NAME.service"
    private const val OPENRC_SCRIPT_PATH = "/etc/init.d/$SERVICE_NAME"

    private var scriptBase64: String? = null

    enum class InitSystem { SYSTEMD, OPENRC, NONE }

    data class VncStatus(
        val installed: Boolean = false,
        val display: String? = null,
        val running: Boolean = false,
        val pid: Int? = null,
        val hasPassword: Boolean = false
    )

    data class CommandResult(val isSuccess: Boolean, val output: List<String>, val error: List<String>) {
        val message: String get() = (error + output).firstOrNull { it.isNotBlank() } ?: ""
    }

    fun initialize(context: Context) {
        if (scriptBase64 == null) {
            try {
                val script = context.assets.open("wireless_display.sh").bufferedReader().readText()
                    .replace("\r\n", "\n").replace("\r", "\n")
                scriptBase64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load wireless_display.sh from assets", e)
            }
        }
    }

    // ── Low-level script invocation ─────────────────────────────────────────

    /** Invoke the validated control script from its stable path or base64 fallback. */
    private suspend fun runScript(containerName: String, vararg args: String): CommandResult =
        withContext(Dispatchers.IO) {
            val b64 = scriptBase64
            val argsStr = args.joinToString(" ")
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                "run 'if [ -x $SCRIPT_PATH ]; then $SCRIPT_PATH $argsStr; elif [ -n \"$b64\" ]; then echo \"$b64\" | base64 -d | sh -s -- $argsStr; fi 2>&1'"
            try {
                val result = Shell.cmd(cmd).exec()
                CommandResult(result.isSuccess, result.out, result.err)
            } catch (e: Exception) {
                Log.e(TAG, "Error running wireless_display.sh ${args.firstOrNull()}", e)
                CommandResult(false, emptyList(), listOf(e.message ?: "Unknown error"))
            }
        }

    /** Atomically deploy and syntax-check the control script before replacing a working copy. */
    private suspend fun deployControlScript(containerName: String): CommandResult = withContext(Dispatchers.IO) {
        val b64 = scriptBase64
            ?: return@withContext CommandResult(false, emptyList(), listOf("Script not initialized"))
        val dir = SCRIPT_PATH.substringBeforeLast('/')
        val tempPath = "$SCRIPT_PATH.tmp"
        val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
            "run 'mkdir -p $dir && echo \"$b64\" | base64 -d > $tempPath && " +
            "sh -n $tempPath && chmod 755 $tempPath && mv -f $tempPath $SCRIPT_PATH 2>&1'"
        try {
            val result = Shell.cmd(cmd).exec()
            CommandResult(result.isSuccess, result.out, result.err)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy wireless_display.sh", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown deployment error"))
        }
    }

    // ── Install ──────────────────────────────────────────────────────────────

    suspend fun isX11vncInstalled(containerName: String): Boolean {
        val deployResult = deployControlScript(containerName)
        return deployResult.isSuccess && runScript(containerName, "check-installed").isSuccess
    }

    suspend fun installX11vnc(containerName: String): CommandResult {
        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) return deployResult
        return runScript(containerName, "install")
    }

    // ── Init-system detection (reuses the existing per-init managers) ─────────

    suspend fun detectInitSystem(containerName: String): InitSystem = when {
        ContainerSystemdManager.isSystemdAvailable(containerName) -> InitSystem.SYSTEMD
        ContainerOpenRCManager.isOpenRCAvailable(containerName) -> InitSystem.OPENRC
        else -> InitSystem.NONE
    }

    // ── Service unit deployment ────────────────────────────────────────────

    private fun systemdUnitContent(port: Int, lanOnly: Boolean): String = """
        [Unit]
        Description=Droidspaces Wireless Display (x11vnc mirror of the existing X11 session)
        After=multi-user.target

        [Service]
        Type=simple
        ExecStart=$SCRIPT_PATH watch $port ${if (lanOnly) 1 else 0}
        ExecStop=$SCRIPT_PATH stop-now
        Restart=on-failure
        RestartSec=3

        [Install]
        WantedBy=multi-user.target
    """.trimIndent() + "\n"

    private fun openRcScriptContent(port: Int, lanOnly: Boolean): String = """
        #!/sbin/openrc-run
        description="Droidspaces Wireless Display (x11vnc mirror of the existing X11 session)"

        command="$SCRIPT_PATH"
        command_args="watch $port ${if (lanOnly) 1 else 0}"
        command_background="true"
        pidfile="/run/droidspaces-vnc/service.pid"
        stopsig="TERM"

        depend() {
        	need localmount
        	after bootmisc
        }

        stop_pre() {
        	$SCRIPT_PATH stop-now
        }
    """.trimIndent() + "\n"

    /** Write (or rewrite) the service unit for whichever init system is active, then reload/enable it. */
    private suspend fun installServiceUnit(containerName: String, initSystem: InitSystem, port: Int, lanOnly: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            when (initSystem) {
                InitSystem.SYSTEMD -> {
                    val content = systemdUnitContent(port, lanOnly)
                    val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
                    val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                        "run 'echo $b64 | base64 -d > $SYSTEMD_UNIT_PATH && systemctl daemon-reload 2>&1'"
                    Shell.cmd(cmd).exec().isSuccess
                }
                InitSystem.OPENRC -> {
                    val content = openRcScriptContent(port, lanOnly)
                    val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
                    val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                        "run 'echo $b64 | base64 -d > $OPENRC_SCRIPT_PATH && chmod 755 $OPENRC_SCRIPT_PATH'"
                    Shell.cmd(cmd).exec().isSuccess
                }
                InitSystem.NONE -> false
            }
        }

    private suspend fun removeServiceUnit(containerName: String, initSystem: InitSystem): Boolean =
        withContext(Dispatchers.IO) {
            val path = when (initSystem) {
                InitSystem.SYSTEMD -> SYSTEMD_UNIT_PATH
                InitSystem.OPENRC -> OPENRC_SCRIPT_PATH
                InitSystem.NONE -> return@withContext true
            }
            val reload = if (initSystem == InitSystem.SYSTEMD) "&& systemctl daemon-reload 2>&1" else ""
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                "run 'rm -f $path $reload'"
            Shell.cmd(cmd).exec().isSuccess
        }

    // ── High-level orchestration ────────────────────────────────────────────

    /**
     * Full enable flow: install x11vnc if missing, deploy the control script
     * and watcher service for whichever init system is present, then start it.
     * Requires a password to already be set (see [setPassword]) -- the watcher
     * refuses to start x11vnc without one.
     */
    suspend fun enable(containerName: String, port: Int, lanOnly: Boolean): CommandResult = withContext(Dispatchers.IO) {
        if (!isX11vncInstalled(containerName)) {
            val installResult = installX11vnc(containerName)
            if (!installResult.isSuccess) {
                return@withContext CommandResult(false, emptyList(), listOf("Failed to install x11vnc: ${installResult.message}"))
            }
        }

        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) {
            return@withContext CommandResult(false, deployResult.output, listOf("Failed to deploy the wireless-display control script", *deployResult.error.toTypedArray()))
        }

        val initSystem = detectInitSystem(containerName)
        if (initSystem == InitSystem.NONE) {
            // Standalone background mode fallback
            return@withContext runScript(containerName, "service", "start", port.toString(), if (lanOnly) "1" else "0")
        }

        if (!installServiceUnit(containerName, initSystem, port, lanOnly)) {
            return@withContext CommandResult(false, emptyList(), listOf("Failed to install the wireless-display service"))
        }

        val startResult = when (initSystem) {
            InitSystem.SYSTEMD -> ContainerSystemdManager.startService(containerName, "$SERVICE_NAME.service").let {
                CommandResult(it.isSuccess, it.output, it.error)
            }
            InitSystem.OPENRC -> {
                ContainerOpenRCManager.enableService(containerName, SERVICE_NAME)
                ContainerOpenRCManager.startService(containerName, SERVICE_NAME).let {
                    CommandResult(it.isSuccess, it.output, it.error)
                }
            }
            else -> runScript(containerName, "service", "start", port.toString(), if (lanOnly) "1" else "0")
        }

        if (initSystem == InitSystem.SYSTEMD && startResult.isSuccess) {
            ContainerSystemdManager.enableService(containerName, "$SERVICE_NAME.service")
        }

        startResult
    }

    /** Stop and disable the watcher service. Leaves x11vnc installed (disabling is not uninstalling). */
    suspend fun disable(containerName: String): CommandResult = withContext(Dispatchers.IO) {
        // Best-effort immediate stop even if the service unit is somehow gone.
        runScript(containerName, "stop-now")

        val initSystem = detectInitSystem(containerName)
        val result = when (initSystem) {
            InitSystem.SYSTEMD -> {
                ContainerSystemdManager.stopService(containerName, "$SERVICE_NAME.service")
                ContainerSystemdManager.disableService(containerName, "$SERVICE_NAME.service").let {
                    CommandResult(it.isSuccess, it.output, it.error)
                }
            }
            InitSystem.OPENRC -> {
                ContainerOpenRCManager.stopService(containerName, SERVICE_NAME)
                ContainerOpenRCManager.disableService(containerName, SERVICE_NAME).let {
                    CommandResult(it.isSuccess, it.output, it.error)
                }
            }
            InitSystem.NONE -> runScript(containerName, "service", "stop")
        }
        removeServiceUnit(containerName, initSystem)
        result
    }

    suspend fun restart(containerName: String, port: Int = 5900, lanOnly: Boolean = true): CommandResult = withContext(Dispatchers.IO) {
        when (detectInitSystem(containerName)) {
            InitSystem.SYSTEMD -> ContainerSystemdManager.restartService(containerName, "$SERVICE_NAME.service").let {
                CommandResult(it.isSuccess, it.output, it.error)
            }
            InitSystem.OPENRC -> ContainerOpenRCManager.restartService(containerName, SERVICE_NAME).let {
                CommandResult(it.isSuccess, it.output, it.error)
            }
            InitSystem.NONE -> runScript(containerName, "service", "restart", port.toString(), if (lanOnly) "1" else "0")
        }
    }

    suspend fun getLogs(containerName: String): String = withContext(Dispatchers.IO) {
        val result = runScript(containerName, "get-log")
        if (result.isSuccess && result.output.isNotEmpty()) {
            result.output.joinToString("\n")
        } else {
            result.error.joinToString("\n").ifBlank { "No logs available" }
        }
    }

    // ── Password ────────────────────────────────────────────────────────────

    /**
     * Stream [password] into the container and convert it to x11vnc's own
     * obscured passwd file. The plaintext is base64-encoded only to survive
     * the single-quoted shell payload intact (same convention the rest of the
     * app uses for content transfer) -- it is never written to disk as-is on
     * either side, and this function does not retain a copy after returning.
     */
    suspend fun setPassword(containerName: String, password: String): CommandResult = withContext(Dispatchers.IO) {
        if (!isX11vncInstalled(containerName)) {
            val installResult = installX11vnc(containerName)
            if (!installResult.isSuccess) {
                return@withContext CommandResult(false, emptyList(), listOf("Failed to install x11vnc: ${installResult.message}"))
            }
        }
        val b64 = Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP)
        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) return@withContext deployResult
        return@withContext runScript(containerName, "set-password", b64)
    }

    suspend fun clearPassword(containerName: String): CommandResult = runScript(containerName, "clear-password")

    // ── Status / connection address ─────────────────────────────────────────

    suspend fun getStatus(containerName: String): VncStatus = withContext(Dispatchers.IO) {
        val result = runScript(containerName, "status")
        if (!result.isSuccess) return@withContext VncStatus()
        val map = result.output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
        }.toMap()

        VncStatus(
            installed = map["installed"] == "1",
            display = map["display"]?.takeIf { it.isNotBlank() },
            running = map["running"] == "1",
            pid = map["pid"]?.toIntOrNull(),
            hasPassword = map["has_password"] == "1"
        )
    }

    /**
     * The address a VNC client should connect to: the phone's LAN-reachable
     * IPv4 address (or container IP in bridged mode) paired with the configured port,
     * e.g. "192.168.1.50:5900". Returns null if no IPv4 address is available yet.
     */
    suspend fun getConnectionAddress(containerName: String, port: Int, context: Context? = null): String? = withContext(Dispatchers.IO) {
        try {
            if (context != null) {
                val lanIp = ContainerManager.getPhoneLanIpAddress(context)
                if (!lanIp.isNullOrBlank()) {
                    return@withContext "$lanIp:$port"
                }
            }
            val cmd = ContainerCommandBuilder.buildGetIpCommand(containerName)
            val result = Shell.cmd(cmd).exec()
            val ip = result.out.joinToString(" ").trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
            if (ip.isNullOrBlank()) null else "$ip:$port"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connection address", e)
            null
        }
    }
}
