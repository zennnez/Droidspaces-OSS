#!/bin/bash
# build.sh - Build the droidspaces binary on Ubuntu / WSL
# Copyright (C) 2026 Droidspaces Contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Usage:
#   ./build.sh                 Build for aarch64 (default - Android phones)
#   ./build.sh aarch64         Build for 64-bit ARM (same as default)
#   ./build.sh x86_64          Build for x86_64
#   ./build.sh armhf           Build for 32-bit ARM
#   ./build.sh x86             Build for 32-bit x86
#   ./build.sh riscv64         Build for RISC-V 64-bit
#   ./build.sh all             Build for all supported architectures
#
# Options (env vars):
#   SKIP_DEPS=1                Skip apt-get dependency install
#   SKIP_TOOLCHAIN=1           Skip musl toolchain install (already installed)
#   SYNC_ANDROID=1             Copy output binary into Android/app/src/main/assets/binaries/
#   V=1                        Verbose make output
#   NO_STRIP=1                 Keep debug symbols in the output binary

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ── CRLF fix (repo lives on Windows NTFS, scripts may have \r\n endings) ─────
# Convert in-place so bash doesn't choke on 'set: -\r: invalid option' etc.
_fix_crlf() {
    local f="$1"
    if [ -f "$f" ] && grep -qP '\r' "$f" 2>/dev/null; then
        sed -i 's/\r//' "$f"
    fi
}
_fix_crlf "$SCRIPT_DIR/scripts/install-musl.sh"
_fix_crlf "$SCRIPT_DIR/build.sh"

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { printf "${CYAN}[*]${NC} %s\n" "$*"; }
ok()    { printf "${GREEN}[+]${NC} %s\n" "$*"; }
warn()  { printf "${YELLOW}[!]${NC} %s\n" "$*"; }
die()   { printf "${RED}[-]${NC} %s\n" "$*" >&2; exit 1; }

# ── Parse arguments ───────────────────────────────────────────────────────────
TARGET_ARCH="${1:-aarch64}"

VALID_ARCHS="aarch64 x86_64 armhf x86 riscv64 all"
if ! echo "$VALID_ARCHS" | grep -qw "$TARGET_ARCH"; then
    die "Unknown architecture '$TARGET_ARCH'. Valid: $VALID_ARCHS"
fi

# ── Environment flags ─────────────────────────────────────────────────────────
SKIP_DEPS="${SKIP_DEPS:-0}"
SKIP_TOOLCHAIN="${SKIP_TOOLCHAIN:-0}"
SYNC_ANDROID="${SYNC_ANDROID:-0}"
MAKE_VERBOSE="${V:-0}"
MAKE_NO_STRIP="${NO_STRIP:-}"

# ── System dependency check / install ────────────────────────────────────────
install_system_deps() {
    info "Checking system build dependencies..."

    local needed=()
    for pkg in gcc g++ make git curl wget patch bzip2 xz-utils; do
        # Map package name to command name for checking
        local cmd="$pkg"
        case "$pkg" in
            gcc)       cmd="gcc" ;;
            g++)       cmd="g++" ;;
            xz-utils)  cmd="xz" ;;
        esac
        if ! command -v "$cmd" > /dev/null 2>&1; then
            needed+=("$pkg")
        fi
    done

    if [ ${#needed[@]} -eq 0 ]; then
        ok "All system dependencies already installed."
        return
    fi

    info "Installing missing packages: ${needed[*]}"
    if command -v apt-get > /dev/null 2>&1; then
        sudo apt-get update -qq
        sudo apt-get install -y -qq "${needed[@]}"
    else
        die "apt-get not found. Please install: ${needed[*]}"
    fi
    ok "System dependencies installed."
}

# ── Musl toolchain installer ──────────────────────────────────────────────────
# Map arch -> musl triplet
_musl_triplet() {
    case "$1" in
        x86)      echo "i686-linux-musl" ;;
        x86_64)   echo "x86_64-linux-musl" ;;
        aarch64)  echo "aarch64-linux-musl" ;;
        armhf)    echo "arm-linux-musleabihf" ;;
        riscv64)  echo "riscv64-linux-musl" ;;
        *)        die "_musl_triplet: unknown arch '$1'" ;;
    esac
}

