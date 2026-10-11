/*
 * Droidspaces notification bridge - logging
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "notify_log.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define NOTIFY_LOG_MAX_BYTES (2 * 1024 * 1024)

static FILE *g_log_fp = NULL;
static char g_log_path[512];
static enum notify_log_level g_min_level = NOTIFY_LOG_INFO;

static const char *level_name(enum notify_log_level level) {
  switch (level) {
  case NOTIFY_LOG_DEBUG:
    return "DEBUG";
  case NOTIFY_LOG_INFO:
    return "INFO";
  case NOTIFY_LOG_WARN:
    return "WARN";
  case NOTIFY_LOG_ERROR:
    return "ERROR";
  }
  return "?";
}

static void rotate_if_needed(void) {
  if (!g_log_path[0])
    return;
  struct stat st;
  if (stat(g_log_path, &st) != 0 || st.st_size < NOTIFY_LOG_MAX_BYTES)
    return;

  char rotated[560];
  snprintf(rotated, sizeof(rotated), "%s.old", g_log_path);
  rename(g_log_path, rotated);

  if (g_log_fp) {
    fclose(g_log_fp);
    g_log_fp = fopen(g_log_path, "a");
  }
}

void notify_log_open(const char *path) {
  if (g_log_fp)
    fclose(g_log_fp);
  g_log_fp = NULL;

  if (!path || !*path)
    return;

  snprintf(g_log_path, sizeof(g_log_path), "%s", path);

  /* Best-effort parent directory creation; ignore failure since the
   * caller may run inside a read-only or minimal rootfs where stderr
   * mirroring (systemd journal) is the only log destination anyway. */
  char dir[512];
  snprintf(dir, sizeof(dir), "%s", g_log_path);
  char *slash = strrchr(dir, '/');
  if (slash) {
    *slash = '\0';
    if (dir[0]) {
      char cmd[600];
      /* mkdir -p equivalent without pulling in mount.c's mkdir_p to keep
       * this daemon link-independent from the rest of the tree. */
      snprintf(cmd, sizeof(cmd), "mkdir -p '%s' 2>/dev/null", dir);
      (void)system(cmd); /* NOLINT: best-effort, path is config-controlled */
    }
  }

  g_log_fp = fopen(g_log_path, "a");
}

void notify_log_close(void) {
  if (g_log_fp) {
    fclose(g_log_fp);
    g_log_fp = NULL;
  }
}

void notify_log_set_level(enum notify_log_level level) { g_min_level = level; }

void notify_log(enum notify_log_level level, const char *fmt, ...) {
  if (level < g_min_level)
    return;

  time_t now = time(NULL);
  struct tm tm_buf;
  localtime_r(&now, &tm_buf);
  char ts[32];
  strftime(ts, sizeof(ts), "%Y-%m-%d %H:%M:%S", &tm_buf);

  char msg[1024];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(msg, sizeof(msg), fmt, ap);
  va_end(ap);

  fprintf(stderr, "[%s] %-5s notify-bridge: %s\n", ts, level_name(level),
          msg);

  rotate_if_needed();
  if (g_log_fp) {
    fprintf(g_log_fp, "[%s] %-5s %s\n", ts, level_name(level), msg);
    fflush(g_log_fp);
  }
}
