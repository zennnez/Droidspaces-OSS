/*
 * Droidspaces notification bridge - logging
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef DROIDSPACES_NOTIFY_LOG_H
#define DROIDSPACES_NOTIFY_LOG_H

enum notify_log_level {
  NOTIFY_LOG_DEBUG = 0,
  NOTIFY_LOG_INFO = 1,
  NOTIFY_LOG_WARN = 2,
  NOTIFY_LOG_ERROR = 3,
};

/* Opens (or reopens, for log rotation) the log file at path. Always also
 * writes to stderr when running attached to a terminal/systemd journal. */
void notify_log_open(const char *path);
void notify_log_close(void);
void notify_log_set_level(enum notify_log_level level);

__attribute__((format(printf, 2, 3))) void
notify_log(enum notify_log_level level, const char *fmt, ...);

#endif /* DROIDSPACES_NOTIFY_LOG_H */
