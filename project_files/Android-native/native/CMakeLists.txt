# Native build of Hedge Party: SDL2 family + PhysFS + Lua + pas2c engine + launcher.
# Driven by Gradle (externalNativeBuild) or directly with the NDK toolchain file.
cmake_minimum_required(VERSION 3.22)
project(hedgeparty C)

set(HP_THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../third_party" CACHE PATH "SDL2 family sources")
set(HP_ENGINE_SRC  "${CMAKE_CURRENT_SOURCE_DIR}/../../upstream/hw" CACHE PATH "Engine fork checkout")
set(HP_ENGINE_C    "${CMAKE_CURRENT_SOURCE_DIR}/../build/engine-c" CACHE PATH "pas2c output")
set(HP_RUST_DIR    "${CMAKE_CURRENT_SOURCE_DIR}/../build/rust" CACHE PATH "libhwengine_future.so per ABI")

# ---- SDL2 family (shared libraries, minimal codecs, no external deps) --------
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${HP_THIRD_PARTY}/SDL SDL2-build EXCLUDE_FROM_ALL)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(SDL2IMAGE_INSTALL OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_SAMPLES OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_VENDORED OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_BACKEND_STB ON CACHE BOOL "" FORCE)
foreach(f AVIF JXL TIF WEBP JPG LBM PCX PNM QOI SVG XCF XPM XV GIF BMP TGA)
  set(SDL2IMAGE_${f} OFF CACHE BOOL "" FORCE)
endforeach()
set(SDL2IMAGE_PNG ON CACHE BOOL "" FORCE)
set(SDL2IMAGE_JPG ON CACHE BOOL "" FORCE)
add_subdirectory(${HP_THIRD_PARTY}/SDL_image SDL2_image-build EXCLUDE_FROM_ALL)

set(SDL2MIXER_INSTALL OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_SAMPLES OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_VENDORED OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_DEPS_SHARED OFF CACHE BOOL "" FORCE)
foreach(f FLAC GME MOD MP3 MIDI OPUS WAVPACK CMD)
  set(SDL2MIXER_${f} OFF CACHE BOOL "" FORCE)
endforeach()
set(SDL2MIXER_WAVE ON CACHE BOOL "" FORCE)
set(SDL2MIXER_VORBIS STB CACHE STRING "" FORCE)
add_subdirectory(${HP_THIRD_PARTY}/SDL_mixer SDL2_mixer-build EXCLUDE_FROM_ALL)

set(SDL2TTF_INSTALL OFF CACHE BOOL "" FORCE)
set(SDL2TTF_SAMPLES OFF CACHE BOOL "" FORCE)
set(SDL2TTF_VENDORED ON CACHE BOOL "" FORCE)
set(SDL2TTF_HARFBUZZ OFF CACHE BOOL "" FORCE)
set(SDL2TTF_PLUTOSVG OFF CACHE BOOL "" FORCE)
add_subdirectory(${HP_THIRD_PARTY}/SDL_ttf SDL2_ttf-build EXCLUDE_FROM_ALL)

set(SDL2NET_INSTALL OFF CACHE BOOL "" FORCE)
set(SDL2NET_SAMPLES OFF CACHE BOOL "" FORCE)
set(SDL2NET_STATIC OFF CACHE BOOL "" FORCE)
add_subdirectory(${HP_THIRD_PARTY}/SDL_net SDL2_net-build EXCLUDE_FROM_ALL)

# ---- Lua 5.1 and PhysFS bundled with the engine -------------------------------
file(GLOB LUA_SRC "${HP_ENGINE_SRC}/misc/liblua/*.c")
list(REMOVE_ITEM LUA_SRC "${HP_ENGINE_SRC}/misc/liblua/lua.c" "${HP_ENGINE_SRC}/misc/liblua/luac.c" "${HP_ENGINE_SRC}/misc/liblua/print.c")
add_library(lua STATIC ${LUA_SRC})
target_compile_definitions(lua PRIVATE LUA_USE_POSIX)
target_include_directories(lua PUBLIC "${HP_ENGINE_SRC}/misc/liblua")
set_target_properties(lua PROPERTIES POSITION_INDEPENDENT_CODE ON)

