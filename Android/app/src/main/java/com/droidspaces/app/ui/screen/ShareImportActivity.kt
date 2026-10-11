package com.droidspaces.app.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.droidspaces.app.ui.theme.DroidspacesTheme
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.FileBridgeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles "Open in Droidspaces" from the system Share sheet (`ACTION_SEND` /
 * `ACTION_SEND_MULTIPLE`, any mime type -- see the manifest's intent-filter).
 *
 * This is the Android -> Linux half of the file bridge. It doesn't need root
 * or any special container-side handling: it just writes the shared file(s)
 * into the chosen container's *inbox* folder using ordinary Android file
 * I/O -- the inbox folder is an Android path the file bridge already
 * bind-mounts into the container (see [FileBridgeManager.setEnabled]), so the
 * files simply appear on the Linux side the next time the container is
 * running.
 */
class ShareImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingUris: MutableList<Uri> = mutableListOf()

        // 1. ClipData (Used by modern file managers and share targets)
        intent?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { incomingUris.add(it) }
            }
        }

        // 2. Extra Stream
        if (incomingUris.isEmpty()) {
            when (intent?.action) {
                Intent.ACTION_SEND -> {
                    val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent?.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    if (single != null) incomingUris.add(single)
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent?.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent?.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                    if (list != null) incomingUris.addAll(list)
                }
            }
        }

        // 3. Intent data (ACTION_VIEW or direct uri)
        if (incomingUris.isEmpty()) {
            intent?.data?.let { incomingUris.add(it) }
        }

        if (incomingUris.isEmpty()) {
            Toast.makeText(this, "Nothing to import", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            DroidspacesTheme {
                ImportDialog(
                    uris = incomingUris,
                    onDone = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun ImportDialog(uris: List<Uri>, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var containers by remember { mutableStateOf<List<ContainerInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val all = ContainerManager.listContainers()
        containers = all
        loading = false
        if (containers.isEmpty()) {
            onDone("No containers found. Create a container in Droidspaces first.")
        } else if (containers.size == 1) {
            importing = true
            val cName = containers[0].name
            val config = FileBridgeManager.loadConfig(cName)
            if (!config.enabled) {
                FileBridgeManager.setEnabled(context, cName, true)
            }
            val count = importInto(context, cName, uris)
            onDone(if (count > 0) "Imported $count file(s) into \"$cName\"" else "Import failed: could not copy file(s)")
        }
    }

    if (loading || importing || (containers.size <= 1 && containers.isNotEmpty())) {
        // Either still resolving, actively copying, or the single-container
        // auto-import above is in flight -- show a small progress dialog.
        Dialog(onDismissRequest = {}) {
            Surface(shape = MaterialTheme.shapes.large) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(if (importing) "Importing…" else "Loading containers…")
                }
            }
        }
        return
    }

    // Multiple eligible containers: let the user pick which one.
    Dialog(onDismissRequest = { onDone("Cancelled") }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Text("Open in Droidspaces", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Choose which container's Inbox to copy ${if (uris.size == 1) "this file" else "these ${uris.size} files"} into:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                )
                containers.forEach { c ->
                    TextButton(
                        onClick = {
                            scope.launch {
                                val config = FileBridgeManager.loadConfig(c.name)
                                if (!config.enabled) {
                                    FileBridgeManager.setEnabled(context, c.name, true)
                                }
                                val count = importInto(context, c.name, uris)
                                onDone(if (count > 0) "Imported $count file(s) into \"${c.name}\"" else "Import failed: could not copy file(s)")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(c.name, modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(onClick = { onDone("Cancelled") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Copies each [uris] entry into `containerName`'s inbox folder via
 * [android.content.ContentResolver], preserving the original display name
 * (Unicode-safe -- Android's filesystem and ContentResolver both handle
 * UTF-8 names natively, nothing special needed) and, where the source
 * exposes one, its last-modified timestamp.
 */
private suspend fun importInto(context: android.content.Context, containerName: String, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
    var config = FileBridgeManager.loadConfig(containerName)
    if (!config.enabled || config.folders.none { it.label == FileBridgeManager.INBOX_LABEL }) {
        FileBridgeManager.setEnabled(context, containerName, true)
        config = FileBridgeManager.loadConfig(containerName)
    }

    val inbox = config.folders.firstOrNull { it.label == FileBridgeManager.INBOX_LABEL }
    val destDirPath = inbox?.androidPath ?: "/storage/emulated/0/Droidspaces-Inbox"
    val destDir = File(destDirPath)
    
    // Ensure destination directory exists on host via root
    com.topjohnwu.superuser.Shell.cmd("mkdir -p ${com.droidspaces.app.util.ContainerCommandBuilder.quote(destDir.absolutePath)} && chmod 777 ${com.droidspaces.app.util.ContainerCommandBuilder.quote(destDir.absolutePath)} 2>/dev/null || true").exec()

    var imported = 0
    for (uri in uris) {
        var tempFile: File? = null
        try {
            val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "shared_file_${System.currentTimeMillis()}"
            val sanitizedName = sanitizeFileName(displayName)
            val destFile = uniqueFile(destDir, sanitizedName)
            
            // Step 1: Copy from Uri stream into an app-private cache file
            tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}_$sanitizedName")
            var streamRead = false
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                streamRead = tempFile.exists() && tempFile.length() > 0
            } catch (_: Exception) {}

            // Fallback for file:// or direct path
            if (!streamRead && (uri.scheme == "file" || uri.path?.startsWith("/") == true)) {
                val srcPath = uri.path.orEmpty()
                val srcFile = File(srcPath)
                if (srcFile.exists() && srcFile.canRead()) {
                    srcFile.copyTo(tempFile, overwrite = true)
                    streamRead = tempFile.exists() && tempFile.length() > 0
                } else {
                    val cpRes = com.topjohnwu.superuser.Shell.cmd("cp -f ${com.droidspaces.app.util.ContainerCommandBuilder.quote(srcPath)} ${com.droidspaces.app.util.ContainerCommandBuilder.quote(tempFile.absolutePath)}").exec()
                    streamRead = cpRes.isSuccess && tempFile.exists() && tempFile.length() > 0
                }
            }

            // Step 2: Copy from tempFile to destFile (using standard I/O first, then root cp)
            var fileCopied = false
            if (streamRead) {
                try {
                    tempFile.copyTo(destFile, overwrite = true)
                    fileCopied = destFile.exists() && destFile.length() > 0
                } catch (_: Exception) {}

                if (!fileCopied) {
                    val cpDest = com.topjohnwu.superuser.Shell.cmd(
                        "cp -f ${com.droidspaces.app.util.ContainerCommandBuilder.quote(tempFile.absolutePath)} ${com.droidspaces.app.util.ContainerCommandBuilder.quote(destFile.absolutePath)} && chmod 666 ${com.droidspaces.app.util.ContainerCommandBuilder.quote(destFile.absolutePath)}"
                    ).exec()
                    fileCopied = cpDest.isSuccess && destFile.exists() && destFile.length() > 0
                }
            }

            if (fileCopied) {
                val lastModified = queryLastModified(context, uri)
                if (lastModified != null) destFile.setLastModified(lastModified)
                com.topjohnwu.superuser.Shell.cmd("chmod 666 ${com.droidspaces.app.util.ContainerCommandBuilder.quote(destFile.absolutePath)} 2>/dev/null || true").exec()
                imported++
            }
        } catch (_: Exception) {
            // Skip this file, keep going
        } finally {
            try { tempFile?.delete() } catch (_: Exception) {}
        }
    }
    imported
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.lastPathSegment
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

private fun queryLastModified(context: android.content.Context, uri: Uri): Long? {
    return try {
        context.contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

/** Strips path separators/control characters but otherwise leaves Unicode names untouched. */
private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[/\\\\\\x00-\\x1F]"), "_").take(255).ifBlank { "shared_file" }

private fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 2
    while (candidate.exists()) {
        candidate = File(dir, "$base ($i)$ext")
        i++
    }
    return candidate
}
