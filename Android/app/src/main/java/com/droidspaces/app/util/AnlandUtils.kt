package com.droidspaces.app.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.droidspaces.app.R

/**
 * Helpers for the anland display integration on the app side.
 */
object AnlandUtils {

    private const val CONSUMER_PACKAGE = "com.anland.consumer"
    private const val CONSUMER_ACTIVITY = "com.anland.consumer.SecondaryActivity"
    // Matches MainActivity.EXTRA_SOCKET_PATH / EXTRA_WINDOW_NAME in the consumer app.
    private const val EXTRA_SOCKET_PATH = "socket_path"
    private const val EXTRA_WINDOW_NAME = "window_name"

    const val INSTALL_COMMAND = "curl -fLO https://raw.githubusercontent.com/Goldzxcbug/Droidspaces-rootfs-KDE-builder/main/scripts/install-anland-kde.sh && chmod +x install-anland-kde.sh && ./install-anland-kde.sh"
    const val INSTALL_NIRI_COMMAND = "if [ -f /usr/local/sbin/install-anland-niri.sh ]; then /usr/local/sbin/install-anland-niri.sh; else curl -fLO https://raw.githubusercontent.com/Goldzxcbug/Droidspaces-rootfs-KDE-builder/main/scripts/install-anland-niri.sh && chmod +x install-anland-niri.sh && ./install-anland-niri.sh; fi"
    // Prefer the copy baked into the rootfs image (/usr/local/sbin), which every
    // Ubuntu-26-KDE build now ships; fall back to the old app-provided-assets
    // override path for containers built before that, or for anyone who wants
    // to drop in a replacement without rebuilding the image.
    const val START_WAYLAND_COMMAND = "touch ~/.zshrc ~/.bashrc 2>/dev/null; if [ -f /usr/local/sbin/fix-kde-runtime.sh ]; then sh /usr/local/sbin/fix-kde-runtime.sh 2>/dev/null || true; elif [ -f /run/droidspaces/assets/fix-kde-runtime.sh ]; then sh /run/droidspaces/assets/fix-kde-runtime.sh 2>/dev/null || true; fi; _U=\$(id -u); _UN=\$(id -un); if [ -d /run/user/\$_U ]; then export XDG_RUNTIME_DIR=/run/user/\$_U; else export XDG_RUNTIME_DIR=/tmp/runtime-\$_UN; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\" 2>/dev/null || true; fi; ln -sfn \"\$XDG_RUNTIME_DIR\" /tmp/runtime-\$_UN 2>/dev/null || true; ln -sfn \"\$XDG_RUNTIME_DIR\" /run/user/\$_U 2>/dev/null || true; if [ -z \"\$DBUS_SESSION_BUS_ADDRESS\" ] && command -v dbus-run-session >/dev/null 2>&1; then dbus-run-session startplasma-wayland; else startplasma-wayland; fi"
    const val START_NIRI_COMMAND = "touch ~/.zshrc ~/.bashrc 2>/dev/null; if [ -f /usr/local/sbin/fix-kde-runtime.sh ]; then sh /usr/local/sbin/fix-kde-runtime.sh 2>/dev/null || true; elif [ -f /run/droidspaces/assets/fix-kde-runtime.sh ]; then sh /run/droidspaces/assets/fix-kde-runtime.sh 2>/dev/null || true; fi; _U=\$(id -u); _UN=\$(id -un); if [ -d /run/user/\$_U ]; then export XDG_RUNTIME_DIR=/run/user/\$_U; else export XDG_RUNTIME_DIR=/tmp/runtime-\$_UN; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\" 2>/dev/null || true; fi; ln -sfn \"\$XDG_RUNTIME_DIR\" /tmp/runtime-\$_UN 2>/dev/null || true; ln -sfn \"\$XDG_RUNTIME_DIR\" /run/user/\$_U 2>/dev/null || true; rm -f \"\$XDG_RUNTIME_DIR\"/wayland-* \"\$XDG_RUNTIME_DIR\"/niri.* 2>/dev/null || true; export QT_QPA_PLATFORM=wayland; export GTK_THEME=Adwaita:dark; if [ -S /run/display.sock ]; then if command -v niri >/dev/null 2>&1; then niri --anland; else /usr/local/bin/niri --anland; fi; else niri; fi"

