#!/usr/bin/env bash
# Stage a trimmed copy of the game data for packaging (build/assets/Data).
# Nothing from the engine checkout is committed to this repository.
#   HP_MUSIC=lite|full|none   music tracks to keep (default lite: a handful of tracks)
#   HP_LOCALES=en|all         locale files to keep (default en)
#   PROFILE=full|test         test: the reduced data of the test profile (no music, no maps, 3 themes, one fort, one voice
#                             pack, a few hats); see README "Build profiles". full (default) is unchanged.
. "$(dirname "$0")/common.sh"
SRC="$HW_ENGINE_DIR/share/hedgewars/Data"
OUT="$BUILD_DIR/assets/Data"
[ "$PROFILE" = test ] && HP_MUSIC="${HP_MUSIC:-none}"
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

if [ "$PROFILE" = test ]; then
  # Reduced data for automated phone and TV tests. What is kept is a whitelist (see README "Build profiles"); the setup screen lists
  # only what is on disk (GameData) and SetupModel.repair() replaces anything missing, so nothing here has to be known to the app.
  TEST_THEMES="Nature Snow Hell"            # all three are also in the TV viewer's data package (it ships every theme)
  # Two game styles set their own theme (Space_Invasion: EarthRise, TechRacer: Cave) and the engine stops without it. These two are
  # staged without icon.png, which hides a theme from the pickers and from "Random" (GameData), so only the three above are offered.
  TEST_HIDDEN_THEMES="EarthRise Cave"
  TEST_FORTS="Earth"
  TEST_VOICES="Default"
  # NoHat, hats that most themed groups of Names/types.ini use, and those the game modes put on (crown: king, Skull, angel, Sniper: The_Specialists)
  TEST_HATS="NoHat royalguard policecap clown-copper cyborg1 InfernalHorns Glasses pinksunhat anzac crown Skull angel Sniper"
  TEST_FLAGS="hedgewars cpu cpu_plain"     # the team flag, and the ones computer teams use
  log "test profile: pruning $OUT"
  set -f   # the keep lists hold glob patterns
  keep_only() {   # keep_only DIR PATTERN...: delete the entries of DIR whose names match none of the patterns
    local dir="$1" e k keep; shift
    set +f
    for e in "$dir"/*; do
      [ -e "$e" ] || continue; keep=0
      for k in "$@"; do case "$(basename "$e")" in $k) keep=1;; esac; done
      [ $keep = 1 ] || rm -rf "$e"
    done
    set -f
  }
  rm -rf "$OUT/Maps" "$OUT/Music" "$OUT/Missions"             # generated maps (random, maze, perlin, wfc, forts) read nothing from Maps
  keep_only "$OUT/Themes" $TEST_THEMES $TEST_HIDDEN_THEMES
  for t in $TEST_HIDDEN_THEMES; do rm -f "$OUT"/Themes/$t/icon*; done
  keep_only "$OUT/Forts" $(for f in $TEST_FORTS; do printf '%s*.png ' "$f"; done)
  keep_only "$OUT/Sounds/voices" $TEST_VOICES
  # Hats/Reserved is kept whole: the engine loads Reserved/chef (and Reserved/Zombie) itself, and crashes without chef
  keep_only "$OUT/Graphics/Hats" Reserved $(for h in $TEST_HATS; do printf '%s.png ' "$h"; done)
  keep_only "$OUT/Graphics/Flags" $(for f in $TEST_FLAGS; do printf '%s.png ' "$f"; done)
  find "$OUT" -type f \( -name '*.svg' -o -name '*.xcf' -o -name '*.sifz' \) -delete
  for t in $TEST_THEMES $TEST_HIDDEN_THEMES; do [ -f "$OUT/Themes/$t/theme.cfg" ] || { echo "test profile: theme $t missing from the engine data"; exit 1; }; done
  [ -f "$OUT/Forts/EarthL.png" ] && [ -d "$OUT/Sounds/voices/Default" ] || { echo "test profile: fort or voice pack missing"; exit 1; }
  set +f
  printf 'Test profile data: themes %s (hidden, for game styles: %s); forts %s; voice packs %s; hats %s; no image maps, no music.\n' \
    "$TEST_THEMES" "$TEST_HIDDEN_THEMES" "$TEST_FORTS" "$TEST_VOICES" "$TEST_HATS" > "$OUT/TEST_PROFILE.txt"
fi

# Licence texts travel with the data (GFDL art and sound, GPL engine).
cp "$HW_ENGINE_DIR/COPYING" "$OUT/COPYING" 2>/dev/null || true
cp "$HW_ENGINE_DIR/CREDITS" "$OUT/CREDITS" 2>/dev/null || true
du -sh "$OUT"; find "$OUT" -type f | wc -l
