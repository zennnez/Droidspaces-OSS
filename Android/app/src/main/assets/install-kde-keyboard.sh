#!/bin/sh
# Installs KDE Plasma Virtual Keyboard (Maliit Keyboard / Qt Virtual Keyboard / Plasma Keyboard / Onboard)
# and configures autostart / environment for Wayland Anland sessions.

export DEBIAN_FRONTEND=noninteractive

log() { echo "[KDE-KEYBOARD] $1"; }

log "=== Installing KDE Plasma Virtual Keyboard ==="

# Step 1: Repository configuration
log "[1/4] Checking and configuring package repositories..."
if command -v apt-get >/dev/null 2>&1; then
    if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
        sed -i '/^Components:/ { /universe/! s/$/ universe/ }' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
    fi
    if [ -f /etc/apt/sources.list ]; then
        sed -i 's/^\(deb .*\) main$/\1 main universe/' /etc/apt/sources.list 2>/dev/null || true
        sed -i 's/^\(deb .*\) main restricted$/\1 main restricted universe/' /etc/apt/sources.list 2>/dev/null || true
    fi
    if command -v add-apt-repository >/dev/null 2>&1; then
        add-apt-repository -y universe >/dev/null 2>&1 || true
    fi
fi

# Step 2: Updating package index
log "[2/4] Updating package index..."
if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq || true
elif command -v pacman >/dev/null 2>&1; then
    pacman -Sy >/dev/null 2>&1 || true
elif command -v apk >/dev/null 2>&1; then
    apk update >/dev/null 2>&1 || true
fi

# Step 3: Installing keyboard and Wayland integration packages
log "[3/4] Installing virtual keyboard and Wayland integration packages..."
installed=0
if command -v apt-get >/dev/null 2>&1; then
    log "Trying maliit-keyboard, Qt virtual keyboard, portals, and Wayland utilities..."
    apt-get install -y -qq maliit-keyboard maliit-framework qtvirtualkeyboard-plugin xdg-desktop-portal-kde xdg-desktop-portal qt6-wayland wayland-utils 2>/dev/null && installed=1 || true
    if [ "$installed" = "0" ]; then
        log "Trying plasma-keyboard / onboard / matchbox-keyboard fallback..."
        apt-get install -y -qq plasma-keyboard onboard matchbox-keyboard xdg-desktop-portal-kde qt6-wayland wayland-utils 2>/dev/null && installed=1 || \
        apt-get install -y -qq onboard matchbox-keyboard 2>/dev/null && installed=1 || \
        apt-get install -y -qq matchbox-keyboard 2>/dev/null && installed=1 || true
    fi
elif command -v pacman >/dev/null 2>&1; then
    pacman -S --noconfirm --needed maliit-keyboard maliit-framework qt6-virtualkeyboard xdg-desktop-portal-kde qt6-wayland wayland-utils 2>/dev/null && installed=1 || \
    pacman -S --noconfirm --needed matchbox-keyboard 2>/dev/null && installed=1 || true
elif command -v dnf >/dev/null 2>&1; then
    dnf install -y maliit-keyboard maliit-framework qtvirtualkeyboard xdg-desktop-portal-kde qt6-qtwayland wayland-utils onboard matchbox-keyboard 2>/dev/null && installed=1 || true
elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache maliit-keyboard matchbox-keyboard qt6-qtwayland wayland-utils 2>/dev/null && installed=1 || true
fi

# Step 4: Configure environment, KWin, & autostart
log "[4/4] Configuring environment & KWin input-method..."
mkdir -p /etc/profile.d /usr/local/bin /etc/xdg/autostart /etc/skel/.config 2>/dev/null || true

# Update /etc/environment
ENV_FILE="/etc/environment"
if [ -f "$ENV_FILE" ]; then
    grep -q "QT_IM_MODULE=" "$ENV_FILE" || echo "QT_IM_MODULE=maliit" >> "$ENV_FILE"
    grep -q "KWIN_WAYLAND_VIRTUAL_KEYBOARD=" "$ENV_FILE" || echo "KWIN_WAYLAND_VIRTUAL_KEYBOARD=1" >> "$ENV_FILE"
    grep -q "KWIN_IM_SHOW_ALWAYS=" "$ENV_FILE" || echo "KWIN_IM_SHOW_ALWAYS=1" >> "$ENV_FILE"
    grep -q "XMODIFIERS=" "$ENV_FILE" || echo "XMODIFIERS=@im=maliit" >> "$ENV_FILE"
    grep -q "GTK_IM_MODULE=" "$ENV_FILE" || echo "GTK_IM_MODULE=maliit" >> "$ENV_FILE"
