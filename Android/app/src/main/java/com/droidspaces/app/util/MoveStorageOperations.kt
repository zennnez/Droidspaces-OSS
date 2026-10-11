package com.droidspaces.app.util

import android.content.Context

/** Which step of the copy -> verify -> delete pipeline is currently running. */
enum class MoveStoragePhase { COPYING, VERIFYING, DELETING }

/**
 * How thoroughly [MoveStorageOperations] proves the destination matches the
 * source before the source is deleted.
 */
enum class MoveVerificationMethod {
    /** Compare sizes only (file size, or total tree size + entry count for directories). Fastest, weakest guarantee. */
    SIZE,
    /** Hash every item and compare (whole file for images; every file individually for directories). Slowest, strongest per-item guarantee. */
    SHA256,
    /** Stream all data through one combined hash instead of many. */
    TAR,
    /** Skip verification entirely -- only the copy command's own exit code is trusted. */
    NONE
}

/**
 * Live progress callback for [MoveStorageOperations.moveViaCopyVerifyDelete]
 * and [MoveStorageOperations.deleteSource].
 *
 * @param percent 0-100 when known.
 * @param doneBytes / @param totalBytes Raw byte counts backing [percent].
 */
typealias MoveProgressCallback = (
    phase: MoveStoragePhase,
    percent: Int?,
    doneBytes: Long?,
    totalBytes: Long?,
    detail: String
) -> Unit

/** Required-vs-available space for a prospective move, from [MoveStorageOperations.checkCapacity]. */
data class MoveCapacityCheck(
    val requiredBytes: Long,
    val availableBytes: Long,
    val sufficient: Boolean
)

object MoveStorageOperations {
    private val BB get() = Constants.BUSYBOX_BINARY_PATH

    private const val CAPACITY_SAFETY_MARGIN_BYTES = 64L * 1024 * 1024 // 64MB
    private const val CAPACITY_SAFETY_MARGIN_FRACTION = 0.02 // +2%

    /** Formats a byte count as a human-readable size with appropriate units (B/KB/MB/GB/TB). */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return if (unitIndex < 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
    }

    /**
     * Checks whether the destination has enough free space for the source
     * data before copying starts.
     */
    suspend fun checkCapacity(
        quotedSrc: String,
        quotedDst: String,
        isSingleFile: Boolean,
        context: Context,
        targetPath: String?,
        forceNsenter: Boolean
    ): Result<MoveCapacityCheck> {
        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("SRC=$quotedSrc")
            appendLine("DST=$quotedDst")
            appendLine("BB=\"$BB\"")
            appendLine()
            if (isSingleFile) {
                appendLine("REQUIRED=\$(stat -c%s \"\$SRC\" 2>/dev/null)")
                appendLine("if [ -z \"\$REQUIRED\" ]; then REQUIRED=\$(wc -c < \"\$SRC\" 2>/dev/null); fi")
            } else {
                appendLine("REQUIRED_KB=\$(\"\$BB\" du -sk \"\$SRC\" 2>/dev/null | \"\$BB\" awk '{print \$1}')")
                appendLine("[ -z \"\$REQUIRED_KB\" ] && REQUIRED_KB=0")
                appendLine("REQUIRED=\$((REQUIRED_KB * 1024))")
            }
            appendLine("[ -z \"\$REQUIRED\" ] && REQUIRED=0")
            appendLine()
            appendLine("DST_CHECK=\"\$DST\"")
            appendLine("while [ ! -e \"\$DST_CHECK\" ] && [ \"\$DST_CHECK\" != \"/\" ] && [ -n \"\$DST_CHECK\" ]; do")
            appendLine("    DST_CHECK=\$(dirname \"\$DST_CHECK\")")
            appendLine("done")
            appendLine("[ -z \"\$DST_CHECK\" ] && DST_CHECK=\"/\"")
            appendLine()
            appendLine("DST_PHYS=\"\$DST_CHECK\"")
            appendLine("case \"\$DST_CHECK\" in")
            appendLine("    /storage/emulated/*)")
            appendLine("        CANDIDATE=\"/data/media/\${DST_CHECK#/storage/emulated/}\"")
            appendLine("        [ -e \"\$CANDIDATE\" ] && DST_PHYS=\"\$CANDIDATE\"")
            appendLine("        ;;")
            appendLine("esac")
            appendLine()
            appendLine("AVAILABLE_KB=\$(\"\$BB\" df -k \"\$DST_PHYS\" 2>/dev/null | \"\$BB\" tail -n 1 | \"\$BB\" awk '{print \$(NF-2)}')")
            appendLine("case \"\$AVAILABLE_KB\" in")
            appendLine("    ''|*[!0-9-]*|-*)")
            appendLine("        if [ \"\$DST_PHYS\" != \"\$DST_CHECK\" ]; then")
            appendLine("            AVAILABLE_KB=\$(\"\$BB\" df -k \"\$DST_CHECK\" 2>/dev/null | \"\$BB\" tail -n 1 | \"\$BB\" awk '{print \$(NF-2)}')")
            appendLine("        fi")
            appendLine("        ;;")
            appendLine("esac")
            appendLine("case \"\$AVAILABLE_KB\" in ''|*[!0-9-]*|-*) AVAILABLE_KB=0 ;; esac")
            appendLine("AVAILABLE=\$((AVAILABLE_KB * 1024))")
            appendLine()
            appendLine("echo \"[MOVE-CAPACITY] \$REQUIRED \$AVAILABLE\"")
        }

