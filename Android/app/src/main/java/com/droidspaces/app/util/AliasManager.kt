package com.droidspaces.app.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manager for Shell Aliases inside Droidspaces containers.
 * Allows listing, adding, deleting, and sourcing aliases across Bash and Zsh.
 */
object AliasManager {
    private const val TAG = "AliasManager"
    private var scriptBase64: String? = null

    data class AliasItem(
        val name: String,
        val command: String,
        val source: String = "Custom",
        val isPreset: Boolean = false
    )

    data class CommandResult(val isSuccess: Boolean, val output: List<String>, val error: List<String>) {
        val message: String get() = (error + output).firstOrNull { it.isNotBlank() } ?: ""
    }

    fun initialize(context: Context) {
        if (scriptBase64 == null) {
            try {
                val script = context.assets.open("alias_setup.sh").bufferedReader().readText()
                    .replace("\r\n", "\n").replace("\r", "\n")
                scriptBase64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load alias_setup.sh from assets", e)
            }
        }
    }

    suspend fun runScript(containerName: String, vararg args: String): CommandResult =
        withContext(Dispatchers.IO) {
            val b64 = scriptBase64
            val argsStr = args.joinToString(" ")
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                "run 'if [ -n \"$b64\" ]; then echo \"$b64\" | base64 -d | sh -s -- $argsStr; fi 2>&1'"
            try {
                val result = Shell.cmd(cmd).exec()
                CommandResult(result.isSuccess, result.out, result.err)
            } catch (e: Exception) {
                Log.e(TAG, "Error running alias_setup.sh ${args.firstOrNull()}", e)
                CommandResult(false, emptyList(), listOf(e.message ?: "Unknown error"))
            }
        }

    suspend fun listAliases(containerName: String): Result<List<AliasItem>> = withContext(Dispatchers.IO) {
        val res = runScript(containerName, "list")
        if (!res.isSuccess && res.output.isEmpty()) {
            return@withContext Result.failure(Exception(res.message.ifBlank { "Failed to list aliases" }))
        }
        try {
            val jsonText = res.output.joinToString("\n").trim()
            val startIdx = jsonText.indexOf('[')
            val endIdx = jsonText.lastIndexOf(']')
            if (startIdx >= 0 && endIdx >= startIdx) {
                val cleanJson = jsonText.substring(startIdx, endIdx + 1)
                val array = JSONArray(cleanJson)
                val list = mutableListOf<AliasItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        AliasItem(
                            name = obj.optString("name"),
                            command = obj.optString("command"),
                            source = obj.optString("source", "Custom")
                        )
                    )
                }
                Result.success(list)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse alias JSON", e)
            Result.failure(e)
        }
    }

    suspend fun addAlias(containerName: String, name: String, command: String): CommandResult =
        withContext(Dispatchers.IO) {
            val quotedName = ContainerCommandBuilder.quote(name.trim())
            val quotedCmd = ContainerCommandBuilder.quote(command.trim())
            runScript(containerName, "add", quotedName, quotedCmd)
        }

    suspend fun deleteAlias(containerName: String, name: String): CommandResult =
        withContext(Dispatchers.IO) {
            val quotedName = ContainerCommandBuilder.quote(name.trim())
            runScript(containerName, "delete", quotedName)
        }

    fun getRecommendedPresets(): List<AliasItem> {
        return listOf(
            AliasItem("ll", "eza -la --icons --git --group-directories-first", "Preset", true),
            AliasItem("la", "eza -la --icons --git", "Preset", true),
            AliasItem("lt", "eza --tree --icons --level=2", "Preset", true),
            AliasItem("cat", "bat --paging=never", "Preset", true),
            AliasItem("top", "btop", "Preset", true),
            AliasItem("du", "ncdu --color dark", "Preset", true),
            AliasItem("gs", "git status -sb", "Preset", true),
            AliasItem("ga", "git add", "Preset", true),
            AliasItem("gaa", "git add -A", "Preset", true),
            AliasItem("gc", "git commit -m", "Preset", true),
            AliasItem("gp", "git push", "Preset", true),
            AliasItem("gpl", "git pull", "Preset", true),
            AliasItem("gd", "git diff", "Preset", true),
            AliasItem("dc", "docker compose", "Preset", true),
            AliasItem("dcu", "docker compose up -d", "Preset", true),
            AliasItem("dcd", "docker compose down", "Preset", true),
            AliasItem("dps", "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'", "Preset", true),
            AliasItem("myip", "curl -s https://ifconfig.me", "Preset", true),
            AliasItem("ports", "ss -tulpn", "Preset", true),
            AliasItem("update", "sudo apt update && sudo apt upgrade -y", "Preset", true)
        )
    }
}
