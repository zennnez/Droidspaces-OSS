/*
 * Droidspaces tray bridge - legacy XEmbed systray detection
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "tray_xembed.h"

#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <stdio.h>
#include <string.h>

void tray_xembed_check(struct tray_xembed_status *out) {
  memset(out, 0, sizeof(*out));

  Display *dpy = XOpenDisplay(NULL);
  if (!dpy) {
    snprintf(out->owner_note, sizeof(out->owner_note),
             "no X11 DISPLAY available (headless container?)");
    return;
  }
  out->x11_available = true;

  int screen = DefaultScreen(dpy);
  char atom_name[32];
  snprintf(atom_name, sizeof(atom_name), "_NET_SYSTEM_TRAY_S%d", screen);
  Atom selection_atom = XInternAtom(dpy, atom_name, False);

  Window owner = XGetSelectionOwner(dpy, selection_atom);
  if (owner != None) {
    out->selection_owned = true;

    /* Best-effort friendly label via WM_CLASS - typically "xfce4-panel"
     * when XFCE's Status Tray plugin owns it. Any failure just leaves a
     * generic note; this is diagnostic-only, nothing depends on it. */
    XClassHint hint;
    if (XGetClassHint(dpy, owner, &hint)) {
      snprintf(out->owner_note, sizeof(out->owner_note),
               "systray already owned by '%s' - legacy XEmbed apps will "
               "dock there natively; Droidspaces will not forward them",
               hint.res_class ? hint.res_class : "unknown");
      if (hint.res_name)
        XFree(hint.res_name);
      if (hint.res_class)
        XFree(hint.res_class);
    } else {
      snprintf(out->owner_note, sizeof(out->owner_note),
               "systray already owned by another window - legacy XEmbed "
               "apps will dock there natively");
    }
  } else {
    snprintf(out->owner_note, sizeof(out->owner_note),
             "no _NET_SYSTEM_TRAY_S%d owner - legacy XEmbed tray apps "
             "have nowhere to dock (not a Droidspaces regression: this "
             "is true with or without the tray bridge running)",
             screen);
  }

  XCloseDisplay(dpy);
}
