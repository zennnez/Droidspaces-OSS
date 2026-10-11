package com.droidspaces.app.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager for desktop and developer programs installation inside the container.
 */
object ProgramsManager {
    private const val TAG = "ProgramsManager"
    private const val SCRIPT_PATH = "/usr/local/sbin/droidspaces-programs"

    private var scriptBase64: String? = null

    data class ProgramInfo(
        val id: String,
        val name: String,
        val description: String,
        val category: String, // "Web Services" | "Development" | "Productivity" | "Media & Web"
        val isInstalled: Boolean = false,
        val webPort: Int? = null,
        val isService: Boolean = false,
        val isRunning: Boolean = false
    )

    data class ProgramStatus(
        val codeServer: Boolean = false,
        val antigravity: Boolean = false,
        val libreoffice: Boolean = false,
        val chromium: Boolean = false,
        val firefox: Boolean = false,
        val gimp: Boolean = false,
        val vlc: Boolean = false,
        val caddy: Boolean = false,
        val transmission: Boolean = false,
        val jupyter: Boolean = false,
        val filebrowser: Boolean = false,
        val ollama: Boolean = false,
        val omniroute: Boolean = false,
        val xfce: Boolean = false,
        // Service running status
        val svcCodeServer: Boolean = false,
        val svcCaddy: Boolean = false,
        val svcJupyter: Boolean = false,
        val svcFilebrowser: Boolean = false,
        val svcTransmission: Boolean = false,
        val svcOllama: Boolean = false,
        val svcOmniroute: Boolean = false
    )

    data class CommandResult(val isSuccess: Boolean, val output: List<String>, val error: List<String>) {
        val message: String get() = (error + output).firstOrNull { it.isNotBlank() } ?: ""
    }

    fun initialize(context: Context) {
        if (scriptBase64 == null) {
            try {
                val script = context.assets.open("programs_setup.sh").bufferedReader().readText()
                    .replace("\r\n", "\n").replace("\r", "\n")
                scriptBase64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load programs_setup.sh from assets", e)
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
                Log.e(TAG, "Error running programs_setup.sh ${args.firstOrNull()}", e)
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
            Log.e(TAG, "Failed to deploy programs_setup.sh", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown deployment error"))
        }
    }

    suspend fun getStatus(containerName: String): ProgramStatus = withContext(Dispatchers.IO) {
        val res = runScript(containerName, "status")
        val map = res.output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to (line.substring(idx + 1) == "1") else null
        }.toMap()
        ProgramStatus(
            codeServer = map["code_server"] == true,
            antigravity = map["antigravity"] == true,
            libreoffice = map["libreoffice"] == true,
            chromium = map["chromium"] == true,
            firefox = map["firefox"] == true,
            gimp = map["gimp"] == true,
            vlc = map["vlc"] == true,
            caddy = map["caddy"] == true,
            transmission = map["transmission"] == true,
            jupyter = map["jupyter"] == true,
            filebrowser = map["filebrowser"] == true,
            ollama = map["ollama"] == true,
            omniroute = map["omniroute"] == true,
            xfce = map["xfce"] == true,
            svcCodeServer = map["svc_code_server"] == true,
            svcCaddy = map["svc_caddy"] == true,
            svcJupyter = map["svc_jupyter"] == true,
            svcFilebrowser = map["svc_filebrowser"] == true,
            svcTransmission = map["svc_transmission"] == true,
            svcOllama = map["svc_ollama"] == true,
            svcOmniroute = map["svc_omniroute"] == true
        )
    }

    suspend fun startService(containerName: String, serviceId: String): CommandResult =
        runScript(containerName, "service-start", ContainerCommandBuilder.quote(serviceId))

    suspend fun stopService(containerName: String, serviceId: String): CommandResult =
        runScript(containerName, "service-stop", ContainerCommandBuilder.quote(serviceId))

    fun getProgramCatalog(status: ProgramStatus): List<ProgramInfo> {
        return listOf(
            // Web Services & Servers
            ProgramInfo(
                id = "code_server",
                name = "Code Server (VS Code)",
                description = "Full VS Code in your browser or WebView with extensions support",
                category = "Web Services",
                isInstalled = status.codeServer,
                webPort = 8080,
                isService = true,
                isRunning = status.svcCodeServer
            ),
            ProgramInfo(
                id = "omniroute",
                name = "OmniRoute AI Gateway",
                description = "Unified multi-provider AI routing proxy for Claude Code & Cursor",
                category = "Web Services",
                isInstalled = status.omniroute,
                webPort = 20128,
                isService = true,
                isRunning = status.svcOmniroute
            ),
            ProgramInfo(
                id = "jupyter",
                name = "JupyterLab Notebook",
                description = "Interactive Python & data science notebook environment",
                category = "Web Services",
                isInstalled = status.jupyter,
                webPort = 8888,
                isService = true,
                isRunning = status.svcJupyter
            ),
            ProgramInfo(
                id = "filebrowser",
                name = "Filebrowser",
                description = "Lightweight web file manager with file upload & download",
                category = "Web Services",
                isInstalled = status.filebrowser,
                webPort = 8085,
                isService = true,
                isRunning = status.svcFilebrowser
            ),
            ProgramInfo(
                id = "caddy",
                name = "Caddy Web Server",
                description = "Modern reverse proxy & HTTP server with automatic TLS",
                category = "Web Services",
                isInstalled = status.caddy,
                webPort = 80,
                isService = true,
                isRunning = status.svcCaddy
            ),
            ProgramInfo(
                id = "transmission",
                name = "Transmission Torrent",
                description = "Lightweight BitTorrent client with responsive Web UI",
                category = "Web Services",
                isInstalled = status.transmission,
                webPort = 9091,
                isService = true,
                isRunning = status.svcTransmission
            ),
            ProgramInfo(
                id = "ollama",
                name = "Ollama AI Server",
                description = "Run local LLMs inside container with OpenAI-compatible API",
                category = "Web Services",
                isInstalled = status.ollama,
                webPort = 11434,
                isService = true,
                isRunning = status.svcOllama
            ),

            // Development & Tools
            ProgramInfo(
                id = "antigravity",
                name = "Google Antigravity IDE",
                description = "Advanced Agentic AI IDE and CLI coding assistant",
                category = "Development",
                isInstalled = status.antigravity
            ),
            ProgramInfo(
                id = "xfce",
                name = "XFCE Desktop Environment",
                description = "Complete lightweight desktop environment and window manager",
                category = "Development",
                isInstalled = status.xfce
            ),

            // Desktop & Productivity
            ProgramInfo(
                id = "libreoffice",
                name = "LibreOffice Suite",
                description = "Full desktop office suite (Writer, Calc, Impress)",
                category = "Productivity",
                isInstalled = status.libreoffice
            ),
            ProgramInfo(
                id = "gimp",
                name = "GIMP Image Editor",
                description = "Professional GNU Image Manipulation Program",
                category = "Productivity",
                isInstalled = status.gimp
            ),

            // Media & Web Browsers
            ProgramInfo(
                id = "chromium",
                name = "Chromium Browser",
                description = "Fast, open-source web browser with GPU acceleration",
                category = "Media & Web",
                isInstalled = status.chromium
            ),
            ProgramInfo(
                id = "firefox",
                name = "Mozilla Firefox",
                description = "Privacy-focused and extensible web browser",
                category = "Media & Web",
                isInstalled = status.firefox
            ),
            ProgramInfo(
                id = "vlc",
                name = "VLC Media Player",
                description = "High performance video, audio, and streaming player",
                category = "Media & Web",
                isInstalled = status.vlc
            )
        )
    }
}
