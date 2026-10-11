#!/usr/bin/env bash
# ============================================================
# Droidspaces (OnePlus Pad 3 / Ubuntu XFCE) Dev Environment Setup
# Zsh + Modern CLI + Docker + Git + Eden Helpers
# Adapted from the Oracle VPS setup script
# ============================================================

set -euo pipefail

# Preserve the original argv untouched -- the arg-parsing loop below
# uses `shift`, but the later re-exec-as-zen still needs the full
# original flag set (e.g. so a re-run with --root-pass doesn't just
# silently drop it on the zen half of the run).
ORIG_ARGS=("$@")

# ============================================================
# Argument parsing
# ============================================================

ROOT_PASS="rootkapass"
ZEN_PASS="9396"
TARGET_USER=""
TARGET_USER_SET=0
KEY_PASSPHRASE=""
HEADSCALE_URL=""
EXTRA_PUBKEY=""
LAN_SUBNET=""
ROOT_PASS_SET=0
ZEN_PASS_SET=0
PASSPHRASE_SET=0
HEADSCALE_URL_SET=0
EXTRA_PUBKEY_SET=1
LAN_SUBNET_SET=0
TAILSCALE_AUTHKEY=""
INSTALL_PYTHON=""
INSTALL_OFFICE=""
INSTALL_ANTIGRAVITY=""
INSTALL_FIREFOX=""
TAILSCALE_AUTHKEY_SET=0
INSTALL_PYTHON_SET=0
INSTALL_OFFICE_SET=0
INSTALL_ANTIGRAVITY_SET=0
INSTALL_FIREFOX_SET=0
SKIP_ROOT_PASS=1
SKIP_ZEN_PASS=1
SKIP_SSH_KEY=1
GENERATE_SSH_KEY=0
CLOUDFLARE_TUNNEL_TOKEN=""
CLOUDFLARE_TOKEN_SET=0
INSTALL_CLOUDFLARE="no"
INSTALL_CLOUDFLARE_SET=1
SKIP_CLOUDFLARE=1
ORACLE_HOST="161.118.190.187"
ORACLE_USER="ubuntu"
ORACLE_SSH_PORT="22"
ORACLE_LOCAL_PORT="80"
ORACLE_REMOTE_PORT="8080"
SKIP_ORACLE_TUNNEL=1
INSTALL_ORACLE_TUNNEL="no"
INSTALL_ORACLE_TUNNEL_SET=1

# Auto-detect container config from Droidspaces app
DS_CONFIG="/run/droidspaces/container.config"
if [ -f "$DS_CONFIG" ]; then
    if grep -q 'enable_ssh=1' "$DS_CONFIG" 2>/dev/null; then
        SKIP_SSH_KEY=1
    fi
    if grep -q 'enable_anland=1' "$DS_CONFIG" 2>/dev/null; then
        export ANLAND=1
    fi
    CFG_USER="$(grep '^default_username=' "$DS_CONFIG" 2>/dev/null | head -1 | cut -d'=' -f2- | tr -d '\r\n' || true)"
    if [ -n "$CFG_USER" ]; then
        TARGET_USER="$CFG_USER"
    fi
fi

usage() {
    cat << 'USAGEEOF'
Usage: setup_droidspaces.sh [options]

Any value not passed as a flag is prompted for interactively.

  --user, --default-user USER Name of the non-root default user (default: auto-detected from config/passwd, or zen)
  --root-pass PASS            Password for the root account (console/local only, no SSH)
  --skip-root-pass            Skip setting/changing root password
  --user-pass, --zen-pass PASS Password for the default user account (local login + sudo)
  --skip-user-pass            Skip setting/changing default user password
  --passphrase PASS           Passphrase for the generated SSH key (empty string = no passphrase)
  --generate-ssh-key          Force generating a new SSH keypair
  --skip-ssh-key              Skip SSH keypair generation
  --cloudflare-token TOKEN    Cloudflare Tunnel token (defaults to reading CLOUDFLARE_TUNNEL_TOKEN from .env)
  --install-cloudflare yes|no Install and enable Cloudflare Tunnel service (default: prompt if no token)
  --skip-cloudflare           Skip Cloudflare Tunnel installation entirely
  --headscale-url URL         Your Headscale control server URL (blank = use tailscale.com's default)
  --tailscale-authkey KEY     Tailscale/Headscale pre-auth key, for fully non-interactive 'tailscale up'
  --extra-pubkey KEY          An additional SSH public key to authorize for the default user (e.g. from another device)
  --lan-subnet CIDR           Your LAN subnet for fail2ban's whitelist (e.g. 192.168.1.0/24; blank = auto-detect)
  --install-python yes|no     Install Python 3 + pip + venv (default: prompt)
  --install-office yes|no     Install LibreOffice (default: prompt)
  --install-antigravity yes|no Install Google Antigravity IDE (default: prompt)
  --install-firefox yes|no    Install Firefox Web Browser (default: prompt)
  --install-oracle-tunnel yes|no  Set up the SSH reverse tunnel to an Oracle relay (default: prompt)
  --skip-oracle-tunnel        Skip the Oracle relay tunnel entirely
  --oracle-host HOST          Oracle VPS public IP or hostname (required if installing the tunnel)
  --oracle-user USER          SSH user on the Oracle VPS (default: ubuntu)
  --oracle-ssh-port PORT      SSH port on the Oracle VPS (default: 22)
  --oracle-local-port PORT    Local port on THIS device the tunnel forwards to, i.e. where your own
                               reverse proxy (e.g. Caddy) listens (default: 80)
  --oracle-remote-port PORT   Port to bind on the Oracle side, on 127.0.0.1 only (default: 8080)
  -h, --help                  Show this help and exit

Examples:
  ./setup_droidspaces.sh                                     # fully interactive
  ./setup_droidspaces.sh --user dev --user-pass 'mypass' --passphrase ''
  ./setup_droidspaces.sh --skip-root-pass --skip-user-pass --skip-ssh-key
USAGEEOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --user|--default-user)
            TARGET_USER="${2:-}"; TARGET_USER_SET=1; shift 2 ;;
        --user=*|--default-user=*)
            TARGET_USER="${1#*=}"; TARGET_USER_SET=1; shift ;;
        --root-pass)
            ROOT_PASS="${2:-}"; ROOT_PASS_SET=1; shift 2 ;;
        --root-pass=*)
            ROOT_PASS="${1#*=}"; ROOT_PASS_SET=1; shift ;;
        --skip-root-pass)
            SKIP_ROOT_PASS=1; ROOT_PASS_SET=1; shift ;;
        --user-pass|--zen-pass)
            ZEN_PASS="${2:-}"; ZEN_PASS_SET=1; shift 2 ;;
        --user-pass=*|--zen-pass=*)
            ZEN_PASS="${1#*=}"; ZEN_PASS_SET=1; shift ;;
        --skip-user-pass|--skip-zen-pass)
            SKIP_ZEN_PASS=1; ZEN_PASS_SET=1; shift ;;
        --passphrase)
            KEY_PASSPHRASE="${2:-}"; PASSPHRASE_SET=1; shift 2 ;;
        --passphrase=*)
            KEY_PASSPHRASE="${1#*=}"; PASSPHRASE_SET=1; shift ;;
        --generate-ssh-key)
            GENERATE_SSH_KEY=1; shift ;;
        --skip-ssh-key)
            SKIP_SSH_KEY=1; shift ;;
        --cloudflare-token)
            CLOUDFLARE_TUNNEL_TOKEN="${2:-}"; CLOUDFLARE_TOKEN_SET=1; shift 2 ;;
        --cloudflare-token=*)
            CLOUDFLARE_TUNNEL_TOKEN="${1#*=}"; CLOUDFLARE_TOKEN_SET=1; shift ;;
        --install-cloudflare)
            INSTALL_CLOUDFLARE="${2:-}"; INSTALL_CLOUDFLARE_SET=1; shift 2 ;;
        --install-cloudflare=*)
            INSTALL_CLOUDFLARE="${1#*=}"; INSTALL_CLOUDFLARE_SET=1; shift ;;
        --skip-cloudflare)
            SKIP_CLOUDFLARE=1; shift ;;
        --headscale-url)
            HEADSCALE_URL="${2:-}"; HEADSCALE_URL_SET=1; shift 2 ;;
        --headscale-url=*)
            HEADSCALE_URL="${1#*=}"; HEADSCALE_URL_SET=1; shift ;;
        --extra-pubkey)
            EXTRA_PUBKEY="${2:-}"; EXTRA_PUBKEY_SET=1; shift 2 ;;
        --extra-pubkey=*)
            EXTRA_PUBKEY="${1#*=}"; EXTRA_PUBKEY_SET=1; shift ;;
        --lan-subnet)
            LAN_SUBNET="${2:-}"; LAN_SUBNET_SET=1; shift 2 ;;
        --lan-subnet=*)
            LAN_SUBNET="${1#*=}"; LAN_SUBNET_SET=1; shift ;;
        --tailscale-authkey)
            TAILSCALE_AUTHKEY="${2:-}"; TAILSCALE_AUTHKEY_SET=1; shift 2 ;;
        --tailscale-authkey=*)
            TAILSCALE_AUTHKEY="${1#*=}"; TAILSCALE_AUTHKEY_SET=1; shift ;;
        --install-python)
            INSTALL_PYTHON="${2:-}"; INSTALL_PYTHON_SET=1; shift 2 ;;
        --install-python=*)
            INSTALL_PYTHON="${1#*=}"; INSTALL_PYTHON_SET=1; shift ;;
        --install-office)
            INSTALL_OFFICE="${2:-}"; INSTALL_OFFICE_SET=1; shift 2 ;;
        --install-office=*)
            INSTALL_OFFICE="${1#*=}"; INSTALL_OFFICE_SET=1; shift ;;
        --install-antigravity)
            INSTALL_ANTIGRAVITY="${2:-}"; INSTALL_ANTIGRAVITY_SET=1; shift 2 ;;
        --install-antigravity=*)
            INSTALL_ANTIGRAVITY="${1#*=}"; INSTALL_ANTIGRAVITY_SET=1; shift ;;
        --install-firefox|--install-brave|--install-browser)
            INSTALL_FIREFOX="${2:-}"; INSTALL_FIREFOX_SET=1; shift 2 ;;
        --install-firefox=*|--install-brave=*|--install-browser=*)
            INSTALL_FIREFOX="${1#*=}"; INSTALL_FIREFOX_SET=1; shift ;;
        --install-oracle-tunnel)
            INSTALL_ORACLE_TUNNEL="${2:-}"; INSTALL_ORACLE_TUNNEL_SET=1; shift 2 ;;
        --install-oracle-tunnel=*)
            INSTALL_ORACLE_TUNNEL="${1#*=}"; INSTALL_ORACLE_TUNNEL_SET=1; shift ;;
        --skip-oracle-tunnel)
            SKIP_ORACLE_TUNNEL=1; shift ;;
        --oracle-host)
            ORACLE_HOST="${2:-}"; shift 2 ;;
        --oracle-host=*)
            ORACLE_HOST="${1#*=}"; shift ;;
        --oracle-user)
            ORACLE_USER="${2:-}"; shift 2 ;;
        --oracle-user=*)
            ORACLE_USER="${1#*=}"; shift ;;
        --oracle-ssh-port)
            ORACLE_SSH_PORT="${2:-}"; shift 2 ;;
        --oracle-ssh-port=*)
            ORACLE_SSH_PORT="${1#*=}"; shift ;;
        --oracle-local-port)
            ORACLE_LOCAL_PORT="${2:-}"; shift 2 ;;
        --oracle-local-port=*)
            ORACLE_LOCAL_PORT="${1#*=}"; shift ;;
        --oracle-remote-port)
            ORACLE_REMOTE_PORT="${2:-}"; shift 2 ;;
        --oracle-remote-port=*)
            ORACLE_REMOTE_PORT="${1#*=}"; shift ;;
        -h|--help)
            usage; exit 0 ;;
        *)
            echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

