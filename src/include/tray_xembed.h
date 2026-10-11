/*
 * Droidspaces tray bridge - legacy XEmbed systray detection
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * XEmbed ("freedesktop System Tray Protocol") is fundamentally different
 * from StatusNotifierItem: it works by *reparenting* the tray app's
 * window into whichever single process currently owns the
 * `_NET_SYSTEM_TRAY_S<screen>` selection. Only that one owner ever
 * receives the icon's window/pixmap - there is no D-Bus-style broadcast
 * a second process can passively observe.
 *
 * That makes a full bridge for legacy XEmbed apps only possible by
 * becoming the tray selection owner ourselves, which would mean
 * reparenting every legacy tray app's window away from XFCE's own
 * Status Tray plugin (xfce4-panel's SystrayBox already implements this
 * exact protocol - see plugins/systray/systray-box.c upstream). Doing
 * that unconditionally would violate "avoid replacing XFCE components
 * unnecessarily" and would break the native panel tray for XEmbed apps
 * whenever our bridge is running.
 *
 * So this module is intentionally detect-only: it checks whether the
 * selection already has an owner (almost always true whenever XFCE's
 * panel/Status Tray plugin is running) and reports that fact for
 * logging/diagnostics. Genuinely forwarding legacy XEmbed icon pixmaps
 * to Android would require either (a) a compile-time opt-in mode where
 * the bridge becomes the tray owner *instead of* XFCE - only sensible
 * for headless/panel-less containers - or (b) XFCE gaining a way to
 * share/relay XEmbed icons, neither of which is implemented here. See
 * the project writeup's Limitations section.
 */

#ifndef DROIDSPACES_TRAY_XEMBED_H
#define DROIDSPACES_TRAY_XEMBED_H

#include <stdbool.h>

struct tray_xembed_status {
  bool x11_available;   /* false if no DISPLAY / X connection failed */
  bool selection_owned;  /* _NET_SYSTEM_TRAY_S<screen> has an owner */
  char owner_note[128]; /* best-effort human-readable note for logs */
};

/* One-shot, synchronous check (opens and closes its own X connection -
 * this is a startup diagnostic, not something polled every tick). */
void tray_xembed_check(struct tray_xembed_status *out);

#endif /* DROIDSPACES_TRAY_XEMBED_H */
