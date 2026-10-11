package com.droidspaces.app.util

/**
 * Centralized constants for the entire application.
 * Single source of truth for all paths, keys, and configuration values.
 */
object Constants {
    // Installation paths
    const val INSTALL_PATH = "/data/local/Droidspaces/bin"
    const val DROIDSPACES_BINARY_NAME = "droidspaces"
    const val BUSYBOX_BINARY_NAME = "busybox"
    const val DROIDSPACES_BINARY_PATH = "$INSTALL_PATH/$DROIDSPACES_BINARY_NAME"
    const val BUSYBOX_BINARY_PATH = "$INSTALL_PATH/$BUSYBOX_BINARY_NAME"
    const val MAGISK_MODULE_PATH = "/data/adb/modules/droidspaces"

    // Clipboard bridge defaults (mirrors DS_CLIPBOARD_DEFAULT_* in droidspace.h)
    const val CLIPBOARD_DEFAULT_SYNC_INTERVAL_MS = 1000
    const val CLIPBOARD_MIN_SYNC_INTERVAL_MS = 250
    const val CLIPBOARD_DEFAULT_MAX_SIZE_BYTES = 256L * 1024L
    // Global (not per-container) status/pid files the clipboard daemon writes,
    // mirroring get_pids_dir() ("/data/local/Droidspaces/Pids") in pid.c.
    const val CLIPBOARD_STATUS_PATH = "/data/local/Droidspaces/Pids/clip.status"
    const val CLIPBOARD_PID_PATH = "/data/local/Droidspaces/Pids/clip.ppid"

    // Container paths
    const val CONTAINERS_BASE_PATH = "/data/local/Droidspaces/Containers"
    const val PIDS_BASE_PATH = "/data/local/Droidspaces/Pids"
    const val MODULE_SYSTEM_BIN_PATH = "$MAGISK_MODULE_PATH/system/bin"
    const val SYSTEM_BIN_SYMLINK_PATH = "$MODULE_SYSTEM_BIN_PATH/$DROIDSPACES_BINARY_NAME"
    const val KEY_SYMLINK_ENABLED = "symlink_enabled"

    const val DAEMON_MODE_FILE = "/data/local/Droidspaces/.daemon_mode"
    const val DAEMON_PID_FILE = "/data/local/Droidspaces/droidspacesd.pid"
    const val CONTAINER_CONFIG_FILE = "container.config"
    const val EMBEDDED_DROIDSPACES_RELATIVE_PATH = "etc/ds"
    const val EMBEDDED_CONFIG_RELATIVE_PATH = "$EMBEDDED_DROIDSPACES_RELATIVE_PATH/$CONTAINER_CONFIG_FILE"
    // Default network mode, the single source of truth for both the ContainerInfo
    // model default and the config parser default (they used to disagree).
    const val DEFAULT_NET_MODE = "nat"

    // Preferences keys
    const val PREFS_NAME = "droidspaces_prefs"
    const val KERNEL_CONFIG_DOC_URL = "https://github.com/ravindu644/Droidspaces-OSS/blob/main/Documentation/Kernel-Configuration.md"
    const val KEY_SETUP_COMPLETED = "setup_completed"
    const val KEY_ROOT_CHECKED = "root_checked"
    const val KEY_DISCOVERY_ENABLED = "discovery_enabled"
    const val KEY_DISCOVERY_HTTP_PORT = "discovery_http_port"
    const val KEY_ROOT_SKIPPED = "root_skipped"
    const val KEY_ROOT_AVAILABLE = "root_available"
    const val KEY_ROOT_PROVIDER_VERSION = "root_provider_version"
    const val KEY_DROIDSPACES_VERSION = "droidspaces_version"
    const val KEY_CONTAINER_COUNT = "container_count"
    const val KEY_RUNNING_COUNT = "running_count"
    const val KEY_BACKEND_STATUS = "backend_status"
    const val KEY_FOLLOW_SYSTEM_THEME = "follow_system_theme"
    const val KEY_DARK_THEME = "dark_theme"
    const val KEY_AMOLED_MODE = "amoled_mode"
    const val KEY_USE_DYNAMIC_COLOR = "use_dynamic_color"
    const val KEY_THEME_PALETTE = "theme_palette"
    const val KEY_TERMINAL_DARK_THEME = "terminal_dark_theme"
    const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size_px"
    const val KEY_TERMINAL_FONT_FILE = "terminal_font_file"
    const val KEY_TERMINAL_CONFIRM_CLOSE = "terminal_confirm_close"
    const val KEY_TERMINAL_TAP_KEYBOARD = "terminal_tap_keyboard"
    const val KEY_TERMINAL_KEYBOARD_LEFT = "terminal_keyboard_button_left"
    const val KEY_APP_LOCALE = "app_locale"
    const val KEY_BACKEND_MODE = "backend_mode"
    const val KEY_SELINUX_STATUS = "selinux_status"
    const val KEY_HOST_CAPABILITIES = "host_capabilities"
    const val KEY_HOST_CAPABILITIES_BOOT_ID = "host_capabilities_boot_id"
    const val KEY_DAEMON_MODE_ENABLED = "daemon_mode_enabled"
    const val KEY_TREAT_AS_64BIT = "treat_as_64bit"
    const val KEY_CHECK_APP_UPDATES = "check_app_updates"

