#!/usr/bin/env bash
# Stage a trimmed copy of the game data for packaging (build/assets/Data).
# Nothing from the engine checkout is committed to this repository.
#   HP_MUSIC=lite|full|none   music tracks to keep (default lite: a handful of tracks)
#   HP_LOCALES=en|all         locale files to keep (default en)
. "$(dirname "$0")/common.sh"
SRC="$HW_ENGINE_DIR/share/hedgewars/Data"
OUT="$BUILD_DIR/assets/Data"
HP_MUSIC="${HP_MUSIC:-lite}"
HP_LOCALES="${HP_LOCALES:-en}"
[ -d "$SRC" ] || { echo "game data not found at $SRC"; exit 1; }

log "staging game data -> $OUT"
rm -rf "$OUT"
mkdir -p "$OUT"
rsync -a \
  --exclude 'CMakeLists.txt' \
  --exclude 'Fonts/wqy-zenhei.ttc' \
  --exclude 'Missions/' \
  --include 'misc/keys.csv' --exclude 'misc/*' \
  --exclude '*.ts' --exclude '*.qm' \
  --exclude '*preview*.png' \
  --exclude 'Music/' \
  "$SRC/" "$OUT/"

# Mobile-only artwork (on-screen touch buttons, ammo menu sheets) lives in the Android port's assets.
# Only files missing from the desktop data are taken.
MOBILE="$HW_ENGINE_DIR/project_files/Android-build/SDL-android-project/assets/Data"
rsync -a --ignore-existing --exclude '*preview*' --exclude 'Music/' --exclude 'Locale/' --exclude 'Maps/' "$MOBILE/" "$OUT/"

# Locales: the engine always loads en.txt first; other languages are optional.
if [ "$HP_LOCALES" = "en" ]; then
  find "$OUT/Locale" -type f ! -name 'en.txt' ! -name 'en.lua' ! -name 'campaigns_en.txt' -delete
fi

mkdir -p "$OUT/Music"
case "$HP_MUSIC" in
  full) rsync -a --exclude CMakeLists.txt --exclude '*.opus' "$SRC/Music/" "$OUT/Music/" ;;
  lite) for f in main_theme.ogg Nature.ogg hell.ogg snow.ogg underwater.ogg EarthRise.ogg bath.ogg sdmusic.ogg olympics_sd.ogg credits.txt; do
          [ -f "$SRC/Music/$f" ] && cp "$SRC/Music/$f" "$OUT/Music/"; done ;;
  none) ;;
  *) echo "unknown HP_MUSIC=$HP_MUSIC"; exit 1 ;;
esac

# Licence texts travel with the data (GFDL art and sound, GPL engine).
cp "$HW_ENGINE_DIR/COPYING" "$OUT/COPYING" 2>/dev/null || true
cp "$HW_ENGINE_DIR/CREDITS" "$OUT/CREDITS" 2>/dev/null || true
du -sh "$OUT"; find "$OUT" -type f | wc -l
