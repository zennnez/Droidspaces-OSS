#!/usr/bin/env bash
# ============================================================
# Oracle VPS Dev Environment Setup
# Zsh + Modern CLI + Docker + Git + Eden Helpers
# Ubuntu/Debian
# ============================================================

set -euo pipefail

echo "=================================================="
echo " Installing packages"
echo "=================================================="

sudo apt update

sudo apt install -y \
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

    sudo mkdir -p /etc/apt/keyrings

    wget -qO- https://raw.githubusercontent.com/eza-community/eza/main/deb.asc \
        | sudo gpg --dearmor -o /etc/apt/keyrings/gierens.gpg

    echo "deb [signed-by=/etc/apt/keyrings/gierens.gpg] http://deb.gierens.de stable main" \
        | sudo tee /etc/apt/sources.list.d/gierens.list

    sudo chmod 644 /etc/apt/keyrings/gierens.gpg
    sudo apt update
    sudo apt install -y eza
fi

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

# ============================================================
# ZSHRC
# ============================================================

cat > "$HOME/.zshrc" << 'EOF'
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
# Eden shortcuts
# ============================================================

alias ddir='cd ~/docker'
alias eve='cd ~/docker/services/eve'

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
    ARCH="$(uname -m)"
    case "$ARCH" in
        x86_64|amd64) FF_ARCH="amd64" ;;
        aarch64|arm64) FF_ARCH="aarch64" ;;
        *) FF_ARCH="" ;;
    esac

    if [ -n "$FF_ARCH" ]; then
        wget -qO /tmp/fastfetch.deb \
            "https://github.com/fastfetch-cli/fastfetch/releases/latest/download/fastfetch-linux-${FF_ARCH}.deb"
        sudo dpkg -i /tmp/fastfetch.deb
        rm /tmp/fastfetch.deb
    else
        echo "Warning: Unsupported architecture ($ARCH) for fastfetch deb package."
    fi
fi

# ============================================================
# delta
# ============================================================

if ! command -v delta >/dev/null 2>&1; then
    echo "Installing delta..."
    ARCH="$(uname -m)"
    case "$ARCH" in
        x86_64|amd64) DELTA_ARCH="amd64" ;;
        aarch64|arm64) DELTA_ARCH="arm64" ;;
        *) DELTA_ARCH="" ;;
    esac

    if [ -n "$DELTA_ARCH" ]; then
        DELTA_VERSION=$(curl -fsSL https://api.github.com/repos/dandavison/delta/releases/latest \
            | grep '"tag_name"' | cut -d'"' -f4)
        curl -fsSL \
            "https://github.com/dandavison/delta/releases/download/${DELTA_VERSION}/git-delta_${DELTA_VERSION}_${DELTA_ARCH}.deb" \
            -o "$HOME/delta.deb"
        sudo dpkg -i "$HOME/delta.deb"
        rm "$HOME/delta.deb"
        delta --version 2>/dev/null || true
    else
        echo "Warning: Unsupported architecture ($ARCH) for delta deb package."
    fi
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
    curl -fsSL https://deb.nodesource.com/setup_22.x | sudo bash -
    sudo apt install -y nodejs
fi

# ============================================================
# bun
# ============================================================

if ! command -v bun >/dev/null 2>&1; then
    curl -fsSL https://bun.sh/install | bash
fi

# ============================================================
# GitHub CLI (gh)
# ============================================================

if ! command -v gh >/dev/null 2>&1; then
    echo "=================================================="
    echo " Installing GitHub CLI (gh)"
    echo "=================================================="

    sudo mkdir -p -m 755 /etc/apt/keyrings
    wget -qO- https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        | sudo tee /etc/apt/keyrings/githubcli-archive-keyring.gpg > /dev/null
    sudo chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null
    sudo apt update
    sudo apt install -y gh
fi

# ============================================================
# Caddy relay (public-facing side of the Oracle relay tunnel)
# ============================================================
# Counterpart to the "Oracle relay tunnel" section in
# setup_droidspaces.sh. That script opens an outbound SSH reverse
# tunnel FROM the other device TO here, binding 127.0.0.1:<remote
# port> on this box. Caddy here just needs to: get a real Let's
# Encrypt cert (works normally since THIS box has a public IP), then
# reverse_proxy to that local tunnel port. The retry options absorb
# brief reconnects on the tunnel side instead of surfacing a 502.

