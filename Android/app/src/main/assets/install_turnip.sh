#!/bin/sh
set -e

echo "=== Droidspaces Adreno Turnip GPU Driver Auto-Installer ==="

# 1. Ensure GPU group permissions & device node access
echo "[1/4] Ensuring user has access to GPU device nodes..."
chmod 1777 /tmp 2>/dev/null || true
for dev in /dev/kgsl-3d0 /dev/dri/card* /dev/dri/renderD*; do
    [ -e "$dev" ] && chmod 0666 "$dev" 2>/dev/null || true
done

if [ -n "$USER" ] && [ "$USER" != "root" ]; then
    sudo usermod -aG droidspaces-gpu,video,render "$USER" 2>/dev/null || true
fi
if id -u droidspaces >/dev/null 2>&1; then
    usermod -aG droidspaces-gpu,video,render droidspaces 2>/dev/null || true
fi

# 2. Detect Linux distribution & version
echo "[2/4] Detecting Linux distribution..."
DISTRO="unknown"
VERSION_ID=""
VERSION_CODENAME=""
if [ -f /etc/os-release ]; then
    . /etc/os-release
    DISTRO="${ID:-unknown}"
    VERSION_ID="${VERSION_ID:-}"
    VERSION_CODENAME="${VERSION_CODENAME:-}"
fi

echo "Detected distribution: $DISTRO ($VERSION_ID / $VERSION_CODENAME)"

# 3. Download and install Mesa Turnip driver package
echo "[3/4] Installing Mesa Turnip hardware acceleration drivers..."
TMPDIR="$(mktemp -d /tmp/turnip-install.XXXXXX)"
cd "$TMPDIR"

# Install dependencies including Vulkan loader, LLVM runtime and XCB cursor
if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq || true
    apt-get install -y -qq curl tar ca-certificates xz-utils libvulkan1 libllvm20 libxcb-cursor0 || \
    apt-get install -y -qq curl tar ca-certificates xz-utils libvulkan1 libxcb-cursor0 || true
elif command -v pacman >/dev/null 2>&1; then
    pacman -Sy --noconfirm curl tar ca-certificates vulkan-icd-loader libxcb || true
elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache curl tar ca-certificates vulkan-loader || true
elif command -v dnf >/dev/null 2>&1; then
    dnf install -y curl tar ca-certificates vulkan-loader || true
fi

# Match exact distribution package suffix
SUFFIX=""
case "$DISTRO" in
    debian*)
        case "$VERSION_ID" in
            13*|*trixie*) SUFFIX="debian_trixie_arm64" ;;
            12*|*bookworm*) SUFFIX="debian_bookworm_arm64" ;;
            *) SUFFIX="debian_trixie_arm64" ;;
        esac
        ;;
    ubuntu*)
        case "$VERSION_ID" in
            26.*|*resolute*) SUFFIX="ubuntu_resolute_arm64" ;;
            25.*|*questing*|*plucky*) SUFFIX="ubuntu_questing_arm64" ;;
            24.*|*noble*) SUFFIX="ubuntu_noble_arm64" ;;
            *)
                case "$VERSION_CODENAME" in
                    resolute*) SUFFIX="ubuntu_resolute_arm64" ;;
                    questing*|plucky*) SUFFIX="ubuntu_questing_arm64" ;;
                    noble*) SUFFIX="ubuntu_noble_arm64" ;;
                    *) SUFFIX="ubuntu_resolute_arm64" ;;
                esac
                ;;
        esac
        ;;
    fedora*)
        case "$VERSION_ID" in
            44*) SUFFIX="fedora_44_arm64" ;;
            43*) SUFFIX="fedora_43_arm64" ;;
            *) SUFFIX="fedora_43_arm64" ;;
        esac
        ;;
    arch*|archarm|endeavouros|manjaro)
        SUFFIX="archlinux_arm64"
        ;;
    alpine*)
        SUFFIX="alpine_3.24_arm64"
        ;;
    void*)
        SUFFIX="void_arm64"
        ;;
    *)
        SUFFIX="debian_trixie_arm64"
        ;;
esac

echo "Target architecture package pattern: $SUFFIX"

# Dynamic URL resolution from GitHub Releases
DOWNLOAD_URL=""
LATEST_JSON="$(curl -fsSL https://api.github.com/repos/lfdevs/mesa-for-android-container/releases/latest 2>/dev/null || true)"
if [ -n "$LATEST_JSON" ]; then
    DOWNLOAD_URL="$(echo "$LATEST_JSON" | grep -o "\"browser_download_url\": *\"[^\"]*${SUFFIX}\.tar[^\"]*\"" | head -n1 | cut -d'"' -f4 || true)"
fi

if [ -z "$DOWNLOAD_URL" ]; then
    FALLBACK_TAG="mesa-26.2.0-devel-20260709"
    TAG_JSON="$(curl -fsSL "https://api.github.com/repos/lfdevs/mesa-for-android-container/releases/tags/$FALLBACK_TAG" 2>/dev/null || true)"
    if [ -n "$TAG_JSON" ]; then
        DOWNLOAD_URL="$(echo "$TAG_JSON" | grep -o "\"browser_download_url\": *\"[^\"]*${SUFFIX}\.tar[^\"]*\"" | head -n1 | cut -d'"' -f4 || true)"
    fi
fi

if [ -z "$DOWNLOAD_URL" ]; then
    DOWNLOAD_URL="https://github.com/lfdevs/mesa-for-android-container/releases/download/mesa-26.2.0-devel-20260709/mesa-for-android-container_26.2.0-devel-20260709_${SUFFIX}.tar.gz"