fi

cat > /etc/profile.d/kde_virtual_keyboard.sh << 'EOF'
# KDE Plasma Virtual Keyboard Environment
export QT_IM_MODULE=maliit
export KWIN_WAYLAND_VIRTUAL_KEYBOARD=1
export KWIN_IM_SHOW_ALWAYS=1
export XMODIFIERS=@im=maliit
export GTK_IM_MODULE=maliit
EOF
chmod 644 /etc/profile.d/kde_virtual_keyboard.sh 2>/dev/null || true

# Find desktop entry for Maliit / virtual keyboard
MALIIT_DESKTOP=""
for d in /usr/share/applications/org.kde.maliit.desktop /usr/share/applications/maliit-keyboard.desktop /usr/share/applications/org.kde.plasma-keyboard.desktop; do
    if [ -f "$d" ]; then
        MALIIT_DESKTOP="$d"
        break
    fi
done
[ -n "$MALIIT_DESKTOP" ] || MALIIT_DESKTOP="/usr/share/applications/org.kde.maliit.desktop"

# Configure kwinrc for /etc/skel and all existing user homes
configure_kwinrc() {
    target_conf="$1"
    mkdir -p "$(dirname "$target_conf")" 2>/dev/null || true
    touch "$target_conf" 2>/dev/null || true
    if ! grep -q "^\[Wayland\]" "$target_conf" 2>/dev/null; then
        cat >> "$target_conf" << KWINEOF

[Wayland]
VirtualKeyboardEnabled=true
InputMethod[\$e]=$MALIIT_DESKTOP
KWINEOF
    else
        grep -q "VirtualKeyboardEnabled=" "$target_conf" && \
            sed -i 's/^VirtualKeyboardEnabled=.*/VirtualKeyboardEnabled=true/' "$target_conf" || \
            sed -i '/^\[Wayland\]/a VirtualKeyboardEnabled=true' "$target_conf"
        grep -q "InputMethod" "$target_conf" && \
            sed -i "s|^InputMethod.*|InputMethod[\$e]=$MALIIT_DESKTOP|" "$target_conf" || \
            sed -i "/^\[Wayland\]/a InputMethod[\$e]=$MALIIT_DESKTOP" "$target_conf"
    fi
}

configure_kwinrc "/etc/skel/.config/kwinrc"
for h in /home/* /root; do
    if [ -d "$h" ]; then
        u="$(basename "$h")"
        [ "$u" = "root" ] || u="$(awk -F: -v home="$h" '$6 == home { print $1; exit }' /etc/passwd 2>/dev/null || echo "$u")"
        configure_kwinrc "$h/.config/kwinrc"
        chown -R "$u:$u" "$h/.config" 2>/dev/null || true
    fi
done

cat > /usr/local/bin/kde-virtual-keyboard << 'EOF'
#!/bin/sh
if command -v maliit-keyboard >/dev/null 2>&1; then
    exec maliit-keyboard "$@"
elif command -v plasma-keyboard >/dev/null 2>&1; then
    exec plasma-keyboard "$@"
elif command -v onboard >/dev/null 2>&1; then
    exec onboard "$@"
elif command -v matchbox-keyboard >/dev/null 2>&1; then
    exec matchbox-keyboard "$@"
else
    echo "No KDE compatible virtual keyboard found" >&2
    exit 1
fi
EOF
chmod 755 /usr/local/bin/kde-virtual-keyboard 2>/dev/null || true

cat > /etc/xdg/autostart/kde-virtual-keyboard.desktop << 'EOF'
[Desktop Entry]
Type=Application
Name=KDE Virtual Keyboard
Exec=/usr/local/bin/kde-virtual-keyboard
Hidden=false
NoDisplay=false
X-GNOME-Autostart-enabled=true
EOF
chmod 644 /etc/xdg/autostart/kde-virtual-keyboard.desktop 2>/dev/null || true

if command -v maliit-keyboard >/dev/null 2>&1 || command -v plasma-keyboard >/dev/null 2>&1 || command -v onboard >/dev/null 2>&1 || command -v matchbox-keyboard >/dev/null 2>&1; then
    log "✅ KDE Plasma Virtual Keyboard installed and configured successfully!"
else
    log "⚠️ Packages installed but launcher binary not detected; check system packages."
fi
