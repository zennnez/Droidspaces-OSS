package com.droidspaces.app.util

import com.topjohnwu.superuser.Shell
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metadata representation of a container snapshot.
 */
data class ContainerSnapshot(
    val id: String,
    val containerName: String,
    val label: String,
    val description: String = "",
    val createdAtEpoch: Long,
    val sizeBytes: Long,
    val isSparseImage: Boolean,
    val isCompressed: Boolean = false,
    val compressionLevel: Int = 6,
    val sha256Hash: String = "",
    val snapshotFilePath: String,
    val configJson: String? = null
) {
    val formattedDate: String
        get() = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(createdAtEpoch))

    val formattedSize: String
        get() = MoveStorageOperations.formatBytes(sizeBytes)
}

object ContainerSnapshotManager {

    fun getSnapshotsDirectory(container: ContainerInfo): String {
        val baseDir = container.rootfsPath.substringBeforeLast(
            "/",
            ContainerManager.getContainerDirectory(container.name)
        )
        return "$baseDir/snapshots"
    }

    /**
     * List all snapshots available for this container.
     */
    fun listSnapshots(container: ContainerInfo): List<ContainerSnapshot> {
        val snapshotsDir = getSnapshotsDirectory(container)
        val listCmd = "mkdir -p \"$snapshotsDir\" && ls -1 \"$snapshotsDir\"/*.json 2>/dev/null"
        val output = Shell.cmd(listCmd).exec().out

        val snapshots = mutableListOf<ContainerSnapshot>()
        for (jsonPath in output) {
            if (jsonPath.isBlank() || !jsonPath.endsWith(".json")) continue
            try {
                val jsonContent = Shell.cmd("cat \"$jsonPath\"").exec().out.joinToString("\n")
                if (jsonContent.isBlank()) continue
                val obj = JSONObject(jsonContent)
                val id = obj.optString("id")
                val containerName = obj.optString("containerName", container.name)
                val label = obj.optString("label", "Snapshot")
                val description = obj.optString("description", "")
                val createdAt = obj.optLong("createdAtEpoch", System.currentTimeMillis())
                val sizeBytes = obj.optLong("sizeBytes", 0L)
                val isSparse = obj.optBoolean("isSparseImage", container.useSparseImage)
                val isCompressed = obj.optBoolean("isCompressed", false)
                val compressionLevel = obj.optInt("compressionLevel", 6)
                val sha256Hash = obj.optString("sha256Hash", "")
                val snapFile = obj.optString("snapshotFilePath", "")
                val configJson = if (obj.has("configJson")) obj.optString("configJson") else null

                if (id.isNotBlank() && snapFile.isNotBlank()) {
                    snapshots.add(
                        ContainerSnapshot(
                            id = id,
                            containerName = containerName,
                            label = label,
                            description = description,
                            createdAtEpoch = createdAt,
                            sizeBytes = sizeBytes,
                            isSparseImage = isSparse,
                            isCompressed = isCompressed,
                            compressionLevel = compressionLevel,
                            sha256Hash = sha256Hash,
                            snapshotFilePath = snapFile,
                            configJson = configJson
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore corrupt/partial JSON
            }
        }
        return snapshots.sortedByDescending { it.createdAtEpoch }
    }

    /**
     * Create a snapshot of the container's rootfs with optional compression and SHA-256 integrity hash.
     */
    fun createSnapshot(
        container: ContainerInfo,
        label: String,
        description: String = "",
        enableCompression: Boolean = false,
        compressionLevel: Int = 6,
        onProgress: (String) -> Unit = {}
    ): Boolean {
        val snapshotsDir = getSnapshotsDirectory(container)
        val epoch = System.currentTimeMillis()
        val id = "snap_${epoch}"
        val sanitizedLabel = label.trim().ifBlank { "Snapshot" }
        val clampedLevel = compressionLevel.coerceIn(1, 9)

        onProgress("Preparing snapshot directory...")
        Shell.cmd("mkdir -p \"$snapshotsDir\"").exec()

        val snapFile = if (container.useSparseImage) {
            if (enableCompression) "$snapshotsDir/${id}.img.gz" else "$snapshotsDir/${id}.img"
        } else {
            if (enableCompression) "$snapshotsDir/${id}.tar.gz" else "$snapshotsDir/${id}.tar"
        }

        val metaFile = "$snapshotsDir/${id}.json"
        val checksumFile = "$snapshotsDir/${id}.sha256"

        val copySuccess = if (container.useSparseImage) {
            if (enableCompression) {
                onProgress("Compressing sparse image (gzip level $clampedLevel)...")
                val cmd = "gzip -$clampedLevel -c \"${container.rootfsPath}\" > \"$snapFile\""
                Shell.cmd(cmd).exec().isSuccess
            } else {
                onProgress("Creating sparse image copy (using reflink if available)...")
                val cmd = "cp --reflink=auto \"${container.rootfsPath}\" \"$snapFile\" 2>/dev/null || cp \"${container.rootfsPath}\" \"$snapFile\""
                Shell.cmd(cmd).exec().isSuccess
            }
        } else {
            if (enableCompression) {
                onProgress("Archiving container rootfs (tar.gz level $clampedLevel)...")
                val cmd = "GZIP=\"-$clampedLevel\" tar -czf \"$snapFile\" -C \"${container.rootfsPath}\" ."
                Shell.cmd(cmd).exec().isSuccess
            } else {
                onProgress("Archiving container rootfs (uncompressed tar)...")
                val cmd = "tar -cf \"$snapFile\" -C \"${container.rootfsPath}\" ."
                Shell.cmd(cmd).exec().isSuccess
            }
        }

        if (!copySuccess) {
            Shell.cmd("rm -f \"$snapFile\" \"$metaFile\" \"$checksumFile\"").exec()
            return false
        }

        onProgress("Calculating SHA-256 integrity checksum...")
        val hashStr = Shell.cmd("sha256sum \"$snapFile\" 2>/dev/null | awk '{print \$1}'").exec().out.firstOrNull()?.trim() ?: ""
        if (hashStr.isNotBlank()) {
            Shell.cmd("echo '$hashStr  ${snapFile.substringAfterLast("/")}' > \"$checksumFile\"").exec()
        }

        onProgress("Recording snapshot metadata...")
        val sizeStr = Shell.cmd("stat -c %s \"$snapFile\" 2>/dev/null || wc -c < \"$snapFile\"").exec().out.firstOrNull()?.trim()
        val sizeBytes = sizeStr?.toLongOrNull() ?: 0L

        val meta = JSONObject().apply {
            put("id", id)
            put("containerName", container.name)
            put("label", sanitizedLabel)
            put("description", description.trim())
            put("createdAtEpoch", epoch)
            put("sizeBytes", sizeBytes)
            put("isSparseImage", container.useSparseImage)
            put("isCompressed", enableCompression)
            put("compressionLevel", clampedLevel)
            put("sha256Hash", hashStr)
            put("snapshotFilePath", snapFile)
        }

        val b64 = android.util.Base64.encodeToString(meta.toString().toByteArray(), android.util.Base64.NO_WRAP)
        val writeMetaCmd = "echo '$b64' | base64 -d > \"$metaFile\""
        Shell.cmd(writeMetaCmd).exec()

        return true
    }

    /**
     * Verify the SHA-256 checksum of a snapshot file against its recorded metadata.
     */
    fun verifyChecksum(snapshot: ContainerSnapshot): Pair<Boolean, String?> {
        if (snapshot.sha256Hash.isBlank()) {
            return true to null // No hash recorded (legacy snapshot)
        }
        val checkFileCmd = "[ -f \"${snapshot.snapshotFilePath}\" ]"
        if (!Shell.cmd(checkFileCmd).exec().isSuccess) {
            return false to "Snapshot file not found: ${snapshot.snapshotFilePath}"
        }
        val currentHash = Shell.cmd("sha256sum \"${snapshot.snapshotFilePath}\" 2>/dev/null | awk '{print \$1}'").exec().out.firstOrNull()?.trim() ?: ""
        if (currentHash.isBlank()) {
            return false to "Failed to compute snapshot checksum"
        }
        if (!currentHash.equals(snapshot.sha256Hash, ignoreCase = true)) {
            return false to "Checksum mismatch! Recorded: ${snapshot.sha256Hash.take(12)}..., Current: ${currentHash.take(12)}..."
        }
        return true to null
    }

    /**
     * Restore (rollback) a snapshot to active container rootfs after verifying SHA-256 integrity.
     */
    fun restoreSnapshot(
        container: ContainerInfo,
        snapshot: ContainerSnapshot,
        onProgress: (String) -> Unit = {}
    ): Result<Unit> {
        onProgress("Verifying snapshot SHA-256 integrity checksum...")
        val (verified, errorMsg) = verifyChecksum(snapshot)
        if (!verified) {
            return Result.failure(IllegalStateException(errorMsg ?: "SHA-256 integrity check failed! Snapshot file may be corrupted."))
        }

        val restoreSuccess = if (snapshot.isSparseImage) {
            if (snapshot.isCompressed || snapshot.snapshotFilePath.endsWith(".gz")) {
                onProgress("Decompressing and restoring sparse image snapshot...")
                val cmd = "gzip -dc \"${snapshot.snapshotFilePath}\" > \"${container.rootfsPath}\""
                Shell.cmd(cmd).exec().isSuccess
            } else {
                onProgress("Restoring sparse image snapshot...")
                val cmd = "cp --reflink=auto \"${snapshot.snapshotFilePath}\" \"${container.rootfsPath}\" 2>/dev/null || cp \"${snapshot.snapshotFilePath}\" \"${container.rootfsPath}\""
                Shell.cmd(cmd).exec().isSuccess
            }
        } else {
            if (snapshot.isCompressed || snapshot.snapshotFilePath.endsWith(".tar.gz") || snapshot.snapshotFilePath.endsWith(".tgz")) {
                onProgress("Extracting compressed rootfs snapshot...")
                val cmd = "rm -rf \"${container.rootfsPath}\"/* && tar -xzf \"${snapshot.snapshotFilePath}\" -C \"${container.rootfsPath}\""
                Shell.cmd(cmd).exec().isSuccess
            } else {
                onProgress("Extracting uncompressed rootfs snapshot...")
                val cmd = "rm -rf \"${container.rootfsPath}\"/* && tar -xf \"${snapshot.snapshotFilePath}\" -C \"${container.rootfsPath}\""
                Shell.cmd(cmd).exec().isSuccess
            }
        }

        return if (restoreSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException("Failed to write snapshot data to ${container.rootfsPath}"))
        }
    }

    /**
     * Delete a snapshot.
     */
    fun deleteSnapshot(container: ContainerInfo, snapshotId: String): Boolean {
        val snapshotsDir = getSnapshotsDirectory(container)
        val cmd = "rm -f \"$snapshotsDir/${snapshotId}\"* \"$snapshotsDir/${snapshotId}.json\" \"$snapshotsDir/${snapshotId}.sha256\""
        return Shell.cmd(cmd).exec().isSuccess
    }
}
