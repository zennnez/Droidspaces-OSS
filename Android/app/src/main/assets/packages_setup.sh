#!/bin/sh
# Droidspaces Packages & Shell Environment setup script.
# POSIX sh script executed inside the container.

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$HOME/.local/bin:$HOME/bin:$HOME/.bun/bin:$PATH"

log() {
    ts="$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '')"
    echo "[packages $ts] $1"
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
    echo "zsh=$(have zsh && echo 1 || echo 0)"
    echo "ohmyzsh=$([ -d "$HOME/.oh-my-zsh" ] || [ -d "/home/zen/.oh-my-zsh" ] || [ -d "/root/.oh-my-zsh" ] && echo 1 || echo 0)"
    echo "eza=$(have eza && echo 1 || echo 0)"
    echo "zoxide=$(have zoxide && echo 1 || echo 0)"
    echo "bat=$(have bat || have batcat && echo 1 || echo 0)"
    echo "fd=$(have fd || have fdfind && echo 1 || echo 0)"
    echo "rg=$(have rg && echo 1 || echo 0)"
    echo "fzf=$(have fzf && echo 1 || echo 0)"
    echo "btop=$(have btop && echo 1 || echo 0)"
    echo "ncdu=$(have ncdu && echo 1 || echo 0)"
    echo "fastfetch=$(have fastfetch && echo 1 || echo 0)"
    echo "delta=$(have delta && echo 1 || echo 0)"
    echo "tmux=$(have tmux && echo 1 || echo 0)"
    echo "python=$(have python3 && echo 1 || echo 0)"
    echo "uv=$(have uv && echo 1 || echo 0)"
    echo "node=$(have node && echo 1 || echo 0)"
    echo "bun=$(have bun && echo 1 || echo 0)"
    echo "gh=$(have gh && echo 1 || echo 0)"
    echo "claude=$(have claude && echo 1 || echo 0)"
    echo "omniroute=$(have omniroute && echo 1 || echo 0)"
    echo "rust=$(have rustc || have cargo && echo 1 || echo 0)"
    echo "go=$(have go && echo 1 || echo 0)"
    echo "neovim=$(have nvim && echo 1 || echo 0)"
    echo "starship=$(have starship && echo 1 || echo 0)"
    echo "ollama=$(have ollama && echo 1 || echo 0)"
}

# ---------------------------------------------------------------------------
# Package Installers
# ---------------------------------------------------------------------------
install_base_apt() {
    log "Updating apt repositories and installing core packages..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
            zsh git git-lfs curl wget unzip zip tar tmux htop btop \
            fzf ripgrep fd-find bat ncdu jq tree neovim \
            build-essential python3 python3-pip python3-venv \
            ca-certificates gnupg lsb-release || true
        if have git-lfs; then
            git lfs install --system 2>/dev/null || git lfs install 2>/dev/null || true
        fi
    fi
}

install_eza() {
    if have eza; then log "eza is already installed"; return 0; fi
    log "Installing eza (modern ls)..."
    if command -v apt-get >/dev/null 2>&1; then
        mkdir -p /etc/apt/keyrings
        wget -qO- https://raw.githubusercontent.com/eza-community/eza/main/deb.asc \
            | gpg --dearmor --yes -o /etc/apt/keyrings/gierens.gpg 2>/dev/null || true
        echo "deb [signed-by=/etc/apt/keyrings/gierens.gpg] http://deb.gierens.de stable main" \
            > /etc/apt/sources.list.d/gierens.list
        chmod 644 /etc/apt/keyrings/gierens.gpg 2>/dev/null || true
        apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq eza || true
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm eza
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache eza
    fi
}

install_zoxide() {
    if have zoxide; then log "zoxide is already installed"; return 0; fi
    log "Installing zoxide (smart cd)..."
    curl -sSfL https://raw.githubusercontent.com/ajeetdsouza/zoxide/main/install.sh | sh
}

