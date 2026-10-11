package com.droidspaces.app.util

import android.content.Context
import android.util.Base64
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Reports the on-disk footprint of a sparse-image container's rootfs.img.
 *
 * For a sparse file the apparent size (e.g. 32 GB) is much larger than the blocks
 * actually allocated on the host (e.g. 5.6 GB). A single `stat -c '%s %b'` gives both:
 *   - %s  = apparent size in bytes        -> total
 *   - %b  = allocated 512-byte blocks      -> used = %b * 512
 *
 * This is the storage the image currently occupies on the device. Note it reflects
 * host-allocated blocks, which only grow: deleting files inside the guest will not
 * shrink it unless the guest issues fstrim/discard. Works whether the container is
 * running or not - no mount required.
 *
 * Always uses our bundled busybox (pinned version) rather than the system `stat`/toybox,
 * so the `%s %b` output is reproducible across devices regardless of the OEM userspace.
 */
object ContainerDiskUsageManager {

    /** Allocated 512-byte blocks are always 512 bytes regardless of filesystem block size. */
    private const val STAT_BLOCK_SIZE = 512L

    data class DiskUsage(val usedBytes: Long, val totalBytes: Long)

    data class RepairResult(
        val before: DiskUsage,
        val after: DiskUsage,
        val output: String
    )

    /** Repair and compact an offline ext4 sparse image with the bundled manager. */
    suspend fun repairAndCompact(
        context: Context,
        imgPath: String,
        runFstrim: Boolean = false,
        compactToUtilized: Boolean = false
    ): Result<RepairResult> = withContext(Dispatchers.IO) {
        var scriptFile: File? = null
        try {
            val before = getUsage(imgPath)
                ?: return@withContext Result.failure(Exception("Unable to read sparse-image usage"))
            val deployedFile = File(context.cacheDir, "sparsemgr-compact.sh")
            scriptFile = deployedFile
            val scriptContent = context.assets.open("sparsemgr.sh").bufferedReader().use { it.readText() }
                .replace("\r\n", "\n").replace("\r", "\n")
            deployedFile.writeText(scriptContent, Charsets.UTF_8)
            Shell.cmd("chmod 755 ${ContainerCommandBuilder.quote(deployedFile.absolutePath)}").exec()

            var flags = "--yes"
            if (runFstrim) flags += " --fstrim"
            if (compactToUtilized) flags += " --shrink"

            val command = "sh ${ContainerCommandBuilder.quote(deployedFile.absolutePath)} " +
                "-i ${ContainerCommandBuilder.quote(imgPath)} compact $flags 2>&1"
            val result = Shell.cmd(command).exec()
            val output = (result.out + result.err).joinToString("\n").trim()
            if (!result.isSuccess) {
                return@withContext Result.failure(Exception(output.ifBlank { "Image repair failed" }))
            }

            delay(300)
            val after = getUsage(imgPath)
                ?: return@withContext Result.failure(Exception("Image repaired, but updated usage could not be read"))
            Result.success(RepairResult(before, after, output))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            scriptFile?.delete()
        }
    }

    /** Last measured value per image path - lets a re-opened screen paint instantly. */
    private val lastKnown = HashMap<String, DiskUsage>()

    /** Last value measured for [imgPath] in this process, or null if never measured. */
    fun getCached(imgPath: String): DiskUsage? = synchronized(lastKnown) { lastKnown[imgPath] }

    fun forget(imgPath: String) = synchronized(lastKnown) { lastKnown.remove(imgPath) }

    /**
     * @param imgPath path to the rootfs.img (use [ContainerInfo.rootfsPath] for a sparse container).
     * @return used/total in bytes, or null if the image could not be stat'd.
     */
    suspend fun getUsage(imgPath: String): DiskUsage? = withContext(Dispatchers.IO) {
        if (imgPath.endsWith(".img")) {
            val cmd = "${Constants.BUSYBOX_BINARY_PATH} stat -c '%s %b' ${ContainerCommandBuilder.quote(imgPath)} 2>/dev/null"
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess) {
                val parts = result.out.firstOrNull()?.trim()?.split(Regex("\\s+"))
                val total = parts?.getOrNull(0)?.toLongOrNull()
                val blocks = parts?.getOrNull(1)?.toLongOrNull()
                if (total != null && blocks != null && total > 0L) {
                    val used = (blocks * STAT_BLOCK_SIZE).coerceIn(0L, total)
                    return@withContext DiskUsage(usedBytes = used, totalBytes = total).also {
                        synchronized(lastKnown) { lastKnown[imgPath] = it }
                    }
                }
            }
        }

