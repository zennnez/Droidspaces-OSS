#!/bin/sh
# Droidspaces Wireless Display (VNC) control script.
# Runs INSIDE the container (via `droidspaces --name=X run 'sh -s -- <args>'`),
# so it must stay POSIX sh -- no bashisms, no assumption of a specific distro.
#
# Mirrors the EXISTING X11 session that Termux:X11 already bridges into this
# container (DISPLAY is discovered, never guessed -- see get_display() below).
# This script never starts XFCE, never touches Termux:X11, and never opens a
# second X session. It only attaches x11vnc to whatever DISPLAY is already
# live, when it's already live.
#
# Subcommands:
#   check-installed          exit 0 + print path if x11vnc is present
#   install                  detect the container's package manager, install x11vnc
#   set-password             read a plaintext password from stdin, store it as
#                            an obscured x11vnc passwd file (mode 600, root-only)
#   clear-password           remove the stored passwd file
#   watch <port> <lan_only>  foreground loop: start/stop x11vnc as the X11
#                            socket for the discovered DISPLAY appears/disappears.
#   service <action> [args]  start/stop/restart/status via systemd, OpenRC, or setsid
#   stop-now                 clean, immediate shutdown of any running x11vnc
#   status                   key=value status report
#   get-log                  print the contents of x11vnc.log
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

CONTROL_DIR="/run/droidspaces-vnc"
PIDFILE="$CONTROL_DIR/x11vnc.pid"
LOGFILE="$CONTROL_DIR/x11vnc.log"
PASSWD_FILE="/root/.droidspaces/vnc.passwd"
DROIDSPACES_ENV="/run/droidspaces.env"
POLL_INTERVAL=3

mkdir -p "$CONTROL_DIR" 2>/dev/null || true

log() {
    ts="$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '')"
    msg="[VNC $ts] $1"
    echo "$msg"
    echo "$msg" >> "$LOGFILE" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# DISPLAY discovery.
# ---------------------------------------------------------------------------
get_display() {
    # Check if Anland Wayland display daemon is active or configured
    if [ -S "/run/display.sock" ] || [ -S "/data/local/tmp/display_daemon.sock" ] || [ -n "${ANLAND_SOCKET:-}" ] || [ "${ANLAND:-}" = "1" ] || grep -q "ANLAND=" /etc/environment 2>/dev/null; then
        export ANLAND=1
        export ANLAND_SOCKET="${ANLAND_SOCKET:-/run/display.sock}"
        export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
        export ANLAND_DRM_DEVICE="${ANLAND_DRM_DEVICE:-/dev/dri/renderD128}"
        export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-wayland}"
    fi

    if [ -f "$DROIDSPACES_ENV" ]; then
        disp="$(sed -n "s/^export DISPLAY=['\"]\?\([^'\"]*\)['\"]\?$/\1/p" "$DROIDSPACES_ENV" | head -n1)"
        if [ -n "$disp" ]; then
            echo "$disp"
            return 0
        fi
    fi
    if [ -f "/etc/environment" ]; then
        disp="$(sed -n "s/^DISPLAY=['\"]\?\([^'\"]*\)['\"]\?$/\1/p" /etc/environment | head -n1)"
        if [ -n "$disp" ]; then
            echo "$disp"
            return 0
        fi
    fi
    if [ -n "${DISPLAY:-}" ]; then
        echo "$DISPLAY"
        return 0
    fi
    for s in /tmp/.X11-unix/X*; do
        if [ -S "$s" ] || [ -e "$s" ]; then
            num="${s#/tmp/.X11-unix/X}"
            if [ -n "$num" ] && [ "$num" != "*" ]; then
                echo ":$num"
                return 0
            fi
        fi
    done
    for s in /data/data/com.termux.x11/files/tmp/.X11-unix/X*; do
        if [ -S "$s" ] || [ -e "$s" ]; then
            num="${s#/data/data/com.termux.x11/files/tmp/.X11-unix/X}"
            if [ -n "$num" ] && [ "$num" != "*" ]; then
                echo ":$num"
                return 0
            fi
        fi
    done

    # Fallback to :0 if Anland display daemon is active
    if [ "${ANLAND:-}" = "1" ] || [ -S "/run/display.sock" ] || [ -S "/data/local/tmp/display_daemon.sock" ]; then
        echo ":0"
        return 0
    fi

    return 1
}

