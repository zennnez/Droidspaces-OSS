/*
 * Droidspaces tray bridge - StatusNotifierItem capture layer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "tray_sni.h"

#include <dbus/dbus.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "notify_log.h"

#define SNI_WATCHER_IFACE "org.kde.StatusNotifierWatcher"
#define SNI_ITEM_IFACE "org.kde.StatusNotifierItem"
#define DBUS_PROPS_IFACE "org.freedesktop.DBus.Properties"
#define WATCHER_CALL_TIMEOUT_MS 500

/* Both well-known names apps/watchers may use; interface name on the wire
 * is always "org.kde.StatusNotifierWatcher" regardless of which of these
 * owns it (long-standing convention predating the freedesktop.org alias). */
static const char *const kWatcherBusNames[] = {
    "org.kde.StatusNotifierWatcher",
    "org.freedesktop.StatusNotifierWatcher",
};
#define NUM_WATCHER_NAMES \
  (int)(sizeof(kWatcherBusNames) / sizeof(kWatcherBusNames[0]))

struct tray_sni_ctx {
  DBusConnection *conn;
  struct tray_sni_callbacks cb;
  uint32_t next_bridge_id;

  bool watcher_present;
  char watcher_bus_name[64]; /* which of kWatcherBusNames is currently owned */
  char watcher_owner[64];    /* unique name, e.g. ":1.4" */
  char watcher_label[TRAY_SNI_OWNER_LABEL_MAX];
  time_t last_probe;

  struct tray_sni_item items[TRAY_SNI_MAX_ITEMS];
  int item_count;
};

/* ---- small D-Bus helpers ------------------------------------------------ */

static bool get_name_owner(DBusConnection *conn, const char *name,
                            char *out, size_t out_size) {
  DBusMessage *msg = dbus_message_new_method_call(
      "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
      "GetNameOwner");
  dbus_message_append_args(msg, DBUS_TYPE_STRING, &name, DBUS_TYPE_INVALID);
  DBusError err;
  dbus_error_init(&err);
  DBusMessage *reply = dbus_connection_send_with_reply_and_block(
      conn, msg, WATCHER_CALL_TIMEOUT_MS, &err);
  dbus_message_unref(msg);
  if (!reply) {
    if (dbus_error_is_set(&err))
      dbus_error_free(&err);
    return false;
  }
  const char *owner = NULL;
  bool ok = dbus_message_get_args(reply, NULL, DBUS_TYPE_STRING, &owner,
                                   DBUS_TYPE_INVALID) &&
            owner;
  if (ok)
    snprintf(out, out_size, "%s", owner);
  dbus_message_unref(reply);
  return ok;
}

/* Best-effort "who owns the watcher" label via /proc/<pid>/comm - Linux
 * only, matches the rest of this codebase's platform assumption. */
static void friendly_owner_label(DBusConnection *conn, const char *unique_name,
                                  char *out, size_t out_size) {
  snprintf(out, out_size, "unknown");

  DBusMessage *msg = dbus_message_new_method_call(
      "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
      "GetConnectionUnixProcessID");
  dbus_message_append_args(msg, DBUS_TYPE_STRING, &unique_name,
                            DBUS_TYPE_INVALID);
  DBusError err;
  dbus_error_init(&err);
  DBusMessage *reply = dbus_connection_send_with_reply_and_block(
      conn, msg, WATCHER_CALL_TIMEOUT_MS, &err);
  dbus_message_unref(msg);
  if (!reply) {
    if (dbus_error_is_set(&err))
      dbus_error_free(&err);
    return;
  }
  uint32_t pid = 0;
  if (dbus_message_get_args(reply, NULL, DBUS_TYPE_UINT32, &pid,
                             DBUS_TYPE_INVALID) &&
      pid != 0) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%u/comm", pid);
    FILE *f = fopen(path, "re");
    if (f) {
      char comm[TRAY_SNI_OWNER_LABEL_MAX];
      if (fgets(comm, sizeof(comm), f)) {
        comm[strcspn(comm, "\n")] = '\0';
        if (comm[0])
          snprintf(out, out_size, "%s", comm);
      }
      fclose(f);
    }
  }
  dbus_message_unref(reply);
}

