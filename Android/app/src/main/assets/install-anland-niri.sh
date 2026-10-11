#!/bin/sh
# Droidspaces Niri + Noctalia Installer for any stock Linux rootfs
# Supports Debian, Ubuntu, Fedora, Arch Linux, Alpine, and other distros.

set -e
export DEBIAN_FRONTEND=noninteractive

log() { printf '[NIRI-INSTALL] %s\n' "$*"; }
warn() { printf '[NIRI-INSTALL] WARNING: %s\n' "$*" >&2; }
fail() { printf '[NIRI-INSTALL] ERROR: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || fail "Please run this installer as root (or with sudo)."

ARCH="$(uname -m)"
case "$ARCH" in
    aarch64|arm64)
        NIRI_ARCH="aarch64"
        DEB_ARCH="arm64"
        ;;
    x86_64|amd64)
        NIRI_ARCH="x86_64"
        DEB_ARCH="amd64"
        ;;
    *)
        fail "Unsupported architecture: $ARCH"
        ;;
esac

log "Detected architecture: $ARCH"

# ---------------------------------------------------------------------------
# 1. Package Manager Installation
# ---------------------------------------------------------------------------
log "Installing dependencies and compositor packages..."

if command -v apt-get >/dev/null 2>&1; then
    # Enable universe for Ubuntu
    if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
        sed -i '/^Components:/ { /universe/! s/$/ universe/ }' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
    fi
    if [ -f /etc/apt/sources.list ]; then
        sed -i 's/^\(deb .*\) main$/\1 main universe/' /etc/apt/sources.list 2>/dev/null || true
        sed -i 's/^\(deb .*\) main restricted$/\1 main restricted universe/' /etc/apt/sources.list 2>/dev/null || true
    fi

    apt-get update -qq || true
    
    # Try native distro package first
    if ! apt-get install -y -qq niri 2>/dev/null; then
        log "Niri not in standard apt repos, installing dependencies & prebuilt binary..."
        apt-get install -y -qq \
            curl wget tar xz-utils ca-certificates git build-essential \
            libwayland-client0 libwayland-server0 libwayland-cursor0 \
            libxkbcommon0 libegl1 libgles2 libinput10 libseat1 \
            libpipewire-0.3-0 libpango-1.0-0 libpangocairo-1.0-0 \
            libdisplay-info2 libglib2.0-0 \
            xdg-desktop-portal xdg-desktop-portal-wlr xdg-desktop-portal-gtk \
            qt6-wayland wayland-utils foot dbus-user-session \
            xwayland mesa-vulkan-drivers libvulkan1 2>/dev/null || true

        # Download prebuilt Niri binary if needed
        if ! command -v niri >/dev/null 2>&1; then
            log "Downloading prebuilt Niri release for $NIRI_ARCH..."
            TMP_DIR="$(mktemp -d)"
            NIRI_URL="https://github.com/niri-wm/niri/releases/latest/download/niri-linux-${NIRI_ARCH}.tar.gz"
            if curl -fsSL "$NIRI_URL" -o "$TMP_DIR/niri.tar.gz" 2>/dev/null || wget -qO "$TMP_DIR/niri.tar.gz" "$NIRI_URL" 2>/dev/null; then
                tar -xzf "$TMP_DIR/niri.tar.gz" -C "$TMP_DIR"
                if [ -f "$TMP_DIR/niri" ]; then
                    install -Dm755 "$TMP_DIR/niri" /usr/local/bin/niri
                    log "Installed niri binary to /usr/local/bin/niri"
                fi
            fi
            rm -rf "$TMP_DIR"
        fi
    fi

    # Install foot & desktop portals
    apt-get install -y -qq foot xdg-desktop-portal xdg-desktop-portal-gtk qt6-wayland wayland-utils 2>/dev/null || true

    # Install xwayland-satellite for X11 app support under niri
    if ! command -v xwayland-satellite >/dev/null 2>&1; then
        log "Installing xwayland-satellite for X11 support..."
        if command -v cargo >/dev/null 2>&1; then
            TMP_XWS="$(mktemp -d)"
            git clone --depth=1 https://github.com/Supreeeme/xwayland-satellite.git "$TMP_XWS/xwayland-satellite" 2>/dev/null && \
                cargo build --release --manifest-path "$TMP_XWS/xwayland-satellite/Cargo.toml" && \
                install -Dm755 "$TMP_XWS/xwayland-satellite/target/release/xwayland-satellite" /usr/local/bin/xwayland-satellite && \
                log "Installed xwayland-satellite from source" || warn "xwayland-satellite build failed, X11 apps may not work"
            rm -rf "$TMP_XWS"
        else
            warn "cargo not found, skipping xwayland-satellite build. X11 apps may not work under niri."
        fi
    fi

    # Vulkan Turnip symlinks
    mkdir -p /usr/lib/aarch64-linux-gnu /usr/share/vulkan/icd.d 2>/dev/null || true
    if [ -f /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so ] && [ ! -f /usr/lib/libvulkan_freedreno.so ]; then
        ln -sf /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so /usr/lib/libvulkan_freedreno.so
    fi
    ldconfig 2>/dev/null || true

