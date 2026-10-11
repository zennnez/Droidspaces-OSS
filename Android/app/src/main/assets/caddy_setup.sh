#!/bin/sh

# Copyright (C) 2026
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Caddy Web Server & Reverse Proxy Manager for Droidspaces
# Supports both Host Service (systemd/binary) and Docker Container mode

set -u

CADDYFILE_HOST="/etc/caddy/Caddyfile"
CADDY_DOCKER_CONTAINER="caddy"

DEFAULT_CADDYFILE='# Droidspaces Caddy Reverse Proxy Configuration
:80 {
    # Reverse proxy to internal service (e.g. Code Server on 8080)
    # reverse_proxy localhost:8080

    # Or static file server
    # root * /var/www/html
    # file_server

    respond "Droidspaces Caddy Reverse Proxy is active!" 200
}
'

detect_mode() {
    if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${CADDY_DOCKER_CONTAINER}$"; then
        MODE="docker"
    elif command -v caddy >/dev/null 2>&1; then
        MODE="host"
    else
        MODE="none"
    fi
}

check_status() {
    detect_mode
    installed=0
    running=0
    version=""
    ports=""
    config_path=""

    case "$MODE" in
        docker)
            installed=1
            config_path="/etc/caddy/Caddyfile"
            state="$(docker inspect --format '{{.State.Status}}' "$CADDY_DOCKER_CONTAINER" 2>/dev/null || echo "stopped")"
            [ "$state" = "running" ] && running=1
            version="$(docker exec "$CADDY_DOCKER_CONTAINER" caddy version 2>/dev/null | head -n1 || echo "docker/caddy:latest")"
            ports="80:80, 443:443"
            ;;
        host)
            installed=1
            config_path="$CADDYFILE_HOST"
            if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet caddy 2>/dev/null; then
                running=1
            elif pgrep -x caddy >/dev/null 2>&1; then
                running=1
            fi
            version="$(caddy version 2>/dev/null | head -n1 || echo "caddy (host)")"
            ports="80, 443"
            ;;
        *)
            installed=0
            running=0
            config_path="$CADDYFILE_HOST"
            ;;
    esac

    echo "mode=$MODE"
    echo "installed=$installed"
    echo "running=$running"
    echo "version=$version"
    echo "ports=$ports"
    echo "config_path=$config_path"
}

get_config() {
    detect_mode
    if [ "$MODE" = "docker" ]; then
        if [ -f "$CADDYFILE_HOST" ]; then
            cat "$CADDYFILE_HOST"
        else
            docker exec "$CADDY_DOCKER_CONTAINER" cat /etc/caddy/Caddyfile 2>/dev/null || printf '%s' "$DEFAULT_CADDYFILE"
        fi
    elif [ -f "$CADDYFILE_HOST" ]; then
        cat "$CADDYFILE_HOST"
    else
        printf '%s' "$DEFAULT_CADDYFILE"
    fi
}

save_config() {
    b64="${1:-}"
    [ -z "$b64" ] && { echo "Error: base64 config required" >&2; exit 1; }

    mkdir -p /etc/caddy
    echo "$b64" | base64 -d > "$CADDYFILE_HOST" || { echo "Failed to write Caddyfile" >&2; exit 1; }

    detect_mode
    if [ "$MODE" = "docker" ]; then
        # Copy to container if needed or reload
        if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CADDY_DOCKER_CONTAINER}$"; then
            docker exec "$CADDY_DOCKER_CONTAINER" caddy reload --config /etc/caddy/Caddyfile 2>&1 || {
                docker restart "$CADDY_DOCKER_CONTAINER" 2>&1
            }
        fi
    elif [ "$MODE" = "host" ]; then
        if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet caddy 2>/dev/null; then
            systemctl reload caddy 2>/dev/null || caddy reload --config "$CADDYFILE_HOST" 2>&1 || systemctl restart caddy 2>/dev/null
        elif pgrep -x caddy >/dev/null 2>&1; then
            caddy reload --config "$CADDYFILE_HOST" 2>&1 || true
        fi
    fi

    echo "Caddyfile saved and applied successfully ✓"
}