        // Fallback for directory containers or un-stat'd files:
        val duCmd = "${Constants.BUSYBOX_BINARY_PATH} du -sk ${ContainerCommandBuilder.quote(imgPath)} 2>/dev/null"
        val duRes = Shell.cmd(duCmd).exec()
        val usedKb = duRes.out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull() ?: return@withContext null
        val usedBytes = usedKb * 1024L
        val file = File(imgPath)
        val totalBytes = file.parentFile?.totalSpace?.takeIf { it > 0L } ?: (usedBytes * 2).coerceAtLeast(1024L * 1024L * 1024L)
        val boundedUsed = usedBytes.coerceIn(0L, totalBytes)
        DiskUsage(usedBytes = boundedUsed, totalBytes = totalBytes).also {
            synchronized(lastKnown) { lastKnown[imgPath] = it }
        }
    }

    // ------------------------------------------------------------------
    // Size-sorted, tree-browsable disk usage explorer.
    // ------------------------------------------------------------------

    /** One entry (file or directory) inside a browsed container directory. */
    data class DiskEntry(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val isDirectory: Boolean
    )

    /**
     * Top-level directories that are essential to a working Linux boot. Any path
     * that is one of these, or nested inside one of these, is flagged as
     * [isSensitivePath] so the UI can warn before deleting it.
     */
    private val SENSITIVE_TOP_LEVEL_DIRS = setOf(
        "/bin", "/boot", "/dev", "/etc", "/lib", "/lib32", "/lib64", "/libx32",
        "/proc", "/run", "/sbin", "/sys", "/usr", "/var"
    )

    /** Collapses "", trailing slashes, etc. into a canonical absolute path (never empty). */
    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        val withLeadingSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (withLeadingSlash.length > 1 && withLeadingSlash.endsWith("/")) {
            withLeadingSlash.dropLast(1)
        } else {
            withLeadingSlash
        }
    }

    /** Joins a browsed directory path with a child entry name. */
    fun joinPath(parent: String, childName: String): String {
        val p = normalizePath(parent)
        return if (p == "/") "/$childName" else "$p/$childName"
    }

    /**
     * True if deleting/modifying [path] risks leaving the container unable to
     * boot (root itself, or anywhere under one of [SENSITIVE_TOP_LEVEL_DIRS]).
     */
    fun isSensitivePath(path: String): Boolean {
        val normalized = normalizePath(path)
        if (normalized == "/") return true
        return SENSITIVE_TOP_LEVEL_DIRS.any { dir -> normalized == dir || normalized.startsWith("$dir/") }
    }

    /**
     * Lists the immediate children of [path] inside container [containerName],
     * each with its size, sorted largest-first.
     *
     * Protected against hangs:
     * - Fast stat for files/symlinks (instant O(1))
     * - Skips recursive du on virtual pseudo-filesystems (/proc, /sys, /dev, /run) and Android storage mounts
     * - Uses one-file-system (-x) and timeout for directory sizing
     * - Coroutine timeout safeguard to prevent infinite loading
     */
    suspend fun listDirectory(containerName: String, path: String): Result<List<DiskEntry>> =
        withContext(Dispatchers.IO) {
            val (isRunning, _) = try {
                ContainerManager.checkContainerStatus(containerName)
            } catch (e: Exception) {
                Pair(false, null)
            }
            if (!isRunning) {
                return@withContext Result.failure(Exception("Container is stopped. Start container '$containerName' to explore files."))
            }

            val timeoutResult = withTimeoutOrNull(10000L) {
                try {
                    val targetPath = normalizePath(path)
                    val pathB64 = Base64.encodeToString(targetPath.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val script = """
                        p=${'$'}(echo "${'$'}1" | base64 -d)
                        if [ ! -d "${'$'}p" ]; then echo "__ERR_NOT_DIR__"; exit 1; fi
                        cd "${'$'}p" 2>/dev/null || { echo "__ERR_NOT_DIR__"; exit 1; }
                        for f in * .[!.]* ..?*; do
                            [ -e "${'$'}f" ] || [ -L "${'$'}f" ] || continue
                            [ "${'$'}f" != "." ] && [ "${'$'}f" != ".." ] || continue
                            if [ -d "${'$'}f" ] && [ ! -L "${'$'}f" ]; then t="d"; else t="f"; fi
                            
                            sz=0
                            if [ "${'$'}t" = "f" ]; then
                                sz=${'$'}(stat -c '%s' -- "${'$'}f" 2>/dev/null)
                                if [ -z "${'$'}sz" ]; then
                                    sz=${'$'}(ls -ld -- "${'$'}f" 2>/dev/null | awk '{print ${'$'}5}')
                                fi
                                [ -n "${'$'}sz" ] || sz=0
                                sz=${'$'}(( sz / 1024 ))
                            else
                                if [ "${'$'}p" = "/" ] && { [ "${'$'}f" = "proc" ] || [ "${'$'}f" = "sys" ] || [ "${'$'}f" = "dev" ] || [ "${'$'}f" = "run" ] || [ "${'$'}f" = "storage" ] || [ "${'$'}f" = "sdcard" ] || [ "${'$'}f" = "mnt" ] || [ "${'$'}f" = "acct" ] || [ "${'$'}f" = "config" ] || [ "${'$'}f" = "data" ]; }; then
                                    sz=0
                                else
                                    if command -v timeout >/dev/null 2>&1; then
                                        sz=${'$'}(timeout 2 du -sk -x -- "${'$'}f" 2>/dev/null | awk '{print ${'$'}1}')
                                    else
                                        sz=${'$'}(du -sk -x -- "${'$'}f" 2>/dev/null | awk '{print ${'$'}1}')
                                    fi
                                    [ -n "${'$'}sz" ] || sz=0
                                fi
                            fi
                            printf '%s	%s	%s\n' "${'$'}t" "${'$'}sz" "${'$'}f"
                        done
                    """.trimIndent()
                    val scriptB64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                        "run 'echo $scriptB64 | base64 -d | sh -s -- $pathB64 2>/dev/null'"
                    val result = Shell.cmd(cmd).exec()
                    val lines = result.out
                    if (!result.isSuccess || lines.any { it.trim() == "__ERR_NOT_DIR__" }) {
                        return@withTimeoutOrNull Result.failure(Exception("Unable to read directory: $targetPath"))
                    }
                    val entries = lines.mapNotNull { line ->
                        val parts = line.split("\t")
                        if (parts.size < 3) return@mapNotNull null
                        val isDir = parts[0] == "d"
                        val sizeKb = parts[1].toLongOrNull() ?: 0L
                        val name = parts.drop(2).joinToString("\t")
                        if (name.isBlank()) return@mapNotNull null
                        DiskEntry(
                            name = name,
                            path = joinPath(targetPath, name),
                            sizeBytes = sizeKb * 1024L,
                            isDirectory = isDir
                        )
                    }.sortedByDescending { it.sizeBytes }
                    Result.success(entries)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            timeoutResult ?: Result.failure(Exception("Timed out reading directory '$path'"))
        }

    /**
     * Deletes [path] (file or directory, recursively) inside container [containerName].
     */
    suspend fun deletePath(containerName: String, path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val timeoutResult = withTimeoutOrNull(10000L) {
                try {
                    val targetPath = normalizePath(path)
                    if (targetPath == "/") {
                        return@withTimeoutOrNull Result.failure(Exception("Refusing to delete the container root"))
                    }
                    val pathB64 = Base64.encodeToString(targetPath.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val script = """
                        p=${'$'}(echo "${'$'}1" | base64 -d)
                        case "${'$'}p" in
                            ""|"/") echo "__ERR_REFUSED__"; exit 1 ;;
                        esac
                        rm -rf -- "${'$'}p"
                        echo "__OK__"
                    """.trimIndent()
                    val scriptB64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                        "run 'echo $scriptB64 | base64 -d | sh -s -- $pathB64 2>/dev/null'"
                    val result = Shell.cmd(cmd).exec()
                    if (result.isSuccess && result.out.any { it.trim() == "__OK__" }) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception((result.err + result.out).joinToString("\n").ifBlank { "Delete failed" }))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            timeoutResult ?: Result.failure(Exception("Timed out deleting '$path'"))
        }
}
