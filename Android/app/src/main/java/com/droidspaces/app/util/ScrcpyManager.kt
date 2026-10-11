package com.droidspaces.app.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * scrcpy loopback: runs the scrcpy CLIENT inside a container's own desktop
 * session (X11/Termux:X11 or anland/KWin -- detected, never guessed, see
 * `scrcpy-setup.sh`) connected to the SAME Android device Droidspaces itself
 * is running on.
 */
object ScrcpyManager {
    private const val TAG = "ScrcpyManager"
    private const val SCRIPT_PATH = Constants.SCRCPY_CONTROL_SCRIPT_PATH

    private var scriptBase64: String? = null

    data class DisplayInfo(
        val mode: String = "none", // "x11" | "anland" | "none"
        val display: String? = null,
        val waylandDisplay: String? = null,
        val xdgRuntimeDir: String? = null
    )

    data class ScrcpyStatus(
        val installed: Boolean = false,
        val displayMode: String = "none",
        val adbConnected: Boolean = false,
        val running: Boolean = false,
        val pid: Int? = null
    )

    data class CommandResult(val isSuccess: Boolean, val output: List<String>, val error: List<String>) {
        val message: String get() = (error + output).firstOrNull { it.isNotBlank() } ?: ""
    }

    fun initialize(context: Context) {
        if (scriptBase64 == null) {
            try {
                val script = context.assets.open("scrcpy-setup.sh").bufferedReader().readText()
                    .replace("\r\n", "\n").replace("\r", "\n")
                scriptBase64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load scrcpy-setup.sh from assets", e)
            }
        }
    }

    suspend fun runScript(containerName: String, vararg args: String): CommandResult =
        withContext(Dispatchers.IO) {
            val b64 = scriptBase64
            val argsStr = args.joinToString(" ")
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                "run 'if [ -x $SCRIPT_PATH ]; then $SCRIPT_PATH $argsStr; elif [ -n \"$b64\" ]; then echo \"$b64\" | base64 -d | sh -s -- $argsStr; fi 2>&1'"
            try {
                val result = Shell.cmd(cmd).exec()
                CommandResult(result.isSuccess, result.out, result.err)
            } catch (e: Exception) {
                Log.e(TAG, "Error running scrcpy-setup.sh ${args.firstOrNull()}", e)
                CommandResult(false, emptyList(), listOf(e.message ?: "Unknown error"))
            }
        }

    suspend fun deployControlScript(containerName: String): CommandResult = withContext(Dispatchers.IO) {
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
            Log.e(TAG, "Failed to deploy scrcpy-setup.sh", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown deployment error"))
        }
    }

    suspend fun ensureAdbTcpip(port: Int = Constants.SCRCPY_ADB_PORT): CommandResult = withContext(Dispatchers.IO) {
        try {
            val cmd = "setprop service.adb.tcp.port $port && stop adbd && start adbd"
            val result = Shell.cmd(cmd).exec()
            CommandResult(result.isSuccess, result.out, result.err)
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling adb tcpip mode", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }

    /**
     * Pre-generate the container's ADB key and authorize it into Android host's /data/misc/adb/adb_keys
     * so adb connect authenticates without user dialog prompts.
     */
    suspend fun authorizeContainerAdbKey(containerName: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val setupKeyCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                "run 'mkdir -p ~/.android /root/.android 2>/dev/null; " +
                "if [ ! -f ~/.android/adbkey.pub ] && [ ! -f /root/.android/adbkey.pub ]; then " +
                "  adb keygen ~/.android/adbkey 2>/dev/null || adb keygen /root/.android/adbkey 2>/dev/null || true; " +
                "fi; " +
                "cat ~/.android/adbkey.pub /root/.android/adbkey.pub 2>/dev/null | head -n1'"
            val keyOut = Shell.cmd(setupKeyCmd).exec().out.firstOrNull { it.isNotBlank() }
            if (!keyOut.isNullOrBlank()) {
                val cleanKey = keyOut.trim().replace("'", "")
                val appendCmd = "mkdir -p /data/misc/adb && " +
                    "touch /data/misc/adb/adb_keys && " +
                    "grep -qxF '$cleanKey' /data/misc/adb/adb_keys 2>/dev/null || echo '$cleanKey' >> /data/misc/adb/adb_keys; " +
                    "chmod 0640 /data/misc/adb/adb_keys 2>/dev/null || true; " +
                    "chown system:shell /data/misc/adb/adb_keys 2>/dev/null || true"
                Shell.cmd(appendCmd).exec()
            }
            CommandResult(true, emptyList(), emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Error authorizing container adb key", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }

    suspend fun isScrcpyInstalled(containerName: String): Boolean {
        val deployResult = deployControlScript(containerName)
        return deployResult.isSuccess && runScript(containerName, "check-installed").isSuccess
    }

    suspend fun installScrcpy(containerName: String): CommandResult {
        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) return deployResult
        return runScript(containerName, "install")
    }

    suspend fun getDisplayInfo(containerName: String): DisplayInfo = withContext(Dispatchers.IO) {
        val res = runScript(containerName, "get-display")
        val map = res.output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
        }.toMap()
        DisplayInfo(
            mode = map["mode"] ?: "none",
            display = map["display"]?.takeIf { it.isNotBlank() },
            waylandDisplay = map["wayland_display"]?.takeIf { it.isNotBlank() },
            xdgRuntimeDir = map["xdg_runtime_dir"]?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun launchScrcpy(
        container: ContainerInfo,
        adbPort: Int = Constants.SCRCPY_ADB_PORT,
        noAudio: Boolean = false,
        turnScreenOff: Boolean = false,
        stayAwake: Boolean = false,
        maxFps: Int = 0,
        extraArgs: String = ""
    ): CommandResult {
        val adbResult = ensureAdbTcpip(adbPort)
        if (!adbResult.isSuccess) {
            return CommandResult(false, emptyList(), listOf("Failed to enable adb TCP/IP mode: ${adbResult.message}"))
        }
        authorizeContainerAdbKey(container.name)
        val deployResult = deployControlScript(container.name)
        if (!deployResult.isSuccess) return deployResult
        return runScript(
            container.name, "launch",
            adbPort.toString(),
            container.scrcpyMaxSize.toString(),
            container.scrcpyBitrateKbps.toString(),
            if (noAudio) "1" else "0",
            if (turnScreenOff) "1" else "0",
            if (stayAwake) "1" else "0",
            maxFps.toString(),
            extraArgs
        )
    }

    suspend fun stopScrcpy(containerName: String): CommandResult = runScript(containerName, "stop-now")

    suspend fun getLog(containerName: String): String = withContext(Dispatchers.IO) {
        runScript(containerName, "get-log").output.joinToString("\n")
    }

    suspend fun getStatus(containerName: String): ScrcpyStatus = withContext(Dispatchers.IO) {
        val res = runScript(containerName, "status")
        val map = res.output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
        }.toMap()
        ScrcpyStatus(
            installed = map["installed"] == "1",
            displayMode = map["display_mode"] ?: "none",
            adbConnected = map["adb_connected"] == "1",
            running = map["running"] == "1",
            pid = map["pid"]?.toIntOrNull()
        )
    }
}