    /**
     * Write standard Anland Wayland environment variables to /etc/environment inside
     * the container if they are not already present.
     */
    suspend fun configureEnvironment(containerName: String, enableSnapdragonFix: Boolean = false): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val script = buildString {
                    appendLine("ENV_FILE=/etc/environment")
                    appendLine("touch \"\$ENV_FILE\"")
                    appendLine("grep -q \"XCURSOR_SIZE=\" \"\$ENV_FILE\" || echo 'XCURSOR_SIZE=48' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"WAYLAND_DISPLAY=\" \"\$ENV_FILE\" || echo 'WAYLAND_DISPLAY=wayland-0' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"QT_QPA_PLATFORM=\" \"\$ENV_FILE\" || echo 'QT_QPA_PLATFORM=wayland' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"QT_AUTO_SCREEN_SCALE_FACTOR=\" \"\$ENV_FILE\" || echo 'QT_AUTO_SCREEN_SCALE_FACTOR=1' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"GDK_BACKEND=\" \"\$ENV_FILE\" || echo 'GDK_BACKEND=wayland,x11' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"CLUTTER_BACKEND=\" \"\$ENV_FILE\" || echo 'CLUTTER_BACKEND=wayland' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"SDL_VIDEODRIVER=\" \"\$ENV_FILE\" || echo 'SDL_VIDEODRIVER=wayland' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"XDG_SESSION_TYPE=\" \"\$ENV_FILE\" || echo 'XDG_SESSION_TYPE=wayland' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"MOZ_ENABLE_WAYLAND=\" \"\$ENV_FILE\" || echo 'MOZ_ENABLE_WAYLAND=1' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"ANLAND=\" \"\$ENV_FILE\" || echo 'ANLAND=1' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"ANLAND_SOCKET=\" \"\$ENV_FILE\" || echo 'ANLAND_SOCKET=/run/display.sock' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"ANLAND_DRM_DEVICE=\" \"\$ENV_FILE\" || echo 'ANLAND_DRM_DEVICE=/dev/dri/renderD128' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"XWAYLAND_GBM_DEVICE=\" \"\$ENV_FILE\" || echo 'XWAYLAND_GBM_DEVICE=/dev/dri/renderD128' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"MESA_LOADER_DRIVER_OVERRIDE=\" \"\$ENV_FILE\" || echo 'MESA_LOADER_DRIVER_OVERRIDE=kgsl' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"GALLIUM_DRIVER=\" \"\$ENV_FILE\" || echo 'GALLIUM_DRIVER=kgsl' >> \"\$ENV_FILE\"")
                    appendLine("grep -q \"FD_FORCE_KGSL=\" \"\$ENV_FILE\" || echo 'FD_FORCE_KGSL=1' >> \"\$ENV_FILE\"")
                    if (enableSnapdragonFix) {
                        appendLine("grep -q \"FD_DEV_FEATURES=\" \"\$ENV_FILE\" || echo 'FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1' >> \"\$ENV_FILE\"")
                        appendLine("grep -q \"TU_DEBUG=\" \"\$ENV_FILE\" || echo 'TU_DEBUG=noconform' >> \"\$ENV_FILE\"")
                    }
                    appendLine("sed -i 's/^[[:space:]]*export[[:space:]]\\+//g' \"\$ENV_FILE\" 2>/dev/null || true")
                    appendLine("sed -i '/^DISPLAY=/d' \"\$ENV_FILE\" 2>/dev/null || true")
                    appendLine("sed -i '/^KWIN_DRM_USE_MODIFIERS=/d' \"\$ENV_FILE\" 2>/dev/null || true")
                    appendLine("chmod 1777 /tmp 2>/dev/null || true")
                    appendLine("mkdir -p -m 1777 /tmp/.X11-unix /tmp/.ICE-unix 2>/dev/null || true")
                    appendLine("chmod 0666 /dev/dri/renderD128 /dev/dri/card* /dev/kgsl-3d0 2>/dev/null || true")
                    appendLine("chmod 0777 /run/display.sock 2>/dev/null || true")
                }
                val b64 = android.util.Base64.encodeToString(script.toByteArray(), android.util.Base64.NO_WRAP)
                val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run 'echo $b64 | base64 -d | sh'"
                val result = com.topjohnwu.superuser.Shell.cmd(cmd).exec()
                result.isSuccess
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Launch the anland consumer app's multi-window activity, pointed at this
     * container's display socket and titled with the container name. The consumer
     * dedups by socket path, so re-launching focuses the existing window. Shows a
     * toast and does nothing if the consumer app isn't installed.
     */
    fun launchWindow(context: Context, containerName: String, socketPath: String) {
        val targets = listOf(
            ComponentName(CONSUMER_PACKAGE, CONSUMER_ACTIVITY),
            ComponentName(CONSUMER_PACKAGE, "com.anland.consumer.MainActivity"),
            null
        )

        for (component in targets) {
            val intent = if (component != null) {
                Intent(Intent.ACTION_MAIN).apply {
                    this.component = component
                }
            } else {
                context.packageManager.getLaunchIntentForPackage(CONSUMER_PACKAGE) ?: continue
            }.apply {
                putExtra(EXTRA_SOCKET_PATH, socketPath)
                putExtra("socketPath", socketPath)
                putExtra("socket", socketPath)
                putExtra("display_socket", socketPath)
                putExtra(EXTRA_WINDOW_NAME, containerName)
                putExtra("title", containerName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }

            try {
                context.startActivity(intent)
                return
            } catch (ignored: Exception) {
                // Try next target
            }
        }

        Toast.makeText(
            context,
            context.getString(R.string.anland_not_installed),
            Toast.LENGTH_SHORT
        ).show()
    }
}
