/*
 * Droidspaces v6 - Clipboard Bridge Daemon
 *
 * Bridges the Android clipboard with the Linux container's X11 CLIPBOARD
 * selection. Deliberately creates NO new IPC surface: it runs as just
 * another client on the Termux:X11 display that x11.c already spawns and
 * bind-mounts into the container, and shells out to Termux:API's
 * termux-clipboard-get/-set for the Android side (the same mechanism a
 * Termux user would use interactively). No new socket, no new bind mount,
 * no Android background Service.
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#define _GNU_SOURCE
#include "droidspace.h"
#include <signal.h>
#include <time.h>

/* ---- helpers ------------------------------------------------------------ */

/*
 * Resolve Termux UID and verify xclip + termux-api are installed.
 * Returns the UID on success, -1 on failure (with a warning explaining
 * which piece is missing, mirroring resolve_termux_uid() in x11.c).
 */
static int clip_resolve_termux_uid(void) {
  int uid = ds_resolve_termux_uid();
  if (uid < 0)
    return -1;

  if (access(TX11_XCLIP_BIN, F_OK) != 0) {
    ds_warn("Clipboard bridge: xclip not found at %s. Install it in Termux "
            "(pkg install xclip) to use the clipboard bridge.",
            TX11_XCLIP_BIN);
    return -1;
  }
  if (access(TX11_CLIPBOARD_GET_BIN, F_OK) != 0 ||
      access(TX11_CLIPBOARD_SET_BIN, F_OK) != 0) {
    ds_warn("Clipboard bridge: termux-clipboard-get/-set not found. Install "
            "the Termux:API app and the termux-api Termux package (pkg "
            "install termux-api) to use the clipboard bridge.");
    return -1;
  }
  if (grep_file(TX11_PACKAGES, "com.termux.api ") != 1) {
    ds_warn("Clipboard bridge: Termux:API app not installed - "
            "termux-clipboard-get/-set will fail without it.");
    return -1;
  }
  return uid;
}

/* ---- command execution --------------------------------------------------- */

/*
 * Run `cmd`, capturing up to bufsize-1 bytes of its stdout into buf
 * (NUL-terminated). Returns the byte count on success (>= 0), or -1 if the
 * command couldn't be launched or exited non-zero. A non-zero exit (e.g.
 * xclip finding an empty/owner-less selection) is treated as "no content"
 * by the caller, not a hard error.
 */
static ssize_t run_capture(const char *cmd, char *buf, size_t bufsize) {
  FILE *p = popen(cmd, "r");
  if (!p)
    return -1;
  size_t n = fread(buf, 1, bufsize - 1, p);
  buf[n] = '\0';
  int rc = pclose(p);
  if (rc != 0)
    return -1;
  return (ssize_t)n;
}

/*
 * Run `cmd`, feeding it `len` bytes of `data` on stdin. Returns 0 on
 * success, -1 on failure to launch, a short write, or non-zero exit.
 */
static int run_feed(const char *cmd, const char *data, size_t len) {
  FILE *p = popen(cmd, "w");
  if (!p)
    return -1;
  size_t written = len ? fwrite(data, 1, len, p) : 0;
  int rc = pclose(p);
  if (written != len)
    return -1;
  return rc == 0 ? 0 : -1;
}

/* ---- status reporting ----------------------------------------------------- */

/*
 * Best-effort status file at <pids_dir>/clip.status, read by the Android UI
 * (Clipboard Bridge card: Status / Last sync) the same way it already reads
 * other daemons' PID files. "Connected" here means the last poll of both
 * sides succeeded, not that a sync just happened -- most polls find nothing
 * changed, which is normal.
 */
static void write_status(const char *state, time_t last_sync_epoch) {
  char path[PATH_MAX], buf[128];
  snprintf(path, sizeof(path), "%s/%s", get_pids_dir(),
           DS_CLIPBOARD_STATUS_FILE);
  snprintf(buf, sizeof(buf), "state=%s\nlast_sync=%lld\n", state,
           (long long)last_sync_epoch);
  write_file_atomic(path, buf);
}

