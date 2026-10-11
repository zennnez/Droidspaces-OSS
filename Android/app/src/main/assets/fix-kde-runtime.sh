#!/bin/sh
# Fix KDE/Niri Wayland Runtime Directory & GPU Device Permissions
set +e

echo "=== Droidspaces Wayland Runtime Fix ==="
echo ""

chmod 1777 /tmp 2>/dev/null || true
mkdir -p -m 1777 /tmp/.X11-unix /tmp/.ICE-unix 2>/dev/null || true

echo "[1/3] Setting up user runtime directories..."
for u in $(awk -F: '$3 >= 1000 && $3 < 65534 {print $1}' /etc/passwd 2>/dev/null); do
    uid_num=$(id -u "$u" 2>/dev/null)
    [ -z "$uid_num" ] && continue
    mkdir -p -m 0755 /run/user 2>/dev/null || true
    mkdir -p -m 0700 "/run/user/$uid_num" "/tmp/runtime-$u" 2>/dev/null || true
    chown -R "$u:$u" "/run/user/$uid_num" "/tmp/runtime-$u" 2>/dev/null || true
    chmod 0700 "/run/user/$uid_num" "/tmp/runtime-$u" 2>/dev/null || true
    # Symlink /tmp/runtime-$u to /run/user/$uid_num so both paths point to the same Wayland socket
    ln -sfn "/run/user/$uid_num" "/tmp/runtime-$u" 2>/dev/null || true
    # Ensure user is in needed graphics and auth groups
    usermod -aG shadow,video,render,audio "$u" 2>/dev/null || true
    # If account was locked via passwd -l, unlock it
    if grep -q "^$u:!" /etc/shadow 2>/dev/null; then
        echo "$u:1234" | chpasswd 2>/dev/null || true
    fi
    echo "  ✓ Configured runtime directory for $u (UID $uid_num)"
done

echo ""
echo "[2/3] Sanitizing /etc/environment..."
ENV_FILE="/etc/environment"
touch "$ENV_FILE" 2>/dev/null || true

# Remove incorrect 'export' prefix lines, stale DISPLAY, and XDG_RUNTIME_DIR from /etc/environment
sed -i 's/^[[:space:]]*export[[:space:]]\+//g' "$ENV_FILE" 2>/dev/null || true
sed -i '/^export /d' "$ENV_FILE" 2>/dev/null || true
sed -i '/^XDG_RUNTIME_DIR=/d' "$ENV_FILE" 2>/dev/null || true
sed -i '/^DISPLAY=/d' "$ENV_FILE" 2>/dev/null || true
sed -i '/^KWIN_DRM_USE_MODIFIERS=/d' "$ENV_FILE" 2>/dev/null || true
echo "  ✓ Cleaned /etc/environment"

# Ensure standard Anland & Mesa variables exist
for var in "XCURSOR_SIZE=48" "WAYLAND_DISPLAY=wayland-0" "QT_QPA_PLATFORM=wayland" "QT_AUTO_SCREEN_SCALE_FACTOR=1" "GDK_BACKEND=wayland,x11" "CLUTTER_BACKEND=wayland" "SDL_VIDEODRIVER=wayland" "XDG_SESSION_TYPE=wayland" "MOZ_ENABLE_WAYLAND=1" "ANLAND=1" "ANLAND_SOCKET=/run/display.sock" "ANLAND_DRM_DEVICE=/dev/dri/renderD128" "XWAYLAND_GBM_DEVICE=/dev/dri/renderD128" "MESA_LOADER_DRIVER_OVERRIDE=kgsl" "GALLIUM_DRIVER=kgsl" "FD_FORCE_KGSL=1"; do
    key=$(echo "$var" | cut -d= -f1)
    if ! grep -q "^$key=" "$ENV_FILE" 2>/dev/null; then
        echo "$var" >> "$ENV_FILE"
        echo "  ✓ Added $var"
    fi
done

echo ""
echo "[3/3] Loosening GPU and display socket device permissions..."
for dev in /dev/kgsl-3d0 /dev/dri/card* /dev/dri/renderD* /run/display.sock; do
    [ -e "$dev" ] && chmod 0666 "$dev" 2>/dev/null && echo "  ✓ $dev -> 0666"
done

echo ""
echo "==================================================="
echo "  Wayland Runtime Fix Complete!"
echo "==================================================="
