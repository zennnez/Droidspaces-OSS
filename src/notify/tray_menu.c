/*
 * Droidspaces tray bridge - dbusmenu client
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "tray_menu.h"

#include <dbus/dbus.h>
#include <string.h>
#include <time.h>

#include "notify_log.h"

#define DBUSMENU_IFACE "com.canonical.dbusmenu"
#define MENU_CALL_TIMEOUT_MS 500

static void parse_child_properties(DBusMessageIter *props_iter,
                                    struct tray_menu_entry *entry) {
  entry->enabled = true;
  bool visible = true;

  while (dbus_message_iter_get_arg_type(props_iter) == DBUS_TYPE_DICT_ENTRY) {
    DBusMessageIter kv;
    dbus_message_iter_recurse(props_iter, &kv);

    const char *key = NULL;
    if (dbus_message_iter_get_arg_type(&kv) == DBUS_TYPE_STRING)
      dbus_message_iter_get_basic(&kv, &key);
    dbus_message_iter_next(&kv);

    if (key && dbus_message_iter_get_arg_type(&kv) == DBUS_TYPE_VARIANT) {
      DBusMessageIter variant;
      dbus_message_iter_recurse(&kv, &variant);
      int vtype = dbus_message_iter_get_arg_type(&variant);

      if (strcmp(key, "label") == 0 && vtype == DBUS_TYPE_STRING) {
        const char *v;
        dbus_message_iter_get_basic(&variant, &v);
        /* Strip dbusmenu mnemonic underscores ("_Pause" -> "Pause") for a
         * cleaner label on a touch UI, which has no concept of an
         * access-key underline anyway. */
        size_t o = 0;
        for (size_t i = 0; v[i] && o + 1 < sizeof(entry->label); i++) {
          if (v[i] == '_' && v[i + 1] != '_')
            continue;
          entry->label[o++] = v[i];
        }
        entry->label[o] = '\0';
      } else if (strcmp(key, "enabled") == 0 && vtype == DBUS_TYPE_BOOLEAN) {
        dbus_bool_t v;
        dbus_message_iter_get_basic(&variant, &v);
        entry->enabled = v;
      } else if (strcmp(key, "visible") == 0 && vtype == DBUS_TYPE_BOOLEAN) {
        dbus_bool_t v;
        dbus_message_iter_get_basic(&variant, &v);
        visible = v;
      } else if (strcmp(key, "type") == 0 && vtype == DBUS_TYPE_STRING) {
        const char *v;
        dbus_message_iter_get_basic(&variant, &v);
        if (strcmp(v, "separator") == 0)
          entry->separator = true;
      } else if (strcmp(key, "toggle-type") == 0 &&
                 vtype == DBUS_TYPE_STRING) {
        entry->is_toggle = true;
      } else if (strcmp(key, "toggle-state") == 0 &&
                 vtype == DBUS_TYPE_INT32) {
        int32_t v;
        dbus_message_iter_get_basic(&variant, &v);
        entry->checked = (v == 1);
      }
    }
    dbus_message_iter_next(props_iter);
  }

  if (!visible)
    entry->action_id = -1; /* sentinel: caller skips this entry */
}

static void parse_child(DBusMessageIter *child_variant,
                         struct tray_menu_result *out) {
  if (out->count >= TRAY_BRIDGE_MAX_MENU_ITEMS)
    return;

  DBusMessageIter child_struct;
  dbus_message_iter_recurse(child_variant, &child_struct);
  if (dbus_message_iter_get_arg_type(&child_struct) != DBUS_TYPE_INT32)
    return;

  struct tray_menu_entry entry;
  memset(&entry, 0, sizeof(entry));
  dbus_message_iter_get_basic(&child_struct, &entry.action_id);
  dbus_message_iter_next(&child_struct);

  if (dbus_message_iter_get_arg_type(&child_struct) == DBUS_TYPE_ARRAY) {
    DBusMessageIter props;
    dbus_message_iter_recurse(&child_struct, &props);
    parse_child_properties(&props, &entry);
  }

  if (entry.action_id >= 0 && out->count < TRAY_BRIDGE_MAX_MENU_ITEMS)
    out->entries[out->count++] = entry;
}