/* ---- daemon loop ----------------------------------------------------------- */

struct clipboard_args {
  int uid;
  int sync_interval_ms;
  long max_size_bytes;
};

/* SIGTERM keeps its default action (process exits) -- ds_global_daemon_stop
 * kills the daemon directly, same as X11/PulseAudio. We only need to make
 * sure the status file reflects "stopped" if we get a chance to run before
 * dying, which isn't guaranteed with a bare kill, so the Android side treats
 * a dead PID as authoritative over a stale status file (see
 * ContainerOperationsViewModel's clipboard status check). */

static void clipboard_child_wrapper(int ready_fd, void *user_data) {
  struct clipboard_args *args = (struct clipboard_args *)user_data;

  ds_daemon_child_preamble();

  /* Set 50ms timer slack to coalesce timers in background */
  prctl(PR_SET_TIMERSLACK, 50000000UL, 0, 0, 0);

  setenv("HOME", TX11_HOME, 1);
  setenv("PREFIX", TX11_PREFIX, 1);
  setenv("TMPDIR", TX11_PREFIX "/tmp", 1);
  setenv("DISPLAY", TX11_DISPLAY_STR, 1);

  char ctx[256];
  const char *mls = NULL;
  if (get_selinux_context(TX11_DATA_DIR, ctx, sizeof(ctx)) == 0 ||
      get_selinux_context(TX11_DATA_ALT, ctx, sizeof(ctx)) == 0) {
    mls = ds_extract_mls(ctx);
  }

  mkdir_p(TX11_PREFIX "/tmp", 01777);
  if (chown(TX11_PREFIX "/tmp", (uid_t)args->uid, (gid_t)args->uid) < 0) {
    /* ignore */
  }

  if (ds_drop_privileges(args->uid) < 0) {
    perror("[Clipboard] privilege drop failed");
    if (write(ready_fd, "\x01", 1) < 0) { /* ignore */
    }
    _exit(1);
  }

  if (mls) {
    ds_selinux_dyntransition(mls);
  } else {
    ds_selinux_enter_domain();
  }

  fprintf(stdout, "[Clipboard] uid=%d display=%s interval=%dms max=%ldB\n",
          (int)getuid(), TX11_DISPLAY_STR, args->sync_interval_ms,
          args->max_size_bytes);
  fflush(stdout);

  /* Signal readiness: we don't execv into anything, so there's no
   * O_CLOEXEC-on-success trick available -- close the ready pipe explicitly
   * to tell the parent (ds_spawn_daemon) that setup succeeded. */
  close(ready_fd);

  const size_t bufcap = (size_t)args->max_size_bytes + 1;
  char *android_buf = malloc(bufcap);
  char *linux_buf = malloc(bufcap);
  char *last_android = calloc(1, bufcap);
  char *last_linux = calloc(1, bufcap);
  if (!android_buf || !linux_buf || !last_android || !last_linux) {
    fprintf(stderr, "[Clipboard] out of memory allocating %zuB buffers\n",
            bufcap);
    _exit(1);
  }

  char cmd_get_clip[PATH_MAX], cmd_set_clip[PATH_MAX];
  char cmd_get_x[PATH_MAX], cmd_set_x[PATH_MAX];
  snprintf(cmd_get_clip, sizeof(cmd_get_clip), "%s 2>/dev/null",
           TX11_CLIPBOARD_GET_BIN);
  snprintf(cmd_set_clip, sizeof(cmd_set_clip), "%s >/dev/null 2>&1",
           TX11_CLIPBOARD_SET_BIN);
  snprintf(cmd_get_x, sizeof(cmd_get_x),
           "%s -selection clipboard -o 2>/dev/null", TX11_XCLIP_BIN);
  snprintf(cmd_set_x, sizeof(cmd_set_x),
           "%s -selection clipboard -i >/dev/null 2>&1", TX11_XCLIP_BIN);

  /* Consecutive-failure backoff: transient X/Termux:API hiccups (server
   * restart, momentary permission prompt) shouldn't kill the daemon or spam
   * the log -- only warn once per failure streak, then quietly keep polling
   * at the normal interval (this is the daemon's own crash-recovery; the
   * process itself never exits from a poll failure). */
  int consecutive_failures = 0;
  int consecutive_android_failures = 0;
  time_t last_sync_epoch = 0;
  int idle_ticks = 0;

  for (;;) {
    ssize_t android_n = run_capture(cmd_get_clip, android_buf, bufcap);
    ssize_t linux_n = run_capture(cmd_get_x, linux_buf, bufcap);

    if (android_n < 0) {
      consecutive_android_failures++;
      if (consecutive_android_failures == 3) {
        ds_warn("[Clipboard] Termux:API clipboard access failed (permission denied or Termux:API restricted). Backing off...");
        write_status("android_denied", time(NULL));
      }
      if (consecutive_android_failures >= 3) {
        /* Back off to avoid spamming Termux:API with broken broadcasts */
        int backoff = consecutive_android_failures * 5;
        if (backoff > 60)
          backoff = 60;
        sleep(backoff);
      }
    } else {
      consecutive_android_failures = 0;
    }

    if (android_n < 0 && linux_n < 0) {
      consecutive_failures++;
      if (consecutive_failures == 1 || consecutive_failures % 30 == 0) {
        ds_warn("[Clipboard] both sides unreachable this poll (x%d) -- will "
                "keep retrying",
                consecutive_failures);
        write_status("reconnecting", time(NULL));
      }
      usleep((unsigned int)args->sync_interval_ms * 1000);
      continue;
    }
    consecutive_failures = 0;

    int synced = 0;

    if (android_n >= 0) {
      int is_new = (size_t)android_n != strlen(last_android) ||
                   memcmp(android_buf, last_android, (size_t)android_n) != 0;
      int is_echo = (size_t)android_n == strlen(last_linux) &&
                    memcmp(android_buf, last_linux, (size_t)android_n) == 0;
      if (is_new && !is_echo) {
        if ((size_t)android_n >= bufcap - 1) {
          ds_warn("[Clipboard] Android clipboard exceeds max size (%ldB) -- "
                  "skipping sync",
                  args->max_size_bytes);
        } else if (run_feed(cmd_set_x, android_buf, (size_t)android_n) == 0) {
          memcpy(last_android, android_buf, (size_t)android_n);
          last_android[android_n] = '\0';
          memcpy(last_linux, android_buf, (size_t)android_n);
          last_linux[android_n] = '\0';
          synced = 1;
          ds_log("[Clipboard] Android -> Linux (%zdB)", android_n);
        } else {
          ds_warn("[Clipboard] failed to push Android clipboard into X11 "
                  "CLIPBOARD selection");
        }
      }
    }

    if (!synced && linux_n >= 0) {
      int is_new = (size_t)linux_n != strlen(last_linux) ||
                   memcmp(linux_buf, last_linux, (size_t)linux_n) != 0;
      int is_echo = (size_t)linux_n == strlen(last_android) &&
                    memcmp(linux_buf, last_android, (size_t)linux_n) == 0;
      if (is_new && !is_echo) {
        if ((size_t)linux_n >= bufcap - 1) {
          ds_warn("[Clipboard] Linux clipboard exceeds max size (%ldB) -- "
                  "skipping sync",
                  args->max_size_bytes);
        } else if (run_feed(cmd_set_clip, linux_buf, (size_t)linux_n) == 0) {
          memcpy(last_linux, linux_buf, (size_t)linux_n);
          last_linux[linux_n] = '\0';
          memcpy(last_android, linux_buf, (size_t)linux_n);
          last_android[linux_n] = '\0';
          synced = 1;
          ds_log("[Clipboard] Linux -> Android (%zdB)", linux_n);
        } else {
          ds_warn(
              "[Clipboard] failed to push X11 CLIPBOARD into Android via "
              "termux-clipboard-set");
        }
      }
    }

    /* Only overwrite last_sync on an actual sync -- a no-op poll (the
     * common case) shouldn't erase the timestamp of the last real one. */
    if (synced) {
      last_sync_epoch = time(NULL);
      idle_ticks = 0;
    } else {
      idle_ticks++;
    }
    write_status("connected", last_sync_epoch);

    /* ponytail: adaptive sleep backoff avoids draining battery with constant
     * popen() subshells when clipboard is completely idle */
    unsigned int sleep_ms = (unsigned int)args->sync_interval_ms;
    if (idle_ticks > 60) {
      sleep_ms = 4000; /* idle > 60s: poll every 4s */
    } else if (idle_ticks > 15) {
      sleep_ms = 2500; /* idle > 15s: poll every 2.5s */
    }
    usleep(sleep_ms * 1000);
  }
}

