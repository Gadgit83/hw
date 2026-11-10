#!/usr/bin/env bash
# Copy the default player names from the hedgewars-party repository into the app as an asset.
#   sync-player-names.sh                 uses origin/main of /home/user/hedgewars-party (shared/player-names.txt)
#   sync-player-names.sh /path/to/repo   uses origin/main of that checkout
#   sync-player-names.sh /path/file.txt  uses that file as it is
# Rules checked: one name per line, at most 20 characters, plain ASCII; blank lines and lines starting with # are skipped.
# Output: app/src/main/assets/player-names.txt (packaged in both the test and the full profile). Re-run when the list changes.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/../app/src/main/assets/player-names.txt"
SRC="${1:-/home/user/hedgewars-party}"
TMP="$(mktemp)"
trap 'rm -f "$TMP" "$TMP.out"' EXIT
if [ -d "$SRC" ]; then
    git -C "$SRC" fetch -q origin main || echo "warning: fetch failed, using the last fetched origin/main" >&2
    git -C "$SRC" show origin/main:shared/player-names.txt > "$TMP"
elif [ -f "$SRC" ]; then
    cp "$SRC" "$TMP"
else
    echo "error: $SRC is neither a checkout nor a file" >&2; exit 1
fi
LC_ALL=C awk '
    { sub(/\r$/, "") }
    /^[ \t]*$/ || /^#/ { next }
    /[^\x20-\x7e]/ { printf "error: line %d is not plain ASCII: %s\n", NR, $0 > "/dev/stderr"; bad = 1; next }
    length($0) > 20 { printf "error: line %d is %d characters (max 20): %s\n", NR, length($0), $0 > "/dev/stderr"; bad = 1; next }
    { n++; print > OUTF }
    END { if (bad) exit 1; if (n == 0) { print "error: no names found" > "/dev/stderr"; exit 1 } printf "%d names\n", n > "/dev/stderr" }
' OUTF="$TMP.out" "$TMP"
mkdir -p "$(dirname "$OUT")"
mv "$TMP.out" "$OUT"
echo "wrote $OUT"