# Normalize yes/no-style flag values so y/Y/yes/true/1 etc. all count
# as "yes" -- the checks later on do a strict "= yes" comparison.
normalize_yesno() {
    case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
        y|yes|true|1) echo "yes" ;;
        *) echo "no" ;;
    esac
}
[ "$INSTALL_PYTHON_SET" -eq 1 ] && INSTALL_PYTHON="$(normalize_yesno "$INSTALL_PYTHON")"
[ "$INSTALL_OFFICE_SET" -eq 1 ] && INSTALL_OFFICE="$(normalize_yesno "$INSTALL_OFFICE")"
[ "$INSTALL_ANTIGRAVITY_SET" -eq 1 ] && INSTALL_ANTIGRAVITY="$(normalize_yesno "$INSTALL_ANTIGRAVITY")"
[ "$INSTALL_FIREFOX_SET" -eq 1 ] && INSTALL_FIREFOX="$(normalize_yesno "$INSTALL_FIREFOX")"
[ "$INSTALL_CLOUDFLARE_SET" -eq 1 ] && INSTALL_CLOUDFLARE="$(normalize_yesno "$INSTALL_CLOUDFLARE")"
[ "$INSTALL_ORACLE_TUNNEL_SET" -eq 1 ] && INSTALL_ORACLE_TUNNEL="$(normalize_yesno "$INSTALL_ORACLE_TUNNEL")"

# Resolve default user
if [ "$TARGET_USER_SET" -eq 0 ] || [ -z "$TARGET_USER" ]; then
    if [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ]; then
        TARGET_USER="$SUDO_USER"
    elif [ "$(id -u)" -ne 0 ]; then
        TARGET_USER="$(id -un)"
    elif [ -n "$(awk -F: '$3 == 1000 && $7 !~ /(nologin|false)$/ { print $1; exit }' /etc/passwd 2>/dev/null || true)" ]; then
        TARGET_USER="$(awk -F: '$3 == 1000 && $7 !~ /(nologin|false)$/ { print $1; exit }' /etc/passwd 2>/dev/null || true)"
    elif [ -n "$(awk -F: '$3 >= 1000 && $3 < 65534 && $7 !~ /(nologin|false)$/ { print $1; exit }' /etc/passwd 2>/dev/null || true)" ]; then
        TARGET_USER="$(awk -F: '$3 >= 1000 && $3 < 65534 && $7 !~ /(nologin|false)$/ { print $1; exit }' /etc/passwd 2>/dev/null || true)"
    else
        TARGET_USER="zen"
    fi
fi

# ============================================================
# sudo shim (Droidspaces rootfs commonly runs everything as root,
# with no `sudo` binary installed at all)
# ============================================================

if command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
elif [ "$(id -u)" -eq 0 ]; then
    SUDO=""
else
    echo "ERROR: not root and 'sudo' is not installed. Install sudo or run this script as root."
    exit 1
fi

# ============================================================
# Root password, default user, sshd (root-only section)
# ============================================================
# Everything in this block needs to run as root -- user creation,
# passwd, sudoers, and sshd config all require it. Once done, the
# script re-execs itself as $TARGET_USER so the rest (dotfiles, zsh,
# docker group, user config) is set up for the default user instead of root.

if [ "$(id -u)" -eq 0 ]; then
    echo "=================================================="
    echo " Droidspaces setup: root password + user '$TARGET_USER' + sshd"
    echo "=================================================="
    echo "Anything not passed as a flag gets asked for now."
    echo "(--user / --root-pass / --user-pass / --passphrase / --help)"
    echo

    KEY_DIR="$(pwd)"
    KEY_PATH="$KEY_DIR/${TARGET_USER}_droidspaces_ed25519"

    # ---- root password ----
    if [ "$ROOT_PASS_SET" -eq 0 ] && [ "$SKIP_ROOT_PASS" -eq 0 ]; then
        read -r -s -p "Root password (console/local access only, leave blank to skip): " ROOT_PASS; echo
        if [ -n "$ROOT_PASS" ]; then
            while true; do
                read -r -s -p "Confirm root password: " ROOT_PASS_CONFIRM; echo
                if [ "$ROOT_PASS" = "$ROOT_PASS_CONFIRM" ]; then
                    break
                else
                    echo "Passwords didn't match -- try again."
                    read -r -s -p "Root password (console/local access only, leave blank to skip): " ROOT_PASS; echo
                    [ -z "$ROOT_PASS" ] && { SKIP_ROOT_PASS=1; break; }
                fi
            done
        else
            SKIP_ROOT_PASS=1
        fi
    fi

    # ---- default user password ----
    if [ "$ZEN_PASS_SET" -eq 0 ] && [ "$SKIP_ZEN_PASS" -eq 0 ]; then
        read -r -s -p "$TARGET_USER password (local login + sudo, leave blank to skip): " ZEN_PASS; echo
        if [ -n "$ZEN_PASS" ]; then
            while true; do
                read -r -s -p "Confirm $TARGET_USER password: " ZEN_PASS_CONFIRM; echo
                if [ "$ZEN_PASS" = "$ZEN_PASS_CONFIRM" ]; then
                    break
                else
                    echo "Passwords didn't match -- try again."
                    read -r -s -p "$TARGET_USER password (local login + sudo, leave blank to skip): " ZEN_PASS; echo
                    [ -z "$ZEN_PASS" ] && { SKIP_ZEN_PASS=1; break; }
                fi
            done
        else
            SKIP_ZEN_PASS=1
        fi
    fi

    # ---- SSH key decision ----
    if [ "$SKIP_SSH_KEY" -eq 0 ] && [ "$GENERATE_SSH_KEY" -eq 0 ]; then
        if [ -f "$KEY_PATH" ]; then
            read -r -p "SSH keypair already exists at $KEY_PATH. Regenerate? [y/N]: " ans
            case "$ans" in
                [Yy]*) GENERATE_SSH_KEY=1 ;;
                *) SKIP_SSH_KEY=1 ;;
            esac
        else
            read -r -p "Generate new SSH keypair for $TARGET_USER at $KEY_PATH? [Y/n]: " ans
            case "$ans" in
                [Nn]*) SKIP_SSH_KEY=1 ;;
                *) GENERATE_SSH_KEY=1 ;;
            esac
        fi
    fi

    # ---- SSH key passphrase (only if generating key) ----
    if [ "$SKIP_SSH_KEY" -eq 0 ] && [ "$PASSPHRASE_SET" -eq 0 ]; then
        read -r -s -p "Passphrase for the generated SSH key (leave blank for none): " KEY_PASSPHRASE; echo
        if [ -n "$KEY_PASSPHRASE" ]; then
            read -r -s -p "Confirm passphrase: " KEY_PASSPHRASE_CONFIRM; echo
            if [ "$KEY_PASSPHRASE" != "$KEY_PASSPHRASE_CONFIRM" ]; then
                echo "Passphrases didn't match -- proceeding with no passphrase instead."
                KEY_PASSPHRASE=""
            fi
        fi
    fi

    # ---- Headscale / Tailscale ----
    if [ "$HEADSCALE_URL_SET" -eq 0 ]; then
        read -r -p "Headscale control server URL (leave blank to use tailscale.com's default): " HEADSCALE_URL
    fi

    # ---- extra SSH public key ----
    if [ "$EXTRA_PUBKEY_SET" -eq 0 ]; then
        read -r -p "Paste an extra SSH public key to authorize for $TARGET_USER now, or leave blank to skip: " EXTRA_PUBKEY
    fi

    # ---- LAN subnet for fail2ban's whitelist ----
    if [ "$LAN_SUBNET_SET" -eq 0 ]; then
        AUTO_LAN_SUBNET="$(ip -4 route show default 2>/dev/null | awk '{print $3}' | head -1 | sed -E 's/\.[0-9]+$/.0\/24/')"
        read -r -p "LAN subnet for fail2ban's whitelist [${AUTO_LAN_SUBNET:-e.g. 192.168.1.0/24}]: " LAN_SUBNET
        LAN_SUBNET="${LAN_SUBNET:-$AUTO_LAN_SUBNET}"
    fi
    echo

    # Root password, set for direct/emergency access
    if [ "$SKIP_ROOT_PASS" -eq 1 ] || [ -z "$ROOT_PASS" ]; then
        echo "Skipping root password setup."
    else
        echo "root:${ROOT_PASS}" | chpasswd
        echo "Root password set."
    fi

    # Create target user if it doesn't already exist
    ZSH_DEFAULT_SHELL="$(command -v zsh 2>/dev/null || echo "/usr/bin/zsh")"
    [ -x "$ZSH_DEFAULT_SHELL" ] || ZSH_DEFAULT_SHELL="/bin/bash"

    if ! id -u "$TARGET_USER" >/dev/null 2>&1; then
        EXISTING_UID1000="$(getent passwd 1000 | cut -d: -f1 || true)"
        if [ -n "$EXISTING_UID1000" ] && [ "$EXISTING_UID1000" != "$TARGET_USER" ]; then
            echo "NOTE: UID 1000 user '$EXISTING_UID1000' already exists; creating '$TARGET_USER'."
            useradd -m -s "$ZSH_DEFAULT_SHELL" "$TARGET_USER" 2>/dev/null || adduser -D -s "$ZSH_DEFAULT_SHELL" "$TARGET_USER" 2>/dev/null || true
        else
            useradd -m -u 1000 -s "$ZSH_DEFAULT_SHELL" "$TARGET_USER" 2>/dev/null || useradd -m -s "$ZSH_DEFAULT_SHELL" "$TARGET_USER" 2>/dev/null || true
        fi
        echo "Created user '$TARGET_USER'."
    else
        echo "User '$TARGET_USER' already exists."
    fi

    TARGET_HOME="$(getent passwd "$TARGET_USER" 2>/dev/null | cut -d: -f6 || true)"
    [ -n "$TARGET_HOME" ] || TARGET_HOME="/home/$TARGET_USER"
    mkdir -p "$TARGET_HOME"
    chown "$TARGET_USER:$TARGET_USER" "$TARGET_HOME" 2>/dev/null || true

    # Default user password
    if [ "$SKIP_ZEN_PASS" -eq 1 ] || [ -z "$ZEN_PASS" ]; then
        echo "Skipping $TARGET_USER password setup."
    else
        echo "$TARGET_USER:${ZEN_PASS}" | chpasswd
        echo "$TARGET_USER password set."
    fi

    # Sudo and hardware access groups
    usermod -aG sudo "$TARGET_USER" 2>/dev/null || usermod -aG wheel "$TARGET_USER" 2>/dev/null || true
    for grp in aid_inet aid_net_raw input video render droidspaces-gpu tty audio shadow; do
        if getent group "$grp" >/dev/null 2>&1; then
            usermod -aG "$grp" "$TARGET_USER" 2>/dev/null || true
        fi
    done

    # NOPASSWD sudo for default user
    mkdir -p /etc/sudoers.d
    echo "$TARGET_USER ALL=(ALL) NOPASSWD:ALL" | tee "/etc/sudoers.d/$TARGET_USER-nopasswd" >/dev/null
    chmod 440 "/etc/sudoers.d/$TARGET_USER-nopasswd"

    # sshd
    if ! command -v sshd >/dev/null 2>&1; then
        echo "Installing openssh-server..."
        apt update
        apt install -y openssh-server
    fi

    mkdir -p /etc/ssh/sshd_config.d
    cat > /etc/ssh/sshd_config.d/99-droidspaces.conf << SSHDEOF
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AllowUsers $TARGET_USER
SSHDEOF

    cat > "/etc/ssh/sshd_config.d/98-$TARGET_USER-keyonly.conf" << SSHDEOF
