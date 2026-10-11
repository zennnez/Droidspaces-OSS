#!/bin/sh

# Copyright (C) 2026
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Install and configure an isolated Droidspaces OpenSSH service.
# Usage: ssh-setup.sh [user] [port] [allow-password: 0|1]

set -eu

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

SSH_USER="${1:-root}"
SSH_PORT="${2:-22}"
ALLOW_PASSWORD="${3:-1}"
CONFIG_FILE="/etc/ssh/sshd_config"
PID_FILE="/run/sshd.pid"

log() { printf '%s\n' "[SSH] $*"; }
fail() { printf '%s\n' "[SSH] ERROR: $*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || fail "Run setup as root inside container"
case "$SSH_PORT" in
    ''|*[!0-9]*) fail "Invalid SSH port: $SSH_PORT" ;;
esac
[ "$SSH_PORT" -ge 1 ] && [ "$SSH_PORT" -le 65535 ] || fail "SSH port must be between 1 and 65535"
case "$ALLOW_PASSWORD" in 0|1) ;; *) fail "Password-auth flag must be 0 or 1" ;; esac

# Clean up any legacy custom droidspaces-sshd services
if command -v systemctl >/dev/null 2>&1; then
    systemctl disable --now droidspaces-sshd.service 2>/dev/null || true
    rm -f /etc/systemd/system/droidspaces-sshd.service 2>/dev/null || true
fi
if command -v rc-service >/dev/null 2>&1; then
    rc-service droidspaces-sshd stop 2>/dev/null || true
    rc-update del droidspaces-sshd default 2>/dev/null || true
    rm -f /etc/init.d/droidspaces-sshd 2>/dev/null || true
fi
rm -f /usr/local/sbin/droidspaces-sshd /run/droidspaces-sshd.pid 2>/dev/null || true

# Function to check if a port is in use
is_port_in_use() {
    p="$1"
    if command -v ss >/dev/null 2>&1 && ss -tuln 2>/dev/null | grep -qE ":$p\b"; then return 0; fi
    if command -v netstat >/dev/null 2>&1 && netstat -tuln 2>/dev/null | grep -qE ":$p\b"; then return 0; fi
    return 1
}

# If requested port is already in use by another non-sshd service, find fallback
if is_port_in_use "$SSH_PORT"; then
    is_our_pid=0
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE" 2>/dev/null)" 2>/dev/null; then
        is_our_pid=1
    elif pgrep -x sshd >/dev/null 2>&1; then
        is_our_pid=1
    fi
    if [ "$is_our_pid" = "0" ]; then
        log "Port $SSH_PORT is already in use by another service. Finding next available port..."
        orig_p="$SSH_PORT"
        for cand in $(seq 2222 2250); do
            if ! is_port_in_use "$cand"; then
                SSH_PORT="$cand"
                log "Selected fallback guest port $SSH_PORT (original $orig_p was occupied)"
                break
            fi
        done
    fi
fi

PKG_MGR=""
SSHD_PKG=""
if command -v apt-get >/dev/null 2>&1; then
    PKG_MGR="apt"; SSHD_PKG="openssh-server"
elif command -v dnf >/dev/null 2>&1; then
    PKG_MGR="dnf"; SSHD_PKG="openssh-server"
elif command -v yum >/dev/null 2>&1; then
    PKG_MGR="yum"; SSHD_PKG="openssh-server"
elif command -v pacman >/dev/null 2>&1; then
    PKG_MGR="pacman"; SSHD_PKG="openssh"
elif command -v apk >/dev/null 2>&1; then
    PKG_MGR="apk"; SSHD_PKG="openssh"
elif command -v zypper >/dev/null 2>&1; then
    PKG_MGR="zypper"; SSHD_PKG="openssh"
else
    fail "No supported package manager found (apt/dnf/yum/pacman/apk/zypper)"
fi

if ! command -v sshd >/dev/null 2>&1 && [ ! -x /usr/sbin/sshd ]; then
    log "Installing $SSHD_PKG with $PKG_MGR"
    case "$PKG_MGR" in
        apt) DEBIAN_FRONTEND=noninteractive apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "$SSHD_PKG" ;;
        dnf) dnf install -y "$SSHD_PKG" ;;
        yum) yum install -y "$SSHD_PKG" ;;
        pacman) pacman -Sy --noconfirm "$SSHD_PKG" ;;
        apk) apk add --no-cache "$SSHD_PKG" ;;
        zypper) zypper --non-interactive install "$SSHD_PKG" ;;
    esac
fi

SSHD_BIN="$(command -v sshd 2>/dev/null || true)"
[ -n "$SSHD_BIN" ] || SSHD_BIN="/usr/sbin/sshd"
[ -x "$SSHD_BIN" ] || fail "OpenSSH server installation completed but sshd is unavailable"

if [ "$SSH_USER" = "root" ] || getent passwd "$SSH_USER" >/dev/null 2>&1 || id "$SSH_USER" >/dev/null 2>&1; then
    log "Using user $SSH_USER"
elif command -v useradd >/dev/null 2>&1; then
    useradd -m -s /bin/sh "$SSH_USER" 2>/dev/null || true
elif command -v adduser >/dev/null 2>&1; then
    adduser -D -s /bin/sh "$SSH_USER" 2>/dev/null || true
else
    log "Cannot create user: useradd/adduser unavailable, falling back to root"
    SSH_USER="root"
fi

