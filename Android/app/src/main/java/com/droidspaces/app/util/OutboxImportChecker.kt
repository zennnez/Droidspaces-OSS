package com.droidspaces.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Linux -> Android half of the file bridge.
 *
 * When someone right-clicks a file in Thunar and picks "Send to Android" (see
 * `file_bridge_setup.sh`), the file is just copied into the container's
 * outbox folder -- an ordinary directory that also happens to be bind-mounted
 * from a real Android path (`.../Droidspaces-Outbox` under whichever Android
 * folder the bridge is rooted at). There's no live channel from a process
 * inside the Linux container back to this app (no binder access from
 * userspace inside a chroot/namespace), so this app can't be *told* the
 * instant a file lands there -- it has to *notice*.
 *
 * [checkNow] does that noticing: it scans each outbox for files it hasn't
 * seen before (tracked in SharedPreferences by name+size+mtime) and, for each
 * new one, posts a notification whose tap target opens Android's normal
 * share/open sheet for that file via a [FileProvider] Uri.
 *
 * This is called: on every visit to [com.droidspaces.app.ui.screen.FileSharingScreen],
 * from its manual "Check for files sent from Linux" button, and is safe to
 * call as often as you like (each call is a handful of `stat`s). There is
 * deliberately no background service/WorkManager here -- see the "Android
 * storage limitations" write-up for why real-time background pickup isn't
 * attempted.
 */
object OutboxImportChecker {

    private const val PREFS_NAME = "file_bridge_outbox_seen"
    private const val CHANNEL_ID = "droidspaces_file_bridge"
    private const val NOTIFICATION_ID_BASE = 9000

    /**
     * Scans every enabled container's outbox folder for files not seen before.
     * Returns how many new files were found (and notified about).
     */
    suspend fun checkNow(context: Context, containerName: String? = null): Int = withContext(Dispatchers.IO) {
        val names = if (containerName != null) listOf(containerName) else {
            ContainerManager.listContainers().map { it.name }
        }

        var found = 0
        for (name in names) {
            val config = FileBridgeManager.loadConfig(name)
            if (!config.enabled) continue
            val outbox = config.folders.firstOrNull { it.label == FileBridgeManager.OUTBOX_LABEL }
                ?: continue
            found += scanFolder(context, name, outbox.androidPath)
        }
        found
    }

    private fun scanFolder(context: Context, containerName: String, androidPath: String): Int {
        val dir = File(androidPath)
        val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") } ?: return 0
        if (files.isEmpty()) return 0

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var newCount = 0
        val edit = prefs.edit()

        for (file in files) {
            val fingerprint = "${file.name}:${file.length()}:${file.lastModified()}"
            val key = "$containerName:${file.name}"
            if (prefs.getString(key, null) == fingerprint) continue // already notified about this exact file
            edit.putString(key, fingerprint)
            notifyNewFile(context, containerName, file)
            newCount++
        }
        edit.apply()
        return newCount
    }

    private fun notifyNewFile(context: Context, containerName: String, file: File) {
        ensureChannel(context)

        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            return
        }

        val mimeType = context.contentResolver.getType(uri)
            ?: guessMimeType(file.name)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Open \"${file.name}\"").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            file.absolutePath.hashCode(),
            chooser,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.droidspaces.app.R.drawable.ic_launcher_foreground)
            .setContentTitle("File ready from Linux")
            .setContentText("\"${file.name}\" from $containerName -- tap to open or share")
            .setStyle(NotificationCompat.BigTextStyle().bigText("\"${file.name}\" from the $containerName container is ready. Tap to open or share it."))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID_BASE + file.absolutePath.hashCode(), notification)
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt", "log", "conf" -> "text/plain"
            "zip" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> "*/*"
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File Bridge",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Notifies you when a file is sent from a Linux container to Android" }
        manager.createNotificationChannel(channel)
    }
}