Match User $TARGET_USER
    PasswordAuthentication no
    PubkeyAuthentication yes
SSHDEOF

    mkdir -p "$TARGET_HOME/.ssh"
    touch "$TARGET_HOME/.ssh/authorized_keys"

    if [ "$SKIP_SSH_KEY" -eq 1 ]; then
        echo "Skipping SSH keypair generation."
        if [ -f "$KEY_PATH.pub" ]; then
            if ! grep -qxF "$(cat "$KEY_PATH.pub")" "$TARGET_HOME/.ssh/authorized_keys" 2>/dev/null; then
                cat "$KEY_PATH.pub" >> "$TARGET_HOME/.ssh/authorized_keys"
                echo "Added existing public key $KEY_PATH.pub to authorized_keys."
            fi
        fi
    else
        echo "Generating SSH keypair for $TARGET_USER at: $KEY_PATH"
        ssh-keygen -t ed25519 -f "$KEY_PATH" -N "$KEY_PASSPHRASE" -C "${TARGET_USER}@droidspaces-auto" -q <<< y >/dev/null

        grep -v "${TARGET_USER}@droidspaces-auto" "$TARGET_HOME/.ssh/authorized_keys" > "$TARGET_HOME/.ssh/authorized_keys.tmp" 2>/dev/null || true
        mv "$TARGET_HOME/.ssh/authorized_keys.tmp" "$TARGET_HOME/.ssh/authorized_keys"
        cat "$KEY_PATH.pub" >> "$TARGET_HOME/.ssh/authorized_keys"
        chmod 600 "$KEY_PATH"
        chmod 644 "$KEY_PATH.pub"
    fi

    # Extra pubkey
    if [ -n "$EXTRA_PUBKEY" ]; then
        if ! grep -qxF "$EXTRA_PUBKEY" "$TARGET_HOME/.ssh/authorized_keys" 2>/dev/null; then
            echo "$EXTRA_PUBKEY" >> "$TARGET_HOME/.ssh/authorized_keys"
            echo "Added extra public key to $TARGET_USER's authorized_keys."
        else
            echo "Extra public key already present in $TARGET_USER's authorized_keys, skipping."
        fi
    fi

    chmod 700 "$TARGET_HOME/.ssh"
    chmod 600 "$TARGET_HOME/.ssh/authorized_keys"
    chown -R "$TARGET_USER:$TARGET_USER" "$TARGET_HOME/.ssh"

    if command -v systemctl >/dev/null 2>&1; then
        systemctl enable --now ssh 2>/dev/null || systemctl enable --now sshd 2>/dev/null || true
    fi

    echo "sshd installed and enabled (key-only, $TARGET_USER only, no root SSH)."

    THIS_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
    echo
    echo "=================================================="
    echo " SSH key for $TARGET_USER -- HOW TO USE IT"
    echo "=================================================="
    echo "Private key : $KEY_PATH"
    echo "Public key  : $KEY_PATH.pub"
    if [ -n "$KEY_PASSPHRASE" ]; then
        echo "Passphrase  : set -- you'll be asked for it every time you use this key"
    else
        echo "Passphrase  : none -- the private key file itself is the only credential"
    fi
    echo
    echo "This key is on the DEVICE right now, in the directory you ran"
    echo "the script from. Copy the PRIVATE key off to whatever machine"
    echo "you'll be SSHing FROM (laptop, PC, etc.):"
    echo "  cat \"$KEY_PATH\""
    echo
    echo "On the machine you copy it to:"
    echo "  chmod 600 $(basename "$KEY_PATH")"
    echo "  ssh -i $(basename "$KEY_PATH") $TARGET_USER@${THIS_IP:-<device-ip>}"
    echo
    echo "Or add to that machine's ~/.ssh/config:"
    echo "  Host pad3"
    echo "    HostName ${THIS_IP:-<device-ip>}"
    echo "    User $TARGET_USER"
    echo "    IdentityFile ~/.ssh/$(basename "$KEY_PATH")"
    echo
    echo "Password login and root SSH are both disabled -- one of these keys is"
    echo "the only way in."
    if [ -n "$EXTRA_PUBKEY" ]; then
        echo
        echo "An extra public key was also authorized this run (see above)."
    fi
    echo "=================================================="
    echo

    # Re-exec the rest of this script as $TARGET_USER, so dotfiles/zsh/etc.
    # get set up for the default user instead of root.
    echo "Re-running setup as $TARGET_USER..."
    USER_RUN_COPY="/tmp/.setup_droidspaces_${TARGET_USER}_run.sh"
    cp "$0" "$USER_RUN_COPY"
    chmod 755 "$USER_RUN_COPY"

    exec sudo -u "$TARGET_USER" -H bash "$USER_RUN_COPY" "${ORIG_ARGS[@]}"
    echo "ERROR: failed to re-exec setup as $TARGET_USER. Aborting rather than continuing as root."
    exit 1
fi

# ============================================================
# Architecture detection (aarch64 on OnePlus Pad 3, but keep
# this generic in case the rootfs reports differently)
# ============================================================

ARCH="$(uname -m)"
case "$ARCH" in
    aarch64|arm64)
        DEB_ARCH="arm64"
        FASTFETCH_ARCH="aarch64"
        ;;
    armv7l|armhf)
        DEB_ARCH="armhf"
        FASTFETCH_ARCH="armhf"
        ;;
    x86_64)
        DEB_ARCH="amd64"
        FASTFETCH_ARCH="amd64"
        ;;
    *)
        echo "WARNING: unrecognized architecture '$ARCH' - defaulting to arm64 packages"
        DEB_ARCH="arm64"
        FASTFETCH_ARCH="aarch64"
        ;;
esac
echo "Detected architecture: $ARCH (deb arch: $DEB_ARCH)"

fix_apt_sources() {
    # Fix 404 errors when ports.ubuntu.com is queried for foreign architectures (e.g. amd64 on arm64 host)
    local base_arch="$(dpkg --print-architecture 2>/dev/null || echo "$DEB_ARCH")"
    if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
        if grep -q "ports.ubuntu.com" /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null && \
           ! grep -q "Architectures:" /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null; then
            $SUDO sed -i "/^URIs: http:\/\/ports.ubuntu.com/a Architectures: $base_arch" /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
        fi
    fi
}
fix_apt_sources

echo "=================================================="
echo " Installing packages"
echo "=================================================="

$SUDO apt update

$SUDO apt install -y \
    zsh git curl wget unzip zip tar tmux htop btop \
    fzf ripgrep fd-find bat ncdu jq tree \
    build-essential python3 python3-pip python3-venv \
    ca-certificates gnupg lsb-release

# ============================================================
# eza
# ============================================================

if ! command -v eza >/dev/null 2>&1; then
    echo "=================================================="
    echo " Installing eza"
    echo "=================================================="

    $SUDO mkdir -p /etc/apt/keyrings

    wget -qO- https://raw.githubusercontent.com/eza-community/eza/main/deb.asc \
        | $SUDO gpg --dearmor -o /etc/apt/keyrings/gierens.gpg

    echo "deb [signed-by=/etc/apt/keyrings/gierens.gpg] http://deb.gierens.de stable main" \
        | $SUDO tee /etc/apt/sources.list.d/gierens.list

    $SUDO chmod 644 /etc/apt/keyrings/gierens.gpg
    $SUDO apt update
    $SUDO apt install -y eza
fi

# ============================================================
# PATH (for the rest of THIS script run)
# ============================================================
# zoxide, uv, and bun all install into ~/.local/bin / ~/.bun/bin,
# neither of which is on PATH yet in this process. The .zshrc written
# further down adds this permanently for future shells, but without
# exporting it here too, every later step in this same script run --
# and the final summary's version checks -- would misreport these as
# "not found" even though they're installed (see the zoxide install
# warning in the log: "not on your $PATH ... will not work unless").
export PATH="$HOME/.local/bin:$HOME/bin:$HOME/.bun/bin:$PATH"

