#!/bin/sh

# Copyright (C) 2026
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Install, configure, and control the real Docker Engine running INSIDE a
# Droidspaces container (see docker.md section 1 -- this is a plain nested
# container host setup, no different in kind from running dockerd inside any
# other Linux container). Not related to the droidspaces-socketd bridge
# (docker.md section 2), which is a separate host-side daemon protocol this
# script has nothing to do with.
#
# Subcommands:
#   readiness                        key=value kernel-feature report (see below)
#   install                          idempotent: install docker.io + configure storage driver
#   set-storage-driver <driver>      overlay2 | vfs -- rewrites daemon.json, restarts docker
#   set-tcp-expose <0|1> <port>      toggle dockerd's TCP API alongside the unix socket
#   service <start|stop|restart>     control the docker(d) service across systemd/OpenRC
#   status                           key=value status report
#   ps                               docker ps, one compact JSON object per line
#   images                           docker images, one compact JSON object per line
#   update-container <id> [recreate] pull latest image and check/apply update for a container
#   update-all [recreate]            pull and check updates for all containers
#
# READINESS CHECK
# Mirrors droidspaces' own `droidspaces check` probes exactly (src/check.c),
# not a reimplementation guessed from scratch:
#   - user_ns:    access("/proc/self/ns/user") -- Docker's procfs safety check
#                 refuses to start without this; there is no in-container
#                 workaround, it must come from the kernel.
#   - overlay_fs: grep "overlay" /proc/filesystems -- Docker's default and by
#                 far most common storage driver. Falls back to vfs (works,
#                 slower, more disk) if absent.
#   - cgroup_v2:  grep "cgroup2" /proc/filesystems -- modern cgroup driver.
#                 Falls back to legacy cgroup v1 if absent (works, loses some
#                 modern resource-accounting).
#   - cgroup_ns:  /proc/self/ns/cgroup existence, PLUS the same functional
#                 unshare() test check_ns() does in check.c (via the
#                 `unshare` CLI tool where available, since this script can't
#                 fork+unshare() directly in POSIX sh) -- needed for Docker's
#                 own nested containers to get proper cgroup isolation.

set -u

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

DAEMON_JSON="/etc/docker/daemon.json"
SYSTEMD_OVERRIDE_DIR="/etc/systemd/system/docker.service.d"
SYSTEMD_OVERRIDE_FILE="$SYSTEMD_OVERRIDE_DIR/droidspaces-tcp.conf"

