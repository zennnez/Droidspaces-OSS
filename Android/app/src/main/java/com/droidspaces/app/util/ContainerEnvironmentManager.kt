package com.droidspaces.app.util

import android.content.Context
import android.util.Base64
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages modular environment variable files per container:
 * - General Container Environment: `env_file` (managed via Edit Container / Container Config)
 * - Anland Wayland Environment: `.anland.env` (managed via Wayland Desktop & Anland screen)
 * - Adreno Turnip GPU Environment: `.turnip.env` (managed via Adreno Turnip screen)
 *
 * Synchronizes all active modular files into `/etc/environment` so that editing one
 * component never overwrites or erases the variables of another component.
 */
object ContainerEnvironmentManager {

    val DEFAULT_ANLAND_ENV = """
        WAYLAND_DISPLAY=wayland-0
        DISPLAY=:0
        QT_QPA_PLATFORM=wayland
        ANLAND=1
        ANLAND_SOCKET=/run/display.sock
        ANLAND_DRM_DEVICE=/dev/dri/renderD128
        GDK_BACKEND=wayland,x11
        CLUTTER_BACKEND=wayland
        SDL_VIDEODRIVER=wayland
        XDG_SESSION_TYPE=wayland
        FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1
    """.trimIndent()

    val DEFAULT_TURNIP_ENV = """
        MESA_LOADER_DRIVER_OVERRIDE=kgsl
        TU_DEBUG=noconform
        LIBGL_DRI3_ENABLE=1
    """.trimIndent()

    fun getGeneralEnvPath(containerName: String): String =
        "${ContainerManager.getContainerDirectory(containerName)}/env_file"

    fun getAnlandEnvPath(containerName: String): String =
        "${ContainerManager.getContainerDirectory(containerName)}/.anland.env"

    fun getTurnipEnvPath(containerName: String): String =
        "${ContainerManager.getContainerDirectory(containerName)}/.turnip.env"

    suspend fun loadGeneralEnv(containerName: String): String = withContext(Dispatchers.IO) {
        val path = getGeneralEnvPath(containerName)
        val res = Shell.cmd("cat ${ContainerCommandBuilder.quote(path)} 2>/dev/null").exec()
        if (res.isSuccess) res.out.joinToString("\n").trim() else ""
    }

    suspend fun saveGeneralEnv(context: Context, containerName: String, content: String) = withContext(Dispatchers.IO) {
        val path = getGeneralEnvPath(containerName)
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        Shell.cmd("echo $b64 | base64 -d > ${ContainerCommandBuilder.quote(path)} && chmod 600 ${ContainerCommandBuilder.quote(path)}").exec()
        val info = ContainerManager.getContainerInfo(containerName)
        if (info != null) {
            ContainerManager.updateContainerConfig(context, containerName, info.copy(envFileContent = content))
        }
        syncToContainer(context, containerName)
    }

    suspend fun loadAnlandEnv(containerName: String): String = withContext(Dispatchers.IO) {
        val path = getAnlandEnvPath(containerName)
        val check = Shell.cmd("test -f ${ContainerCommandBuilder.quote(path)}").exec()
        if (check.isSuccess) {
            val res = Shell.cmd("cat ${ContainerCommandBuilder.quote(path)} 2>/dev/null").exec()
            res.out.joinToString("\n").trim()
        } else {
            DEFAULT_ANLAND_ENV
        }
    }

    suspend fun saveAnlandEnv(context: Context, containerName: String, content: String) = withContext(Dispatchers.IO) {
        val path = getAnlandEnvPath(containerName)
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        Shell.cmd("echo $b64 | base64 -d > ${ContainerCommandBuilder.quote(path)} && chmod 600 ${ContainerCommandBuilder.quote(path)}").exec()
        syncToContainer(context, containerName)
    }

    suspend fun loadTurnipEnv(containerName: String): String = withContext(Dispatchers.IO) {
        val path = getTurnipEnvPath(containerName)
        val check = Shell.cmd("test -f ${ContainerCommandBuilder.quote(path)}").exec()
        if (check.isSuccess) {
            val res = Shell.cmd("cat ${ContainerCommandBuilder.quote(path)} 2>/dev/null").exec()
            res.out.joinToString("\n").trim()
        } else {
            DEFAULT_TURNIP_ENV
        }
    }

    suspend fun saveTurnipEnv(context: Context, containerName: String, content: String) = withContext(Dispatchers.IO) {
        val path = getTurnipEnvPath(containerName)
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        Shell.cmd("echo $b64 | base64 -d > ${ContainerCommandBuilder.quote(path)} && chmod 600 ${ContainerCommandBuilder.quote(path)}").exec()
        syncToContainer(context, containerName)
    }

    /**
     * Combines general environment, Anland environment, and Turnip GPU environment
     * and synchronizes to `/etc/environment` inside the container without conflicts.
     */
    suspend fun syncToContainer(context: Context, containerName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val info = ContainerManager.getContainerInfo(containerName)

            val generalEnv = loadGeneralEnv(containerName)
            val anlandEnv = if (info?.enableAnland == true) loadAnlandEnv(containerName) else ""
            val turnipEnv = if (info?.enableGpuMode == true) loadTurnipEnv(containerName) else ""

            val mergedMap = LinkedHashMap<String, String>()

            fun parseLines(raw: String) {
                raw.lines().forEach { line ->
                    val trimmed = line.trim().removePrefix("export ").trim()
                    if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split("=", limit = 2)
                        if (parts.isNotEmpty()) {
                            val key = parts[0].trim()
                            val value = if (parts.size > 1) parts[1].trim() else ""
                            if (key.isNotBlank()) {
                                mergedMap[key] = value
                            }
                        }
                    }
                }
            }

            parseLines(generalEnv)
            parseLines(anlandEnv)
            parseLines(turnipEnv)

            val combined = mergedMap.entries.joinToString("\n") { (k, v) -> "$k=$v" }

            val b64 = Base64.encodeToString(combined.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val updateCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run " +
                "'echo $b64 | base64 -d > /etc/environment; chmod 0666 /dev/dri/renderD128 /dev/dri/card* /dev/kgsl-3d0 2>/dev/null || true; chmod 0777 /run/display.sock 2>/dev/null || true'"
            val res = Shell.cmd(updateCmd).exec()
            res.isSuccess
        } catch (_: Exception) {
            false
        }
    }
}