fi

echo "Downloading Mesa Turnip package from: $DOWNLOAD_URL"
if curl -fL --progress-bar "$DOWNLOAD_URL" -o mesa-turnip.pkg 2>/dev/null || wget -q --show-progress "$DOWNLOAD_URL" -O mesa-turnip.pkg 2>/dev/null; then
    echo "Extracting Mesa Turnip drivers to rootfs..."
    tar -xf mesa-turnip.pkg -C / 2>/dev/null || tar -zxf mesa-turnip.pkg -C /
    echo "Mesa Turnip driver binary extraction complete."
else
    echo "Notice: Direct binary package download failed or offline. Installing distro Mesa drivers..."
    if command -v apt-get >/dev/null 2>&1; then
        apt-get install -y -qq mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools libllvm20 libxcb-cursor0 || true
    elif command -v pacman >/dev/null 2>&1; then
        pacman -S --noconfirm mesa vulkan-freedreno vulkan-tools || true
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache mesa-vulkan-freedreno mesa-dri-gallium vulkan-tools || true
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y mesa-vulkan-drivers mesa-dri-drivers vulkan-tools || true
    fi
fi

# Ensure libvulkan_freedreno.so symlinks across all standard multiarch paths
echo "Creating Vulkan Freedreno library symlinks..."
mkdir -p /usr/lib /usr/lib/aarch64-linux-gnu /usr/lib64 /usr/share/vulkan/icd.d 2>/dev/null || true

if [ -f /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so ]; then
    echo "Found libvulkan_freedreno.so in /usr/lib/aarch64-linux-gnu/"
    [ -e /usr/lib/libvulkan_freedreno.so ] || ln -sf /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so /usr/lib/libvulkan_freedreno.so
    [ -e /usr/lib64/libvulkan_freedreno.so ] || ln -sf /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so /usr/lib64/libvulkan_freedreno.so
    echo "  ✓ Symlinks created."
elif [ -f /usr/lib/libvulkan_freedreno.so ]; then
    echo "Found libvulkan_freedreno.so in /usr/lib/"
    [ -e /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so ] || ln -sf /usr/lib/libvulkan_freedreno.so /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so
    [ -e /usr/lib64/libvulkan_freedreno.so ] || ln -sf /usr/lib/libvulkan_freedreno.so /usr/lib64/libvulkan_freedreno.so
    echo "  ✓ Symlinks created."
elif [ -f /usr/lib64/libvulkan_freedreno.so ]; then
    echo "Found libvulkan_freedreno.so in /usr/lib64/"
    [ -e /usr/lib/libvulkan_freedreno.so ] || ln -sf /usr/lib64/libvulkan_freedreno.so /usr/lib/libvulkan_freedreno.so
    [ -e /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so ] || ln -sf /usr/lib64/libvulkan_freedreno.so /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so
    echo "  ✓ Symlinks created."
else
    echo "  ⚠ Warning: libvulkan_freedreno.so not found in standard locations"
fi

ldconfig 2>/dev/null || true
echo "Library cache updated."

rm -rf "$TMPDIR"

# 4. Configure environment variables for KGSL & Turnip
echo "[4/4] Configuring container environment for Turnip..."
ENV_FILE="/etc/environment"
touch "$ENV_FILE"
sed -i 's/^[[:space:]]*export[[:space:]]\+//g' "$ENV_FILE" 2>/dev/null || true
grep -q "^MESA_LOADER_DRIVER_OVERRIDE=" "$ENV_FILE" || echo "MESA_LOADER_DRIVER_OVERRIDE=kgsl" >> "$ENV_FILE"
grep -q "^GALLIUM_DRIVER=" "$ENV_FILE" || echo "GALLIUM_DRIVER=kgsl" >> "$ENV_FILE"
grep -q "^FD_FORCE_KGSL=" "$ENV_FILE" || echo "FD_FORCE_KGSL=1" >> "$ENV_FILE"
grep -q "^TU_DEBUG=" "$ENV_FILE" || echo "TU_DEBUG=noconform" >> "$ENV_FILE"

if [ -f /run/droidspaces.env ]; then
    sed -i 's/^[[:space:]]*export[[:space:]]\+//g' /run/droidspaces.env 2>/dev/null || true
    grep -q "^MESA_LOADER_DRIVER_OVERRIDE=" /run/droidspaces.env || echo "MESA_LOADER_DRIVER_OVERRIDE=kgsl" >> /run/droidspaces.env
    grep -q "^GALLIUM_DRIVER=" /run/droidspaces.env || echo "GALLIUM_DRIVER=kgsl" >> /run/droidspaces.env
    grep -q "^FD_FORCE_KGSL=" /run/droidspaces.env || echo "FD_FORCE_KGSL=1" >> /run/droidspaces.env
    grep -q "^TU_DEBUG=" /run/droidspaces.env || echo "TU_DEBUG=noconform" >> /run/droidspaces.env
fi

export MESA_LOADER_DRIVER_OVERRIDE=kgsl
export GALLIUM_DRIVER=kgsl
export FD_FORCE_KGSL=1
export TU_DEBUG=noconform

echo ""
echo "=========================================================="
echo "  Adreno Turnip GPU Drivers configured successfully!      "
echo "  MESA_LOADER_DRIVER_OVERRIDE=kgsl                        "
echo "  GALLIUM_DRIVER=kgsl                                     "
echo "  FD_FORCE_KGSL=1                                         "
echo "  TU_DEBUG=noconform                                      "
echo "=========================================================="
