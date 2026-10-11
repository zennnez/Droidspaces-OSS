/*
 * Droidspaces notification bridge - configuration
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef DROIDSPACES_NOTIFY_CONFIG_H
#define DROIDSPACES_NOTIFY_CONFIG_H

#include <stdbool.h>

#include "notify_protocol.h"

#define NOTIFY_CFG_MAX_APPS 64
#define NOTIFY_CFG_APP_NAME_MAX 128

/*
 * Default location. Overridable with --config on the CLI or the
 * DROIDSPACES_NOTIFY_CONFIG environment variable so the Android app can
 * point the daemon at a path inside the container's bind-mounted
 * per-app config directory.
 */
#define NOTIFY_CFG_DEFAULT_PATH "/etc/ds/notify-bridge.conf"

struct notify_app_filter {
  char name[NOTIFY_CFG_APP_NAME_MAX];
  bool enabled;
};

struct notify_bridge_config {
  bool bridge_enabled;             /* NOTIFICATION_BRIDGE */
  enum notify_bridge_level level;  /* NOTIFICATION_LEVEL */
  int listen_port;                 /* NOTIFICATION_BRIDGE_PORT, default 8942 */
  char socket_path[512];           /* optional unix socket, in addition to TCP */
  char log_path[512];              /* NOTIFICATION_BRIDGE_LOG */

  /*
   * Per-application allow-list, mirrors the Settings > Linux Notifications
   * > Applications checklist in the Android app. Empty list == allow all
   * (matches the "no config yet" bootstrap case).
   */
  struct notify_app_filter apps[NOTIFY_CFG_MAX_APPS];
  int app_count;

  bool tray_enabled;           /* TRAY_BRIDGE */
  enum tray_bridge_mode tray_mode; /* TRAY_MODE */
};

/* Populates *out with built-in defaults (bridge disabled, level=all). */
void notify_config_defaults(struct notify_bridge_config *out);

/*
 * Loads KEY=VALUE lines from path into *cfg (defaults are applied first).
 * Missing file is not an error - defaults are kept and false is returned
 * so the caller can log a one-line notice without treating it as fatal.
 */
bool notify_config_load(const char *path, struct notify_bridge_config *cfg);

/* Re-reads the same path used by the last successful/attempted load. */
bool notify_config_reload(struct notify_bridge_config *cfg);

/*
 * True if application app_name should be forwarded per the allow-list.
 * Matching is case-insensitive substring match against the D-Bus
 * app_name (e.g. "Firefox" matches "firefox", "Firefox 128.0").
 */
bool notify_config_app_allowed(const struct notify_bridge_config *cfg,
                                const char *app_name);

/* True if urgency passes the configured NOTIFICATION_LEVEL filter. */
bool notify_config_level_allows(const struct notify_bridge_config *cfg,
                                 enum notify_bridge_urgency urgency);

/* True if TRAY_BRIDGE=true and TRAY_MODE calls for forwarding to Android
 * (i.e. mode is "android" or "both" - "xfce" mode means stay idle). */
bool notify_config_tray_forwarding_enabled(
    const struct notify_bridge_config *cfg);

#endif /* DROIDSPACES_NOTIFY_CONFIG_H */
