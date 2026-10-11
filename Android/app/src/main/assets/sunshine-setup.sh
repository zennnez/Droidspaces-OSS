#!/bin/sh
# Droidspaces Sunshine (Moonlight Game & Desktop Streaming) control script.
# Runs INSIDE the container to manage the Sunshine streaming server.

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

CONTROL_DIR="/run/droidspaces-sunshine"
PIDFILE="$CONTROL_DIR/sunshine.pid"
LOGFILE="$CONTROL_DIR/sunshine.log"
CONFIG_DIR="/root/.config/sunshine"
CONFIG_FILE="$CONFIG_DIR/sunshine.conf"

mkdir -p "$CONTROL_DIR" "$CONFIG_DIR" 2>/dev/null || true

log() {
    ts="$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '')"
    msg="[sunshine $ts] $1"
    echo "$msg"
    echo "$msg" >> "$LOGFILE" 2>/dev/null || true
}

have_sunshine() { command -v sunshine >/dev/null 2>&1; }

is_running() {
    if [ -f "$PIDFILE" ]; then
        pid="$(cat "$PIDFILE" 2>/dev/null)"
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
    fi
    # Also check pgrep in case pidfile is stale
    pgrep -x sunshine >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# Display environment setup
# ---------------------------------------------------------------------------
configure_display_env() {
    # Find active Wayland socket
    found_wayland=0
    for cand_dir in /run/user/* /tmp/runtime-* /run /tmp; do
        if [ -S "$cand_dir/wayland-0" ] || [ -S "$cand_dir/wayland-1" ]; then
            export XDG_RUNTIME_DIR="$cand_dir"
            if [ -S "$cand_dir/wayland-0" ]; then
                export WAYLAND_DISPLAY="wayland-0"
            else
                export WAYLAND_DISPLAY="wayland-1"
            fi
            chmod 777 "$cand_dir" "$cand_dir"/wayland-* 2>/dev/null || true
            found_wayland=1
            break
        fi
    done

    if [ "$found_wayland" = "0" ]; then
        export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-$(id -un 2>/dev/null || echo root)}"
        mkdir -p -m 0777 "$XDG_RUNTIME_DIR" 2>/dev/null || true
        export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
    fi

    # Discover X11 display
    if [ -z "${DISPLAY:-}" ]; then
        for s in /tmp/.X11-unix/X*; do
            if [ -S "$s" ] || [ -e "$s" ]; then
                num="$(basename "$s" | sed 's/^X//')"
                [ -n "$num" ] && export DISPLAY=":$num" && break
            fi
        done
    fi
    export DISPLAY="${DISPLAY:-:0}"

    # Ensure /dev/uinput permissions for gamepad & virtual input
    chmod 0666 /dev/uinput 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Installation
# ---------------------------------------------------------------------------
install_sunshine() {
    if have_sunshine; then
        log "Sunshine is already installed: $(sunshine --version 2>&1 | head -n1)"
        return 0
    fi

    log "=== Installing Sunshine Streaming Server ==="
    mkdir -p /root /etc/skel /etc/apt/apt.conf.d /etc/dpkg/dpkg.cfg.d 2>/dev/null || true
    [ -f /root/.curlrc ] || printf "silent\nshow-error\n" > /root/.curlrc 2>/dev/null || true
    [ -f /etc/curlrc ] || printf "silent\nshow-error\n" > /etc/curlrc 2>/dev/null || true
    [ -f /etc/apt/apt.conf.d/99droidspaces ] || printf 'Dpkg::Use-Pty "0";\nquiet "2";\nAPT::Install-Recommends "false";\nDpkg::Options { "--force-confdef"; "--force-confold"; };\n' > /etc/apt/apt.conf.d/99droidspaces 2>/dev/null || true

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
        DEBIAN_FRONTEND=noninteractive apt-get update -qq

        log "Trying apt-get install sunshine..."
        if DEBIAN_FRONTEND=noninteractive apt-get install -y -qq sunshine 2>/dev/null; then
            log "Sunshine installed via apt-get ✓"
        else
            log "Installing prerequisites and latest release package from GitHub..."
            DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
                libayatana-appindicator3-1 libevdev2 libpulse0 libminiupnpc* \
                libva2 libvdpau9 udev curl wget 2>/dev/null || true

            ARCH="$(uname -m)"
            DEB_ARCH="arm64"
            [ "$ARCH" = "x86_64" ] && DEB_ARCH="amd64"
            [ "$ARCH" = "aarch64" ] && DEB_ARCH="arm64"

            TMP_DEB="/tmp/sunshine.deb"
            URL="https://github.com/LizardByte/Sunshine/releases/latest/download/sunshine-ubuntu-24.04-${DEB_ARCH}.deb"
            log "Downloading $URL..."
            if curl -fSL "$URL" -o "$TMP_DEB" 2>/dev/null || wget -q "$URL" -O "$TMP_DEB" 2>/dev/null; then
                dpkg -i "$TMP_DEB" 2>/dev/null || DEBIAN_FRONTEND=noninteractive apt-get install -f -y -qq 2>/dev/null || true
                rm -f "$TMP_DEB"
            fi
        fi
    elif command -v pacman >/dev/null 2>&1; then
        log "Installing Sunshine via pacman..."
        pacman -Sy --noconfirm sunshine 2>/dev/null || true
    elif command -v dnf >/dev/null 2>&1; then
        log "Installing Sunshine via dnf..."
        dnf install -y sunshine 2>/dev/null || true
    elif command -v apk >/dev/null 2>&1; then
        log "Installing Sunshine via apk..."
        apk add --no-cache sunshine 2>/dev/null || true
    fi

    # Set up uinput udev rules
    mkdir -p /etc/udev/rules.d 2>/dev/null || true
    cat > /etc/udev/rules.d/85-sunshine-input.rules << 'EOF'
KERNEL=="uinput", GROUP="input", MODE="0660", OPTIONS+="static_node=uinput"
EOF
    chmod 0666 /dev/uinput 2>/dev/null || true

    # Default sunshine configuration
    cat > "$CONFIG_FILE" << 'EOF'
origin_pin_allowed = pc
port = 47989
origin_web_ui_allowed = lan
EOF

    if have_sunshine; then
        log "✅ Sunshine installed successfully: $(sunshine --version 2>&1 | head -n1)"
        return 0
    else
        log "❌ Sunshine installation failed"
        return 1
    fi
}

# ---------------------------------------------------------------------------
# Launch / Stop
# ---------------------------------------------------------------------------
launch_sunshine() {
    if is_running; then
        log "Sunshine is already running"
        return 0
    fi

    have_sunshine || { log "Sunshine is not installed"; return 1; }

    configure_display_env
    log "Display: DISPLAY=$DISPLAY WAYLAND_DISPLAY=$WAYLAND_DISPLAY XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR"

    log "Starting Sunshine daemon..."
    sunshine "$CONFIG_FILE" >> "$LOGFILE" 2>&1 &
    new_pid=$!

    sleep 1.5
    if kill -0 "$new_pid" 2>/dev/null || pgrep -x sunshine >/dev/null 2>&1; then
        real_pid="$(pgrep -x sunshine | head -n1)"
        [ -n "$real_pid" ] && new_pid="$real_pid"
        echo "$new_pid" > "$PIDFILE"
        log "Sunshine started successfully (pid $new_pid)"
        return 0
    fi

    log "Sunshine failed to start -- check $LOGFILE"
    tail -n 20 "$LOGFILE" 2>/dev/null || true
    return 1
}

stop_sunshine() {
    log "Stopping Sunshine..."
    if [ -f "$PIDFILE" ]; then
        pid="$(cat "$PIDFILE" 2>/dev/null)"
        [ -n "$pid" ] && kill -TERM "$pid" 2>/dev/null || true
        rm -f "$PIDFILE"
    fi
    pkill -x sunshine 2>/dev/null || true
    sleep 0.5
    pkill -9 -x sunshine 2>/dev/null || true
    log "Sunshine stopped"
}

set_creds() {
    user="${1:-admin}"
    pass="${2:-admin}"
    have_sunshine || { log "Sunshine is not installed"; return 1; }
    log "Setting Sunshine Web UI credentials for user '$user'..."
    sunshine --creds "$user" "$pass" >> "$LOGFILE" 2>&1
    log "Credentials configured successfully ✓"
}

# ---------------------------------------------------------------------------
# Status
# ---------------------------------------------------------------------------
status() {
    installed=0
    have_sunshine && installed=1

    running=0
    pid=""
    if is_running; then
        running=1
        [ -f "$PIDFILE" ] && pid="$(cat "$PIDFILE" 2>/dev/null)"
        [ -z "$pid" ] && pid="$(pgrep -x sunshine | head -n1)"
    fi

    ver=""
    have_sunshine && ver="$(sunshine --version 2>&1 | head -n1)"

    echo "installed=$installed"
    echo "running=$running"
    echo "pid=$pid"
    echo "version=$ver"
    echo "web_port=47990"
    echo "stream_port=47989"
}

# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------
case "${1:-status}" in
    install)    install_sunshine ;;
    launch)     launch_sunshine ;;
    stop-now)   stop_sunshine ;;
    restart)    stop_sunshine; sleep 1; launch_sunshine ;;
    set-creds)  set_creds "$2" "$3" ;;
    status)     status ;;
    get-log)    cat "$LOGFILE" 2>/dev/null || echo "No logs found." ;;
    check-installed) have_sunshine && exit 0 || exit 1 ;;
    *)          echo "Usage: $0 {install|launch|stop-now|restart|set-creds <user> <pass>|status|get-log}" ;;
esac
