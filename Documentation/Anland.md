<!--
title: Anland (Wayland) Usage Guide
section: Guides
order: 3
desc: Configure Anland Wayland display for Droidspaces Android containers, including backend installation, APK setup, and container options.
keywords: anland, wayland, droidspaces, android, kwin, weston, kde, plasma, gpu, adreno, snapdragon
-->

# Anland (Wayland) Usage Guide

Anland is a high-performance Wayland display path for Droidspaces. It forwards hardware-accelerated GPU buffers directly from container desktop compositors (such as KDE Plasma KWin or Weston) to an Android surface window over a Unix domain socket.

---

## Quick Setup Guide

### Step 1: Install Anland Companion APK
1. Download the **Anland APK** (`app-debug.apk`) from the [latest Anland release](https://github.com/superturtlee/anland/releases/latest).
2. Install the APK on your Android device.

---

### Step 2: Configure Droidspaces Container
In Container Settings:
1. **Enable GPU Access** and **Enable Anland Display**.
2. **Disable Configure Termux:X11** (prevents display variable conflicts).
3. **Disable Configure PulseAudio** (Anland provides its own audio routing).
4. In Security / Privileged Mode, ensure **nocaps** and **noseccomp** are checked.

---

### Step 3: Install Desktop Backend in Container
You can set up the desktop in two simple ways:

#### Option A: One-Click Desktop Installer (Recommended)
1. In Droidspaces **Container Details**, tap **Install Anland KDE**.
2. Alternatively, run this command in the container terminal:
   ```bash
   curl -fLO https://raw.githubusercontent.com/Goldzxcbug/Droidspaces-rootfs-KDE-builder/main/scripts/install-anland-kde.sh && chmod +x install-anland-kde.sh && ./install-anland-kde.sh
   ```

#### Option B: Pre-built Wayland Rootfs
Use the [Droidspaces-rootfs-KDE-builder](https://github.com/Goldzxcbug/Droidspaces-rootfs-KDE-builder) GitHub Actions workflow with `Wayland 支持` enabled to download a ready-to-use KDE Wayland rootfs.

---

### Step 4: Configure Environment & Launch
1. In Container Details, tap **Setup Environment** to populate `/etc/environment` with:
   ```env
   WAYLAND_DISPLAY=wayland-0
   DISPLAY=:0
   QT_QPA_PLATFORM=wayland
   ANLAND=1
   ANLAND_SOCKET=/run/display.sock
   ANLAND_DRM_DEVICE=/dev/dri/renderD128
   ```
2. Tap **Start Plasma Wayland** or run `startplasma-wayland` in the container.

---

## Troubleshooting & Debugging

- **Snapdragon 8 Gen 2 / Adreno 7xx Corruption / Flickering**:
  Add `FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1` to `/etc/environment` (or check the Snapdragon 8 Gen 2 box in the Setup Environment dialog).
- **SELinux Denials**:
  Run in SELinux enforcing by default; if buffer sharing is blocked on custom ROMs, temporarily test in SELinux Permissive mode.
- **Virtual DRM Daemon**:
  If building custom distributions manually, flash `virtual-drm-daemon.zip` as a Magisk / KernelSU / APatch module.

