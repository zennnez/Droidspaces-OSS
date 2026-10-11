package com.droidspaces.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-level manager for managing Docker containers and images inside a Droidspaces container.
 * Provides service-like management equivalent to [ContainerSystemdManager] for Docker.
 */
object ContainerDockerManager {

    data class DockerContainerInfo(
        val id: String,
        val names: String,
        val image: String,
        val status: String,
        val state: String,
        val ports: String,
        val createdAt: String,
        val command: String,
        val size: String
    ) {
        val shortId: String get() = id.take(12)
        val cleanName: String get() = names.removePrefix("/").ifBlank { shortId }
        val isRunning: Boolean get() = state.equals("running", ignoreCase = true) || status.startsWith("Up", ignoreCase = true)
        val isPaused: Boolean get() = state.equals("paused", ignoreCase = true) || status.contains("Paused", ignoreCase = true)
        val isRestarting: Boolean get() = state.equals("restarting", ignoreCase = true)
        val isStopped: Boolean get() = !isRunning && !isPaused && !isRestarting
    }

    data class DockerImageInfo(
        val id: String,
        val repository: String,
        val tag: String,
        val size: String,
        val createdAt: String
    ) {
        val shortId: String get() = id.take(12)
        val fullName: String get() = if (tag.isNotBlank() && tag != "<none>") "$repository:$tag" else repository
    }

    data class DockerCommandResult(
        val isSuccess: Boolean,
        val output: List<String>,
        val error: List<String>
    ) {
        val message: String get() = (error + output).firstOrNull { it.isNotBlank() } ?: ""
    }

    data class ContainerUpdateResult(
        val status: String, // "up_to_date" | "updated" | "update_available" | "error"
        val container: String,
        val image: String,
        val oldImageId: String = "",
        val newImageId: String = "",
        val restarted: Boolean = false,
        val message: String = ""
    )

    private fun dockerScriptBase64(context: Context): String {
        val content = context.assets.open("docker-setup.sh").bufferedReader().use { it.readText() }
            .replace("\r\n", "\n").replace("\r", "\n")
        return android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    }

    private suspend fun runDockerScript(
        context: Context,
        containerName: String,
        vararg args: String
    ): Shell.Result = withContext(Dispatchers.IO) {
        val b64 = dockerScriptBase64(context)
        val argsStr = args.joinToString(" ")
        val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
            "run 'echo $b64 | base64 -d | sh -s -- $argsStr 2>&1'"
        Shell.cmd(cmd).exec()
    }