mkdir -p -m 0755 /etc/ssh /run/sshd /var/run/sshd /var/empty
chmod 0755 /run/sshd /var/run/sshd /var/empty 2>/dev/null || true
ssh-keygen -A 2>/dev/null || ssh-keygen -A || true

SFTP_SERVER=""
for candidate in /usr/lib/openssh/sftp-server /usr/lib/ssh/sftp-server /usr/libexec/openssh/sftp-server /usr/libexec/sftp-server; do
    if [ -x "$candidate" ]; then SFTP_SERVER="$candidate"; break; fi
done
if [ -n "$SFTP_SERVER" ]; then
    SFTP_DIRECTIVE="Subsystem sftp $SFTP_SERVER"
else
    SFTP_DIRECTIVE="Subsystem sftp internal-sftp"
fi

PASSWORD_AUTH="no"
[ "$ALLOW_PASSWORD" = "1" ] && PASSWORD_AUTH="yes"

# Write hardened /etc/ssh/sshd_config
ROOT_LOGIN="yes"
[ "$ALLOW_PASSWORD" = "0" ] && ROOT_LOGIN="prohibit-password"

cat > "$CONFIG_FILE" <<EOF
Port $SSH_PORT
ListenAddress 0.0.0.0
Protocol 2
PidFile $PID_FILE
PermitRootLogin $ROOT_LOGIN
PasswordAuthentication $PASSWORD_AUTH
PermitEmptyPasswords no
MaxAuthTries 3
IgnoreRhosts yes
HostbasedAuthentication no
KbdInteractiveAuthentication no
ChallengeResponseAuthentication no
PubkeyAuthentication yes
AuthorizedKeysFile .ssh/authorized_keys
UsePAM yes
UseDNS no
GSSAPIAuthentication no
IPQoS 0x00
TCPKeepAlive yes
ClientAliveInterval 60
ClientAliveCountMax 3
PrintMotd no
X11Forwarding no
AllowTcpForwarding yes
$SFTP_DIRECTIVE
EOF

if [ -d /etc/ssh/sshd_config.d ]; then
    cat > /etc/ssh/sshd_config.d/99-droidspaces.conf <<EOF
Port $SSH_PORT
PasswordAuthentication $PASSWORD_AUTH
PubkeyAuthentication yes
PermitRootLogin $ROOT_LOGIN
PermitEmptyPasswords no
MaxAuthTries 3
EOF
fi

# Optimize client-side SSH latency if connecting to other devices from container
if [ -f /etc/ssh/ssh_config ]; then
    grep -q "IPQoS" /etc/ssh/ssh_config 2>/dev/null || cat >> /etc/ssh/ssh_config << 'EOF'

# Droidspaces latency optimization
Host *
    IPQoS 0x00
    ServerAliveInterval 30
    ServerAliveCountMax 3
    TCPKeepAlive yes
EOF
fi

mkdir -p /root/.ssh
touch /root/.ssh/authorized_keys
chmod 700 /root/.ssh 2>/dev/null || true
chmod 600 /root/.ssh/authorized_keys 2>/dev/null || true

USER_HOME="$(getent passwd "$SSH_USER" 2>/dev/null | cut -d: -f6 || true)"
if [ -z "$USER_HOME" ]; then
    USER_HOME="$(awk -F: -v user="$SSH_USER" '$1 == user { print $6; exit }' /etc/passwd 2>/dev/null || true)"
fi
if [ -n "$USER_HOME" ] && [ -d "$USER_HOME" ]; then
    mkdir -p "$USER_HOME/.ssh"
    touch "$USER_HOME/.ssh/authorized_keys"
    chown -R "$SSH_USER:$SSH_USER" "$USER_HOME/.ssh" 2>/dev/null || true
    chmod 700 "$USER_HOME/.ssh" 2>/dev/null || true
    chmod 600 "$USER_HOME/.ssh/authorized_keys" 2>/dev/null || true
fi

# If UsePAM fails validation (e.g. musl/Alpine), fallback to UsePAM no
if ! "$SSHD_BIN" -t -f "$CONFIG_FILE" 2>/dev/null; then
    sed -i 's/^UsePAM yes/UsePAM no/' "$CONFIG_FILE"
fi

"$SSHD_BIN" -t -f "$CONFIG_FILE" || fail "Generated sshd configuration is invalid"

# Enable and start default ssh/sshd service
if command -v systemctl >/dev/null 2>&1 && [ "$(cat /proc/1/comm 2>/dev/null)" = "systemd" ]; then
    systemctl daemon-reload 2>/dev/null || true
    systemctl enable --now ssh.service 2>/dev/null || systemctl enable --now sshd.service 2>/dev/null || true
    systemctl restart ssh.service 2>/dev/null || systemctl restart sshd.service 2>/dev/null || systemctl restart ssh.socket 2>/dev/null || systemctl restart sshd.socket 2>/dev/null || fail "Failed to start ssh/sshd service"
elif command -v rc-service >/dev/null 2>&1; then
    rc-update add ssh default 2>/dev/null || rc-update add sshd default 2>/dev/null || true
    rc-service ssh restart 2>/dev/null || rc-service sshd restart 2>/dev/null || fail "Failed to start ssh/sshd OpenRC service"
else
    pkill -x sshd 2>/dev/null || true
    "$SSHD_BIN" -D >/var/log/sshd.log 2>&1 &
    sleep 1
    kill -0 "$!" 2>/dev/null || fail "sshd exited during startup; see /var/log/sshd.log"
fi

log "SSH server configured on port $SSH_PORT for user '$SSH_USER' (pass-auth: $ALLOW_PASSWORD)"