/* Splits a StatusNotifierWatcher registration entry into bus_name and
 * object_path. Entries are either "bus_name/object/path" or just
 * "bus_name" (implied path "/StatusNotifierItem", the de facto default
 * most SNI implementations use). */
static void split_registration(const char *entry, char *bus_name,
                                size_t bus_name_size, char *object_path,
                                size_t object_path_size) {
  const char *slash = strchr(entry, '/');
  if (slash) {
    size_t bn_len = (size_t)(slash - entry);
    if (bn_len >= bus_name_size)
      bn_len = bus_name_size - 1;
    memcpy(bus_name, entry, bn_len);
    bus_name[bn_len] = '\0';
    snprintf(object_path, object_path_size, "%s", slash);
  } else {
    snprintf(bus_name, bus_name_size, "%s", entry);
    snprintf(object_path, object_path_size, "/StatusNotifierItem");
  }
}

static enum tray_item_status parse_status(const char *s) {
  if (!s)
    return TRAY_STATUS_ACTIVE;
  if (strcasecmp(s, "Passive") == 0)
    return TRAY_STATUS_PASSIVE;
  if (strcasecmp(s, "NeedsAttention") == 0)
    return TRAY_STATUS_NEEDS_ATTENTION;
  return TRAY_STATUS_ACTIVE;
}

/* Parses a `Properties.GetAll` reply (a{sv}) into a tray_sni_item, keeping
 * whatever fields were already there (bridge_id/bus_name/object_path) so
 * this can be reused for both "new item" and "refresh existing item". */
static void apply_properties_reply(DBusMessage *reply,
                                    struct tray_sni_item *item) {
  DBusMessageIter iter, dict;
  if (!dbus_message_iter_init(reply, &iter))
    return;
  if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_ARRAY)
    return;
  dbus_message_iter_recurse(&iter, &dict);

  while (dbus_message_iter_get_arg_type(&dict) == DBUS_TYPE_DICT_ENTRY) {
    DBusMessageIter entry;
    dbus_message_iter_recurse(&dict, &entry);

    const char *key = NULL;
    if (dbus_message_iter_get_arg_type(&entry) == DBUS_TYPE_STRING)
      dbus_message_iter_get_basic(&entry, &key);
    dbus_message_iter_next(&entry);

    if (key && dbus_message_iter_get_arg_type(&entry) == DBUS_TYPE_VARIANT) {
      DBusMessageIter variant;
      dbus_message_iter_recurse(&entry, &variant);
      int vtype = dbus_message_iter_get_arg_type(&variant);

      if (strcmp(key, "Id") == 0 && vtype == DBUS_TYPE_STRING) {
        const char *v;
        dbus_message_iter_get_basic(&variant, &v);
        snprintf(item->app_id, sizeof(item->app_id), "%s", v);
      } else if (strcmp(key, "Title") == 0 && vtype == DBUS_TYPE_STRING) {
        const char *v;
        dbus_message_iter_get_basic(&variant, &v);
        snprintf(item->title, sizeof(item->title), "%s", v);
      } else if (strcmp(key, "Status") == 0 && vtype == DBUS_TYPE_STRING) {
        const char *v;
        dbus_message_iter_get_basic(&variant, &v);
        item->status = parse_status(v);
      } else if (strcmp(key, "IconName") == 0 && vtype == DBUS_TYPE_STRING) {
        const char *v;
        dbus_message_iter_get_basic(&variant, &v);
        snprintf(item->icon_name, sizeof(item->icon_name), "%s", v);
      } else if (strcmp(key, "Menu") == 0 && vtype == DBUS_TYPE_OBJECT_PATH) {
        const char *v;
        dbus_message_iter_get_basic(&variant, &v);
        if (v && strcmp(v, "/") != 0 && *v) {
          snprintf(item->menu_object_path, sizeof(item->menu_object_path),
                    "%s", v);
          item->has_menu = true;
        }
      } else if (strcmp(key, "ToolTip") == 0 &&
                 vtype == DBUS_TYPE_STRUCT) {
        /* (icon-name, icon-pixmaps, title, text) - best-effort: take the
         * trailing "text" string field if present, else "title". */
        DBusMessageIter tt;
        dbus_message_iter_recurse(&variant, &tt);
        char title_field[TRAY_SNI_TEXT_MAX] = {0};
        char text_field[TRAY_SNI_TEXT_MAX] = {0};
        int field = 0;
        while (dbus_message_iter_get_arg_type(&tt) != DBUS_TYPE_INVALID) {
          if (field == 2 && dbus_message_iter_get_arg_type(&tt) ==
                                 DBUS_TYPE_STRING) {
            const char *v;
            dbus_message_iter_get_basic(&tt, &v);
            snprintf(title_field, sizeof(title_field), "%s", v);
          } else if (field == 3 && dbus_message_iter_get_arg_type(&tt) ==
                                        DBUS_TYPE_STRING) {
            const char *v;
            dbus_message_iter_get_basic(&tt, &v);
            snprintf(text_field, sizeof(text_field), "%s", v);
          }
          dbus_message_iter_next(&tt);
          field++;
        }
        const char *chosen = text_field[0] ? text_field : title_field;
        if (chosen[0])
          snprintf(item->tooltip, sizeof(item->tooltip), "%s", chosen);
      }
    }
    dbus_message_iter_next(&dict);
  }
}

