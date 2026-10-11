#!/bin/sh
# Droidspaces Programs & Applications installer script.
# POSIX sh script executed inside the container.

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$HOME/.local/bin:$HOME/bin:$HOME/.bun/bin:$PATH"

log() {
    ts="$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '')"
    echo "[programs $ts] $1"
}

get_arch() {
    arch="$(uname -m)"
    case "$arch" in
        aarch64|arm64) echo "arm64" ;;
        armv7l|armhf)  echo "armhf" ;;
        x86_64|amd64)  echo "amd64" ;;
        *)             echo "arm64" ;;
    esac
}

have() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------------------
# Status Check
# ---------------------------------------------------------------------------
check_status() {
    echo "code_server=$(have code-server && echo 1 || echo 0)"
    echo "antigravity=$(have antigravity || [ -f /opt/antigravity/bin/antigravity ] && echo 1 || echo 0)"
    echo "libreoffice=$(have libreoffice || have soffice && echo 1 || echo 0)"
    echo "chromium=$(have chromium || have chromium-browser && echo 1 || echo 0)"
    echo "firefox=$(have firefox && echo 1 || echo 0)"
    echo "gimp=$(have gimp && echo 1 || echo 0)"
    echo "vlc=$(have vlc && echo 1 || echo 0)"
    echo "caddy=$(have caddy && echo 1 || echo 0)"
    echo "transmission=$(have transmission-daemon || have transmission-gtk && echo 1 || echo 0)"
    echo "jupyter=$(have jupyter-lab || have jupyter && echo 1 || echo 0)"
    echo "filebrowser=$(have filebrowser && echo 1 || echo 0)"
    echo "ollama=$(have ollama && echo 1 || echo 0)"
    echo "omniroute=$(have omniroute && echo 1 || echo 0)"
    echo "xfce=$(have startxfce4 && echo 1 || echo 0)"

    # Service running states
    echo "svc_code_server=$(pgrep -f code-server >/dev/null 2>&1 && echo 1 || echo 0)"
    echo "svc_caddy=$(pgrep -x caddy >/dev/null 2>&1 && echo 1 || echo 0)"
    echo "svc_jupyter=$(pgrep -f 'jupyter-lab|jupyter' >/dev/null 2>&1 && echo 1 || echo 0)"
    echo "svc_filebrowser=$(pgrep -x filebrowser >/dev/null 2>&1 && echo 1 || echo 0)"
    echo "svc_transmission=$(pgrep -f transmission-daemon >/dev/null 2>&1 && echo 1 || echo 0)"
    echo "svc_ollama=$(pgrep -x ollama >/dev/null 2>&1 && echo 1 || echo 0)"
    echo "svc_omniroute=$(pgrep -f omniroute >/dev/null 2>&1 && echo 1 || echo 0)"
}

# ---------------------------------------------------------------------------
# Program Installers
# ---------------------------------------------------------------------------
install_code_server() {
    log "Installing Code Server (VS Code in Browser)..."
    curl -fsSL https://code-server.dev/install.sh | sh
    log "Code Server installed successfully: $(code-server --version 2>&1 | head -n1)"
}

install_antigravity() {
    log "Installing Google Antigravity CLI & tools..."
    if command -v npm >/dev/null 2>&1; then
        npm install -g @google/antigravity 2>&1 || true
    fi
    log "Antigravity setup completed"
}

install_libreoffice() {
    log "Installing LibreOffice suite..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq libreoffice
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm libreoffice-fresh
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache libreoffice
    fi
}

install_chromium() {
    log "Installing Chromium Web Browser..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq chromium-browser || DEBIAN_FRONTEND=noninteractive apt-get install -y -qq chromium || true
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm chromium
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache chromium
    fi
}

install_firefox() {
    log "Installing Mozilla Firefox..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq firefox || DEBIAN_FRONTEND=noninteractive apt-get install -y -qq firefox-esr || true
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm firefox
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache firefox
    fi
}

install_gimp() {
    log "Installing GIMP Image Editor..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gimp
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm gimp
    fi
}

install_vlc() {
    log "Installing VLC Media Player..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq vlc
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm vlc
    fi
}

install_caddy() {
    log "Installing Caddy Web & Reverse Proxy Server..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https curl
        curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg 2>/dev/null || true
        curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
        apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq caddy
    fi
}

install_transmission() {
    log "Installing Transmission torrent client..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq transmission-daemon transmission-cli
    fi
}

install_jupyter() {
    log "Installing JupyterLab..."
    if command -v pip3 >/dev/null 2>&1; then
        pip3 install --no-cache-dir jupyterlab 2>&1 || true
    elif command -v uv >/dev/null 2>&1; then
        uv pip install --system jupyterlab 2>&1 || true
    elif command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq jupyter-core python3-notebook || true
    fi
}