install_fastfetch() {
    if have fastfetch; then log "fastfetch is already installed"; return 0; fi
    log "Installing fastfetch..."
    arch="$(get_arch)"
    ff_arch="$arch"
    [ "$arch" = "arm64" ] && ff_arch="aarch64"
    tmp_deb="/tmp/fastfetch.deb"
    wget -qO "$tmp_deb" "https://github.com/fastfetch-cli/fastfetch/releases/latest/download/fastfetch-linux-${ff_arch}.deb" 2>/dev/null || true
    if [ -f "$tmp_deb" ] && [ -s "$tmp_deb" ]; then
        dpkg -i "$tmp_deb" 2>/dev/null || true
        rm -f "$tmp_deb"
    fi
}

install_delta() {
    if have delta; then log "git-delta is already installed"; return 0; fi
    log "Installing git-delta..."
    arch="$(get_arch)"
    ver="$(curl -fsSL https://api.github.com/repos/dandavison/delta/releases/latest 2>/dev/null | grep '"tag_name"' | cut -d'"' -f4 || echo '')"
    if [ -n "$ver" ]; then
        deb_url="https://github.com/dandavison/delta/releases/download/${ver}/git-delta_${ver}_${arch}.deb"
        tmp_deb="/tmp/delta.deb"
        curl -fsSL "$deb_url" -o "$tmp_deb" 2>/dev/null || true
        if [ -f "$tmp_deb" ] && [ -s "$tmp_deb" ]; then
            dpkg -i "$tmp_deb" 2>/dev/null || true
            rm -f "$tmp_deb"
        fi
    fi
}

install_uv() {
    if have uv; then log "uv is already installed"; return 0; fi
    log "Installing uv (fast Python package manager)..."
    curl -LsSf https://astral.sh/uv/install.sh | sh
}

install_node() {
    if have node; then log "Node.js is already installed: $(node -v 2>&1)"; return 0; fi
    log "Installing Node.js 22 LTS..."
    if command -v apt-get >/dev/null 2>&1; then
        curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nodejs
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm nodejs npm
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache nodejs npm
    fi
}

install_bun() {
    if have bun; then log "bun is already installed: $(bun -v 2>&1)"; return 0; fi
    log "Installing bun..."
    curl -fsSL https://bun.sh/install | bash
}

install_gh() {
    if have gh; then log "GitHub CLI is already installed: $(gh --version 2>&1 | head -n1)"; return 0; fi
    log "Installing GitHub CLI (gh)..."
    if command -v apt-get >/dev/null 2>&1; then
        mkdir -p /etc/apt/keyrings
        curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
            | dd of=/etc/apt/keyrings/githubcli-archive-keyring.gpg 2>/dev/null || true
        chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg 2>/dev/null || true
        arch="$(get_arch)"
        echo "deb [arch=${arch} signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
            > /etc/apt/sources.list.d/github-cli.list
        apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gh || true
    fi
}

install_claude() {
    if have claude; then log "Claude Code CLI is already installed"; return 0; fi
    log "Installing Claude Code CLI (@anthropic-ai/claude-code)..."
    install_node
    npm install -g --no-fund --no-audit @anthropic-ai/claude-code 2>/dev/null || true
}

install_omniroute() {
    if have omniroute; then log "OmniRoute is already installed"; return 0; fi
    log "Installing OmniRoute (Local AI Gateway & Routing Proxy)..."
    install_node
    npm install -g --no-fund --no-audit omniroute 2>/dev/null || true
}

install_rust() {
    if have rustc; then log "Rust is already installed: $(rustc --version 2>&1)"; return 0; fi
    log "Installing Rust and Cargo..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
}

install_go() {
    if have go; then log "Go is already installed: $(go version 2>&1)"; return 0; fi
    log "Installing Go programming language..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq golang-go || true
    fi
}

