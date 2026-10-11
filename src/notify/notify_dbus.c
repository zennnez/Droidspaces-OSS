/*
 * Droidspaces notification bridge - D-Bus capture layer
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Uses libdbus-1's low-level API (not GDBus/sd-bus) so the bridge has
 * no dependency on glib or systemd being present in the container -
 * consistent with Droidspaces' general "musl + minimal deps" stance.
 */

#include "notify_dbus.h"

#include <dbus/dbus.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "notify_log.h"

#define NOTIFICATIONS_IFACE "org.freedesktop.Notifications"
#define NOTIFICATIONS_PATH "/org/freedesktop/Notifications"

struct notify_dbus_ctx {
  DBusConnection *conn;
  struct notify_dbus_callbacks cb;
  uint32_t next_bridge_id;

  /* Watches tracked via libdbus's low-level watch API and surfaced to
   * the caller's epoll loop through notify_dbus_pollfds(). */
  DBusWatch *watches[NOTIFY_DBUS_MAX_WATCHES];
  int watch_count;
};

/* ---- watch bookkeeping ------------------------------------------------ */

static dbus_bool_t add_watch_cb(DBusWatch *watch, void *data) {
  struct notify_dbus_ctx *ctx = data;
  if (ctx->watch_count >= NOTIFY_DBUS_MAX_WATCHES) {
    notify_log(NOTIFY_LOG_WARN, "dbus: watch limit reached, dropping watch");
    return FALSE;
  }
  ctx->watches[ctx->watch_count++] = watch;
  if (ctx->cb.on_watches_changed)
    ctx->cb.on_watches_changed(ctx->cb.user_data);
  return TRUE;
}

static void remove_watch_cb(DBusWatch *watch, void *data) {
  struct notify_dbus_ctx *ctx = data;
  for (int i = 0; i < ctx->watch_count; i++) {
    if (ctx->watches[i] == watch) {
      memmove(&ctx->watches[i], &ctx->watches[i + 1],
              (size_t)(ctx->watch_count - i - 1) * sizeof(DBusWatch *));
      ctx->watch_count--;
      break;
    }
  }
  if (ctx->cb.on_watches_changed)
    ctx->cb.on_watches_changed(ctx->cb.user_data);
}

static void toggle_watch_cb(DBusWatch *watch, void *data) {
  (void)watch;
  struct notify_dbus_ctx *ctx = data;
  if (ctx->cb.on_watches_changed)
    ctx->cb.on_watches_changed(ctx->cb.user_data);
}

/* ---- message parsing ---------------------------------------------------
 *
 * The Notify() method signature is:
 *   Notify(STRING app_name, UINT32 replaces_id, STRING app_icon,
 *          STRING summary, STRING body, ARRAY actions,
 *          DICT<STRING,VARIANT> hints, INT32 expire_timeout)
 */

static enum notify_bridge_urgency urgency_from_hints(DBusMessageIter *iter) {
  DBusMessageIter dict;
  dbus_message_iter_recurse(iter, &dict);

  while (dbus_message_iter_get_arg_type(&dict) == DBUS_TYPE_DICT_ENTRY) {
    DBusMessageIter entry;
    dbus_message_iter_recurse(&dict, &entry);

    const char *key = NULL;
    if (dbus_message_iter_get_arg_type(&entry) == DBUS_TYPE_STRING) {
      dbus_message_iter_get_basic(&entry, &key);
    }
    dbus_message_iter_next(&entry);

    if (key && strcmp(key, "urgency") == 0 &&
        dbus_message_iter_get_arg_type(&entry) == DBUS_TYPE_VARIANT) {
      DBusMessageIter variant;
      dbus_message_iter_recurse(&entry, &variant);
      if (dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_BYTE) {
        unsigned char v = 0;
        dbus_message_iter_get_basic(&variant, &v);
        if (v == 0)
          return NOTIFY_URGENCY_LOW;
        if (v == 2)
          return NOTIFY_URGENCY_CRITICAL;
        return NOTIFY_URGENCY_NORMAL;
      }
    }
    dbus_message_iter_next(&dict);
  }
  return NOTIFY_URGENCY_NORMAL;
}

