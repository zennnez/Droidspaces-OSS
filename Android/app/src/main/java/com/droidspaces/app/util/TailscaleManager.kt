package com.droidspaces.app.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager for Tailscale mesh networking and Headscale control server integration.
 */
object TailscaleManager {
    private const val TAG = "TailscaleManager"
    private const val SCRIPT_PATH = "/usr/local/sbin/droidspaces-tailscale"

    private var scriptBase64: String? = null

    data class TailscaleStatus(
        val installed: Boolean = false,
        val daemonRunning: Boolean = false,
        val connected: Boolean = false,
        val ip4: String = "",
        val ip6: String = "",
        val backendState: String = "Unknown",
        val hostname: String = "",
        val authUrl: String = ""
    )

    data class CommandResult(val isSuccess: Boolean, val output: List<String>, val error: List<String>) {
        val message: String get() = (error + output).firstOrNull { it.isNotBlank() } ?: ""
    }

    fun initialize(context: Context) {
        if (scriptBase64 == null) {
            try {
                val script = context.assets.open("tailscale_setup.sh").bufferedReader().readText()
                    .replace("\r\n", "\n").replace("\r", "\n")
                scriptBase64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load tailscale_setup.sh from assets", e)
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
                Log.e(TAG, "Error running tailscale_setup.sh ${args.firstOrNull()}", e)
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
            "chmod 755 $tempPath && mv -f $tempPath $SCRIPT_PATH 2>&1'"
        try {
            val result = Shell.cmd(cmd).exec()
            CommandResult(result.isSuccess, result.out, result.err)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy tailscale_setup.sh", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown deployment error"))
        }
    }

    suspend fun isTailscaleInstalled(containerName: String): Boolean {
        val deployResult = deployControlScript(containerName)
        return deployResult.isSuccess && runScript(containerName, "check-installed").isSuccess
    }

    suspend fun installTailscale(containerName: String): CommandResult {
        val deployResult = deployControlScript(containerName)
        if (!deployResult.isSuccess) return deployResult
        return runScript(containerName, "install")
    }

    suspend fun tailscaleUp(
        containerName: String,
        loginServer: String = "",
        authKey: String = "",
        hostname: String = "",
        acceptRoutes: Boolean = true,
        enableSsh: Boolean = false,
        exitNode: String = "",
        advExitNode: Boolean = false,
        advRoutes: String = "",
        reset: Boolean = false
    ): CommandResult {
        deployControlScript(containerName)
        return runScript(
            containerName, "up",
            ContainerCommandBuilder.quote(loginServer),
            ContainerCommandBuilder.quote(authKey),
            ContainerCommandBuilder.quote(hostname),
            if (acceptRoutes) "1" else "0",
            if (enableSsh) "1" else "0",
            ContainerCommandBuilder.quote(exitNode),
            if (advExitNode) "1" else "0",
            ContainerCommandBuilder.quote(advRoutes),
            if (reset) "1" else "0"
        )
    }

    suspend fun tailscaleDown(containerName: String): CommandResult = runScript(containerName, "down")

    suspend fun ping(containerName: String, target: String = "100.100.100.100"): CommandResult =
        runScript(containerName, "ping", ContainerCommandBuilder.quote(target))

    suspend fun netcheck(containerName: String): CommandResult =
        runScript(containerName, "netcheck")

    suspend fun getStatus(containerName: String): TailscaleStatus = withContext(Dispatchers.IO) {
        val res = runScript(containerName, "status")
        val map = res.output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
        }.toMap()
        TailscaleStatus(
            installed = map["installed"] == "1",
            daemonRunning = map["daemon_running"] == "1",
            connected = map["connected"] == "1",
            ip4 = map["ip4"] ?: "",
            ip6 = map["ip6"] ?: "",
            backendState = map["backend_state"] ?: "Unknown",
            hostname = map["hostname"] ?: "",
            authUrl = map["auth_url"] ?: ""
        )
    }

    suspend fun getFullStatus(containerName: String): String = withContext(Dispatchers.IO) {
        runScript(containerName, "full-status").output.joinToString("\n")
    }

    // Headscale Docker helpers
    suspend fun listHeadscaleNodes(containerName: String): List<String> = withContext(Dispatchers.IO) {
        runScript(containerName, "headscale-nodes").output
    }

    suspend fun listHeadscaleUsers(containerName: String): List<String> = withContext(Dispatchers.IO) {
        runScript(containerName, "headscale-users").output
    }

    suspend fun createHeadscaleUser(containerName: String, username: String): CommandResult =
        runScript(containerName, "headscale-create-user", ContainerCommandBuilder.quote(username))

    suspend fun createHeadscalePreauthKey(
        containerName: String,
        username: String,
        expiration: String = "24h",
        reusable: Boolean = false
    ): CommandResult =
        runScript(
            containerName,
            "headscale-create-key",
            ContainerCommandBuilder.quote(username),
            ContainerCommandBuilder.quote(expiration),
            if (reusable) "1" else "0"
        )

    suspend fun deleteHeadscaleNode(containerName: String, nodeId: String): CommandResult =
        runScript(containerName, "headscale-delete-node", ContainerCommandBuilder.quote(nodeId))

    suspend fun getLogs(containerName: String): String = withContext(Dispatchers.IO) {
        try {
            val script = """
                if command -v journalctl >/dev/null 2>&1; then
                    journalctl -u tailscaled -u headscale -n 120 --no-pager 2>/dev/null || true
                elif [ -f /var/log/tailscaled.log ]; then
                    tail -n 120 /var/log/tailscaled.log
                else
                    tailscale status 2>&1 || true
                fi
            """.trimIndent()
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run 'echo $b64 | base64 -d | sh'"
            val res = Shell.cmd(cmd).exec()
            if (res.isSuccess && res.out.isNotEmpty()) res.out.joinToString("\n")
            else "[Tailscale] Status: ready"
        } catch (e: Exception) {
            "[Tailscale Log Error] ${e.message}"
        }
    }
}
