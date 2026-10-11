#!/system/bin/sh
# Rootfs Tarball Validator for Droidspaces
# Copyright (c) 2026 ravindu644
# Inspects a container tarball (WITHOUT extracting) to confirm it actually
# contains a Linux rootfs, so arbitrary archives cannot be installed.

# Parameters
TARBALL="$1"
BUSYBOX_PATH="${BUSYBOX_PATH:-/data/local/Droidspaces/bin/busybox}"
BB="$BUSYBOX_PATH"

log() { $BB echo "$1" >&2; }

# Check BusyBox
if [ ! -x "$BB" ]; then
    log "BusyBox not found or not executable at $BB"
    exit 1
fi

# Check tarball
if [ -z "$TARBALL" ] || [ ! -f "$TARBALL" ]; then
    log "Tarball not found: $TARBALL"
    exit 1
fi

# Pick a decompressor from the extension (mirrors the extractor).
case "$TARBALL" in
    *.xz)  DECOMP="$BB xzcat" ;;
    *.bz2) DECOMP="$BB bzcat" ;;
    *)     DECOMP="$BB zcat"  ;;
esac

# Stream entry names and confirm the core Linux rootfs structure without
# decompressing the whole archive. Compatible with modern merged-usr distros
# (where sbin/bin may be symlinks or live under /usr), Alpine/musl, minimal
# container images, and NixOS (nix/store).
# A tarball wrapped in a single top-level dir (e.g. rootfs/bin/...) is
# intentionally rejected, it would extract to the wrong layout anyway.
RESULT=$($DECOMP "$TARBALL" 2>/dev/null | $BB tar -tf - 2>/dev/null | $BB awk '
    {
        name = $0
        sub(/^\.\//, "", name)
        sub(/^\//, "", name)

        # Direct Linux rootfs file markers
        if (name ~ /^(etc\/os-release|etc\/passwd|etc\/group|etc\/shadow|etc\/issue|etc\/hostname)$/) ok = 1
        if (name ~ /^(bin\/sh|bin\/bash|bin\/busybox|bin\/dash|usr\/bin\/sh|usr\/bin\/bash|sbin\/init)$/) ok = 1

        split(name, parts, "/")
        top = parts[1]
        if (top == "nix")   n = 1
        if (top == "bin")   b = 1
        if (top == "sbin")  s = 1
        if (top == "etc")   e = 1
        if (top == "usr")   u = 1
        if (top == "lib" || top == "lib64" || top == "lib32") l = 1
        if (top == "var")   v = 1

        # Check conditions:
        # 1. Direct file marker hit
        # 2. NixOS marker
        # 3. etc + (bin or usr or sbin or lib)
        # 4. bin + (usr or lib or var)
        # 5. Any 3 core directories present
        if (ok || n || (e && (b || u || s || l)) || (b && (u || l || v)) || ((b + s + e + u + l + v) >= 3)) {
            ok = 1
            print "OK"
            exit
        }
    }
    END {
        if (!ok) {
            miss = ""
            if (!b && !u) miss = miss " bin/usr"
            if (!e) miss = miss " etc"
            print "MISSING" miss
        }
    }')

case "$RESULT" in
    OK)
        exit 0
        ;;
    MISSING*)
        log "Not a Linux rootfs - missing standard rootfs directories:${RESULT#MISSING}"
        exit 2
        ;;
    *)
        log "Could not read archive (corrupt or not a supported tarball?)"
        exit 3
        ;;
esac
