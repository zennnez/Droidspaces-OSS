#!/bin/sh
# Droidspaces Alias Management Helper
# Manages persistent shell aliases across Bash and Zsh inside Droidspaces containers.

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

GLOBAL_ALIAS_FILE="/etc/profile.d/droidspaces_aliases.sh"

get_target_home() {
    target_user="$(awk -F: '$3 >= 1000 && $3 < 65534 {print $1; exit}' /etc/passwd 2>/dev/null || echo '')"
    [ -z "$target_user" ] && target_user="$(getent passwd 1000 2>/dev/null | cut -d: -f1 || echo '')"
    [ -z "$target_user" ] && target_user="$(id -un 2>/dev/null || echo root)"
    target_home="$(getent passwd "$target_user" 2>/dev/null | cut -d: -f6 || echo "/home/$target_user")"
    [ ! -d "$target_home" ] && target_home="/root"
    echo "$target_home"
}

ensure_files() {
    mkdir -p /etc/profile.d
    if [ ! -f "$GLOBAL_ALIAS_FILE" ]; then
        cat > "$GLOBAL_ALIAS_FILE" << 'EOF'
# Droidspaces custom aliases
# Automatically loaded by all interactive login and non-login shells
EOF
        chmod 644 "$GLOBAL_ALIAS_FILE"
    fi

    # Ensure sourced in ~/.zshrc and ~/.bashrc
    home_dir="$(get_target_home)"
    for rc in "$home_dir/.zshrc" "$home_dir/.bashrc" "/root/.zshrc" "/root/.bashrc"; do
        if [ -f "$rc" ]; then
            if ! grep -q 'droidspaces_aliases.sh' "$rc" 2>/dev/null; then
                echo '[ -f /etc/profile.d/droidspaces_aliases.sh ] && source /etc/profile.d/droidspaces_aliases.sh' >> "$rc"
            fi
        fi
    done
}

list_aliases() {
    ensure_files
    home_dir="$(get_target_home)"

    # Print JSON array of aliases
    printf '[\n'
    first=1

    tmp_out="/tmp/ds_aliases.tmp"
    rm -f "$tmp_out"
    touch "$tmp_out"

    # Helper to parse alias lines from a file
    parse_file_aliases() {
        file="$1"
        source_label="$2"
        [ ! -f "$file" ] && return
        grep -E '^[[:space:]]*alias[[:space:]]+[a-zA-Z0-9_\-\.]+=.*' "$file" 2>/dev/null | while read -r line; do
            clean="$(echo "$line" | sed 's/^[[:space:]]*alias[[:space:]]*//')"
            aname="$(echo "$clean" | cut -d= -f1 | tr -d ' ')"
            acmd="$(echo "$clean" | cut -d= -f2- | sed "s/^['\"]//" | sed "s/['\"]$//")"
            [ -n "$aname" ] && printf '%s\t%s\t%s\n' "$aname" "$acmd" "$source_label" >> "$tmp_out"
        done
    }

    # 1. Extract from global files
    parse_file_aliases "$GLOBAL_ALIAS_FILE" "Custom"
    for pf in /etc/profile.d/*.sh; do
        [ "$pf" != "$GLOBAL_ALIAS_FILE" ] && parse_file_aliases "$pf" "Global"
    done
    parse_file_aliases "/etc/bash.bashrc" "System"
    parse_file_aliases "/etc/zsh/zshrc" "System"
    parse_file_aliases "/etc/profile" "System"

    # 2. Extract from target user homes
    parse_file_aliases "$home_dir/.bash_aliases" "User"
    parse_file_aliases "$home_dir/.bashrc" "User"
    parse_file_aliases "$home_dir/.zshrc" "User"
    parse_file_aliases "$home_dir/.profile" "User"

    # 3. Extract from root
    parse_file_aliases "/root/.bash_aliases" "Root"
    parse_file_aliases "/root/.bashrc" "Root"
    parse_file_aliases "/root/.zshrc" "Root"

    # 4. Extract from all other users in /home
    for udir in /home/*; do
        if [ -d "$udir" ] && [ "$udir" != "$home_dir" ]; then
            parse_file_aliases "$udir/.bash_aliases" "User"
            parse_file_aliases "$udir/.bashrc" "User"
            parse_file_aliases "$udir/.zshrc" "User"
        fi
    done

    # 5. Extract active runtime shell aliases
    if command -v bash >/dev/null 2>&1; then
        bash -i -c 'alias' 2>/dev/null | while read -r line; do
            clean="$(echo "$line" | sed 's/^[[:space:]]*alias[[:space:]]*//')"
            aname="$(echo "$clean" | cut -d= -f1 | tr -d ' ')"
            acmd="$(echo "$clean" | cut -d= -f2- | sed "s/^['\"]//" | sed "s/['\"]$//")"
            [ -n "$aname" ] && printf '%s\t%s\t%s\n' "$aname" "$acmd" "Shell" >> "$tmp_out"
        done
    fi

    # 6. Extract custom shell functions
    parse_file_functions() {
        file="$1"
        [ ! -f "$file" ] && return
        grep -E '^[[:space:]]*(function[[:space:]]+)?[a-zA-Z0-9_-]+[[:space:]]*\(\)[[:space:]]*\{' "$file" 2>/dev/null | while read -r line; do
            fname="$(echo "$line" | sed 's/^[[:space:]]*function[[:space:]]*//' | cut -d'(' -f1 | tr -d ' ')"
            if [ -n "$fname" ] && [ "$fname" != "ensure_files" ] && [ "$fname" != "list_aliases" ]; then
                printf '%s\t%s\t%s\n' "$fname" "Shell Function () { ... }" "Function" >> "$tmp_out"
            fi
        done
    }
    parse_file_functions "$GLOBAL_ALIAS_FILE"
    parse_file_functions "$home_dir/.bashrc"
    parse_file_functions "$home_dir/.zshrc"

    # Remove duplicates and format as JSON
    sort -u -k1,1 "$tmp_out" 2>/dev/null | while IFS="$(printf '\t')" read -r aname acmd asrc; do
        [ -z "$aname" ] && continue
        escaped_cmd=$(printf '%s' "$acmd" | sed 's/\\/\\\\/g' | sed 's/"/\\"/g')
        escaped_name=$(printf '%s' "$aname" | sed 's/\\/\\\\/g' | sed 's/"/\\"/g')
        if [ "$first" -eq 1 ]; then
            first=0
        else
            printf ',\n'
        fi
        printf '  {"name": "%s", "command": "%s", "source": "%s"}' "$escaped_name" "$escaped_cmd" "$asrc"
    done

    printf '\n]\n'
    rm -f "$tmp_out"
}

