<!--
title: Clipboard Bridge
section: Guides
order: 3
desc: Bidirectional clipboard sync between Android and the Linux container's X11 CLIPBOARD selection.
keywords: clipboard, sync, droidspaces, termux, x11, xclip, termux-api, android, copy, paste
-->

# Droidspaces Clipboard Bridge Guide

This guide covers the clipboard bridge: syncing copy/paste between Android's clipboard and the Linux container's desktop applications (XFCE, or anything else running on the bridged Termux:X11 display).

> [!IMPORTANT]
>
> **Droidspaces does not ship xclip or Termux:API.** These are upstream Termux packages/apps installed by the setup script. Droidspaces only manages the bridge daemon's lifecycle. If you hit issues specific to `xclip` or `termux-clipboard-get`/`-set` themselves, report them to the respective upstream projects.

### Quick Navigation

- [**How it works**](#how-it-works)
- [**Requirements**](#requirements)
- [**Enabling it**](#enabling)
- [**Configuration**](#configuration)
- [**Selections: PRIMARY vs CLIPBOARD**](#selections)
- [**Security**](#security)
- [**Status & troubleshooting**](#status)
- [**Known limitations**](#limitations)

---

<a id="how-it-works"></a>

## How it works

The bridge adds **no new socket or bind-mount**. It reuses two mechanisms Droidspaces already has:

```
Android ClipboardManager
     |  termux-clipboard-get / termux-clipboard-set  (Termux:API)
     v
droidspaces-clipboardd   <- new global daemon, spawned like the PulseAudio/X11 daemons
     |  xclip -selection clipboard   (same Termux:X11 display already bridged into the container)
     v
X11 server (Termux:X11)
     |  standard X11 CLIPBOARD selection - this is exactly what XFCE apps already use
     v
Linux container XFCE applications
```

The daemon is just another X11 client on the display your XFCE session already uses, and a normal Termux:API caller on the Android side - both are things a user could already do by hand from a Termux shell. There is no separate service inside the container, and no Android background `Service`: the app only reads a small status file the daemon writes.

<a id="requirements"></a>

## Requirements

1. **Termux:X11 must be enabled for the container** (`--termux-x11` / the "Configure Termux:X11" toggle). The bridge needs a live X11 display to attach to; it refuses to start without one.
2. **xclip** and the **termux-api** package, plus the **Termux:API app** ([F-Droid](https://f-droid.org/packages/com.termux.api/) or [GitHub releases](https://github.com/termux/termux-api/releases)) - installed by the setup script:

   ```
   bash scripts/setup-termux.sh
   ```

   `pkg install xclip termux-api` alone is not enough - `termux-clipboard-get`/`-set` talk to Android's clipboard through the **Termux:API app**, not the package.

<a id="enabling"></a>

## Enabling it

**Kotlin app:** toggle **Clipboard Bridge** in the container's config screen (Integration & Hardware section). It's greyed out until Termux:X11 is also enabled.

**CLI:**

```
droidspaces --name=mycontainer --clipboard-bridge --termux-x11 start
```

**container.config** (what the app/CLI write under the hood):

```
enable_termux_x11=1
enable_clipboard_bridge=1
clipboard_sync_interval=1000
clipboard_max_size=262144
```

<a id="configuration"></a>

## Configuration

| container.config key       | CLI flag                       | Container env var       | Default    |
|-----------------------------|---------------------------------|--------------------------|------------|
| `enable_clipboard_bridge`   | `--clipboard-bridge`           | `CLIPBOARD_BRIDGE`       | `false`    |
| `clipboard_sync_interval`   | `--clipboard-sync-interval=MS` | `CLIPBOARD_SYNC_INTERVAL`| `1000` ms (min `250`) |
| `clipboard_max_size`        | `--clipboard-max-size=BYTES`   | `CLIPBOARD_MAX_SIZE`     | `262144` (256 KiB) |

`CLIPBOARD_BRIDGE`/`CLIPBOARD_SYNC_INTERVAL`/`CLIPBOARD_MAX_SIZE` are exported inside the container (both live and via `/etc/profile.d`) the same way `DISPLAY` and `PULSE_SERVER` already are, in case a script inside the container wants to know the bridge is active - they don't configure anything by themselves; the daemon reads its settings from `container.config` on the host side.

The bridge is **polling-based**, not event-driven (neither X11 selection ownership nor Android's clipboard offer a portable, service-free change notification here) - `clipboard_sync_interval` is the trade-off between responsiveness and background CPU/battery use. The default (1s) is a reasonable middle ground; lower it for snappier syncing, raise it to save battery.

<a id="selections"></a>

## Selections: PRIMARY vs CLIPBOARD

Android has exactly one clipboard. X11 has several *selections*; the two relevant ones are:

- **CLIPBOARD** - the "normal" one, set by Ctrl+C / Edit -> Copy. This is what's bridged to/from Android.
- **PRIMARY** - auto-set by merely *selecting* text (used for middle-click paste). Bridging this to Android would sync on every text drag-select, which is far too noisy for a phone clipboard. The daemon does **not** push PRIMARY to Android.

<a id="security"></a>

## Security

- Clipboard content **never leaves the device** - the daemon only talks to the local Termux:X11 socket and the local Termux:API app; there is no network path in the bridge at all.
- **Loop prevention:** each poll compares both sides against the *last value synced in either direction*, so pushing Android's clipboard into X11 doesn't get read back and bounced to Android again next poll (and vice versa).
- **`clipboard_max_size`** caps what gets synced - an oversized clipboard entry on either side is skipped (with a log line) rather than truncated or synced partially.
- Disable at any time by turning the toggle off (or removing `--clipboard-bridge` / setting `enable_clipboard_bridge=0`) and restarting the container.

<a id="status"></a>

## Status & troubleshooting

The Kotlin app shows **Status** (Connected / Reconnecting / Disconnected) and **Last sync** on the container card once the bridge is enabled, read from the daemon's status file - no polling loop of its own on the Android side, it's picked up on the normal container-list refresh.

If it's stuck on Disconnected:

1. Confirm Termux:X11 is actually running and the container's desktop is visible.
2. From a Termux shell: `termux-clipboard-get` (should print the Android clipboard, and may prompt for a Termux:API permission the first time) and `xclip -selection clipboard -o -display :5` (should print the X11 clipboard, or error if nothing has ever been copied).
3. Check `/data/local/Droidspaces/Pids/clipboard.log` for the daemon's own log output.

<a id="limitations"></a>

## Known limitations

- **Text only.** Images and files are not yet bridged - see [Architecture](#how-it-works): both the X11 and Android sides carry MIME-typed clipboard data, so this is a planned extension, not a redesign, but it isn't implemented here.
- **Polling, not push.** A sync can lag by up to `clipboard_sync_interval`.
- **PRIMARY is Linux-local only** (see [above](#selections)) - not a bug, a deliberate choice to avoid selection-spam on Android.
- **No automatic daemon respawn.** If the daemon process itself is killed (not just a transient X11/Termux:API hiccup, which it already retries), it stays down until the container is restarted - same behavior as the existing X11 and PulseAudio daemons today, not a regression specific to this feature.
- **One global daemon serves all containers**, same as X11/PulseAudio - per-container clipboard isolation isn't implemented; if you run multiple containers with the bridge enabled, they all sync to the same Android clipboard.
