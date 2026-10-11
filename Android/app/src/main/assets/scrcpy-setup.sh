#!/bin/sh
# Droidspaces scrcpy loopback control script.
# Runs INSIDE the container (via `droidspaces --name=X run 'sh -s -- <args>'`),
# so it must stay POSIX sh -- no bashisms, no assumption of a specific distro.
#
# Loopback scrcpy: runs the scrcpy CLIENT inside this container's own desktop
# session (X11/Termux:X11 or anland/KWin -- detected, never guessed, see
# get_display_mode() below) connected to the SAME Android device Droidspaces
# itself is running on, via `adb connect 172.28.0.1:<port>` -- the container's
# own NAT gateway address.

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

CONTROL_DIR="/run/droidspaces-scrcpy"
PIDFILE="$CONTROL_DIR/scrcpy.pid"
LOGFILE="$CONTROL_DIR/scrcpy.log"
DROIDSPACES_ENV="/run/droidspaces.env"

mkdir -p "$CONTROL_DIR" 2>/dev/null || true

log() {
    ts="$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '')"
    msg="[scrcpy $ts] $1"
    echo "$msg"
    echo "$msg" >> "$LOGFILE" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Xauthority discovery
# ---------------------------------------------------------------------------
find_xauth() {
    for pid in $(pgrep -x Xwayland 2>/dev/null; pgrep -x Xorg 2>/dev/null; pgrep -x Xvfb 2>/dev/null; pgrep -x X 2>/dev/null); do
        [ -r "/proc/$pid/cmdline" ] || continue
        cmdline="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
        auth="$(echo "$cmdline" | sed -n 's/.*-auth  *\([^ ]*\).*/\1/p')"
        if [ -n "$auth" ] && [ -r "$auth" ]; then
            echo "$auth"
            return 0
        fi
    done

    if [ -n "${XAUTHORITY:-}" ] && [ -r "$XAUTHORITY" ]; then
        echo "$XAUTHORITY"
        return 0
    fi

    for pattern in \
        "${XDG_RUNTIME_DIR:-/tmp}"/.mutter-Xwaylandauth.* \
        "${XDG_RUNTIME_DIR:-/tmp}"/xauth_* \
        "${XDG_RUNTIME_DIR:-/tmp}"/.Xauthority \
        /tmp/xauth-*/* ; do
        for f in $pattern; do
            [ -r "$f" ] && { echo "$f"; return 0; }
        done
    done

    if [ -n "${HOME:-}" ] && [ -r "$HOME/.Xauthority" ]; then
        echo "$HOME/.Xauthority"
        return 0
    fi
    [ -r "/root/.Xauthority" ] && { echo "/root/.Xauthority"; return 0; }

    return 1
}

# ---------------------------------------------------------------------------
# Display backend detection
# ---------------------------------------------------------------------------
get_display_mode() {
    if [ -S "/run/display.sock" ] || [ -S "/data/local/tmp/display_daemon.sock" ] || \
       [ -n "${ANLAND_SOCKET:-}" ] || [ "${ANLAND:-}" = "1" ] || \
       grep -q "ANLAND=" /etc/environment 2>/dev/null || [ -n "${WAYLAND_DISPLAY:-}" ]; then
        echo "anland"
        return 0
    fi

    if [ -f "$DROIDSPACES_ENV" ]; then
        disp="$(sed -n "s/^export DISPLAY=['\"]\?\([^'\"]*\)['\"]\?$/\1/p" "$DROIDSPACES_ENV" | head -n1)"
        [ -n "$disp" ] && { echo "x11"; return 0; }
    fi
    if [ -n "${DISPLAY:-}" ]; then
        echo "x11"
        return 0
    fi
    for s in /tmp/.X11-unix/X*; do
        if [ -S "$s" ] || [ -e "$s" ]; then
            echo "x11"
            return 0
        fi
    done

    echo "none"
    return 1
}

configure_display_env() {
    mode="$1"

    # Find the active user's runtime directory containing a Wayland socket
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

    # Discover and export Xauthority
    xauth_file="$(find_xauth 2>/dev/null || true)"
    if [ -n "$xauth_file" ] && [ -r "$xauth_file" ]; then
        export XAUTHORITY="$xauth_file"
        chmod 644 "$xauth_file" 2>/dev/null || true
        if [ "$(id -u 2>/dev/null)" = "0" ] && [ "$xauth_file" != "/root/.Xauthority" ]; then
            cp -f "$xauth_file" /root/.Xauthority 2>/dev/null || true
            chmod 600 /root/.Xauthority 2>/dev/null || true
        fi
    fi

    # Discover X11 display socket
    if [ -z "${DISPLAY:-}" ]; then
        for s in /tmp/.X11-unix/X*; do
            if [ -S "$s" ] || [ -e "$s" ]; then
                num="$(basename "$s" | sed 's/^X//')"
                [ -n "$num" ] && export DISPLAY=":$num" && break
            fi
        done
    fi
    export DISPLAY="${DISPLAY:-:0}"

    case "$mode" in
        anland)
            export ANLAND=1
            export ANLAND_SOCKET="${ANLAND_SOCKET:-/run/display.sock}"
            export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-wayland}"
            ;;
        x11)
            if [ -f "$DROIDSPACES_ENV" ]; then
                disp="$(sed -n "s/^export DISPLAY=['\"]\?\([^'\"]*\)['\"]\?$/\1/p" "$DROIDSPACES_ENV" | head -n1)"
                [ -n "$disp" ] && export DISPLAY="$disp"
            fi
            ;;
        *)
            ;;
    esac
}

# ---------------------------------------------------------------------------
have_scrcpy() { command -v scrcpy >/dev/null 2>&1; }
have_adb() { command -v adb >/dev/null 2>&1; }

install_scrcpy() {
    if have_scrcpy && have_adb; then
        log "scrcpy and adb already installed: $(scrcpy --version 2>&1 | head -n1)"
        return 0
    fi

    # Ensure curl runs quietly during postinst downloads
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
        log "Installing scrcpy and adb via apt-get..."
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq scrcpy adb || DEBIAN_FRONTEND=noninteractive apt-get install -y -qq scrcpy android-tools-adb || true
    elif command -v dnf >/dev/null 2>&1; then
        log "Installing scrcpy via dnf..."
        dnf install -y scrcpy android-tools
    elif command -v pacman >/dev/null 2>&1; then
        log "Installing scrcpy via pacman..."
        pacman -Sy --noconfirm scrcpy android-tools
    elif command -v apk >/dev/null 2>&1; then
        log "Installing scrcpy via apk..."
        apk add --no-cache scrcpy android-tools
    elif command -v zypper >/dev/null 2>&1; then
        log "Installing scrcpy via zypper..."
        zypper --non-interactive install scrcpy android-tools
    else
        log "No supported package manager found (apt/dnf/pacman/apk/zypper)"
        return 1
    fi

    if have_scrcpy && have_adb; then
        log "scrcpy installed successfully: $(scrcpy --version 2>&1 | head -n1)"
        return 0
    fi
    log "Install completed but scrcpy or adb is still missing"
    return 1
}

# ---------------------------------------------------------------------------
is_running() {
    [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null
}

prepare_adb() {
    target_ip="${1:-172.28.0.1}"
    port="${2:-5555}"

    # Generate adb keys if not already present
    mkdir -p "$HOME/.android" /root/.android 2>/dev/null || true
    if [ ! -f "$HOME/.android/adbkey" ] && [ ! -f /root/.android/adbkey ]; then
        adb keygen "$HOME/.android/adbkey" 2>/dev/null || adb keygen /root/.android/adbkey 2>/dev/null || true
    fi

    # Start adb server
    adb start-server >/dev/null 2>&1 || true

    # Connect with retry
    connected=0
    for _ in 1 2 3 4 5; do
        res="$(adb connect "$target_ip:$port" 2>&1)"
        log "adb connect $target_ip:$port: $res"
        if adb devices 2>/dev/null | grep "$target_ip:$port" | grep -qv "offline"; then
            connected=1
            break
        fi
        sleep 1
    done

    if [ "$connected" = "0" ]; then
        log "Warning: adb connect to $target_ip:$port was not recognized as online yet -- check ADB authorization dialog"
    fi
}

launch_scrcpy() {
    port="${1:-5555}"
    max_size="${2:-0}"
    bitrate_kbps="${3:-8000}"
    no_audio="${4:-0}"
    turn_screen_off="${5:-0}"
    stay_awake="${6:-0}"
    max_fps="${7:-0}"
    extra_args="${8:-}"

    if is_running; then
        log "scrcpy is already running (pid $(cat "$PIDFILE"))"
        return 0
    fi

    have_scrcpy || { log "scrcpy is not installed"; return 1; }
    have_adb || { log "adb is not installed"; return 1; }

    mode="$(get_display_mode)"
    if [ "$mode" = "none" ]; then
        log "No display session detected (neither anland nor X11) -- nothing to open a window in"
        return 1
    fi
    configure_display_env "$mode"
    log "Display mode: $mode (DISPLAY=${DISPLAY:-unset} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-unset} XAUTHORITY=${XAUTHORITY:-unset})"

    prepare_adb "172.28.0.1" "$port"

    scrcpy_args="-s 172.28.0.1:$port --window-title=Droidspaces"
    [ "$max_size" -gt 0 ] 2>/dev/null && scrcpy_args="$scrcpy_args --max-size=$max_size"
    [ "$bitrate_kbps" -gt 0 ] 2>/dev/null && scrcpy_args="$scrcpy_args --video-bit-rate=${bitrate_kbps}K"
    [ "$no_audio" = "1" ] && scrcpy_args="$scrcpy_args --no-audio"
    [ "$turn_screen_off" = "1" ] && scrcpy_args="$scrcpy_args --turn-screen-off"
    [ "$stay_awake" = "1" ] && scrcpy_args="$scrcpy_args --stay-awake"
    [ "$max_fps" -gt 0 ] 2>/dev/null && scrcpy_args="$scrcpy_args --max-fps=$max_fps"
    [ -n "$extra_args" ] && scrcpy_args="$scrcpy_args $extra_args"

    log "Launching: scrcpy $scrcpy_args"
    # Attempt 1: Direct launch (SDL detects Wayland or X11)
    # shellcheck disable=SC2086
    scrcpy $scrcpy_args >>"$LOGFILE" 2>&1 &
    new_pid=$!

    sleep 1
    if kill -0 "$new_pid" 2>/dev/null; then
        echo "$new_pid" > "$PIDFILE"
        log "scrcpy started successfully (pid $new_pid)"
        return 0
    fi

    # Attempt 2: Explicit Wayland video driver
    log "Retrying with explicit SDL_VIDEODRIVER=wayland..."
    # shellcheck disable=SC2086
    SDL_VIDEODRIVER=wayland scrcpy $scrcpy_args >>"$LOGFILE" 2>&1 &
    new_pid=$!
    sleep 1
    if kill -0 "$new_pid" 2>/dev/null; then
        echo "$new_pid" > "$PIDFILE"
        log "scrcpy started via Wayland backend (pid $new_pid)"
        return 0
    fi

    # Attempt 3: Explicit X11 / Xwayland video driver
    log "Retrying with X11 backend (DISPLAY=${DISPLAY:-:0})..."
    # shellcheck disable=SC2086
    DISPLAY="${DISPLAY:-:0}" SDL_VIDEODRIVER=x11 scrcpy $scrcpy_args >>"$LOGFILE" 2>&1 &
    new_pid=$!
    sleep 1
    if kill -0 "$new_pid" 2>/dev/null; then
        echo "$new_pid" > "$PIDFILE"
        log "scrcpy started via X11 backend (pid $new_pid)"
        return 0
    fi

    # Attempt 4: Software rendering fallback + audio disabled
    log "Retrying with software render fallback (LIBGL_ALWAYS_SOFTWARE=1 --no-audio)..."
    # shellcheck disable=SC2086
    LIBGL_ALWAYS_SOFTWARE=1 SDL_RENDER_DRIVER=software DISPLAY="${DISPLAY:-:0}" scrcpy $scrcpy_args --no-audio >>"$LOGFILE" 2>&1 &
    new_pid=$!
    sleep 1
    if kill -0 "$new_pid" 2>/dev/null; then
        echo "$new_pid" > "$PIDFILE"
        log "scrcpy started via software render (pid $new_pid)"
        return 0
    fi

    log "scrcpy did not stay running -- check $LOGFILE"
    tail -n 20 "$LOGFILE" 2>/dev/null || true
    return 1
}

stop_scrcpy() {
    if [ -f "$PIDFILE" ]; then
        pid="$(cat "$PIDFILE" 2>/dev/null)"
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            log "Stopping scrcpy (pid $pid)"
            kill -TERM "$pid" 2>/dev/null
            i=0
            while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 10 ]; do
                sleep 0.5
                i=$((i + 1))
            done
            kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null
        fi
        rm -f "$PIDFILE"
    fi
    pkill -x scrcpy 2>/dev/null || true
    log "scrcpy stopped"
}

# ---------------------------------------------------------------------------
status() {
    installed=0
    have_scrcpy && have_adb && installed=1

    mode="$(get_display_mode)"

    running=0
    is_running && running=1

    adb_connected=0
    if have_adb && adb devices 2>/dev/null | grep "172.28.0.1:" | grep -qv "offline"; then
        adb_connected=1
    fi

    echo "installed=$installed"
    echo "display_mode=$mode"
    echo "adb_connected=$adb_connected"
    echo "running=$running"
    echo "pid=$(cat "$PIDFILE" 2>/dev/null)"
}

# ---------------------------------------------------------------------------
cmd="${1:-}"
shift 2>/dev/null || true

case "$cmd" in
    check-installed)
        if have_scrcpy; then
            command -v scrcpy
            exit 0
        fi
        exit 1
        ;;
    install) install_scrcpy ;;
    get-display)
        mode="$(get_display_mode)"
        configure_display_env "$mode"
        echo "mode=$mode"
        echo "display=${DISPLAY:-}"
        echo "wayland_display=${WAYLAND_DISPLAY:-}"
        echo "xdg_runtime_dir=${XDG_RUNTIME_DIR:-}"
        ;;
    launch) launch_scrcpy "${1:-5555}" "${2:-0}" "${3:-8000}" "${4:-0}" "${5:-0}" "${6:-0}" "${7:-0}" "${8:-}" ;;
    stop-now) stop_scrcpy ;;
    status) status ;;
    get-log) cat "$LOGFILE" 2>/dev/null ;;
    *)
        echo "Usage: $0 {check-installed|install|get-display|launch <adb_port> <max_size> <bitrate_kbps> <no_audio> <turn_screen_off> <stay_awake> <max_fps> [extra_args]|stop-now|status|get-log}" >&2
        exit 2
        ;;
esac
