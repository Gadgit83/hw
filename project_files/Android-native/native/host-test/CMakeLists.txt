# Desktop smoke test of the pas2c engine + launcher against system SDL2 (apt: libsdl2-*-dev).
# Uses desktop OpenGL in place of OpenGL ES 1.1 (the calls used by the renderer are common to both).
cmake_minimum_required(VERSION 3.16)
project(hp_hosttest C)
find_package(SDL2 REQUIRED)
find_package(OpenGL REQUIRED)
set(HP_ENGINE_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../upstream/hw" CACHE PATH "")
set(HP_ENGINE_C   "${CMAKE_CURRENT_SOURCE_DIR}/../../build/engine-c-host" CACHE PATH "")
set(HP_RUST_LIB   "${CMAKE_CURRENT_SOURCE_DIR}/../../build/rust-host/libhwengine_future.so" CACHE FILEPATH "")
file(GLOB LUA_SRC "${HP_ENGINE_SRC}/misc/liblua/*.c")
list(REMOVE_ITEM LUA_SRC "${HP_ENGINE_SRC}/misc/liblua/lua.c" "${HP_ENGINE_SRC}/misc/liblua/luac.c" "${HP_ENGINE_SRC}/misc/liblua/print.c")
set(PD "${HP_ENGINE_SRC}/misc/libphysfs")
set(PL "${HP_ENGINE_SRC}/misc/libphyslayer")
file(GLOB RTL_SRC "${HP_ENGINE_SRC}/project_files/hwc/rtl/*.c")
file(GLOB ENGINE_SRC "${HP_ENGINE_C}/*.c")
# desktop GL stand-ins for the GLES1 shim headers
file(WRITE ${CMAKE_BINARY_DIR}/shim/gles11.h "#pragma once\n#include <GL/gl.h>\n#include <GL/glext.h>\n")
file(WRITE ${CMAKE_BINARY_DIR}/shim/GLUnit.h "#include \"gles11.h\"\n")
add_executable(hp_hosttest hp_host_main.c ../launcher/hp_ipc.c ${ENGINE_SRC} ${RTL_SRC} ${LUA_SRC}
  ${PD}/physfs.c ${PD}/physfs_byteorder.c ${PD}/physfs_unicode.c ${PD}/platform_posix.c ${PD}/platform_unix.c
  ${PD}/archiver_dir.c ${PD}/archiver_grp.c ${PD}/archiver_hog.c ${PD}/archiver_lzma.c ${PD}/archiver_mvl.c
  ${PD}/archiver_qpak.c ${PD}/archiver_wad.c ${PD}/archiver_zip.c
  ${PL}/physfscompat.c ${PL}/physfsrwops.c ${PL}/physfslualoader.c ${PL}/hwpacksmounter.c)
target_include_directories(hp_hosttest PRIVATE ${CMAKE_BINARY_DIR}/shim ${HP_ENGINE_C} ${HP_ENGINE_SRC}/project_files/hwc/rtl
  ${HP_ENGINE_SRC}/misc/liblua ${PD} ${PL} ${SDL2_INCLUDE_DIRS} /usr/include/SDL2)
target_compile_definitions(hp_hosttest PRIVATE PAS2C=1 HP_GLES1=0 LUA_USE_POSIX PHYSFS_NO_CDROM_SUPPORT=1)
set_source_files_properties(${PL}/hwpacksmounter.c PROPERTIES COMPILE_OPTIONS "-include;lua.h")
# Mirror the device build flags: optimised with FORTIFY on. Lua 5.1 is exempt (struct hack on
# TString, see native/CMakeLists.txt). Note: glibc does not check strchr, so this does NOT
# reproduce the bionic abort "FORTIFY: strchr: prevented read past end of buffer"; the option
# only exists to compare. The device-only check is verified by inspecting the Android binary.
option(HP_FORTIFY_LUA "Keep FORTIFY on for Lua (reproduces the device crash)" OFF)
target_compile_options(hp_hosttest PRIVATE -w -fsigned-char -fno-strict-aliasing -g -O2 -D_FORTIFY_SOURCE=3)
if(NOT HP_FORTIFY_LUA)
  set_source_files_properties(${LUA_SRC} PROPERTIES COMPILE_OPTIONS "-U_FORTIFY_SOURCE;-D_FORTIFY_SOURCE=0")
endif()
target_link_libraries(hp_hosttest PRIVATE SDL2 SDL2_image SDL2_mixer SDL2_ttf SDL2_net OpenGL::GL ${HP_RUST_LIB} pthread m dl)
