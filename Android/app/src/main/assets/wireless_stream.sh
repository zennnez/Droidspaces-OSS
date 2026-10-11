#!/bin/sh
# Droidspaces Wireless Display -- STREAM backend control script.
# Runs INSIDE the container (via `droidspaces --name=X run 'sh -s -- <args>'`),
# so it must stay POSIX sh -- no bashisms, no assumption of a specific distro.
#
# Pipeline: X11 capture -> H.264 encode -> MPEG-TS over HTTP / TCP.
#
# Subcommands:
#   detect-caps                              key=value capability report
#   install                                  install ffmpeg (+ gstreamer/v4l-utils if useful)
#   watch <port> <lan_only> <fps> <bitrate_kbps> <preset> <resolution>
#                                             foreground loop
#   service <action> [args...]               start/stop/restart/status via systemd, OpenRC, or setsid
#   stop-now                                 clean, immediate shutdown
#   status                                   key=value status report
#   get-log                                  print the contents of encoder.log

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

CONTROL_DIR="/run/droidspaces-stream"
PIDFILE="$CONTROL_DIR/encoder.pid"
LOGFILE="$CONTROL_DIR/encoder.log"
PROGRESSFILE="$CONTROL_DIR/progress.txt"
ENGINEFILE="$CONTROL_DIR/engine.txt"
DROIDSPACES_ENV="/run/droidspaces.env"
POLL_INTERVAL=2

mkdir -p "$CONTROL_DIR" 2>/dev/null || true

log() {
    ts="$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '')"
    msg="[STREAM $ts] $1"
    echo "$msg"
    echo "$msg" >> "$LOGFILE" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# DISPLAY discovery
# ---------------------------------------------------------------------------
get_display() {
    # Check if Anland Wayland display daemon is active or configured
    if [ -S "/run/display.sock" ] || [ -S "/data/local/tmp/display_daemon.sock" ] || [ -n "${ANLAND_SOCKET:-}" ] || [ "${ANLAND:-}" = "1" ] || grep -q "ANLAND=" /etc/environment 2>/dev/null; then
        export ANLAND=1
        export ANLAND_SOCKET="${ANLAND_SOCKET:-/run/display.sock}"
        export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
        export ANLAND_DRM_DEVICE="${ANLAND_DRM_DEVICE:-/dev/dri/renderD128}"
        export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-wayland}"
        # For Anland/Xwayland compositor, :0 is always the primary display socket
        if [ -S "/tmp/.X11-unix/X0" ] || [ -e "/tmp/.X11-unix/X0" ] || [ -S "/run/display.sock" ]; then
            echo ":0"
            return 0
        fi
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
    if [ -S "/tmp/.X11-unix/X0" ] || [ -e "/tmp/.X11-unix/X0" ]; then
        echo ":0"
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

    # Fallback to :0
    echo ":0"
    return 0
}