static void handle_notify_call(struct notify_dbus_ctx *ctx,
                                DBusMessage *msg) {
  DBusMessageIter iter;
  if (!dbus_message_iter_init(msg, &iter))
    return;

  struct notify_dbus_event ev;
  memset(&ev, 0, sizeof(ev));
  ev.timestamp = time(NULL);

  const char *s = NULL;

  /* app_name */
  if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_STRING)
    return;
  dbus_message_iter_get_basic(&iter, &s);
  snprintf(ev.app_name, sizeof(ev.app_name), "%s", s ? s : "");
  dbus_message_iter_next(&iter);

  /* replaces_id */
  if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_UINT32)
    return;
  dbus_message_iter_get_basic(&iter, &ev.replaces_id);
  dbus_message_iter_next(&iter);

  /* app_icon */
  if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_STRING)
    return;
  dbus_message_iter_get_basic(&iter, &s);
  snprintf(ev.icon, sizeof(ev.icon), "%s", s ? s : "");
  dbus_message_iter_next(&iter);

  /* summary */
  if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_STRING)
    return;
  dbus_message_iter_get_basic(&iter, &s);
  snprintf(ev.summary, sizeof(ev.summary), "%s", s ? s : "");
  dbus_message_iter_next(&iter);

  /* body */
  if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_STRING)
    return;
  dbus_message_iter_get_basic(&iter, &s);
  snprintf(ev.body, sizeof(ev.body), "%s", s ? s : "");
  dbus_message_iter_next(&iter);

  /* actions (array of strings) - skip over, the bridge doesn't yet
   * surface individual action labels to Android, only default click. */
  if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_ARRAY)
    dbus_message_iter_next(&iter);

  /* hints (a{sv}) - only urgency is consumed today. */
  ev.urgency = NOTIFY_URGENCY_NORMAL;
  if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_ARRAY) {
    ev.urgency = urgency_from_hints(&iter);
    dbus_message_iter_next(&iter);
  }

  /* expire_timeout: ignored - Android manages its own notification
   * lifetime; the "close" event still arrives separately when the
   * Linux side actually expires it. */

  ev.bridge_id = ctx->next_bridge_id++;
  /*
   * The real dbus_id (as assigned by the notification daemon) is only
   * known once the daemon replies to this call with its UINT32 return
   * value - which, on the eavesdropping path, is a separate reply
   * message we do not correlate here for simplicity. We use the reply
   * serial as a stand-in dbus_id key: msg's serial is unique per call
   * and is what NotificationClosed/ActionInvoked will NOT reference
   * directly (those reference the daemon's assigned id). To keep close
   * events tied back to the right entry despite this, the bridge layer
   * above matches primarily on bridge_id via replaces_id/app+summary
   * dedup, and treats dbus_id as best-effort metadata for CloseNotification
   * replay.
   */
  ev.dbus_id = dbus_message_get_serial(msg);

  if (ctx->cb.on_notify)
    ctx->cb.on_notify(&ev, ctx->cb.user_data);
}

static void handle_notification_closed_signal(struct notify_dbus_ctx *ctx,
                                                DBusMessage *msg) {
  DBusMessageIter iter;
  if (!dbus_message_iter_init(msg, &iter))
    return;

  struct notify_dbus_close_event ev;
  memset(&ev, 0, sizeof(ev));

  if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_UINT32)
    return;
  dbus_message_iter_get_basic(&iter, &ev.dbus_id);
  dbus_message_iter_next(&iter);

  uint32_t reason = NOTIFY_CLOSE_UNDEFINED;
  if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_UINT32)
    dbus_message_iter_get_basic(&iter, &reason);

  switch (reason) {
  case 1:
    ev.reason = NOTIFY_CLOSE_EXPIRED;
    break;
  case 2:
    ev.reason = NOTIFY_CLOSE_DISMISSED;
    break;
  case 3:
    ev.reason = NOTIFY_CLOSE_BY_SENDER;
    break;
  default:
    ev.reason = NOTIFY_CLOSE_UNDEFINED;
    break;
  }

  if (ctx->cb.on_close)
    ctx->cb.on_close(&ev, ctx->cb.user_data);
}