# ============================================================
# zoxide
# ============================================================

if ! command -v zoxide >/dev/null 2>&1; then
    echo "=================================================="
    echo " Installing zoxide"
    echo "=================================================="

    curl -sSfL https://raw.githubusercontent.com/ajeetdsouza/zoxide/main/install.sh | bash
fi

# ============================================================
# Oh My Zsh
# ============================================================

if [ ! -d "$HOME/.oh-my-zsh" ]; then
    echo "=================================================="
    echo " Installing Oh My Zsh"
    echo "=================================================="

    RUNZSH=no CHSH=no sh -c \
      "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)"
fi

# ============================================================
# Plugins
# ============================================================

ZSH_CUSTOM="${ZSH_CUSTOM:-$HOME/.oh-my-zsh/custom}"

mkdir -p "$ZSH_CUSTOM/plugins"

clone_if_missing() {
    [ -d "$2" ] || git clone "$1" "$2"
}

clone_if_missing https://github.com/zsh-users/zsh-autosuggestions \
    "$ZSH_CUSTOM/plugins/zsh-autosuggestions"

clone_if_missing https://github.com/zsh-users/zsh-syntax-highlighting \
    "$ZSH_CUSTOM/plugins/zsh-syntax-highlighting"

clone_if_missing https://github.com/zsh-users/zsh-completions \
    "$ZSH_CUSTOM/plugins/zsh-completions"

# ============================================================
# fd / bat aliases
# ============================================================

mkdir -p "$HOME/.local/bin"

[ ! -f "$HOME/.local/bin/fd" ] && ln -sf /usr/bin/fdfind "$HOME/.local/bin/fd"
[ ! -f "$HOME/.local/bin/bat" ] && ln -sf /usr/bin/batcat "$HOME/.local/bin/bat"

# ============================================================
# BIN scripts
# ============================================================

mkdir -p "$HOME/bin"

# eveupdate
cat > "$HOME/bin/eveupdate" << 'EOF'
#!/usr/bin/env bash
set -e
cd ~/docker
git pull
git submodule sync --recursive
git submodule update --init --recursive
EOF
chmod +x "$HOME/bin/eveupdate"

# everebuild
cat > "$HOME/bin/everebuild" << 'EOF'
#!/usr/bin/env bash
set -e
cd ~/docker
docker compose down eve
docker compose up -d --build eve
EOF
chmod +x "$HOME/bin/everebuild"

# ddeploy
cat > "$HOME/bin/ddeploy" << 'EOF'
#!/usr/bin/env bash
set -e
cd ~/docker
echo "[+] Pulling latest..."
git pull
echo "[+] Updating submodules..."
git submodule sync --recursive
git submodule update --init --recursive
echo "[+] Rebuilding containers..."
docker compose build
echo "[+] Restarting stack..."
docker compose up -d
echo "[+] Done."
EOF
chmod +x "$HOME/bin/ddeploy"

# evedeploy
cat > "$HOME/bin/evedeploy" << 'EOF'
#!/usr/bin/env bash
set -e
cd ~/docker
echo "[+] Pulling latest..."
git pull
echo "[+] Updating submodules..."
git submodule sync --recursive
git submodule update --init --recursive
echo "[+] Rebuilding eve containers..."
docker compose build eve eve_api eve_web
echo "[+] Restarting eve containers..."
docker compose up -d eve eve_api eve_web
echo "[+] Done."
EOF
chmod +x "$HOME/bin/evedeploy"

# evelogs
cat > "$HOME/bin/evelogs" << 'EOF'
#!/usr/bin/env bash
cd ~/docker
docker compose logs -f --tail=100 eve eve_api eve_web
EOF
chmod +x "$HOME/bin/evelogs"

# dockerstart - convenience wrapper to (re)start the system dockerd
# via systemd if it's not already up.
cat > "$HOME/bin/dockerstart" << 'EOF'
#!/usr/bin/env bash
set -e
if docker info >/dev/null 2>&1; then
    echo "dockerd already running."
    exit 0
fi
sudo systemctl restart docker
sleep 2
if docker info >/dev/null 2>&1; then
    echo "dockerd is running."
else
    echo "Failed to start. Check: sudo systemctl status docker / sudo journalctl -u docker -n 100"
    exit 1
fi
EOF
chmod +x "$HOME/bin/dockerstart"

# ============================================================
# ZSHRC
# ============================================================

cat > "$HOME/.zshrc" << 'EOF'
# Droidspaces sometimes attaches the terminal with cwd=/ regardless
# of which user is logged in, instead of starting in $HOME like a
# normal login shell would. Force it back if that happens.
if [ "$PWD" = "/" ]; then
    cd "$HOME" 2>/dev/null || cd /home/zen 2>/dev/null || true
fi

export ZSH="$HOME/.oh-my-zsh"

ZSH_THEME="robbyrussell"

plugins=(
    git docker python node npm fzf
    zsh-autosuggestions
    zsh-syntax-highlighting
    zsh-completions
)

source "$ZSH/oh-my-zsh.sh"

# ============================================================
# PATH
# ============================================================

export PATH="$HOME/.local/bin:$HOME/bin:$PATH"

# ============================================================
# CORE MODERN CLI
# ============================================================

alias ls='eza --icons --group-directories-first'
alias ll='eza -la --icons --git --group-directories-first'
alias la='eza -la --icons --git'
alias lt='eza --tree --icons --level=2'
alias tree='eza --tree --icons'

alias cat='bat --paging=never'
alias less='bat --paging=always'

alias find='fd'
alias grep='rg'
alias top='btop'
alias du='ncdu --color dark'
alias df='df -h'

# ============================================================
# HISTORY INTELLIGENCE (TERMIUS-LIKE)
# ============================================================

setopt SHARE_HISTORY
setopt HIST_IGNORE_ALL_DUPS
setopt HIST_REDUCE_BLANKS
setopt INC_APPEND_HISTORY

HISTSIZE=50000
SAVEHIST=50000

# ============================================================
# GIT
# ============================================================

alias gs='git status -sb'
alias ga='git add'
alias gaa='git add -A'
alias gc='git commit -m'
alias gca='git commit --amend --no-edit'
alias gpl='git pull'
alias gps='git push'
alias gpf='git push --force-with-lease'
alias gl='git pull --rebase'
alias gd='git diff'
alias gds='git diff --staged'
alias gb='git branch -vv'
alias gco='git checkout'
alias gsw='git switch'
alias glog='git log --oneline --graph --decorate --all'
alias gloga='git log --graph --oneline --decorate --all --color'
alias gst='git stash'
alias gstp='git stash pop'

# ============================================================
# SUBMODULES
# ============================================================

alias gsub='git submodule sync --recursive && git submodule update --init --recursive'
alias gsubr='git submodule sync --recursive && git submodule update --init --recursive --remote'

# ============================================================
# DOCKER
# ============================================================

alias dc='docker compose'
alias dcu='docker compose up -d'
alias dcub='docker compose up -d --build'
alias dcd='docker compose down'
alias dcr='docker compose restart'
alias dcl='docker compose logs -f'
alias dclf='docker compose logs -f --tail=100'

alias dps='docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"'
alias dpsa='docker ps -a --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"'

alias di='docker images --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}"'

alias dprune='docker system prune -f'
alias dclean='docker system prune -af --volumes'
alias dsize='docker system df'

# ============================================================
# Python
# ============================================================

alias py='python3'
alias pip='pip3'
alias venv='python3 -m venv'
alias activate='source venv/bin/activate'

# ============================================================
# tmux
# ============================================================

alias t='tmux'
alias ta='tmux attach -t'
alias tls='tmux ls'

# ============================================================
# FZF
# ============================================================
export FZF_DEFAULT_COMMAND='fd --type f --hidden --follow --exclude .git --exclude node_modules --exclude .venv --exclude __pycache__'
export FZF_CTRL_T_COMMAND="$FZF_DEFAULT_COMMAND"
export FZF_ALT_C_COMMAND='fd --type d --hidden --follow --exclude .git --exclude node_modules --exclude .venv'

export FZF_DEFAULT_OPTS='
--height=40%
--layout=reverse
--border
--preview "bat --color=always {} 2>/dev/null || cat {}"
'

# CTRL-R HISTORY SEARCH
export FZF_CTRL_R_OPTS="
--preview 'echo {}'
--preview-window=up:3:hidden:wrap
"

# ============================================================
# ZOXIDE (SMART CD)
# ============================================================

eval "$(zoxide init zsh --cmd cd)"

# ============================================================
# Claude Code / Omniroute configuration
# ============================================================

export ANTHROPIC_BASE_URL="${ANTHROPIC_BASE_URL:-http://127.0.0.1:20128}"
export ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-dummy}"

# ============================================================
# STARTUP FEEL (OPTIONAL)
# ============================================================

if command -v fastfetch >/dev/null 2>&1; then
    [[ $- == *i* ]] && fastfetch
fi
EOF

# ============================================================
# tmux config
# ============================================================

cat > "$HOME/.tmux.conf" << 'EOF'
set -g mouse on
set -g history-limit 50000
set -g status-bg black
set -g status-fg white
EOF

# ============================================================
# fastfetch
# ============================================================

if ! command -v fastfetch >/dev/null 2>&1; then
    echo "Installing fastfetch..."
    wget -qO /tmp/fastfetch.deb \
        "https://github.com/fastfetch-cli/fastfetch/releases/latest/download/fastfetch-linux-${FASTFETCH_ARCH}.deb"
    $SUDO dpkg -i /tmp/fastfetch.deb
    rm /tmp/fastfetch.deb
fi

# ============================================================
# delta
# ============================================================

if ! command -v delta >/dev/null 2>&1; then
    echo "Installing delta..."
    DELTA_VERSION=$(curl -fsSL https://api.github.com/repos/dandavison/delta/releases/latest \
        | grep '"tag_name"' | cut -d'"' -f4)
    curl -fsSL \
        "https://github.com/dandavison/delta/releases/download/${DELTA_VERSION}/git-delta_${DELTA_VERSION}_${DEB_ARCH}.deb" \
        -o "$HOME/delta.deb"
    $SUDO dpkg -i "$HOME/delta.deb"
    rm "$HOME/delta.deb"
    delta --version
fi

# ============================================================
# uv
# ============================================================

