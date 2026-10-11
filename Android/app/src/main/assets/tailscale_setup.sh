#!/bin/sh
# Droidspaces Tailscale & Headscale control script.
# POSIX sh script executed inside the container.

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

log() {
    ts="$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '')"
    echo "[tailscale $ts] $1"
}

have_tailscale() { command -v tailscale >/dev/null 2>&1; }
have_tailscaled() { command -v tailscaled >/dev/null 2>&1; }

check_installed() {
    if have_tailscale && have_tailscaled; then
        tailscale version | head -n1
        exit 0
    fi
    exit 1
}

install_tailscale() {
    if have_tailscale && have_tailscaled; then
        log "Tailscale is already installed: $(tailscale version | head -n1)"
        return 0
    fi

    log "Installing Tailscale via official installer..."
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL https://tailscale.com/install.sh | sh
    elif command -v wget >/dev/null 2>&1; then
        wget -qO- https://tailscale.com/install.sh | sh
    else
        if command -v apt-get >/dev/null 2>&1; then
            DEBIAN_FRONTEND=noninteractive apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl
            curl -fsSL https://tailscale.com/install.sh | sh
        else
            log "Error: neither curl nor wget found to download Tailscale installer"
            return 1
        fi
    fi

    if have_tailscale; then
        log "Tailscale installed successfully: $(tailscale version | head -n1)"
        return 0
    fi
    log "Error: Tailscale installation failed"
    return 1
}

ensure_daemon() {
    if pgrep -x tailscaled >/dev/null 2>&1; then
        return 0
    fi

    if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
        systemctl enable --now tailscaled 2>/dev/null || true
    fi

    if ! pgrep -x tailscaled >/dev/null 2>&1; then
        log "Starting tailscaled daemon in background..."
        mkdir -p /var/run/tailscale /var/lib/tailscale /var/log 2>/dev/null || true
        tailscaled --state=/var/lib/tailscale/tailscaled.state --socket=/var/run/tailscale/tailscaled.sock >/var/log/tailscaled.log 2>&1 &
        sleep 2
    fi
}

tailscale_up() {
    login_server="${1:-}"
    authkey="${2:-}"
    hostname="${3:-}"
    accept_routes="${4:-1}"
    enable_ssh="${5:-0}"
    exit_node="${6:-}"
    adv_exit_node="${7:-0}"
    adv_routes="${8:-}"
    reset="${9:-0}"

    ensure_daemon

    cmd="tailscale up --timeout=30s"
    [ -n "$login_server" ] && cmd="$cmd --login-server=$login_server"
    [ -n "$authkey" ] && cmd="$cmd --authkey=$authkey"
    [ -n "$hostname" ] && cmd="$cmd --hostname=$hostname"
    [ "$accept_routes" = "1" ] && cmd="$cmd --accept-routes"
    [ "$enable_ssh" = "1" ] && cmd="$cmd --ssh"
    [ -n "$exit_node" ] && cmd="$cmd --exit-node=$exit_node"
    [ "$adv_exit_node" = "1" ] && cmd="$cmd --advertise-exit-node"
    [ -n "$adv_routes" ] && cmd="$cmd --advertise-routes=$adv_routes"
    [ "$reset" = "1" ] && cmd="$cmd --reset"

    log "Executing: $cmd"
    # shellcheck disable=SC2086
    eval $cmd
}

tailscale_down() {
    log "Bringing Tailscale down..."
    tailscale down 2>&1 || true
}

tailscale_ping() {
    target="${1:-100.100.100.100}"
    if have_tailscale; then
        tailscale ping -c 3 "$target" 2>&1
    else
        ping -c 3 "$target" 2>&1
    fi
}

tailscale_netcheck() {
    if have_tailscale; then
        tailscale netcheck 2>&1
    else
        echo "Tailscale is not installed"
        return 1
    fi
}

