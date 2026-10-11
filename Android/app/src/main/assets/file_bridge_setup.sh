#!/bin/sh
# File Bridge / Thunar integration setup.
# Runs INSIDE the container as root (via `droidspaces --name=X run`), so all
# paths here are container-relative, not host/rootfs-relative.
#
# Usage: ds_fb_setup.sh <outbox-dest-path> <shared-base-path>
#
# Idempotent: safe to run every time the bridge is (re)enabled.

set -e

OUTBOX_DEST="$1"
SHARED_PATH="${2:-/mnt/shared}"
SEND_SCRIPT="/usr/local/bin/droidspaces-send-to-android"

# ---------------------------------------------------------------------------
# 1. "Send to Android" helper script -- what the Thunar custom action runs.
#    Just copies the file(s) into the outbox with -p (preserves permissions,
#    ownership where possible, and timestamps -- requirement 6). The Android
#    side watches this folder and turns new files into a share/open prompt.
# ---------------------------------------------------------------------------
mkdir -p "$(dirname "$SEND_SCRIPT")"
cat > "$SEND_SCRIPT" <<'SCRIPT_EOF'
#!/bin/sh
# Copies each argument into the Droidspaces Android outbox, preserving
# filenames (including Unicode -- the filesystem is UTF-8 and this never
# touches the name), permissions, and timestamps.
OUTBOX="__OUTBOX_DEST__"
if [ -z "$OUTBOX" ] || [ ! -d "$OUTBOX" ]; then
    echo "Droidspaces outbox ($OUTBOX) is not available. Is the File Bridge enabled?" >&2
    exit 1
fi
status=0
for f in "$@"; do
    if [ -e "$f" ]; then
        cp -p -r "$f" "$OUTBOX/" || status=1
    fi
done
exit $status
SCRIPT_EOF
# Bake in the real outbox path (avoids relying on env vars reaching Thunar's
# spawned process, which isn't guaranteed).
sed -i "s#__OUTBOX_DEST__#$OUTBOX_DEST#" "$SEND_SCRIPT"
chmod 755 "$SEND_SCRIPT"

# ---------------------------------------------------------------------------
# 2. Thunar custom action: right-click a file -> "Send to Android".
#    System-wide (/etc/xdg/Thunar/uca.xml) so it applies for every user
#    without needing to touch each home directory. Only added if Thunar's
#    config directory exists (i.e. Thunar is actually installed) and the
#    action isn't already present.
# ---------------------------------------------------------------------------
UCA_DIR="/etc/xdg/Thunar"
UCA_FILE="$UCA_DIR/uca.xml"

if command -v thunar >/dev/null 2>&1 || [ -d "$UCA_DIR" ] || [ -x /usr/bin/thunar ]; then
    mkdir -p "$UCA_DIR"
    if [ ! -f "$UCA_FILE" ]; then
        cat > "$UCA_FILE" <<'UCA_EOF'
<?xml version="1.0" encoding="UTF-8"?>
<actions>
</actions>
UCA_EOF
    fi

    if ! grep -q "droidspaces-send-to-android" "$UCA_FILE" 2>/dev/null; then
        # Insert a new <action> block right before the closing </actions> tag.
        # Using a temp file keeps this atomic and safe to re-run.
        awk -v script="$SEND_SCRIPT" '
            /<\/actions>/ && !done {
                print "  <action>"
                print "    <icon>android-file-transfer</icon>"
                print "    <name>Send to Android</name>"
                print "    <unique-id>droidspaces-send-to-android</unique-id>"
                print "    <command>" script " %F</command>"
                print "    <description>Copy the selected file(s) to Android via Droidspaces</description>"
                print "    <patterns>*</patterns>"
                print "    <startup-notify/>"
                print "    <directories/>"
                print "    <audio-files/>"
                print "    <image-files/>"
                print "    <other-files/>"
                print "    <text-files/>"
                print "    <video-files/>"
                print "  </action>"
                done = 1
            }
            { print }
        ' "$UCA_FILE" > "$UCA_FILE.tmp" && mv "$UCA_FILE.tmp" "$UCA_FILE"
        echo "[FILE-BRIDGE] Added 'Send to Android' to Thunar's right-click menu"
    fi
else
    echo "[FILE-BRIDGE] Thunar not detected -- skipped custom action (bind mounts still work with any file manager)"
fi

# ---------------------------------------------------------------------------
# 3. GTK bookmark ("Android Files" in Thunar's sidebar) for every real user
#    home directory, so browsing the bridge doesn't require typing a path.
# ---------------------------------------------------------------------------
BOOKMARK_LINE="file://$SHARED_PATH Android Files"
for home_dir in /root /home/*; do
    [ -d "$home_dir" ] || continue
    cfg_dir="$home_dir/.config/gtk-3.0"
    bookmarks="$cfg_dir/bookmarks"
    mkdir -p "$cfg_dir" 2>/dev/null || continue
    touch "$bookmarks" 2>/dev/null || continue
    if ! grep -qF "file://$SHARED_PATH " "$bookmarks" 2>/dev/null; then
        echo "$BOOKMARK_LINE" >> "$bookmarks"
    fi
done

echo "[FILE-BRIDGE-SETUP-OK]"
