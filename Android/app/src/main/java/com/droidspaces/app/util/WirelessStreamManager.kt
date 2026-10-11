package com.droidspaces.app.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wireless display -- Stream backend: mirrors the container's existing
 * XFCE/X11 session over H.264/MPEG-TS-over-TCP instead of VNC, for
 * meaningfully lower latency and bandwidth on modern hardware. VNC
 * ([WirelessDisplayManager]) is untouched and remains the compatibility
 * backend -- this is a fully separate script, service, and port, so both can
 * be configured independently even though only one runs at a time in
 * practice (see [ContainerInfo.wirelessDisplayMode]).
 *
 * Capture/encode engine (ffmpeg hardware V4L2 M2M, GStreamer w/ XDamage, or
 * ffmpeg software libx264) is chosen INSIDE the container at start time by
 * `wireless_stream.sh` itself, based on real capability detection -- see
 * [detectCapabilities] to surface that detection to the UI ahead of time.
 *
 * Known, documented limitation: unlike VNC, the raw TCP stream has no
 * built-in authentication or encryption. Keep it LAN-only; use VNC mode on
 * untrusted networks. See the script's own header comment for the full list.
 */
object WirelessStreamManager {
    private const val TAG = "WirelessStreamManager"

    private const val SERVICE_NAME = Constants.STREAM_SERVICE_NAME
    private const val SCRIPT_PATH = Constants.STREAM_CONTROL_SCRIPT_PATH
    private const val SYSTEMD_UNIT_PATH = "/etc/systemd/system/$SERVICE_NAME.service"
    private const val OPENRC_SCRIPT_PATH = "/etc/init.d/$SERVICE_NAME"

    private var scriptBase64: String? = null

    data class Capabilities(
        val ffmpegInstalled: Boolean = false,
        val gstreamerInstalled: Boolean = false,
        val hwEncoderAvailable: Boolean = false,
        val hwEncoderNode: String? = null,
        val chosenEngine: String = "none",
        val anlandActive: Boolean = false,
        val anlandSocket: String? = null
    )

    data class StreamStatus(
        val installed: Boolean = false,
        val display: String? = null,
        val running: Boolean = false,
        val pid: Int? = null,
        val engine: String? = null,
        val liveFps: String? = null,
        val liveBitrateKbps: String? = null,
        val anlandActive: Boolean = false
    )

    data class CommandResult(val isSuccess: Boolean, val output: List<String>, val error: List<String>) {
        val message: String get() = (error + output).firstOrNull { it.isNotBlank() } ?: ""
    }

    fun initialize(context: Context) {
        if (scriptBase64 == null) {
            try {
                val script = context.assets.open("wireless_stream.sh").bufferedReader().readText()
                    .replace("\r\n", "\n").replace("\r", "\n")
                scriptBase64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load wireless_stream.sh from assets", e)
            }
        }
    }

