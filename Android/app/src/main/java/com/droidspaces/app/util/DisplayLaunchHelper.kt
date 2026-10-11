package com.droidspaces.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Utility for launching external display clients (Anland and Termux:X11).
 */
object DisplayLaunchHelper {

    private const val TERMUX_X11_PACKAGE = "com.termux.x11"
    private const val TERMUX_X11_ACTIVITY = "com.termux.x11.MainActivity"

    /**
     * Launches the Termux:X11 app to view standard X11 / Xwayland displays.
     */
    fun launchTermuxX11(context: Context) {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(TERMUX_X11_PACKAGE) ?: Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(TERMUX_X11_PACKAGE, TERMUX_X11_ACTIVITY)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Termux:X11 app is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launches the Anland Wayland consumer app pointed at the container's display socket.
     * Queries ContainerManager for the active anland socket or uses the fallback socket.
     */
    fun launchAnland(context: Context, containerName: String, explicitSocketPath: String? = null) {
        CoroutineScope(Dispatchers.Main).launch {
            val sock = explicitSocketPath ?: withContext(Dispatchers.IO) {
                ContainerManager.getAnlandSocket(containerName)
            } ?: "/data/local/tmp/display_daemon.sock"
            AnlandUtils.launchWindow(context, containerName, sock)
        }
    }
}
