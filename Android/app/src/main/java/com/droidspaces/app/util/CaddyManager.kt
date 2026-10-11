package com.droidspaces.app.util

import android.content.Context
import android.util.Base64
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager for Caddy Reverse Proxy inside Droidspaces container.
 * Supports both Host and Docker deployment modes.
 */
object CaddyManager {

    data class CaddyStatus(
        val mode: String = "none", // "docker", "host", "none"
        val isInstalled: Boolean = false,
        val isRunning: Boolean = false,
        val version: String = "",
        val ports: String = "",
        val configPath: String = "/etc/caddy/Caddyfile"
    )

    data class CommandResult(
        val isSuccess: Boolean,
        val output: List<String>,
        val error: String
    )

    data class ValidationResult(
        val isValid: Boolean,
        val message: String
    )

    fun initialize(context: Context) {
        // Assets are deployed during container start/setup
    }

    private suspend fun runScript(containerName: String, vararg args: String): CommandResult = withContext(Dispatchers.IO) {
        val quotedName = ContainerCommandBuilder.quote(containerName)
        val argsStr = args.joinToString(" ") { ContainerCommandBuilder.quote(it) }
        val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=$quotedName run '/run/droidspaces/caddy_setup.sh $argsStr 2>&1'"

        val res = Shell.cmd(cmd).exec()
        val allOut = res.out + res.err
        CommandResult(
            isSuccess = res.isSuccess,
            output = res.out,
            error = if (!res.isSuccess) allOut.firstOrNull { it.isNotBlank() } ?: "Command failed" else ""
        )
    }

    suspend fun getStatus(containerName: String): CaddyStatus = withContext(Dispatchers.IO) {
        val res = runScript(containerName, "status")
        val map = res.output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
        }.toMap()
        CaddyStatus(
            mode = map["mode"] ?: "none",
            isInstalled = map["installed"] == "1",
            isRunning = map["running"] == "1",
            version = map["version"] ?: "",
            ports = map["ports"] ?: "",
            configPath = map["config_path"] ?: "/etc/caddy/Caddyfile"
        )
    }

    suspend fun getCaddyfile(containerName: String): String = withContext(Dispatchers.IO) {
        val res = runScript(containerName, "get-config")
        if (res.output.isNotEmpty()) {
            res.output.joinToString("\n")
        } else {
            ""
        }
    }

    suspend fun validateCaddyfile(containerName: String, content: String): ValidationResult = withContext(Dispatchers.IO) {
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val res = runScript(containerName, "validate", b64)
        val text = res.output.joinToString("\n")
        if (text.contains("VALID") && !text.contains("INVALID")) {
            ValidationResult(true, "Configuration syntax is valid ✓")
        } else {
            ValidationResult(false, text.removePrefix("INVALID:").trim())
        }
    }

    suspend fun saveAndReloadCaddyfile(containerName: String, content: String): CommandResult = withContext(Dispatchers.IO) {
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        runScript(containerName, "save-config", b64)
    }

    suspend fun controlService(containerName: String, action: String): CommandResult =
        runScript(containerName, "service", action)

    suspend fun installHost(containerName: String): CommandResult =
        runScript(containerName, "install-host")

    suspend fun installDocker(containerName: String): CommandResult =
        runScript(containerName, "install-docker")

    suspend fun getLogs(containerName: String, lines: Int = 100): List<String> = withContext(Dispatchers.IO) {
        val res = runScript(containerName, "logs", lines.toString())
        res.output
    }

    fun getPresetSnippets(): List<Pair<String, String>> {
        return listOf(
            "Reverse Proxy to Port" to """
# Reverse Proxy to Port (e.g. Code Server or Web App)
:80 {
    reverse_proxy localhost:8080
}
            """.trimIndent(),

            "Static Web Server" to """
# Static HTML/Web Asset Server
:80 {
    root * /var/www/html
    file_server
}
            """.trimIndent(),

            "OmniRoute & Claude Code Proxy" to """
# OmniRoute AI Gateway Proxy on port 80
:80 {
    reverse_proxy localhost:20128
}
            """.trimIndent(),

            "Multi-Service Routing" to """
# Multi-service route by path or subdomain
:80 {
    handle /code/* {
        reverse_proxy localhost:8080
    }
    handle /files/* {
        reverse_proxy localhost:8085
    }
    handle {
        respond "Droidspaces Gateway Active" 200
    }
}
            """.trimIndent(),

            "Custom Domain with Automatic HTTPS" to """
# Automatic TLS cert with Let's Encrypt
yourdomain.com {
    reverse_proxy localhost:8080
}
            """.trimIndent()
        )
    }
}
