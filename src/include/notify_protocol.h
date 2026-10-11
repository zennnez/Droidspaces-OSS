/*
 * Droidspaces notification bridge - wire protocol
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Linux apps -> XFCE/D-Bus notification daemon -> droidspaces-notifyd
 * -> (this protocol, newline-delimited JSON, over TCP loopback or a
 *    unix socket forwarded into the container) -> Android app
 *
 * The wire format is deliberately simple newline-delimited JSON (NDJSON)
 * rather than the binary struct framing used by socketd_protocol.h: the
 * notification bridge is a low-throughput, human-debuggable side channel
 * (a handful of messages a minute at most), and NDJSON lets the Android
 * side and any `nc`/`socat` based test tool inspect traffic without a
 * matching binary schema on both ends.
 *
 * Every line is a single JSON object with a mandatory "type" field.
 *
 * ---- daemon -> app -------------------------------------------------
 *
 * {"type":"hello","proto_version":1,"daemon_pid":1234,"boot_id":"..."}
 *
 *   Sent once, immediately after connect. "boot_id" changes every time
 *   the notify daemon (re)starts (container restart, daemon crash, or
 *   a fresh `for (;;)` respawn loop) - the app uses it to tell "the
 *   daemon restarted" apart from "the network blipped".
 *
 * {"type":"notify","id":42,"replaces_id":0,"app_name":"Firefox",
 *  "summary":"Download complete","body":"file.zip finished downloading",
 *  "urgency":"normal","icon":"firefox","icon_b64":null,
 *  "timestamp":1737000000}
 *
 *   One per captured D-Bus Notify() call. "id" is the bridge's own
 *   monotonic id (stable for dedup across reconnects); "replaces_id"
 *   is the app-provided replaces_id from the original D-Bus call (0 if
 *   none), used to collapse progress-style notifications instead of
 *   duplicating them. "urgency" is one of "low"|"normal"|"critical".
 *
 * {"type":"close","id":42,"reason":"dismissed|closed|expired"}
 *
 *   The Linux-side notification was closed (by the user, by the app
 *   that sent it, or because it expired). The app should cancel the
 *   matching Android notification if it hasn't already been dismissed
 *   locally (see "dismiss" below - this is how a `notify-send` timeout
 *   or a Linux-side "X" click clears the Android notification).
 *
 * {"type":"ping"}  -- keepalive, app should reply "pong"
 *
 * ---- tray bridge (daemon -> app) --------------------------------------
 *
 * See tray_sni.h for the StatusNotifierItem capture side. Wire messages:
 *
 * {"type":"tray_state","protocol":"sni","watcher_owner":"xfce4-panel",
 *  "notify_fallback":true}
 *
 *   Sent once after "hello" (and again whenever the watcher owner
 *   changes, e.g. XFCE restarting). "watcher_owner" is a best-effort
 *   human label for whoever currently owns the StatusNotifierWatcher
 *   name - normally XFCE's Status Tray panel plugin - or "droidspaces"/
 *   "none". "notify_fallback" mirrors TRAY_MODE=both, telling the app
 *   whether it should also maintain a persistent notification-shade
 *   summary (see requirement: "Integrate with Android notifications
 *   where native tray is impossible").
 *
 * {"type":"tray_item","id":7,"app_id":"syncthing","title":"Syncthing",
 *  "status":"active","icon_name":"syncthing-tray","tooltip":"Syncing 3 folders",
 *  "has_menu":true,"protocol":"sni"}
 *
 *   One per currently-registered tray item; sent as a full dump right
 *   after "tray_state" on every fresh connection (covers "recover after
 *   Android app restart" / "recover after container restart" - the app
 *   never has to remember anything, it just gets told the current truth
 *   every time it (re)connects), and again individually whenever a new
 *   item registers.
 *
 * {"type":"tray_update","id":7,"status":"needs_attention","tooltip":"..."}
 *
 *   Partial update - only changed fields are present besides "id".
 *
 * {"type":"tray_remove","id":7}
 *
 *   The item unregistered (app quit, or the watcher restarted and this
 *   item did not reappear as an SNI object within TRAY_REAPPEAR_GRACE_SEC).
 *
 * {"type":"tray_menu","id":7,"items":[{"action_id":3,"label":"Pause All",
 *  "enabled":true,"checked":false,"separator":false}, ...]}
 *
 *   Reply to a "tray_menu_request". Reflects the app's *real* dbusmenu
 *   contents (parsed one level deep) - not a fabricated fixed action set,
 *   since a fixed "pause/quit/settings" bar would silently do nothing for
 *   apps whose menu doesn't offer those actions.
 *
 * ---- tray bridge (app -> daemon) ---------------------------------------
 *
 * {"type":"tray_activate","id":7}            -- primary click ("open")
 * {"type":"tray_secondary_activate","id":7}  -- secondary click/long-press
 * {"type":"tray_menu_request","id":7}        -- ask for the current menu
 * {"type":"tray_menu_action","id":7,"action_id":3} -- invoke one entry
 *
 * ---- app -> daemon --------------------------------------------------
 *
 * {"type":"hello","proto_version":1,"device":"Pixel-8"}
 *
 * {"type":"dismiss","id":42}
 *
 *   The user dismissed (swiped away) the Android notification. The
 *   daemon calls the D-Bus NotificationClosed signal / CloseNotification
 *   so Linux-side state (e.g. xfce4-notifyd's history) stays in sync.
 *
 * {"type":"click","id":42,"action_key":"default"}
 *
 *   The user tapped the Android notification (or one of its action
 *   buttons). The daemon replays this as a D-Bus ActionInvoked signal
 *   for the originating app, if that app registered actions.
 *
 * {"type":"pong"}
 *
 * ---------------------------------------------------------------------
 * Every message is terminated by a single '\n'. Lines are capped at
 * NOTIFY_BRIDGE_MAX_LINE bytes; longer lines are truncated on the wire
 * (icon payloads should be sent as icon_b64 only when small, otherwise
 * omitted - see notify_bridge.c ICON_INLINE_MAX_BYTES).
 */