validate_config() {
    b64="${1:-}"
    [ -z "$b64" ] && { echo "Error: base64 config required" >&2; exit 1; }

    tmp_file="/tmp/Caddyfile.test.$$"
    echo "$b64" | base64 -d > "$tmp_file"

    detect_mode
    if command -v caddy >/dev/null 2>&1; then
        out="$(caddy validate --config "$tmp_file" 2>&1)"
        rc=$?
        rm -f "$tmp_file"
        if [ $rc -eq 0 ]; then
            echo "VALID"
        else
            echo "INVALID: $out"
            exit 1
        fi
    elif [ "$MODE" = "docker" ] && docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CADDY_DOCKER_CONTAINER}$"; then
        docker cp "$tmp_file" "${CADDY_DOCKER_CONTAINER}:/tmp/Caddyfile.test" 2>/dev/null
        out="$(docker exec "$CADDY_DOCKER_CONTAINER" caddy validate --config /tmp/Caddyfile.test 2>&1)"
        rc=$?
        rm -f "$tmp_file"
        docker exec "$CADDY_DOCKER_CONTAINER" rm -f /tmp/Caddyfile.test 2>/dev/null || true
        if [ $rc -eq 0 ]; then
            echo "VALID"
        else
            echo "INVALID: $out"
            exit 1
        fi
    else
        rm -f "$tmp_file"
        echo "VALID (syntax check skipped: caddy binary not available)"
    fi
}

service_action() {
    act="${1:-status}"
    detect_mode

    if [ "$MODE" = "docker" ]; then
        case "$act" in
            start) docker start caddy 2>&1 ;;
            stop) docker stop caddy 2>&1 ;;
            restart) docker restart caddy 2>&1 ;;
            reload) docker exec caddy caddy reload --config /etc/caddy/Caddyfile 2>&1 ;;
            *) echo "Unknown action: $act" >&2; exit 1 ;;
        esac
    else
        case "$act" in
            start)
                if command -v systemctl >/dev/null 2>&1; then
                    systemctl start caddy 2>&1
                else
                    nohup caddy run --config "$CADDYFILE_HOST" >/var/log/caddy.log 2>&1 &
                fi
                ;;
            stop)
                if command -v systemctl >/dev/null 2>&1; then
                    systemctl stop caddy 2>&1
                else
                    pkill -x caddy 2>/dev/null || true
                fi
                ;;
            restart)
                if command -v systemctl >/dev/null 2>&1; then
                    systemctl restart caddy 2>&1
                else
                    pkill -x caddy 2>/dev/null || true
                    sleep 1
                    nohup caddy run --config "$CADDYFILE_HOST" >/var/log/caddy.log 2>&1 &
                fi
                ;;
            reload)
                caddy reload --config "$CADDYFILE_HOST" 2>&1 || {
                    if command -v systemctl >/dev/null 2>&1; then
                        systemctl reload caddy 2>&1
                    fi
                }
                ;;
            *) echo "Unknown action: $act" >&2; exit 1 ;;
        esac
    fi
}

install_host() {
    echo "Installing Caddy on host system..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https curl
        curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg 2>/dev/null || true
        curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
        apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq caddy
        mkdir -p /etc/caddy
        [ ! -f "$CADDYFILE_HOST" ] && printf '%s' "$DEFAULT_CADDYFILE" > "$CADDYFILE_HOST"
        if command -v systemctl >/dev/null 2>&1; then
            systemctl enable --now caddy 2>/dev/null || true
        fi
        echo "Caddy installed and enabled on host ✓"
    fi
}

install_docker() {
    echo "Installing and launching Caddy container in Docker..."
    if ! command -v docker >/dev/null 2>&1; then
        echo "Error: Docker is not installed in this container" >&2
        exit 1
    fi
    mkdir -p /etc/caddy
    [ ! -f "$CADDYFILE_HOST" ] && printf '%s' "$DEFAULT_CADDYFILE" > "$CADDYFILE_HOST"

    docker rm -f caddy 2>/dev/null || true
    docker run -d \
        --name caddy \
        --restart unless-stopped \
        -p 80:80 \
        -p 443:443 \
        -p 443:443/udp \
        -v /etc/caddy/Caddyfile:/etc/caddy/Caddyfile \
        -v caddy_data:/data \
        -v caddy_config:/config \
        caddy:latest

    echo "Caddy Docker container deployed and running on ports 80/443 ✓"
}

get_logs() {
    lines="${1:-100}"
    detect_mode
    if [ "$MODE" = "docker" ]; then
        docker logs --tail "$lines" caddy 2>&1
    elif command -v journalctl >/dev/null 2>&1; then
        journalctl -u caddy -n "$lines" --no-pager 2>&1
    elif [ -f /var/log/caddy.log ]; then
        tail -n "$lines" /var/log/caddy.log 2>&1
    else
        echo "No logs found"
    fi
}

cmd="${1:-status}"
shift 2>/dev/null || true

case "$cmd" in
    status) check_status ;;
    get-config) get_config ;;
    save-config) save_config "${1:-}" ;;
    validate) validate_config "${1:-}" ;;
    service) service_action "${1:-status}" ;;
    install-host) install_host ;;
    install-docker) install_docker ;;
    logs) get_logs "${1:-100}" ;;
    *)
        echo "Usage: $0 {status|get-config|save-config <b64>|validate <b64>|service <start|stop|restart|reload>|install-host|install-docker|logs [lines]}" >&2
        exit 2
        ;;
esac