    // ── Low-level script invocation (same raw-interpolation convention and
    //    caveat as WirelessDisplayManager.runScript -- every arg here is a
    //    fixed literal, an Int, or a preset/resolution keyword we control). ──

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
                Log.e(TAG, "Error running wireless_stream.sh ${args.firstOrNull()}", e)
                CommandResult(false, emptyList(), listOf(e.message ?: "Unknown error"))
            }
        }

    private suspend fun deployControlScript(containerName: String): CommandResult = withContext(Dispatchers.IO) {
        val b64 = scriptBase64
            ?: return@withContext CommandResult(false, emptyList(), listOf("Script not initialized"))
        val dir = SCRIPT_PATH.substringBeforeLast('/')
        val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
            "run 'mkdir -p $dir && echo \"$b64\" | base64 -d > $SCRIPT_PATH && chmod 755 $SCRIPT_PATH 2>&1'"
        try {
            val result = Shell.cmd(cmd).exec()
            CommandResult(result.isSuccess, result.out, result.err)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy wireless_stream.sh", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown deployment error"))
        }
    }

    // ── Capability detection ────────────────────────────────────────────────

    /**
     * Real, on-device capability probe -- never assumed. Detects whether
     * ffmpeg/GStreamer are installed, and separately whether a genuine V4L2
     * memory-to-memory H.264 encoder device node is reachable (only possible
     * if "GPU/Hardware Access" is enabled on the container AND the vendor
     * happens to expose one, which is uncommon on stock Android).
     */
    suspend fun detectCapabilities(containerName: String): Capabilities = withContext(Dispatchers.IO) {
        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) return@withContext Capabilities()
        val result = runScript(containerName, "detect-caps")
        if (!result.isSuccess) return@withContext Capabilities()
        val map = result.output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
        }.toMap()

        Capabilities(
            ffmpegInstalled = map["ffmpeg_installed"] == "1",
            gstreamerInstalled = map["gstreamer_installed"] == "1",
            hwEncoderAvailable = map["hw_encoder_available"] == "1",
            hwEncoderNode = map["hw_encoder_node"]?.takeIf { it.isNotBlank() },
            chosenEngine = map["chosen_engine"] ?: "none",
            anlandActive = map["anland_active"] == "1",
            anlandSocket = map["anland_socket"]?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun installDeps(containerName: String): CommandResult {
        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) return deployResult
        return runScript(containerName, "install")
    }

    // ── Service unit deployment (identical shape to WirelessDisplayManager,
    //    intentionally not shared/abstracted -- keeping them independent means
    //    a bug in one backend's service wiring can't affect the other). ──────

    private fun systemdUnitContent(port: Int, lanOnly: Boolean, fps: Int, bitrateKbps: Int, preset: String, resolution: String, engine: String = "auto"): String = """
        [Unit]
        Description=Droidspaces Wireless Display Stream (H.264 mirror of the existing X11/Anland session)
        After=multi-user.target
        EnvironmentFile=-/etc/environment
        EnvironmentFile=-/run/droidspaces.env

        [Service]
        Type=simple
        EnvironmentFile=-/etc/environment
        EnvironmentFile=-/run/droidspaces.env
        ExecStart=$SCRIPT_PATH watch $port ${if (lanOnly) 1 else 0} $fps $bitrateKbps $preset $resolution $engine
        ExecStop=$SCRIPT_PATH stop-now
        Restart=on-failure
        RestartSec=3

        [Install]
        WantedBy=multi-user.target
    """.trimIndent() + "\n"

    private fun openRcScriptContent(port: Int, lanOnly: Boolean, fps: Int, bitrateKbps: Int, preset: String, resolution: String, engine: String = "auto"): String = """
        #!/sbin/openrc-run
        description="Droidspaces Wireless Display Stream (H.264 mirror of the existing X11 session)"

        command="$SCRIPT_PATH"
        command_args="watch $port ${if (lanOnly) 1 else 0} $fps $bitrateKbps $preset $resolution $engine"
        command_background="true"
        pidfile="/run/droidspaces-stream/service.pid"
        stopsig="TERM"

        depend() {
        	need localmount
        	after bootmisc
        }

        stop_pre() {
        	$SCRIPT_PATH stop-now
        }
    """.trimIndent() + "\n"

    private suspend fun installServiceUnit(
        containerName: String, initSystem: WirelessDisplayManager.InitSystem,
        port: Int, lanOnly: Boolean, fps: Int, bitrateKbps: Int, preset: String, resolution: String, engine: String = "auto"
    ): Boolean = withContext(Dispatchers.IO) {
        when (initSystem) {
            WirelessDisplayManager.InitSystem.SYSTEMD -> {
                val content = systemdUnitContent(port, lanOnly, fps, bitrateKbps, preset, resolution, engine)
                val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
                val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                    "run 'echo $b64 | base64 -d > $SYSTEMD_UNIT_PATH && systemctl daemon-reload 2>&1'"
                Shell.cmd(cmd).exec().isSuccess
            }
            WirelessDisplayManager.InitSystem.OPENRC -> {
                val content = openRcScriptContent(port, lanOnly, fps, bitrateKbps, preset, resolution, engine)
                val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
                val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                    "run 'echo $b64 | base64 -d > $OPENRC_SCRIPT_PATH && chmod 755 $OPENRC_SCRIPT_PATH'"
                Shell.cmd(cmd).exec().isSuccess
            }
            WirelessDisplayManager.InitSystem.NONE -> false
        }
    }

    private suspend fun removeServiceUnit(containerName: String, initSystem: WirelessDisplayManager.InitSystem): Boolean =
        withContext(Dispatchers.IO) {
            val path = when (initSystem) {
                WirelessDisplayManager.InitSystem.SYSTEMD -> SYSTEMD_UNIT_PATH
                WirelessDisplayManager.InitSystem.OPENRC -> OPENRC_SCRIPT_PATH
                WirelessDisplayManager.InitSystem.NONE -> return@withContext true
            }
            val reload = if (initSystem == WirelessDisplayManager.InitSystem.SYSTEMD) "&& systemctl daemon-reload 2>&1" else ""
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                "run 'rm -f $path $reload'"
            Shell.cmd(cmd).exec().isSuccess
        }

    // ── High-level orchestration ────────────────────────────────────────────

    suspend fun enable(
        containerName: String, port: Int, lanOnly: Boolean,
        fps: Int, bitrateKbps: Int, preset: String, resolution: String, engine: String = "auto"
    ): CommandResult = withContext(Dispatchers.IO) {
        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) {
            return@withContext CommandResult(false, deployResult.output, listOf("Failed to deploy the wireless-stream control script", *deployResult.error.toTypedArray()))
        }

        val caps = detectCapabilities(containerName)
        if (caps.chosenEngine == "none") {
            val installResult = installDeps(containerName)
            if (!installResult.isSuccess) {
                return@withContext CommandResult(false, emptyList(), listOf("Failed to install streaming dependencies: ${installResult.message}"))
            }
        }

        val initSystem = WirelessDisplayManager.detectInitSystem(containerName)
        if (initSystem == WirelessDisplayManager.InitSystem.NONE) {
            // Standalone background mode fallback
            return@withContext runScript(
                containerName, "service", "start",
                port.toString(), if (lanOnly) "1" else "0", fps.toString(), bitrateKbps.toString(), preset, resolution, engine
            )
        }

        if (!installServiceUnit(containerName, initSystem, port, lanOnly, fps, bitrateKbps, preset, resolution, engine)) {
            return@withContext CommandResult(false, emptyList(), listOf("Failed to install the wireless-stream service"))
        }

        val startResult = when (initSystem) {
            WirelessDisplayManager.InitSystem.SYSTEMD -> ContainerSystemdManager.startService(containerName, "$SERVICE_NAME.service").let {
                CommandResult(it.isSuccess, it.output, it.error)
            }
            WirelessDisplayManager.InitSystem.OPENRC -> {
                ContainerOpenRCManager.enableService(containerName, SERVICE_NAME)
                ContainerOpenRCManager.startService(containerName, SERVICE_NAME).let {
                    CommandResult(it.isSuccess, it.output, it.error)
                }
            }
            else -> runScript(
                containerName, "service", "start",
                port.toString(), if (lanOnly) "1" else "0", fps.toString(), bitrateKbps.toString(), preset, resolution, engine
            )
        }

        if (initSystem == WirelessDisplayManager.InitSystem.SYSTEMD && startResult.isSuccess) {
            ContainerSystemdManager.enableService(containerName, "$SERVICE_NAME.service")
        }

        startResult
    }

    suspend fun disable(containerName: String): CommandResult = withContext(Dispatchers.IO) {
        runScript(containerName, "stop-now")

        val initSystem = WirelessDisplayManager.detectInitSystem(containerName)
        val result = when (initSystem) {
            WirelessDisplayManager.InitSystem.SYSTEMD -> {
                ContainerSystemdManager.stopService(containerName, "$SERVICE_NAME.service")
                ContainerSystemdManager.disableService(containerName, "$SERVICE_NAME.service").let {
                    CommandResult(it.isSuccess, it.output, it.error)
                }
            }
            WirelessDisplayManager.InitSystem.OPENRC -> {
                ContainerOpenRCManager.stopService(containerName, SERVICE_NAME)
                ContainerOpenRCManager.disableService(containerName, SERVICE_NAME).let {
                    CommandResult(it.isSuccess, it.output, it.error)
                }
            }
            WirelessDisplayManager.InitSystem.NONE -> runScript(containerName, "service", "stop")
        }
        removeServiceUnit(containerName, initSystem)
        result
    }

    suspend fun restart(
        containerName: String,
        port: Int = 8554,
        lanOnly: Boolean = true,
        fps: Int = 30,
        bitrateKbps: Int = 4000,
        preset: String = "low-latency",
        resolution: String = "native",
        engine: String = "auto"
    ): CommandResult = withContext(Dispatchers.IO) {
        val initSystem = WirelessDisplayManager.detectInitSystem(containerName)
        // Ensure service unit is up to date with new engine setting
        if (initSystem != WirelessDisplayManager.InitSystem.NONE) {
            installServiceUnit(containerName, initSystem, port, lanOnly, fps, bitrateKbps, preset, resolution, engine)
        }
        when (initSystem) {
            WirelessDisplayManager.InitSystem.SYSTEMD -> ContainerSystemdManager.restartService(containerName, "$SERVICE_NAME.service").let {
                CommandResult(it.isSuccess, it.output, it.error)
            }
            WirelessDisplayManager.InitSystem.OPENRC -> ContainerOpenRCManager.restartService(containerName, SERVICE_NAME).let {
                CommandResult(it.isSuccess, it.output, it.error)
            }
            WirelessDisplayManager.InitSystem.NONE -> runScript(
                containerName, "service", "restart",
                port.toString(), if (lanOnly) "1" else "0", fps.toString(), bitrateKbps.toString(), preset, resolution, engine
            )
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

    // ── Status / connection address ─────────────────────────────────────────

    suspend fun getStatus(containerName: String): StreamStatus = withContext(Dispatchers.IO) {
        val result = runScript(containerName, "status")
        if (!result.isSuccess) return@withContext StreamStatus()
        val map = result.output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
        }.toMap()

        StreamStatus(
            installed = map["installed"] == "1",
            display = map["display"]?.takeIf { it.isNotBlank() },
            running = map["running"] == "1",
            pid = map["pid"]?.toIntOrNull(),
            engine = map["engine"]?.takeIf { it.isNotBlank() },
            liveFps = map["live_fps"]?.takeIf { it.isNotBlank() },
            liveBitrateKbps = map["live_bitrate_kbps"]?.takeIf { it.isNotBlank() },
            anlandActive = map["anland_active"] == "1"
        )
    }

    /** Same phone-LAN-IP-first lookup as VNC's [WirelessDisplayManager.getConnectionAddress]. */
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