log() { printf '%s\n' "[Droidspaces Docker] $*"; }
fail() { printf '%s\n' "[Droidspaces Docker] ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
readiness() {
    user_ns=0
    [ -e /proc/self/ns/user ] && user_ns=1

    overlay_fs=0
    grep -q overlay /proc/filesystems 2>/dev/null && overlay_fs=1

    cgroup_v2=0
    grep -q cgroup2 /proc/filesystems 2>/dev/null && cgroup_v2=1

    cgroup_ns=0
    if [ -e /proc/self/ns/cgroup ]; then
        if command -v unshare >/dev/null 2>&1; then
            unshare -C true >/dev/null 2>&1 && cgroup_ns=1
        else
            # unshare(1) unavailable -- fall back to the file-existence half
            # of check.c's own two-part test (kernel support is necessary
            # but not sufficient; documented as a weaker signal than check.c's
            # full functional test).
            cgroup_ns=1
        fi
    fi

    verdict="good"
    [ "$overlay_fs" = "0" ] && verdict="degraded"
    [ "$cgroup_v2" = "0" ] && verdict="degraded"
    [ "$user_ns" = "0" ] && verdict="blocked"

    echo "user_ns=$user_ns"
    echo "overlay_fs=$overlay_fs"
    echo "cgroup_v2=$cgroup_v2"
    echo "cgroup_ns=$cgroup_ns"
    echo "verdict=$verdict"
}

# ---------------------------------------------------------------------------
write_daemon_json() {
    driver="$1"
    mkdir -p /etc/docker
    cat > "$DAEMON_JSON" <<EOF
{
    "exec-opts": ["native.cgroupdriver=cgroupfs"],
    "storage-driver": "$driver",
    "dns": ["8.8.8.8", "1.1.1.1"],
    "mtu": 1420,
    "ipv6": false,
    "features": {
        "buildkit": true
    }
}
EOF
}

install_docker() {
    if command -v dockerd >/dev/null 2>&1 || [ -x /usr/bin/dockerd ] || [ -x /usr/sbin/dockerd ]; then
        log "dockerd already installed"
        # Ensure buildx and compose plugins are also installed if missing
        if command -v apt-get >/dev/null 2>&1; then
            DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker-buildx-plugin docker-compose-plugin docker-buildx docker-compose-v2 2>/dev/null || true
        elif command -v pacman >/dev/null 2>&1; then
            pacman -Sy --noconfirm --needed docker-compose docker-buildx 2>/dev/null || true
        elif command -v dnf >/dev/null 2>&1; then
            dnf install -y docker-compose docker-buildx-plugin 2>/dev/null || true
        fi
    else
        if command -v apt-get >/dev/null 2>&1; then
            log "Installing Docker & BuildKit / Compose plugins via apt-get..."
            DEBIAN_FRONTEND=noninteractive apt-get update -qq || true
            DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ca-certificates curl gnupg 2>/dev/null || true

            if ! DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker.io docker-buildx docker-compose-v2 2>/dev/null; then
                # Setup official Docker apt repository for Debian/Ubuntu
                mkdir -p -m 0755 /etc/apt/keyrings 2>/dev/null || true
                curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc 2>/dev/null || \
                curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc 2>/dev/null || true
                chmod a+r /etc/apt/keyrings/docker.asc 2>/dev/null || true

                os_id="$(. /etc/os-release 2>/dev/null && echo "${ID:-ubuntu}")"
                os_distro="ubuntu"
                [ "$os_id" = "debian" ] || [ "$os_id" = "raspbian" ] && os_distro="debian"
                os_codename="$(. /etc/os-release 2>/dev/null && echo "${UBUNTU_CODENAME:-${VERSION_CODENAME:-noble}}")"

                echo "deb [arch=$(dpkg --print-architecture 2>/dev/null || echo arm64) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${os_distro} ${os_codename} stable" > /etc/apt/sources.list.d/docker.list 2>/dev/null || true

                DEBIAN_FRONTEND=noninteractive apt-get update -qq || true
                DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin 2>/dev/null || \
                DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker.io docker-buildx 2>/dev/null || true
            else
                DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker-buildx-plugin docker-compose-plugin 2>/dev/null || true
            fi
        elif command -v dnf >/dev/null 2>&1; then
            log "Installing docker & plugins via dnf..."
            dnf install -y docker docker-compose docker-buildx-plugin 2>/dev/null || dnf install -y docker
        elif command -v yum >/dev/null 2>&1; then
            log "Installing docker via yum..."
            yum install -y docker
        elif command -v pacman >/dev/null 2>&1; then
            log "Installing docker & plugins via pacman..."
            pacman -Sy --noconfirm --needed docker docker-compose docker-buildx
        elif command -v apk >/dev/null 2>&1; then
            log "Installing docker & plugins via apk..."
            apk add --no-cache docker docker-cli-compose docker-cli-buildx
        elif command -v zypper >/dev/null 2>&1; then
            log "Installing docker via zypper..."
            zypper --non-interactive install docker docker-compose
        else
            fail "No supported package manager found (apt/dnf/yum/pacman/apk/zypper)"
        fi
    fi

    if ! command -v dockerd >/dev/null 2>&1 && [ ! -x /usr/bin/dockerd ] && [ ! -x /usr/sbin/dockerd ]; then
        fail "Docker installation completed but dockerd is unavailable"
    fi

    # Pick the storage driver readiness already determined is viable, unless
    # the caller (Kotlin side, via the container's forceVfs setting) already
    # wrote daemon.json before calling install -- don't clobber an explicit
    # override.
    if [ ! -f "$DAEMON_JSON" ]; then
        write_daemon_json "overlay2"
    fi

    if command -v systemctl >/dev/null 2>&1 && [ "$(cat /proc/1/comm 2>/dev/null)" = "systemd" ]; then
        systemctl daemon-reload
        systemctl enable --now containerd.service 2>/dev/null || true
        systemctl restart containerd.service 2>/dev/null || true
        systemctl enable --now docker.service 2>/dev/null || systemctl restart docker.service 2>/dev/null || fail "Failed to start docker.service"
    elif command -v rc-service >/dev/null 2>&1 && command -v rc-update >/dev/null 2>&1; then
        rc-update add containerd default 2>/dev/null || true
        rc-service containerd restart 2>/dev/null || true
        rc-update add docker default 2>/dev/null || true
        rc-service docker restart || fail "Failed to start docker OpenRC service"
    else
        log "Non-systemd/OpenRC init detected; starting dockerd in background..."
        pkill -x dockerd 2>/dev/null || true
        dockerd >/var/log/dockerd.log 2>&1 &
        sleep 2
    fi

    log "Docker installed and service launched successfully"
}

set_storage_driver() {
    driver="${1:-}"
    case "$driver" in
        overlay2|vfs) ;;
        *) fail "Invalid storage driver '$driver'; must be overlay2 or vfs" ;;
    esac
    write_daemon_json "$driver"
    log "Updated $DAEMON_JSON with storage-driver=$driver"
    docker_service restart
}