if ! command -v uv >/dev/null 2>&1; then
    curl -LsSf https://astral.sh/uv/install.sh | sh
fi

# ============================================================
# Node 22
# ============================================================

if ! command -v node >/dev/null 2>&1; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | $SUDO bash -
    $SUDO apt install -y nodejs
fi

# ============================================================
# bun
# ============================================================

if ! command -v bun >/dev/null 2>&1; then
    curl -fsSL https://bun.sh/install | bash
fi

# ============================================================
# Claude CLI (@anthropic-ai/claude-code)
# ============================================================

if ! command -v claude >/dev/null 2>&1; then
    echo "=================================================="
    echo " Installing Claude CLI"
    echo "=================================================="
    $SUDO npm install -g --no-fund --no-audit @anthropic-ai/claude-code || true
fi

# ============================================================
# GitHub CLI (gh)
# ============================================================

if ! command -v gh >/dev/null 2>&1; then
    echo "=========================================="
    echo " Installing GitHub CLI (gh)"
    echo "=========================================="

    $SUDO install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        | $SUDO dd of=/etc/apt/keyrings/githubcli-archive-keyring.gpg
    $SUDO chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
    echo "deb [arch=${DEB_ARCH} signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        | $SUDO tee /etc/apt/sources.list.d/github-cli.list >/dev/null
    $SUDO apt update
    $SUDO apt install -y gh
fi

# ============================================================
# iptables-legacy compatibility layer
# ============================================================
# Modern Ubuntu/Debian default to the nftables backend, which
# commonly fails with "chain not found" errors in Droidspaces
# containers on Android kernels. Both Docker's networking and UFW
# below need the legacy backend to work reliably here.

$SUDO apt install -y iptables
if command -v update-alternatives >/dev/null 2>&1; then
    $SUDO update-alternatives --set iptables /usr/sbin/iptables-legacy 2>/dev/null || true
    $SUDO update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy 2>/dev/null || true
fi

# ============================================================
# Docker
# ============================================================

if command -v docker >/dev/null 2>&1 && systemctl list-unit-files docker.service >/dev/null 2>&1; then
    echo "Docker already installed - skipping install."
else
    echo "=================================================="
    echo " Installing Docker"
    echo "=================================================="

    $SUDO apt install -y ca-certificates curl gnupg
    $SUDO install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /tmp/docker.asc
    $SUDO mv /tmp/docker.asc /etc/apt/keyrings/docker.asc
    $SUDO chmod a+r /etc/apt/keyrings/docker.asc

    os_distro="ubuntu"
    [ "$(. /etc/os-release 2>/dev/null && echo "${ID:-ubuntu}")" = "debian" ] && os_distro="debian"
    os_code="$(. /etc/os-release 2>/dev/null && echo "${UBUNTU_CODENAME:-${VERSION_CODENAME:-noble}}")"

    echo "deb [arch=${DEB_ARCH} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${os_distro} ${os_code} stable" \
        | $SUDO tee /etc/apt/sources.list.d/docker.list >/dev/null

    $SUDO apt update
    $SUDO apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

# This kernel doesn't have CONFIG_CGROUP_DEVICE (removing it fixed a
# bootloop caused by breaking the GKI vendor module ABI), and this
# rootfs runs on the legacy cgroup v1 hierarchy rather than a v2
# unified one, so Docker can't use its usual BPF-based device cgroup
# either. cgroupfs avoids the driver negotiation that fails over
# this, and vfs avoids stacking Docker's overlay2 storage driver on
# top of Droidspaces' own OverlayFS rootfs (which doesn't work).
$SUDO mkdir -p /etc/docker
cat << 'EOF' | $SUDO tee /etc/docker/daemon.json >/dev/null
{
  "exec-opts": ["native.cgroupdriver=cgroupfs"],
  "storage-driver": "overlay2",
  "dns": ["8.8.8.8", "1.1.1.1"],
  "mtu": 1420,
  "ipv6": false,
  "features": {
    "buildkit": true
  }
}
EOF

if command -v systemctl >/dev/null 2>&1; then
    $SUDO systemctl enable --now containerd 2>/dev/null || true
    $SUDO systemctl enable --now docker
fi

# Let zen run docker without sudo. Note this is effectively
# root-equivalent -- anyone in the docker group can mount the host
# filesystem via a container and get full root, so this is only as
# safe as zen's own account.
$SUDO usermod -aG docker "$(id -un)"

# ============================================================
# Tailscale (optionally via your own Headscale server)
# ============================================================

# Pull HEADSCALE_AUTH_KEY / TAILSCALE_DOMAIN out of .env (same search order as
# the Cloudflare token below) unless the user already passed an explicit
# --headscale-url / --tailscale-authkey flag. This is the standard override
# order: CLI flag > .env > interactive prompt > (now-empty) default.
if [ "$HEADSCALE_URL_SET" -eq 0 ] || [ "$TAILSCALE_AUTHKEY_SET" -eq 0 ]; then
    for env_file in ".env" "$HOME/docker/.env" "/home/zen/docker/.env" "/root/docker/.env"; do
        [ -f "$env_file" ] || continue

        if [ "$HEADSCALE_URL_SET" -eq 0 ] && [ -z "$HEADSCALE_URL" ]; then
            DOMAIN_FROM_ENV="$(grep -E '^TAILSCALE_DOMAIN=' "$env_file" 2>/dev/null | cut -d'=' -f2- | tr -d '"' | tr -d "'" | tr -d '\r')" || true
            if [ -n "$DOMAIN_FROM_ENV" ]; then
                case "$DOMAIN_FROM_ENV" in
                    http://*|https://*) HEADSCALE_URL="$DOMAIN_FROM_ENV" ;;
                    *)                  HEADSCALE_URL="https://$DOMAIN_FROM_ENV" ;;
                esac
                echo "Read TAILSCALE_DOMAIN from $env_file -> login-server $HEADSCALE_URL"
            fi
        fi

        if [ "$TAILSCALE_AUTHKEY_SET" -eq 0 ] && [ -z "$TAILSCALE_AUTHKEY" ]; then
            KEY_FROM_ENV="$(grep -E '^HEADSCALE_AUTH_KEY=' "$env_file" 2>/dev/null | cut -d'=' -f2- | tr -d '"' | tr -d "'" | tr -d '\r')" || true
            if [ -n "$KEY_FROM_ENV" ]; then
                TAILSCALE_AUTHKEY="$KEY_FROM_ENV"
                echo "Read HEADSCALE_AUTH_KEY from $env_file"
            fi
        fi
    done
fi

if command -v tailscale >/dev/null 2>&1; then
    echo "Tailscale already installed - skipping install."
else
    echo "=================================================="
    echo " Installing Tailscale"
    echo "=================================================="
    curl -fsSL https://tailscale.com/install.sh | $SUDO sh
fi

$SUDO systemctl enable --now tailscaled 2>/dev/null || true
echo -n "Waiting for tailscaled to come up"
TAILSCALED_READY=0
for i in $(seq 1 15); do
    if $SUDO tailscale status >/dev/null 2>&1; then
        TAILSCALED_READY=1
        echo " ready."
        break
    fi
    echo -n "."
    sleep 1
done
if [ "$TAILSCALED_READY" -eq 0 ]; then
    echo
    echo "WARNING: tailscaled still isn't responding after 15s -- skipping Tailscale"
    echo "auth, the rest of the script will continue. Check: sudo systemctl status"
    echo "tailscaled / sudo journalctl -u tailscaled -n 50"
fi

TAILSCALE_IP=""
if [ "$TAILSCALED_READY" -eq 1 ]; then
    # Is Tailscale currently up (has an IP)? Note: everything that reads this via
    # command substitution is guarded with '|| true' -- under 'set -euo pipefail',
    # a bare assignment like X="$(cmd | head -1)" aborts the ENTIRE script the
    # instant 'cmd' returns non-zero, which is what silently killed the rest of
    # this script (autossh/ssh setup never ran) on the previous run.
    TS_CUR_IP="$($SUDO tailscale ip -4 2>/dev/null | head -1)" || true

    if [ -n "$TS_CUR_IP" ]; then
        echo "Tailscale is already up (IP: $TS_CUR_IP) -- bringing it down so it can be"
        echo "re-authed cleanly with the configured settings."
        set +e
        timeout 15 $SUDO tailscale down >/dev/null 2>&1
        TS_DOWN_RC=$?
        set -e
        if [ "$TS_DOWN_RC" -ne 0 ]; then
            echo "WARNING: 'tailscale down' didn't complete cleanly (exit $TS_DOWN_RC) -- continuing anyway."
        fi
    fi

    TS_UP_CMD=(tailscale up)
    if [ -n "$HEADSCALE_URL" ]; then
        TS_UP_CMD+=(--login-server="$HEADSCALE_URL")
    fi
    if [ -n "$TAILSCALE_AUTHKEY" ]; then
        TS_UP_CMD+=(--authkey="$TAILSCALE_AUTHKEY")
    fi
    # Note: this only sets the *node's* Tailscale hostname to the current OS
    # user -- it does NOT change which Headscale user/namespace the node
    # registers under. That's determined entirely by which Headscale user the
    # preauth key was generated for on the server side (see chat for how to
    # do that from the CLI).
    TS_UP_CMD+=(--hostname="$(id -un)-droidspaces")

    if [ -n "$TAILSCALE_AUTHKEY" ]; then
        echo "Bringing up Tailscale with the provided pre-auth key (non-interactive, 30s cap):"
    else
        echo "Bringing up Tailscale (30s cap) -- this will print a URL to authenticate in a"
        echo "browser if this node isn't already registered:"
    fi

    # Wrapped in the shell's own 'timeout' (not just tailscale's --timeout flag,
    # which only bounds part of the handshake) so a stuck control-server
    # negotiation can never block the rest of the script. set +e/-e around it so
    # a non-zero exit here is handled by us, not by the script's global set -e.
    set +e
    timeout 30 $SUDO "${TS_UP_CMD[@]}"
    TS_UP_RC=$?
    set -e
    case "$TS_UP_RC" in
        0)   : ;;
        124) echo "WARNING: 'tailscale up' got stuck and was killed after 30s -- skipping, run it manually later." ;;
        *)   echo "WARNING: 'tailscale up' failed (exit $TS_UP_RC) -- skipping, run it manually later." ;;
    esac

    TAILSCALE_IP="$($SUDO tailscale ip -4 2>/dev/null | head -1)" || true
fi

if [ -n "$TAILSCALE_IP" ]; then
    echo "Tailscale IP: $TAILSCALE_IP"
