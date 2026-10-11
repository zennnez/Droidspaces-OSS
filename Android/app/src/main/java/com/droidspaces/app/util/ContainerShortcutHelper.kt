package com.droidspaces.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.droidspaces.app.R
import com.droidspaces.app.ui.shortcut.ContainerShortcutActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages dynamic app shortcuts on the Android launcher for all created containers.
 * Long-pressing the Droidspaces app icon displays shortcuts for created containers with their distro icons.
 */
object ContainerShortcutHelper {

    private const val TAG = "ContainerShortcutHelper"

    /**
     * Updates dynamic launcher shortcuts with the list of created containers.
     */
    fun updateShortcuts(context: Context, containers: List<ContainerInfo>? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val list = containers ?: ContainerManager.listContainers()
                val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)

                val shortcutList = list.take(maxShortcuts).map { container ->
                    val iconCompat = createShortcutIcon(context, container.name)
                    val intent = Intent(context, ContainerShortcutActivity::class.java).apply {
                        action = ContainerShortcutActivity.ACTION_START_CONTAINER
                        putExtra(ContainerShortcutActivity.EXTRA_CONTAINER_NAME, container.name)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }

                    val displayType = if (container.enableAnland) "Anland" else "X11"

                    ShortcutInfoCompat.Builder(context, "container_${container.name}")
                        .setShortLabel(container.name)
                        .setLongLabel("${container.name} ($displayType)")
                        .setIcon(iconCompat)
                        .setIntent(intent)
                        .build()
                }

                ShortcutManagerCompat.setDynamicShortcuts(context, shortcutList)
                Log.d(TAG, "Updated dynamic shortcuts with ${shortcutList.size} container(s)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update container shortcuts: ${e.message}")
            }
        }
    }

    /**
     * Generates a rendered Bitmap icon from the container's distro vector drawable
     * to guarantee crisp rendering across all Android OEM launchers.
     */
    private fun createShortcutIcon(context: Context, containerName: String): IconCompat {
        return try {
            val cachedOsInfo = ContainerOSInfoManager.getCachedOSInfo(containerName, context)
            val iconRes = IconUtils.getDistroIconRes(cachedOsInfo?.prettyName ?: cachedOsInfo?.name ?: containerName)
                ?: R.drawable.ic_shortcut_containers

            val drawable = ContextCompat.getDrawable(context, iconRes)
                ?: return IconCompat.createWithResource(context, R.drawable.ic_shortcut_containers)

            val sizePx = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(108)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)

            IconCompat.createWithBitmap(bitmap)
        } catch (e: Exception) {
            IconCompat.createWithResource(context, R.drawable.ic_shortcut_containers)
        }
    }
}