# Try downloading and extracting a pre-built toolchain from musl.cc.
# Returns 0 on success, 1 on any failure (HTTP error, bad archive, wrong structure).
_install_prebuilt_toolchain() {
    local triplet="$1"
    local toolchain_parent="$HOME/toolchains"
    local tarball="${triplet}-cross.tgz"
    local tmp_tar="/tmp/${tarball}"

    # musl.cc primary URL; also try the cdn subdomain as fallback
    local urls=(
        "https://musl.cc/${tarball}"
        "https://more.musl.cc/12.2.0/x86_64-linux-musl/${tarball}"
    )

    local local_candidate
    for local_candidate in "$toolchain_parent/$tarball" "$tmp_tar" "$SCRIPT_DIR/$tarball"; do
        if [ -f "$local_candidate" ] && gzip -t "$local_candidate" 2>/dev/null; then
            info "Found valid pre-downloaded toolchain archive at $local_candidate"
            tmp_tar="$local_candidate"
            downloaded=1
            break
        fi
    done

    if [ "$downloaded" -eq 0 ]; then
        local url
        for url in "${urls[@]}"; do
            info "Downloading pre-built musl.cc toolchain: $url"
            # -C - : resume transfer if partial file exists
            # --fail: treat HTTP 4xx/5xx as errors
            # --location: follow redirects
            if curl -C - --fail --location --progress-bar \
                    --max-time 600 --retry 5 --retry-delay 3 \
                    --output "$tmp_tar" "$url" 2>&1; then
                if gzip -t "$tmp_tar" 2>/dev/null; then
                    downloaded=1
                    break
                fi
            fi
            warn "Download from $url was incomplete or invalid, trying next URL/attempt..."
        done
    fi

    if [ "$downloaded" -eq 0 ]; then
        warn "All musl.cc URLs failed. Will try building from source."
        return 1
    fi

    # Validate the archive before extracting - catch HTML error pages etc.
    if ! gzip -t "$tmp_tar" 2>/dev/null; then
        warn "Downloaded file is not a valid gzip archive (possibly an HTML error page)."
        warn "File type: $(file -b "$tmp_tar" 2>/dev/null || echo 'unknown')"
        rm -f "$tmp_tar"
        return 1
    fi

    info "Extracting toolchain to $toolchain_parent/ ..."
    mkdir -p "$toolchain_parent"

    # Clean up any stale/partial directory from a previous attempt
    rm -rf "${toolchain_parent:?}/${triplet}-cross"

    if ! tar -xzf "$tmp_tar" -C "$toolchain_parent"; then
        warn "tar extraction failed."
        rm -f "$tmp_tar"
        rm -rf "${toolchain_parent:?}/${triplet}-cross"
        return 1
    fi
    rm -f "$tmp_tar"

    if [ ! -f "$toolchain_parent/${triplet}-cross/bin/${triplet}-gcc" ]; then
        warn "Extracted archive but $triplet-gcc not found in expected location."
        warn "Contents: $(ls "$toolchain_parent/${triplet}-cross/" 2>/dev/null)"
        return 1
    fi

    ok "Pre-built toolchain installed at $toolchain_parent/${triplet}-cross"
    return 0
}

# Fall back to building via musl-cross-make (slow, ~20 min, needs internet for sources).
# musl.libc.org is frequently unreachable from WSL; we patch the make invocation to
# use a GitHub-hosted musl mirror via MUSL_REPO instead of the official site.
_install_source_toolchain() {
    local arch="$1"
    local triplet="$2"

    info "Building musl toolchain from source for $arch (${triplet})..."
    info "This can take 15-30 minutes the first time."
    warn "Note: musl.libc.org may be unreachable from WSL."
    warn "If it fails, try: SKIP_TOOLCHAIN=1 and install the toolchain manually."

    # musl-cross-make respects MUSL_REPO for cloning musl from git instead of downloading
    # a tarball from musl.libc.org - avoids the connectivity issue entirely.
    MUSL_REPO="https://git.musl-libc.org/cgit/musl" \
    bash "$SCRIPT_DIR/scripts/install-musl.sh" "$arch"
}

