/*
 * Droidspaces notification bridge - D-Bus capture layer
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Captures org.freedesktop.Notifications traffic on the session bus
 * without taking over the well-known name, so libnotify / D-Bus-native
 * apps and the real XFCE notification daemon (xfce4-notifyd) keep
 * working exactly as before - the bridge is a silent observer plus a
 * best-effort replayer for actions coming back from Android.
 */

#ifndef DROIDSPACES_NOTIFY_DBUS_H
#define DROIDSPACES_NOTIFY_DBUS_H

#include <stdbool.h>
#include <stdint.h>

#include "notify_protocol.h"

#define NOTIFY_DBUS_APP_NAME_MAX 128
#define NOTIFY_DBUS_TEXT_MAX 1024
#define NOTIFY_DBUS_ICON_MAX 512

struct notify_dbus_event {
  uint32_t bridge_id;    /* our own monotonic id, stable across reconnects */
  uint32_t dbus_id;       /* the id the notification daemon assigned */
  uint32_t replaces_id;   /* app-requested replaces_id, 0 if none */
  char app_name[NOTIFY_DBUS_APP_NAME_MAX];
  char summary[NOTIFY_DBUS_TEXT_MAX];
  char body[NOTIFY_DBUS_TEXT_MAX];
  char icon[NOTIFY_DBUS_ICON_MAX]; /* icon name or absolute path, may be empty */
  enum notify_bridge_urgency urgency;
  int64_t timestamp; /* unix seconds, capture time */
};

struct notify_dbus_close_event {
  uint32_t dbus_id;
  enum notify_bridge_close_reason reason;
};

/* Callbacks the bridge event loop registers before connecting. */
struct notify_dbus_callbacks {
  void (*on_notify)(const struct notify_dbus_event *ev, void *user_data);
  void (*on_close)(const struct notify_dbus_close_event *ev, void *user_data);
  /* fd readiness changed - bridge must re-poll notify_dbus_fds(). */
  void (*on_watches_changed)(void *user_data);
  void *user_data;
};

/*
 * Up to this many watch fds are tracked simultaneously; libdbus rarely
 * needs more than 1-2 for a single session bus connection.
 */
#define NOTIFY_DBUS_MAX_WATCHES 16

struct notify_dbus_pollfd {
  int fd;
  bool want_read;
  bool want_write;
};

/* Opaque handle; definition lives in notify_dbus.c. */
struct notify_dbus_ctx;

/*
 * Connects to the session bus and installs the eavesdropping match
 * rules. Returns NULL on failure (e.g. no session bus available, which
 * is normal until an X session/xfce4-session has started - the caller
 * is expected to retry with backoff).
 */
struct notify_dbus_ctx *notify_dbus_connect(struct notify_dbus_callbacks cb);

void notify_dbus_disconnect(struct notify_dbus_ctx *ctx);

/* True if the underlying DBusConnection is still open. */
bool notify_dbus_is_connected(struct notify_dbus_ctx *ctx);

/* Fills pollfds with up to NOTIFY_DBUS_MAX_WATCHES entries, returns count. */
int notify_dbus_pollfds(struct notify_dbus_ctx *ctx,
                         struct notify_dbus_pollfd *out);

/* Call after any of ctx's fds become readable/writable, and periodically
 * (every ~1s) to service D-Bus timeouts even with no fd activity. */
void notify_dbus_process(struct notify_dbus_ctx *ctx);

/*
 * Replays a dismissal requested from the Android side back onto the bus
 * by calling CloseNotification on whichever service currently owns
 * org.freedesktop.Notifications (normally xfce4-notifyd). This is a
 * real, spec-compliant call, so it reliably closes the Linux-side
 * popup/history entry.
 */
bool notify_dbus_close_notification(struct notify_dbus_ctx *ctx,
                                     uint32_t dbus_id);

/*
 * Best-effort replay of a click/action-invoked event coming from
 * Android. Emits an ActionInvoked signal for the given notification id.
 * LIMITATION: since the bridge does not own org.freedesktop.Notifications,
 * this signal's sender is the bridge's own unique bus name rather than
 * the real daemon's - most action-consuming apps (which match on
 * interface+member, not sender) still react correctly, but strict
 * consumers that verify the sender will not.
 */
bool notify_dbus_invoke_action(struct notify_dbus_ctx *ctx, uint32_t dbus_id,
                                const char *action_key);

/*
 * Exposes the underlying DBusConnection so other capture modules that
 * share the same daemon process (currently tray_sni.c) can add their own
 * filters/matches without opening a second connection to the session
 * bus. Ownership stays with notify_dbus_ctx; callers must not close or
 * unref it.
 */
struct DBusConnection *notify_dbus_raw_connection(struct notify_dbus_ctx *ctx);

#endif /* DROIDSPACES_NOTIFY_DBUS_H */