    // Container log cache prefix
    const val KEY_CONTAINER_LOG_PREFIX = "container_log_"
    const val KEY_CONTAINER_OS_INFO_PREFIX = "container_os_info_"
    const val KEY_CONTAINER_USERS_PREFIX = "container_users_"
    const val KEY_CACHED_CONTAINER_NAMES = "cached_container_names"
    const val KEY_CACHED_CONTAINER_CONFIG_PREFIX = "cached_container_config_"

    // Custom rootfs repository subscriptions
    const val KEY_CUSTOM_REPOS = "custom_rootfs_repos"

    // Linux notification bridge
    const val KEY_NOTIFY_BRIDGE_ENABLED = "notify_bridge_enabled"
    const val KEY_NOTIFY_BRIDGE_APPS = "notify_bridge_apps"
    const val KEY_NOTIFY_BRIDGE_LEVEL = "notify_bridge_level"
    const val KEY_NOTIFY_BRIDGE_LAST_BOOT_ID_PREFIX = "notify_bridge_boot_id_"
    const val NOTIFY_BRIDGE_DEFAULT_PORT = 8942
    const val NOTIFY_BRIDGE_CHANNEL_ID = "droidspaces_linux_notifications"
    val NOTIFY_BRIDGE_DEFAULT_APPS = listOf("Firefox", "Terminal", "Build tools")

    // Tray bridge (Syncthing, qBittorrent, VPN clients, etc.)
    const val KEY_TRAY_BRIDGE_ENABLED = "tray_bridge_enabled"
    const val KEY_TRAY_BRIDGE_MODE = "tray_bridge_mode" // "android" | "xfce" | "both"
    const val TRAY_BRIDGE_SUMMARY_CHANNEL_ID = "droidspaces_tray_summary"

    // Minimum storage requirements
    const val MIN_STORAGE_GB = 4

    // Wireless display (VNC over the existing Termux:X11 session)
    // 5900 is standard VNC RFB default port
    const val DEFAULT_VNC_PORT = 5900
    const val VNC_CONTROL_SCRIPT_PATH = "/usr/local/sbin/droidspaces-vnc"
    const val VNC_PASSWD_FILE_PATH = "/root/.droidspaces/vnc.passwd"
    const val VNC_SERVICE_NAME = "droidspaces-vnc"

    // Wireless display: Stream backend (H.264 over MPEG-TS/TCP)
    const val DEFAULT_STREAM_PORT = 8554
    const val STREAM_CONTROL_SCRIPT_PATH = "/usr/local/sbin/droidspaces-stream"
    const val STREAM_SERVICE_NAME = "droidspaces-stream"

    // Sunshine / Moonlight game & desktop streaming
    const val DEFAULT_SUNSHINE_WEB_PORT = 47990
    const val DEFAULT_SUNSHINE_STREAM_PORT = 47989
    const val SUNSHINE_CONTROL_SCRIPT_PATH = "/usr/local/sbin/droidspaces-sunshine"
    const val SUNSHINE_SERVICE_NAME = "droidspaces-sunshine"

    // scrcpy loopback
    const val SCRCPY_ADB_PORT = 5555
    const val SCRCPY_CONTROL_SCRIPT_PATH = "/usr/local/sbin/droidspaces-scrcpy"
    const val DEFAULT_SCRCPY_MAX_SIZE = 0 // 0 == scrcpy's own default (no cap)
    const val DEFAULT_SCRCPY_BITRATE_KBPS = 8000

    // Docker Engine running inside a container
    const val DEFAULT_DOCKER_TCP_PORT = 2375

    // NAT Networking constants
    const val NAT_IP_PREFIX = "172.28"
    const val NAT_OCTET_MIN = 1
    const val NAT_OCTET_MAX = 254

    // Maximum DNS servers
    const val MAX_DNS_SERVERS = 8

    // Pull-to-refresh animation delay (ms) - smooth refresh indicator animation
    const val PULL_TO_REFRESH_ANIMATION_DELAY = 200L

    /**
     * Check if droidspaces binary is available in system PATH
     * Returns the command to use (either "droidspaces" if in PATH, or full path otherwise)
     */
    fun getDroidspacesCommand(): String {
        val result = com.topjohnwu.superuser.Shell.cmd("command -v droidspaces 2>&1").exec()
        return if (result.isSuccess && result.out.isNotEmpty() && result.out[0].isNotBlank()) {
            // droidspaces is in PATH, use just the command name
            "droidspaces"
        } else {
            // droidspaces is not in PATH, use full path
            DROIDSPACES_BINARY_PATH
        }
    }
}