bool tray_menu_fetch(struct DBusConnection *conn_opaque, const char *bus_name,
                      const char *object_path,
                      struct tray_menu_result *out) {
  memset(out, 0, sizeof(*out));
  DBusConnection *conn = (DBusConnection *)conn_opaque;

  DBusMessage *msg = dbus_message_new_method_call(
      bus_name, object_path, DBUSMENU_IFACE, "GetLayout");
  int32_t parent_id = 0;
  int32_t depth = 1;
  const char *no_props[] = {NULL};
  dbus_message_append_args(msg, DBUS_TYPE_INT32, &parent_id, DBUS_TYPE_INT32,
                            &depth, DBUS_TYPE_INVALID);
  DBusMessageIter iter;
  dbus_message_iter_init_append(msg, &iter);
  DBusMessageIter arr;
  dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "s", &arr);
  dbus_message_iter_close_container(&iter, &arr);
  (void)no_props;

  DBusError err;
  dbus_error_init(&err);
  DBusMessage *reply =
      dbus_connection_send_with_reply_and_block(conn, msg, MENU_CALL_TIMEOUT_MS,
                                                 &err);
  dbus_message_unref(msg);
  if (!reply) {
    if (dbus_error_is_set(&err)) {
      notify_log(NOTIFY_LOG_DEBUG, "tray: GetLayout failed for %s%s: %s",
                 bus_name, object_path, err.message);
      dbus_error_free(&err);
    }
    return false;
  }

  DBusMessageIter reply_iter;
  if (!dbus_message_iter_init(reply, &reply_iter) ||
      dbus_message_iter_get_arg_type(&reply_iter) != DBUS_TYPE_UINT32) {
    dbus_message_unref(reply);
    return false;
  }
  dbus_message_iter_next(&reply_iter); /* skip revision */

  if (dbus_message_iter_get_arg_type(&reply_iter) != DBUS_TYPE_STRUCT) {
    dbus_message_unref(reply);
    return false;
  }
  DBusMessageIter root;
  dbus_message_iter_recurse(&reply_iter, &root);
  /* root: (i id, a{sv} props, av children) - skip id + props, we only
   * want the immediate children. */
  dbus_message_iter_next(&root); /* skip root id */
  dbus_message_iter_next(&root); /* skip root properties */

  if (dbus_message_iter_get_arg_type(&root) == DBUS_TYPE_ARRAY) {
    DBusMessageIter children;
    dbus_message_iter_recurse(&root, &children);
    while (dbus_message_iter_get_arg_type(&children) == DBUS_TYPE_VARIANT) {
      parse_child(&children, out);
      dbus_message_iter_next(&children);
    }
  }

  dbus_message_unref(reply);
  return true;
}

bool tray_menu_invoke(struct DBusConnection *conn_opaque, const char *bus_name,
                       const char *object_path, int32_t action_id) {
  DBusConnection *conn = (DBusConnection *)conn_opaque;

  DBusMessage *msg = dbus_message_new_method_call(
      bus_name, object_path, DBUSMENU_IFACE, "Event");
  const char *event_id = "clicked";
  uint32_t timestamp = (uint32_t)time(NULL);

  DBusMessageIter iter;
  dbus_message_iter_init_append(msg, &iter);
  dbus_message_iter_append_basic(&iter, DBUS_TYPE_INT32, &action_id);
  dbus_message_iter_append_basic(&iter, DBUS_TYPE_STRING, &event_id);

  DBusMessageIter variant;
  dbus_message_iter_open_container(&iter, DBUS_TYPE_VARIANT, "s", &variant);
  const char *empty = "";
  dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &empty);
  dbus_message_iter_close_container(&iter, &variant);

  dbus_message_iter_append_basic(&iter, DBUS_TYPE_UINT32, &timestamp);

  dbus_message_set_no_reply(msg, TRUE);
  bool ok = dbus_connection_send(conn, msg, NULL);
  dbus_connection_flush(conn);
  dbus_message_unref(msg);
  return ok;
}