install_neovim() {
    if have nvim; then log "Neovim is already installed"; return 0; fi
    log "Installing Neovim..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq neovim || true
    fi
}

install_starship() {
    if have starship; then log "Starship prompt is already installed"; return 0; fi
    log "Installing Starship cross-shell prompt..."
    curl -sS https://starship.rs/install.sh | sh -s -- -y
}

install_ollama() {
    if have ollama; then log "Ollama is already installed"; return 0; fi
    log "Installing Ollama AI runner..."
    curl -fsSL https://ollama.com/install.sh | sh
}

# ---------------------------------------------------------------------------
# Oh My Zsh & Custom .zshrc
# ---------------------------------------------------------------------------
setup_zsh() {
    log "Configuring Oh My Zsh and enhanced plugins..."
    target_user="${1:-}"
    if [ -z "$target_user" ]; then
        target_user="$(getent passwd 1000 2>/dev/null | cut -d: -f1 || echo '')"
        [ -z "$target_user" ] && target_user="$(id -un 2>/dev/null || echo root)"
    fi
    target_home="$(getent passwd "$target_user" 2>/dev/null | cut -d: -f6 || echo "/home/$target_user")"
    [ ! -d "$target_home" ] && target_home="/root"

    # Install Oh My Zsh if missing
    if [ ! -d "$target_home/.oh-my-zsh" ]; then
        RUNZSH=no CHSH=no sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)" "" --unattended 2>/dev/null || true
    fi

    # Plugins
    zsh_custom="${target_home}/.oh-my-zsh/custom"
    mkdir -p "$zsh_custom/plugins"
    [ ! -d "$zsh_custom/plugins/zsh-autosuggestions" ] && git clone --depth=1 https://github.com/zsh-users/zsh-autosuggestions "$zsh_custom/plugins/zsh-autosuggestions" 2>/dev/null || true
    [ ! -d "$zsh_custom/plugins/zsh-syntax-highlighting" ] && git clone --depth=1 https://github.com/zsh-users/zsh-syntax-highlighting "$zsh_custom/plugins/zsh-syntax-highlighting" 2>/dev/null || true
    [ ! -d "$zsh_custom/plugins/zsh-completions" ] && git clone --depth=1 https://github.com/zsh-users/zsh-completions "$zsh_custom/plugins/zsh-completions" 2>/dev/null || true

    # Link bat and fd
    mkdir -p "$target_home/.local/bin"
    [ ! -f "$target_home/.local/bin/fd" ] && [ -x /usr/bin/fdfind ] && ln -sf /usr/bin/fdfind "$target_home/.local/bin/fd"
    [ ! -f "$target_home/.local/bin/bat" ] && [ -x /usr/bin/batcat ] && ln -sf /usr/bin/batcat "$target_home/.local/bin/bat"

    # Write clean, standard .zshrc
    cat > "$target_home/.zshrc" << 'ZSHRCEOF'
if [ "$PWD" = "/" ]; then
    cd "$HOME" 2>/dev/null || true
fi

export ZSH="$HOME/.oh-my-zsh"
ZSH_THEME="robbyrussell"

plugins=(
    git docker python node npm fzf
    zsh-autosuggestions
    zsh-syntax-highlighting
    zsh-completions
)

[ -f "$ZSH/oh-my-zsh.sh" ] && source "$ZSH/oh-my-zsh.sh"

export PATH="$HOME/.local/bin:$HOME/bin:$HOME/.bun/bin:$HOME/.cargo/bin:$PATH"

# Core Modern CLI Aliases
if command -v eza >/dev/null 2>&1; then
    alias ls='eza --icons --group-directories-first'
    alias ll='eza -la --icons --git --group-directories-first'
    alias la='eza -la --icons --git'
    alias lt='eza --tree --icons --level=2'
    alias tree='eza --tree --icons'
else
    alias ll='ls -la'
    alias la='ls -A'
fi

