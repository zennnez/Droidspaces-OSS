package com.droidspaces.app.ui.shortcut

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.droidspaces.app.util.Constants
import com.droidspaces.app.util.ContainerCommandBuilder
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.ContainerOSInfoManager
import com.droidspaces.app.util.ContainerShortcutHelper
import com.droidspaces.app.util.DisplayLaunchHelper
import com.droidspaces.app.util.PreferencesManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent trampoline activity that starts a container and directly launches
 * Anland or Termux:X11 display client without any opening animations or UI delay.
 */
class ContainerShortcutActivity : Activity() {

    companion object {
        const val ACTION_START_CONTAINER = "com.droidspaces.app.action.START_CONTAINER_DIRECT"
        const val EXTRA_CONTAINER_NAME = "extra_container_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seamless transparent launch - no window transitions or visual delay
        disableActivityTransitions()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
                splashScreenViewProvider.remove()
            }
        }

        val containerName = intent.getStringExtra(EXTRA_CONTAINER_NAME)
            ?: intent.data?.getQueryParameter("container")

        if (containerName.isNullOrBlank()) {
            finish()
            disableActivityTransitions()
            return
        }

        val appContext = applicationContext

        // Show immediate start toast so user gets instant confirmation without any UI animation
        Toast.makeText(appContext, "Starting $containerName...", Toast.LENGTH_SHORT).show()

        // Finish immediately so this trampoline activity never stays on screen
        finish()
        disableActivityTransitions()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = ContainerManager.getContainerInfo(containerName)
                    ?: ContainerManager.listContainers().find { it.name == containerName }

                if (container == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Container '$containerName' not found", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val (isRunning, _) = ContainerManager.checkContainerStatus(container.name)

                if (isRunning) {
                    withContext(Dispatchers.Main) {
                        if (container.enableAnland) {
                            DisplayLaunchHelper.launchAnland(appContext, container.name)
                        } else {
                            DisplayLaunchHelper.launchTermuxX11(appContext)
                        }
                    }
                    return@launch
                }

                val effective = ContainerManager.autoDetectAndRemapContainerPath(appContext, container, null)
                val startCmd = ContainerCommandBuilder.buildStartCommand(effective)
                val result = Shell.cmd(startCmd).exec()

                if (!result.isSuccess) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Failed to start $containerName", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // If Anland is enabled, launch immediately as the Wayland compositor initializes
                if (effective.enableAnland) {
                    // Quick poll for socket (up to 400ms in 40ms intervals) so launch is immediate
                    var attempts = 0
                    while (attempts < 10 && ContainerManager.getAnlandSocket(effective.name) == null) {
                        delay(40)
                        attempts++
                    }
                    withContext(Dispatchers.Main) {
                        DisplayLaunchHelper.launchAnland(appContext, effective.name)
                    }
                } else {
                    // For Termux:X11, launch immediately so X11 activity is ready
                    withContext(Dispatchers.Main) {
                        DisplayLaunchHelper.launchTermuxX11(appContext)
                    }
                }

                // Configure Anland Wayland environment in background if needed
                if (effective.enableAnland) {
                    val anlandScript = "ENV_FILE=/etc/environment; touch \"\$ENV_FILE\"; " +
                        "grep -q \"WAYLAND_DISPLAY=\" \"\$ENV_FILE\" || echo 'WAYLAND_DISPLAY=wayland-0' >> \"\$ENV_FILE\"; " +
                        "grep -q \"DISPLAY=\" \"\$ENV_FILE\" || echo 'DISPLAY=:0' >> \"\$ENV_FILE\"; " +
                        "grep -q \"QT_QPA_PLATFORM=\" \"\$ENV_FILE\" || echo 'QT_QPA_PLATFORM=wayland' >> \"\$ENV_FILE\"; " +
                        "grep -q \"ANLAND=\" \"\$ENV_FILE\" || echo 'ANLAND=1' >> \"\$ENV_FILE\"; " +
                        "grep -q \"ANLAND_SOCKET=\" \"\$ENV_FILE\" || echo 'ANLAND_SOCKET=/run/display.sock' >> \"\$ENV_FILE\"; " +
                        "grep -q \"ANLAND_DRM_DEVICE=\" \"\$ENV_FILE\" || echo 'ANLAND_DRM_DEVICE=/dev/dri/renderD128' >> \"\$ENV_FILE\"; " +
                        "chmod 0666 /dev/dri/renderD128 /dev/dri/card* /dev/kgsl-3d0 2>/dev/null || true; " +
                        "chmod 0777 /run/display.sock 2>/dev/null || true"
                    val b64 = android.util.Base64.encodeToString(anlandScript.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                    val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(effective.name)} run 'echo $b64 | base64 -d | sh'"
                    Shell.cmd(cmd).exec()
                }

                if (effective.sshEnabled) {
                    ContainerManager.sshServiceControl(effective, "start")
                }

                // Prefetch distro icon so OS info is cached
                ContainerOSInfoManager.prefetchDistroIcon(effective.name, appContext)

                // Update PreferencesManager cache in background so Droidspaces app reflects Running status
                val updatedList = ContainerManager.listContainers()
                val prefs = PreferencesManager.getInstance(appContext)
                prefs.saveCachedContainers(updatedList)
                prefs.cachedContainerCount = updatedList.size
                prefs.cachedRunningCount = updatedList.count { it.isRunning }

                // Sync shortcuts
                ContainerShortcutHelper.updateShortcuts(appContext, updatedList)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Error launching $containerName: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun disableActivityTransitions() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