elif command -v pacman >/dev/null 2>&1; then
    pacman -Sy --noconfirm --needed \
        niri foot xdg-desktop-portal xdg-desktop-portal-gtk \
        qt6-wayland wayland-utils pipewire wireplumber \
        xorg-xwayland vulkan-freedreno vulkan-icd-loader git base-devel 2>/dev/null || true
    pacman -Sy --noconfirm --needed noctalia-shell 2>/dev/null || true
    # xwayland-satellite from AUR or build from source
    if ! command -v xwayland-satellite >/dev/null 2>&1 && command -v cargo >/dev/null 2>&1; then
        TMP_XWS="$(mktemp -d)"
        git clone --depth=1 https://github.com/Supreeeme/xwayland-satellite.git "$TMP_XWS" 2>/dev/null && \
            cargo build --release --manifest-path "$TMP_XWS/Cargo.toml" && \
            install -Dm755 "$TMP_XWS/target/release/xwayland-satellite" /usr/local/bin/xwayland-satellite || true
        rm -rf "$TMP_XWS"
    fi

elif command -v dnf >/dev/null 2>&1; then
    dnf install -y \
        niri foot xdg-desktop-portal xdg-desktop-portal-gtk \
        qt6-qtwayland wayland-utils pipewire wireplumber \
        xorg-x11-server-Xwayland mesa-vulkan-drivers vulkan-loader git cargo rust 2>/dev/null || true
    dnf install -y noctalia 2>/dev/null || true
    if ! command -v xwayland-satellite >/dev/null 2>&1 && command -v cargo >/dev/null 2>&1; then
        TMP_XWS="$(mktemp -d)"
        git clone --depth=1 https://github.com/Supreeeme/xwayland-satellite.git "$TMP_XWS" 2>/dev/null && \
            cargo build --release --manifest-path "$TMP_XWS/Cargo.toml" && \
            install -Dm755 "$TMP_XWS/target/release/xwayland-satellite" /usr/local/bin/xwayland-satellite || true
        rm -rf "$TMP_XWS"
    fi
    # Vulkan lib64 symlink for Fedora
    [ -f /usr/lib64/libvulkan_freedreno.so ] && \
        ln -sf /usr/lib64/libvulkan_freedreno.so /usr/lib/libvulkan_freedreno.so 2>/dev/null || true

elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache \
        niri foot xdg-desktop-portal xdg-desktop-portal-wlr xdg-desktop-portal-gtk \
        qt6-qtwayland wayland-utils 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
# 2. Configure Default Niri & Noctalia Configs
# ---------------------------------------------------------------------------
log "Setting up default Niri and Noctalia configurations..."

mkdir -p /etc/niri /etc/skel/.config/niri /etc/skel/.config/noctalia /etc/skel/.config/foot 2>/dev/null || true

# Default niri config
if [ ! -f /etc/niri/config.kdl ]; then
    cat > /etc/niri/config.kdl << 'KDLEOF'
input {
    keyboard {
        xkb {
            layout "us"
        }
    }
    touchpad {
        tap
        natural-scroll
    }
    touch {
        map-to-output "default"
    }
}

output "default" {
    scale 1.0
}

layout {
    gaps 8
    center-focused-column "never"
    preset-column-widths {
        proportion 0.33333
        proportion 0.5
        proportion 0.66667
        proportion 1.0
    }
    default-column-width { proportion 0.5; }
    focus-ring {
        width 2
        active-color "#7fc8ff"
        inactive-color "#505050"
    }
}

spawn-at-startup "noctalia"
spawn-at-startup "xwayland-satellite"
spawn-at-startup "foot" "--server"

prefer-no-csd

binds {
    Mod+Return { spawn "foot"; }
    Mod+D { spawn "fuzzel"; }
    Mod+Q { close-window; }
    Mod+Left  { focus-column-left; }
    Mod+Right { focus-column-right; }
    Mod+Up    { focus-window-up; }
    Mod+Down  { focus-window-down; }
    Mod+Shift+Left  { move-column-left; }
    Mod+Shift+Right { move-column-right; }
    Mod+F { maximize-column; }
    Mod+Shift+F { fullscreen-window; }
}
KDLEOF
fi

cp -f /etc/niri/config.kdl /etc/skel/.config/niri/config.kdl 2>/dev/null || true