        val result = RootNamespace.runScript(script, context, targetPath, forceNsenter)
        val line = (result.out + result.err).firstOrNull { it.trim().startsWith("[MOVE-CAPACITY]") }
            ?: return Result.failure(Exception("Could not determine required/available space"))

        val parts = line.trim().removePrefix("[MOVE-CAPACITY]").trim().split(Regex("\\s+"))
        val required = parts.getOrNull(0)?.toLongOrNull()
        val available = parts.getOrNull(1)?.toLongOrNull()
        if (required == null || available == null) {
            return Result.failure(Exception("Could not parse required/available space"))
        }

        val requiredWithMargin = required + maxOf(
            CAPACITY_SAFETY_MARGIN_BYTES,
            (required * CAPACITY_SAFETY_MARGIN_FRACTION).toLong()
        )
        return Result.success(
            MoveCapacityCheck(
                requiredBytes = required.coerceAtLeast(0),
                availableBytes = available.coerceAtLeast(0),
                sufficient = available >= requiredWithMargin
            )
        )
    }

    /**
     * Runs the full copy -> verify -> delete pipeline for a single rootfs.img
     * or a rootfs directory tree.
     */
    suspend fun moveViaCopyVerifyDelete(
        quotedSrc: String,
        quotedDst: String,
        isSingleFile: Boolean,
        verificationMethod: MoveVerificationMethod,
        context: Context,
        targetPath: String?,
        forceNsenter: Boolean,
        logger: ContainerLogger?,
        onProgress: MoveProgressCallback?
    ): Result<Unit> {
        val copyVerifyScript = if (isSingleFile) {
            buildFileCopyVerifyScript(quotedSrc, quotedDst, verificationMethod)
        } else {
            buildDirCopyVerifyScript(quotedSrc, quotedDst, verificationMethod)
        }

        var copyFailed = false
        var verifyFailed = false
        var failureDetail = ""
        val mismatches = mutableListOf<String>()

        val copyVerifyResult = RootNamespace.runScriptStreaming(
            copyVerifyScript, context, targetPath, forceNsenter
        ) { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("[MOVE-COPY-START]") -> {
                    onProgress?.invoke(MoveStoragePhase.COPYING, 0, 0L, null, "Starting copy...")
                    logger?.logImmediate(android.util.Log.INFO, "Starting copy...")
                }
                line.startsWith("[MOVE-COPY-PROGRESS]") -> {
                    val parts = line.substringAfter("[MOVE-COPY-PROGRESS]").trim().split(Regex("\\s+"))
                    val done = parts.getOrNull(0)?.toLongOrNull()
                    val total = parts.getOrNull(1)?.toLongOrNull()
                    val pct = if (done != null && total != null && total > 0) {
                        ((done.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
                    } else null
                    val detail = if (done != null && total != null) {
                        "Copying: ${formatBytes(done)} / ${formatBytes(total)} (${pct ?: 0}%)"
                    } else {
                        "Copying data to new location..."
                    }
                    onProgress?.invoke(MoveStoragePhase.COPYING, pct, done, total, detail)
                }
                line.startsWith("[MOVE-COPY-DONE]") -> {
                    logger?.logImmediate(android.util.Log.INFO, "Copy complete.")
                }
                line.startsWith("[MOVE-COPY-FAIL]") -> {
                    copyFailed = true
                    failureDetail = line.substringAfter("[MOVE-COPY-FAIL]").trim()
                    logger?.logImmediate(android.util.Log.ERROR, "Copy failed: $failureDetail")
                }
                line.startsWith("[MOVE-VERIFY-START]") -> {
                    val msg = when (verificationMethod) {
                        MoveVerificationMethod.NONE -> "Skipping verification (per your setting)..."
                        else -> "Verifying copy matches source..."
                    }
                    onProgress?.invoke(MoveStoragePhase.VERIFYING, 0, 0L, null, msg)
                    logger?.logImmediate(android.util.Log.INFO, msg)
                }
                line.startsWith("[MOVE-VERIFY-PROGRESS]") -> {
                    val parts = line.substringAfter("[MOVE-VERIFY-PROGRESS]").trim().split(Regex("\\s+"))
                    val pct = parts.getOrNull(0)?.toIntOrNull()
                    val done = parts.getOrNull(1)?.toLongOrNull()
                    val total = parts.getOrNull(2)?.toLongOrNull()
                    val tag = parts.getOrNull(3)
                    val detail = if (done != null && total != null) {
                        val label = when (tag) {
                            "src" -> "Verifying (Source)"
                            "dst" -> "Verifying (Destination)"
                            else -> "Verifying"
                        }
                        "$label: ${formatBytes(done)} / ${formatBytes(total)} (${pct ?: 0}%)"
                    } else if (pct != null) {
                        "Verifying copy matches source ($pct%)..."
                    } else {
                        "Verifying copy matches source..."
                    }
                    onProgress?.invoke(MoveStoragePhase.VERIFYING, pct, done, total, detail)
                }
                line.startsWith("[MOVE-VERIFY-MISMATCH]") -> {
                    val rel = line.substringAfter("[MOVE-VERIFY-MISMATCH]").trim()
                    mismatches.add(rel)
                    logger?.logImmediate(android.util.Log.ERROR, "Mismatch: $rel")
                }
                line.startsWith("[MOVE-VERIFY-FAIL]") -> {
                    verifyFailed = true
                    failureDetail = line.substringAfter("[MOVE-VERIFY-FAIL]").trim()
                    logger?.logImmediate(android.util.Log.ERROR, "Verification failed: $failureDetail")
                }
                line.startsWith("[MOVE-VERIFY-OK]") -> {
                    val msg = when (verificationMethod) {
                        MoveVerificationMethod.NONE -> "Verification skipped (per your setting)."
                        else -> "Verified: destination matches source (100%)."
                    }
                    onProgress?.invoke(MoveStoragePhase.VERIFYING, 100, null, null, msg)
                    logger?.logImmediate(android.util.Log.INFO, msg)
                }
                line.isNotBlank() -> {
                    logger?.logImmediate(android.util.Log.INFO, line)
                }
            }
        }

        if (copyFailed) {
            return Result.failure(Exception("Copy failed: $failureDetail"))
        }
        if (verifyFailed || !copyVerifyResult.isSuccess) {
            val reason = if (mismatches.isNotEmpty()) {
                "Verification failed -- ${mismatches.size} file(s) differ from source " +
                    "(source left untouched): ${mismatches.take(5).joinToString(", ")}" +
                    if (mismatches.size > 5) ", ..." else ""
            } else if (failureDetail.isNotBlank()) {
                "Verification failed: $failureDetail (source left untouched)"
            } else {
                "Verification failed (source left untouched)"
            }
            return Result.failure(Exception(reason))
        }

        return Result.success(Unit)
    }

    /**
     * Phase 3: delete the source copy.
     */
    suspend fun deleteSource(
        quotedSrc: String,
        isSingleFile: Boolean,
        context: Context,
        targetPath: String?,
        forceNsenter: Boolean,
        logger: ContainerLogger?,
        onProgress: MoveProgressCallback?
    ): Result<Unit> {
        val script = if (isSingleFile) buildFileDeleteScript(quotedSrc) else buildDirDeleteScript(quotedSrc)

        var deleteFailed = false
        var failureDetail = ""

        val result = RootNamespace.runScriptStreaming(script, context, targetPath, forceNsenter) { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("[MOVE-DELETE-START]") -> {
                    onProgress?.invoke(MoveStoragePhase.DELETING, 0, 0L, null, "Removing old copy...")
                    logger?.logImmediate(android.util.Log.INFO, "Removing old copy...")
                }
                line.startsWith("[MOVE-DELETE-PROGRESS]") -> {
                    val parts = line.substringAfter("[MOVE-DELETE-PROGRESS]").trim().split(Regex("\\s+"))
                    val pct = parts.getOrNull(0)?.toIntOrNull()
                    val arg1 = parts.getOrNull(1)?.toLongOrNull()
                    val arg2 = parts.getOrNull(2)?.toLongOrNull()
                    val detail = if (arg1 != null && arg2 != null) {
                        if (isSingleFile) {
                            "Removing old copy: ${formatBytes(arg1)} / ${formatBytes(arg2)} (${pct ?: 0}%)"
                        } else {
                            "Removing old copy: $arg1 / $arg2 files (${pct ?: 0}%)"
                        }
                    } else if (pct != null) {
                        "Removing old copy ($pct%)..."
                    } else {
                        "Removing old copy..."
                    }
                    onProgress?.invoke(MoveStoragePhase.DELETING, pct, if (isSingleFile) arg1 else null, if (isSingleFile) arg2 else null, detail)
                }
                line.startsWith("[MOVE-DELETE-DONE]") -> {
                    onProgress?.invoke(MoveStoragePhase.DELETING, 100, null, null, "Old copy removed (100%).")
                    logger?.logImmediate(android.util.Log.INFO, "Old copy removed.")
                }
                line.startsWith("[MOVE-DELETE-FAIL]") -> {
                    deleteFailed = true
                    failureDetail = line.substringAfter("[MOVE-DELETE-FAIL]").trim()
                    logger?.logImmediate(android.util.Log.ERROR, "Couldn't remove old copy: $failureDetail")
                }
                line.isNotBlank() -> {
                    logger?.logImmediate(android.util.Log.INFO, line)
                }
            }
        }

        return if (deleteFailed || !result.isSuccess) {
            Result.failure(Exception(failureDetail.ifBlank { "Failed to remove old copy" }))
        } else {
            Result.success(Unit)
        }
    }

    // ── Script builders ─────────────────────────────────────────────────────

    private fun buildFileCopyVerifyScript(
        quotedSrc: String,
        quotedDst: String,
        method: MoveVerificationMethod
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("SRC=$quotedSrc")
        appendLine("DST=$quotedDst")
        appendLine("BB=\"$BB\"")
        appendLine()
        appendLine("echo \"[MOVE-COPY-START]\"")
        appendLine("SRC_SIZE=\$(stat -c%s \"\$SRC\" 2>/dev/null)")
        appendLine("if [ -z \"\$SRC_SIZE\" ]; then SRC_SIZE=\$(wc -c < \"\$SRC\" 2>/dev/null); fi")
        appendLine("if [ -z \"\$SRC_SIZE\" ] || [ \"\$SRC_SIZE\" -eq 0 ]; then")
        appendLine("    echo \"[MOVE-COPY-FAIL] could not determine source size\"")
        appendLine("    exit 1")
        appendLine("fi")
        appendLine()
        appendLine("rm -f \"\$DST.cperr\" \"\$DST.done\" 2>/dev/null")
        appendLine("( \"\$BB\" cp \"\$SRC\" \"\$DST\" 2>\"\$DST.cperr\"; echo \$? > \"\$DST.done\" ) &")
        appendLine("CP_PID=\$!")
        appendLine("LAST_BYTES=-1")
        appendLine("while [ ! -f \"\$DST.done\" ]; do")
        appendLine("    CUR=0")
        appendLine("    if [ -f \"/proc/\$CP_PID/io\" ]; then")
        appendLine("        CUR=\$(\"\$BB\" grep -E '^(wchar|rchar):' \"/proc/\$CP_PID/io\" 2>/dev/null | \"\$BB\" awk 'NR==1{print \$2}')")
        appendLine("    fi")
        appendLine("    if [ -z \"\$CUR\" ] || [ \"\$CUR\" -le 0 ]; then")
        appendLine("        CUR=\$(\"\$BB\" grep '^pos:' /proc/\$CP_PID/fdinfo/* 2>/dev/null | \"\$BB\" awk '{print \$2}' | \"\$BB\" sort -nr 2>/dev/null | \"\$BB\" head -n1)")
        appendLine("    fi")
        appendLine("    if [ -z \"\$CUR\" ] || [ \"\$CUR\" -le 0 ]; then")
        appendLine("        BLKS=\$(stat -c%b \"\$DST\" 2>/dev/null)")
        appendLine("        [ -n \"\$BLKS\" ] && CUR=\$(( BLKS * 512 ))")
        appendLine("    fi")
        appendLine("    [ -z \"\$CUR\" ] && CUR=0")
        appendLine("    [ \"\$CUR\" -gt \"\$SRC_SIZE\" ] && CUR=\$SRC_SIZE")
        appendLine("    if [ \"\$CUR\" -ne \"\$LAST_BYTES\" ]; then")
        appendLine("        echo \"[MOVE-COPY-PROGRESS] \$CUR \$SRC_SIZE\"")
        appendLine("        LAST_BYTES=\$CUR")
        appendLine("    fi")
        appendLine("    sleep 0.3 2>/dev/null || sleep 1")
        appendLine("done")
        appendLine("CP_RC=\$(cat \"\$DST.done\" 2>/dev/null)")
        appendLine("[ -z \"\$CP_RC\" ] && CP_RC=1")
        appendLine("rm -f \"\$DST.done\" 2>/dev/null")
        appendLine("if [ \"\$CP_RC\" -ne 0 ]; then")
        appendLine("    ERR=\$(cat \"\$DST.cperr\" 2>/dev/null)")
        appendLine("    rm -f \"\$DST.cperr\" \"\$DST\" 2>/dev/null")
        appendLine("    echo \"[MOVE-COPY-FAIL] cp exited \$CP_RC: \$ERR\"")
        appendLine("    exit 1")
        appendLine("fi")
        appendLine("rm -f \"\$DST.cperr\" 2>/dev/null")
        appendLine("echo \"[MOVE-COPY-PROGRESS] \$SRC_SIZE \$SRC_SIZE\"")
        appendLine("echo \"[MOVE-COPY-DONE]\"")
        appendLine()
        appendLine("echo \"[MOVE-VERIFY-START]\"")
        appendLine("DST_SIZE=\$(stat -c%s \"\$DST\" 2>/dev/null)")
        appendLine("if [ \"\$DST_SIZE\" != \"\$SRC_SIZE\" ]; then")
        appendLine("    echo \"[MOVE-VERIFY-FAIL] size mismatch: source=\$SRC_SIZE dest=\$DST_SIZE\"")
        appendLine("    rm -f \"\$DST\" 2>/dev/null")
        appendLine("    exit 1")
        appendLine("fi")
        appendLine()
        when (method) {
            MoveVerificationMethod.SIZE -> {
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 100 \$SRC_SIZE \$SRC_SIZE done\"")
                appendLine("echo \"[MOVE-VERIFY-OK]\"")
            }
            MoveVerificationMethod.NONE -> {
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 100 \$SRC_SIZE \$SRC_SIZE done\"")
                appendLine("echo \"[MOVE-VERIFY-OK]\"")
            }
            MoveVerificationMethod.SHA256, MoveVerificationMethod.TAR -> {
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 0 0 \$SRC_SIZE src\"")
                appendLine("\"\$BB\" sha256sum \"\$SRC\" > \"\$DST.srcsum\" 2>/dev/null &")
                appendLine("HPID=\$!")
                appendLine("LAST_HBYTES=-1")
                appendLine("while kill -0 \"\$HPID\" 2>/dev/null; do")
                appendLine("    CUR=0")
                appendLine("    if [ -f \"/proc/\$HPID/io\" ]; then")
                appendLine("        CUR=\$(\"\$BB\" grep '^rchar:' \"/proc/\$HPID/io\" 2>/dev/null | \"\$BB\" awk '{print \$2}')")
                appendLine("    fi")
                appendLine("    if [ -z \"\$CUR\" ] || [ \"\$CUR\" -le 0 ]; then")
                appendLine("        CUR=\$(\"\$BB\" grep '^pos:' /proc/\$HPID/fdinfo/* 2>/dev/null | \"\$BB\" awk '{print \$2}' | \"\$BB\" sort -nr 2>/dev/null | \"\$BB\" head -n1)")
                appendLine("    fi")
                appendLine("    [ -z \"\$CUR\" ] && CUR=0")
                appendLine("    [ \"\$CUR\" -gt \"\$SRC_SIZE\" ] && CUR=\$SRC_SIZE")
                appendLine("    if [ \"\$CUR\" -ne \"\$LAST_HBYTES\" ]; then")
                appendLine("        PCT=\$(( (CUR * 50) / SRC_SIZE ))")
                appendLine("        echo \"[MOVE-VERIFY-PROGRESS] \$PCT \$CUR \$SRC_SIZE src\"")
                appendLine("        LAST_HBYTES=\$CUR")
                appendLine("    fi")
                appendLine("    sleep 0.3 2>/dev/null || sleep 1")
                appendLine("done")
                appendLine("wait \"\$HPID\" 2>/dev/null || true")
                appendLine("SRC_HASH=\$(awk '{print \$1}' \"\$DST.srcsum\" 2>/dev/null)")
                appendLine("rm -f \"\$DST.srcsum\" 2>/dev/null")
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 50 \$SRC_SIZE \$SRC_SIZE src\"")
                appendLine()
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 50 0 \$SRC_SIZE dst\"")
                appendLine("\"\$BB\" sha256sum \"\$DST\" > \"\$DST.dstsum\" 2>/dev/null &")
                appendLine("HPID2=\$!")
                appendLine("LAST_HBYTES2=-1")
                appendLine("while kill -0 \"\$HPID2\" 2>/dev/null; do")
                appendLine("    CUR=0")
                appendLine("    if [ -f \"/proc/\$HPID2/io\" ]; then")
                appendLine("        CUR=\$(\"\$BB\" grep '^rchar:' \"/proc/\$HPID2/io\" 2>/dev/null | \"\$BB\" awk '{print \$2}')")
                appendLine("    fi")
                appendLine("    if [ -z \"\$CUR\" ] || [ \"\$CUR\" -le 0 ]; then")
                appendLine("        CUR=\$(\"\$BB\" grep '^pos:' /proc/\$HPID2/fdinfo/* 2>/dev/null | \"\$BB\" awk '{print \$2}' | \"\$BB\" sort -nr 2>/dev/null | \"\$BB\" head -n1)")
                appendLine("    fi")
                appendLine("    [ -z \"\$CUR\" ] && CUR=0")
                appendLine("    [ \"\$CUR\" -gt \"\$SRC_SIZE\" ] && CUR=\$SRC_SIZE")
                appendLine("    if [ \"\$CUR\" -ne \"\$LAST_HBYTES2\" ]; then")
                appendLine("        PCT=\$(( 50 + (CUR * 50) / SRC_SIZE ))")
                appendLine("        echo \"[MOVE-VERIFY-PROGRESS] \$PCT \$CUR \$SRC_SIZE dst\"")
                appendLine("        LAST_HBYTES2=\$CUR")
                appendLine("    fi")
                appendLine("    sleep 0.3 2>/dev/null || sleep 1")
                appendLine("done")
                appendLine("wait \"\$HPID2\" 2>/dev/null || true")
                appendLine("DST_HASH=\$(awk '{print \$1}' \"\$DST.dstsum\" 2>/dev/null)")
                appendLine("rm -f \"\$DST.dstsum\" 2>/dev/null")
                appendLine()
                appendLine("if [ -z \"\$SRC_HASH\" ] || [ -z \"\$DST_HASH\" ] || [ \"\$SRC_HASH\" != \"\$DST_HASH\" ]; then")
                appendLine("    echo \"[MOVE-VERIFY-FAIL] checksum mismatch (source=\$SRC_HASH dest=\$DST_HASH)\"")
                appendLine("    rm -f \"\$DST\" 2>/dev/null")
                appendLine("    exit 1")
                appendLine("fi")
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 100 \$SRC_SIZE \$SRC_SIZE done\"")
                appendLine("echo \"[MOVE-VERIFY-OK]\"")
            }
        }
    }

    private fun buildFileDeleteScript(quotedSrc: String): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("SRC=$quotedSrc")
        appendLine()
        appendLine("echo \"[MOVE-DELETE-START]\"")
        appendLine("SRC_SIZE=\$(stat -c%s \"\$SRC\" 2>/dev/null)")
        appendLine("[ -z \"\$SRC_SIZE\" ] && SRC_SIZE=0")
        appendLine("if [ -e \"\$SRC\" ]; then")
        appendLine("    echo \"[MOVE-DELETE-PROGRESS] 0 0 \$SRC_SIZE\"")
        appendLine("    ERR=\$(rm -f \"\$SRC\" 2>&1)")
        appendLine("    RC=\$?")
        appendLine("    if [ \"\$RC\" -ne 0 ]; then")
        appendLine("        echo \"[MOVE-DELETE-FAIL] \$ERR\"")
        appendLine("        exit 1")
        appendLine("    fi")
        appendLine("fi")
        appendLine("echo \"[MOVE-DELETE-PROGRESS] 100 \$SRC_SIZE \$SRC_SIZE\"")
        appendLine("echo \"[MOVE-DELETE-DONE]\"")
    }

    private fun buildDirCopyVerifyScript(
        quotedSrc: String,
        quotedDst: String,
        method: MoveVerificationMethod
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("SRC=$quotedSrc")
        appendLine("DST=$quotedDst")
        appendLine("BB=\"$BB\"")
        appendLine()
        appendLine("echo \"[MOVE-COPY-START]\"")
        appendLine("TOTAL_KB=\$(\"\$BB\" du -sk \"\$SRC\" 2>/dev/null | \"\$BB\" awk '{print \$1}')")
        appendLine("[ -z \"\$TOTAL_KB\" ] && TOTAL_KB=0")
        appendLine("TOTAL_BYTES=\$((TOTAL_KB * 1024))")
        appendLine("mkdir -p \"\$DST\" 2>/dev/null")
        appendLine("rm -f \"\$DST.cperr\" \"\$DST.done\" 2>/dev/null")
        appendLine("( \"\$BB\" cp -a \"\$SRC/.\" \"\$DST/\" 2>\"\$DST.cperr\"; echo \$? > \"\$DST.done\" ) &")
        appendLine("LAST_KB=-1")
        appendLine("while [ ! -f \"\$DST.done\" ]; do")
        appendLine("    sleep 0.4 2>/dev/null || sleep 1")
        appendLine("    CUR_KB=\$(\"\$BB\" du -sk \"\$DST\" 2>/dev/null | \"\$BB\" awk '{print \$1}')")
        appendLine("[ -z \"\$CUR_KB\" ] && CUR_KB=0")
        appendLine("[ \"\$TOTAL_KB\" -gt 0 ] && [ \"\$CUR_KB\" -gt \"\$TOTAL_KB\" ] && CUR_KB=\$TOTAL_KB")
        appendLine("    if [ \"\$CUR_KB\" -ne \"\$LAST_KB\" ]; then")
        appendLine("        echo \"[MOVE-COPY-PROGRESS] \$((CUR_KB * 1024)) \$TOTAL_BYTES\"")
        appendLine("        LAST_KB=\$CUR_KB")
        appendLine("    fi")
        appendLine("done")
        appendLine("CP_RC=\$(cat \"\$DST.done\" 2>/dev/null)")
        appendLine("[ -z \"\$CP_RC\" ] && CP_RC=1")
        appendLine("rm -f \"\$DST.done\" 2>/dev/null")
        appendLine("if [ \"\$CP_RC\" -ne 0 ]; then")
        appendLine("    ERR=\$(cat \"\$DST.cperr\" 2>/dev/null)")
        appendLine("    rm -f \"\$DST.cperr\" 2>/dev/null")
        appendLine("    rm -rf \"\$DST\" 2>/dev/null")
        appendLine("    echo \"[MOVE-COPY-FAIL] cp exited \$CP_RC: \$ERR\"")
        appendLine("    exit 1")
        appendLine("fi")
        appendLine("rm -f \"\$DST.cperr\" 2>/dev/null")
        appendLine("echo \"[MOVE-COPY-PROGRESS] \$TOTAL_BYTES \$TOTAL_BYTES\"")
        appendLine("echo \"[MOVE-COPY-DONE]\"")
        appendLine()
        appendLine("echo \"[MOVE-VERIFY-START]\"")
        appendLine("SRC_COUNT=\$(\"\$BB\" find \"\$SRC\" ! -type d 2>/dev/null | \"\$BB\" wc -l | tr -d ' ')")
        appendLine("DST_COUNT=\$(\"\$BB\" find \"\$DST\" ! -type d 2>/dev/null | \"\$BB\" wc -l | tr -d ' ')")
        appendLine("if [ \"\$SRC_COUNT\" != \"\$DST_COUNT\" ]; then")
        appendLine("    echo \"[MOVE-VERIFY-FAIL] file count mismatch: source=\$SRC_COUNT dest=\$DST_COUNT\"")
        appendLine("    rm -rf \"\$DST\" 2>/dev/null")
        appendLine("    exit 1")
        appendLine("fi")
        appendLine()
        when (method) {
            MoveVerificationMethod.NONE -> {
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 100\"")
                appendLine("echo \"[MOVE-VERIFY-OK]\"")
            }
            MoveVerificationMethod.SIZE -> {
                appendLine("SRC_KB=\$(\"\$BB\" du -sk \"\$SRC\" 2>/dev/null | \"\$BB\" awk '{print \$1}')")
                appendLine("DST_KB=\$(\"\$BB\" du -sk \"\$DST\" 2>/dev/null | \"\$BB\" awk '{print \$1}')")
                appendLine("if [ \"\$SRC_KB\" != \"\$DST_KB\" ]; then")
                appendLine("    echo \"[MOVE-VERIFY-FAIL] total size mismatch: source=\${SRC_KB}KB dest=\${DST_KB}KB\"")
                appendLine("    rm -rf \"\$DST\" 2>/dev/null")
                appendLine("    exit 1")
                appendLine("fi")
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 100\"")
                appendLine("echo \"[MOVE-VERIFY-OK]\"")
            }
            MoveVerificationMethod.TAR -> {
                appendLine("(cd \"\$SRC\" && \"\$BB\" find . ! -type d 2>/dev/null | \"\$BB\" sort | while IFS= read -r F; do")
                appendLine("    if [ -L \"\$F\" ]; then")
                appendLine("        printf '%s\\x00L\\x00' \"\$F\"; readlink \"\$F\" 2>/dev/null; printf '\\x00'")
                appendLine("    elif [ -f \"\$F\" ]; then")
                appendLine("        printf '%s\\x00F\\x00' \"\$F\"; cat \"\$F\" 2>/dev/null; printf '\\x00'")
                appendLine("    else")
                appendLine("        printf '%s\\x00O\\x00\\x00' \"\$F\"")
                appendLine("    fi")
                appendLine("done) | \"\$BB\" sha256sum > \"\$DST.srcsum\" 2>/dev/null &")
                appendLine("HPID=\$!")
                appendLine("while kill -0 \"\$HPID\" 2>/dev/null; do")
                appendLine("    sleep 1")
                appendLine("    echo \"[MOVE-VERIFY-PROGRESS] 35\"")
                appendLine("done")
                appendLine("wait \"\$HPID\"")
                appendLine("SRC_HASH=\$(awk '{print \$1}' \"\$DST.srcsum\" 2>/dev/null)")
                appendLine("rm -f \"\$DST.srcsum\" 2>/dev/null")
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 50\"")
                appendLine()
                appendLine("(cd \"\$DST\" && \"\$BB\" find . ! -type d 2>/dev/null | \"\$BB\" sort | while IFS= read -r F; do")
                appendLine("    if [ -L \"\$F\" ]; then")
                appendLine("        printf '%s\\x00L\\x00' \"\$F\"; readlink \"\$F\" 2>/dev/null; printf '\\x00'")
                appendLine("    elif [ -f \"\$F\" ]; then")
                appendLine("        printf '%s\\x00F\\x00' \"\$F\"; cat \"\$F\" 2>/dev/null; printf '\\x00'")
                appendLine("    else")
                appendLine("        printf '%s\\x00O\\x00\\x00' \"\$F\"")
                appendLine("    fi")
                appendLine("done) | \"\$BB\" sha256sum > \"\$DST.dstsum\" 2>/dev/null &")
                appendLine("HPID2=\$!")
                appendLine("while kill -0 \"\$HPID2\" 2>/dev/null; do")
                appendLine("    sleep 1")
                appendLine("    echo \"[MOVE-VERIFY-PROGRESS] 85\"")
                appendLine("done")
                appendLine("wait \"\$HPID2\"")
                appendLine("DST_HASH=\$(awk '{print \$1}' \"\$DST.dstsum\" 2>/dev/null)")
                appendLine("rm -f \"\$DST.dstsum\" 2>/dev/null")
                appendLine()
                appendLine("if [ -z \"\$SRC_HASH\" ] || [ -z \"\$DST_HASH\" ] || [ \"\$SRC_HASH\" != \"\$DST_HASH\" ]; then")
                appendLine("    echo \"[MOVE-VERIFY-FAIL] tree hash mismatch (source=\$SRC_HASH dest=\$DST_HASH)\"")
                appendLine("    rm -rf \"\$DST\" 2>/dev/null")
                appendLine("    exit 1")
                appendLine("fi")
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 100\"")
                appendLine("echo \"[MOVE-VERIFY-OK]\"")
            }
            MoveVerificationMethod.SHA256 -> {
                appendLine("I=0")
                appendLine("LAST_PCT=-1")
                appendLine("rm -f \"\$DST.mismatch\" 2>/dev/null")
                appendLine("\"\$BB\" find \"\$SRC\" ! -type d 2>/dev/null | while IFS= read -r F; do")
                appendLine("    REL=\${F#\"\$SRC\"/}")
                appendLine("    SF=\"\$SRC/\$REL\"")
                appendLine("    DF=\"\$DST/\$REL\"")
                appendLine("    if [ -L \"\$SF\" ]; then")
                appendLine("        SLT=\$(readlink \"\$SF\" 2>/dev/null)")
                appendLine("        DLT=\$(readlink \"\$DF\" 2>/dev/null)")
                appendLine("        if [ \"\$SLT\" != \"\$DLT\" ]; then")
                appendLine("            echo \"[MOVE-VERIFY-MISMATCH] \$REL\"")
                appendLine("            echo 1 > \"\$DST.mismatch\"")
                appendLine("        fi")
                appendLine("    elif [ -f \"\$SF\" ]; then")
                appendLine("        SH=\$(\"\$BB\" sha256sum \"\$SF\" 2>/dev/null | awk '{print \$1}')")
                appendLine("        DH=\$(\"\$BB\" sha256sum \"\$DF\" 2>/dev/null | awk '{print \$1}')")
                appendLine("        if [ -z \"\$SH\" ] || [ \"\$SH\" != \"\$DH\" ]; then")
                appendLine("            echo \"[MOVE-VERIFY-MISMATCH] \$REL\"")
                appendLine("            echo 1 > \"\$DST.mismatch\"")
                appendLine("        fi")
                appendLine("    fi")
                appendLine("    I=\$((I+1))")
                appendLine("    if [ \"\$SRC_COUNT\" -gt 0 ]; then")
                appendLine("        PCT=\$(( I * 100 / SRC_COUNT ))")
                appendLine("    else")
                appendLine("        PCT=100")
                appendLine("    fi")
                appendLine("    if [ \"\$PCT\" -ne \"\$LAST_PCT\" ]; then")
                appendLine("        echo \"[MOVE-VERIFY-PROGRESS] \$PCT\"")
                appendLine("        LAST_PCT=\$PCT")
                appendLine("    fi")
                appendLine("done")
                appendLine()
                appendLine("if [ -f \"\$DST.mismatch\" ]; then")
                appendLine("    rm -f \"\$DST.mismatch\" 2>/dev/null")
                appendLine("    echo \"[MOVE-VERIFY-FAIL] one or more files differ from source\"")
                appendLine("    rm -rf \"\$DST\" 2>/dev/null")
                appendLine("    exit 1")
                appendLine("fi")
                appendLine("echo \"[MOVE-VERIFY-PROGRESS] 100\"")
                appendLine("echo \"[MOVE-VERIFY-OK]\"")
            }
        }
    }

    private fun buildDirDeleteScript(quotedSrc: String): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("SRC=$quotedSrc")
        appendLine("BB=\"$BB\"")
        appendLine()
        appendLine("echo \"[MOVE-DELETE-START]\"")
        appendLine("TOTAL=\$(\"\$BB\" find \"\$SRC\" ! -type d 2>/dev/null | \"\$BB\" wc -l | tr -d ' ')")
        appendLine("[ -z \"\$TOTAL\" ] && TOTAL=0")
        appendLine("if [ \"\$TOTAL\" -eq 0 ]; then")
        appendLine("    ERR=\$(rm -rf \"\$SRC\" 2>&1)")
        appendLine("    RC=\$?")
        appendLine("else")
        appendLine("    I=0")
        appendLine("    LAST_PCT=-1")
        appendLine("    \"\$BB\" find \"\$SRC\" ! -type d 2>/dev/null | while IFS= read -r F; do")
        appendLine("        rm -f \"\$F\" 2>/dev/null")
        appendLine("        I=\$((I+1))")
        appendLine("        PCT=\$(( I * 100 / TOTAL ))")
        appendLine("        [ \"\$PCT\" -gt 100 ] && PCT=100")
        appendLine("        if [ \"\$PCT\" -ne \"\$LAST_PCT\" ]; then")
        appendLine("            echo \"[MOVE-DELETE-PROGRESS] \$PCT \$I \$TOTAL\"")
        appendLine("            LAST_PCT=\$PCT")
        appendLine("        fi")
        appendLine("    done")
        appendLine("    \"\$BB\" find \"\$SRC\" -depth -type d 2>/dev/null | while IFS= read -r D; do")
        appendLine("        rmdir \"\$D\" 2>/dev/null")
        appendLine("    done")
        appendLine("    ERR=\"\"")
        appendLine("    RC=0")
        appendLine("    if [ -e \"\$SRC\" ]; then")
        appendLine("        ERR=\$(rm -rf \"\$SRC\" 2>&1)")
        appendLine("        RC=\$?")
        appendLine("    fi")
        appendLine("fi")
        appendLine("if [ \"\$RC\" -ne 0 ]; then")
        appendLine("    echo \"[MOVE-DELETE-FAIL] \$ERR\"")
        appendLine("    exit 1")
        appendLine("fi")
        appendLine("echo \"[MOVE-DELETE-PROGRESS] 100\"")
        appendLine("echo \"[MOVE-DELETE-DONE]\"")
    }
}
