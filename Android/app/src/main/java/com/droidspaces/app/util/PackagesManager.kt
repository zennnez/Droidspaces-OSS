package com.droidspaces.app.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager for Packages & Shell Environment setup inside the container.
 */
object PackagesManager {
    private const val TAG = "PackagesManager"
    private const val SCRIPT_PATH = "/usr/local/sbin/droidspaces-packages"

    private var scriptBase64: String? = null

    data class PackageStatus(
        val zsh: Boolean = false,
        val ohMyZsh: Boolean = false,
        val eza: Boolean = false,
        val zoxide: Boolean = false,
        val bat: Boolean = false,
        val fd: Boolean = false,
        val rg: Boolean = false,
        val fzf: Boolean = false,
        val btop: Boolean = false,
        val ncdu: Boolean = false,
        val fastfetch: Boolean = false,
        val delta: Boolean = false,
        val tmux: Boolean = false,
        val python: Boolean = false,
        val uv: Boolean = false,
        val node: Boolean = false,
        val bun: Boolean = false,
        val gh: Boolean = false,
        val claude: Boolean = false,
        val omniroute: Boolean = false,
        val rust: Boolean = false,
        val go: Boolean = false,
        val neovim: Boolean = false,
        val starship: Boolean = false,
        val ollama: Boolean = false,
        val gitLfs: Boolean = false
    )

    data class PackageItem(
        val id: String,
        val name: String,
        val description: String,
        val category: String, // "CLI Tools" | "Shell & Terminal" | "Languages & SDKs" | "AI & Tools"
        val isInstalled: Boolean = false,
        val installCommand: String = id
    )

    data class CommandResult(val isSuccess: Boolean, val output: List<String>, val error: List<String>) {
        val message: String get() = (error + output).firstOrNull { it.isNotBlank() } ?: ""
    }

    fun initialize(context: Context) {
        if (scriptBase64 == null) {
            try {
                val script = context.assets.open("packages_setup.sh").bufferedReader().readText()
                    .replace("\r\n", "\n").replace("\r", "\n")
                scriptBase64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load packages_setup.sh from assets", e)
            }
        }
    }

    fun getTerminalInstallCommand(context: Context, subcommand: String): String {
        initialize(context)
        val b64 = scriptBase64 ?: ""
        return "if [ -x $SCRIPT_PATH ]; then $SCRIPT_PATH $subcommand; elif [ -n \"$b64\" ]; then echo \"$b64\" | base64 -d | bash -s -- $subcommand; fi"
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
                Log.e(TAG, "Error running packages_setup.sh ${args.firstOrNull()}", e)
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
            Log.e(TAG, "Failed to deploy packages_setup.sh", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown deployment error"))
        }
    }

    suspend fun getStatus(containerName: String): PackageStatus = withContext(Dispatchers.IO) {
        val res = runScript(containerName, "status")
        val map = res.output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to (line.substring(idx + 1) == "1") else null
        }.toMap()
        PackageStatus(
            zsh = map["zsh"] == true,
            ohMyZsh = map["ohmyzsh"] == true,
            eza = map["eza"] == true,
            zoxide = map["zoxide"] == true,
            bat = map["bat"] == true,
            fd = map["fd"] == true,
            rg = map["rg"] == true,
            fzf = map["fzf"] == true,
            btop = map["btop"] == true,
            ncdu = map["ncdu"] == true,
            fastfetch = map["fastfetch"] == true,
            delta = map["delta"] == true,
            tmux = map["tmux"] == true,
            python = map["python"] == true,
            uv = map["uv"] == true,
            node = map["node"] == true,
            bun = map["bun"] == true,
            gh = map["gh"] == true,
            claude = map["claude"] == true,
            omniroute = map["omniroute"] == true,
            rust = map["rust"] == true,
            go = map["go"] == true,
            neovim = map["neovim"] == true,
            starship = map["starship"] == true,
            ollama = map["ollama"] == true,
            gitLfs = map["gitlfs"] == true
        )
    }

    fun getPackageCatalog(status: PackageStatus): List<PackageItem> {
        return listOf(
            // Modern CLI Tools
            PackageItem("eza", "eza", "Modern replacement for ls with icons and colors", "CLI Tools", status.eza),
            PackageItem("zoxide", "zoxide", "A smarter cd command with learning database", "CLI Tools", status.zoxide),
            PackageItem("fastfetch", "fastfetch", "Ultra-fast neofetch-like system information tool", "CLI Tools", status.fastfetch),
            PackageItem("bat", "bat", "Cat clone with syntax highlighting & git integration", "CLI Tools", status.bat),
            PackageItem("fd", "fd-find", "Simple, fast and user-friendly alternative to find", "CLI Tools", status.fd),
            PackageItem("rg", "ripgrep", "Blazingly fast recursive regex search tool", "CLI Tools", status.rg),
            PackageItem("btop", "btop", "Resource monitor with responsive CPU/RAM graphs", "CLI Tools", status.btop),
            PackageItem("ncdu", "ncdu", "NCurses disk usage analyzer for terminal", "CLI Tools", status.ncdu),
            PackageItem("delta", "git-delta", "Syntax-highlighting pager for git diffs", "CLI Tools", status.delta),
            PackageItem("gitlfs", "Git LFS", "Git Large File Storage extension for large repositories", "CLI Tools", status.gitLfs),
            PackageItem("fzf", "fzf", "General-purpose command-line fuzzy finder", "CLI Tools", status.fzf),
            PackageItem("tmux", "tmux", "Terminal multiplexer with detachable sessions", "CLI Tools", status.tmux),

            // Shell & Terminal
            PackageItem("zsh", "Zsh Shell", "Z shell with advanced completion and scripting", "Shell & Terminal", status.zsh),
            PackageItem("ohmyzsh", "Oh My Zsh", "Framework with community plugins and themes", "Shell & Terminal", status.ohMyZsh, "zsh"),
            PackageItem("starship", "Starship", "Minimal, blazing-fast cross-shell prompt", "Shell & Terminal", status.starship),
            PackageItem("neovim", "Neovim", "Vim-fork focused on extensibility and Lua plugins", "Shell & Terminal", status.neovim),

            // Languages & SDKs
            PackageItem("python", "Python 3 & uv", "Python interpreter + Astral uv fast package manager", "Languages & SDKs", status.python && status.uv, "uv"),
            PackageItem("node", "Node.js 22 LTS", "JavaScript runtime built on Chrome's V8 engine", "Languages & SDKs", status.node),
            PackageItem("bun", "Bun Runtime", "Fast all-in-one JavaScript runtime & bundler", "Languages & SDKs", status.bun),
            PackageItem("rust", "Rust & Cargo", "Systems programming language via rustup", "Languages & SDKs", status.rust),
            PackageItem("go", "Go (Golang)", "Fast, open-source programming language by Google", "Languages & SDKs", status.go),
            PackageItem("gh", "GitHub CLI", "Official CLI for GitHub pull requests and issues", "Languages & SDKs", status.gh),

            // AI & Developer Agents
            PackageItem("claude", "Claude Code CLI", "Anthropic's official agentic terminal coding assistant", "AI & Tools", status.claude),
            PackageItem("omniroute", "OmniRoute", "Local AI Gateway & Routing Proxy for Claude Code (port 20128)", "AI & Tools", status.omniroute),
            PackageItem("ollama", "Ollama AI", "Run local large language models inside container", "AI & Tools", status.ollama)
        )
    }
}
