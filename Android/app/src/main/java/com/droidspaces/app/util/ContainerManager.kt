package com.droidspaces.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File

enum class ContainerStatus {
    RUNNING,
    STOPPED
}

data class BindMount(
    val src: String,
    val dest: String,
    val ro: Boolean = false
)

data class PortForward(
    val hostPort: String,
    val containerPort: String? = null,
    val proto: String = "tcp"
)

data class ContainerInfo(
    val name: String,
    val hostname: String,
    val rootfsPath: String,
    val netMode: String = Constants.DEFAULT_NET_MODE,
    val disableIPv6: Boolean = false,
    val enableAndroidStorage: Boolean = false,
    val enableHwAccess: Boolean = false,
    val enableGpuMode: Boolean = false,
    val enableTermuxX11: Boolean = false,
    val tx11ExtraFlags: String = "",
    val enableAnland: Boolean = false,
    val installUsbManager: Boolean = false,
    val enableVirgl: Boolean = false,
    val virglExtraFlags: String = "",
    val enablePulseaudio: Boolean = false,
    val wirelessDisplayEnabled: Boolean = false,
    val wirelessDisplayMode: String = "vnc",
    val vncPort: Int = Constants.DEFAULT_VNC_PORT,
    val vncLanOnly: Boolean = true,
    val streamPort: Int = Constants.DEFAULT_STREAM_PORT,
    val streamFps: Int = 30,
    val streamBitrateKbps: Int = 4000,
    val streamCodec: String = "h264",
    val streamPreset: String = "low-latency",
    val streamResolution: String = "native",
    val streamEngine: String = "auto",
    val sshEnabled: Boolean = false,
    val sshPort: Int = 2222,
    val sshLanOnly: Boolean = true,
    val sshUser: String = "",
    val sshAuthorizedKeys: List<String> = emptyList(),
    val sshPasswordAuthEnabled: Boolean = true,
    val dockerEnabled: Boolean = false,
    val dockerExposeTcp: Boolean = false,
    val dockerTcpPort: Int = Constants.DEFAULT_DOCKER_TCP_PORT,
    val dockerForceVfs: Boolean = false,
    val scrcpyEnabled: Boolean = false,
    val scrcpyMaxSize: Int = Constants.DEFAULT_SCRCPY_MAX_SIZE,
    val scrcpyBitrateKbps: Int = Constants.DEFAULT_SCRCPY_BITRATE_KBPS,
    val enableClipboardBridge: Boolean = false,
    val clipboardSyncIntervalMs: Int = Constants.CLIPBOARD_DEFAULT_SYNC_INTERVAL_MS,
    val clipboardMaxSizeBytes: Long = Constants.CLIPBOARD_DEFAULT_MAX_SIZE_BYTES,
    val selinuxPermissive: Boolean = false,
    val allowSandboxing: Boolean = false,
    val volatileMode: Boolean = false,
    val bindMounts: List<BindMount> = emptyList(),
    val dnsServers: String = "",
    val runAtBoot: Boolean = false,
    val runAtBootPriority: Int = 0,
    val status: ContainerStatus = ContainerStatus.STOPPED,
    val pid: Int? = null,
    // Runtime-only, like status/pid above -- populated by listContainers() from
    // the clipboard daemon's global status file, never persisted to container.config.
    val clipboardStatus: String? = null,
    val clipboardLastSyncEpoch: Long? = null,
    val useSparseImage: Boolean = false,
    val sparseImageSizeGB: Int? = null,
    val envFileContent: String? = null,
    val upstreamInterfaces: List<String> = emptyList(),
    val portForwards: List<PortForward> = emptyList(),
    val forceCgroupv1: Boolean = false,
    val staticNatIp: String = "",
    val gatewayContainer: String = "",
    val gatewayNet: String = "",
    val gatewayIface: String = "",
    val gatewayBridge: String = "",
    val privileged: String = "",
    val customInit: String = "",
    val macvlanParent: String = "",
    val macvlanMode: String = "",
    /** Bytes, cpu_quota microseconds per [ResourceLimits.CPU_PERIOD_US], process count. 0 is unlimited. */
    val memoryLimit: Long = 0,
    val cpuQuota: Long = 0,
    val pidsLimit: Long = 0,
    val uuid: String = "",
    val createDefaultUser: Boolean = false,
    val defaultUsername: String = "",
    val defaultUserPassword: String = "",
    val defaultUserSudo: Boolean = true,
    val defaultUserShell: String = "/bin/bash"
) {
    val isRunning: Boolean
        get() = status == ContainerStatus.RUNNING

    fun toConfigContent(): String = buildString {
        appendLine("# Droidspaces Container Configuration")
        appendLine("# Generated automatically")
        appendLine()
        appendLine("name=$name")
        appendLine("hostname=$hostname")
        appendLine("rootfs_path=$rootfsPath")
        appendLine("net_mode=$netMode")
        appendLine("disable_ipv6=${if (disableIPv6) "1" else "0"}")
        appendLine("enable_android_storage=${if (enableAndroidStorage) "1" else "0"}")
        appendLine("enable_hw_access=${if (enableHwAccess) "1" else "0"}")
        appendLine("enable_gpu_mode=${if (enableGpuMode) "1" else "0"}")
        appendLine("enable_x11=${if (enableTermuxX11) "1" else "0"}")
        if (tx11ExtraFlags.isNotBlank()) appendLine("tx11_extra_flags=$tx11ExtraFlags")
        appendLine("enable_anland=${if (enableAnland) "1" else "0"}")
        appendLine("install_usb_manager=${if (installUsbManager) "1" else "0"}")
        appendLine("enable_virgl=${if (enableVirgl) "1" else "0"}")
        if (virglExtraFlags.isNotBlank()) appendLine("virgl_extra_flags=$virglExtraFlags")
        appendLine("enable_pulseaudio=${if (enablePulseaudio) "1" else "0"}")
        // Wireless display (VNC). App-managed only -- the native runtime never reads
        // these; the password itself is never written here (see WirelessDisplayManager).
        appendLine("wireless_display_enabled=${if (wirelessDisplayEnabled) "1" else "0"}")
        if (wirelessDisplayEnabled) {
            appendLine("wireless_display_mode=$wirelessDisplayMode")
            appendLine("vnc_port=$vncPort")
            appendLine("vnc_lan_only=${if (vncLanOnly) "1" else "0"}")
            if (wirelessDisplayMode == "stream") {
                appendLine("stream_port=$streamPort")
                appendLine("stream_fps=$streamFps")
                appendLine("stream_bitrate_kbps=$streamBitrateKbps")
                appendLine("stream_codec=$streamCodec")
                appendLine("stream_preset=$streamPreset")
                appendLine("stream_resolution=$streamResolution")
                appendLine("stream_engine=$streamEngine")
            }
        }
        appendLine("ssh_enabled=${if (sshEnabled) "1" else "0"}")
        if (sshEnabled) {
            appendLine("ssh_port=$sshPort")
            appendLine("ssh_lan_only=${if (sshLanOnly) "1" else "0"}")
            if (sshUser.isNotBlank()) appendLine("ssh_user=$sshUser")
            appendLine("ssh_password_auth=${if (sshPasswordAuthEnabled) "1" else "0"}")
            if (sshAuthorizedKeys.isNotEmpty()) {
                val encoded = sshAuthorizedKeys.map {
                    android.util.Base64.encodeToString(it.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                }
                appendLine("ssh_authorized_keys=${encoded.joinToString(",")}")
            }
        }
        appendLine("docker_enabled=${if (dockerEnabled) "1" else "0"}")
        if (dockerEnabled) {
            appendLine("docker_expose_tcp=${if (dockerExposeTcp) "1" else "0"}")
            if (dockerExposeTcp) appendLine("docker_tcp_port=$dockerTcpPort")
            appendLine("docker_force_vfs=${if (dockerForceVfs) "1" else "0"}")
        }
        appendLine("scrcpy_enabled=${if (scrcpyEnabled) "1" else "0"}")
        if (scrcpyEnabled) {
            appendLine("scrcpy_max_size=$scrcpyMaxSize")
            appendLine("scrcpy_bitrate_kbps=$scrcpyBitrateKbps")
        }
        appendLine("enable_clipboard_bridge=${if (enableClipboardBridge) "1" else "0"}")
        if (enableClipboardBridge) {
            appendLine("clipboard_sync_interval=$clipboardSyncIntervalMs")
            appendLine("clipboard_max_size=$clipboardMaxSizeBytes")
        }
        appendLine("selinux_permissive=${if (selinuxPermissive) "1" else "0"}")
        appendLine("allow_sandboxing=${if (allowSandboxing) "1" else "0"}")
        appendLine("volatile_mode=${if (volatileMode) "1" else "0"}")
        if (bindMounts.isNotEmpty()) {
            appendLine("bind_mounts=${bindMounts.joinToString(",") { "${it.src}:${it.dest}${if (it.ro) ":ro" else ""}" }}")
        }
        if (netMode == "nat" && upstreamInterfaces.isNotEmpty()) {
            appendLine("upstream_interfaces=${upstreamInterfaces.joinToString(",")}")
        }
        // SSH and VNC reuse the existing --port DNAT mechanism in NAT mode.
        // When enabled, their forwards are folded into the same port_forwards=
        // list the C daemon parses. Only meaningful in NAT mode.
        val effectivePortForwards = buildList {
            addAll(portForwards)
            if (netMode == "nat") {
                if (sshEnabled) {
                    val sshForward = PortForward(hostPort = sshPort.toString(), containerPort = "22", proto = "tcp")
                    if (portForwards.none { it.hostPort == sshForward.hostPort && it.proto == "tcp" }) {
                        add(sshForward)
                    }
                }
                if (wirelessDisplayEnabled) {
                    val activePort = if (wirelessDisplayMode == "stream") streamPort else vncPort
                    val displayForward = PortForward(hostPort = activePort.toString(), containerPort = activePort.toString(), proto = "tcp")
                    if (portForwards.none { it.hostPort == displayForward.hostPort && it.proto == "tcp" }) {
                        add(displayForward)
                    }
                }
                if (dockerEnabled && dockerExposeTcp) {
                    val dockerForward = PortForward(hostPort = dockerTcpPort.toString(), containerPort = dockerTcpPort.toString(), proto = "tcp")
                    if (portForwards.none { it.hostPort == dockerForward.hostPort && it.proto == "tcp" }) {
                        add(dockerForward)
                    }
                }
            }
        }
        if (netMode == "nat" && effectivePortForwards.isNotEmpty()) {
            appendLine("port_forwards=${effectivePortForwards.joinToString(",") {
                val mapping = if (it.containerPort != null) "${it.hostPort}:${it.containerPort}" else it.hostPort
                "$mapping/${it.proto}"
            }}")
        }
        if (dnsServers.isNotEmpty()) {
            appendLine("dns_servers=$dnsServers")
        }
        appendLine("run_at_boot=${if (runAtBoot) "1" else "0"}")
        if (runAtBoot && runAtBootPriority > 0) {
            appendLine("run_at_boot_priority=$runAtBootPriority")
        }
        appendLine("force_cgroupv1=${if (forceCgroupv1) "1" else "0"}")
        if (netMode == "nat" && staticNatIp.isNotEmpty()) {
            appendLine("static_nat_ip=$staticNatIp")
        }
        // Gateway-mode keys (the C runtime parses gateway_lan_ifname, not gateway_iface).
        // gateway_container is required in gateway mode; the rest are optional overrides.
        if (netMode == "gateway") {
            if (gatewayContainer.isNotBlank()) appendLine("gateway_container=$gatewayContainer")
            if (gatewayNet.isNotBlank()) appendLine("gateway_net=$gatewayNet")
            if (gatewayIface.isNotBlank()) appendLine("gateway_lan_ifname=$gatewayIface")
            if (gatewayBridge.isNotBlank()) appendLine("gateway_bridge=$gatewayBridge")
        }
        // Bridge is the runtime's default, so only the other modes are written.
        if (netMode == "macvlan") {
            if (macvlanParent.isNotBlank()) appendLine("macvlan_parent=$macvlanParent")
            if (macvlanMode.isNotBlank() && macvlanMode != "bridge") appendLine("macvlan_mode=$macvlanMode")
        }
        appendLine("use_sparse_image=${if (useSparseImage) "1" else "0"}")
        if (sparseImageSizeGB != null) {
            appendLine("sparse_image_size_gb=$sparseImageSizeGB")
        }
        if (envFileContent != null) {
            appendLine("env_file=${Constants.CONTAINERS_BASE_PATH}/${ContainerManager.sanitizeContainerName(name)}/.env")
        }
        if (privileged.isNotEmpty()) {
            appendLine("privileged=$privileged")
        }
        if (memoryLimit > 0) appendLine("memory_limit=$memoryLimit")
        if (cpuQuota > 0) {
            appendLine("cpu_quota=$cpuQuota")
            appendLine("cpu_period=${ResourceLimits.CPU_PERIOD_US}")
        }
        if (pidsLimit > 0) appendLine("pids_limit=$pidsLimit")
        if (customInit.isNotEmpty()) {
            appendLine("custom_init=$customInit")
        }
        if (uuid.isNotEmpty()) {
            appendLine("uuid=$uuid")
        }
        appendLine("create_default_user=${if (createDefaultUser) "1" else "0"}")
        if (createDefaultUser && defaultUsername.isNotBlank()) {
            appendLine("default_username=$defaultUsername")
            if (defaultUserPassword.isNotBlank()) {
                val b64pwd = android.util.Base64.encodeToString(defaultUserPassword.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                appendLine("default_user_password_b64=$b64pwd")
            }
            appendLine("default_user_sudo=${if (defaultUserSudo) "1" else "0"}")
            appendLine("default_user_shell=$defaultUserShell")
        }
    }
}

object ContainerManager {
    private const val CONTAINERS_BASE_PATH = Constants.CONTAINERS_BASE_PATH

    /**
     * Sanitize container name for use in directory paths.
     * Replaces spaces with dashes, but allows dots and other valid characters.
     * This ensures directory names are safe while preserving readable names.
     */
    fun sanitizeContainerName(name: String): String {
        return name.replace(" ", "-")
    }

    /**
     * Get the container directory path (parent directory).
     * Uses sanitized name to handle spaces.
     */
    fun getContainerDirectory(name: String): String {
        val sanitizedName = sanitizeContainerName(name)
        return "$CONTAINERS_BASE_PATH/$sanitizedName"
    }

    /**
     * Get the rootfs path for a container (LXC-style: /rootfs subdirectory).
     *
     * [baseDir] relocates the bulk data to a chosen volume. The container's own directory
     * under CONTAINERS_BASE_PATH still holds the config, .env and pidfile, so a container
     * stays discoverable when its storage is unplugged. The <baseDir>/<name>/ level is kept
     * so two containers can share one destination without colliding.
     */
    fun getRootfsPath(name: String, baseDir: String? = null): String {
        return "${storageBase(baseDir, name)}/rootfs"
    }

    /**
     * Get the sparse image path for a container. See [getRootfsPath] for [baseDir].
     */
    fun getSparseImagePath(name: String, baseDir: String? = null): String {
        return "${storageBase(baseDir, name)}/rootfs.img"
    }

    private fun storageBase(baseDir: String?, name: String): String {
        val trimmed = baseDir?.trim()?.trimEnd('/')
        return if (trimmed.isNullOrEmpty()) getContainerDirectory(name)
        else "$trimmed/${sanitizeContainerName(name)}"
    }

    /**
     * List all installed containers by scanning the containers directory.
     * Returns a list of ContainerInfo objects.
     */
    suspend fun listContainers(): List<ContainerInfo> = withContext(Dispatchers.IO) {
        val containers = mutableListOf<ContainerInfo>()

        try {
            // List all directories in the containers base path (quoted for safety)
            val listResult = Shell.cmd("ls -d \"$CONTAINERS_BASE_PATH\"/*/ 2>/dev/null").exec()

            if (!listResult.isSuccess) {
                // Directory might not exist or be empty
                return@withContext emptyList()
            }

            // The clipboard bridge is one global daemon shared by every container (like
            // X11/PulseAudio), so its status is read once per listContainers() call and
            // applied to whichever containers have it enabled, rather than once per container.
            val (clipboardStatus, clipboardLastSync) = readClipboardBridgeStatus()

            // Parse each directory path
            listResult.out.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith(CONTAINERS_BASE_PATH)) {
                    return@forEach
                }

                // Extract sanitized container name from path: /data/local/Droidspaces/Containers/name/
                val sanitizedName = trimmed
                    .removeSuffix("/")
                    .substringAfterLast("/")

                if (sanitizedName.isEmpty()) {
                    return@forEach
                }

                // Try to load container config
                val configPath = "$CONTAINERS_BASE_PATH/$sanitizedName/${Constants.CONTAINER_CONFIG_FILE}"
                // Use sanitizedName as default, but config file will have the real name
                val config = loadContainerConfig(configPath, sanitizedName)

                if (config != null) {
                    // Check if container is running (use the real name from config)
                    val runningInfo = checkContainerStatus(config.name)
                    val status = if (runningInfo.first) {
                        ContainerStatus.RUNNING
                    } else {
                        ContainerStatus.STOPPED
                    }
                    containers.add(config.copy(
                        status = status,
                        pid = runningInfo.second,
                        clipboardStatus = if (config.enableClipboardBridge && config.enableTermuxX11) clipboardStatus else null,
                        clipboardLastSyncEpoch = if (config.enableClipboardBridge && config.enableTermuxX11) clipboardLastSync else null
                    ))
                }
            }
        } catch (e: Exception) {
            // Return empty list on error
        }

        containers
    }

    /**
     * Remove a rootfs stored outside the container directory, then the folder we made for
     * it if nothing else is left in there.
     *
     * The path comes out of a config file, so this is an rm -rf whose target is not fully
     * under our control. It therefore only ever deletes an entry named exactly "rootfs" or
     * "rootfs.img", the two names the installer creates. A hand-edited config pointing at
     * someone's existing image is reported and left alone rather than deleted, and a
     * truncated or malicious value like "/" or "/data" cannot match at all.
     *
     * The parent is removed with a plain rmdir, which fails harmlessly when it still holds
     * anything, so a shared destination folder never takes other containers down with it.
     */
    private suspend fun deleteExternalRootfs(rootfsPath: String, logger: ContainerLogger) {
        val name = rootfsPath.substringAfterLast('/')
        if (name != "rootfs" && name != "rootfs.img") {
            logger.w("Rootfs at $rootfsPath is not named rootfs or rootfs.img, so it was not")
            logger.w("created by Droidspaces. Leaving it in place, remove it by hand if you")
            logger.w("no longer need it.")
            return
        }

        val quoted = ContainerCommandBuilder.quote(rootfsPath)
        val result = Shell.cmd("rm -rf $quoted 2>&1").exec()
        (result.out + result.err).forEach { line ->
            line.trim().takeIf { it.isNotEmpty() }?.let { logger.i(it) }
        }
        if (!result.isSuccess) {
            // The volume may simply be unplugged. Say so and carry on: refusing to
            // uninstall would leave the container listed with no way to remove it.
            logger.w("Could not delete $rootfsPath (exit code: ${result.code}).")
            logger.w("If its storage is disconnected, delete it by hand once reattached.")
            return
        }
        logger.i("Rootfs deleted.")

        val parent = rootfsPath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            Shell.cmd("rmdir ${ContainerCommandBuilder.quote(parent)} 2>/dev/null").exec()
        }
    }

    /**
     * The rootfs path as recorded in the container's own config, which is the only
     * authoritative copy. A container installed to a custom location cannot be derived
     * from its name, and the backend rewrites this file on every start, so an in-memory
     * ContainerInfo can be stale by the time an operation runs.
     *
     * Returns null when the config is missing or carries no rootfs_path, so callers can
     * refuse rather than guess.
     */
    suspend fun readRootfsPath(name: String): String? = withContext(Dispatchers.IO) {
        val configPath = "${getContainerDirectory(name)}/${Constants.CONTAINER_CONFIG_FILE}"
        loadContainerConfig(configPath, name)?.rootfsPath?.takeIf { it.isNotBlank() }
    }

    /**
     * Load container configuration from config file.
     */
    private fun loadContainerConfig(configPath: String, defaultName: String): ContainerInfo? {
        try {
            // Read config file using shell (quoted for safety)
            val readResult = Shell.cmd("cat ${ContainerCommandBuilder.quote(configPath)} 2>/dev/null").exec()

            if (!readResult.isSuccess || readResult.out.isEmpty()) {
                return null
            }

            val configContent = readResult.out.joinToString("\n")
            return parseConfig(configContent, defaultName)
        } catch (e: Exception) {
            return null
        }
    }

    internal fun parseConfigValues(configContent: String): Map<String, String> = buildMap {
        configContent.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) put(parts[0].trim(), parts[1].trim())
            }
        }
    }

    /**
     * Parse container configuration from string content.
     */
    fun parseConfig(
        configContent: String,
        defaultName: String,
        loadEnvironment: Boolean = true
    ): ContainerInfo? {
        try {
            val configMap = parseConfigValues(configContent)

            // Build ContainerInfo from config
            val containerName = configMap["name"] ?: defaultName
            // Drop a container whose on-disk name carries shell metacharacters before
            // it can reach a root command. Over-length-but-safe names still load.
            if (!ValidationUtils.isSafeContainerName(containerName)) {
                android.util.Log.w("ContainerManager", "Skipping container with unsafe name in config")
                return null
            }
            val useSparseImage = configMap["use_sparse_image"] == "1"
            val sparseImageSizeGB = configMap["sparse_image_size_gb"]?.toIntOrNull()

            // Parse bind mounts: src:dest,src2:dest2
            val bindMounts = configMap["bind_mounts"]?.split(",")?.mapNotNull {
                val parts = it.split(":", limit = 3)
                when (parts.size) {
                    2 -> BindMount(parts[0], parts[1])
                    3 -> BindMount(parts[0], parts[1], ro = parts[2].trim() == "ro")
                    else -> null
                }
            } ?: emptyList()

            // Parse upstream interfaces
            val upstreamInterfaces = configMap["upstream_interfaces"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            val sshEnabled = configMap["ssh_enabled"] == "1"
            val sshPort = configMap["ssh_port"]?.toIntOrNull() ?: 2222
            val sshAuthorizedKeys = configMap["ssh_authorized_keys"]?.split(",")?.mapNotNull { encoded ->
                try {
                    val decoded = String(android.util.Base64.decode(encoded.trim(), android.util.Base64.NO_WRAP), Charsets.UTF_8).trim()
                    decoded.takeIf { it.isNotBlank() }
                } catch (e: Exception) { null }
            } ?: emptyList()

            // Filter out the SSH and wireless-display forwards -- they are derived
            // dynamically at write time (see toConfigContent), not part of the user's
            // manual port forward list, so they must not reappear there and get
            // duplicated on next save.
            val wirelessDisplayEnabled = configMap["wireless_display_enabled"] == "1"
            val wirelessDisplayMode = configMap["wireless_display_mode"] ?: "vnc"
            val vncPort = configMap["vnc_port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: Constants.DEFAULT_VNC_PORT
            val streamPort = configMap["stream_port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: Constants.DEFAULT_STREAM_PORT
            val activeDisplayPort = if (wirelessDisplayMode == "stream") streamPort else vncPort
            val dockerEnabled = configMap["docker_enabled"] == "1"
            val dockerExposeTcp = configMap["docker_expose_tcp"] == "1"
            val dockerTcpPort = configMap["docker_tcp_port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: Constants.DEFAULT_DOCKER_TCP_PORT
            val portForwards = configMap["port_forwards"]?.split(",")?.mapNotNull { pfStr ->
                try {
                    val parts = pfStr.trim().split("/")
                    val proto = if (parts.size > 1) parts[1].lowercase() else "tcp"
                    val portParts = parts[0].split(":")
                    if (portParts.size == 2) {
                        PortForward(portParts[0].trim(), portParts[1].trim(), proto)
                    } else if (portParts.size == 1 && portParts[0].isNotBlank()) {
                        PortForward(portParts[0].trim(), null, proto)
                    } else null
                } catch (e: Exception) { null }
            }?.filterNot {
                (sshEnabled && it.hostPort == sshPort.toString() && it.containerPort == "22" && it.proto == "tcp") ||
                (wirelessDisplayEnabled && it.hostPort == activeDisplayPort.toString() && (it.containerPort == activeDisplayPort.toString() || it.containerPort == null) && it.proto == "tcp") ||
                (dockerEnabled && dockerExposeTcp && it.hostPort == dockerTcpPort.toString() && (it.containerPort == dockerTcpPort.toString() || it.containerPort == null) && it.proto == "tcp")
            } ?: emptyList()

            return ContainerInfo(
                name = containerName,
                hostname = configMap["hostname"] ?: ValidationUtils.sanitizeHostname(containerName),
                // Use the new rootfs path structure (LXC-style) or sparse image path
                rootfsPath = configMap["rootfs_path"] ?: if (useSparseImage) {
                    getSparseImagePath(containerName)
                } else {
                    getRootfsPath(containerName)
                },
                netMode = configMap["net_mode"] ?: Constants.DEFAULT_NET_MODE,
                disableIPv6 = configMap["disable_ipv6"] == "1",
                enableAndroidStorage = configMap["enable_android_storage"] == "1",
                enableHwAccess = configMap["enable_hw_access"] == "1",
                enableGpuMode = configMap["enable_gpu_mode"] == "1",
                enableTermuxX11 = (configMap["enable_x11"] ?: configMap["enable_termux_x11"]) == "1",
                tx11ExtraFlags = configMap["tx11_extra_flags"] ?: "",
                enableAnland = configMap["enable_anland"] == "1",
                installUsbManager = configMap["install_usb_manager"] == "1",
                enableVirgl = configMap["enable_virgl"] == "1",
                virglExtraFlags = configMap["virgl_extra_flags"] ?: "",
                enablePulseaudio = configMap["enable_pulseaudio"] == "1",
                wirelessDisplayEnabled = wirelessDisplayEnabled,
                wirelessDisplayMode = wirelessDisplayMode,
                vncPort = vncPort,
                vncLanOnly = configMap["vnc_lan_only"] != "0",
                streamPort = streamPort,
                streamFps = configMap["stream_fps"]?.toIntOrNull()?.takeIf { it in 1..120 } ?: 30,
                streamBitrateKbps = configMap["stream_bitrate_kbps"]?.toIntOrNull()?.takeIf { it > 0 } ?: 4000,
                streamCodec = configMap["stream_codec"] ?: "h264",
                streamPreset = configMap["stream_preset"] ?: "low-latency",
                streamResolution = configMap["stream_resolution"] ?: "native",
                streamEngine = configMap["stream_engine"] ?: "auto",
                sshEnabled = sshEnabled,
                sshPort = sshPort,
                sshLanOnly = configMap["ssh_lan_only"] != "0",
                sshUser = configMap["ssh_user"] ?: "",
                sshAuthorizedKeys = sshAuthorizedKeys,
                sshPasswordAuthEnabled = configMap["ssh_password_auth"] != "0",
                dockerEnabled = dockerEnabled,
                dockerExposeTcp = dockerExposeTcp,
                dockerTcpPort = dockerTcpPort,
                dockerForceVfs = configMap["docker_force_vfs"] == "1",
                scrcpyEnabled = configMap["scrcpy_enabled"] == "1",
                scrcpyMaxSize = configMap["scrcpy_max_size"]?.toIntOrNull()?.takeIf { it >= 0 } ?: Constants.DEFAULT_SCRCPY_MAX_SIZE,
                scrcpyBitrateKbps = configMap["scrcpy_bitrate_kbps"]?.toIntOrNull()?.takeIf { it > 0 } ?: Constants.DEFAULT_SCRCPY_BITRATE_KBPS,
                enableClipboardBridge = configMap["enable_clipboard_bridge"] == "1",
                clipboardSyncIntervalMs = configMap["clipboard_sync_interval"]?.toIntOrNull()
                    ?: Constants.CLIPBOARD_DEFAULT_SYNC_INTERVAL_MS,
                clipboardMaxSizeBytes = configMap["clipboard_max_size"]?.toLongOrNull()
                    ?: Constants.CLIPBOARD_DEFAULT_MAX_SIZE_BYTES,
                selinuxPermissive = configMap["selinux_permissive"] == "1",
                allowSandboxing = (configMap["allow_sandboxing"] ?: configMap["allow_userns"]) == "1",
                volatileMode = configMap["volatile_mode"] == "1",
                bindMounts = bindMounts,
                dnsServers = configMap["dns_servers"] ?: "",
                runAtBoot = configMap["run_at_boot"] == "1",
                runAtBootPriority = configMap["run_at_boot_priority"]?.toIntOrNull() ?: 0,
                status = ContainerStatus.STOPPED,
                useSparseImage = useSparseImage,
                sparseImageSizeGB = sparseImageSizeGB,
                envFileContent = if (loadEnvironment) loadEnvFileContent(containerName) else null,
                upstreamInterfaces = upstreamInterfaces,
                portForwards = portForwards,
                forceCgroupv1 = configMap["force_cgroupv1"] == "1",
                staticNatIp = configMap["static_nat_ip"] ?: "",
                gatewayContainer = configMap["gateway_container"] ?: "",
                gatewayNet = configMap["gateway_net"] ?: "",
                gatewayIface = configMap["gateway_lan_ifname"] ?: "",
                gatewayBridge = configMap["gateway_bridge"] ?: "",
                macvlanParent = configMap["macvlan_parent"] ?: "",
                macvlanMode = configMap["macvlan_mode"] ?: "",
                privileged = configMap["privileged"] ?: "",
                customInit = configMap["custom_init"] ?: "",
                memoryLimit = configMap["memory_limit"]?.toLongOrNull() ?: 0,
                // The CLI can set any period, we always write the default one.
                cpuQuota = (configMap["cpu_quota"]?.toLongOrNull() ?: 0) * ResourceLimits.CPU_PERIOD_US /
                    (configMap["cpu_period"]?.toLongOrNull()?.takeIf { it > 0 } ?: ResourceLimits.CPU_PERIOD_US),
                pidsLimit = configMap["pids_limit"]?.toLongOrNull() ?: 0,
                uuid = configMap["uuid"] ?: "",
                createDefaultUser = configMap["create_default_user"] == "1",
                defaultUsername = configMap["default_username"] ?: "",
                defaultUserPassword = configMap["default_user_password_b64"]?.let { b64 ->
                    try {
                        String(android.util.Base64.decode(b64.trim(), android.util.Base64.NO_WRAP), Charsets.UTF_8)
                    } catch (e: Exception) { configMap["default_user_password"] ?: "" }
                } ?: configMap["default_user_password"] ?: "",
                defaultUserSudo = configMap["default_user_sudo"] != "0",
                defaultUserShell = configMap["default_user_shell"] ?: "/bin/bash"
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Load .env file content for a container.
     */
    private fun loadEnvFileContent(containerName: String): String? {
        val envFilePath = "${getContainerDirectory(containerName)}/.env"
        val readResult = Shell.cmd("cat ${ContainerCommandBuilder.quote(envFilePath)} 2>/dev/null").exec()
        return if (readResult.isSuccess && readResult.out.isNotEmpty()) {
            readResult.out.joinToString("\n")
        } else {
            null
        }
    }

    /**
     * Check if a container is running and get its correct init PID.
     * Returns Pair<isRunning, pid>
     *
     * Uses 'droidspaces --name=X pid' which:
     *  - Reads the PID file directly (no pgrep guessing)
     *  - Checks kill(pid, 0) to confirm the process is alive
     *  - Prints just the PID number or "NONE"
     *  - NEVER calls cleanup_container_resources (safe to call post-start)
     */
    suspend fun checkContainerStatus(containerName: String): Pair<Boolean, Int?> = withContext(Dispatchers.IO) {
        try {
            val binary = Constants.DROIDSPACES_BINARY_PATH
            val quotedName = ContainerCommandBuilder.quote(containerName)
            val result = Shell.cmd("\"$binary\" --name=$quotedName pid 2>/dev/null").exec()

            val output = result.out.firstOrNull()?.trim() ?: "NONE"
            if (output == "NONE" || output.isEmpty()) {
                return@withContext Pair(false, null)
            }

            val pid = output.toIntOrNull()
            if (pid != null && pid > 0) {
                return@withContext Pair(true, pid)
            }
        } catch (e: Exception) {
            // Ignore errors - treat as stopped
        }

        Pair(false, null)
    }

    /**
     * Return the anland display-daemon socket path for a running container, or
     * null if it isn't running / anland isn't active. The native runtime records
     * the generated per-container socket path in Pids/<name>.anland at start and
     * removes it on stop, so the presence of a non-empty path also signals that
     * the anland window can be launched.
     */
    suspend fun getAnlandSocket(containerName: String): String? = withContext(Dispatchers.IO) {
        try {
            val path = "${Constants.PIDS_BASE_PATH}/$containerName.anland"
            val result = Shell.cmd("cat ${ContainerCommandBuilder.quote(path)} 2>/dev/null").exec()
            val sock = result.out.firstOrNull()?.trim()
            if (result.isSuccess && !sock.isNullOrEmpty()) sock else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read the clipboard bridge daemon's status. It's a single global daemon shared by
     * every container (like X11/PulseAudio -- one process, not one per container), so
     * this is called once per [listContainers] refresh rather than per container.
     *
     * Returns Pair<state, lastSyncEpochSeconds>. State is one of "connected",
     * "reconnecting" (both reported by the daemon itself in clip.status), or
     * "disconnected" (daemon process isn't alive, regardless of what a stale
     * clip.status might still say).
     */
    private suspend fun readClipboardBridgeStatus(): Pair<String, Long?> = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(
                "cat \"${Constants.CLIPBOARD_PID_PATH}\" 2>/dev/null; echo ---; cat \"${Constants.CLIPBOARD_STATUS_PATH}\" 2>/dev/null"
            ).exec()
            val parts = result.out.joinToString("\n").split("---")
            val pid = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val alive = pid != null && pid > 0 &&
                Shell.cmd("kill -0 $pid 2>/dev/null").exec().isSuccess
            if (!alive) {
                return@withContext Pair("disconnected", null)
            }

            val statusLines = parts.getOrNull(1)?.trim()?.lines() ?: emptyList()
            val state = statusLines.firstOrNull { it.startsWith("state=") }
                ?.substringAfter("state=")?.trim() ?: "connected"
            val lastSync = statusLines.firstOrNull { it.startsWith("last_sync=") }
                ?.substringAfter("last_sync=")?.trim()?.toLongOrNull()
                ?.takeIf { it > 0 }
            Pair(state, lastSync)
        } catch (e: Exception) {
            Pair("disconnected", null)
        }
    }

    /**
     * Get container info by name.
     * Note: name should be the sanitized directory name (spaces replaced with dashes).
     */
    suspend fun getContainerInfo(name: String): ContainerInfo? = withContext(Dispatchers.IO) {
        val sanitizedName = sanitizeContainerName(name)
        val configPath = "$CONTAINERS_BASE_PATH/$sanitizedName/${Constants.CONTAINER_CONFIG_FILE}"
        val config = loadContainerConfig(configPath, sanitizedName)

        if (config != null) {
            val runningInfo = checkContainerStatus(name)
            val status = if (runningInfo.first) {
                ContainerStatus.RUNNING
            } else {
                ContainerStatus.STOPPED
            }
            config.copy(
                status = status,
                pid = runningInfo.second
            )
        } else {
            null
        }
    }

    /**
     * List active upstream interfaces by scanning all routing tables.
     *
     * Uses `table all` instead of the default table so that CLAT/Qualcomm
     * devices are correctly detected - on these devices every interface has
     * its own per-interface routing table and nothing appears in the main
     * table, so `ip route show default` returns empty.
     */
    suspend fun listUpstreamInterfaces(): List<String> = withContext(Dispatchers.IO) {
        try {
            val busybox = Constants.BUSYBOX_BINARY_PATH
            val cmd = "ip route show table all | $busybox grep '^default' | $busybox awk '{for(i=1;i<=NF;i++) if(\$i==\"dev\") print \$(i+1)}' | $busybox grep -Ev '^(ds-|dummy)' | $busybox sort -u"
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess) {
                result.out.map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Host interfaces a macvlan can sit on: Ethernet-type links (type 1), minus
     * our own, dummies and tunnels. Wi-Fi is listed too; whether a given access
     * point carries a second MAC is for the user to find out.
     */
    suspend fun listEthernetInterfaces(): List<String> = withContext(Dispatchers.IO) {
        try {
            val busybox = Constants.BUSYBOX_BINARY_PATH
            val result = Shell.cmd("$busybox grep -l '^1$' /sys/class/net/*/type; true").exec()
            result.out.mapNotNull { it.trim().removePrefix("/sys/class/net/").removeSuffix("/type").takeIf(String::isNotEmpty) }
                .filterNot { n -> ETHERNET_SKIP.any { n.startsWith(it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Ours, dummies and tunnels: type 1 links a macvlan cannot usefully sit on. */
    private val ETHERNET_SKIP = listOf("ds-", "dummy", "ifb", "gretap", "erspan", "p2p")

    /**
     * Update container configuration.
     * Updates the configurable options (hostname, flags, rootfsPath, etc.), not the
     * container's name. Note: changing rootfsPath here only rewrites the config file --
     * it does NOT move any data on disk. Use [moveContainerStorage] to actually relocate
     * a container's rootfs and update its config together.
     *
     * @param context Android context for temporary file creation
     * @param containerName Name of the container to update (will be sanitized)
     * @param newConfig New configuration values (only configurable fields are used)
     * @return Result.success on success, Result.failure on error
     */
    suspend fun updateContainerConfig(
        context: Context,
        containerName: String,
        newConfig: ContainerInfo
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Reject control chars in single-line config values.
            ValidationUtils.validateConfigValues(newConfig).errorMessage?.let {
                return@withContext Result.failure(Exception(it))
            }
            val sanitizedName = sanitizeContainerName(containerName)
            val configPath = "$CONTAINERS_BASE_PATH/$sanitizedName/${Constants.CONTAINER_CONFIG_FILE}"

            // Build new config content using the shared method
            // Preserve the existing UUID -- never overwrite it with an empty value
            val configToWrite = if (newConfig.uuid.isNotEmpty()) {
                newConfig
            } else {
                val existingContent = Shell.cmd("cat ${ContainerCommandBuilder.quote(configPath)} 2>/dev/null").exec()
                    .out.joinToString("\n")
                val existingUuid = existingContent.lines()
                    .firstOrNull { it.startsWith("uuid=") }
                    ?.removePrefix("uuid=")?.trim() ?: ""
                newConfig.copy(uuid = existingUuid)
            }
            val configContent = configToWrite.toConfigContent()

            // Handle .env file
            val envFilePath = "${getContainerDirectory(containerName)}/.env"
            if (newConfig.envFileContent.isNullOrBlank()) {
                Shell.cmd("rm -f ${ContainerCommandBuilder.quote(envFilePath)}").exec()
            } else {
                val tempEnvFile = File("${context.cacheDir}/.env_${sanitizedName}")
                tempEnvFile.writeText(newConfig.envFileContent + "\n")
                Shell.cmd("cp ${ContainerCommandBuilder.quote(tempEnvFile.absolutePath)} ${ContainerCommandBuilder.quote(envFilePath)}").exec()
                Shell.cmd("chmod 644 ${ContainerCommandBuilder.quote(envFilePath)}").exec()
                tempEnvFile.delete()
            }

            // Write config to temp file first (app can write to cache dir)
            // Use sanitizedName to avoid issues with spaces in filename
            val tempConfigFile = File("${context.cacheDir}/container_${sanitizedName}.config")
            tempConfigFile.writeText(configContent)

            // Copy temp config to final location using shell (root required)
            // Quote paths to handle spaces and special characters
            val copyResult = Shell.cmd("cp ${ContainerCommandBuilder.quote(tempConfigFile.absolutePath)} ${ContainerCommandBuilder.quote(configPath)} 2>&1").exec()
            if (!copyResult.isSuccess) {
                // Check both stdout and stderr for error messages
                val errorOutput = (copyResult.out + copyResult.err).joinToString("\n").trim()
                val errorMsg = if (errorOutput.isNotEmpty()) errorOutput else "Unknown error (exit code: ${copyResult.code})"
                tempConfigFile.delete()
                return@withContext Result.failure(Exception("Failed to update container config: $errorMsg"))
            }

            // Set proper permissions
            val chmodResult = Shell.cmd("chmod 644 ${ContainerCommandBuilder.quote(configPath)} 2>&1").exec()
            if (!chmodResult.isSuccess) {
                // Non-fatal, but log warning
            }

            // Clean up temp config file
            tempConfigFile.delete()

            // Best-effort sync embedded config inside sparse image for portability
            if (newConfig.useSparseImage && newConfig.rootfsPath.isNotBlank()) {
                try {
                    ExistingImageManager.embedConfig(newConfig.rootfsPath, configContent, context)
                } catch (e: Exception) {
                    // Non-fatal
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Auto-detects if an external container's rootfs or image path has changed
     * due to USB storage eject/remount (e.g. volume UUID or mount point change).
     * If found on another active external storage volume, updates container.config on disk
     * and returns the updated ContainerInfo.
     */
    suspend fun autoDetectAndRemapContainerPath(
        context: Context,
        container: ContainerInfo,
        logger: ContainerLogger? = null
    ): ContainerInfo = withContext(Dispatchers.IO) {
        val oldPath = container.rootfsPath.trim()
        if (oldPath.isBlank()) return@withContext container

        // Ensure stale loop devices and mounts are cleaned first
        StorageMountManager.cleanupStaleMountsAndLoopDevices()
        StorageMountManager.ensureVolumeMounted(oldPath)

        val oldVolId = StorageMountManager.extractVolumeId(oldPath)

        // For external sparse images, ALWAYS ensure the physical path (/mnt/media_rw/...)
        // is used in container.config so loop mounting bypasses Android FUSE layer (which returns ENXIO).
        if (oldVolId != null && container.useSparseImage) {
            val physicalPath = StorageMountManager.resolveToPhysicalPath(oldPath)
            val qPhys = ContainerCommandBuilder.quote(physicalPath)
            val physExists = Shell.cmd("[ -f $qPhys ] && echo yes || echo no").exec().out.firstOrNull()?.trim() == "yes"
            if (physExists) {
                if (oldPath != physicalPath) {
                    logger?.i("Using raw physical storage path for loop mount: $physicalPath")
                    val updated = container.copy(rootfsPath = physicalPath)
                    updateContainerConfig(context, container.name, updated)
                    return@withContext updated
                }
                return@withContext container
            }
        }

        // Check if current path exists and is accessible
        val qOld = ContainerCommandBuilder.quote(oldPath)
        val exists = Shell.cmd("[ -e $qOld ] && echo yes || echo no").exec().out.firstOrNull()?.trim() == "yes"
        if (exists) {
            return@withContext container
        }

        if (oldVolId == null) return@withContext container

        // Extract subpath after the old volume ID
        val subPath = oldPath.substringAfter(oldVolId, "").trimStart('/')
        val filename = oldPath.substringAfterLast("/")

        // Discover all currently connected storage volumes
        val activeVolumes = StorageMountManager.listAllStorageVolumes()
        for (volPath in activeVolumes) {
            val candidatePaths = mutableListOf<String>()
            if (subPath.isNotEmpty()) {
                candidatePaths.add("$volPath/$subPath")
            }
            candidatePaths.add("$volPath/${sanitizeContainerName(container.name)}/rootfs.img")
            candidatePaths.add("$volPath/${sanitizeContainerName(container.name)}/rootfs")
            if (filename.isNotEmpty() && filename != "rootfs.img" && filename != "rootfs") {
                candidatePaths.add("$volPath/$filename")
            }

            for (cand in candidatePaths.distinct()) {
                val qCand = ContainerCommandBuilder.quote(cand)
                val candExists = Shell.cmd("[ -e $qCand ] && echo yes || echo no").exec().out.firstOrNull()?.trim() == "yes"
                if (candExists) {
                    val targetPath = if (container.useSparseImage) {
                        StorageMountManager.resolveToPhysicalPath(cand)
                    } else {
                        cand
                    }
                    logger?.i("Storage remount detected: container rootfs relocated from $oldPath to $targetPath")
                    val updated = container.copy(rootfsPath = targetPath)
                    updateContainerConfig(context, container.name, updated)
                    return@withContext updated
                }
            }
        }

        logger?.w("Warning: Container rootfs not found at $oldPath and no matching path found on connected storage drives.")
        container
    }

    /**
     * Move a container's rootfs (or rootfs.img) to a different storage location and
     * update its config file to match. This is the "storage relocation" companion to
     * [ContainerInstaller.installContainer]'s initial storage-location choice.
     *
     * The container's small metadata (config file, .env, pidfile) always stays under the
     * internal CONTAINERS_BASE_PATH -- only the (large) rootfs data is relocated.
     *
     * @param context Android context, needed by [updateContainerConfig]
     * @param container The container to move (must be stopped)
     * @param newStorageDir Destination storage root, e.g. "/storage/1234-5678/Droidspaces".
     *   Pass null to move the container back to the default internal location.
     * @param logger Optional logger for progress/diagnostic messages
     * @param onProgress Optional live progress callback -- see [MoveStorageOperations].
     * @return Result.success(newRootfsPath) on success, Result.failure with a
     *   human-readable message on error. On failure, no partial state is left behind:
     *   either the move completed and the config was updated, or nothing changed.
     *
     * Relocation always goes through copy -> verify -> delete (see
     * [MoveStorageOperations]) rather than a plain `cp`/`mv`, *except* when
     * source and destination are on the very same filesystem: there, a rename
     * is a metadata-only operation with no data duplication and therefore
     * nothing to verify -- it's used as a fast path, not a shortcut around
     * safety.
     * @param verificationMethod How thoroughly to verify the destination
     *   before deleting the source, when a copy is actually needed (ignored
     *   on the same-filesystem rename fast path, where nothing is copied and
     *   there's nothing to verify). Defaults to [MoveVerificationMethod.SHA256]
     *   -- the strongest guarantee -- but is user-choosable; see
     *   [MoveVerificationMethod] for the trade-offs of each option.
     */
    suspend fun moveContainerStorage(
        context: Context,
        container: ContainerInfo,
        newStorageDir: String?,
        logger: ContainerLogger? = null,
        verificationMethod: MoveVerificationMethod = MoveVerificationMethod.SHA256,
        skipFreeSpaceCheck: Boolean = false,
        onProgress: MoveProgressCallback? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Refuse to move a running container -- the rootfs may be actively
            // mounted/in use and moving it out from under a live process is unsafe.
            val (isRunning, _) = checkContainerStatus(container.name)
            if (isRunning) {
                return@withContext Result.failure(
                    Exception("Stop the container before moving its storage.")
                )
            }

            // /mnt/media_rw/<vol> is vold's raw mount of removable media. Writing
            // there directly is unreliable -- see StorageChecker.normalizeStorageDir.
            val normalizedStorageDir = newStorageDir?.let { StorageChecker.normalizeStorageDir(it) }

            val containerPath = getContainerDirectory(container.name)
            val oldRootfsPath = container.rootfsPath
            val newRootfsPath = if (container.useSparseImage) {
                getSparseImagePath(container.name, normalizedStorageDir)
            } else {
                getRootfsPath(container.name, normalizedStorageDir)
            }

            if (oldRootfsPath == newRootfsPath) {
                return@withContext Result.failure(
                    Exception("Container is already at that location.")
                )
            }

            val isExternal = RootNamespace.isExternalStorage(oldRootfsPath) || RootNamespace.isExternalStorage(newRootfsPath)
            val quotedOld = ContainerCommandBuilder.quote(oldRootfsPath)
            val quotedNew = ContainerCommandBuilder.quote(newRootfsPath)

            // Verify the current rootfs actually exists before doing anything.
            val existsCheck = RootNamespace.exec("[ -e $quotedOld ] && echo yes || echo no", forceNsenter = isExternal)
            if (existsCheck.out.firstOrNull()?.trim() != "yes") {
                return@withContext Result.failure(
                    Exception("Current rootfs not found at $oldRootfsPath")
                )
            }

            val newParentDir = newRootfsPath.substringBeforeLast("/", newRootfsPath)

            val mkdirParentResult = RootNamespace.exec(
                "mkdir -p ${ContainerCommandBuilder.quote(newParentDir)} 2>&1",
                forceNsenter = isExternal
            )
            if (!mkdirParentResult.isSuccess) {
                val err = (mkdirParentResult.out + mkdirParentResult.err).joinToString("\n").trim()
                return@withContext Result.failure(Exception("Failed to create destination directory: $err"))
            }

            val sizeResult = RootNamespace.exec(
                "du -sk $quotedOld 2>/dev/null | ${Constants.BUSYBOX_BINARY_PATH} awk '{print \$1}'",
                forceNsenter = isExternal
            )
            val neededKB = sizeResult.out.firstOrNull()?.trim()?.toLongOrNull()

            if (!skipFreeSpaceCheck) {
                // Best-effort free-space check at the destination (non-fatal if it can't
                // be determined -- some filesystems/paths don't report du/df cleanly).
                logger?.i("Checking free space at $newParentDir ...")
                if (neededKB != null) {
                    val freeGB = StorageChecker.getFreeSpaceGB(newParentDir)
                    val neededGB = (neededKB / 1024 / 1024).toInt() + 1 // round up + 1GB margin
                    if (freeGB != null && freeGB < neededGB) {
                        return@withContext Result.failure(
                            Exception("Not enough free space at destination (~${neededGB}GB needed, ${freeGB}GB free)")
                        )
                    }
                }
            } else {
                logger?.i("Skipping free-space check (per user option).")
            }

            // FAT32 caps any single file at 4 GiB. cp/mv into a FAT32 destination
            // fail mid-copy with "short write: No space left on device" once the
            // destination file crosses that line, even with plenty of free space on
            // the volume -- catch it up front with a clear message instead. exFAT
            // and ext4 have no such limit.
            val fsTypeResult = RootNamespace.exec(
                "stat -f -c '%T' ${ContainerCommandBuilder.quote(newParentDir)} 2>/dev/null",
                forceNsenter = isExternal
            )
            val destFsType = fsTypeResult.out.firstOrNull()?.trim()?.lowercase()
            val isFat32 = destFsType == "msdos" || destFsType == "vfat" || destFsType == "fat"
            if (isFat32 && neededKB != null && neededKB > 4L * 1024 * 1024) {
                return@withContext Result.failure(
                    Exception(
                        "Destination is FAT32, which can't hold a single file over 4GB " +
                        "(this container's data is ~${neededKB / 1024 / 1024}GB). " +
                        "Reformat the drive as exFAT or ext4, or pick a different destination."
                    )
                )
            }

            // Same-filesystem fast path: a rename is a metadata-only operation
            // (no bytes are duplicated), so there's nothing to verify -- use it
            // when available instead of needlessly copying+hashing gigabytes of
            // data that's already perfectly safe. Detected by comparing device
            // IDs rather than just trying `mv` and reacting to failure, so we
            // decide up front which path (and which progress UI) applies.
            val oldDevResult = RootNamespace.exec(
                "stat -c %d ${ContainerCommandBuilder.quote(oldRootfsPath.substringBeforeLast("/", oldRootfsPath))} 2>/dev/null",
                forceNsenter = isExternal
            )
            val newDevResult = RootNamespace.exec(
                "stat -c %d ${ContainerCommandBuilder.quote(newParentDir)} 2>/dev/null",
                forceNsenter = isExternal
            )
            val oldDev = oldDevResult.out.firstOrNull()?.trim()
            val newDev = newDevResult.out.firstOrNull()?.trim()
            val sameFilesystem = oldDev != null && oldDev == newDev

            if (sameFilesystem) {
                logger?.i("Same filesystem detected -- moving via instant rename (no copy needed).")
                onProgress?.invoke(MoveStoragePhase.COPYING, 100, null, null, "Renaming (same filesystem, instant)...")
                val mvResult = RootNamespace.exec("mv $quotedOld $quotedNew 2>&1", forceNsenter = isExternal)
                if (!mvResult.isSuccess) {
                    val err = (mvResult.out + mvResult.err).joinToString("\n").trim()
                    return@withContext Result.failure(Exception("Failed to move rootfs: $err"))
                }
                onProgress?.invoke(MoveStoragePhase.VERIFYING, 100, null, null, "Same filesystem -- rename is lossless, nothing to verify.")

                val updateResult = updateContainerConfig(
                    context, container.name, container.copy(rootfsPath = newRootfsPath)
                )
                if (updateResult.isFailure) {
                    // Move the data back so we don't leave the container broken.
                    RootNamespace.exec("mv $quotedNew $quotedOld 2>&1", forceNsenter = isExternal)
                    return@withContext Result.failure(
                        updateResult.exceptionOrNull() ?: Exception("Failed to update container config")
                    )
                }
                onProgress?.invoke(MoveStoragePhase.DELETING, 100, null, null, "Nothing left to remove (rename moved the only copy).")
            } else {
                if (!skipFreeSpaceCheck) {
                    // Precheck: verify the destination actually has room before touching
                    // anything, so a doomed move fails immediately with a clear "need X,
                    // have Y" message instead of running for minutes/hours only to fail
                    // (or worse, silently fill the destination volume) partway through a
                    // multi-gigabyte copy.
                    logger?.i("Checking available space at destination...")
                    val capacityResult = MoveStorageOperations.checkCapacity(
                        quotedSrc = quotedOld,
                        quotedDst = quotedNew,
                        isSingleFile = container.useSparseImage,
                        context = context,
                        targetPath = null,
                        forceNsenter = isExternal
                    )
                    val capacity = capacityResult.getOrNull()
                    if (capacity == null) {
                        logger?.w("Could not verify available space -- proceeding anyway. ${capacityResult.exceptionOrNull()?.message ?: ""}")
                    } else if (capacity.availableBytes == 0L) {
                        // A hard 0 is virtually never the true answer for a real device's
                        // free space -- it means df's output couldn't be trusted on this
                        // device/path even after MoveStorageOperations' own physical-path
                        // fallback (see checkCapacity), not that the disk is genuinely
                        // full to the last byte. Treat it as "couldn't determine" rather
                        // than blocking an otherwise-legitimate move on a false reading.
                        logger?.w("Could not reliably determine available space at destination -- proceeding anyway.")
                    } else if (!capacity.sufficient) {
                        val msg = "Not enough space at destination: need ${MoveStorageOperations.formatBytes(capacity.requiredBytes)}, " +
                            "only ${MoveStorageOperations.formatBytes(capacity.availableBytes)} available."
                        logger?.e(msg)
                        return@withContext Result.failure(Exception(msg))
                    } else {
                        logger?.i(
                            "Space check passed: need ${MoveStorageOperations.formatBytes(capacity.requiredBytes)}, " +
                                "${MoveStorageOperations.formatBytes(capacity.availableBytes)} available."
                        )
                    }
                } else {
                    logger?.i("Skipping destination capacity check (per user option).")
                }

                logger?.i("Different filesystems -- copying, verifying (${verificationMethod.name.lowercase()}), then removing the original. This may take a while for large containers.")

                val copyVerifyResult = MoveStorageOperations.moveViaCopyVerifyDelete(
                    quotedSrc = quotedOld,
                    quotedDst = quotedNew,
                    isSingleFile = container.useSparseImage,
                    verificationMethod = verificationMethod,
                    context = context,
                    // No targetPath here deliberately: it's only ever the OLD or
                    // NEW path, but this script's commands reference BOTH, which
                    // could be two *different* external volumes (e.g. SD card ->
                    // USB-OTG). RootNamespace's targetPath-driven physicalization
                    // only rewrites one volume's prefix and, if it succeeds,
                    // skips the nsenter fallback entirely -- leaving the other
                    // volume's path pointing at an empty view. forceNsenter alone
                    // (matching every other call in this function) enters the
                    // app's own namespace, which sees ALL mounted volumes at
                    // once via FUSE, so both paths resolve correctly.
                    targetPath = null,
                    forceNsenter = isExternal,
                    logger = logger,
                    onProgress = onProgress
                )
                if (copyVerifyResult.isFailure) {
                    // Source is guaranteed untouched at this point -- see
                    // MoveStorageOperations' contract. Just clean up any
                    // unverified destination remnants (already done internally
                    // on most failure paths, but harmless/idempotent here too)
                    // and surface the error.
                    RootNamespace.exec("rm -rf $quotedNew 2>&1", forceNsenter = isExternal)
                    return@withContext Result.failure(
                        copyVerifyResult.exceptionOrNull() ?: Exception("Copy/verify failed")
                    )
                }

                // Destination is copied and verified byte-for-byte identical to
                // the source. Repoint the config BEFORE deleting the source, so
                // a crash/kill here never leaves the container referencing data
                // that's about to be removed.
                val updateResult = updateContainerConfig(
                    context, container.name, container.copy(rootfsPath = newRootfsPath)
                )
                if (updateResult.isFailure) {
                    RootNamespace.exec("rm -rf $quotedNew 2>&1", forceNsenter = isExternal)
                    return@withContext Result.failure(
                        updateResult.exceptionOrNull() ?: Exception("Failed to update container config")
                    )
                }

                val deleteResult = MoveStorageOperations.deleteSource(
                    quotedSrc = quotedOld,
                    isSingleFile = container.useSparseImage,
                    context = context,
                    targetPath = null,
                    forceNsenter = isExternal,
                    logger = logger,
                    onProgress = onProgress
                )
                if (deleteResult.isFailure) {
                    // Config already points at the new (verified-good) location,
                    // so this is a cleanup failure, not a data-safety one --
                    // surface it as a warning, not an overall failure.
                    logger?.w(
                        "Move succeeded, but couldn't remove the old data at $oldRootfsPath: " +
                            "${deleteResult.exceptionOrNull()?.message}. Remove it manually to free up space."
                    )
                }
            }

            // Clean up the now-empty old external directory, if any (no-op/safe if
            // it's not empty or was the internal container directory).
            val oldParentDir = oldRootfsPath.substringBeforeLast("/", "")
            if (oldParentDir.isNotEmpty() && oldParentDir != containerPath) {
                RootNamespace.exec("rmdir ${ContainerCommandBuilder.quote(oldParentDir)} 2>/dev/null", forceNsenter = isExternal)
            }

            // Compact and verify sparse image at destination to reclaim free blocks after move
            if (container.useSparseImage || newRootfsPath.endsWith(".img")) {
                logger?.i("Compacting and verifying sparse image at new destination...")
                val compactResult = ContainerDiskUsageManager.repairAndCompact(context, newRootfsPath)
                if (compactResult.isSuccess) {
                    val cr = compactResult.getOrNull()
                    if (cr != null) {
                        logger?.i("Compaction complete: ${cr.before.usedBytes / 1024 / 1024}MB -> ${cr.after.usedBytes / 1024 / 1024}MB allocated.")
                    }
                } else {
                    logger?.w("Post-move compaction notice: ${compactResult.exceptionOrNull()?.message}")
                }
            }

            logger?.i("Move complete. New location: $newRootfsPath")
            Result.success(newRootfsPath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uninstall a container by stopping it (if running) and deleting its directory.
     * This function logs all operations using the provided logger callback.
     *
     * @param container Container to uninstall
     * @param logger Logger callback for logging operations
     * @return Result.success on success, Result.failure on error
     */
    suspend fun uninstallContainer(
        container: ContainerInfo,
        logger: ContainerLogger
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            logger.i("Starting uninstallation of container: ${container.name}")
            logger.i("")

            // Step 1: Check if container is running
            logger.i("Step 1: Checking container status...")
            val isRunning = checkContainerStatus(container.name).first

            if (isRunning) {
                logger.i("Container is currently running. Stopping it first...")
                logger.i("")

                // Stop the container using droidspaces command
                val stopCommand = ContainerCommandBuilder.buildStopCommand(container)
                logger.i("Executing: $stopCommand")

                val stopResult = Shell.cmd("$stopCommand 2>&1").exec()

                // Log stop command output
                if (stopResult.out.isNotEmpty()) {
                    stopResult.out.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            logger.i(trimmed)
                        }
                    }
                }
                if (stopResult.err.isNotEmpty()) {
                    stopResult.err.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            logger.e(trimmed)
                        }
                    }
                }

                if (!stopResult.isSuccess) {
                    logger.e("Failed to stop container (exit code: ${stopResult.code})")
                    logger.e("Uninstallation aborted.")
                    return@withContext Result.failure(Exception("Failed to stop container before uninstallation"))
                }

                logger.i("Container stopped successfully.")
                logger.i("")

                // Wait a moment for the container to fully stop
                kotlinx.coroutines.delay(500)
            } else {
                logger.i("Container is not running. Proceeding with deletion...")
                logger.i("")
            }

            // Step 2: Delete the rootfs if it lives outside the container directory.
            val containerPath = getContainerDirectory(container.name)
            val rootfsPath = readRootfsPath(container.name) ?: container.rootfsPath

            if (rootfsPath.isNotBlank() && !rootfsPath.startsWith("$containerPath/")) {
                logger.i("Step 2: Deleting rootfs at custom location...")
                logger.i("Rootfs path: $rootfsPath")
                deleteExternalRootfs(rootfsPath, logger)
                logger.i("")
            }

            // Step 3: Delete the container directory
            logger.i("Step 3: Deleting container directory...")
            // Delete the parent directory (which contains rootfs and config)
            logger.i("Container path: $containerPath")

            // Use rm -rf to recursively delete the entire container directory
            val deleteCommand = "rm -rf ${ContainerCommandBuilder.quote(containerPath)} 2>&1"
            logger.i("Executing: $deleteCommand")

            val deleteResult = Shell.cmd(deleteCommand).exec()

            // Log delete command output
            if (deleteResult.out.isNotEmpty()) {
                deleteResult.out.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        logger.i(trimmed)
                    }
                }
            }
            if (deleteResult.err.isNotEmpty()) {
                deleteResult.err.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        logger.e(trimmed)
                    }
                }
            }

            if (!deleteResult.isSuccess) {
                logger.e("Failed to delete container directory (exit code: ${deleteResult.code})")
                return@withContext Result.failure(Exception("Failed to delete container directory"))
            }

            // Step 2.1: If this container's rootfs lives on external/custom storage
            // (outside the default container directory), it won't have been touched
            // by the rm -rf above -- clean it up separately.
            val rootfsParentDir = container.rootfsPath.substringBeforeLast("/", "")
            if (rootfsParentDir.isNotEmpty() && rootfsParentDir != containerPath) {
                logger.i("Removing external storage location: $rootfsParentDir")
                val extDeleteResult = RootNamespace.exec(
                    "rm -rf ${ContainerCommandBuilder.quote(rootfsParentDir)} 2>&1",
                    targetPath = rootfsParentDir
                )
                if (!extDeleteResult.isSuccess) {
                    logger.w("Warning: failed to remove external storage directory: $rootfsParentDir")
                } else {
                    logger.i("External storage location removed.")
                }
            }

            // Verify deletion
            logger.i("")
            logger.i("Verifying deletion...")
            val verifyResult = Shell.cmd("test -d ${ContainerCommandBuilder.quote(containerPath)} && echo 'exists' || echo 'deleted' 2>&1").exec()
            if (verifyResult.out.any { it.contains("exists") }) {
                logger.e("Warning: Container directory still exists after deletion attempt!")
                return@withContext Result.failure(Exception("Container directory still exists after deletion"))
            }

            logger.i("Container directory successfully deleted.")
            logger.i("")
            logger.i("Uninstallation completed successfully!")

            Result.success(Unit)
        } catch (e: Exception) {
            logger.e("Exception during uninstallation: ${e.message}")
            logger.e(e.stackTraceToString())
            Result.failure(e)
        }
    }

    // ── SSH Remote Management ───────────────────────────────────────────────

    data class SshStatus(
        val configured: Boolean,
        val serviceRunning: Boolean,
        val port: Int,
        val containerIp: String?,
        val lanAddress: String?
    )

    fun isValidSshPublicKey(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isBlank() || trimmed.contains("\n")) return false
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 2) return false
        val validTypes = setOf(
            "ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "sk-ssh-ed25519@openssh.com"
        )
        if (parts[0] !in validTypes) return false
        return try {
            val decoded = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
            decoded.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun saveContainerConfig(context: Context, newConfig: ContainerInfo): Result<Unit> =
        updateContainerConfig(context, newConfig.name, newConfig)

    /**
     * The phone's own LAN-facing IP -- the address other devices actually connect
     * to, not the container's internal NAT IP.
     *
     * Deliberately does NOT use ConnectivityManager.activeNetwork: that reflects
     * the phone's own uplink/default route, which is the wrong thing to check for
     * hotspot mode -- a phone hotspotting Wi-Fi to other devices while itself
     * using mobile data as its uplink has an active network of TRANSPORT_CELLULAR,
     * even though the hotspot's AP interface has a perfectly good LAN-facing IP
     * that other devices are actively connected to. Same issue for USB tethering.
     * Enumerating interfaces directly sidesteps the question of what the phone's
     * own uplink is entirely.
     *
     * Matches by interface name prefix rather than by address range, since carrier
     * CGNAT commonly hands out 10.x/172.16.x addresses on mobile data too --
     * range alone can't distinguish "real LAN" from "carrier-NATed cellular".
     */
    fun getPhoneLanIpAddress(context: Context): String? {
        return try {
            val lanPrefixes = listOf("wlan", "ap", "swlan", "rndis", "ncm", "usb", "eth")
            val cellularPrefixes = listOf("rmnet", "ccmni", "pdp", "cdma", "clat", "v4-rmnet")

            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { iface ->
                    val name = iface.name.lowercase()
                    lanPrefixes.any { name.startsWith(it) } && cellularPrefixes.none { name.startsWith(it) }
                }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    suspend fun addSshKey(context: Context, container: ContainerInfo, key: String): Result<ContainerInfo> = withContext(Dispatchers.IO) {
        val trimmed = key.trim()
        if (!isValidSshPublicKey(trimmed)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid SSH public key format"))
        }
        if (container.sshAuthorizedKeys.contains(trimmed)) {
            return@withContext Result.success(container)
        }
        val updated = container.copy(sshAuthorizedKeys = container.sshAuthorizedKeys + trimmed)
        val saveRes = saveContainerConfig(context, updated)
        if (saveRes.isFailure) return@withContext Result.failure(saveRes.exceptionOrNull()!!)
        syncSshAuthorizedKeys(updated)
        Result.success(updated)
    }

    suspend fun removeSshKey(context: Context, container: ContainerInfo, key: String): Result<ContainerInfo> = withContext(Dispatchers.IO) {
        val updated = container.copy(sshAuthorizedKeys = container.sshAuthorizedKeys - key)
        val saveRes = saveContainerConfig(context, updated)
        if (saveRes.isFailure) return@withContext Result.failure(saveRes.exceptionOrNull()!!)
        syncSshAuthorizedKeys(updated)
        Result.success(updated)
    }

    data class GeneratedSshKey(
        val privateKey: String,
        val publicKey: String,
        val keyName: String
    )

    suspend fun generateSshKeypair(
        context: Context,
        container: ContainerInfo,
        keyComment: String = "droidspaces-key"
    ): Result<Pair<ContainerInfo, GeneratedSshKey>> = withContext(Dispatchers.IO) {
        try {
            val user = container.sshUser.ifBlank { container.defaultUsername.ifBlank { "root" } }
            val keyGenCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} " +
                "run 'TMPDIR=\"\$(mktemp -d)\" && ssh-keygen -t ed25519 -f \"\$TMPDIR/id_ed25519\" -N \"\" -C \"$keyComment\" -q && " +
                "cat \"\$TMPDIR/id_ed25519\" && echo \"---SSH_KEY_SPLIT---\" && cat \"\$TMPDIR/id_ed25519.pub\" && rm -rf \"\$TMPDIR\"'"
            val res = Shell.cmd(keyGenCmd).exec()
            if (!res.isSuccess) {
                return@withContext Result.failure(Exception(res.err.firstOrNull() ?: "Failed to generate SSH keypair"))
            }
            val output = res.out.joinToString("\n")
            val parts = output.split("---SSH_KEY_SPLIT---")
            if (parts.size < 2) {
                return@withContext Result.failure(Exception("Malformed output from ssh-keygen"))
            }
            val privKey = parts[0].trim()
            val pubKey = parts[1].trim()
            val keyName = "${user}_droidspaces_ed25519"

            val addResult = addSshKey(context, container, pubKey)
            if (addResult.isFailure) {
                return@withContext Result.failure(addResult.exceptionOrNull()!!)
            }

            val updatedContainer = addResult.getOrThrow()
            Result.success(updatedContainer to GeneratedSshKey(privKey, pubKey, keyName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun exportKeyToDownloads(context: Context, filename: String, content: String): Result<String> {
        return try {
            val resolver = context.contentResolver
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return Result.failure(Exception("Failed to create download entry in MediaStore"))
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
                Result.success("Saved to Downloads/$filename")
            } else {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val targetFile = java.io.File(downloadsDir, filename)
                targetFile.writeText(content)
                Result.success("Saved to Downloads/$filename")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncSshAuthorizedKeys(container: ContainerInfo): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val user = container.sshUser.ifBlank { container.defaultUsername.ifBlank { "root" } }
            val fileContent = if (container.sshAuthorizedKeys.isEmpty()) "" else container.sshAuthorizedKeys.joinToString("\n") + "\n"
            val b64 = android.util.Base64.encodeToString(fileContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val script = """
                HOME_DIR="${'$'}(getent passwd $user | cut -d: -f6)"
                if [ -n "${'$'}HOME_DIR" ] && [ -d "${'$'}HOME_DIR" ]; then
                    mkdir -p "${'$'}HOME_DIR/.ssh"
                    echo '$b64' | base64 -d > "${'$'}HOME_DIR/.ssh/authorized_keys"
                    chown -R $user:$user "${'$'}HOME_DIR/.ssh"
                    chmod 700 "${'$'}HOME_DIR/.ssh"
                    chmod 600 "${'$'}HOME_DIR/.ssh/authorized_keys"
                fi
            """.trimIndent()
            val scriptB64 = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $scriptB64 | base64 -d | sh'"
            val res = Shell.cmd(cmd).exec()
            if (res.isSuccess) Result.success(Unit)
            else Result.failure(Exception(res.err.joinToString("\n")))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun installSshServer(context: Context, container: ContainerInfo, password: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val user = container.sshUser.ifBlank { container.defaultUsername.ifBlank { "root" } }
            val assetStream = context.assets.open("ssh-setup.sh")
            val scriptContent = assetStream.bufferedReader().use { it.readText() }.replace("\r\n", "\n").replace("\r", "\n")
            val b64 = android.util.Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val guestPort = if (container.netMode == "nat") 22 else container.sshPort
            val passwordAuth = if (container.sshPasswordAuthEnabled) 1 else 0
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} " +
                "run 'echo $b64 | base64 -d | sh -s -- $user $guestPort $passwordAuth 2>&1'"
            val res = Shell.cmd(cmd).exec()
            if (!res.isSuccess) {
                return@withContext Result.failure(Exception((res.err + res.out).joinToString("\n")))
            }
            if (!password.isNullOrBlank()) {
                val passwordResult = setUserPassword(container.name, user, password)
                if (passwordResult.isFailure) {
                    return@withContext Result.failure(
                        Exception("SSH installed, but password setup failed: ${passwordResult.exceptionOrNull()?.message}")
                    )
                }
            }
            if (container.sshAuthorizedKeys.isNotEmpty()) {
                val keyResult = syncSshAuthorizedKeys(container)
                if (keyResult.isFailure) {
                    return@withContext Result.failure(
                        Exception("SSH installed, but authorized-key setup failed: ${keyResult.exceptionOrNull()?.message}")
                    )
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sshServiceControl(container: ContainerInfo, action: String): Result<Unit> = withContext(Dispatchers.IO) {
        val validActions = setOf("start", "stop", "restart", "status")
        if (action !in validActions) return@withContext Result.failure(IllegalArgumentException("Invalid action: $action"))
        val script = """
            export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${'$'}PATH"
            PIDFILE="/run/sshd.pid"
            [ ! -f "${'$'}PIDFILE" ] && [ -f "/var/run/sshd.pid" ] && PIDFILE="/var/run/sshd.pid"

            stop_all() {
                if command -v systemctl >/dev/null 2>&1 && [ "${'$'}(cat /proc/1/comm 2>/dev/null)" = "systemd" ]; then
                    systemctl stop ssh.service sshd.service ssh.socket sshd.socket 2>/dev/null || true
                fi
                if command -v rc-service >/dev/null 2>&1; then
                    rc-service ssh stop 2>/dev/null || rc-service sshd stop 2>/dev/null || true
                fi
                if [ -f "${'$'}PIDFILE" ]; then
                    pid="${'$'}(cat "${'$'}PIDFILE" 2>/dev/null)"
                    if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then
                        kill -TERM "${'$'}pid" 2>/dev/null || true
                        sleep 0.2
                        kill -9 "${'$'}pid" 2>/dev/null || true
                    fi
                fi
                pkill -x sshd 2>/dev/null || true
                pkill -f '/usr/sbin/sshd' 2>/dev/null || true
                rm -f /run/sshd.pid /var/run/sshd.pid /run/droidspaces-sshd.pid
            }

            start_srv() {
                mkdir -p -m 0755 /run/sshd /var/run/sshd /var/empty
                if [ -f /etc/ssh/sshd_config ]; then
                    /usr/sbin/sshd -t -f /etc/ssh/sshd_config 2>/dev/null || true
                fi
                if command -v systemctl >/dev/null 2>&1 && [ "${'$'}(cat /proc/1/comm 2>/dev/null)" = "systemd" ]; then
                    systemctl start ssh.service 2>/dev/null || systemctl start sshd.service 2>/dev/null || systemctl restart ssh.socket 2>/dev/null || systemctl restart sshd.socket 2>/dev/null || exit 1
                elif command -v rc-service >/dev/null 2>&1; then
                    rc-service ssh start 2>/dev/null || rc-service sshd start 2>/dev/null || exit 1
                else
                    /usr/sbin/sshd -D >/var/log/sshd.log 2>&1 &
                    echo "${'$'}!" > /run/sshd.pid
                    sleep 0.5
                fi
            }

            case "$action" in
                start)
                    stop_all
                    start_srv
                    ;;
                stop)
                    stop_all
                    ;;
                restart)
                    stop_all
                    sleep 0.5
                    start_srv
                    ;;
                status)
                    if command -v systemctl >/dev/null 2>&1 && (systemctl is-active --quiet ssh 2>/dev/null || systemctl is-active --quiet sshd 2>/dev/null); then
                        exit 0
                    elif command -v rc-service >/dev/null 2>&1 && (rc-service ssh status 2>/dev/null | grep -qi running || rc-service sshd status 2>/dev/null | grep -qi running); then
                        exit 0
                    elif pgrep -x sshd >/dev/null 2>&1; then
                        exit 0
                    elif [ -f "${'$'}PIDFILE" ] && kill -0 "${'$'}(cat "${'$'}PIDFILE" 2>/dev/null)" 2>/dev/null; then
                        exit 0
                    else
                        exit 1
                    fi
                    ;;
            esac
        """.trimIndent()
        val b64 = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $b64 | base64 -d | sh'"
        val res = Shell.cmd(cmd).exec()
        if (res.isSuccess) Result.success(Unit)
        else Result.failure(Exception((res.err + res.out).joinToString("\n")))
    }

    suspend fun getSshLogs(containerName: String): String = withContext(Dispatchers.IO) {
        try {
            val script = """
                if command -v journalctl >/dev/null 2>&1; then
                    journalctl -u ssh -u sshd -n 150 --no-pager 2>/dev/null || true
                elif [ -f /var/log/auth.log ]; then
                    tail -n 150 /var/log/auth.log
                elif [ -f /var/log/secure ]; then
                    tail -n 150 /var/log/secure
                elif [ -f /var/log/sshd.log ]; then
                    tail -n 150 /var/log/sshd.log
                elif [ -f /var/log/droidspaces-sshd.log ]; then
                    tail -n 150 /var/log/droidspaces-sshd.log
                else
                    echo "[SSH] Service ready."
                fi
            """.trimIndent()
            val b64 = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run 'echo $b64 | base64 -d | sh'"
            val res = Shell.cmd(cmd).exec()
            if (res.isSuccess && res.out.isNotEmpty()) {
                res.out.joinToString("\n")
            } else {
                "[SSH] Service ready."
            }
        } catch (e: Exception) {
            "[SSH Log Error] ${e.message}"
        }
    }

    suspend fun getSshStatus(context: Context, container: ContainerInfo): SshStatus = withContext(Dispatchers.IO) {
        val lanIp = getPhoneLanIpAddress(context)
        val lanAddress = lanIp?.let { "$it:${container.sshPort}" }

        if (!container.isRunning) {
            val rootfs = File(container.rootfsPath)
            val hasSsh = File(rootfs, "usr/sbin/sshd").exists() ||
                         File(rootfs, "usr/bin/sshd").exists() ||
                         File(rootfs, "etc/ssh/sshd_config").exists() ||
                         File(rootfs, "etc/ssh/sshd_config.d").exists()
            return@withContext SshStatus(
                configured = hasSsh,
                serviceRunning = false,
                port = container.sshPort,
                containerIp = null,
                lanAddress = lanAddress
            )
        }

        val checkScript = """
            export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${'$'}PATH"
            CONFIGURED=0
            if command -v sshd >/dev/null 2>&1 || [ -x /usr/sbin/sshd ] || [ -x /usr/bin/sshd ] || [ -f /etc/ssh/sshd_config ]; then
                CONFIGURED=1
            fi
            RUNNING=0
            if command -v systemctl >/dev/null 2>&1 && (systemctl is-active --quiet ssh 2>/dev/null || systemctl is-active --quiet sshd 2>/dev/null); then
                RUNNING=1
            elif command -v rc-service >/dev/null 2>&1 && (rc-service ssh status 2>/dev/null | grep -qi "running" || rc-service sshd status 2>/dev/null | grep -qi "running"); then
                RUNNING=1
            elif pgrep -x sshd >/dev/null 2>&1 || pgrep -f '/usr/sbin/sshd' >/dev/null 2>&1; then
                RUNNING=1
            elif [ -f /run/sshd.pid ] && kill -0 "${'$'}(cat /run/sshd.pid 2>/dev/null)" 2>/dev/null; then
                RUNNING=1
            elif [ -f /var/run/sshd.pid ] && kill -0 "${'$'}(cat /var/run/sshd.pid 2>/dev/null)" 2>/dev/null; then
                RUNNING=1
            elif [ -f /run/droidspaces-sshd.pid ] && kill -0 "${'$'}(cat /run/droidspaces-sshd.pid 2>/dev/null)" 2>/dev/null; then
                RUNNING=1
            fi
            echo "configured=${'$'}CONFIGURED"
            echo "running=${'$'}RUNNING"
        """.trimIndent()
        val b64 = android.util.Base64.encodeToString(checkScript.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $b64 | base64 -d | sh'"
        val res = Shell.cmd(cmd).exec()
        val map = res.out.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
        }.toMap()

        val configured = map["configured"] == "1"
        val running = map["running"] == "1"

        SshStatus(
            configured = configured,
            serviceRunning = running,
            port = container.sshPort,
            containerIp = null,
            lanAddress = lanAddress
        )
    }

    suspend fun syncSshPasswordAuth(container: ContainerInfo): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val authValue = if (container.sshPasswordAuthEnabled) "yes" else "no"
            val script = """
                CONFIG="/etc/ssh/sshd_config"
                if [ -f "/etc/ssh/sshd_config.d/99-droidspaces.conf" ]; then
                    CONFIG="/etc/ssh/sshd_config.d/99-droidspaces.conf"
                fi
                if [ -f "${'$'}CONFIG" ]; then
                    if grep -q '^[#[:space:]]*PasswordAuthentication ' "${'$'}CONFIG"; then
                        sed -i 's/^[#[:space:]]*PasswordAuthentication .*/PasswordAuthentication $authValue/' "${'$'}CONFIG"
                    else
                        echo 'PasswordAuthentication $authValue' >> "${'$'}CONFIG"
                    fi
                    /usr/sbin/sshd -t 2>/dev/null || true
                    systemctl reload ssh.service 2>/dev/null || systemctl reload sshd.service 2>/dev/null || rc-service ssh reload 2>/dev/null || rc-service sshd reload 2>/dev/null || pkill -HUP -x sshd 2>/dev/null || true
                else
                    exit 1
                fi
            """.trimIndent()
            val b64 = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run 'echo $b64 | base64 -d | sh'"
            Shell.cmd(cmd).exec()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Set or change password for a Linux user inside the container using chpasswd. */
    fun setUserPassword(containerName: String, username: String, password: String): Result<Unit> {
        return try {
            val cleanUser = username.ifBlank { "root" }
            val payload = "$cleanUser:$password"
            val b64 = android.util.Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run 'echo $b64 | base64 -d | chpasswd'"
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess) {
                Result.success(Unit)
            } else {
                val errorMsg = (result.err + result.out).firstOrNull { it.isNotBlank() } ?: "Failed to set password for $cleanUser"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Check if a given user has a password set inside the container. */
    suspend fun hasUserPassword(container: ContainerInfo, username: String): Boolean = withContext(Dispatchers.IO) {
        val cleanUser = username.ifBlank { "root" }
        try {
            if (container.isRunning) {
                val script = "grep '^$cleanUser:' /etc/shadow | cut -d: -f2"
                val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(container.name)} run '$script'"
                val res = Shell.cmd(cmd).exec()
                val hash = res.out.firstOrNull()?.trim() ?: ""
                return@withContext hash.isNotBlank() && hash != "*" && hash != "!" && !hash.startsWith("!")
            } else {
                val shadowFile = java.io.File("${container.rootfsPath}/etc/shadow")
                if (shadowFile.exists()) {
                    val line = Shell.cmd("grep '^$cleanUser:' \"${shadowFile.absolutePath}\" | cut -d: -f2").exec().out.firstOrNull()?.trim() ?: ""
                    return@withContext line.isNotBlank() && line != "*" && line != "!" && !line.startsWith("!")
                }
            }
        } catch (_: Exception) {}
        false
    }

    // ── Docker (docker.md sections 1 & 3) ──────────────────────────────────
    // Real Docker Engine running INSIDE the container, controlled by shelling
    // docker-setup.sh's subcommands through the same `run` mechanism SSH/VNC
    // already use. Unrelated to the droidspaces-socketd bridge (section 2 of
    // docker.md, host-side C code) -- that has no Android-app surface yet.

    data class DockerReadiness(
        val userNs: Boolean,
        val overlayFs: Boolean,
        val cgroupV2: Boolean,
        val cgroupNs: Boolean,
        val verdict: String // "good" | "degraded" | "blocked"
    )

    data class DockerStatus(
        val installed: Boolean,
        val running: Boolean,
        val storageDriver: String?,
        val tcpExposed: Boolean
    )

    data class DockerContainerEntry(
        val id: String,
        val names: String,
        val image: String,
        val status: String,
        val ports: String
    )

    data class DockerImageEntry(
        val repository: String,
        val tag: String,
        val id: String,
        val size: String
    )

    private fun dockerScriptBase64(context: Context): String {
        val content = context.assets.open("docker-setup.sh").bufferedReader().use { it.readText() }
            .replace("\r\n", "\n").replace("\r", "\n")
        return android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    }

    private suspend fun runDockerScript(context: Context, containerName: String, vararg args: String): Shell.Result =
        withContext(Dispatchers.IO) {
            val b64 = dockerScriptBase64(context)
            // Same raw-interpolation convention as the wireless-display scripts:
            // every caller here passes fixed subcommand literals or Ints, never
            // free-form text, so this is safe without per-arg quoting (which
            // wouldn't nest correctly inside the already single-quoted `run`
            // payload anyway).
            val argsStr = args.joinToString(" ")
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} " +
                "run 'echo $b64 | base64 -d | sh -s -- $argsStr 2>&1'"
            Shell.cmd(cmd).exec()
        }

    private fun parseKeyValue(lines: List<String>): Map<String, String> =
        lines.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
        }.toMap()

    /**
     * Kernel-feature readiness for Docker, evaluated together as one verdict
     * instead of the four independent `droidspaces check` lines (see
     * docker.md "Suggested next improvements" #1). Surface this BEFORE
     * offering the install button so a user isn't left with a daemon that
     * won't start.
     */
    suspend fun getDockerReadiness(context: Context, containerName: String): DockerReadiness = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, containerName, "readiness")
        val map = parseKeyValue(res.out)
        DockerReadiness(
            userNs = map["user_ns"] == "1",
            overlayFs = map["overlay_fs"] == "1",
            cgroupV2 = map["cgroup_v2"] == "1",
            cgroupNs = map["cgroup_ns"] == "1",
            verdict = map["verdict"] ?: "blocked"
        )
    }

    /** Idempotent -- docker-setup.sh skips the install step if dockerd is already on PATH. */
    suspend fun installDocker(context: Context, container: ContainerInfo): Result<Unit> = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, container.name, "install")
        if (res.isSuccess) Result.success(Unit)
        else Result.failure(Exception((res.err + res.out).firstOrNull { it.isNotBlank() } ?: "Docker install failed"))
    }

    suspend fun dockerServiceControl(context: Context, container: ContainerInfo, action: String): Result<Unit> = withContext(Dispatchers.IO) {
        val validActions = setOf("start", "stop", "restart")
        if (action !in validActions) return@withContext Result.failure(IllegalArgumentException("Invalid action: $action"))
        val res = runDockerScript(context, container.name, "service", action)
        if (res.isSuccess) Result.success(Unit)
        else Result.failure(Exception((res.err + res.out).firstOrNull { it.isNotBlank() } ?: "Failed to $action docker"))
    }

    suspend fun setDockerStorageDriver(context: Context, container: ContainerInfo, forceVfs: Boolean): Result<ContainerInfo> = withContext(Dispatchers.IO) {
        val driver = if (forceVfs) "vfs" else "overlay2"
        val res = runDockerScript(context, container.name, "set-storage-driver", driver)
        if (!res.isSuccess) {
            return@withContext Result.failure(Exception((res.err + res.out).firstOrNull { it.isNotBlank() } ?: "Failed to set storage driver"))
        }
        val updated = container.copy(dockerForceVfs = forceVfs)
        val saveRes = saveContainerConfig(context, updated)
        if (saveRes.isFailure) return@withContext Result.failure(saveRes.exceptionOrNull()!!)
        Result.success(updated)
    }

    /**
     * Toggle dockerd's plain TCP API alongside the (always-present) unix
     * socket. OFF by default -- see docker-setup.sh's own header comment and
     * the UI's warning text for why this needs to stay LAN-only by
     * convention (same advisory-not-enforced limitation as SSH_LAN_ONLY;
     * Droidspaces' port-forward DNAT has no interface scoping).
     */
    suspend fun setDockerTcpExpose(context: Context, container: ContainerInfo, expose: Boolean): Result<ContainerInfo> = withContext(Dispatchers.IO) {
        val updated = container.copy(dockerExposeTcp = expose)
        // Save first so the port-forward derivation in toConfigContent() picks
        // up the new state before we touch the running daemon.
        val saveRes = saveContainerConfig(context, updated)
        if (saveRes.isFailure) return@withContext Result.failure(saveRes.exceptionOrNull()!!)
        val res = runDockerScript(context, container.name, "set-tcp-expose", if (expose) "1" else "0", container.dockerTcpPort.toString())
        if (!res.isSuccess) {
            return@withContext Result.failure(Exception((res.err + res.out).firstOrNull { it.isNotBlank() } ?: "Failed to update Docker TCP expose"))
        }
        Result.success(updated)
    }

    suspend fun getDockerStatus(context: Context, container: ContainerInfo): DockerStatus = withContext(Dispatchers.IO) {
        if (!container.isRunning) {
            val rootfs = File(container.rootfsPath)
            val hasDocker = File(rootfs, "usr/bin/dockerd").exists() ||
                            File(rootfs, "usr/sbin/dockerd").exists() ||
                            File(rootfs, "usr/local/bin/dockerd").exists() ||
                            File(rootfs, "usr/libexec/docker/dockerd").exists() ||
                            File(rootfs, "usr/bin/docker").exists() ||
                            File(rootfs, "etc/docker/daemon.json").exists()
            var storageDriver: String? = null
            val daemonJson = File(rootfs, "etc/docker/daemon.json")
            if (daemonJson.exists()) {
                try {
                    val text = daemonJson.readText()
                    val match = Regex("\"storage-driver\"\\s*:\\s*\"([^\"]+)\"").find(text)
                    storageDriver = match?.groupValues?.get(1)
                } catch (_: Exception) {}
            }
            return@withContext DockerStatus(
                installed = hasDocker,
                running = false,
                storageDriver = storageDriver,
                tcpExposed = false
            )
        }

        val res = runDockerScript(context, container.name, "status")
        val map = parseKeyValue(res.out)
        DockerStatus(
            installed = map["installed"] == "1",
            running = map["running"] == "1",
            storageDriver = map["storage_driver"]?.takeIf { it.isNotBlank() },
            tcpExposed = map["tcp_exposed"] == "1"
        )
    }

    /** `docker ps -a`, parsed from docker's own `--format '{{json .}}'` JSON-Lines output. */
    suspend fun listDockerContainers(context: Context, container: ContainerInfo): Result<List<DockerContainerEntry>> = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, container.name, "ps")
        if (!res.isSuccess) {
            return@withContext Result.failure(Exception((res.err + res.out).firstOrNull { it.isNotBlank() } ?: "Failed to list Docker containers"))
        }
        try {
            val entries = res.out.mapNotNull { line ->
                if (line.isBlank() || !line.trimStart().startsWith("{")) return@mapNotNull null
                val obj = org.json.JSONObject(line)
                DockerContainerEntry(
                    id = obj.optString("ID"),
                    names = obj.optString("Names"),
                    image = obj.optString("Image"),
                    status = obj.optString("Status"),
                    ports = obj.optString("Ports")
                )
            }
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** `docker images`, parsed from docker's own `--format '{{json .}}'` JSON-Lines output. */
    suspend fun listDockerImages(context: Context, container: ContainerInfo): Result<List<DockerImageEntry>> = withContext(Dispatchers.IO) {
        val res = runDockerScript(context, container.name, "images")
        if (!res.isSuccess) {
            return@withContext Result.failure(Exception((res.err + res.out).firstOrNull { it.isNotBlank() } ?: "Failed to list Docker images"))
        }
        try {
            val entries = res.out.mapNotNull { line ->
                if (line.isBlank() || !line.trimStart().startsWith("{")) return@mapNotNull null
                val obj = org.json.JSONObject(line)
                DockerImageEntry(
                    repository = obj.optString("Repository"),
                    tag = obj.optString("Tag"),
                    id = obj.optString("ID"),
                    size = obj.optString("Size")
                )
            }
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
