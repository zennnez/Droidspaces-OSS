/*
 * Droidspaces tray bridge - StatusNotifierItem capture layer
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Watches whichever process currently owns org.kde.StatusNotifierWatcher
 * (or its org.freedesktop.StatusNotifierWatcher alias) and mirrors its
 * registered items. Deliberately does NOT try to become the watcher
 * itself: on a typical Droidspaces XFCE container, xfce4-panel's Status
 * Tray plugin already implements the watcher (and has since xfce4-panel
 * 4.16, when xfce4-statusnotifier-plugin was merged into it), so owning
 * the name ourselves would fight XFCE for it. If nothing owns the
 * watcher name at all (XFCE not running, panel plugin removed, or
 * xfce4-panel built without libdbusmenu-gtk3 so its watcher never
 * starts), this module simply reports NONE and idles - see the
 * project-level writeup for why "avoid replacing XFCE unnecessarily"
 * rules out a fallback watcher implementation here.
 *
 * Modern AppIndicator-based apps (libappindicator/libayatana-appindicator)
 * fall back to SNI when a StatusNotifierWatcher is present, which covers
 * the large majority of "AppIndicator" tray apps in the wild without any
 * separate ayatana/unity indicator protocol implementation - see the
 * writeup's "Supported protocols" section for the precise scope.
 */

#ifndef DROIDSPACES_TRAY_SNI_H
#define DROIDSPACES_TRAY_SNI_H

#include <stdbool.h>
#include <stdint.h>

#include "notify_protocol.h"

#define TRAY_SNI_MAX_ITEMS 64
#define TRAY_SNI_APP_ID_MAX 128
#define TRAY_SNI_TEXT_MAX 256
#define TRAY_SNI_OWNER_LABEL_MAX 64

struct tray_sni_item {
  uint32_t bridge_id;
  char bus_name[64];      /* unique name owning this item, e.g. ":1.87" */
  char object_path[256];  /* item's own object path */
  char app_id[TRAY_SNI_APP_ID_MAX];
  char title[TRAY_SNI_TEXT_MAX];
  char icon_name[TRAY_SNI_TEXT_MAX];
  char tooltip[TRAY_SNI_TEXT_MAX];
  enum tray_item_status status;
  bool has_menu;
  char menu_object_path[256]; /* valid iff has_menu */
};

struct tray_sni_callbacks {
  void (*on_item_added)(const struct tray_sni_item *item, void *user_data);
  void (*on_item_updated)(const struct tray_sni_item *item, void *user_data);
  void (*on_item_removed)(uint32_t bridge_id, void *user_data);
  /* owner_label is "xfce4-panel" (best-effort friendly name) | "unknown" |
   * "none" when the watcher disappears entirely. */
  void (*on_watcher_changed)(const char *owner_label, void *user_data);
  void *user_data;
};

struct tray_sni_ctx;

/*
 * Attaches to the D-Bus connection owned by notify_dbus_ctx (see
 * notify_dbus_raw_connection) and starts watching for a
 * StatusNotifierWatcher. Returns NULL only on allocation failure -
 * "no watcher present yet" is a normal, non-error state reported via
 * on_watcher_changed("none"), not a failed connect.
 */
struct tray_sni_ctx *tray_sni_start(struct DBusConnection *conn,
                                     struct tray_sni_callbacks cb);

void tray_sni_stop(struct tray_sni_ctx *ctx);

/* Call once a second (piggybacks on the daemon's existing timer tick) to
 * re-check watcher presence when no watcher was found at start/last
 * restart - covers "recover after XFCE restart" without needing a
 * dedicated poll loop. */
void tray_sni_tick(struct tray_sni_ctx *ctx);

/* Replays a primary/secondary click as Activate(0,0)/SecondaryActivate(0,0)
 * on the item's own D-Bus object. */
bool tray_sni_activate(struct tray_sni_ctx *ctx, uint32_t bridge_id,
                        bool secondary);

/* Looks up bus_name/object_path/menu_object_path for a bridge_id, for
 * tray_menu.c to use without duplicating the item table. Returns false
 * if the id isn't currently known. */
bool tray_sni_lookup(struct tray_sni_ctx *ctx, uint32_t bridge_id,
                      struct tray_sni_item *out);

/* Copies up to max currently-known items into out, returns how many.
 * Used to give a freshly-connected Android client (or one recovering
 * from an app/container restart) the full current picture in one go
 * instead of only future deltas. */
int tray_sni_list(struct tray_sni_ctx *ctx, struct tray_sni_item *out,
                   int max);

#endif /* DROIDSPACES_TRAY_SNI_H */
