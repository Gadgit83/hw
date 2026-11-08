#!/usr/bin/env bash
# Clone the engine fork and the SDL2 family into host/third_party (all gitignored).
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

if [ ! -e "$HW_ENGINE_DIR/hedgewars/hwengine.pas" ]; then
  log "clone engine fork $HW_ENGINE_REPO ($HW_ENGINE_BRANCH) into $HW_ENGINE_DIR"
  mkdir -p "$(dirname "$HW_ENGINE_DIR")"
  git clone --branch "$HW_ENGINE_BRANCH" "$HW_ENGINE_REPO" "$HW_ENGINE_DIR"
fi
clone SDL        https://github.com/libsdl-org/SDL        "$SDL_TAG"
clone SDL_image  https://github.com/libsdl-org/SDL_image  "$SDL_IMAGE_TAG"
clone SDL_mixer  https://github.com/libsdl-org/SDL_mixer  "$SDL_MIXER_TAG"
clone SDL_ttf    https://github.com/libsdl-org/SDL_ttf    "$SDL_TTF_TAG" external/freetype
clone SDL_net    https://github.com/libsdl-org/SDL_net    "$SDL_NET_TAG"