# Populate config in existing user directories
for h in /home/* /root; do
    if [ -d "$h" ]; then
        u="$(basename "$h")"
        [ "$u" = "root" ] || u="$(awk -F: -v home="$h" '$6 == home { print $1; exit }' /etc/passwd 2>/dev/null || echo "$u")"
        mkdir -p "$h/.config/niri" "$h/.config/noctalia" "$h/.config/foot" 2>/dev/null || true
        [ -f "$h/.config/niri/config.kdl" ] || cp /etc/niri/config.kdl "$h/.config/niri/config.kdl" 2>/dev/null || true
        chown -R "$u:$u" "$h/.config" 2>/dev/null || true
    fi
done

# ---------------------------------------------------------------------------
# 3. Configure Wayland & XDG Environment
# ---------------------------------------------------------------------------
log "Configuring Wayland and XDG environment..."

ENV_FILE="/etc/environment"
touch "$ENV_FILE" 2>/dev/null || true

add_env_var() {
    key="$1"
    val="$2"
    if ! grep -q "^${key}=" "$ENV_FILE" 2>/dev/null; then
        echo "${key}=${val}" >> "$ENV_FILE"
    fi
}

add_env_var "WAYLAND_DISPLAY" "wayland-0"
add_env_var "DISPLAY" ":0"
add_env_var "QT_QPA_PLATFORM" "wayland"
add_env_var "QT_AUTO_SCREEN_SCALE_FACTOR" "1"
add_env_var "GDK_BACKEND" "wayland,x11"
add_env_var "CLUTTER_BACKEND" "wayland"
add_env_var "SDL_VIDEODRIVER" "wayland"
add_env_var "XDG_SESSION_TYPE" "wayland"
add_env_var "XDG_CURRENT_DESKTOP" "niri"
add_env_var "ANLAND" "1"
add_env_var "ANLAND_SOCKET" "/run/display.sock"
add_env_var "ANLAND_DRM_DEVICE" "/dev/dri/renderD128"
add_env_var "MOZ_ENABLE_WAYLAND" "1"

# Profile drop-in for interactive login shells
mkdir -p /etc/profile.d 2>/dev/null || true
cat > /etc/profile.d/anland-niri.sh << 'EOF'
# Anland Niri Wayland environment setup
[ -z "$WAYLAND_DISPLAY" ] && export WAYLAND_DISPLAY=wayland-0
[ -z "$DISPLAY" ] && export DISPLAY=:0
[ -z "$QT_QPA_PLATFORM" ] && export QT_QPA_PLATFORM=wayland
[ -z "$ANLAND" ] && export ANLAND=1
[ -z "$ANLAND_SOCKET" ] && export ANLAND_SOCKET=/run/display.sock
[ -z "$ANLAND_DRM_DEVICE" ] && export ANLAND_DRM_DEVICE=/dev/dri/renderD128
[ -z "$XDG_SESSION_TYPE" ] && export XDG_SESSION_TYPE=wayland
[ -z "$XDG_CURRENT_DESKTOP" ] && export XDG_CURRENT_DESKTOP=niri
[ -z "$XDG_RUNTIME_DIR" ] && export XDG_RUNTIME_DIR="/tmp/runtime-$(id -un 2>/dev/null || echo user)"
[ -d "$XDG_RUNTIME_DIR" ] || mkdir -p -m 0700 "$XDG_RUNTIME_DIR" 2>/dev/null || true
EOF
chmod 0755 /etc/profile.d/anland-niri.sh 2>/dev/null || true

# ---------------------------------------------------------------------------
# 4. Configure Systemd Services
# ---------------------------------------------------------------------------
TARGET_USER="$(awk -F: '$3 >= 1000 && $3 < 65534 && $7 !~ /(nologin|false)$/ { print $1; exit }' /etc/passwd 2>/dev/null || echo "")"
[ -n "$TARGET_USER" ] || TARGET_USER="root"

log "Configuring systemd service for user '$TARGET_USER'..."

mkdir -p /etc/systemd/system 2>/dev/null || true

cat > /etc/systemd/system/niri.service << EOF
[Unit]
Description=Niri Scrollable Tiling Compositor (Anland Backend)
Documentation=https://github.com/niri-wm/niri
After=network.target display-manager.service
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=$TARGET_USER
PAMName=login
EnvironmentFile=-/etc/environment
Environment=ANLAND_SOCKET=/run/display.sock
Environment=ANLAND_DRM_DEVICE=/dev/dri/renderD128
Environment=QT_QPA_PLATFORM=wayland
Environment=GTK_THEME=Adwaita:dark
ExecStartPre=/bin/bash -lc 'export XDG_RUNTIME_DIR="/tmp/runtime-\$(id -un 2>/dev/null || echo $TARGET_USER)"; mkdir -p -m 0700 "\$XDG_RUNTIME_DIR" && chown $TARGET_USER:$TARGET_USER "\$XDG_RUNTIME_DIR"'
ExecStart=/bin/bash -lc 'export XDG_RUNTIME_DIR="/tmp/runtime-\$(id -un 2>/dev/null || echo $TARGET_USER)"; if [ -S /run/display.sock ]; then if command -v niri >/dev/null 2>&1; then niri --anland; else /usr/local/bin/niri --anland; fi; else niri; fi'
Restart=on-failure
RestartSec=2s

[Install]
WantedBy=multi-user.target
EOF
chmod 644 /etc/systemd/system/niri.service 2>/dev/null || true

if command -v systemctl >/dev/null 2>&1; then
    systemctl daemon-reload 2>/dev/null || true
    systemctl enable niri.service 2>/dev/null || true
fi

log "✅ Niri Wayland Compositor installed and configured successfully!"
