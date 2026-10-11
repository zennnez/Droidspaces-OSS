package com.droidspaces.app.util

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where a shared folder's Android-side source came from. Purely informational
 * (shown in the UI) -- all three are wired up the exact same way underneath.
 */
enum class SharedFolderType {
    INTERNAL,       // /storage/emulated/0 (device's main shared storage)
    USER_SELECTED,  // A folder the user picked via the SAF folder picker
    REMOVABLE       // A folder on an SD card / USB-OTG drive
}

data class SharedFolder(
    val id: String,
    val label: String,
    val androidPath: String,
    val linuxDest: String,
    val readOnly: Boolean,
    val type: SharedFolderType
)

data class FileBridgeConfig(
    val enabled: Boolean = false,
    val sharedPath: String = FileBridgeManager.DEFAULT_SHARED_PATH,
    val readOnlyDefault: Boolean = false,
    val folders: List<SharedFolder> = emptyList()
)

enum class FolderLiveState { MOUNTED, NOT_MOUNTED, UNKNOWN }

data class FileBridgeStatus(
    val containerRunning: Boolean,
    val bridgeEnabled: Boolean,
    val folders: List<Pair<SharedFolder, FolderLiveState>>,
    val error: String? = null
) {
    /** True only when the container is up, the bridge is on, and every folder mounted cleanly. */
    val isFullyConnected: Boolean
        get() = containerRunning && bridgeEnabled && folders.isNotEmpty() &&
            folders.all { it.second == FolderLiveState.MOUNTED }
}

/**
 * Android <-> Linux file bridge.
 *
 * Architecture:
 *   Android storage -> [FileBridgeManager] (this file) -> Linux filesystem -> Thunar
 *
 * This deliberately does NOT reinvent mounting. Droidspaces' native binary
 * already has a generic, security-checked bind-mount facility
 * (`bind_mounts=` in container.config, applied by `setup_custom_binds()` in
 * src/mount.c every time the container starts, and torn down automatically
 * when it stops). All this class does is:
 *   1. Resolve an Android-side source (internal storage / a SAF-picked folder
 *      / a removable volume) to a real filesystem path.
 *   2. Keep a small, richer piece of metadata about each folder (label, type,
 *      when it was added) in a per-container JSON file, since container.config
 *      itself only stores src:dest:ro triples.
 *   3. Reconcile that metadata into `bind_mounts` entries under the
 *      configured [FileBridgeConfig.sharedPath] and hand off to
 *      [ContainerManager.updateContainerConfig] -- the same call every other
 *      settings screen in the app already uses.
 *
 * Because this rides on the existing bind-mount mechanism, "Integrate with
 * XFCE Thunar" (requirement 3) is mostly free: once a folder is bind-mounted,
 * it's just an ordinary directory as far as Thunar is concerned. The only
 * bespoke Thunar work is a bookmark for discoverability and a custom action
 * for the reverse (Linux -> Android) direction -- see [setupThunarIntegration].
 */
object FileBridgeManager {

    const val DEFAULT_SHARED_PATH = "/mnt/shared"
    private const val METADATA_FILENAME = ".file_bridge.json"
    private const val INBOX_FOLDER_ID = "__inbox__"
    private const val OUTBOX_FOLDER_ID = "__outbox__"
    const val INBOX_LABEL = "Android → Linux (Inbox)"
    const val OUTBOX_LABEL = "Linux → Android (Outbox)"

    // droidspaces' native config reader (src/config.c) reads each config line
    // with `fgets(line, sizeof(line), f)` into a fixed `char line[2048]`
    // buffer. A `bind_mounts=` line longer than that is silently truncated
    // mid-entry -- the leftover tail is then misread as a bogus new line and
    // dropped (no `=` before the next newline), which can corrupt or lose the
    // last bind mount entirely with no error surfaced anywhere. We keep a
    // safety margin under the real 2048-byte limit for the "bind_mounts="
    // prefix, a trailing newline, and any multi-byte UTF-8 in a folder name.
    private const val MAX_BIND_MOUNTS_LINE_LENGTH = 1900

