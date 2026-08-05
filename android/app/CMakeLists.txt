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
# version.mk carries the suffix that marks this fork's builds as unofficial;
# Android adds its own name so a report says which build it came from. Gradle
# can still override the whole thing with keeperfx.packageSuffix.
if(NOT DEFINED PACKAGE_SUFFIX OR PACKAGE_SUFFIX STREQUAL "")
    string(REGEX MATCH "PACKAGE_SUFFIX=([^\r\n]*)" _match "${KFX_VERSION_MK}")
    if(CMAKE_MATCH_1 STREQUAL "")
        set(PACKAGE_SUFFIX "Android")
    else()
        set(PACKAGE_SUFFIX "Android-${CMAKE_MATCH_1}")
    endif()
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
# The engine accepts .wav, .mp3, .flac and .ogg for sounds and speech
# (config_sounds.c), so switching FLAC off made a whole declared format
# silently inaudible. drflac is built into SDL2_mixer and pulls in nothing.
set(SDL2MIXER_FLAC        ON     CACHE BOOL   "" FORCE)
set(SDL2MIXER_FLAC_DRFLAC ON     CACHE BOOL   "" FORCE)
set(SDL2MIXER_FLAC_LIBFLAC OFF   CACHE BOOL   "" FORCE)
set(SDL2MIXER_MOD         OFF    CACHE BOOL   "" FORCE)
# Campaign speech ships as MP3 - 40 files per language in the official release -
# so this cannot be off. minimp3 is built into SDL2_mixer and pulls in nothing.
# (SDL2MIXER_MP3_DRMP3 was set here before and does not exist in 2.8.2, so it
# did nothing; the backend option is called MINIMP3.)
set(SDL2MIXER_MP3         ON     CACHE BOOL   "" FORCE)
set(SDL2MIXER_MP3_MINIMP3 ON     CACHE BOOL   "" FORCE)
set(SDL2MIXER_MP3_MPG123  OFF    CACHE BOOL   "" FORCE)
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
# mbedTLS + libcurl
#
# The matchmaking client speaks wss:// to keeperfx.net, so it needs curl with
# WebSocket support and a TLS backend. mbedTLS rather than OpenSSL: it is an
# ordinary CMake project that cross compiles without a bespoke configure step,
# and curl's mbedTLS backend loads a directory of certificates through
# mbedtls_x509_crt_parse_path(), which is exactly the shape of Android's trust
# store - so no certificate bundle has to be shipped.
#******************************************************************************
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING  OFF CACHE BOOL "" FORCE)
set(MBEDTLS_AS_SUBPROJECT ON CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON  CACHE BOOL "" FORCE)
# On by default upstream; the NDK clang is not the compiler mbedTLS tunes its
# warning set for, and a single new warning would fail the whole APK build.
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GEN_FILES OFF CACHE BOOL "" FORCE)
add_subdirectory("${KFX_DEPS}/mbedtls" "${CMAKE_CURRENT_BINARY_DIR}/mbedtls" EXCLUDE_FROM_ALL)

# curl looks for mbedTLS with find_package. Pointing the four variables it
# checks at the targets built above skips the search entirely; CMake resolves
# the names as targets when it links.
set(MBEDTLS_INCLUDE_DIR "${KFX_DEPS}/mbedtls/include" CACHE PATH   "" FORCE)
set(MBEDTLS_LIBRARY     mbedtls                       CACHE STRING "" FORCE)
set(MBEDX509_LIBRARY    mbedx509                      CACHE STRING "" FORCE)
set(MBEDCRYPTO_LIBRARY  mbedcrypto                    CACHE STRING "" FORCE)

# The check curl runs for this links against an imported target, which a
# try_compile cannot see, so it would answer "no" after a confusing link error.
# Answer it directly: DES is only used for NTLM, which is disabled below.
set(HAVE_MBEDTLS_DES_CRYPT_ECB 0 CACHE INTERNAL "")