    suspend fun listContainers(
        context: Context,
        containerName: String
    ): Result<List<DockerContainerInfo>> = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "ps")
        if (!res.isSuccess) {
            val err = (res.err + res.out).firstOrNull { it.isNotBlank() } ?: "Failed to list Docker containers"
            return@withContext Result.failure(Exception(err))
        }
        try {
            val entries = res.out.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || !trimmed.startsWith("{")) return@mapNotNull null
                try {
                    val obj = JSONObject(trimmed)
                    DockerContainerInfo(
                        id = obj.optString("ID"),
                        names = obj.optString("Names"),
                        image = obj.optString("Image"),
                        status = obj.optString("Status"),
                        state = obj.optString("State"),
                        ports = obj.optString("Ports"),
                        createdAt = obj.optString("CreatedAt"),
                        command = obj.optString("Command"),
                        size = obj.optString("Size")
                    )
                } catch (e: Exception) {
                    null
                }
            }
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listImages(
        context: Context,
        containerName: String
    ): Result<List<DockerImageInfo>> = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "images")
        if (!res.isSuccess) {
            val err = (res.err + res.out).firstOrNull { it.isNotBlank() } ?: "Failed to list Docker images"
            return@withContext Result.failure(Exception(err))
        }
        try {
            val entries = res.out.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || !trimmed.startsWith("{")) return@mapNotNull null
                try {
                    val obj = JSONObject(trimmed)
                    DockerImageInfo(
                        id = obj.optString("ID"),
                        repository = obj.optString("Repository"),
                        tag = obj.optString("Tag"),
                        size = obj.optString("Size"),
                        createdAt = obj.optString("CreatedAt")
                    )
                } catch (e: Exception) {
                    null
                }
            }
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startContainer(
        context: Context,
        containerName: String,
        dockerId: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "container", "start", dockerId)
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun stopContainer(
        context: Context,
        containerName: String,
        dockerId: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "container", "stop", dockerId)
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun restartContainer(
        context: Context,
        containerName: String,
        dockerId: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "container", "restart", dockerId)
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun pauseContainer(
        context: Context,
        containerName: String,
        dockerId: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "container", "pause", dockerId)
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun unpauseContainer(
        context: Context,
        containerName: String,
        dockerId: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "container", "unpause", dockerId)
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun killContainer(
        context: Context,
        containerName: String,
        dockerId: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "container", "kill", dockerId)
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun removeContainer(
        context: Context,
        containerName: String,
        dockerId: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "container", "rm", dockerId)
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun renameContainer(
        context: Context,
        containerName: String,
        dockerId: String,
        newName: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "container", "rename", dockerId, newName)
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun inspectContainer(
        context: Context,
        containerName: String,
        dockerId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "container", "inspect", dockerId)
        if (res.isSuccess || res.out.isNotEmpty()) {
            val text = res.out.joinToString("\n")
            Result.success(text)
        } else {
            val err = (res.err + res.out).firstOrNull { it.isNotBlank() } ?: "Failed to inspect container"
            Result.failure(Exception(err))
        }
    }

    suspend fun pruneContainers(
        context: Context,
        containerName: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "container", "prune")
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun pruneImages(
        context: Context,
        containerName: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "image", "prune")
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun pruneSystem(
        context: Context,
        containerName: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "system-prune")
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun pullImage(
        context: Context,
        containerName: String,
        image: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "image", "pull", image)
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun removeImage(
        context: Context,
        containerName: String,
        imageId: String
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "image", "rm", imageId)
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun runContainer(
        context: Context,
        containerName: String,
        image: String,
        name: String? = null,
        portMappings: List<String> = emptyList(),
        volumeMappings: List<String> = emptyList(),
        envVars: List<String> = emptyList(),
        restartPolicy: String = "no",
        command: String? = null
    ): DockerCommandResult = withContext(Dispatchers.IO) {
        val args = mutableListOf("run", "-d")
        if (!name.isNullOrBlank()) {
            args.add("--name")
            args.add(name.trim())
        }
        if (restartPolicy.isNotBlank() && restartPolicy != "no") {
            args.add("--restart")
            args.add(restartPolicy)
        }
        portMappings.forEach { p ->
            if (p.isNotBlank()) {
                args.add("-p")
                args.add(p.trim())
            }
        }
        volumeMappings.forEach { v ->
            if (v.isNotBlank()) {
                args.add("-v")
                args.add(v.trim())
            }
        }
        envVars.forEach { e ->
            if (e.isNotBlank()) {
                args.add("-e")
                args.add(e.trim())
            }
        }
        args.add(image.trim())
        if (!command.isNullOrBlank()) {
            args.addAll(command.trim().split(Regex("\\s+")))
        }

        val res = runDockerScript(context, containerName, *args.toTypedArray())
        DockerCommandResult(res.isSuccess, res.out, res.err)
    }

    suspend fun updateContainerImage(
        context: Context,
        containerName: String,
        dockerTarget: String,
        recreate: Boolean = true
    ): ContainerUpdateResult = withContext(Dispatchers.IO) {
        val recreateArg = if (recreate) "1" else "0"
        val res = runDockerScript(context, containerName, "update-container", dockerTarget, recreateArg)
        val jsonLine = res.out.lastOrNull { it.trim().startsWith("{") }
        if (jsonLine != null) {
            try {
                val obj = JSONObject(jsonLine.trim())
                return@withContext ContainerUpdateResult(
                    status = obj.optString("status", "unknown"),
                    container = obj.optString("container", dockerTarget),
                    image = obj.optString("image"),
                    oldImageId = obj.optString("oldImageId"),
                    newImageId = obj.optString("newImageId"),
                    restarted = obj.optBoolean("restarted", false),
                    message = res.out.joinToString("\n")
                )
            } catch (_: Exception) {}
        }
        ContainerUpdateResult(
            status = if (res.isSuccess) "updated" else "error",
            container = dockerTarget,
            image = "",
            message = (res.err + res.out).joinToString("\n")
        )
    }

    suspend fun updateAllContainers(
        context: Context,
        containerName: String,
        recreate: Boolean = true
    ): List<ContainerUpdateResult> = withContext(Dispatchers.IO) {
        val recreateArg = if (recreate) "1" else "0"
        val res = runDockerScript(context, containerName, "update-all", recreateArg)
        val raw = res.out.joinToString("\n")
        val startIdx = raw.indexOf('[')
        val endIdx = raw.lastIndexOf(']')
        if (startIdx >= 0 && endIdx >= startIdx) {
            try {
                val arr = JSONArray(raw.substring(startIdx, endIdx + 1))
                val list = mutableListOf<ContainerUpdateResult>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        ContainerUpdateResult(
                            status = obj.optString("status"),
                            container = obj.optString("container"),
                            image = obj.optString("image"),
                            oldImageId = obj.optString("oldImageId"),
                            newImageId = obj.optString("newImageId"),
                            restarted = obj.optBoolean("restarted", false)
                        )
                    )
                }
                return@withContext list
            } catch (_: Exception) {}
        }
        emptyList()
    }
}
