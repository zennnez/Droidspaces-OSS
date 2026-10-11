package com.droidspaces.app.util

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages container users listing from /etc/passwd.
 * Uses in-memory caching for fast access.
 */
object ContainerUsersManager {
    private const val TAG = "ContainerUsersManager"

    // In-memory cache for users (container name -> List of users)
    private val cache = mutableMapOf<String, List<String>>()

    /**
     * Get list of users from container's /etc/passwd.
     * Returns users with UID between 1000 and 65534 (regular users).
     * Returns cached value if available for instant access.
     */
    suspend fun getUsers(containerName: String, useCache: Boolean = true): List<String> = withContext(Dispatchers.IO) {
        // Return cached value if available and caching is enabled
        if (useCache) {
            cache[containerName]?.let { return@withContext it }
        }

        try {
            val result = Shell.cmd(
                "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run 'awk -F: \"\\\$3 >= 1000 && \\\$3 < 65534 && \\\$1 !~ /^(nixbld)/ {print \\\$1}\" /etc/passwd 2>/dev/null | tr \"\\n\" \",\" | sed \"s/,\\$//\"'"
            ).exec()

            if (!result.isSuccess) return@withContext emptyList()

            // A container with only root yields an empty list. Cache that too, or a
            // stale entry from a previous container of the same name outlives refresh.
            val users = result.out.joinToString("").trim()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            cache[containerName] = users
            users
        } catch (e: Exception) {
            Log.e(TAG, "Error getting users for $containerName", e)
            emptyList()
        }
    }

    /**
     * Get cached users without fetching (returns null if not cached).
     */
    fun getCachedUsers(containerName: String): List<String>? = cache[containerName]

    /**
     * Delete a user and their home directory inside the container.
     */
    suspend fun deleteUser(containerName: String, username: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (username == "root") {
            return@withContext Result.failure(IllegalArgumentException("Cannot delete root user"))
        }
        try {
                val d = '$'
                val script = """
                    pkill -u "$username" 2>/dev/null || true
                    if command -v userdel >/dev/null 2>&1; then
                        userdel -r -f "$username" 2>&1 || userdel "$username" 2>&1
                    elif command -v deluser >/dev/null 2>&1; then
                        deluser --remove-home "$username" 2>&1 || deluser "$username" 2>&1
                    else
                        echo "Neither userdel nor deluser found in container" >&2
                        exit 1
                    fi
                    # Reconfigure desktop services to the next available user
                    nextUser="${d}(awk -F: '${d}3 >= 1000 && ${d}3 < 65534 {print ${d}1; exit}' /etc/passwd 2>/dev/null || echo "")"
                    for srv in /etc/systemd/system/plasma-wayland.service /etc/systemd/system/plasma-mobile.service /etc/systemd/system/plasma-x11.service /etc/systemd/system/niri.service /etc/systemd/system/noctalia.service /etc/systemd/system/xfce-autostart.service /etc/systemd/system/*-autostart.service; do
                        if [ -f "${d}srv" ]; then
                            if [ -n "${d}nextUser" ]; then
                                sed -i "s/^User=.*/User=${d}nextUser/" "${d}srv" 2>/dev/null || true
                            else
                                sed -i "s/^User=.*/User=root/" "${d}srv" 2>/dev/null || true
                            fi
                        fi
                    done
                    # Update SSH config if deleted user was in AllowUsers
                    if [ -f /etc/ssh/sshd_config.d/99-droidspaces.conf ]; then
                        if [ -n "${d}nextUser" ]; then
                            sed -i "s/^AllowUsers .*/AllowUsers ${d}nextUser/" /etc/ssh/sshd_config.d/99-droidspaces.conf 2>/dev/null || true
                        else
                            sed -i '/^AllowUsers /d' /etc/ssh/sshd_config.d/99-droidspaces.conf 2>/dev/null || true
                        fi
                    fi
                """.trimIndent()
            val b64 = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run 'echo $b64 | base64 -d | sh'"
            val res = Shell.cmd(cmd).exec()
            clearCache(containerName)
            if (res.isSuccess) {
                Result.success(Unit)
            } else {
                val err = (res.err + res.out).joinToString("\n").trim()
                Result.failure(Exception(err.ifBlank { "Failed to delete user $username" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clear cache for a specific container or all containers.
     */
    fun clearCache(containerName: String? = null) {
        if (containerName != null) {
            cache.remove(containerName)
        } else {
            cache.clear()
        }
    }
}