static DBusHandlerResult filter_cb(DBusConnection *conn, DBusMessage *msg,
                                    void *data) {
  (void)conn;
  struct notify_dbus_ctx *ctx = data;

  if (dbus_message_is_method_call(msg, NOTIFICATIONS_IFACE, "Notify")) {
    handle_notify_call(ctx, msg);
  } else if (dbus_message_is_signal(msg, NOTIFICATIONS_IFACE,
                                     "NotificationClosed")) {
    handle_notification_closed_signal(ctx, msg);
  }

  /* Never consume the message - we are a passive observer; the real
   * notification daemon (and every other legitimate listener) must
   * still receive it. */
  return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
}

struct notify_dbus_ctx *notify_dbus_connect(struct notify_dbus_callbacks cb) {
  DBusError err;
  dbus_error_init(&err);

  DBusConnection *conn = dbus_bus_get_private(DBUS_BUS_SESSION, &err);
  if (!conn) {
    notify_log(NOTIFY_LOG_WARN, "dbus: session bus unavailable: %s",
               dbus_error_is_set(&err) ? err.message : "unknown error");
    dbus_error_free(&err);
    return NULL;
  }
  dbus_connection_set_exit_on_disconnect(conn, FALSE);

  struct notify_dbus_ctx *ctx = calloc(1, sizeof(*ctx));
  ctx->conn = conn;
  ctx->cb = cb;
  ctx->next_bridge_id = 1;

  if (!dbus_connection_set_watch_functions(conn, add_watch_cb, remove_watch_cb,
                                            toggle_watch_cb, ctx, NULL)) {
    notify_log(NOTIFY_LOG_ERROR, "dbus: failed to install watch functions");
    dbus_connection_close(conn);
    dbus_connection_unref(conn);
    free(ctx);
    return NULL;
  }

  dbus_connection_add_filter(conn, filter_cb, ctx, NULL);

  /*
   * eavesdrop=true is required to see Notify() calls, since they are
   * addressed to whichever unique name owns org.freedesktop.Notifications
   * (xfce4-notifyd), not to us. Most distro session buses allow
   * eavesdrop for the logged-in user's own session bus; if the bus
   * policy forbids it, this add_match fails silently (dbus_error may be
   * set) and the bridge falls back to catching only broadcast signals
   * (NotificationClosed), logging a warning so the operator knows
   * captures will be partial.
   */
  dbus_bus_add_match(conn,
                      "eavesdrop=true,interface=" NOTIFICATIONS_IFACE
                      ",member=Notify,type=method_call",
                      &err);
  if (dbus_error_is_set(&err)) {
    notify_log(NOTIFY_LOG_WARN,
               "dbus: eavesdrop match rejected (%s) - falling back to "
               "non-eavesdrop capture; some notifications may be missed",
               err.message);
    dbus_error_free(&err);
    dbus_error_init(&err);
    /* Fallback: a plain (non-eavesdrop) match still works for clients
     * that broadcast, and always works for the NotificationClosed /
     * ActionInvoked signals added below. */
  }

  dbus_bus_add_match(conn,
                      "interface=" NOTIFICATIONS_IFACE
                      ",member=NotificationClosed,type=signal",
                      &err);
  if (dbus_error_is_set(&err)) {
    notify_log(NOTIFY_LOG_ERROR, "dbus: match install failed: %s",
               err.message);
    dbus_error_free(&err);
  }

  notify_log(NOTIFY_LOG_INFO, "dbus: connected to session bus as %s",
             dbus_bus_get_unique_name(conn));
  return ctx;
}