static bool fetch_item_properties(DBusConnection *conn,
                                   struct tray_sni_item *item) {
  DBusMessage *msg = dbus_message_new_method_call(
      item->bus_name, item->object_path, DBUS_PROPS_IFACE, "GetAll");
  const char *iface = SNI_ITEM_IFACE;
  dbus_message_append_args(msg, DBUS_TYPE_STRING, &iface, DBUS_TYPE_INVALID);

  DBusError err;
  dbus_error_init(&err);
  DBusMessage *reply = dbus_connection_send_with_reply_and_block(
      conn, msg, WATCHER_CALL_TIMEOUT_MS, &err);
  dbus_message_unref(msg);
  if (!reply) {
    if (dbus_error_is_set(&err))
      dbus_error_free(&err);
    return false;
  }
  apply_properties_reply(reply, item);
  dbus_message_unref(reply);

  if (!item->app_id[0])
    snprintf(item->app_id, sizeof(item->app_id), "%s", item->bus_name);
  if (!item->title[0])
    snprintf(item->title, sizeof(item->title), "%s", item->app_id);
  return true;
}

/* ---- item table ---------------------------------------------------- */

static struct tray_sni_item *find_by_key(struct tray_sni_ctx *ctx,
                                          const char *bus_name,
                                          const char *object_path) {
  for (int i = 0; i < ctx->item_count; i++) {
    if (strcmp(ctx->items[i].bus_name, bus_name) == 0 &&
        strcmp(ctx->items[i].object_path, object_path) == 0)
      return &ctx->items[i];
  }
  return NULL;
}

static struct tray_sni_item *find_by_id(struct tray_sni_ctx *ctx,
                                         uint32_t bridge_id) {
  for (int i = 0; i < ctx->item_count; i++)
    if (ctx->items[i].bridge_id == bridge_id)
      return &ctx->items[i];
  return NULL;
}

static void add_per_item_match(DBusConnection *conn, const char *bus_name) {
  char rule[256];
  snprintf(rule, sizeof(rule), "type='signal',sender='%s',interface='%s'",
           bus_name, SNI_ITEM_IFACE);
  DBusError err;
  dbus_error_init(&err);
  dbus_bus_add_match(conn, rule, &err);
  if (dbus_error_is_set(&err))
    dbus_error_free(&err);
}

static void remove_per_item_match(DBusConnection *conn,
                                   const char *bus_name) {
  char rule[256];
  snprintf(rule, sizeof(rule), "type='signal',sender='%s',interface='%s'",
           bus_name, SNI_ITEM_IFACE);
  DBusError err;
  dbus_error_init(&err);
  dbus_bus_remove_match(conn, rule, &err);
  if (dbus_error_is_set(&err))
    dbus_error_free(&err);
}

static void remove_item_at(struct tray_sni_ctx *ctx, int index) {
  remove_per_item_match(ctx->conn, ctx->items[index].bus_name);
  if (ctx->cb.on_item_removed)
    ctx->cb.on_item_removed(ctx->items[index].bridge_id, ctx->cb.user_data);
  memmove(&ctx->items[index], &ctx->items[index + 1],
          (size_t)(ctx->item_count - index - 1) * sizeof(struct tray_sni_item));
  ctx->item_count--;
}