else
    echo "NOTE: couldn't read a Tailscale IP yet -- if 'tailscale up' printed an auth"
    echo "URL above, finish that in a browser, then re-run 'sudo tailscale ip -4'."
fi

# ============================================================
# UFW (firewall)
# ============================================================
# Droidspaces NAT mode only supports IPv4 currently, so IPv6 is
# disabled in UFW to avoid init errors. Only the Tailscale interface
# is trusted inbound -- everything else on the public/LAN side is
# denied by default.

$SUDO apt install -y ufw
$SUDO sed -i 's/IPV6=yes/IPV6=no/' /etc/default/ufw
$SUDO ufw default deny incoming
$SUDO ufw default allow outgoing
$SUDO ufw allow in on tailscale0
$SUDO ufw --force enable

# ============================================================
# Fail2ban (brute-force protection)
# ============================================================

$SUDO apt install -y fail2ban

# Whitelist: this node's own Tailscale IP, the Droidspaces internal
# NAT bridge (covers all containers), and your LAN subnet (auto-
# detected above from the default route, or whatever you set via
# --lan-subnet). Anything in ignoreip is never banned, so this list
# is what keeps you from locking yourself out.
IGNORE_IPS="127.0.0.1/8 172.28.0.0/16"
[ -n "$TAILSCALE_IP" ] && IGNORE_IPS="$IGNORE_IPS $TAILSCALE_IP"
[ -n "$LAN_SUBNET" ] && IGNORE_IPS="$IGNORE_IPS $LAN_SUBNET"

cat << EOF | $SUDO tee /etc/fail2ban/jail.local >/dev/null
[DEFAULT]
# Ban for 1 hour after 5 failed attempts within 10 minutes
bantime  = 1h
findtime = 10m
maxretry = 5

# Use UFW as the banning action
banaction = ufw

# See the whitelist comment above this file's generation in
# setup_droidspaces.sh for what each entry here covers.
ignoreip = ${IGNORE_IPS}

[sshd]
enabled = true
port    = ssh
backend = systemd
EOF

$SUDO systemctl restart fail2ban
$SUDO fail2ban-client status sshd || true

echo
echo "=================================================="
echo " Mobile server summary"
echo "=================================================="
echo "Tailscale IP : ${TAILSCALE_IP:-not yet authenticated, see note above}"
echo "UFW          : denies all inbound except the tailscale0 interface"
echo "Fail2ban     : watching sshd, ignoreip = ${IGNORE_IPS}"
echo
echo "SSH in over Tailscale once authenticated:"
echo "  ssh -i zen_droidspaces_ed25519 zen@${TAILSCALE_IP:-<tailscale-ip>}"
echo "=================================================="

# ============================================================
# Cloudflare Tunnel (cloudflared)
# ============================================================

if [ "$SKIP_CLOUDFLARE" -eq 1 ] || [ "$INSTALL_CLOUDFLARE" = "no" ]; then
    echo "=================================================="
    echo " Cloudflare Tunnel (cloudflared)"
    echo "=================================================="
    echo "Skipping Cloudflare Tunnel installation as requested."
else
    if [ -z "${CLOUDFLARE_TUNNEL_TOKEN:-}" ]; then
        for env_file in ".env" "$HOME/docker/.env" "/home/zen/docker/.env" "/root/docker/.env"; do
            if [ -f "$env_file" ]; then
                TOKEN_FROM_ENV="$(grep -E '^CLOUDFLARE_TUNNEL_TOKEN=' "$env_file" 2>/dev/null | cut -d'=' -f2- | tr -d '"' | tr -d "'" | tr -d '\r' || true)"
                if [ -n "$TOKEN_FROM_ENV" ]; then
                    CLOUDFLARE_TUNNEL_TOKEN="$TOKEN_FROM_ENV"
                    echo "Read CLOUDFLARE_TUNNEL_TOKEN from $env_file"
                    break
                fi
            fi
        done
    fi

    if [ "$INSTALL_CLOUDFLARE_SET" -eq 0 ] && [ "$CLOUDFLARE_TOKEN_SET" -eq 0 ] && [ -z "$CLOUDFLARE_TUNNEL_TOKEN" ]; then
        if [ -t 0 ]; then
            read -r -p "Install and configure Cloudflare Tunnel? [y/N]: " ans
            case "$ans" in [Yy]*) INSTALL_CLOUDFLARE=yes ;; *) INSTALL_CLOUDFLARE=no ;; esac
            if [ "$INSTALL_CLOUDFLARE" = "yes" ] && [ -z "$CLOUDFLARE_TUNNEL_TOKEN" ]; then
                read -r -p "Enter Cloudflare Tunnel token (leave blank to skip): " CLOUDFLARE_TUNNEL_TOKEN
            fi
        else
            INSTALL_CLOUDFLARE=no
        fi
    elif [ "$INSTALL_CLOUDFLARE_SET" -eq 0 ] && [ -n "$CLOUDFLARE_TUNNEL_TOKEN" ]; then
        INSTALL_CLOUDFLARE=yes
    fi

    if [ "${INSTALL_CLOUDFLARE:-no}" = "yes" ] && [ -n "${CLOUDFLARE_TUNNEL_TOKEN:-}" ]; then
        echo "=================================================="
        echo " Installing and configuring Cloudflare Tunnel"
        echo "=================================================="

        if ! command -v cloudflared >/dev/null 2>&1; then
            echo "Downloading cloudflared.deb..."
            curl -L --output /tmp/cloudflared.deb "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${DEB_ARCH}.deb"
            $SUDO dpkg -i /tmp/cloudflared.deb
            rm -f /tmp/cloudflared.deb
        else
            echo "cloudflared is already installed."
        fi

        if command -v systemctl >/dev/null 2>&1; then
            echo "Installing cloudflared service with tunnel token..."
            if systemctl list-unit-files cloudflared.service >/dev/null 2>&1 || [ -f /etc/systemd/system/cloudflared.service ]; then
                $SUDO cloudflared service uninstall 2>/dev/null || true
            fi
            $SUDO cloudflared service install "$CLOUDFLARE_TUNNEL_TOKEN" || true
            $SUDO systemctl enable --now cloudflared
            echo -n "cloudflared service status: "
            systemctl is-enabled cloudflared || echo "disabled or not installed"
        else
            echo "WARNING: systemctl not available; cloudflared service could not be enabled."
        fi
    else
        echo "=================================================="
        echo " Cloudflare Tunnel (cloudflared)"
        echo "=================================================="
        echo "CLOUDFLARE_TUNNEL_TOKEN not provided or Cloudflare Tunnel disabled -- skipping Cloudflare Tunnel service installation."
    fi
fi

# ============================================================
# Oracle relay tunnel (SSH reverse tunnel via autossh)
# ============================================================
# For domains you don't control DNS for at the zone level (so
# Cloudflare Tunnel's <uuid>.cfargotunnel.com CNAME can't be used --
# it only routes hostnames that live in your own Cloudflare account)
# and when this device sits behind CGNAT (no usable public IP for
# direct port-forwarding + ACME HTTP-01), the working pattern is:
#   Internet -> Oracle (public IP, real Let's Encrypt cert via Caddy)
#            -> SSH reverse tunnel (outbound FROM here, so it works
#               fine behind CGNAT/NAT of any kind)
#            -> this device's own reverse proxy on ORACLE_LOCAL_PORT
#
# This only rides the SSH port that's already open for normal admin
# access -- no new inbound port needs to be opened on the Oracle side
# (Security List/NSG changes aren't needed), which matters if you
# don't have console access to open new ports there.

if [ "$SKIP_ORACLE_TUNNEL" -eq 1 ] || [ "$INSTALL_ORACLE_TUNNEL" = "no" ]; then
    echo "=================================================="
    echo " Oracle relay tunnel"
    echo "=================================================="
    echo "Skipping Oracle relay tunnel setup as requested."
else
    if [ "$INSTALL_ORACLE_TUNNEL_SET" -eq 0 ] && [ -z "$ORACLE_HOST" ]; then
        if [ -t 0 ]; then
            read -r -p "Set up an SSH reverse tunnel to an Oracle (or other) VPS relay? [y/N]: " ans
            case "$ans" in [Yy]*) INSTALL_ORACLE_TUNNEL=yes ;; *) INSTALL_ORACLE_TUNNEL=no ;; esac
            if [ "$INSTALL_ORACLE_TUNNEL" = "yes" ]; then
                read -r -p "Oracle VPS public IP or hostname: " ORACLE_HOST
                read -r -p "SSH user on Oracle [${ORACLE_USER}]: " ans; [ -n "$ans" ] && ORACLE_USER="$ans"
                read -r -p "SSH port on Oracle [${ORACLE_SSH_PORT}]: " ans; [ -n "$ans" ] && ORACLE_SSH_PORT="$ans"
                read -r -p "Local port here to forward (your reverse proxy) [${ORACLE_LOCAL_PORT}]: " ans; [ -n "$ans" ] && ORACLE_LOCAL_PORT="$ans"
                read -r -p "Bind port on Oracle, 127.0.0.1 only [${ORACLE_REMOTE_PORT}]: " ans; [ -n "$ans" ] && ORACLE_REMOTE_PORT="$ans"
            fi
        else
            INSTALL_ORACLE_TUNNEL=no
        fi
    elif [ "$INSTALL_ORACLE_TUNNEL_SET" -eq 0 ] && [ -n "$ORACLE_HOST" ]; then
        INSTALL_ORACLE_TUNNEL=yes
    fi

    if [ "${INSTALL_ORACLE_TUNNEL:-no}" = "yes" ] && [ -n "${ORACLE_HOST:-}" ]; then
        echo "=================================================="
        echo " Setting up Oracle relay tunnel"
        echo "=================================================="

        $SUDO apt install -y autossh

        TUNNEL_KEY="$HOME/.ssh/oracle_tunnel"
        if [ ! -f "$TUNNEL_KEY" ]; then
            echo "Generating dedicated SSH key for the tunnel at $TUNNEL_KEY ..."
            mkdir -p "$HOME/.ssh"
            chmod 700 "$HOME/.ssh"
            ssh-keygen -t ed25519 -f "$TUNNEL_KEY" -N "" -C "oracle-tunnel@$(hostname)"
        else
            echo "Reusing existing tunnel key at $TUNNEL_KEY"
        fi

        echo
        echo "-----------------------------------------------------------"
        echo " ACTION NEEDED on the Oracle side (do this before continuing"
        echo " if you haven't already -- the tunnel service will just keep"
        echo " retrying/failing until the key is authorized there):"
        echo
        echo "   echo '$(cat "${TUNNEL_KEY}.pub")' >> ~/.ssh/authorized_keys"
        echo
        echo " Also make sure on Oracle: /etc/ssh/sshd_config has"
        echo " AllowTcpForwarding not set to 'no' (default 'yes' is fine),"
        echo " then: sudo systemctl restart sshd  (only needed if you change it)"
        echo "-----------------------------------------------------------"
        echo

        CURRENT_USER="$(id -un)"
        CURRENT_HOME="$HOME"

        $SUDO tee /etc/systemd/system/oracle-tunnel.service > /dev/null << TUNNELEOF
