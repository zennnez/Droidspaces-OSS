#!/bin/sh
# Installs vboard (wvkbd / squeekboard / touch on-screen keyboard for Wayland)
# and configures launcher & layer-shell autostart for Anland sessions.

export DEBIAN_FRONTEND=noninteractive

log() { echo "[VBOARD] $1"; }

log "=== Installing vboard Virtual Keyboard for Wayland ==="

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

# Step 3: Installing keyboard packages
log "[3/4] Installing vboard packages..."
installed=0
if command -v apt-get >/dev/null 2>&1; then
    log "Trying wvkbd & squeekboard..."
    apt-get install -y -qq wvkbd squeekboard 2>/dev/null && installed=1 || \
    apt-get install -y -qq squeekboard 2>/dev/null && installed=1 || true
    if [ "$installed" = "0" ]; then
        log "Trying onboard / matchbox-keyboard fallback..."
        apt-get install -y -qq onboard matchbox-keyboard 2>/dev/null && installed=1 || \
        apt-get install -y -qq matchbox-keyboard 2>/dev/null && installed=1 || true
    fi
elif command -v pacman >/dev/null 2>&1; then
    pacman -S --noconfirm wvkbd squeekboard matchbox-keyboard 2>/dev/null && installed=1 || true
elif command -v dnf >/dev/null 2>&1; then
    dnf install -y squeekboard onboard matchbox-keyboard 2>/dev/null && installed=1 || true
elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache wvkbd matchbox-keyboard 2>/dev/null && installed=1 || true
fi

# Step 4: Configure dedicated launcher and autostart
log "[4/4] Configuring dedicated launcher & autostart..."
mkdir -p /usr/local/bin /etc/xdg/autostart 2>/dev/null || true

cat > /usr/local/bin/vboard << 'EOF'
#!/bin/sh
# vboard launcher for Wayland / Xwayland
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
export DISPLAY="${DISPLAY:-:0}"

if command -v wvkbd-mobintl >/dev/null 2>&1; then
    exec wvkbd-mobintl -L 240 "$@"
elif command -v wvkbd >/dev/null 2>&1; then
    exec wvkbd -L 240 "$@"
elif command -v squeekboard >/dev/null 2>&1; then
    exec squeekboard "$@"
elif command -v onboard >/dev/null 2>&1; then
    exec onboard "$@"
elif command -v matchbox-keyboard >/dev/null 2>&1; then
    exec matchbox-keyboard "$@"
else
    echo "vboard: No virtual keyboard binary found" >&2
    exit 1
fi
EOF
chmod 755 /usr/local/bin/vboard 2>/dev/null || true

cat > /etc/xdg/autostart/vboard.desktop << 'EOF'
[Desktop Entry]
Type=Application
Name=vboard Virtual Keyboard
Exec=/usr/local/bin/vboard
Hidden=false
NoDisplay=false
X-GNOME-Autostart-enabled=true
EOF
chmod 644 /etc/xdg/autostart/vboard.desktop 2>/dev/null || true

if command -v wvkbd-mobintl >/dev/null 2>&1 || command -v wvkbd >/dev/null 2>&1 || command -v squeekboard >/dev/null 2>&1 || command -v onboard >/dev/null 2>&1 || command -v matchbox-keyboard >/dev/null 2>&1; then
    log "✅ vboard Virtual Keyboard installed and configured successfully!"
else
    log "⚠️ Packages installed but launcher binary not detected; check system packages."
fi
