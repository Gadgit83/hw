#!/usr/bin/env bash
# Clone the SDL2 family into third_party/ (gitignored; THIRD_PARTY=/some/dir uses or fills another cache).
# The engine is not fetched: it is the repository this directory belongs to.
# GitHub archive downloads are not used; everything is a git clone.
. "$(dirname "$0")/common.sh"
mkdir -p "$THIRD_PARTY"

clone() { # name url tag [submodule paths...]
  local dir="$THIRD_PARTY/$1" name="$1" url="$2" tag="$3"; shift 3
  if [ -d "$dir/.git" ]; then echo "have $name"; return; fi
  log "clone $name @ $tag"
  git clone --depth 1 --branch "$tag" "$url" "$dir"
  # Only the submodules we need (others, e.g. libjxl, live on hosts that may be unreachable).
  for sm in "$@"; do git -C "$dir" submodule update --init --depth 1 "$sm"; done
}

[ -e "$HW_ENGINE_DIR/hedgewars/hwengine.pas" ] || { echo "no engine sources at $HW_ENGINE_DIR (expected hedgewars/hwengine.pas)"; exit 1; }
clone SDL        https://github.com/libsdl-org/SDL        "$SDL_TAG"
clone SDL_image  https://github.com/libsdl-org/SDL_image  "$SDL_IMAGE_TAG"
clone SDL_mixer  https://github.com/libsdl-org/SDL_mixer  "$SDL_MIXER_TAG"
clone SDL_ttf    https://github.com/libsdl-org/SDL_ttf    "$SDL_TTF_TAG" external/freetype
clone SDL_net    https://github.com/libsdl-org/SDL_net    "$SDL_NET_TAG"