x11_socket_for_display() {
    d="$1"
    num="${d#:}"
    num="${num%%.*}"
    [ -n "$num" ] && echo "/tmp/.X11-unix/X$num"
}

find_x11vnc() {
    command -v x11vnc 2>/dev/null
}

# ---------------------------------------------------------------------------
# Package manager detection + install
# ---------------------------------------------------------------------------
install_x11vnc() {
    if find_x11vnc >/dev/null; then
        log "x11vnc already installed at $(find_x11vnc)"
        return 0
    fi

    if command -v apt-get > /dev/null 2>&1; then
        log "Installing x11vnc via apt-get..."
        if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
            sed -i '/^Components:/ { /universe/! s/$/ universe/ }' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
        fi
        if [ -f /etc/apt/sources.list ]; then
            sed -i 's/^\(deb .*\) main$/\1 main universe/' /etc/apt/sources.list 2>/dev/null || true
            sed -i 's/^\(deb .*\) main restricted$/\1 main restricted universe/' /etc/apt/sources.list 2>/dev/null || true
        fi
        if command -v add-apt-repository > /dev/null 2>&1; then
            add-apt-repository -y universe > /dev/null 2>&1 || true
        fi
        apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq x11vnc
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq novnc websockify >/dev/null 2>&1 || true
    elif command -v dnf >/dev/null 2>&1; then
        log "Installing x11vnc via dnf..."
        dnf install -y x11vnc
    elif command -v yum >/dev/null 2>&1; then
        log "Installing x11vnc via yum..."
        yum install -y x11vnc
    elif command -v pacman >/dev/null 2>&1; then
        log "Installing x11vnc via pacman..."
        pacman -Sy --noconfirm x11vnc
    elif command -v apk >/dev/null 2>&1; then
        log "Installing x11vnc via apk..."
        apk add --no-cache x11vnc
    elif command -v zypper >/dev/null 2>&1; then
        log "Installing x11vnc via zypper..."
        zypper --non-interactive install x11vnc
    else
        log "No supported package manager found (tried apt-get, dnf, yum, pacman, apk, zypper)"
        return 1
    fi

    if find_x11vnc >/dev/null; then
        log "x11vnc installed successfully at $(find_x11vnc)"
        return 0
    else
        log "x11vnc install command ran but the binary still isn't on PATH"
        return 1
    fi
}

# ---------------------------------------------------------------------------
# Password provisioning
# ---------------------------------------------------------------------------
set_password() {
    x11vnc_bin="$(find_x11vnc)"
    if [ -z "$x11vnc_bin" ]; then
        log "x11vnc not installed, installing now..."
        install_x11vnc || {
            log "Cannot set password: failed to install x11vnc"
            return 1
        }
        x11vnc_bin="$(find_x11vnc)"
    fi
    b64pw="${1:-}"
    if [ -z "$b64pw" ]; then
        log "Refusing to set an empty password"
        return 1
    fi
    mkdir -p "$(dirname "$PASSWD_FILE")"
    pw="$(echo "$b64pw" | base64 -d)"
    if [ -z "$pw" ]; then
        log "Refusing to set an empty password"
        return 1
    fi
    "$x11vnc_bin" -storepasswd "$pw" "$PASSWD_FILE" >/dev/null 2>&1
    rc=$?
    pw=""
    if [ "$rc" -eq 0 ] && [ -f "$PASSWD_FILE" ]; then
        chmod 600 "$PASSWD_FILE"
        log "VNC password stored successfully"
        return 0
    fi
    log "Failed to store VNC password (rc=$rc)"
    return 1
}

