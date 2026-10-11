/*
 * Droidspaces notification bridge - configuration
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Deliberately hand-rolled KEY=VALUE parsing (no JSON/YAML dependency)
 * so the bridge config file can be edited by hand or dropped in by the
 * Android app's ContainerInstaller with a plain string template, the
 * same way droidspace.conf itself is written.
 */

#include "notify_config.h"

#include <ctype.h>
#include <stdio.h>
#include <string.h>

#include "notify_log.h"

static char g_loaded_path[512];

void notify_config_defaults(struct notify_bridge_config *out) {
  memset(out, 0, sizeof(*out));
  out->bridge_enabled = false;
  out->level = NOTIFY_LEVEL_ALL;
  out->listen_port = NOTIFY_BRIDGE_DEFAULT_PORT;
  snprintf(out->socket_path, sizeof(out->socket_path), "/run/droidspaces/%s",
           NOTIFY_BRIDGE_SOCKET_NAME);
  snprintf(out->log_path, sizeof(out->log_path),
           "/var/log/droidspaces/notify-bridge.log");
  out->app_count = 0;
  out->tray_enabled = false;
  out->tray_mode = TRAY_MODE_ANDROID;
}

static char *trim(char *s) {
  while (*s && isspace((unsigned char)*s))
    s++;
  size_t len = strlen(s);
  while (len > 0 && isspace((unsigned char)s[len - 1])) {
    s[len - 1] = '\0';
    len--;
  }
  return s;
}

static bool parse_bool(const char *v) {
  return strcasecmp(v, "true") == 0 || strcasecmp(v, "1") == 0 ||
         strcasecmp(v, "yes") == 0 || strcasecmp(v, "on") == 0;
}

static enum notify_bridge_level parse_level(const char *v) {
  if (strcasecmp(v, "important") == 0)
    return NOTIFY_LEVEL_IMPORTANT;
  if (strcasecmp(v, "errors-only") == 0 || strcasecmp(v, "errors_only") == 0)
    return NOTIFY_LEVEL_ERRORS_ONLY;
  return NOTIFY_LEVEL_ALL;
}

static enum tray_bridge_mode parse_tray_mode(const char *v) {
  if (strcasecmp(v, "xfce") == 0)
    return TRAY_MODE_XFCE;
  if (strcasecmp(v, "both") == 0)
    return TRAY_MODE_BOTH;
  return TRAY_MODE_ANDROID;
}

/*
 * APPLICATIONS is a comma separated list, each entry optionally prefixed
 * with '-' to mean "explicitly disabled" (used by the Android checklist
 * to persist an unchecked box rather than just omitting the app, so a
 * previously-known app that gets unchecked doesn't quietly fall back to
 * "allow all" when it's the only remaining entry).
 *
 *   APPLICATIONS=Firefox,Terminal,-Build tools
 */
static void parse_applications(struct notify_bridge_config *cfg,
                                const char *value) {
  cfg->app_count = 0;
  char buf[2048];
  snprintf(buf, sizeof(buf), "%s", value);

  char *save = NULL;
  char *tok = strtok_r(buf, ",", &save);
  while (tok && cfg->app_count < NOTIFY_CFG_MAX_APPS) {
    char *entry = trim(tok);
    bool enabled = true;
    if (*entry == '-') {
      enabled = false;
      entry++;
      entry = trim(entry);
    } else if (*entry == '+') {
      entry++;
      entry = trim(entry);
    }
    if (*entry) {
      struct notify_app_filter *f = &cfg->apps[cfg->app_count++];
      snprintf(f->name, sizeof(f->name), "%s", entry);
      f->enabled = enabled;
    }
    tok = strtok_r(NULL, ",", &save);
  }
}