set(BUILD_CURL_EXE   OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(CURL_DISABLE_NTLM ON CACHE BOOL "" FORCE)
set(CURL_USE_MBEDTLS ON  CACHE BOOL "" FORCE)
set(CURL_USE_OPENSSL OFF CACHE BOOL "" FORCE)
set(CURL_USE_LIBPSL  OFF CACHE BOOL "" FORCE)
set(CURL_USE_LIBSSH2 OFF CACHE BOOL "" FORCE)
set(CURL_ZLIB        OFF CACHE STRING "" FORCE)
set(CURL_BROTLI      OFF CACHE BOOL "" FORCE)
set(CURL_ZSTD        OFF CACHE BOOL "" FORCE)
set(USE_LIBIDN2      OFF CACHE BOOL "" FORCE)
set(CURL_DISABLE_LDAP  ON CACHE BOOL "" FORCE)
set(CURL_DISABLE_LDAPS ON CACHE BOOL "" FORCE)
set(ENABLE_CURL_MANUAL OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING    OFF CACHE BOOL "" FORCE)
# Android's trust store. The engine never sets CURLOPT_CAINFO, so this compiled
# in default is what verifies the matchmaking server.
set(CURL_CA_PATH "/system/etc/security/cacerts" CACHE STRING "" FORCE)
set(CURL_CA_BUNDLE "none" CACHE STRING "" FORCE)
add_subdirectory("${KFX_DEPS}/curl" "${CMAKE_CURRENT_BINARY_DIR}/curl" EXCLUDE_FROM_ALL)

#******************************************************************************
# miniupnpc + libnatpmp
#
# Automatic port forwarding when hosting a game.
#******************************************************************************
set(UPNPC_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(UPNPC_BUILD_STATIC ON  CACHE BOOL "" FORCE)
set(UPNPC_BUILD_TESTS  OFF CACHE BOOL "" FORCE)
set(UPNPC_BUILD_SAMPLE OFF CACHE BOOL "" FORCE)
set(UPNPC_NO_INSTALL   ON  CACHE BOOL "" FORCE)
add_subdirectory("${KFX_DEPS}/miniupnp/miniupnpc" "${CMAKE_CURRENT_BINARY_DIR}/miniupnpc" EXCLUDE_FROM_ALL)
add_subdirectory("${KFX_DEPS}/libnatpmp" "${CMAKE_CURRENT_BINARY_DIR}/libnatpmp" EXCLUDE_FROM_ALL)

# Same situation as the SDL satellites: the engine includes
# <miniupnpc/miniupnpc.h>, which is where the headers land when miniupnpc is
# installed, but in the source tree they sit flat in include/.
set(KFX_UPNP_COMPAT_INC "${CMAKE_CURRENT_BINARY_DIR}/miniupnpc-compat-include")
file(MAKE_DIRECTORY "${KFX_UPNP_COMPAT_INC}/miniupnpc")
file(GLOB KFX_UPNP_HEADERS "${KFX_DEPS}/miniupnp/miniupnpc/include/*.h")
foreach(header ${KFX_UPNP_HEADERS})
    get_filename_component(_name "${header}" NAME)
    configure_file("${header}" "${KFX_UPNP_COMPAT_INC}/miniupnpc/${_name}" COPYONLY)
endforeach()

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

# Platform files of the other ports, plus the one module replaced by an Android
# specific stub (FFmpeg is not bundled).
set(KFX_EXCLUDED
    "${KFX_ROOT}/src/linux.cpp"
    "${KFX_ROOT}/src/windows.cpp"
    "${KFX_ROOT}/src/cdrom.cpp"
    "${KFX_ROOT}/src/steam_api.cpp"
    "${KFX_ROOT}/src/bflib_fmvids.cpp")
list(REMOVE_ITEM KFX_SOURCES_C   ${KFX_EXCLUDED})
list(REMOVE_ITEM KFX_SOURCES_CXX ${KFX_EXCLUDED})

add_library(keeperfx SHARED ${KFX_SOURCES_C} ${KFX_SOURCES_CXX})

target_include_directories(keeperfx PRIVATE
    "${KFX_ROOT}/src"
    "${KFX_GENERATED_DIR}"
    "${KFX_SDL2_COMPAT_INC}"
    "${KFX_UPNP_COMPAT_INC}"
    ${KFX_ZLIB_INCLUDES})

target_compile_definitions(keeperfx PRIVATE
    BFDEBUG_LEVEL=0
    SPNG_STATIC=1)

target_compile_options(keeperfx PRIVATE
    # Plain char is unsigned on ARM but signed on x86, and the engine uses -1 as
    # a sentinel in char valued returns and fields all over: pathfinding
    # (ariadne_wallhug.c), door orientation (thing_traps.c), menu fade
    # (gui_frontmenu.c) and about forty more. Unsigned char turns "x != -1" into
    # a constant true and "case -1:" into "case 255:", so those checks silently
    # stop working. Forcing signed char gives the same semantics the desktop
    # builds have always had, without touching any of those sites.
    -fsigned-char
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
    CURL::libcurl
    libminiupnpc-static
    natpmp
    android
    log
    GLESv1_CM
    GLESv2
    OpenSLES
    m
    dl)