/* Full reconciliation against the watcher's current RegisteredStatusNotifierItems.
 * Used on startup, after any ItemRegistered/ItemUnregistered signal, and
 * after the watcher itself restarts - one code path handles "new item",
 * "item went away", and "recover after XFCE restart" identically. */
static void reconcile_items(struct tray_sni_ctx *ctx) {
  if (!ctx->watcher_present)
    return;

  DBusMessage *msg = dbus_message_new_method_call(
      ctx->watcher_bus_name, "/StatusNotifierWatcher", DBUS_PROPS_IFACE,
      "Get");
  const char *iface = SNI_WATCHER_IFACE;
  const char *prop = "RegisteredStatusNotifierItems";
  dbus_message_append_args(msg, DBUS_TYPE_STRING, &iface, DBUS_TYPE_STRING,
                            &prop, DBUS_TYPE_INVALID);
  DBusError err;
  dbus_error_init(&err);
  DBusMessage *reply = dbus_connection_send_with_reply_and_block(
      ctx->conn, msg, WATCHER_CALL_TIMEOUT_MS, &err);
  dbus_message_unref(msg);
  if (!reply) {
    if (dbus_error_is_set(&err))
      dbus_error_free(&err);
    notify_log(NOTIFY_LOG_WARN,
               "tray: failed to read RegisteredStatusNotifierItems from %s",
               ctx->watcher_bus_name);
    return;
  }

  bool seen[TRAY_SNI_MAX_ITEMS] = {0};

  DBusMessageIter iter, variant, arr;
  if (dbus_message_iter_init(reply, &iter) &&
      dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_VARIANT) {
    dbus_message_iter_recurse(&iter, &variant);
    if (dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_ARRAY) {
      dbus_message_iter_recurse(&variant, &arr);
      while (dbus_message_iter_get_arg_type(&arr) == DBUS_TYPE_STRING) {
        const char *entry;
        dbus_message_iter_get_basic(&arr, &entry);

        char bus_name[64], object_path[256];
        split_registration(entry, bus_name, sizeof(bus_name), object_path,
                            sizeof(object_path));

        struct tray_sni_item *existing = find_by_key(ctx, bus_name, object_path);
        if (existing) {
          fetch_item_properties(ctx->conn, existing);
          if (ctx->cb.on_item_updated)
            ctx->cb.on_item_updated(existing, ctx->cb.user_data);
          for (int i = 0; i < ctx->item_count; i++)
            if (&ctx->items[i] == existing)
              seen[i] = true;
        } else if (ctx->item_count < TRAY_SNI_MAX_ITEMS) {
          struct tray_sni_item *item = &ctx->items[ctx->item_count];
          memset(item, 0, sizeof(*item));
          item->bridge_id = ctx->next_bridge_id++;
          snprintf(item->bus_name, sizeof(item->bus_name), "%s", bus_name);
          snprintf(item->object_path, sizeof(item->object_path), "%s",
                    object_path);
          fetch_item_properties(ctx->conn, item);
          add_per_item_match(ctx->conn, bus_name);
          seen[ctx->item_count] = true;
          ctx->item_count++;
          if (ctx->cb.on_item_added)
            ctx->cb.on_item_added(item, ctx->cb.user_data);
          notify_log(NOTIFY_LOG_INFO, "tray: item registered: %s (%s)",
                     item->app_id, bus_name);
        } else {
          notify_log(NOTIFY_LOG_WARN,
                     "tray: item limit (%d) reached, ignoring %s",
                     TRAY_SNI_MAX_ITEMS, entry);
        }

        dbus_message_iter_next(&arr);
      }
    }
  }
  dbus_message_unref(reply);

  /* Anything tracked but not in the fresh list anymore is gone. Walk
   * backwards so remove_item_at's memmove doesn't skip an entry. */
  for (int i = ctx->item_count - 1; i >= 0; i--) {
    if (!seen[i]) {
      notify_log(NOTIFY_LOG_INFO, "tray: item unregistered: %s",
                 ctx->items[i].app_id);
      remove_item_at(ctx, i);
    }
  }
}

/* ---- watcher discovery ------------------------------------------------ */