INSTALL_ORACLE_RELAY="no"
if [ -t 0 ]; then
    read -r -p "Set up Caddy here as a public relay for an Oracle-side SSH tunnel? [y/N]: " ans
    case "$ans" in [Yy]*) INSTALL_ORACLE_RELAY=yes ;; *) INSTALL_ORACLE_RELAY=no ;; esac
fi

if [ "$INSTALL_ORACLE_RELAY" = "yes" ]; then
    echo "=================================================="
    echo " Installing Caddy"
    echo "=================================================="

    if ! command -v caddy >/dev/null 2>&1; then
        sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
        curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
            | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
        curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
            | sudo tee /etc/apt/sources.list.d/caddy-stable.list
        sudo apt update
        sudo apt install -y caddy
    else
        echo "Caddy already installed -- skipping install."
    fi

    read -r -p "Remote tunnel port to proxy to, must match --oracle-remote-port on the other side [8080]: " RELAY_PORT
    RELAY_PORT="${RELAY_PORT:-8080}"

    RELAY_DOMAINS=()
    while true; do
        read -r -p "Domain to relay (blank to finish): " dom
        [ -z "$dom" ] && break
        RELAY_DOMAINS+=("$dom")
    done

    if [ "${#RELAY_DOMAINS[@]}" -eq 0 ]; then
        echo "No domains entered -- skipping Caddyfile write. Re-run this section manually later if needed."
    else
        {
            for dom in "${RELAY_DOMAINS[@]}"; do
                echo "${dom} {"
                echo "    reverse_proxy 127.0.0.1:${RELAY_PORT} {"
                echo "        lb_try_duration 10s"
                echo "        lb_try_interval 250ms"
                echo "    }"
                echo "}"
                echo
            done
        } | sudo tee /etc/caddy/Caddyfile > /dev/null

        sudo systemctl restart caddy
        echo -n "Caddy status: "; systemctl is-active caddy || echo "not active -- check: sudo journalctl -u caddy -n 50"
    fi

    echo "=================================================="
    echo " Opening 80/443 (OS firewall only)"
    echo "=================================================="
    if command -v ufw >/dev/null 2>&1; then
        sudo ufw allow 80/tcp
        sudo ufw allow 443/tcp
    else
        echo "ufw not installed -- skipping. Make sure 80/tcp and 443/tcp are open."
    fi
    echo "NOTE: on Oracle Cloud specifically, the OS firewall alone isn't enough --"
    echo "the VCN Security List / NSG (Console -> instance -> Subnet -> Security List)"
    echo "also needs ingress rules for TCP 80 and TCP 443 from 0.0.0.0/0, or Caddy"
    echo "will never be reachable from the internet even though it's running fine."

    echo
    echo "-----------------------------------------------------------"
    echo " ACTION NEEDED on the other device's side:"
    echo "   Point its ORACLE_HOST / --oracle-host at this box's public IP,"
    echo "   and authorize its tunnel key here with:"
    echo "     echo '<contents of its ~/.ssh/oracle_tunnel.pub>' >> ~/.ssh/authorized_keys"
    echo " Then set DNS (A record, or CNAME if that's all your provider allows)"
    echo " for each relayed domain to this box's public IP."
    echo "-----------------------------------------------------------"
    echo
fi

# ============================================================
# DEFAULT SHELL
# ============================================================

chsh -s "$(which zsh)"

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
echo -n "  gh        : "; gh --version 2>/dev/null | head -1 || echo "not found"
echo -n "  docker    : "; docker --version 2>/dev/null || echo "not found"
echo

echo "Shell features:"
echo "  - Oh My Zsh with autosuggestions, syntax highlighting, completions"
echo "  - Ctrl+R fuzzy history search (fzf)"
echo "  - Smart cd with zoxide"
echo "  - Git diff pager: delta"
echo "  - 50k line history with deduplication"
echo

echo "Helper scripts:"
echo "  eveupdate | everebuild | evedeploy | evelogs | ddeploy"
echo

echo "Git aliases: gs ga gaa gc gpl gps gd gb glog gst gsub"
echo "Docker aliases: dc dcu dcub dcd dcr dcl dps di dprune"
echo "=================================================="