[Unit]
Description=SSH reverse tunnel to Oracle relay
After=network-online.target
Wants=network-online.target

[Service]
User=${CURRENT_USER}
ExecStart=/usr/bin/autossh -M 0 -N \\
  -o "ServerAliveInterval 10" \\
  -o "ServerAliveCountMax 2" \\
  -o "ExitOnForwardFailure yes" \\
  -o "StrictHostKeyChecking accept-new" \\
  -i ${TUNNEL_KEY} \\
  -p ${ORACLE_SSH_PORT} \\
  -R 127.0.0.1:${ORACLE_REMOTE_PORT}:127.0.0.1:${ORACLE_LOCAL_PORT} \\
  ${ORACLE_USER}@${ORACLE_HOST}
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
TUNNELEOF

        $SUDO systemctl daemon-reload
        $SUDO systemctl enable --now oracle-tunnel.service

        echo -n "oracle-tunnel service status: "
        systemctl is-active oracle-tunnel.service || echo "not active yet -- check: sudo journalctl -u oracle-tunnel -n 50"
        echo
        echo "Once the key is authorized on Oracle, verify from there with:"
        echo "  ss -tlnp | grep ${ORACLE_REMOTE_PORT}"
        echo "  curl -v http://127.0.0.1:${ORACLE_REMOTE_PORT}"
    else
        echo "=================================================="
        echo " Oracle relay tunnel"
        echo "=================================================="
        echo "ORACLE_HOST not provided or Oracle relay tunnel disabled -- skipping."
    fi
fi

# ============================================================
# XFCE + X11 autostart
# ============================================================
# This wires the exact pattern Droidspaces' official XFCE tarballs
# use (see Graphics-and-Audio.md), for a plain Ubuntu rootfs that
# doesn't already have it. Everything below runs INSIDE the
# container -- it cannot install Termux:X11 itself, enable GPU/X11
# toggles, or set XFCE_USER, since those are Droidspaces app/host
# settings outside this container entirely. See the printed notes
# below for what you still need to do on the Android side.

$SUDO apt install -y xfce4 xfce4-terminal

$SUDO tee /usr/local/bin/xfce-start > /dev/null << 'XFCESTARTEOF'
#!/bin/sh

ENV_FILE=/run/droidspaces.env
CONFIG=/run/droidspaces/container.config

# If Anland Wayland mode is active, skip Termux:X11 XFCE start
if [ -S /run/display.sock ] || [ "${ANLAND:-}" = "1" ] || grep -q 'enable_anland=1' "$CONFIG" 2>/dev/null; then
    echo "Anland Wayland session active -- skipping Termux:X11 XFCE start"
    exit 0
fi

if [ -f "$ENV_FILE" ]; then
    . "$ENV_FILE"
    WHITELIST=$(sed -n 's/^export \([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' "$ENV_FILE" | tr '\n' ',' | sed 's/,$//')
else
    export DISPLAY=:5
    WHITELIST=DISPLAY
    if grep -q 'enable_pulseaudio=1' "$CONFIG" 2>/dev/null; then
        export PULSE_SERVER=unix:/tmp/.pulse-socket
        WHITELIST="$WHITELIST,PULSE_SERVER"
    fi
    # Turnip (native Adreno): GPU access enabled, VirGL disabled.
    # MESA_LOADER_DRIVER_OVERRIDE=kgsl tells Mesa to load the Turnip
    # driver instead of the virpipe/softpipe gallium drivers.
    # TU_DEBUG=noconform skips conformance checks that fail on mobile.
    # VirGL is mutually exclusive with Turnip -- enable only one.
    if grep -q 'enable_gpu=1' "$CONFIG" 2>/dev/null && \
       ! grep -q 'enable_virgl=1' "$CONFIG" 2>/dev/null; then
        export MESA_LOADER_DRIVER_OVERRIDE=kgsl
        export TU_DEBUG=noconform
        WHITELIST="$WHITELIST,MESA_LOADER_DRIVER_OVERRIDE,TU_DEBUG"
    elif grep -q 'enable_virgl=1' "$CONFIG" 2>/dev/null; then
        # VirGL (non-Qualcomm): use virpipe gallium driver.
        export GALLIUM_DRIVER=virpipe
        WHITELIST="$WHITELIST,GALLIUM_DRIVER"
    fi
fi

if [ -n "$XFCE_USER" ]; then
    exec su -l -w "$WHITELIST" "$XFCE_USER" -c 'exec /usr/bin/startxfce4'
else
    AUTO_USER=$(awk -F: '$3 >= 1000 && $3 < 65534 && $7 !~ /(nologin|false)$/ {print $1; exit}' /etc/passwd 2>/dev/null || echo "")
    if [ -n "$AUTO_USER" ]; then
        exec su -l -w "$WHITELIST" "$AUTO_USER" -c 'exec /usr/bin/startxfce4'
    else
        exec /usr/bin/startxfce4
    fi
fi
XFCESTARTEOF
$SUDO chmod +x /usr/local/bin/xfce-start

$SUDO tee /etc/systemd/system/xfce-autostart.service > /dev/null << 'XFCESVCEOF'
[Unit]
Description=XFCE Autostart (Termux:X11 + llvmpipe / VirGL / Turnip)
After=graphical.target

[Service]
Type=simple
User=root
# Termux:X11 must be configured and the X socket must exist.
ExecCondition=/bin/sh -c "grep -q 'enable_termux_x11=1' /run/droidspaces/container.config"
ExecCondition=/bin/sh -c "test -S /tmp/.X11-unix/X5"
ExecStart=/usr/local/bin/xfce-start
Restart=on-failure
RestartSec=3

[Install]
WantedBy=graphical.target
XFCESVCEOF

$SUDO systemctl enable xfce-autostart.service

echo
echo "=================================================="
echo " XFCE / X11 autostart"
echo "=================================================="
echo "Installed and enabled xfce-autostart.service -- it stays dormant"
echo "(both ExecConditions just fail silently) until these host-side"
echo "steps are done, which this script CANNOT do for you:"
echo "  1. Install the Termux and Termux:X11 apps on the device."
echo "  2. In Termux, run (once): curl -fsSL"
echo "     https://github.com/ravindu644/Droidspaces-OSS/raw/refs/heads/dev/scripts/setup-termux.sh | bash"
echo "  3. In the Droidspaces app's container config, enable 'Configure Termux:X11'."
echo "  4. In the same config's Environment Variables, set: XFCE_USER=zen"
echo "     (without this, xfce-start runs XFCE as root instead of zen)"
echo "Once those are done, XFCE launches automatically on container start"
echo "and appears in the Termux:X11 app."
echo "=================================================="

# ============================================================
# Turnip (native Qualcomm/Adreno GPU driver)
# ============================================================
# Turnip is the preferred GPU driver for Qualcomm/Adreno devices.
# The Mesa driver itself is installed on the ANDROID HOST via a
# separate project -- this script cannot install it inside the
# container. What it does:
#   - Writes the required env vars to /etc/profile.d/turnip-env.sh
#     (sourced by login shells for interactive use)
#   - The xfce-start script above also injects them for XFCE
#     when GPU access is on and VirGL is off

cat << 'TURNIPEOF' | $SUDO tee /etc/profile.d/turnip-env.sh > /dev/null
# Turnip (native Adreno) driver environment variables.
# These are picked up automatically for login shells when GPU access
# is enabled in Droidspaces and VirGL is disabled.
# See: https://github.com/ravindu644/Droidspaces-OSS/blob/main/Documentation/Graphics-and-Audio.md
export MESA_LOADER_DRIVER_OVERRIDE=kgsl
export TU_DEBUG=noconform
TURNIPEOF

echo
echo "=========================================="
echo " Turnip GPU driver (Qualcomm/Adreno)"
echo "=========================================="
echo "env vars written to /etc/profile.d/turnip-env.sh (login shells)"
echo "and injected into XFCE autostart via xfce-start."
echo
echo "Turnip driver is NOT installed by this script -- it lives on the"
echo "Android host. To complete the setup:"
echo
echo "  HOST (Android) side:"
echo "  1. Install the custom Mesa/Turnip driver package:"
echo "       https://github.com/lfdevs/mesa-for-android-container"
echo
echo "  Droidspaces app -> Edit container configuration:"
echo "  2. Enable  : 'GPU Access'"
echo "  3. Enable  : 'Configure Termux:X11'"
echo "  4. DISABLE : 'Configure VirGL 3D Acceleration' (incompatible with Turnip)"
echo "  5. Add env vars in the container config (belt-and-suspenders):"
echo "       MESA_LOADER_DRIVER_OVERRIDE=kgsl"
echo "       TU_DEBUG=noconform"
echo
echo "  Verify inside container (after Termux:X11 is open):"
echo "       glxinfo | grep -i renderer"
echo "  Expected: 'Turnip Adreno (TM)' in the renderer string."
echo "=========================================="

# ============================================================
# Optional programs
# ============================================================

echo
echo "=================================================="
echo " Optional programs"
echo "=================================================="

if [ "$INSTALL_PYTHON_SET" -eq 0 ] && [ -z "${INSTALL_PYTHON:-}" ]; then
    read -r -p "Install Python 3 (+pip, venv)? [Y/n]: " ans
    case "$ans" in [Nn]*) INSTALL_PYTHON=no ;; *) INSTALL_PYTHON=yes ;; esac
fi
if [ "${INSTALL_PYTHON:-no}" = "yes" ]; then
    $SUDO apt install -y python3 python3-pip python3-venv
fi

if [ "$INSTALL_OFFICE_SET" -eq 0 ] && [ -z "${INSTALL_OFFICE:-}" ]; then
    read -r -p "Install LibreOffice (full office suite, ~1-2GB)? [y/N]: " ans
    case "$ans" in [Yy]*) INSTALL_OFFICE=yes ;; *) INSTALL_OFFICE=no ;; esac