/* ---- spawn --------------------------------------------------------------- */

static pid_t spawn_clipboard(int uid, int sync_interval_ms,
                             long max_size_bytes) {
  static struct clipboard_args args; /* outlives the fork, no free needed */
  args.uid = uid;
  args.sync_interval_ms = sync_interval_ms;
  args.max_size_bytes = max_size_bytes;
  return ds_spawn_daemon(clipboard_child_wrapper, &args, "clipboard.log",
                         "Clipboard", "Clipboard bridge");
}

/* ---- public API ------------------------------------------------------------ */

int ds_clipboard_daemon_start(struct ds_config *cfg) {
  if (!cfg || !cfg->clipboard_bridge || !is_android())
    return -1;
  if (!cfg->x11) {
    ds_warn("Clipboard bridge requires Termux:X11 to be enabled (needs a "
            "live X11 CLIPBOARD selection) -- skipping.");
    return -1;
  }
  if (getuid() != 0) {
    ds_error("[Clipboard] not running as root");
    return -1;
  }

  /* Reuse existing global daemon if still alive -- one clipboard bridge
   * serves every container, same as X11/PulseAudio. */
  pid_t existing = ds_daemon_read_pid(DS_CLIPBOARD_PID_FILE);
  if (existing > 0) {
    ds_log("Clipboard bridge: daemon already running (PID %d)",
           (int)existing);
    cfg->clipboard_pid = existing;
    return 1;
  }

  int uid = clip_resolve_termux_uid();
  if (uid < 0)
    return -1;

  int interval = cfg->clipboard_sync_interval_ms > 0
                     ? cfg->clipboard_sync_interval_ms
                     : DS_CLIPBOARD_DEFAULT_SYNC_INTERVAL_MS;
  if (interval < DS_CLIPBOARD_MIN_SYNC_INTERVAL_MS)
    interval = DS_CLIPBOARD_MIN_SYNC_INTERVAL_MS;
  long max_size = cfg->clipboard_max_size_bytes > 0
                       ? cfg->clipboard_max_size_bytes
                       : DS_CLIPBOARD_DEFAULT_MAX_SIZE_BYTES;

  ds_log("[Clipboard] launching bridge daemon (uid=%d)", uid);
  pid_t child = spawn_clipboard(uid, interval, max_size);
  if (child <= 0)
    return -1;

  cfg->clipboard_pid = child;
  ds_daemon_write_pid(DS_CLIPBOARD_PID_FILE, child);
  return 0;
}

void ds_clipboard_daemon_stop(struct ds_config *cfg) {
  if (!cfg)
    return;
  /* No socket to pass -- the daemon owns no listening socket of its own
   * (it's purely an outbound X11/termux-api client), so ds_global_daemon_stop
   * is given NULL and just skips the unlink step. */
  ds_global_daemon_stop(check_clipboard_needs, cfg->clipboard_pid,
                        &cfg->clipboard_pid, DS_CLIPBOARD_PID_FILE, NULL,
                        "[Clipboard]");
  char status_path[PATH_MAX];
  snprintf(status_path, sizeof(status_path), "%s/%s", get_pids_dir(),
           DS_CLIPBOARD_STATUS_FILE);
  unlink(status_path);
}