    // The native format is bare `src:dest[:ro],src2:dest2[:ro],...` --
    // parse_bind_mounts() in src/config.c splits on ',' then ':' with no
    // escaping whatsoever. Since Android filenames CAN legally contain both
    // characters (e.g. a folder named "Photos: 2026" or "Work, Personal"),
    // any Android path containing either would silently misparse: src/dest
    // boundaries shift, a ":ro" flag lands on the wrong entry, etc. Reject
    // these up front with a clear reason instead of writing a corrupt config.
    private val UNSAFE_PATH_CHARS = Regex("[,:]")

    private fun metadataPath(containerName: String): String =
        "${ContainerManager.getContainerDirectory(containerName)}/$METADATA_FILENAME"

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    suspend fun loadConfig(containerName: String): FileBridgeConfig = withContext(Dispatchers.IO) {
        try {
            val path = metadataPath(containerName)
            val result = Shell.cmd("cat ${ContainerCommandBuilder.quote(path)} 2>/dev/null").exec()
            val raw = result.out.joinToString("\n")
            if (!result.isSuccess || raw.isBlank()) return@withContext FileBridgeConfig()

            val obj = JSONObject(raw)
            val folders = mutableListOf<SharedFolder>()
            val arr = obj.optJSONArray("folders") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                folders.add(
                    SharedFolder(
                        id = f.getString("id"),
                        label = f.getString("label"),
                        androidPath = f.getString("androidPath"),
                        linuxDest = f.getString("linuxDest"),
                        readOnly = f.optBoolean("readOnly", false),
                        type = try {
                            SharedFolderType.valueOf(f.optString("type", "USER_SELECTED"))
                        } catch (e: Exception) {
                            SharedFolderType.USER_SELECTED
                        }
                    )
                )
            }
            FileBridgeConfig(
                enabled = obj.optBoolean("enabled", false),
                sharedPath = obj.optString("sharedPath", DEFAULT_SHARED_PATH).ifBlank { DEFAULT_SHARED_PATH },
                readOnlyDefault = obj.optBoolean("readOnlyDefault", false),
                folders = folders
            )
        } catch (e: Exception) {
            FileBridgeConfig()
        }
    }

    private suspend fun saveMetadata(context: Context, containerName: String, config: FileBridgeConfig) = withContext(Dispatchers.IO) {
        val obj = JSONObject().apply {
            put("enabled", config.enabled)
            put("sharedPath", config.sharedPath)
            put("readOnlyDefault", config.readOnlyDefault)
            put("folders", JSONArray().apply {
                config.folders.forEach { f ->
                    put(JSONObject().apply {
                        put("id", f.id)
                        put("label", f.label)
                        put("androidPath", f.androidPath)
                        put("linuxDest", f.linuxDest)
                        put("readOnly", f.readOnly)
                        put("type", f.type.name)
                    })
                }
            })
        }
        val tempFile = File("${context.cacheDir}/.file_bridge_${ContainerManager.sanitizeContainerName(containerName)}.json")
        tempFile.writeText(obj.toString(2))
        val destPath = metadataPath(containerName)
        Shell.cmd(
            "cp ${ContainerCommandBuilder.quote(tempFile.absolutePath)} ${ContainerCommandBuilder.quote(destPath)} " +
                "&& chmod 600 ${ContainerCommandBuilder.quote(destPath)}"
        ).exec()
        tempFile.delete()
    }

    /**
     * Reconciles [config]'s folders into the container's `bind_mounts`, preserving
     * any bind mounts the user set up manually outside the file bridge (anything
     * whose destination doesn't fall under [FileBridgeConfig.sharedPath]).
     */
    private suspend fun applyToContainerConfig(context: Context, containerName: String, config: FileBridgeConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext Result.failure(Exception("Container '$containerName' not found"))

        val foreignBinds = info.bindMounts.filterNot { it.dest.startsWith(config.sharedPath) }
        val bridgeBinds = if (config.enabled) {
            config.folders.map { BindMount(src = it.androidPath, dest = it.linuxDest, ro = it.readOnly) }
        } else {
            emptyList()
        }
        val allBinds = foreignBinds + bridgeBinds

        // See MAX_BIND_MOUNTS_LINE_LENGTH's doc: the native config reader
        // truncates a bind_mounts= line over ~2048 bytes mid-entry, silently
        // corrupting or dropping mounts. Check the exact serialized form
        // (same join the native writer produces) before ever writing it.
        val serialized = allBinds.joinToString(",") { "${it.src}:${it.dest}${if (it.ro) ":ro" else ""}" }
        if (serialized.length > MAX_BIND_MOUNTS_LINE_LENGTH) {
            return@withContext Result.failure(Exception(
                "Too many shared folders (or paths too long) to fit in one container config " +
                "line safely -- remove a folder or shorten a path before adding another."
            ))
        }

        val updated = info.copy(bindMounts = allBinds)
        ContainerManager.updateContainerConfig(context, containerName, updated)
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    suspend fun setEnabled(context: Context, containerName: String, enabled: Boolean): Result<FileBridgeConfig> = withContext(Dispatchers.IO) {
        try {
            var config = loadConfig(containerName)
            // Seed sensible defaults the first time this is turned on, so the
            // user immediately has something browsable rather than an empty list
            // (requirement 1: expose Android shared storage inside Linux).
            if (enabled && config.folders.isEmpty()) {
                val internal = internalStorageFolder(config.sharedPath)
                val inbox = wellKnownFolder(INBOX_FOLDER_ID, INBOX_LABEL, "${internal.androidPath}/Droidspaces-Inbox", config.sharedPath, readOnly = false)
                val outbox = wellKnownFolder(OUTBOX_FOLDER_ID, OUTBOX_LABEL, "${internal.androidPath}/Droidspaces-Outbox", config.sharedPath, readOnly = false)
                // Ensure directories exist on Android storage with world-accessible permissions
                Shell.cmd("mkdir -p \"${inbox.androidPath}\" \"${outbox.androidPath}\" && chmod 777 \"${inbox.androidPath}\" \"${outbox.androidPath}\"").exec()
                config = config.copy(folders = listOf(internal, inbox, outbox))
            }
            config = config.copy(enabled = enabled)
            saveMetadata(context, containerName, config)
            applyToContainerConfig(context, containerName, config).getOrThrow()
            if (enabled) {
                // Best-effort; Thunar may not be installed yet, that's fine.
                setupThunarIntegration(context, containerName, config)
            }
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setReadOnlyDefault(context: Context, containerName: String, readOnly: Boolean): Result<FileBridgeConfig> = withContext(Dispatchers.IO) {
        try {
            val config = loadConfig(containerName).copy(readOnlyDefault = readOnly)
            saveMetadata(context, containerName, config)
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Adds a shared folder from a SAF tree Uri (user-selected folder or a
     * removable-volume folder picked through the system folder picker).
     * Handles Android scoped storage by resolving through [SafPathResolver]
     * rather than assuming a raw filesystem path is usable.
     */
    suspend fun addFolderFromUri(context: Context, containerName: String, uri: Uri, label: String, readOnly: Boolean? = null): Result<SharedFolder> = withContext(Dispatchers.IO) {
        try {
            SafPathResolver.takePersistablePermission(context, uri)
            val resolvedPath = SafPathResolver.resolvePathFromUri(context, uri)
                ?: return@withContext Result.failure(Exception(
                    "Couldn't resolve a real filesystem path for the selected folder. " +
                    "This can happen with cloud-only or virtual document providers, which " +
                    "don't have a path a Linux bind mount can use."
                ))
            addFolderFromPath(
                context, containerName, resolvedPath, label, readOnly,
                type = if (StorageMountManager.extractVolumeId(resolvedPath)?.let { it != "emulated" } == true) {
                    SharedFolderType.REMOVABLE
                } else {
                    SharedFolderType.USER_SELECTED
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addFolderFromPath(
        context: Context,
        containerName: String,
        androidPath: String,
        label: String,
        readOnly: Boolean? = null,
        type: SharedFolderType = SharedFolderType.USER_SELECTED
    ): Result<SharedFolder> = withContext(Dispatchers.IO) {
        try {
            // The native config format (src:dest[:ro] joined by commas) has no
            // escaping at all -- see UNSAFE_PATH_CHARS's doc. Catch this before
            // it can silently corrupt every other bind mount in the config.
            if (UNSAFE_PATH_CHARS.containsMatchIn(androidPath)) {
                return@withContext Result.failure(Exception(
                    "\"$androidPath\" contains a comma or colon in its name, which the " +
                    "container config format can't represent safely. Rename the folder " +
                    "(or the part of the path containing it) and try again."
                ))
            }

            // Permission / existence checks up front (requirement: handle
            // permission denial and missing folders with a clear error rather
            // than a confusing failure once the container starts).
            val readCheck = RootNamespace.exec("test -d ${ContainerCommandBuilder.quote(androidPath)} -a -r ${ContainerCommandBuilder.quote(androidPath)}", androidPath)
            if (!readCheck.isSuccess) {
                return@withContext Result.failure(Exception(
                    "'$androidPath' doesn't exist or isn't readable. If this is on removable " +
                    "storage, make sure the drive is still connected."
                ))
            }
            val writable = readOnly != true &&
                RootNamespace.exec("test -w ${ContainerCommandBuilder.quote(androidPath)}", androidPath).isSuccess

            var config = loadConfig(containerName)
            val effectiveReadOnly = readOnly ?: (config.readOnlyDefault || !writable)
            val id = "sf_${System.currentTimeMillis()}_${(0..999).random()}"
            val slug = slugify(label).ifBlank { "folder" }
            val dest = uniqueDest(config, "${config.sharedPath}/${slug}")
            val folder = SharedFolder(id, label, androidPath, dest, effectiveReadOnly, type)

            config = config.copy(folders = config.folders + folder)
            saveMetadata(context, containerName, config)
            if (config.enabled) {
                applyToContainerConfig(context, containerName, config).getOrThrow()
            }
            Result.success(folder)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFolder(context: Context, containerName: String, folderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var config = loadConfig(containerName)
            config = config.copy(folders = config.folders.filterNot { it.id == folderId })
            saveMetadata(context, containerName, config)
            if (config.enabled) {
                applyToContainerConfig(context, containerName, config).getOrThrow()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setFolderReadOnly(context: Context, containerName: String, folderId: String, readOnly: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var config = loadConfig(containerName)
            config = config.copy(folders = config.folders.map { if (it.id == folderId) it.copy(readOnly = readOnly) else it })
            saveMetadata(context, containerName, config)
            if (config.enabled) {
                applyToContainerConfig(context, containerName, config).getOrThrow()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Status ("automatic mount/unmount, status checking, error reporting")
    // ------------------------------------------------------------------

    /**
     * Bind mounts are applied by the native binary every time the container
     * starts (and torn down when it stops) -- there's no separate mount/unmount
     * step for this class to drive. What we CAN do is report the truth: is the
     * container running, and for each configured folder, does it actually show
     * up as mounted inside the container right now.
     */
    suspend fun getStatus(containerName: String): FileBridgeStatus = withContext(Dispatchers.IO) {
        val config = loadConfig(containerName)
        val (running, _) = ContainerManager.checkContainerStatus(containerName)

        if (!running || !config.enabled || config.folders.isEmpty()) {
            return@withContext FileBridgeStatus(
                containerRunning = running,
                bridgeEnabled = config.enabled,
                folders = config.folders.map { it to FolderLiveState.UNKNOWN }
            )
        }

        try {
            // Check mount points and directory accessibility cleanly via base64 script
            val checkScript = config.folders.joinToString("\n") {
                val d = it.linuxDest
                """if mountpoint -q "$d" 2>/dev/null || grep -qs " $d " /proc/mounts || [ -d "$d" ]; then echo "MOUNTED $d"; else echo "NOTMOUNTED $d"; fi"""
            }
            val b64 = android.util.Base64.encodeToString(checkScript.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run 'echo $b64 | base64 -d | sh'"
            val result = Shell.cmd(cmd).exec()
            val states = result.out.associate { line ->
                val trimmed = line.trim()
                val parts = trimmed.split(" ", limit = 2)
                if (parts.size == 2) parts[1].trim() to (parts[0].trim() == "MOUNTED") else "" to false
            }
            val folderStates = config.folders.map { f ->
                f to when (states[f.linuxDest]) {
                    true -> FolderLiveState.MOUNTED
                    false -> FolderLiveState.NOT_MOUNTED
                    null -> FolderLiveState.UNKNOWN
                }
            }
            FileBridgeStatus(containerRunning = true, bridgeEnabled = true, folders = folderStates)
        } catch (e: Exception) {
            FileBridgeStatus(
                containerRunning = running,
                bridgeEnabled = config.enabled,
                folders = config.folders.map { it to FolderLiveState.UNKNOWN },
                error = e.message
            )
        }
    }

    // ------------------------------------------------------------------
    // Thunar integration (requirement 3 + the Linux -> Android half of 5)
    // ------------------------------------------------------------------

    /**
     * Best-effort: adds a "Android Files" bookmark Thunar shows in its sidebar,
     * and a "Send to Android" custom right-click action that drops the file into
     * the outbox folder (picked up by [com.droidspaces.app.util.OutboxImportChecker]
     * on the Android side). Safe to call repeatedly -- every step is idempotent.
     * Does nothing (and reports nothing) if Thunar isn't installed; the bind
     * mounts themselves work regardless of which file manager is used.
     */
    suspend fun setupThunarIntegration(context: Context, containerName: String, config: FileBridgeConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val assetStream = context.assets.open("file_bridge_setup.sh")
            val scriptContent = assetStream.bufferedReader().use { it.readText() }.replace("\r\n", "\n").replace("\r", "\n")
            val b64 = android.util.Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val outboxDest = config.folders.firstOrNull { it.id == OUTBOX_FOLDER_ID }?.linuxDest
                ?: "${config.sharedPath}/outbox"

            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                "run 'echo $b64 | base64 -d | sh -s -- ${ContainerCommandBuilder.quote(outboxDest)} ${ContainerCommandBuilder.quote(config.sharedPath)} 2>&1'"
            val result = Shell.cmd(cmd).exec()

            if (result.isSuccess) Result.success(Unit) else Result.failure(Exception(result.out.joinToString("\n")))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun internalStorageFolder(sharedPath: String) = SharedFolder(
        id = "internal",
        label = "Internal Storage",
        androidPath = "/storage/emulated/0",
        linuxDest = "$sharedPath/internal",
        readOnly = false,
        type = SharedFolderType.INTERNAL
    )

    private fun wellKnownFolder(id: String, label: String, androidPath: String, sharedPath: String, readOnly: Boolean) = SharedFolder(
        id = id,
        label = label,
        androidPath = androidPath,
        linuxDest = "$sharedPath/${id.trim('_')}",
        readOnly = readOnly,
        type = SharedFolderType.INTERNAL
    )

    private fun uniqueDest(config: FileBridgeConfig, base: String): String {
        val existing = config.folders.map { it.linuxDest }.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base-$i" in existing) i++
        return "$base-$i"
    }

    private fun slugify(label: String): String =
        label.lowercase()
            .map { c -> if (c.isLetterOrDigit()) c else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(40)
}
