/*
 * Droidspaces tray bridge - dbusmenu client
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tray items that expose a "Menu" property point at an object
 * implementing com.canonical.dbusmenu (the de facto standard menu
 * protocol used by both AppIndicator and most SNI apps - Syncthing
 * Tray, qBittorrent, and typical VPN client trays all use it). This
 * module fetches that menu's real contents on demand rather than
 * fabricating a fixed "pause/quit/settings" action set, so the actions
 * shown on Android are the ones the app actually implements.
 */

#ifndef DROIDSPACES_TRAY_MENU_H
#define DROIDSPACES_TRAY_MENU_H

#include <stdbool.h>
#include <stdint.h>

#include "notify_protocol.h"

struct tray_menu_entry {
  int32_t action_id;   /* dbusmenu item id - pass back to tray_menu_invoke */
  char label[128];
  bool enabled;
  bool checked;    /* only meaningful if toggle_type is set */
  bool is_toggle;
  bool separator;
};

struct tray_menu_result {
  struct tray_menu_entry entries[TRAY_BRIDGE_MAX_MENU_ITEMS];
  int count;
};

/*
 * Fetches the top level of the menu at bus_name/object_path via
 * GetLayout(0, 1, []) (depth=1: immediate children only - deep submenus
 * aren't surfaced today, see the project writeup's limitations section).
 * Returns false on any D-Bus error (menu object gone, timeout, etc.);
 * *out is left zeroed in that case.
 */
bool tray_menu_fetch(struct DBusConnection *conn, const char *bus_name,
                      const char *object_path,
                      struct tray_menu_result *out);

/* Sends a dbusmenu "Event" of type "clicked" for action_id - the
 * standard way to invoke a menu entry per the dbusmenu spec. */
bool tray_menu_invoke(struct DBusConnection *conn, const char *bus_name,
                       const char *object_path, int32_t action_id);

#endif /* DROIDSPACES_TRAY_MENU_H */