set(PHYSFS_DIR "${HP_ENGINE_SRC}/misc/libphysfs")
add_library(physfs STATIC
  ${PHYSFS_DIR}/physfs.c ${PHYSFS_DIR}/physfs_byteorder.c ${PHYSFS_DIR}/physfs_unicode.c
  ${PHYSFS_DIR}/platform_posix.c ${PHYSFS_DIR}/platform_unix.c
  ${PHYSFS_DIR}/archiver_dir.c ${PHYSFS_DIR}/archiver_grp.c ${PHYSFS_DIR}/archiver_hog.c
  ${PHYSFS_DIR}/archiver_lzma.c ${PHYSFS_DIR}/archiver_mvl.c ${PHYSFS_DIR}/archiver_qpak.c
  ${PHYSFS_DIR}/archiver_wad.c ${PHYSFS_DIR}/archiver_zip.c)
target_compile_definitions(physfs PRIVATE PHYSFS_NO_CDROM_SUPPORT=1 PHYSFS_SUPPORTS_ZIP=1)
target_compile_options(physfs PRIVATE -fsigned-char -w)
target_include_directories(physfs PUBLIC ${PHYSFS_DIR})
set_target_properties(physfs PROPERTIES POSITION_INDEPENDENT_CODE ON)

set(PL_DIR "${HP_ENGINE_SRC}/misc/libphyslayer")
add_library(physlayer STATIC ${PL_DIR}/physfscompat.c ${PL_DIR}/physfsrwops.c
  ${PL_DIR}/physfslualoader.c ${PL_DIR}/hwpacksmounter.c)
target_include_directories(physlayer PUBLIC ${PL_DIR})
target_link_libraries(physlayer PUBLIC SDL2::SDL2 lua physfs)
target_compile_options(physlayer PRIVATE -w)
set_target_properties(physlayer PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ---- Rust library (map generator etc.), prebuilt by scripts/build-rust.sh -----
add_library(hwengine_future SHARED IMPORTED)
set_target_properties(hwengine_future PROPERTIES
  IMPORTED_LOCATION "${HP_RUST_DIR}/${ANDROID_ABI}/libhwengine_future.so"
  IMPORTED_SONAME "libhwengine_future.so")

# ---- Pascal runtime in C (fpcrtl) and the pas2c-generated engine --------------
set(RTL_DIR "${HP_ENGINE_SRC}/project_files/hwc/rtl")
file(GLOB RTL_SRC "${RTL_DIR}/*.c")
add_library(fpcrtl STATIC ${RTL_SRC})
target_include_directories(fpcrtl PUBLIC ${RTL_DIR} ${HP_ENGINE_C} include)
target_link_libraries(fpcrtl PUBLIC SDL2::SDL2 SDL2_image::SDL2_image SDL2_mixer::SDL2_mixer
  SDL2_ttf::SDL2_ttf SDL2_net::SDL2_net lua physfs physlayer)
target_compile_options(fpcrtl PRIVATE -fsigned-char -w)
target_compile_definitions(fpcrtl PUBLIC HP_GLES1=1)
set_target_properties(fpcrtl PROPERTIES POSITION_INDEPENDENT_CODE ON)

file(GLOB ENGINE_SRC "${HP_ENGINE_C}/*.c")
add_library(hwengine_c STATIC ${ENGINE_SRC})
target_compile_definitions(hwengine_c PRIVATE PAS2C=1)
target_compile_options(hwengine_c PRIVATE -fsigned-char -fno-strict-aliasing -w -Werror=incompatible-pointer-types)
target_link_libraries(hwengine_c PUBLIC fpcrtl hwengine_future)
set_target_properties(hwengine_c PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ---- libmain.so: SDL_main entry + in-process IPC server -----------------------
add_library(main SHARED launcher/hp_main.c launcher/hp_ipc.c)
target_link_libraries(main PRIVATE hwengine_c SDL2::SDL2 GLESv1_CM log m dl)
target_link_options(main PRIVATE -Wl,--no-undefined)