# ---------------------------------------------------------------------------
# Xauthority discovery
# ---------------------------------------------------------------------------
# script -- `xauth_file="$(find_xauth 2>/dev/null || true)"` silently failed
# every time (undefined function -> exit 127, swallowed by 2>/dev/null || true),
# so XAUTHORITY was never set and ffmpeg's x11grab always hit "Authorization
# required, but no authorization protocol specified".
find_xauth() {
    # Most reliable: read the -auth flag directly off the actually-running X
    # server (Xwayland under a Wayland/Anland session, or Xorg/Xvfb otherwise)
    # rather than guessing a path convention -- whatever launched it always
    # passes -auth <cookie-file> explicitly.
    for pid in $(pgrep -x Xwayland 2>/dev/null; pgrep -x Xorg 2>/dev/null; pgrep -x Xvfb 2>/dev/null; pgrep -x X 2>/dev/null); do
        [ -r "/proc/$pid/cmdline" ] || continue
        cmdline="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
        auth="$(echo "$cmdline" | sed -n 's/.*-auth  *\([^ ]*\).*/\1/p')"
        if [ -n "$auth" ] && [ -r "$auth" ]; then
            echo "$auth"
            return 0
        fi
    done

    # Already set and valid in the environment
    if [ -n "${XAUTHORITY:-}" ] && [ -r "$XAUTHORITY" ]; then
        echo "$XAUTHORITY"
        return 0
    fi

    # Common Xwayland/session temp-auth file conventions
    for pattern in \
        "${XDG_RUNTIME_DIR:-/tmp}"/.mutter-Xwaylandauth.* \
        "${XDG_RUNTIME_DIR:-/tmp}"/xauth_* \
        "${XDG_RUNTIME_DIR:-/tmp}"/.Xauthority \
        /tmp/xauth-*/* ; do
        for f in $pattern; do
            [ -r "$f" ] && { echo "$f"; return 0; }
        done
    done

    # Per-user home directories
    if [ -n "${HOME:-}" ] && [ -r "$HOME/.Xauthority" ]; then
        echo "$HOME/.Xauthority"
        return 0
    fi
    [ -r "/root/.Xauthority" ] && { echo "/root/.Xauthority"; return 0; }

    # Last resort: shallow search of the usual runtime dirs
    found="$(find /tmp /run "${XDG_RUNTIME_DIR:-/run/user/0}" -maxdepth 3 -iname '*xauth*' -type f 2>/dev/null | head -n1)"
    if [ -n "$found" ] && [ -r "$found" ]; then
        echo "$found"
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

# ---------------------------------------------------------------------------
# Capability detection
# ---------------------------------------------------------------------------
have_ffmpeg_libx264() {
    command -v ffmpeg >/dev/null 2>&1 && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q "libx264"
}

have_ffmpeg_v4l2m2m() {
    command -v ffmpeg >/dev/null 2>&1 && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q "h264_v4l2m2m"
}

have_ffmpeg_vaapi() {
    command -v ffmpeg >/dev/null 2>&1 && [ -e "/dev/dri/renderD128" ] && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q "h264_vaapi"
}

have_gstreamer_pipeline() {
    command -v gst-launch-1.0 >/dev/null 2>&1 || return 1
    command -v gst-inspect-1.0 >/dev/null 2>&1 || return 1
    for elem in ximagesrc x264enc mpegtsmux tcpserversink videoconvert videoscale; do
        gst-inspect-1.0 "$elem" >/dev/null 2>&1 || return 1
    done
    return 0
}

detect_hw_encoder() {
    if command -v v4l2-ctl >/dev/null 2>&1; then
        for dev in /dev/video*; do
            [ -c "$dev" ] || continue
            caps="$(v4l2-ctl -d "$dev" -D 2>/dev/null)"
            echo "$caps" | grep -qi "Memory-to-Memory" || continue
            fmts="$(v4l2-ctl -d "$dev" --list-formats 2>/dev/null)"
            if echo "$fmts" | grep -qi "H264"; then
                if ffmpeg -hide_banner -f lavfi -i nullsrc=s=128x128:d=0.1 -c:v h264_v4l2m2m -f null - >/dev/null 2>&1; then
                    echo "$dev"
                    return 0
                fi
            fi
        done
    fi
    if have_ffmpeg_v4l2m2m; then
        for dev in /dev/video*; do
            [ -c "$dev" ] || continue
            if ffmpeg -hide_banner -f lavfi -i nullsrc=s=128x128:d=0.1 -c:v h264_v4l2m2m -f null - >/dev/null 2>&1; then
                echo "$dev"
                return 0
            fi
        done
    fi
    return 1
}

detect_caps() {
    ffmpeg_ok=0
    have_ffmpeg_libx264 && ffmpeg_ok=1
    v4l2m2m_compiled=0
    have_ffmpeg_v4l2m2m && v4l2m2m_compiled=1
    gst_ok=0
    have_gstreamer_pipeline && gst_ok=1
    wf_recorder_ok=0
    command -v wf-recorder >/dev/null 2>&1 && wf_recorder_ok=1
    hw_node="$(detect_hw_encoder 2>/dev/null)"
    hw_ok=0
    [ -n "$hw_node" ] && [ "$v4l2m2m_compiled" = "1" ] && hw_ok=1

    anland_active=0
    anland_sock=""
    if [ -S "/run/display.sock" ] || [ -S "/data/local/tmp/display_daemon.sock" ] || [ "${ANLAND:-}" = "1" ] || [ -n "${WAYLAND_DISPLAY:-}" ]; then
        anland_active=1
        anland_sock="/run/display.sock"
    fi

    engine="none"
    if [ "$anland_active" = "1" ] && [ "$wf_recorder_ok" = "1" ]; then
        engine="wf-recorder-wayland"
    elif [ "$hw_ok" = "1" ]; then
        engine="ffmpeg-hw"
    elif [ "$ffmpeg_ok" = "1" ]; then
        engine="ffmpeg-sw"
    elif [ "$gst_ok" = "1" ]; then
        engine="gstreamer-sw"
    fi

    echo "ffmpeg_installed=$ffmpeg_ok"
    echo "wf_recorder_installed=$wf_recorder_ok"
    echo "gstreamer_installed=$gst_ok"
    echo "hw_encoder_available=$hw_ok"
    echo "hw_encoder_node=${hw_node:-}"
    echo "chosen_engine=$engine"
    echo "anland_active=$anland_active"
    echo "anland_socket=$anland_sock"
}

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------
install_deps() {
    installed_any=0
    if command -v apt-get > /dev/null 2>&1; then
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
        apt-get update -qq
        log "Installing ffmpeg, wf-recorder, v4l-utils..."
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ffmpeg wf-recorder v4l-utils >/dev/null 2>&1 || DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ffmpeg v4l-utils >/dev/null 2>&1 || true
        installed_any=1
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y ffmpeg wf-recorder v4l-utils && installed_any=1
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm ffmpeg wf-recorder v4l-utils && installed_any=1
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache ffmpeg wf-recorder v4l-utils && installed_any=1
    elif command -v zypper >/dev/null 2>&1; then
        zypper --non-interactive install ffmpeg wf-recorder v4l-utils && installed_any=1
    else
        log "No supported package manager found"
        return 1
    fi

    if have_ffmpeg_libx264 || command -v wf-recorder >/dev/null 2>&1; then
        log "Video capture tools (ffmpeg/wf-recorder) are ready"
        return 0
    fi
    log "Install did not produce a working encoder"
    return 1
}

x264_preset_args() {
    preset="$1"; fps="$2"
    case "$preset" in
        low-latency) echo "-preset ultrafast -tune zerolatency -bf 0 -g $fps -x264-params keyint=${fps}:min-keyint=${fps}:scenecut=0:repeat-headers=1" ;;
        quality)     echo "-preset fast -g $((fps * 2)) -x264-params keyint=$((fps * 2)):repeat-headers=1" ;;
        balanced|*)  echo "-preset veryfast -tune zerolatency -g $((fps * 2)) -x264-params keyint=$((fps * 2)):repeat-headers=1" ;;
    esac
}

resolution_scale_filter() {
    case "$1" in
        1080p) echo "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2" ;;
        720p)  echo "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2" ;;
        480p)  echo "scale=854:480:force_original_aspect_ratio=decrease,pad=854:480:(ow-iw)/2:(oh-ih)/2" ;;
        native|*) echo "scale=trunc(iw/2)*2:trunc(ih/2)*2" ;;
    esac
}

# ---------------------------------------------------------------------------
is_running() {
    if [ -f "$PIDFILE" ]; then
        pid="$(cat "$PIDFILE" 2>/dev/null)"
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
    fi
    pgrep -x ffmpeg >/dev/null 2>&1
}

start_stream() {
    port="${1:-8554}"
    lan_only="${2:-1}"
    fps="${3:-30}"
    bitrate_kbps="${4:-4000}"
    preset="${5:-low-latency}"
    resolution="${6:-native}"
    forced_engine="${7:-auto}"

    if is_running; then
        log "Stream encoder is already running"
        return 0
    fi

    display="$(get_display)"
    log "Discovered DISPLAY=$display (engine preference: $forced_engine)"

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
        log "Configured stream pipeline for Anland display daemon (socket: $ANLAND_SOCKET, WAYLAND_DISPLAY: $WAYLAND_DISPLAY)"
    fi
    [ -n "$MESA_LOADER_DRIVER_OVERRIDE" ] && export MESA_LOADER_DRIVER_OVERRIDE
    [ -n "$TU_DEBUG" ] && export TU_DEBUG
    [ -n "$FD_DEV_FEATURES" ] && export FD_DEV_FEATURES

    # Discover Xauthority and permit local X connections
    xauth_file="$(find_xauth 2>/dev/null || true)"
    if [ -n "$xauth_file" ] && [ -r "$xauth_file" ]; then
        log "Found Xauthority: $xauth_file"
        export XAUTHORITY="$xauth_file"
        if [ "$(id -u 2>/dev/null)" = "0" ] && [ "$xauth_file" != "/root/.Xauthority" ]; then
            cp -f "$xauth_file" /root/.Xauthority 2>/dev/null || true
            chmod 600 /root/.Xauthority 2>/dev/null || true
        fi
        DISPLAY="$display" XAUTHORITY="$xauth_file" xhost +local: >/dev/null 2>&1 || DISPLAY="$display" XAUTHORITY="$xauth_file" xhost + >/dev/null 2>&1 || true
    else
        log "Warning: no readable Xauthority found -- x11grab will likely fail auth against $display"
        DISPLAY="$display" xhost +local: >/dev/null 2>&1 || DISPLAY="$display" xhost + >/dev/null 2>&1 || true
    fi
    export XAUTHLOCALHOSTNAME="${XAUTHLOCALHOSTNAME:-localhost}"

    mkdir -p "$CONTROL_DIR"

    # Detect GPU and hardware acceleration capabilities
    gpu_diag=""
    [ -c "/dev/kgsl-3d0" ] && gpu_diag="${gpu_diag}Adreno KGSL (/dev/kgsl-3d0) "
    [ -c "/dev/dri/renderD128" ] && gpu_diag="${gpu_diag}Mesa DRI Render (/dev/dri/renderD128) "
    v4l_cnt="$(ls /dev/video* 2>/dev/null | wc -l)"
    [ "$v4l_cnt" -gt 0 ] && gpu_diag="${gpu_diag}V4L2 Video nodes ($v4l_cnt devices) "
    log "Hardware / GPU status: ${gpu_diag:-CPU software (no direct DRM/KGSL nodes)}"

    hw_node="$(detect_hw_encoder 2>/dev/null)"
    use_hw=0
    if [ "$forced_engine" = "hw" ]; then
        use_hw=1
    elif [ "$forced_engine" != "sw" ] && [ -n "$hw_node" ] && have_ffmpeg_v4l2m2m; then
        use_hw=1
    fi

    scale_filter="$(resolution_scale_filter "$resolution")"
    vf_args="-vf $scale_filter"

    # Stop any running relay before creating new one
    pkill -f ws_server.py 2>/dev/null || true
    pkill -f "$CONTROL_DIR/ws_server.py" 2>/dev/null || true

    # Deploy embedded Web + WebSocket relay for live browser viewing
    cat << 'EOF' > "$CONTROL_DIR/ws_server.py"
import sys, os, time, socket, threading, hashlib, base64, struct
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

HTTP_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8554
INGEST_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8553
clients_http = []
clients_lock = threading.Lock()
header_buffer = bytearray()
HEADER_MAX_BYTES = 262144

HTML_PAGE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Droidspaces Live Stream</title>
<script src="https://cdn.jsdelivr.net/npm/mpegts.js@1.7.3/dist/mpegts.min.js"></script>
<script>
if (!window.mpegts) {
  document.write('<script src="https://unpkg.com/mpegts.js@1.7.3/dist/mpegts.min.js"><\\/script>');
}
if (!window.mpegts) {
  document.write('<script src="https://cdnjs.cloudflare.com/ajax/libs/mpegts.js/1.7.3/mpegts.min.js"><\\/script>');
}
</script>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { background: #0f172a; color: #f8fafc; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; padding: 16px; }
  .container { max-width: 1200px; width: 100%; display: flex; flex-direction: column; align-items: center; gap: 16px; }
  .header { width: 100%; display: flex; justify-content: space-between; align-items: center; padding: 12px 20px; background: rgba(30, 41, 59, 0.8); border-radius: 12px; border: 1px solid rgba(255,255,255,0.08); backdrop-filter: blur(8px); }
  .header h1 { font-size: 1.1rem; font-weight: 700; display: flex; align-items: center; gap: 8px; color: #38bdf8; }
  .badge { background: #22c55e; color: #000; font-weight: 700; font-size: 0.75rem; padding: 3px 8px; border-radius: 9999px; }
  .canvas-wrapper { position: relative; width: 100%; min-height: 480px; max-height: 80vh; background: #000; border-radius: 16px; overflow: hidden; box-shadow: 0 20px 25px -5px rgba(0,0,0,0.5); border: 1px solid rgba(255,255,255,0.1); display: flex; align-items: center; justify-content: center; }
  video { width: 100%; height: auto; max-height: 80vh; object-fit: contain; display: block; }
  .controls { display: flex; gap: 12px; align-items: center; }
  .btn { background: #334155; color: #f8fafc; border: 1px solid rgba(255,255,255,0.1); padding: 8px 16px; border-radius: 8px; cursor: pointer; font-size: 0.85rem; font-weight: 600; transition: all 0.2s; }
  .btn:hover { background: #475569; }
</style>
</head>
<body>
<div class="container">
  <div class="header">
    <h1><span>📺</span> Droidspaces Display Mirror</h1>
    <div class="controls">
      <span class="badge" id="status">LIVE</span>
      <button class="btn" onclick="retryStream()">↻ Reconnect</button>
      <button class="btn" onclick="toggleFullscreen()">⛶ Fullscreen</button>
    </div>
  </div>
  <div class="canvas-wrapper" id="canvas-box">
    <video id="player" autoplay playsinline muted></video>
  </div>
</div>
<script>
  const video = document.getElementById('player');
  const statusBadge = document.getElementById('status');
  const streamUrl = new URL('/stream.ts?t=' + Date.now(), window.location.href).href;
  let player = null;
  
  function toggleFullscreen() {
    const box = document.getElementById('canvas-box');
    if (!document.fullscreenElement) {
      box.requestFullscreen ? box.requestFullscreen() : box.webkitRequestFullscreen();
    } else {
      document.exitFullscreen ? document.exitFullscreen() : document.webkitExitFullscreen();
    }
  }

  function startPlayback() {
    if (player) {
      try { player.destroy(); } catch(e) {}
      player = null;
    }
    video.muted = true;
    if (window.mpegts && mpegts.getFeatureList().mseLivePlayback) {
      try {
        player = mpegts.createPlayer({
          type: 'mse',
          isLive: true,
          url: streamUrl
        }, {
          enableWorker: false,
          enableStashBuffer: false,
          stashInitialSize: 32,
          liveBufferLatencyChasing: true,
          autoCleanupSourceBuffer: true,
          lazyLoad: false
        });
        player.attachMediaElement(video);
        player.load();
        player.play().then(() => {
          statusBadge.innerText = 'LIVE';
          statusBadge.style.background = '#22c55e';
        }).catch(() => {});
        player.on(mpegts.Events.ERROR, (e) => {
          console.warn('mpegts error:', e);
          statusBadge.innerText = 'CONNECTING...';
          statusBadge.style.background = '#eab308';
          setTimeout(startPlayback, 2000);
        });
      } catch (e) {
        console.error('MSE player failed, falling back to native video:', e);
        video.src = streamUrl;
        video.play().catch(() => {});
      }
    } else {
      video.src = streamUrl;
      video.play().catch(() => {});
    }
  }

  function retryStream() {
    startPlayback();
  }

  window.addEventListener('load', startPlayback);
</script>
</body>
</html>
"""

class StreamHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def do_GET(self):
        if self.path == '/' or self.path == '/index.html':
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(HTML_PAGE.encode('utf-8'))))
            self.end_headers()
            self.wfile.write(HTML_PAGE.encode('utf-8'))
        elif self.path.startswith('/stream.ts') or self.path.startswith('/live'):
            self.send_response(200)
            self.send_header('Content-Type', 'video/mp2t')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
            self.send_header('Connection', 'close')
            self.end_headers()
            with clients_lock:
                if header_buffer:
                    try:
                        self.wfile.write(header_buffer)
                        self.wfile.flush()
                    except Exception:
                        return
                clients_http.append(self.wfile)
            try:
                while True:
                    time.sleep(1)
            except Exception:
                pass
            finally:
                with clients_lock:
                    if self.wfile in clients_http:
                        clients_http.remove(self.wfile)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        return

def run_http():
    ThreadingHTTPServer.allow_reuse_address = True
    server = ThreadingHTTPServer(('0.0.0.0', HTTP_PORT), StreamHandler)
    server.serve_forever()

def run_ingest():
    global header_buffer
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('127.0.0.1', INGEST_PORT))
    s.listen(1)
    while True:
        try:
            conn, _ = s.accept()
            while True:
                data = conn.recv(65536)
                if not data:
                    break
                with clients_lock:
                    if len(header_buffer) < HEADER_MAX_BYTES:
                        header_buffer.extend(data[:HEADER_MAX_BYTES - len(header_buffer)])
                    dead = []
                    for c in clients_http:
                        try:
                            c.write(data)
                            c.flush()
                        except Exception:
                            dead.append(c)
                    for d in dead:
                        if d in clients_http:
                            clients_http.remove(d)
            conn.close()
        except Exception:
            time.sleep(0.5)

threading.Thread(target=run_http, daemon=True).start()
run_ingest()
EOF

    # Start relay if python3 is available
    ingest_target="http://0.0.0.0:$port"
    if command -v python3 >/dev/null 2>&1; then
        python3 "$CONTROL_DIR/ws_server.py" "$port" 8553 >>"$LOGFILE" 2>&1 &
        echo $! > "$CONTROL_DIR/relay.pid"
        ingest_target="tcp://127.0.0.1:8553"
        # Wait up to 3 seconds for Python relay to open ingest port
        for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
            if (exec 3<>/dev/tcp/127.0.0.1/8553) 2>/dev/null; then
                exec 3<&-
                exec 3>&-
                break
            fi
            sleep 0.2
        done
        log "Started live HTTP/browser stream relay on port $port"
    fi

    is_wayland=0
    if [ -n "${WAYLAND_DISPLAY:-}" ] && { [ -S "/run/display.sock" ] || [ -S "/data/local/tmp/display_daemon.sock" ] || [ "${ANLAND:-}" = "1" ]; }; then
        is_wayland=1
    fi

    new_pid=""
    if [ "$is_wayland" = "1" ] && command -v wf-recorder >/dev/null 2>&1; then
        engine="wf-recorder-wayland"
        echo "$engine" > "$ENGINEFILE"
        log "Starting wf-recorder (native Wayland compositor capture on $WAYLAND_DISPLAY) target=$ingest_target fps=$fps"
        # shellcheck disable=SC2086
        wf-recorder -c libx264 -p preset=ultrafast -p tune=zerolatency -p keyint="$fps" -p repeat-headers=1 -p b_strategy=0 -F "fps=$fps" -m mpegts -f "$ingest_target" >>"$LOGFILE" 2>&1 &
        new_pid=$!
        sleep 1
        if ! kill -0 "$new_pid" 2>/dev/null; then
            log "wf-recorder did not stay running (screencopy protocol unavailable), falling back to X11/Xwayland capture"
            is_wayland=0
            new_pid=""
        fi
    fi

    if [ -z "$new_pid" ] && [ "$use_hw" = "1" ]; then
        engine="ffmpeg-hw"
        echo "$engine" > "$ENGINEFILE"
        log "Starting ffmpeg (HW encoder $hw_node) DISPLAY=$display target=$ingest_target fps=$fps bitrate=${bitrate_kbps}k"
        # Constrain dimensions for Qualcomm VPU to prevent ION buffer allocation failures on tall screens
        hw_vf_args="-vf scale='min(1920,iw)':'min(1080,ih)':force_original_aspect_ratio=decrease,scale=trunc(iw/2)*2:trunc(ih/2)*2,format=nv12"
        # shellcheck disable=SC2086
        ffmpeg -hide_banner -loglevel info -y \
            -f x11grab -draw_mouse 1 -framerate "$fps" -i "$display" \
            $hw_vf_args \
            -c:v h264_v4l2m2m -b:v "${bitrate_kbps}k" -g "$fps" -pix_fmt nv12 \
            -f mpegts -mpegts_flags +pat_pmt_at_frames+resend_headers -muxdelay 0.1 "$ingest_target" \
            -progress "$PROGRESSFILE" \
            >>"$LOGFILE" 2>&1 &
        new_pid=$!
        sleep 1
        if ! kill -0 "$new_pid" 2>/dev/null; then
            log "HW encoder failed to initialize (V4L2 buffer allocation failed), falling back to SW libx264 encoder"
            use_hw=0
            new_pid=""
        fi
    fi

    if [ -z "$new_pid" ] && have_ffmpeg_libx264; then
        engine="ffmpeg-sw"
        echo "$engine" > "$ENGINEFILE"
        preset_args="$(x264_preset_args "$preset" "$fps")"
        log "Starting ffmpeg (SW libx264) DISPLAY=$display target=$ingest_target fps=$fps bitrate=${bitrate_kbps}k preset=$preset"
        # shellcheck disable=SC2086
        ffmpeg -hide_banner -loglevel info -y \
            -f x11grab -draw_mouse 1 -framerate "$fps" -i "$display" \
            $vf_args \
            -c:v libx264 $preset_args -pix_fmt yuv420p -b:v "${bitrate_kbps}k" -maxrate "${bitrate_kbps}k" -bufsize "$((bitrate_kbps * 2))k" \
            -f mpegts -mpegts_flags +pat_pmt_at_frames+resend_headers -muxdelay 0.1 "$ingest_target" \
            -progress "$PROGRESSFILE" \
            >>"$LOGFILE" 2>&1 &
        new_pid=$!
    elif [ -z "$new_pid" ] && have_gstreamer_pipeline; then
        engine="gstreamer-sw"
        echo "$engine" > "$ENGINEFILE"
        log "Starting gstreamer (SW) DISPLAY=$display target=$ingest_target fps=$fps bitrate=${bitrate_kbps}k"
        # shellcheck disable=SC2086
        gst-launch-1.0 -q \
            ximagesrc display-name="$display" show-pointer=true ! \
            videoconvert ! \
            videoscale ! "$scale_filter" ! \
            video/x-raw,framerate="${fps}/1" ! \
            x264enc tune=zerolatency speed-preset=ultrafast bitrate="$bitrate_kbps" ! \
            mpegtsmux ! \
            tcpserversink host=0.0.0.0 port="$port" \
            >>"$LOGFILE" 2>&1 &
        new_pid=$!
    elif [ -z "$new_pid" ]; then
        log "No suitable encoder available"
        return 1
    fi

    sleep 1
    if kill -0 "$new_pid" 2>/dev/null; then
        echo "$new_pid" > "$PIDFILE"
        log "Stream encoder started successfully (pid $new_pid, engine=$engine) on port $port"
        return 0
    fi
    log "Encoder did not stay running. Log output:"
    tail -n 15 "$LOGFILE" 2>/dev/null || true
    return 1
}

stop_stream() {
    log "Stopping stream encoder..."
    if [ -f "$CONTROL_DIR/relay.pid" ]; then
        rpid="$(cat "$CONTROL_DIR/relay.pid" 2>/dev/null)"
        [ -n "$rpid" ] && kill "$rpid" 2>/dev/null || true
        rm -f "$CONTROL_DIR/relay.pid"
    fi
    pkill -f ws_server.py 2>/dev/null || true
    pkill -f "$CONTROL_DIR/ws_server.py" 2>/dev/null || true

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
    pkill -x ffmpeg 2>/dev/null || true
    pkill -x wf-recorder 2>/dev/null || true
    pkill -x gst-launch-1.0 2>/dev/null || true
    log "Stream encoder stopped"
}

watch_loop() {
    port="${1:-8554}"
    lan_only="${2:-1}"
    fps="${3:-30}"
    bitrate_kbps="${4:-4000}"
    preset="${5:-low-latency}"
    resolution="${6:-native}"
    forced_engine="${7:-auto}"

    trap 'log "Received shutdown signal"; stop_stream; exit 0' TERM INT

    log "Stream watch loop started (port=$port, fps=$fps, bitrate=${bitrate_kbps}k, engine=$forced_engine)..."
    while true; do
        if ! is_running; then
            start_stream "$port" "$lan_only" "$fps" "$bitrate_kbps" "$preset" "$resolution" "$forced_engine" || sleep 3
        fi
        sleep "$POLL_INTERVAL"
    done
}

service_action() {
    action="$1"
    shift
    if command -v systemctl >/dev/null 2>&1 && [ "$(cat /proc/1/comm 2>/dev/null)" = "systemd" ]; then
        systemctl daemon-reload 2>/dev/null || true
        case "$action" in
            start) systemctl start droidspaces-stream.service 2>&1 ;;
            stop) systemctl stop droidspaces-stream.service 2>&1 ;;
            restart) systemctl restart droidspaces-stream.service 2>&1 ;;
            status) systemctl status droidspaces-stream.service 2>&1 ;;
        esac
    elif command -v rc-service >/dev/null 2>&1 && [ -x /etc/init.d/droidspaces-stream ]; then
        case "$action" in
            start) rc-service droidspaces-stream start 2>&1 ;;
            stop) rc-service droidspaces-stream stop 2>&1 ;;
            restart) rc-service droidspaces-stream restart 2>&1 ;;
            status) rc-service droidspaces-stream status 2>&1 ;;
        esac
    else
        # Fallback to standalone background process
        case "$action" in
            start)
                stop_stream
                sleep 0.5
                setsid "$0" watch "$@" >>"$LOGFILE" 2>&1 &
                sleep 1
                status
                ;;
            stop)
                stop_stream
                pkill -f "wireless_stream.sh watch" 2>/dev/null || true
                status
                ;;
            restart)
                stop_stream
                pkill -f "wireless_stream.sh watch" 2>/dev/null || true
                sleep 0.5
                setsid "$0" watch "$@" >>"$LOGFILE" 2>&1 &
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
        pid="$(cat "$PIDFILE" 2>/dev/null || pgrep -x ffmpeg | head -n1)"
    fi
    engine=""
    [ -f "$ENGINEFILE" ] && engine="$(cat "$ENGINEFILE" 2>/dev/null)"

    live_fps=""
    live_bitrate_kbps=""
    if [ "$running" = "1" ] && [ -f "$PROGRESSFILE" ]; then
        live_fps="$(grep '^fps=' "$PROGRESSFILE" 2>/dev/null | tail -n1 | cut -d= -f2)"
        raw_bitrate="$(grep '^bitrate=' "$PROGRESSFILE" 2>/dev/null | tail -n1 | cut -d= -f2)"
        live_bitrate_kbps="$(echo "$raw_bitrate" | sed 's/kbits\/s//' | tr -d ' ')"
    fi

    installed=0
    have_ffmpeg_libx264 && installed=1

    anland_active=0
    if [ -S "/run/display.sock" ] || [ -S "/data/local/tmp/display_daemon.sock" ] || [ "${ANLAND:-}" = "1" ]; then
        anland_active=1
    fi

    echo "installed=$installed"
    echo "display=$display"
    echo "running=$running"
    echo "pid=$pid"
    echo "engine=$engine"
    echo "live_fps=$live_fps"
    echo "live_bitrate_kbps=$live_bitrate_kbps"
    echo "anland_active=$anland_active"
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
    detect-caps)
        detect_caps
        ;;
    install)
        install_deps
        ;;
    watch)
        watch_loop "${1:-8554}" "${2:-1}" "${3:-30}" "${4:-4000}" "${5:-low-latency}" "${6:-native}" "${7:-auto}"
        ;;
    service)
        service_action "$@"
        ;;
    stop-now)
        stop_stream
        ;;
    status)
        status
        ;;
    get-log)
        get_log
        ;;
    *)
        echo "Usage: $0 {detect-caps|install|watch <port> <lan_only> <fps> <bitrate_kbps> <preset> <resolution>|service <start|stop|restart|status> [args...]|stop-now|status|get-log}" >&2
        exit 2
        ;;
esac