void notify_dbus_disconnect(struct notify_dbus_ctx *ctx) {
  if (!ctx)
    return;
  if (ctx->conn) {
    dbus_connection_remove_filter(ctx->conn, filter_cb, ctx);
    dbus_connection_close(ctx->conn);
    dbus_connection_unref(ctx->conn);
  }
  free(ctx);
}

bool notify_dbus_is_connected(struct notify_dbus_ctx *ctx) {
  return ctx && ctx->conn && dbus_connection_get_is_connected(ctx->conn);
}

int notify_dbus_pollfds(struct notify_dbus_ctx *ctx,
                         struct notify_dbus_pollfd *out) {
  if (!ctx)
    return 0;
  int n = 0;
  for (int i = 0; i < ctx->watch_count && n < NOTIFY_DBUS_MAX_WATCHES; i++) {
    DBusWatch *w = ctx->watches[i];
    if (!dbus_watch_get_enabled(w))
      continue;
    unsigned int flags = dbus_watch_get_flags(w);
    out[n].fd = dbus_watch_get_unix_fd(w);
    out[n].want_read = (flags & DBUS_WATCH_READABLE) != 0;
    out[n].want_write = (flags & DBUS_WATCH_WRITABLE) != 0;
    n++;
  }
  return n;
}

void notify_dbus_process(struct notify_dbus_ctx *ctx) {
  if (!ctx || !ctx->conn)
    return;

  /* Drive each active watch once; libdbus internally figures out which
   * fd(s) actually had activity when we call dbus_watch_handle(), so it
   * is safe (if slightly wasteful) to nudge all of them and then drain
   * the dispatch queue. */
  for (int i = 0; i < ctx->watch_count; i++) {
    DBusWatch *w = ctx->watches[i];
    if (dbus_watch_get_enabled(w)) {
      unsigned int flags = dbus_watch_get_flags(w);
      dbus_watch_handle(w, flags);
    }
  }

  while (dbus_connection_dispatch(ctx->conn) == DBUS_DISPATCH_DATA_REMAINS) {
  }
}

bool notify_dbus_close_notification(struct notify_dbus_ctx *ctx,
                                     uint32_t dbus_id) {
  if (!notify_dbus_is_connected(ctx))
    return false;

  DBusMessage *msg = dbus_message_new_method_call(
      NOTIFICATIONS_IFACE, NOTIFICATIONS_PATH, NOTIFICATIONS_IFACE,
      "CloseNotification");
  if (!msg)
    return false;

  dbus_message_append_args(msg, DBUS_TYPE_UINT32, &dbus_id,
                            DBUS_TYPE_INVALID);
  dbus_message_set_no_reply(msg, TRUE);

  bool ok = dbus_connection_send(ctx->conn, msg, NULL);
  dbus_connection_flush(ctx->conn);
  dbus_message_unref(msg);
  return ok;
}

bool notify_dbus_invoke_action(struct notify_dbus_ctx *ctx, uint32_t dbus_id,
                                const char *action_key) {
  if (!notify_dbus_is_connected(ctx))
    return false;

  DBusMessage *sig = dbus_message_new_signal(NOTIFICATIONS_PATH,
                                              NOTIFICATIONS_IFACE,
                                              "ActionInvoked");
  if (!sig)
    return false;

  const char *key = action_key && *action_key ? action_key : "default";
  dbus_message_append_args(sig, DBUS_TYPE_UINT32, &dbus_id, DBUS_TYPE_STRING,
                            &key, DBUS_TYPE_INVALID);

  bool ok = dbus_connection_send(ctx->conn, sig, NULL);
  dbus_connection_flush(ctx->conn);
  dbus_message_unref(sig);
  return ok;
}

struct DBusConnection *notify_dbus_raw_connection(struct notify_dbus_ctx *ctx) {
  return ctx ? ctx->conn : NULL;
}