if command -v bat >/dev/null 2>&1; then
    alias cat='bat --paging=never'
    alias less='bat --paging=always'
elif command -v batcat >/dev/null 2>&1; then
    alias cat='batcat --paging=never'
    alias less='batcat --paging=always'
fi

command -v fdfind >/dev/null 2>&1 && alias fd='fdfind'
command -v rg >/dev/null 2>&1 && alias grep='rg'
command -v btop >/dev/null 2>&1 && alias top='btop'
command -v ncdu >/dev/null 2>&1 && alias du='ncdu --color dark'

# Git Aliases
alias gs='git status -sb'
alias ga='git add'
alias gaa='git add -A'
alias gc='git commit -m'
alias gca='git commit --amend --no-edit'
alias gpl='git pull'
alias gps='git push'
alias gd='git diff'
alias gds='git diff --staged'
alias gb='git branch -vv'
alias gco='git checkout'
alias gsw='git switch'
alias glog='git log --oneline --graph --decorate --all'

# Docker Aliases
alias dc='docker compose'
alias dcu='docker compose up -d'
alias dcd='docker compose down'
alias dcl='docker compose logs -f'
alias dps='docker ps'

# OmniRoute AI Gateway config
export ANTHROPIC_BASE_URL="${ANTHROPIC_BASE_URL:-http://127.0.0.1:20128}"
export ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-dummy}"

# Load global custom aliases
[ -f /etc/profile.d/droidspaces_aliases.sh ] && source /etc/profile.d/droidspaces_aliases.sh
[ -f "$HOME/.bash_aliases" ] && source "$HOME/.bash_aliases"

# Smart CD
if command -v zoxide >/dev/null 2>&1; then
    eval "$(zoxide init zsh --cmd cd)"
fi

# Starship Prompt (if installed)
if command -v starship >/dev/null 2>&1; then
    eval "$(starship init zsh)"
fi

if command -v fastfetch >/dev/null 2>&1; then
    [[ $- == *i* ]] && fastfetch
fi
ZSHRCEOF

    chown -R "$target_user:$target_user" "$target_home/.zshrc" "$target_home/.oh-my-zsh" "$target_home/.local" 2>/dev/null || true
    log "Oh My Zsh and .zshrc configured successfully for $target_user"
}

# ---------------------------------------------------------------------------
install_all() {
    install_base_apt
    install_eza
    install_zoxide
    install_fastfetch
    install_delta
    install_uv
    install_node
    install_bun
    install_gh
    install_claude
    install_omniroute
    install_rust
    install_go
    install_neovim
    install_starship
    setup_zsh "${1:-}"
    log "All packages and shell environment setup completed successfully ✓"
}

# ---------------------------------------------------------------------------
cmd="${1:-status}"
shift 2>/dev/null || true

case "$cmd" in
    status) check_status ;;
    install-all) install_all "${1:-}" ;;
    install-cli)
        install_base_apt
        install_eza
        install_zoxide
        install_fastfetch
        install_delta
        ;;
    install-zsh) setup_zsh "${1:-}" ;;
    install-runtimes)
        install_uv
        install_node
        install_bun
        install_gh
        install_rust
        install_go
        ;;
    install-eza) install_eza ;;
    install-zoxide) install_zoxide ;;
    install-fastfetch) install_fastfetch ;;
    install-delta) install_delta ;;
    install-uv) install_uv ;;
    install-node) install_node ;;
    install-bun) install_bun ;;
    install-gh) install_gh ;;
    install-claude) install_claude ;;
    install-omniroute) install_omniroute ;;
    install-rust) install_rust ;;
    install-go) install_go ;;
    install-neovim) install_neovim ;;
    install-starship) install_starship ;;
    install-ollama) install_ollama ;;
    *)
        echo "Usage: $0 {status|install-all|install-cli|install-zsh|install-runtimes|install-<pkg>}" >&2
        exit 2
        ;;
esac
