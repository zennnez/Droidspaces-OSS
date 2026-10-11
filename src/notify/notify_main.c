/*
 * droidspaces-notifyd - Linux -> Android notification bridge daemon
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Standalone binary rather than a mode of the main `droidspaces` daemon:
 * it runs *inside* the container (alongside XFCE), talks to the
 * container's own session D-Bus, and is started/stopped independently
 * of container lifecycle (e.g. from an XFCE autostart entry or a
 * systemd --user unit), so it is compiled and shipped as its own small
 * daemon.
 *
 * Usage:
 *   droidspaces-notifyd [--config PATH] [--pid-file PATH] [--foreground]
 *
 * Dependencies: libdbus-1 (see the end of the accompanying writeup for
 * the full dependency list and package names per distro).
 */

#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "notify_bridge.h"

static void print_usage(const char *argv0) {
  fprintf(stderr,
          "Usage: %s [--config PATH] [--pid-file PATH] [--foreground]\n"
          "\n"
          "  --config PATH     notify-bridge.conf location "
          "(default: " NOTIFY_CFG_DEFAULT_PATH ")\n"
          "  --pid-file PATH   pidfile location "
          "(default: /run/droidspaces/notify-bridge.pid)\n"
          "  --foreground      stay attached to the controlling terminal "
          "(default when run interactively; daemonizes otherwise)\n",
          argv0);
}

int main(int argc, char **argv) {
  struct notify_bridge_opts opts = {0};
  opts.foreground = isatty(STDIN_FILENO) != 0;

  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "--config") == 0 && i + 1 < argc) {
      opts.config_path = argv[++i];
    } else if (strcmp(argv[i], "--pid-file") == 0 && i + 1 < argc) {
      opts.pid_file = argv[++i];
    } else if (strcmp(argv[i], "--foreground") == 0) {
      opts.foreground = true;
    } else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
      print_usage(argv[0]);
      return 0;
    } else {
      fprintf(stderr, "unknown argument: %s\n\n", argv[i]);
      print_usage(argv[0]);
      return 2;
    }
  }

  if (!opts.foreground) {
    if (daemon(0, 0) != 0) {
      perror("daemon");
      return 1;
    }
  }

  return notify_bridge_run(&opts);
}