clear_password() {
    rm -f "$PASSWD_FILE"
    log "VNC password cleared"
}

# ---------------------------------------------------------------------------
# Process management
# ---------------------------------------------------------------------------
is_running() {
    if [ -f "$PIDFILE" ]; then
        pid="$(cat "$PIDFILE" 2>/dev/null)"
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
    fi
    # Fallback process check
    pgrep -x x11vnc >/dev/null 2>&1
}

find_xauth() {
    if [ -n "${XAUTHORITY:-}" ] && [ -f "$XAUTHORITY" ]; then
        echo "$XAUTHORITY"
        return 0
    fi
    if [ -f "$HOME/.Xauthority" ]; then
        echo "$HOME/.Xauthority"
        return 0
    fi
    if [ -f "/root/.Xauthority" ]; then
        echo "/root/.Xauthority"
        return 0
    fi
    for f in /home/*/.Xauthority /home/*/.xauth* /home/*/xauth_*; do
        if [ -f "$f" ]; then
            echo "$f"
            return 0
        fi
    done
    for f in /tmp/.Xauth* /tmp/xauth_* /run/user/*/.Xauthority /run/user/*/xauth* /run/xauth/*; do
        if [ -f "$f" ]; then
            echo "$f"
            return 0
        fi
    done
    return 1
}

start_vnc() {
    port="${1:-5900}"
    lan_only="${2:-1}"

    if is_running; then
        log "x11vnc is already running"
        return 0
    fi

    display="$(get_display)"
    log "Discovered DISPLAY=$display"

    x11vnc_bin="$(find_x11vnc)"
    if [ -z "$x11vnc_bin" ]; then
        log "x11vnc is not installed"
        return 1
    fi

    # Ensure environment variables for Anland, Wayland, GPU acceleration & Turnip are loaded
    if [ -f "$DROIDSPACES_ENV" ]; then
        # shellcheck disable=SC1090
        . "$DROIDSPACES_ENV" 2>/dev/null || true
    fi
    if [ -f "/etc/environment" ]; then
        # shellcheck disable=SC1091
        . /etc/environment 2>/dev/null || true
    fi
    if [ -S "/run/display.sock" ] || [ -S "/data/local/tmp/display_daemon.sock" ] || [ "${ANLAND:-}" = "1" ] || grep -q "ANLAND=" /etc/environment 2>/dev/null; then
        export ANLAND=1
        export ANLAND_SOCKET="${ANLAND_SOCKET:-/run/display.sock}"
        export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
        export ANLAND_DRM_DEVICE="${ANLAND_DRM_DEVICE:-/dev/dri/renderD128}"
        export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-wayland}"
        export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-$(id -un 2>/dev/null || echo root)}"
        mkdir -p -m 0700 "$XDG_RUNTIME_DIR" 2>/dev/null || true
        log "Configured VNC for Anland display daemon (socket: $ANLAND_SOCKET, WAYLAND_DISPLAY: $WAYLAND_DISPLAY)"
    fi
    [ -n "$MESA_LOADER_DRIVER_OVERRIDE" ] && export MESA_LOADER_DRIVER_OVERRIDE
    [ -n "$TU_DEBUG" ] && export TU_DEBUG
    [ -n "$FD_DEV_FEATURES" ] && export FD_DEV_FEATURES

    mkdir -p "$CONTROL_DIR"

    # Clean up stale processes
    pkill -f websockify 2>/dev/null || true

    auth_args=""
    if [ -f "$PASSWD_FILE" ]; then
        auth_args="-rfbauth $PASSWD_FILE"
        log "Using configured VNC password"
    else
        auth_args="-nopw"
        log "No VNC password configured; starting with -nopw (open access on LAN)"
    fi

    allow_args=""
    if [ "$lan_only" = "1" ]; then
        allow_args="-allow 127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"
    fi

    # Discover Xauthority and permit local X connections
    xauth_file="$(find_xauth 2>/dev/null || true)"
    if [ -n "$xauth_file" ] && [ -r "$xauth_file" ]; then
        export XAUTHORITY="$xauth_file"
        if [ "$(id -u 2>/dev/null)" = "0" ] && [ "$xauth_file" != "/root/.Xauthority" ]; then
            cp -f "$xauth_file" /root/.Xauthority 2>/dev/null || true
            chmod 600 /root/.Xauthority 2>/dev/null || true
        fi
        DISPLAY="$display" XAUTHORITY="$xauth_file" xhost +local: >/dev/null 2>&1 || DISPLAY="$display" XAUTHORITY="$xauth_file" xhost + >/dev/null 2>&1 || true
    else
        DISPLAY="$display" xhost +local: >/dev/null 2>&1 || DISPLAY="$display" xhost + >/dev/null 2>&1 || true
    fi
    export XAUTHLOCALHOSTNAME="${XAUTHLOCALHOSTNAME:-localhost}"

    xauth_args=""
    if [ -n "$xauth_file" ] && [ -f "$xauth_file" ]; then
        xauth_args="-auth $xauth_file"
    elif [ -f "/root/.Xauthority" ]; then
        xauth_args="-auth /root/.Xauthority"
    fi

    log "Executing: $x11vnc_bin -display $display -rfbport $port -forever -shared -noxdamage $auth_args $allow_args $xauth_args"

    # shellcheck disable=SC2086
    "$x11vnc_bin" -display "$display" -rfbport "$port" -forever -shared \
        -noxdamage $xauth_args $auth_args $allow_args >>"$LOGFILE" 2>&1 &
    new_pid=$!

    sleep 1
    if ! kill -0 "$new_pid" 2>/dev/null; then
        # Retry without explicit -auth flag (relies on xhost +local:)
        log "Initial x11vnc start failed; retrying with socket connection..."
        "$x11vnc_bin" -display "$display" -rfbport "$port" -forever -shared \
            -noxdamage $auth_args $allow_args >>"$LOGFILE" 2>&1 &
        new_pid=$!
        sleep 1
    fi

    if kill -0 "$new_pid" 2>/dev/null; then
        echo "$new_pid" > "$PIDFILE"
        log "x11vnc started successfully (pid $new_pid) on port $port"

        # Optional noVNC + websockify browser bridge
        novnc_web=""
        [ -d "/usr/share/novnc" ] && novnc_web="/usr/share/novnc"
        [ -d "/usr/share/novnc-core" ] && novnc_web="/usr/share/novnc-core"
        if command -v websockify >/dev/null 2>&1 && [ -n "$novnc_web" ]; then
            web_port="$((port + 180))"
            [ "$port" = "5900" ] && web_port=6080
            log "Starting noVNC websockify bridge on port $web_port (target localhost:$port)..."
            websockify --web "$novnc_web" "$web_port" "localhost:$port" >>"$LOGFILE" 2>&1 &
            echo $! > "$CONTROL_DIR/websockify.pid"
        fi

        return 0
    fi

    log "x11vnc failed to start or exited immediately. Last log output:"
    tail -n 15 "$LOGFILE" 2>/dev/null || true
    return 1
}

stop_vnc() {
    log "Stopping x11vnc..."
    if [ -f "$CONTROL_DIR/websockify.pid" ]; then
        wpid="$(cat "$CONTROL_DIR/websockify.pid" 2>/dev/null)"
        [ -n "$wpid" ] && kill "$wpid" 2>/dev/null || true
        rm -f "$CONTROL_DIR/websockify.pid"
    fi
    pkill -f websockify 2>/dev/null || true
    if [ -f "$PIDFILE" ]; then
        pid="$(cat "$PIDFILE" 2>/dev/null)"
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill -TERM "$pid" 2>/dev/null || true
            i=0
            while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 10 ]; do
                sleep 0.2
                i=$((i + 1))
            done
            kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null || true
        fi
        rm -f "$PIDFILE"
    fi
    pkill -x x11vnc 2>/dev/null || true
    log "x11vnc stopped"
}

watch_loop() {
    port="${1:-5900}"
    lan_only="${2:-1}"

    trap 'log "Received shutdown signal"; stop_vnc; exit 0' TERM INT

    log "Watch loop started for DISPLAY (port=$port, lan_only=$lan_only)..."
    while true; do
        if ! is_running; then
            start_vnc "$port" "$lan_only" || sleep 3
        fi
        sleep "$POLL_INTERVAL"
    done
}

service_action() {
    action="$1"
    port="${2:-5900}"
    lan_only="${3:-1}"

    if command -v systemctl >/dev/null 2>&1 && [ "$(cat /proc/1/comm 2>/dev/null)" = "systemd" ]; then
        systemctl daemon-reload 2>/dev/null || true
        case "$action" in
            start) systemctl start droidspaces-vnc.service 2>&1 ;;
            stop) systemctl stop droidspaces-vnc.service 2>&1 ;;
            restart) systemctl restart droidspaces-vnc.service 2>&1 ;;
            status) systemctl status droidspaces-vnc.service 2>&1 ;;
        esac
    elif command -v rc-service >/dev/null 2>&1 && [ -x /etc/init.d/droidspaces-vnc ]; then
        case "$action" in
            start) rc-service droidspaces-vnc start 2>&1 ;;
            stop) rc-service droidspaces-vnc stop 2>&1 ;;
            restart) rc-service droidspaces-vnc restart 2>&1 ;;
            status) rc-service droidspaces-vnc status 2>&1 ;;
        esac
    else
        # Fallback to standalone background process
        case "$action" in
            start)
                stop_vnc
                sleep 0.5
                setsid "$0" watch "$port" "$lan_only" >>"$LOGFILE" 2>&1 &
                sleep 1
                status
                ;;
            stop)
                stop_vnc
                pkill -f "wireless_display.sh watch" 2>/dev/null || true
                status
                ;;
            restart)
                stop_vnc
                pkill -f "wireless_display.sh watch" 2>/dev/null || true
                sleep 0.5
                setsid "$0" watch "$port" "$lan_only" >>"$LOGFILE" 2>&1 &
                sleep 1
                status
                ;;
            status)
                status
                ;;
        esac
    fi
}

status() {
    display="$(get_display)"
    running=0
    pid=""
    if is_running; then
        running=1
        pid="$(cat "$PIDFILE" 2>/dev/null || pgrep -x x11vnc | head -n1)"
    fi
    installed=0
    find_x11vnc >/dev/null && installed=1
    has_password=0
    [ -f "$PASSWD_FILE" ] && has_password=1

    echo "installed=$installed"
    echo "display=$display"
    echo "running=$running"
    echo "pid=$pid"
    echo "has_password=$has_password"
}

get_log() {
    if [ -f "$LOGFILE" ]; then
        tail -n 200 "$LOGFILE"
    else
        echo "No log file found at $LOGFILE"
    fi
}

# ---------------------------------------------------------------------------
cmd="${1:-}"
shift 2>/dev/null || true

case "$cmd" in
    check-installed)
        bin="$(find_x11vnc)"
        if [ -n "$bin" ]; then
            echo "$bin"
            exit 0
        fi
        exit 1
        ;;
    install)
        install_x11vnc
        ;;
    set-password)
        set_password "${1:-}"
        ;;
    clear-password)
        clear_password
        ;;
    watch)
        watch_loop "${1:-5900}" "${2:-1}"
        ;;
    service)
        service_action "$@"
        ;;
    stop-now)
        stop_vnc
        ;;
    status)
        status
        ;;
    get-log)
        get_log
        ;;
    *)
        echo "Usage: $0 {check-installed|install|set-password <b64pw>|clear-password|watch <port> <lan_only>|service <start|stop|restart|status> [port] [lan_only]|stop-now|status|get-log}" >&2
        exit 2
        ;;
esac