bool notify_config_load(const char *path, struct notify_bridge_config *cfg) {
  notify_config_defaults(cfg);
  snprintf(g_loaded_path, sizeof(g_loaded_path), "%s", path);

  FILE *f = fopen(path, "re");
  if (!f) {
    notify_log(NOTIFY_LOG_WARN,
                "config: %s not found, using defaults (bridge disabled)",
                path);
    return false;
  }

  char line[2048];
  while (fgets(line, sizeof(line), f)) {
    char *l = trim(line);
    if (!*l || *l == '#' || *l == ';')
      continue;

    char *eq = strchr(l, '=');
    if (!eq)
      continue;
    *eq = '\0';
    char *key = trim(l);
    char *value = trim(eq + 1);

    /* Strip optional surrounding quotes. */
    size_t vlen = strlen(value);
    if (vlen >= 2 && ((value[0] == '"' && value[vlen - 1] == '"') ||
                       (value[0] == '\'' && value[vlen - 1] == '\''))) {
      value[vlen - 1] = '\0';
      value++;
    }

    if (strcasecmp(key, "NOTIFICATION_BRIDGE") == 0) {
      cfg->bridge_enabled = parse_bool(value);
    } else if (strcasecmp(key, "NOTIFICATION_LEVEL") == 0) {
      cfg->level = parse_level(value);
    } else if (strcasecmp(key, "NOTIFICATION_BRIDGE_PORT") == 0) {
      int p = atoi(value);
      if (p > 0 && p < 65536)
        cfg->listen_port = p;
    } else if (strcasecmp(key, "NOTIFICATION_BRIDGE_SOCKET") == 0) {
      snprintf(cfg->socket_path, sizeof(cfg->socket_path), "%s", value);
    } else if (strcasecmp(key, "NOTIFICATION_BRIDGE_LOG") == 0) {
      snprintf(cfg->log_path, sizeof(cfg->log_path), "%s", value);
    } else if (strcasecmp(key, "APPLICATIONS") == 0) {
      parse_applications(cfg, value);
    } else if (strcasecmp(key, "TRAY_BRIDGE") == 0) {
      cfg->tray_enabled = parse_bool(value);
    } else if (strcasecmp(key, "TRAY_MODE") == 0) {
      cfg->tray_mode = parse_tray_mode(value);
    }
    /* Unknown keys are ignored, not fatal - keeps the file forward
     * compatible with future Android-side settings additions. */
  }

  fclose(f);
  notify_log(NOTIFY_LOG_INFO,
              "config: loaded from %s (bridge=%s level=%d apps=%d "
              "tray=%s tray_mode=%d)",
              path, cfg->bridge_enabled ? "enabled" : "disabled", cfg->level,
              cfg->app_count, cfg->tray_enabled ? "enabled" : "disabled",
              cfg->tray_mode);
  return true;
}

bool notify_config_reload(struct notify_bridge_config *cfg) {
  if (!g_loaded_path[0]) {
    notify_config_defaults(cfg);
    return false;
  }
  return notify_config_load(g_loaded_path, cfg);
}

bool notify_config_app_allowed(const struct notify_bridge_config *cfg,
                                const char *app_name) {
  if (cfg->app_count == 0)
    return true; /* no explicit list configured yet -> allow all */

  for (int i = 0; i < cfg->app_count; i++) {
    const struct notify_app_filter *f = &cfg->apps[i];
    if (strcasestr(app_name, f->name) != NULL ||
        strcasestr(f->name, app_name) != NULL) {
      return f->enabled;
    }
  }
  /* App not mentioned in the list at all: default to allowed so newly
   * installed Linux apps aren't silently swallowed until the user
   * visits Settings. */
  return true;
}

bool notify_config_level_allows(const struct notify_bridge_config *cfg,
                                 enum notify_bridge_urgency urgency) {
  switch (cfg->level) {
  case NOTIFY_LEVEL_ALL:
    return true;
  case NOTIFY_LEVEL_IMPORTANT:
    return urgency >= NOTIFY_URGENCY_NORMAL;
  case NOTIFY_LEVEL_ERRORS_ONLY:
    return urgency >= NOTIFY_URGENCY_CRITICAL;
  }
  return true;
}

bool notify_config_tray_forwarding_enabled(
    const struct notify_bridge_config *cfg) {
  return cfg->tray_enabled && cfg->tray_mode != TRAY_MODE_XFCE;
}
