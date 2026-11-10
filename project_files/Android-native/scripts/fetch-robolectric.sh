#!/usr/bin/env bash
# Fills the cache Robolectric reads its Android jars from, so the launch smoke test runs offline.
#   ROBOLECTRIC_DEPS=dir   cache folder (default ~/.cache/hedgewars/robolectric); build.gradle reads the same variable
#   ROBOLECTRIC_MIRRORS    space separated Maven repository base URLs, tried in order
# Robolectric downloads these itself from repo1.maven.org, which answers HTTP 429 to many networks. The first mirror here
# is Google's copy of Maven Central (the same files, same checksums); repo1 is the fallback.
. "$(dirname "$0")/common.sh"
DEST="${ROBOLECTRIC_DEPS:-$HOME/.cache/hedgewars/robolectric}"
MIRRORS="${ROBOLECTRIC_MIRRORS:-https://maven-central.storage-download.googleapis.com/maven2 https://repo1.maven.org/maven2}"
mkdir -p "$DEST"
fetch() {   # fetch <url> <file>: resumable, retried
  curl -fsSL --retry 3 --retry-delay 2 -C - -o "$2" "$1"
}
ok=1
for v in $ROBOLECTRIC_JARS; do
  name="android-all-instrumented-$v.jar"
  if [ -s "$DEST/$name" ] && [ -f "$DEST/$name.sha1" ] && [ "$(sha1sum "$DEST/$name" | cut -d' ' -f1)" = "$(cat "$DEST/$name.sha1")" ]; then
    echo "have $name"; continue
  fi
  got=0
  for m in $MIRRORS; do
    url="$m/org/robolectric/android-all-instrumented/$v/$name"
    log "fetching $name from $m"
    rm -f "$DEST/$name.part"
    if fetch "$url" "$DEST/$name.part" && fetch "$url.sha1" "$DEST/$name.sha1.part"; then
      want="$(cut -d' ' -f1 < "$DEST/$name.sha1.part" | tr -d '[:space:]')"
      have="$(sha1sum "$DEST/$name.part" | cut -d' ' -f1)"
      if [ "$want" = "$have" ]; then
        mv "$DEST/$name.part" "$DEST/$name"; echo "$want" > "$DEST/$name.sha1"; rm -f "$DEST/$name.sha1.part"; got=1; break
      fi
      echo "checksum mismatch from $m (wanted $want, got $have)" >&2
    else
      echo "could not download from $m" >&2
    fi
  done
  [ "$got" = 1 ] || { echo "ERROR: could not fetch $name from any mirror" >&2; ok=0; }
done
[ "$ok" = 1 ] || exit 1
echo "Robolectric Android jars are in $DEST"