static void clear_all_items(struct tray_sni_ctx *ctx) {
  for (int i = ctx->item_count - 1; i >= 0; i--)
    remove_item_at(ctx, i);
}

static void probe_watcher(struct tray_sni_ctx *ctx) {
  ctx->last_probe = time(NULL);

  for (int i = 0; i < NUM_WATCHER_NAMES; i++) {
    char owner[64];
    if (get_name_owner(ctx->conn, kWatcherBusNames[i], owner, sizeof(owner))) {
      bool changed = !ctx->watcher_present ||
                     strcmp(ctx->watcher_owner, owner) != 0;
      ctx->watcher_present = true;
      snprintf(ctx->watcher_bus_name, sizeof(ctx->watcher_bus_name), "%s",
               kWatcherBusNames[i]);
      snprintf(ctx->watcher_owner, sizeof(ctx->watcher_owner), "%s", owner);
      friendly_owner_label(ctx->conn, owner, ctx->watcher_label,
                            sizeof(ctx->watcher_label));

      if (changed) {
        notify_log(NOTIFY_LOG_INFO,
                   "tray: StatusNotifierWatcher found: %s (owner=%s, "
                   "process=%s)",
                   kWatcherBusNames[i], owner, ctx->watcher_label);
        if (ctx->cb.on_watcher_changed)
          ctx->cb.on_watcher_changed(ctx->watcher_label, ctx->cb.user_data);
      }
      reconcile_items(ctx);
      return;
    }
  }

  if (ctx->watcher_present) {
    notify_log(NOTIFY_LOG_WARN,
               "tray: StatusNotifierWatcher disappeared (XFCE panel "
               "restarting?) - tray items will reappear automatically "
               "once it comes back");
    clear_all_items(ctx);
    ctx->watcher_present = false;
    ctx->watcher_bus_name[0] = '\0';
    ctx->watcher_owner[0] = '\0';
    if (ctx->cb.on_watcher_changed)
      ctx->cb.on_watcher_changed("none", ctx->cb.user_data);
  } else {
    /* First-ever probe finding nothing is expected right after container
     * boot (XFCE session still starting) - log once at INFO, not WARN. */
    static bool logged_once = false;
    if (!logged_once) {
      notify_log(NOTIFY_LOG_INFO,
                 "tray: no StatusNotifierWatcher on the session bus yet "
                 "(waiting for XFCE's Status Tray panel plugin, or "
                 "another SNI watcher, to start) - not becoming one "
                 "ourselves so we don't fight XFCE for the name");
      logged_once = true;
    }
  }
}

/* ---- message filter ----------------------------------------------------- */

static DBusHandlerResult filter_cb(DBusConnection *conn, DBusMessage *msg,
                                    void *data) {
  (void)conn;
  struct tray_sni_ctx *ctx = data;

  if (dbus_message_is_signal(msg, "org.freedesktop.DBus",
                              "NameOwnerChanged")) {
    const char *name = NULL, *old_owner = NULL, *new_owner = NULL;
    if (dbus_message_get_args(msg, NULL, DBUS_TYPE_STRING, &name,
                               DBUS_TYPE_STRING, &old_owner, DBUS_TYPE_STRING,
                               &new_owner, DBUS_TYPE_INVALID) &&
        name) {
      for (int i = 0; i < NUM_WATCHER_NAMES; i++) {
        if (strcmp(name, kWatcherBusNames[i]) == 0) {
          probe_watcher(ctx);
          break;
        }
      }
    }
  } else if (dbus_message_is_signal(msg, SNI_WATCHER_IFACE,
                                     "StatusNotifierItemRegistered") ||
             dbus_message_is_signal(msg, SNI_WATCHER_IFACE,
                                     "ItemRegistered") ||
             dbus_message_is_signal(msg, SNI_WATCHER_IFACE,
                                     "StatusNotifierItemUnregistered") ||
             dbus_message_is_signal(msg, SNI_WATCHER_IFACE,
                                     "ItemUnregistered")) {
    /* Full reconcile rather than trusting the signal payload format,
     * which varies across watcher implementations (see notify_dbus.c's
     * NOT_YET_HANDLED discussion for the same "be liberal in what we
     * accept" reasoning). */
    reconcile_items(ctx);
  } else {
    /* Any signal from a currently-tracked item's bus name is treated as
     * "something about this item changed" (NewStatus/NewIcon/NewTitle/
     * NewToolTip all funnel here rather than being parsed individually,
     * since every consumer has to re-Get the property anyway). */
    const char *sender = dbus_message_get_sender(msg);
    if (sender && dbus_message_get_type(msg) == DBUS_MESSAGE_TYPE_SIGNAL) {
      for (int i = 0; i < ctx->item_count; i++) {
        if (strcmp(ctx->items[i].bus_name, sender) == 0) {
          fetch_item_properties(ctx->conn, &ctx->items[i]);
          if (ctx->cb.on_item_updated)
            ctx->cb.on_item_updated(&ctx->items[i], ctx->cb.user_data);
          break;
        }
      }
    }
  }

  return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
}