fi
if [ "${INSTALL_OFFICE:-no}" = "yes" ]; then
    $SUDO apt install -y libreoffice
fi

if [ "$INSTALL_ANTIGRAVITY_SET" -eq 0 ] && [ -z "${INSTALL_ANTIGRAVITY:-}" ]; then
    echo "NOTE: Antigravity is a heavy Electron-based GUI IDE, needs XFCE/X11"
    echo "running to be usable at all, and Google's apt repo for it has been"
    echo "reported to lag behind the latest release. Best-effort install."
    read -r -p "Install Google Antigravity (AI IDE)? [y/N]: " ans
    case "$ans" in [Yy]*) INSTALL_ANTIGRAVITY=yes ;; *) INSTALL_ANTIGRAVITY=no ;; esac
fi
if [ "${INSTALL_ANTIGRAVITY:-no}" = "yes" ]; then
    $SUDO install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://us-central1-apt.pkg.dev/doc/repo-signing-key.gpg | $SUDO gpg --dearmor -o /etc/apt/keyrings/antigravity-repo-key.gpg
    echo "deb [signed-by=/etc/apt/keyrings/antigravity-repo-key.gpg] https://us-central1-apt.pkg.dev/projects/antigravity-auto-updater-dev/ antigravity-debian main" \
        | $SUDO tee /etc/apt/sources.list.d/antigravity.list > /dev/null
    $SUDO apt update
    $SUDO apt install -y antigravity || echo "WARNING: Antigravity install failed -- check https://antigravity.google/download for the current install method, it may have changed."
fi

if [ "$INSTALL_FIREFOX_SET" -eq 0 ] && [ -z "${INSTALL_FIREFOX:-}" ]; then
    read -r -p "Install Firefox Web Browser? [y/N]: " ans
    case "$ans" in [Yy]*) INSTALL_FIREFOX=yes ;; *) INSTALL_FIREFOX=no ;; esac
fi
if [ "${INSTALL_FIREFOX:-no}" = "yes" ]; then
    echo "=================================================="
    echo " Installing Firefox Browser"
    echo "=================================================="
    $SUDO install -m 0755 -d /etc/apt/keyrings
    wget -q https://packages.mozilla.org/apt/repo-signing-key.gpg -O- | $SUDO tee /etc/apt/keyrings/packages.mozilla.org.asc > /dev/null 2>&1 || true
    echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" \
        | $SUDO tee /etc/apt/sources.list.d/mozilla.list > /dev/null
    cat << 'MOZILLA_PREF_EOF' | $SUDO tee /etc/apt/preferences.d/mozilla > /dev/null
Package: *
Pin: origin packages.mozilla.org
Pin-Priority: 1000
MOZILLA_PREF_EOF
    $SUDO apt update
    $SUDO apt install -y firefox || echo "WARNING: Firefox from official repo failed, attempting fallback..." && $SUDO apt install -y firefox-esr 2>/dev/null || $SUDO apt install -y chromium-browser 2>/dev/null || true
fi

echo "=================================================="

# ============================================================
# DEFAULT SHELL
# ============================================================

echo "Configuring default shell to zsh..."
ZSH_BIN="$(command -v zsh 2>/dev/null || which zsh 2>/dev/null || echo "/usr/bin/zsh")"

if [ -x "$ZSH_BIN" ]; then
    # Ensure zsh paths are in /etc/shells
    for zpath in "$ZSH_BIN" /bin/zsh /usr/bin/zsh; do
        if [ -x "$zpath" ] && [ -f /etc/shells ]; then
            if ! grep -qxF "$zpath" /etc/shells 2>/dev/null; then
                echo "$zpath" | $SUDO tee -a /etc/shells >/dev/null 2>&1 || true
            fi
        fi
    done

    # Set default shell in /etc/passwd via usermod/chsh with sudo
    $SUDO usermod -s "$ZSH_BIN" "$TARGET_USER" 2>/dev/null || \
    $SUDO chsh -s "$ZSH_BIN" "$TARGET_USER" 2>/dev/null || \
    chsh -s "$ZSH_BIN" 2>/dev/null || true

    # If current user is different from TARGET_USER, update too
    CURRENT_USER="$(id -un 2>/dev/null || true)"
    if [ -n "$CURRENT_USER" ] && [ "$CURRENT_USER" != "root" ] && [ "$CURRENT_USER" != "$TARGET_USER" ]; then
        $SUDO usermod -s "$ZSH_BIN" "$CURRENT_USER" 2>/dev/null || \
        $SUDO chsh -s "$ZSH_BIN" "$CURRENT_USER" 2>/dev/null || \
        chsh -s "$ZSH_BIN" 2>/dev/null || true
    fi

    # Update Droidspaces container config if present
    if [ -f /run/droidspaces/container.config ]; then
        if grep -q '^default_user_shell=' /run/droidspaces/container.config 2>/dev/null; then
            $SUDO sed -i "s|^default_user_shell=.*|default_user_shell=$ZSH_BIN|" /run/droidspaces/container.config 2>/dev/null || true
        else
            echo "default_user_shell=$ZSH_BIN" | $SUDO tee -a /run/droidspaces/container.config >/dev/null 2>&1 || true
        fi
    fi

    # Add ~/.bashrc fallback to auto-switch interactive sessions to zsh
    if [ -f "$HOME/.bashrc" ] && ! grep -q 'AUTO-SWITCH TO ZSH' "$HOME/.bashrc" 2>/dev/null; then
        cat >> "$HOME/.bashrc" << 'BASHRC_ZSH_EOF'

# ============================================================
# AUTO-SWITCH TO ZSH
# ============================================================
if [ -t 1 ] && [ -n "$PS1" ] && [ -z "$ZSH_VERSION" ] && [ -z "$BASH_EXECUTION_STRING" ] && [ -z "${NO_AUTO_ZSH:-}" ] && command -v zsh >/dev/null 2>&1; then
    export SHELL="$(command -v zsh)"
    exec zsh -l
fi
BASHRC_ZSH_EOF
    fi
fi

# ============================================================
# Git config
# ============================================================

echo "Configuring Git..."
git config --global init.defaultBranch    main
git config --global pull.rebase           false
git config --global push.autoSetupRemote  true
git config --global core.pager            delta
git config --global interactive.diffFilter "delta --color-only"
git config --global delta.navigate        true
git config --global delta.light           false
git config --global merge.conflictstyle   diff3
git config --global diff.colorMoved       default

echo "=================================================="
echo " Setup complete"
echo "=================================================="
echo "Restart shell or run: exec zsh"
echo

echo "Versions installed:"
echo -n "  zsh       : "; zsh --version 2>/dev/null | head -1 || echo "not found"
echo -n "  git       : "; git --version 2>/dev/null || echo "not found"
echo -n "  delta     : "; delta --version 2>/dev/null || echo "not found"
echo -n "  fzf       : "; fzf --version 2>/dev/null || echo "not found"
echo -n "  fd        : "; fd --version 2>/dev/null || echo "not found"
echo -n "  rg        : "; rg --version 2>/dev/null | head -1 || echo "not found"
echo -n "  bat       : "; bat --version 2>/dev/null || echo "not found"
echo -n "  eza       : "; eza --version 2>/dev/null | head -1 || echo "not found"
echo -n "  zoxide    : "; zoxide --version 2>/dev/null || echo "not found"
echo -n "  btop      : "; btop --version 2>/dev/null | head -1 || echo "not found"
echo -n "  ncdu      : "; ncdu --version 2>/dev/null || echo "not found"
echo -n "  fastfetch : "; fastfetch --version 2>/dev/null || echo "not found"
echo -n "  uv        : "; uv --version 2>/dev/null || echo "not found"
echo -n "  node      : "; node --version 2>/dev/null || echo "not found"
echo -n "  bun       : "; bun --version 2>/dev/null || echo "not found"
echo -n "  claude    : "; claude --version 2>/dev/null || echo "not found"
echo -n "  gh        : "; gh --version 2>/dev/null | head -1 || echo "not found"
echo -n "  firefox   : "; firefox --version 2>/dev/null | head -1 || echo "not found"
echo -n "  cloudflared: "; cloudflared --version 2>/dev/null | head -1 || echo "not found"
if [ -f /etc/systemd/system/oracle-tunnel.service ]; then
    echo -n "  oracle-tunnel: "; systemctl is-active oracle-tunnel.service 2>/dev/null || echo "inactive"
fi
echo -n "  docker    : "; docker --version 2>/dev/null || echo "not found"
echo -n "  compose   : "; docker compose version 2>/dev/null || echo "not found"
if command -v docker >/dev/null 2>&1; then
    echo -n "  dockerd   : "; docker info >/dev/null 2>&1 && echo "running" || echo "NOT running - try 'dockerstart' or check: sudo systemctl status docker"
fi
echo

echo "Shell features:"
echo "  - Oh My Zsh with autosuggestions, syntax highlighting, completions"
echo "  - Ctrl+R fuzzy history search (fzf)"
echo "  - Smart cd with zoxide"
echo "  - Git diff pager: delta"
echo "  - 50k line history with deduplication"
echo

echo "Helper scripts:"
echo "  eveupdate | everebuild | evedeploy | evelogs | ddeploy | dockerstart"
echo

echo "Git aliases: gs ga gaa gc gpl gps gd gb glog gst gsub"
echo "Docker aliases: dc dcu dcub dcd dcr dcl dps di dprune"
echo "=================================================="
echo
echo "Accounts:"
echo "  root : password set, console/local access only -- no SSH"
echo "  zen  : password set locally, but SSH is key-only (see key info printed above)"
echo
echo "NOTE: docker group membership needs a fresh login to take effect --"
echo "log out and back in (or 'newgrp docker') before running docker"
echo "commands as zen without sudo."

# Hand off to an interactive zen shell so this terminal actually ends
# up logged in as zen, instead of the script just finishing and
# dropping back to whichever shell (likely still root) launched it.
# Only do this when attached to a real terminal -- skip it if this
# script is being run non-interactively (e.g. from another script or
# CI), where dropping into a login shell would just hang.
if [ -t 0 ] && [ "$(id -un)" != "root" ]; then
    echo
    echo "Handing off to an interactive $TARGET_USER zsh shell now..."
    if command -v zsh >/dev/null 2>&1; then
        export SHELL="$(command -v zsh)"
        exec zsh -l
    else
        exec bash -l
    fi
fi