install_toolchain() {
    local arch="$1"
    local triplet
    triplet="$(_musl_triplet "$arch")"

    info "Checking musl toolchain for $arch (${triplet})..."

    local toolchain_dir="$HOME/toolchains/${triplet}-cross"

    # A stale partial directory (no gcc binary) should not count as "installed"
    if [ -f "$toolchain_dir/bin/${triplet}-gcc" ]; then
        ok "Toolchain already installed at $toolchain_dir"
        return
    elif [ -d "$toolchain_dir" ]; then
        warn "Stale/partial toolchain directory found at $toolchain_dir - cleaning up."
        rm -rf "$toolchain_dir"
    fi

    # Prefer fast pre-built from musl.cc; fall back to source build.
    if ! _install_prebuilt_toolchain "$triplet"; then
        _install_source_toolchain "$arch" "$triplet"
    fi

    # Final verification

    if [ ! -f "$toolchain_dir/bin/${triplet}-gcc" ]; then
        die "Toolchain installation failed - $toolchain_dir/bin/${triplet}-gcc not found."
    fi
    ok "Toolchain for $arch is ready."
}

# ── Build one architecture ────────────────────────────────────────────────────
build_arch() {
    local arch="$1"
    info "Building droidspaces for $arch..."

    local make_flags=("V=$MAKE_VERBOSE")
    [ -n "$MAKE_NO_STRIP" ] && make_flags+=("NO_STRIP=$MAKE_NO_STRIP")

    make --no-print-directory "$arch" "${make_flags[@]}"
    ok "Build complete for $arch: output/droidspaces"
}

# ── Sync to Android assets ────────────────────────────────────────────────────
sync_to_android() {
    local arch="$1"
    local src="output/droidspaces"

    local android_assets="$SCRIPT_DIR/Android/app/src/main/assets/binaries"
    if [ ! -d "$SCRIPT_DIR/Android" ]; then
        warn "Android project directory not found, skipping sync."
        return
    fi

    mkdir -p "$android_assets"

    local dest="$android_assets/droidspaces-${arch}"
    cp -f "$src" "$dest"
    ok "Synced binary → $dest"
}

# ══════════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════════

echo ""
info "Droidspaces build script - target: $TARGET_ARCH"
echo ""

# 1. System dependencies
if [ "$SKIP_DEPS" = "0" ]; then
    install_system_deps
fi

# 2. Toolchain(s)
if [ "$SKIP_TOOLCHAIN" = "0" ]; then
    if [ "$TARGET_ARCH" = "all" ]; then
        for arch in aarch64 x86_64 armhf x86 riscv64; do
            install_toolchain "$arch"
        done
    else
        install_toolchain "$TARGET_ARCH"
    fi
fi

# 3. Build
if [ "$TARGET_ARCH" = "all" ]; then
    info "Building all architectures..."
    make --no-print-directory all-build V="$MAKE_VERBOSE" ${MAKE_NO_STRIP:+NO_STRIP=$MAKE_NO_STRIP}

    # Rename outputs with arch suffix (all-build already does this)
    if [ "$SYNC_ANDROID" = "1" ]; then
        make --no-print-directory sync-android
        ok "Synced all binaries to Android assets."
    fi
else
    build_arch "$TARGET_ARCH"

    # Rename binary with arch suffix in-place so repeated builds don't clobber each other
    if [ -f "output/droidspaces" ]; then
        cp -f "output/droidspaces" "output/droidspaces-${TARGET_ARCH}"
        ok "Binary copy saved as output/droidspaces-${TARGET_ARCH}"
    fi

    if [ "$SYNC_ANDROID" = "1" ]; then
        sync_to_android "$TARGET_ARCH"
    fi
fi

echo ""
ok "All done. Binaries are in: $(pwd)/output/"
ls -lh output/droidspaces* 2>/dev/null | awk '{print "  "$NF,"("$5")"}'
echo ""