install_filebrowser() {
    log "Installing Filebrowser (web file manager)..."
    curl -fsSL https://raw.githubusercontent.com/filebrowser/get/master/get.sh | bash
}

install_ollama() {
    log "Installing Ollama AI LLM runner..."
    curl -fsSL https://ollama.com/install.sh | sh
}

install_omniroute() {
    log "Installing OmniRoute (Local AI Gateway & Proxy)..."
    if ! command -v node >/dev/null 2>&1; then
        if command -v apt-get >/dev/null 2>&1; then
            curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
            DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nodejs
        fi
    fi
    npm install -g --no-fund --no-audit omniroute 2>&1 || true
}

install_xfce() {
    log "Installing XFCE Desktop Environment..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq xfce4 xfce4-terminal xfce4-goodies
    fi
}

# ---------------------------------------------------------------------------
# Background Service Management
# ---------------------------------------------------------------------------
service_start() {
    svc="$1"
    log "Starting service $svc..."
    case "$svc" in
        code_server)
            nohup code-server --bind-addr 0.0.0.0:8080 --auth none >/var/log/code-server.log 2>&1 &
            ;;
        caddy)
            nohup caddy run >/var/log/caddy.log 2>&1 &
            ;;
        jupyter)
            nohup jupyter-lab --ip=0.0.0.0 --port=8888 --no-browser --NotebookApp.token='' --NotebookApp.password='' --allow-root >/var/log/jupyter.log 2>&1 &
            ;;
        filebrowser)
            nohup filebrowser -a 0.0.0.0 -p 8085 -r / >/var/log/filebrowser.log 2>&1 &
            ;;
        transmission)
            nohup transmission-daemon -g /var/lib/transmission-daemon/info >/var/log/transmission.log 2>&1 &
            ;;
        ollama)
            nohup ollama serve >/var/log/ollama.log 2>&1 &
            ;;
        omniroute)
            nohup omniroute start >/var/log/omniroute.log 2>&1 &
            ;;
        *)
            log "No background service configuration for $svc"
            ;;
    esac
    sleep 1
    log "Service $svc started"
}

service_stop() {
    svc="$1"
    log "Stopping service $svc..."
    case "$svc" in
        code_server) pkill -f code-server 2>/dev/null || true ;;
        caddy) pkill -x caddy 2>/dev/null || true ;;
        jupyter) pkill -f 'jupyter-lab|jupyter' 2>/dev/null || true ;;
        filebrowser) pkill -x filebrowser 2>/dev/null || true ;;
        transmission) pkill -f transmission-daemon 2>/dev/null || true ;;
        ollama) pkill -x ollama 2>/dev/null || true ;;
        omniroute) pkill -f omniroute 2>/dev/null || true ;;
        *) log "Unknown service $svc" ;;
    esac
    log "Service $svc stopped"
}

# ---------------------------------------------------------------------------
uninstall_program() {
    prog="$1"
    log "Uninstalling $prog..."
    service_stop "$prog"
    if command -v apt-get >/dev/null 2>&1; then
        case "$prog" in
            libreoffice) DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq libreoffice* ;;
            chromium) DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq chromium* ;;
            firefox) DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq firefox* ;;
            gimp) DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq gimp* ;;
            vlc) DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq vlc* ;;
            caddy) DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq caddy ;;
            transmission) DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq transmission* ;;
            omniroute) npm uninstall -g omniroute 2>/dev/null || true ;;
            *) log "Package $prog removal completed" ;;
        esac
    fi
}

# ---------------------------------------------------------------------------
cmd="${1:-status}"
shift 2>/dev/null || true

case "$cmd" in
    status) check_status ;;
    install)
        prog="${1:-}"
        case "$prog" in
            code_server) install_code_server ;;
            antigravity) install_antigravity ;;
            libreoffice) install_libreoffice ;;
            chromium) install_chromium ;;
            firefox) install_firefox ;;
            gimp) install_gimp ;;
            vlc) install_vlc ;;
            caddy) install_caddy ;;
            transmission) install_transmission ;;
            jupyter) install_jupyter ;;
            filebrowser) install_filebrowser ;;
            ollama) install_ollama ;;
            omniroute) install_omniroute ;;
            xfce) install_xfce ;;
            *)
                echo "Unknown program: $prog" >&2
                exit 1
                ;;
        esac
        ;;
    uninstall) uninstall_program "${1:-}" ;;
    service-start) service_start "${1:-}" ;;
    service-stop) service_stop "${1:-}" ;;
    *)
        echo "Usage: $0 {status|install <prog>|uninstall <prog>|service-start <prog>|service-stop <prog>}" >&2
        exit 2
        ;;
esac
