/*
 * Droidspaces notification bridge - core daemon
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef DROIDSPACES_NOTIFY_BRIDGE_H
#define DROIDSPACES_NOTIFY_BRIDGE_H

#include "notify_config.h"

struct notify_bridge_opts {
  const char *config_path; /* NOTIFY_CFG_DEFAULT_PATH if NULL */
  const char *pid_file;    /* /run/droidspaces/notify-bridge.pid if NULL */
  bool foreground;         /* stay attached instead of forking */
};

/*
 * Runs the bridge until SIGTERM/SIGINT. Blocks the calling thread.
 * Returns an exit code suitable for main()'s return value.
 */
int notify_bridge_run(const struct notify_bridge_opts *opts);

#endif /* DROIDSPACES_NOTIFY_BRIDGE_H */