#ifndef DROIDSPACES_NOTIFY_PROTOCOL_H
#define DROIDSPACES_NOTIFY_PROTOCOL_H

#define NOTIFY_BRIDGE_PROTO_VERSION 1
#define NOTIFY_BRIDGE_DEFAULT_PORT 8942
#define NOTIFY_BRIDGE_SOCKET_NAME "droidspaces-notify-bridge.sock"
#define NOTIFY_BRIDGE_MAX_LINE 65536

enum notify_bridge_urgency {
  NOTIFY_URGENCY_LOW = 0,
  NOTIFY_URGENCY_NORMAL = 1,
  NOTIFY_URGENCY_CRITICAL = 2,
};

enum notify_bridge_close_reason {
  NOTIFY_CLOSE_EXPIRED = 1,   /* timeout elapsed */
  NOTIFY_CLOSE_DISMISSED = 2, /* user dismissed on the Linux side */
  NOTIFY_CLOSE_BY_SENDER = 3, /* CloseNotification called by the app */
  NOTIFY_CLOSE_UNDEFINED = 4,
};

/* NOTIFICATION_LEVEL config values, in increasing filter strength. */
enum notify_bridge_level {
  NOTIFY_LEVEL_ALL = 0,        /* forward everything */
  NOTIFY_LEVEL_IMPORTANT = 1,  /* normal + critical urgency only */
  NOTIFY_LEVEL_ERRORS_ONLY = 2 /* critical urgency only */
};

/* TRAY_MODE config values. */
enum tray_bridge_mode {
  TRAY_MODE_XFCE = 0,    /* bridge stays idle; native XFCE tray is untouched
                           * and is the only tray the user interacts with */
  TRAY_MODE_ANDROID = 1, /* forward tray state to the Android tray panel */
  TRAY_MODE_BOTH = 2,    /* like ANDROID, plus a persistent notification-
                           * shade summary (requirement: integrate with
                           * Android notifications where no native tray
                           * surface is available, e.g. app fully closed) */
};

enum tray_item_status {
  TRAY_STATUS_PASSIVE = 0,        /* nothing noteworthy - shown dimmed */
  TRAY_STATUS_ACTIVE = 1,         /* normal running state */
  TRAY_STATUS_NEEDS_ATTENTION = 2 /* wants the user's attention now */
};

enum tray_item_protocol {
  TRAY_PROTOCOL_SNI = 0, /* org.kde / org.freedesktop StatusNotifierItem */
  TRAY_PROTOCOL_XEMBED_DETECTED = 1 /* legacy XEmbed tray icon, detected only
                                      * (see tray_xembed.h for why it isn't
                                      * fully forwarded) */
};

#define TRAY_BRIDGE_MAX_MENU_ITEMS 32
#define TRAY_BRIDGE_REAPPEAR_GRACE_SEC 5

#endif /* DROIDSPACES_NOTIFY_PROTOCOL_H */
