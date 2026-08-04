#******************************************************************************
#  Free implementation of Bullfrog's Dungeon Keeper strategy game.
#******************************************************************************
#   @file android/app/CMakeLists.txt
#      Native build of the Android port.
#  @par Purpose:
#      Builds libkeeperfx.so plus every native dependency from source with the
#      Android NDK toolchain. Sources are pulled by android/scripts/fetch-deps.sh
#      beforehand, so this file never touches the network.
#  @par Comment:
#      This is additive: neither Makefile nor linux.mk nor the root CMakeLists
#      are involved, so the Windows and Linux builds are untouched.
#  @author   KeeperFX Team
#  @date     04 Aug 2026
#
#      This program is free software; you can redistribute it and/or modify
#      it under the terms of the GNU General Public License as published by
#      the Free Software Foundation; either version 2 of the License, or
#      (at your option) any later version.
#
#******************************************************************************
cmake_minimum_required(VERSION 3.22)

project(keeperfx_android C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(KFX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
get_filename_component(KFX_DEPS "${CMAKE_CURRENT_SOURCE_DIR}/../.deps" ABSOLUTE)

if(NOT EXISTS "${KFX_DEPS}/SDL2/CMakeLists.txt")
    message(FATAL_ERROR
        "Dependencies are missing. Run android/scripts/fetch-deps.sh first.")
endif()

# Third party sources are not ours to keep warning free.
add_compile_options(-Wno-everything)

#******************************************************************************
# Version header
#******************************************************************************
file(READ "${KFX_ROOT}/version.mk" KFX_VERSION_MK)
foreach(field VER_MAJOR VER_MINOR VER_RELEASE VER_BUILD)
    string(REGEX MATCH "${field}=([0-9]+)" _match "${KFX_VERSION_MK}")
    set(${field} "${CMAKE_MATCH_1}")
endforeach()
if(NOT DEFINED KFX_BUILD_NUMBER OR KFX_BUILD_NUMBER STREQUAL "")
    set(KFX_BUILD_NUMBER "${VER_BUILD}")
endif()
set(VER_BUILD "${KFX_BUILD_NUMBER}")
if(NOT DEFINED PACKAGE_SUFFIX OR PACKAGE_SUFFIX STREQUAL "")
    set(PACKAGE_SUFFIX "Android")
endif()
if(NOT DEFINED GIT_REVISION OR GIT_REVISION STREQUAL "")
    set(GIT_REVISION "unknown")
endif()
set(VER_STRING "${VER_MAJOR}.${VER_MINOR}.${VER_RELEASE}.${VER_BUILD} ${PACKAGE_SUFFIX}")

set(KFX_GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${KFX_GENERATED_DIR}")
# ver_defs.h.in uses ${} placeholders, so the default substitution syntax applies.
configure_file("${KFX_ROOT}/ver_defs.h.in" "${KFX_GENERATED_DIR}/ver_defs.h")

#******************************************************************************
# SDL2 and satellites
#******************************************************************************
set(SDL_SHARED ON  CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST   OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_INSTALL ON CACHE BOOL "" FORCE)
set(SDL2_DISABLE_UNINSTALL ON CACHE BOOL "" FORCE)
add_subdirectory("${KFX_DEPS}/SDL2" "${CMAKE_CURRENT_BINARY_DIR}/SDL2" EXCLUDE_FROM_ALL)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

set(SDL2IMAGE_INSTALL     OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_SAMPLES     OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_VENDORED    OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_DEPS_SHARED OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_BACKEND_STB ON  CACHE BOOL "" FORCE)
set(SDL2IMAGE_AVIF        OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_JXL         OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_TIF         OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_WEBP        OFF CACHE BOOL "" FORCE)
add_subdirectory("${KFX_DEPS}/SDL2_image" "${CMAKE_CURRENT_BINARY_DIR}/SDL2_image" EXCLUDE_FROM_ALL)

set(SDL2MIXER_INSTALL     OFF    CACHE BOOL   "" FORCE)
set(SDL2MIXER_SAMPLES     OFF    CACHE BOOL   "" FORCE)
set(SDL2MIXER_VENDORED    OFF    CACHE BOOL   "" FORCE)
set(SDL2MIXER_DEPS_SHARED OFF    CACHE BOOL   "" FORCE)
# stb_vorbis is built into SDL2_mixer, so Ogg music needs no external libraries.
set(SDL2MIXER_VORBIS      "STB"  CACHE STRING "" FORCE)
set(SDL2MIXER_FLAC        OFF    CACHE BOOL   "" FORCE)
set(SDL2MIXER_MOD         OFF    CACHE BOOL   "" FORCE)
set(SDL2MIXER_MP3         OFF    CACHE BOOL   "" FORCE)
set(SDL2MIXER_MIDI        OFF    CACHE BOOL   "" FORCE)
set(SDL2MIXER_OPUS        OFF    CACHE BOOL   "" FORCE)
set(SDL2MIXER_WAVPACK     OFF    CACHE BOOL   "" FORCE)
set(SDL2MIXER_GME         OFF    CACHE BOOL   "" FORCE)
add_subdirectory("${KFX_DEPS}/SDL2_mixer" "${CMAKE_CURRENT_BINARY_DIR}/SDL2_mixer" EXCLUDE_FROM_ALL)

set(SDL2NET_INSTALL OFF CACHE BOOL "" FORCE)
set(SDL2NET_SAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory("${KFX_DEPS}/SDL2_net" "${CMAKE_CURRENT_BINARY_DIR}/SDL2_net" EXCLUDE_FROM_ALL)

# The engine includes the satellite libraries as <SDL2/SDL_mixer.h> and friends,
# matching how the desktop packages install them. Built from source they sit in
# include/SDL_mixer.h instead, so stage them under the expected prefix.
set(KFX_SDL2_COMPAT_INC "${CMAKE_CURRENT_BINARY_DIR}/sdl2-compat-include")
file(MAKE_DIRECTORY "${KFX_SDL2_COMPAT_INC}/SDL2")
configure_file("${KFX_DEPS}/SDL2_image/include/SDL_image.h"
               "${KFX_SDL2_COMPAT_INC}/SDL2/SDL_image.h" COPYONLY)
configure_file("${KFX_DEPS}/SDL2_mixer/include/SDL_mixer.h"
               "${KFX_SDL2_COMPAT_INC}/SDL2/SDL_mixer.h" COPYONLY)
configure_file("${KFX_DEPS}/SDL2_net/include/SDL_net.h"
               "${KFX_SDL2_COMPAT_INC}/SDL2/SDL_net.h" COPYONLY)

# Everything from here on is linked straight into libkeeperfx.so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

#******************************************************************************
# zlib + minizip
#******************************************************************************
set(ZLIB_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory("${KFX_DEPS}/zlib" "${CMAKE_CURRENT_BINARY_DIR}/zlib" EXCLUDE_FROM_ALL)
# zlib generates zconf.h into its own binary dir.
set(KFX_ZLIB_INCLUDES "${KFX_DEPS}/zlib" "${CMAKE_CURRENT_BINARY_DIR}/zlib")

# KeeperFX includes <minizip/unzip.h>; stage zlib's contrib/minizip headers under
# that prefix and build the three source files it needs.
set(KFX_MINIZIP_INC "${CMAKE_CURRENT_BINARY_DIR}/minizip-include")
file(MAKE_DIRECTORY "${KFX_MINIZIP_INC}/minizip")
foreach(header ioapi.h unzip.h zip.h crypt.h mztools.h)
    configure_file("${KFX_DEPS}/zlib/contrib/minizip/${header}"
                   "${KFX_MINIZIP_INC}/minizip/${header}" COPYONLY)
endforeach()
add_library(kfx_minizip STATIC
    "${KFX_DEPS}/zlib/contrib/minizip/ioapi.c"
    "${KFX_DEPS}/zlib/contrib/minizip/unzip.c"
    "${KFX_DEPS}/zlib/contrib/minizip/zip.c")
target_include_directories(kfx_minizip PUBLIC "${KFX_MINIZIP_INC}" PRIVATE ${KFX_ZLIB_INCLUDES})
# Bionic only grew the *64 stdio calls in API 24; the 32 bit API is enough here.
target_compile_definitions(kfx_minizip PRIVATE USE_FILE32API)
target_link_libraries(kfx_minizip PRIVATE zlibstatic)

#******************************************************************************
# libspng
#
# Built directly rather than through its CMakeLists, which insists on
# find_package(ZLIB REQUIRED) and would not see the zlib we just built. The
# library is a single translation unit, so there is nothing else to configure.
#******************************************************************************
add_library(kfx_spng STATIC "${KFX_DEPS}/libspng/spng/spng.c")
target_include_directories(kfx_spng PUBLIC "${KFX_DEPS}/libspng/spng")
target_include_directories(kfx_spng PRIVATE ${KFX_ZLIB_INCLUDES})
target_compile_definitions(kfx_spng PUBLIC SPNG_STATIC=1)
target_link_libraries(kfx_spng PRIVATE zlibstatic)

#******************************************************************************
# OpenAL Soft
#******************************************************************************
set(ALSOFT_UTILS         OFF CACHE BOOL "" FORCE)
set(ALSOFT_EXAMPLES      OFF CACHE BOOL "" FORCE)
set(ALSOFT_TESTS         OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL       OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_CONFIG OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_HRTF_DATA OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_AMBDEC_PRESETS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_UTILS OFF CACHE BOOL "" FORCE)
set(LIBTYPE "SHARED" CACHE STRING "" FORCE)
add_subdirectory("${KFX_DEPS}/openal" "${CMAKE_CURRENT_BINARY_DIR}/openal" EXCLUDE_FROM_ALL)

#******************************************************************************
# enet6
#******************************************************************************
# enet6 has no library type option of its own; it follows BUILD_SHARED_LIBS,
# which is OFF from here on.
add_subdirectory("${KFX_DEPS}/enet6" "${CMAKE_CURRENT_BINARY_DIR}/enet6" EXCLUDE_FROM_ALL)

#******************************************************************************
# centijson, centitoml, astronomy - small C libraries, compiled directly
#******************************************************************************
add_library(kfx_centijson STATIC
    "${KFX_DEPS}/centijson/src/json.c"
    "${KFX_DEPS}/centijson/src/json-dom.c"
    "${KFX_DEPS}/centijson/src/json-ptr.c"
    "${KFX_DEPS}/centijson/src/value.c")
target_include_directories(kfx_centijson PUBLIC "${KFX_DEPS}/centijson/src")

add_library(kfx_centitoml STATIC "${KFX_ROOT}/deps/centitoml/toml_api.c")
target_include_directories(kfx_centitoml PUBLIC "${KFX_ROOT}/deps/centitoml")
target_link_libraries(kfx_centitoml PUBLIC kfx_centijson)

add_library(kfx_astronomy STATIC "${KFX_DEPS}/astronomy/source/c/astronomy.c")
target_include_directories(kfx_astronomy PUBLIC "${KFX_DEPS}/astronomy/source/c")

#******************************************************************************
# LuaJIT
#
# LuaJIT ships a hand written Makefile rather than CMake. It is cross compiled
# with the NDK clang; the JIT itself is disabled because Android's W^X policy
# makes writable-executable mappings unreliable across vendors. The interpreter
# is fully API and ABI compatible, which is all the engine needs - level scripts
# are not performance critical.
#******************************************************************************
include(ExternalProject)

get_filename_component(NDK_BIN "${CMAKE_C_COMPILER}" DIRECTORY)
set(LUAJIT_WORK "${CMAKE_CURRENT_BINARY_DIR}/luajit")
set(LUAJIT_LIB  "${LUAJIT_WORK}/src/libluajit.a")
# ANDROID_LLVM_TRIPLE already carries the API level (aarch64-none-linux-android21),
# so it must not be appended a second time.
if(CMAKE_C_COMPILER_TARGET)
    set(LUAJIT_TRIPLE "${CMAKE_C_COMPILER_TARGET}")
elseif(DEFINED ANDROID_LLVM_TRIPLE)
    set(LUAJIT_TRIPLE "${ANDROID_LLVM_TRIPLE}")
else()
    message(FATAL_ERROR "Cannot determine the LLVM target triple for LuaJIT")
endif()
set(LUAJIT_TARGET_FLAGS "--target=${LUAJIT_TRIPLE}")
if(CMAKE_SYSROOT)
    set(LUAJIT_TARGET_FLAGS "${LUAJIT_TARGET_FLAGS} --sysroot=${CMAKE_SYSROOT}")
endif()
message(STATUS "LuaJIT cross target: ${LUAJIT_TARGET_FLAGS}")

find_program(KFX_MAKE_PROGRAM NAMES make gmake REQUIRED)

ExternalProject_Add(luajit_external
    SOURCE_DIR "${KFX_DEPS}/luajit"
    BUILD_IN_SOURCE OFF
    DOWNLOAD_COMMAND ""
    UPDATE_COMMAND ""
    CONFIGURE_COMMAND "${CMAKE_COMMAND}" -E copy_directory "${KFX_DEPS}/luajit" "${LUAJIT_WORK}"
    # libluajit.a is a target of src/Makefile; LuaJIT's top level Makefile only
    # forwards to it and would also link the luajit executable, which we do not
    # need for the target architecture.
    BUILD_COMMAND "${KFX_MAKE_PROGRAM}" -C "${LUAJIT_WORK}/src"
        "HOST_CC=cc"
        "CROSS=${NDK_BIN}/llvm-"
        "STATIC_CC=${NDK_BIN}/clang"
        "DYNAMIC_CC=${NDK_BIN}/clang -fPIC"
        "TARGET_LD=${NDK_BIN}/clang"
        "TARGET_AR=${NDK_BIN}/llvm-ar rcus"
        "TARGET_STRIP=${NDK_BIN}/llvm-strip"
        "TARGET_FLAGS=${LUAJIT_TARGET_FLAGS}"
        "TARGET_SYS=Linux"
        "XCFLAGS=-DLUAJIT_DISABLE_JIT -fPIC"
        "BUILDMODE=static"
        libluajit.a
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${LUAJIT_LIB}"
    LOG_BUILD OFF)

add_library(kfx_luajit STATIC IMPORTED GLOBAL)
set_target_properties(kfx_luajit PROPERTIES IMPORTED_LOCATION "${LUAJIT_LIB}")
add_dependencies(kfx_luajit luajit_external)
# The headers only exist after the copy step, so declare the directory eagerly.
file(MAKE_DIRECTORY "${LUAJIT_WORK}/src")
target_include_directories(kfx_luajit INTERFACE "${LUAJIT_WORK}/src")

#******************************************************************************
# KeeperFX engine
#******************************************************************************
file(GLOB KFX_SOURCES_C   "${KFX_ROOT}/src/*.c")
file(GLOB KFX_SOURCES_CXX "${KFX_ROOT}/src/*.cpp" "${KFX_ROOT}/src/kfx/lense/*.cpp")

# Platform files of the other ports, plus the three modules replaced by Android
# specific stubs (FFmpeg, libcurl and UPnP are not bundled).
set(KFX_EXCLUDED
    "${KFX_ROOT}/src/linux.cpp"
    "${KFX_ROOT}/src/windows.cpp"
    "${KFX_ROOT}/src/cdrom.cpp"
    "${KFX_ROOT}/src/steam_api.cpp"
    "${KFX_ROOT}/src/bflib_fmvids.cpp"
    "${KFX_ROOT}/src/net_matchmaking.c"
    "${KFX_ROOT}/src/net_portforward.cpp")
list(REMOVE_ITEM KFX_SOURCES_C   ${KFX_EXCLUDED})
list(REMOVE_ITEM KFX_SOURCES_CXX ${KFX_EXCLUDED})

add_library(keeperfx SHARED ${KFX_SOURCES_C} ${KFX_SOURCES_CXX})

target_include_directories(keeperfx PRIVATE
    "${KFX_ROOT}/src"
    "${KFX_GENERATED_DIR}"
    "${KFX_SDL2_COMPAT_INC}"
    ${KFX_ZLIB_INCLUDES})

target_compile_definitions(keeperfx PRIVATE
    BFDEBUG_LEVEL=0
    SPNG_STATIC=1)

target_compile_options(keeperfx PRIVATE
    -Wall -Wextra
    -Wno-unused-parameter
    -Wno-unknown-pragmas
    -Wno-sign-compare
    -Wno-format-truncation
    -Wno-missing-field-initializers
    -fno-strict-aliasing)

target_link_libraries(keeperfx PRIVATE
    SDL2::SDL2
    SDL2_image::SDL2_image
    SDL2_mixer::SDL2_mixer
    SDL2_net::SDL2_net
    OpenAL::OpenAL
    kfx_spng
    kfx_minizip
    zlibstatic
    enet6
    kfx_centijson
    kfx_centitoml
    kfx_astronomy
    kfx_luajit
    android
    log
    GLESv1_CM
    GLESv2
    OpenSLES
    m
    dl)