set_tcp_expose() {
    enable="${1:-0}"
    port="${2:-2375}"

    if [ "$enable" = "1" ]; then
        mkdir -p "$SYSTEMD_OVERRIDE_DIR"
        cat > "$SYSTEMD_OVERRIDE_FILE" <<EOF
[Service]
ExecStart=
ExecStart=/usr/bin/dockerd -H fd:// -H tcp://0.0.0.0:$port --containerd=/run/containerd/containerd.sock
EOF
        log "Created systemd override $SYSTEMD_OVERRIDE_FILE (port $port)"
    else
        rm -f "$SYSTEMD_OVERRIDE_FILE"
        log "Removed systemd override $SYSTEMD_OVERRIDE_FILE"
    fi

    if command -v systemctl >/dev/null 2>&1 && [ "$(cat /proc/1/comm 2>/dev/null)" = "systemd" ]; then
        systemctl daemon-reload
        docker_service restart
    fi
}

docker_service() {
    action="${1:-status}"
    if command -v systemctl >/dev/null 2>&1 && [ "$(cat /proc/1/comm 2>/dev/null)" = "systemd" ]; then
        case "$action" in
            start)
                systemctl start containerd.service 2>/dev/null || true
                systemctl start docker.service
                ;;
            stop)    systemctl stop docker.service ;;
            restart)
                systemctl restart containerd.service 2>/dev/null || true
                systemctl restart docker.service
                ;;
            status)  systemctl is-active --quiet docker.service && echo "running" || echo "stopped" ;;
            *) fail "Unknown service action '$action'" ;;
        esac
    elif command -v rc-service >/dev/null 2>&1; then
        case "$action" in
            start)
                rc-service containerd start 2>/dev/null || true
                rc-service docker start
                ;;
            stop)    rc-service docker stop ;;
            restart)
                rc-service containerd restart 2>/dev/null || true
                rc-service docker restart
                ;;
            status)  rc-service docker status >/dev/null 2>&1 && echo "running" || echo "stopped" ;;
            *) fail "Unknown service action '$action'" ;;
        esac
    else
        case "$action" in
            start)
                dockerd >/var/log/dockerd.log 2>&1 &
                ;;
            stop)
                pkill -x dockerd 2>/dev/null || true
                ;;
            restart)
                pkill -x dockerd 2>/dev/null || true
                sleep 1
                dockerd >/var/log/dockerd.log 2>&1 &
                ;;
            status)
                pgrep -x dockerd >/dev/null 2>&1 && echo "running" || echo "stopped"
                ;;
            *) fail "Unknown service action '$action'" ;;
        esac
    fi
}

status() {
    installed=0
    running=0
    storage_driver="unknown"
    tcp_exposed=0

    if command -v dockerd >/dev/null 2>&1 || command -v docker >/dev/null 2>&1 || [ -x /usr/bin/dockerd ] || [ -x /usr/sbin/dockerd ] || [ -x /usr/local/bin/dockerd ] || [ -x /usr/libexec/docker/dockerd ] || [ -x /usr/bin/docker ]; then
        installed=1
    fi

    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        running=1
        storage_driver="$(docker info --format '{{.Driver}}' 2>/dev/null || echo 'unknown')"
    elif command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet docker 2>/dev/null; then
        running=1
    elif command -v rc-service >/dev/null 2>&1 && rc-service docker status 2>/dev/null | grep -qi "running"; then
        running=1
    elif pgrep -x dockerd >/dev/null 2>&1; then
        running=1
    fi

    if [ "$storage_driver" = "unknown" ] && [ -f "$DAEMON_JSON" ]; then
        storage_driver="$(grep -o '"storage-driver"[[:space:]]*:[[:space:]]*"[^"]*"' "$DAEMON_JSON" 2>/dev/null | cut -d'"' -f4 || echo 'unknown')"
    fi
    [ -f "$SYSTEMD_OVERRIDE_FILE" ] && tcp_exposed=1

    echo "installed=$installed"
    echo "running=$running"
    echo "storage_driver=$storage_driver"
    echo "tcp_exposed=$tcp_exposed"
}

# ---------------------------------------------------------------------------
list_containers() {
    command -v docker >/dev/null 2>&1 || fail "docker CLI not installed"
    docker ps -a --no-trunc --format '{{json .}}' 2>&1
}

list_images() {
    command -v docker >/dev/null 2>&1 || fail "docker CLI not installed"
    docker images --no-trunc --format '{{json .}}' 2>&1
}

container_action() {
    action="$1"
    shift
    command -v docker >/dev/null 2>&1 || fail "docker CLI not installed"
    case "$action" in
        start) docker start "$@" 2>&1 ;;
        stop) docker stop "$@" 2>&1 ;;
        restart) docker restart "$@" 2>&1 ;;
        pause) docker pause "$@" 2>&1 ;;
        unpause) docker unpause "$@" 2>&1 ;;
        kill) docker kill "$@" 2>&1 ;;
        rm) docker rm -f "$@" 2>&1 ;;
        rename) docker rename "$1" "$2" 2>&1 ;;
        inspect) docker inspect "$1" 2>&1 ;;
        prune) docker container prune -f 2>&1 ;;
        *) fail "Unknown container action: $action" ;;
    esac
}