/* ---- public API ---------------------------------------------------- */

struct tray_sni_ctx *tray_sni_start(struct DBusConnection *conn,
                                     struct tray_sni_callbacks cb) {
  struct tray_sni_ctx *ctx = calloc(1, sizeof(*ctx));
  if (!ctx)
    return NULL;
  ctx->conn = (DBusConnection *)conn;
  ctx->cb = cb;
  ctx->next_bridge_id = 1;

  dbus_connection_add_filter(ctx->conn, filter_cb, ctx, NULL);

  DBusError err;
  dbus_error_init(&err);
  dbus_bus_add_match(ctx->conn,
                      "type='signal',interface='org.freedesktop.DBus',"
                      "member='NameOwnerChanged'",
                      &err);
  if (dbus_error_is_set(&err)) {
    notify_log(NOTIFY_LOG_ERROR, "tray: NameOwnerChanged match failed: %s",
               err.message);
    dbus_error_free(&err);
  }
  dbus_error_init(&err);
  dbus_bus_add_match(
      ctx->conn, "type='signal',interface='" SNI_WATCHER_IFACE "'", &err);
  if (dbus_error_is_set(&err))
    dbus_error_free(&err);

  probe_watcher(ctx);
  return ctx;
}

void tray_sni_stop(struct tray_sni_ctx *ctx) {
  if (!ctx)
    return;
  clear_all_items(ctx);
  if (ctx->conn)
    dbus_connection_remove_filter(ctx->conn, filter_cb, ctx);
  free(ctx);
}

void tray_sni_tick(struct tray_sni_ctx *ctx) {
  if (!ctx || ctx->watcher_present)
    return;
  /* Only relevant while no watcher was found; once one is found,
   * NameOwnerChanged handles updates in real time. Retrying every 5s
   * keeps CPU/D-Bus traffic negligible while still recovering quickly
   * once XFCE (or another watcher) starts. */
  if (time(NULL) - ctx->last_probe >= 5)
    probe_watcher(ctx);
}

bool tray_sni_activate(struct tray_sni_ctx *ctx, uint32_t bridge_id,
                        bool secondary) {
  struct tray_sni_item *item = find_by_id(ctx, bridge_id);
  if (!item)
    return false;

  DBusMessage *msg = dbus_message_new_method_call(
      item->bus_name, item->object_path, SNI_ITEM_IFACE,
      secondary ? "SecondaryActivate" : "Activate");
  int32_t x = 0, y = 0;
  dbus_message_append_args(msg, DBUS_TYPE_INT32, &x, DBUS_TYPE_INT32, &y,
                            DBUS_TYPE_INVALID);
  dbus_message_set_no_reply(msg, TRUE);
  bool ok = dbus_connection_send(ctx->conn, msg, NULL);
  dbus_connection_flush(ctx->conn);
  dbus_message_unref(msg);
  return ok;
}

bool tray_sni_lookup(struct tray_sni_ctx *ctx, uint32_t bridge_id,
                      struct tray_sni_item *out) {
  struct tray_sni_item *item = find_by_id(ctx, bridge_id);
  if (!item)
    return false;
  *out = *item;
  return true;
}

int tray_sni_list(struct tray_sni_ctx *ctx, struct tray_sni_item *out,
                   int max) {
  if (!ctx)
    return 0;
  int n = ctx->item_count < max ? ctx->item_count : max;
  for (int i = 0; i < n; i++)
    out[i] = ctx->items[i];
  return n;
}