get_status() {
    installed=0
    have_tailscale && installed=1

    daemon_running=0
    pgrep -x tailscaled >/dev/null 2>&1 && daemon_running=1

    ip4="$(tailscale ip -4 2>/dev/null | head -n1 || echo '')"
    ip6="$(tailscale ip -6 2>/dev/null | head -n1 || echo '')"

    connected=0
    [ -n "$ip4" ] && connected=1

    backend_state="$(tailscale status --json 2>/dev/null | grep -o '"BackendState":"[^"]*"' | cut -d'"' -f4 || echo 'Unknown')"
    self_name="$(tailscale status --json 2>/dev/null | grep -o '"HostName":"[^"]*"' | head -n1 | cut -d'"' -f4 || echo '')"
    auth_url="$(tailscale status 2>&1 | grep -o 'https://[^ ]*' | head -n1 || echo '')"

    echo "installed=$installed"
    echo "daemon_running=$daemon_running"
    echo "connected=$connected"
    echo "ip4=$ip4"
    echo "ip6=$ip6"
    echo "backend_state=$backend_state"
    echo "hostname=$self_name"
    echo "auth_url=$auth_url"
}

# ---------------------------------------------------------------------------
# Headscale in Docker management helpers
# ---------------------------------------------------------------------------
find_headscale_container() {
    if command -v docker >/dev/null 2>&1; then
        docker ps --format '{{.Names}}' 2>/dev/null | grep -i headscale | head -n1
    fi
}

headscale_nodes() {
    c="$(find_headscale_container)"
    if [ -n "$c" ]; then
        docker exec "$c" headscale nodes list 2>&1 || docker exec "$c" headscale node list 2>&1
    elif command -v headscale >/dev/null 2>&1; then
        headscale nodes list 2>&1 || headscale node list 2>&1
    else
        echo "Headscale binary or Docker container not found"
        return 1
    fi
}

headscale_users() {
    c="$(find_headscale_container)"
    if [ -n "$c" ]; then
        docker exec "$c" headscale users list 2>&1 || docker exec "$c" headscale user list 2>&1
    elif command -v headscale >/dev/null 2>&1; then
        headscale users list 2>&1 || headscale user list 2>&1
    else
        echo "Headscale binary or Docker container not found"
        return 1
    fi
}

headscale_create_user() {
    username="${1:-}"
    [ -z "$username" ] && { echo "Username required"; return 1; }
    c="$(find_headscale_container)"
    if [ -n "$c" ]; then
        docker exec "$c" headscale users create "$username" 2>&1 || docker exec "$c" headscale user create "$username" 2>&1
    elif command -v headscale >/dev/null 2>&1; then
        headscale users create "$username" 2>&1 || headscale user create "$username" 2>&1
    else
        echo "Headscale binary or Docker container not found"
        return 1
    fi
}

headscale_create_key() {
    user="${1:-default}"
    expiry="${2:-24h}"
    reusable="${3:-0}"
    c="$(find_headscale_container)"
    reusable_flag=""
    [ "$reusable" = "1" ] && reusable_flag="--reusable"

    if [ -n "$c" ]; then
        docker exec "$c" headscale preauthkeys create -u "$user" --expiration "$expiry" $reusable_flag 2>&1 || \
        docker exec "$c" headscale preauthkeys create --user "$user" --expiration "$expiry" $reusable_flag 2>&1
    elif command -v headscale >/dev/null 2>&1; then
        headscale preauthkeys create -u "$user" --expiration "$expiry" $reusable_flag 2>&1 || \
        headscale preauthkeys create --user "$user" --expiration "$expiry" $reusable_flag 2>&1
    else
        echo "Headscale binary or Docker container not found"
        return 1
    fi
}

headscale_delete_node() {
    node_id="${1:-}"
    [ -z "$node_id" ] && { echo "Node ID required"; return 1; }
    c="$(find_headscale_container)"
    if [ -n "$c" ]; then
        docker exec "$c" headscale nodes delete -i "$node_id" --force 2>&1 || docker exec "$c" headscale node delete -i "$node_id" --force 2>&1
    elif command -v headscale >/dev/null 2>&1; then
        headscale nodes delete -i "$node_id" --force 2>&1 || headscale node delete -i "$node_id" --force 2>&1
    else
        echo "Headscale binary or Docker container not found"
        return 1
    fi
}

# ---------------------------------------------------------------------------
action="${1:-status}"
shift 2>/dev/null || true

case "$action" in
    check-installed) check_installed ;;
    install) install_tailscale ;;
    ensure-daemon) ensure_daemon ;;
    up) tailscale_up "${1:-}" "${2:-}" "${3:-}" "${4:-1}" "${5:-0}" "${6:-}" "${7:-0}" "${8:-}" "${9:-0}" ;;
    down) tailscale_down ;;
    ping) tailscale_ping "${1:-100.100.100.100}" ;;
    netcheck) tailscale_netcheck ;;
    status) get_status ;;
    full-status) tailscale status 2>&1 ;;
    headscale-nodes) headscale_nodes ;;
    headscale-users) headscale_users ;;
    headscale-create-user) headscale_create_user "${1:-}" ;;
    headscale-create-key) headscale_create_key "${1:-default}" "${2:-24h}" "${3:-0}" ;;
    headscale-delete-node) headscale_delete_node "${1:-}" ;;
    *)
        echo "Usage: $0 {check-installed|install|ensure-daemon|up|down|ping|netcheck|status|full-status|headscale-nodes|headscale-users|headscale-create-user|headscale-create-key|headscale-delete-node}" >&2
        exit 2
        ;;
esac