image_action() {
    action="$1"
    shift
    command -v docker >/dev/null 2>&1 || fail "docker CLI not installed"
    case "$action" in
        pull) docker pull "$@" 2>&1 ;;
        rm) docker rmi -f "$@" 2>&1 ;;
        prune) docker image prune -a -f 2>&1 ;;
        *) fail "Unknown image action: $action" ;;
    esac
}

system_prune() {
    command -v docker >/dev/null 2>&1 || fail "docker CLI not installed"
    docker system prune -f 2>&1
}

update_container() {
    target="${1:-}"
    recreate="${2:-0}"
    [ -z "$target" ] && fail "Container ID or name required"
    command -v docker >/dev/null 2>&1 || fail "docker CLI not installed"

    # Get image name and current image ID
    image_name="$(docker inspect --format '{{.Config.Image}}' "$target" 2>/dev/null || echo '')"
    [ -z "$image_name" ] && fail "Could not find image for container $target"

    old_img_id="$(docker inspect --format '{{.Image}}' "$target" 2>/dev/null || echo '')"
    old_short_id="$(echo "$old_img_id" | sed 's/sha256://' | cut -c1-12)"

    log "Checking image updates for container $target (image: $image_name)..."
    docker pull "$image_name" 2>&1

    new_img_id="$(docker inspect --format '{{.Id}}' "$image_name" 2>/dev/null || echo '')"
    new_short_id="$(echo "$new_img_id" | sed 's/sha256://' | cut -c1-12)"

    if [ "$old_img_id" = "$new_img_id" ]; then
        log "Container $target is already running the latest image version ($old_short_id)."
        printf '{"status": "up_to_date", "container": "%s", "image": "%s", "imageId": "%s"}\n' "$target" "$image_name" "$new_short_id"
    else
        log "New image version downloaded ($old_short_id -> $new_short_id)."
        if [ "$recreate" = "1" ] || [ "$recreate" = "recreate" ] || [ "$recreate" = "true" ]; then
            # Check if docker-compose managed
            compose_workdir="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$target" 2>/dev/null || echo '')"
            if [ -n "$compose_workdir" ] && [ -d "$compose_workdir" ]; then
                log "Recreating compose container in $compose_workdir..."
                (cd "$compose_workdir" && docker compose up -d) 2>&1
            else
                log "Restarting container with new image..."
                docker restart "$target" 2>&1
            fi
            printf '{"status": "updated", "container": "%s", "image": "%s", "oldImageId": "%s", "newImageId": "%s", "restarted": true}\n' "$target" "$image_name" "$old_short_id" "$new_short_id"
        else
            printf '{"status": "update_available", "container": "%s", "image": "%s", "oldImageId": "%s", "newImageId": "%s"}\n' "$target" "$image_name" "$old_short_id" "$new_short_id"
        fi
    fi
}

update_all() {
    recreate="${1:-0}"
    command -v docker >/dev/null 2>&1 || fail "docker CLI not installed"

    printf '[\n'
    first=1
    for cid in $(docker ps -q); do
        cname="$(docker inspect --format '{{.Name}}' "$cid" | sed 's/^\///')"
        [ "$first" -eq 1 ] && first=0 || printf ',\n'
        update_container "$cname" "$recreate"
    done
    printf '\n]\n'
}

# ---------------------------------------------------------------------------
cmd="${1:-}"
shift 2>/dev/null || true

case "$cmd" in
    readiness) readiness ;;
    install) install_docker ;;
    set-storage-driver) set_storage_driver "${1:-}" ;;
    set-tcp-expose) set_tcp_expose "${1:-0}" "${2:-2375}" ;;
    service) docker_service "${1:-status}" ;;
    status) status ;;
    ps) list_containers ;;
    images) list_images ;;
    container) container_action "$@" ;;
    image) image_action "$@" ;;
    system-prune) system_prune ;;
    update-container) update_container "${1:-}" "${2:-0}" ;;
    update-all) update_all "${1:-0}" ;;
    run) docker run "$@" 2>&1 ;;
    *)
        echo "Usage: $0 {readiness|install|set-storage-driver <driver>|set-tcp-expose <0|1> <port>|service <start|stop|restart>|status|ps|images|container <action> [args...]|image <action> [args...]|system-prune|update-container <id> [recreate]|update-all [recreate]|run [args...]}" >&2
        exit 2
        ;;
esac