add_alias() {
    name="$1"
    cmd="$2"

    [ -z "$name" ] && { echo "Error: Alias name cannot be empty" >&2; exit 1; }
    [ -z "$cmd" ] && { echo "Error: Alias command cannot be empty" >&2; exit 1; }

    # Validate name format
    case "$name" in
        *[!a-zA-Z0-9_.-]*)
            echo "Error: Invalid alias name. Only alphanumeric, _, - and . allowed" >&2
            exit 1
            ;;
    esac

    ensure_files

    # Remove existing alias with same name from global file
    if [ -f "$GLOBAL_ALIAS_FILE" ]; then
        grep -v -E "^[[:space:]]*alias[[:space:]]+${name}=" "$GLOBAL_ALIAS_FILE" > "${GLOBAL_ALIAS_FILE}.tmp" 2>/dev/null || true
        mv "${GLOBAL_ALIAS_FILE}.tmp" "$GLOBAL_ALIAS_FILE"
    fi

    # Append new alias with single quotes, escaping internal single quotes
    escaped_cmd=$(printf '%s' "$cmd" | sed "s/'/'\\\\''/g")
    printf "alias %s='%s'\n" "$name" "$escaped_cmd" >> "$GLOBAL_ALIAS_FILE"
    chmod 644 "$GLOBAL_ALIAS_FILE"

    # Also update user ~/.bash_aliases if exists
    home_dir="$(get_target_home)"
    if [ -f "$home_dir/.bash_aliases" ]; then
        grep -v -E "^[[:space:]]*alias[[:space:]]+${name}=" "$home_dir/.bash_aliases" > "${home_dir}/.bash_aliases.tmp" 2>/dev/null || true
        printf "alias %s='%s'\n" "$name" "$escaped_cmd" >> "${home_dir}/.bash_aliases.tmp"
        mv "${home_dir}/.bash_aliases.tmp" "$home_dir/.bash_aliases"
    fi

    # Test source
    sh -c ". $GLOBAL_ALIAS_FILE" 2>&1 || {
        echo "Warning: Alias created but failed syntax verification" >&2
    }

    echo "OK"
}

delete_alias() {
    name="$1"
    [ -z "$name" ] && { echo "Error: Alias name cannot be empty" >&2; exit 1; }

    ensure_files

    if [ -f "$GLOBAL_ALIAS_FILE" ]; then
        grep -v -E "^[[:space:]]*alias[[:space:]]+${name}=" "$GLOBAL_ALIAS_FILE" > "${GLOBAL_ALIAS_FILE}.tmp" 2>/dev/null || true
        mv "${GLOBAL_ALIAS_FILE}.tmp" "$GLOBAL_ALIAS_FILE"
        chmod 644 "$GLOBAL_ALIAS_FILE"
    fi

    home_dir="$(get_target_home)"
    if [ -f "$home_dir/.bash_aliases" ]; then
        grep -v -E "^[[:space:]]*alias[[:space:]]+${name}=" "$home_dir/.bash_aliases" > "${home_dir}/.bash_aliases.tmp" 2>/dev/null || true
        mv "${home_dir}/.bash_aliases.tmp" "$home_dir/.bash_aliases"
    fi

    echo "OK"
}

source_aliases() {
    ensure_files
    if [ -f "$GLOBAL_ALIAS_FILE" ]; then
        . "$GLOBAL_ALIAS_FILE"
        echo "Aliases sourced successfully"
    fi
}

cmd="${1:-list}"
shift 2>/dev/null || true

case "$cmd" in
    list) list_aliases ;;
    add) add_alias "${1:-}" "${2:-}" ;;
    delete) delete_alias "${1:-}" ;;
    source) source_aliases ;;
    *)
        echo "Usage: $0 {list|add <name> <command>|delete <name>|source}" >&2
        exit 2
        ;;
esac
