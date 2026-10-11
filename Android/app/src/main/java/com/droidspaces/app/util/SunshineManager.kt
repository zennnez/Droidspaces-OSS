package com.droidspaces.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sunshine (Moonlight game and desktop streaming server) manager.
 * Runs Sunshine inside the container to stream low-latency video and audio
 * to Moonlight clients (Android, Windows, macOS, Linux, iOS, TV, etc.).
 */
object SunshineManager {
    private const val TAG = "SunshineManager"
    private const val SCRIPT_PATH = Constants.SUNSHINE_CONTROL_SCRIPT_PATH

    private var scriptBase64: String? = null

    data class SunshineStatus(
        val installed: Boolean = false,
        val running: Boolean = false,
        val pid: Int? = null,
        val version: String = "",
        val webPort: Int = Constants.DEFAULT_SUNSHINE_WEB_PORT,
        val streamPort: Int = Constants.DEFAULT_SUNSHINE_STREAM_PORT
    )

    data class CommandResult(val isSuccess: Boolean, val output: List<String>, val error: List<String>) {
        val message: String get() = (error + output).firstOrNull { it.isNotBlank() } ?: ""
    }

    fun initialize(context: Context) {
        if (scriptBase64 == null) {
            try {
                val script = context.assets.open("sunshine-setup.sh").bufferedReader().readText()
                    .replace("\r\n", "\n").replace("\r", "\n")
                scriptBase64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sunshine-setup.sh from assets", e)
            }
        }
    }

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
                Log.e(TAG, "Error running sunshine-setup.sh ${args.firstOrNull()}", e)
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
            Log.e(TAG, "Failed to deploy sunshine-setup.sh", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown deployment error"))
        }
    }

    suspend fun getStatus(containerName: String): SunshineStatus = withContext(Dispatchers.IO) {
        val res = runScript(containerName, "status")
        val map = res.output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
        }.toMap()
        SunshineStatus(
            installed = map["installed"] == "1",
            running = map["running"] == "1",
            pid = map["pid"]?.toIntOrNull(),
            version = map["version"] ?: "",
            webPort = map["web_port"]?.toIntOrNull() ?: Constants.DEFAULT_SUNSHINE_WEB_PORT,
            streamPort = map["stream_port"]?.toIntOrNull() ?: Constants.DEFAULT_SUNSHINE_STREAM_PORT
        )
    }

    suspend fun installSunshine(containerName: String): CommandResult {
        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) return deployResult
        return runScript(containerName, "install")
    }

    suspend fun startSunshine(containerName: String): CommandResult {
        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) return deployResult
        return runScript(containerName, "launch")
    }

    suspend fun stopSunshine(containerName: String): CommandResult = runScript(containerName, "stop-now")

    suspend fun restartSunshine(containerName: String): CommandResult = runScript(containerName, "restart")

    suspend fun setCredentials(containerName: String, user: String, pass: String): CommandResult {
        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) return deployResult
        return runScript(containerName, "set-creds", ContainerCommandBuilder.quote(user), ContainerCommandBuilder.quote(pass))
    }

    suspend fun getLogs(containerName: String): String = withContext(Dispatchers.IO) {
        runScript(containerName, "get-log").output.joinToString("\n")
    }

    suspend fun getConnectionIp(containerName: String, context: Context): String = withContext(Dispatchers.IO) {
        val lanIp = ContainerManager.getPhoneLanIpAddress(context)
        if (!lanIp.isNullOrBlank()) {
            return@withContext lanIp
        }
        val cmd = ContainerCommandBuilder.buildGetIpCommand(containerName)
        val result = Shell.cmd(cmd).exec()
        val ip = result.out.joinToString(" ").trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
        ip ?: "127.0.0.1"
    }

    fun launchMoonlightApp(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage("com.limelight")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                // Open Play Store or Moonlight web
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.limelight")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
                true
            }
        } catch (e: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://moonlight-stream.org/")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
