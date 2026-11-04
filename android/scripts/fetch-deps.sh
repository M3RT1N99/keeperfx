#!/usr/bin/env bash
#******************************************************************************
#  Free implementation of Bullfrog's Dungeon Keeper strategy game.
#******************************************************************************
#   @file android/scripts/fetch-deps.sh
#      Downloads the native dependencies of the Android build.
#  @par Purpose:
#      The desktop ports pull prebuilt libraries from dkfans/kfx-deps, which has
#      no Android arm64 artifacts. Everything is therefore built from source by
#      the NDK toolchain; this script only fetches the pinned sources so that
#      CMake itself never needs network access and the whole directory can be
#      cached by CI.
#  @par Comment:
#      Idempotent - a stamp file per dependency records the pin it was unpacked
#      from, so changing a pin below is all it takes to fetch the new one.
#
#      Everything goes into a signed APK, so every source is pinned to a checksum
#      or a commit and a mismatch stops the build. To move a pin, change it and
#      put the value the failure prints next to it, after checking it against what
#      the project publishes.
#  @author   KeeperFX Team
#  @date     04 Aug 2026
#
#      This program is free software; you can redistribute it and/or modify
#      it under the terms of the GNU General Public License as published by
#      the Free Software Foundation; either version 2 of the License, or
#      (at your option) any later version.
#
#******************************************************************************
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPS_DIR="${ANDROID_DIR}/.deps"
mkdir -p "${DEPS_DIR}"

# --- Pinned versions -------------------------------------------------------
# The versions build/cmake/modules/Dependencies.cmake pins for the desktop ports.
SDL3_VERSION=3.4.18
SDL3_SHA256=9c75cf16330322c217dedd2e0609f1124f1b54b8633e763467b4684d0f4334a3
SDL3_IMAGE_VERSION=3.4.6
SDL3_IMAGE_SHA256=d2e4637ae700f72e5196b8fbd749850ed2e5e1e09c5a5be8d06ff55aaccf3b01
SDL3_MIXER_VERSION=3.2.4
SDL3_MIXER_SHA256=182a07c745375e113dc740d43964ff21b0be29f29f59876c4dbc4db3d32f6901

ZLIB_TAG=v1.3.1
ZLIB_COMMIT=51b7f2abdade71cd9bb0e7a373ef2610ec6f9daf
SPNG_TAG=v0.7.4
SPNG_COMMIT=fb768002d4288590083a476af628e51c3f1d47cd
# 1.25 needs std::format and std::lexicographical_compare_three_way, which the
# libc++ shipped with NDK r26 does not provide. 1.24.3 is C++17 and builds clean.
OPENAL_TAG=1.24.3
OPENAL_COMMIT=dc7d7054a5b4f3bec1dc23a42fd616a0847af948
ENET6_TAG=v6.1.3
ENET6_COMMIT=bf0003fb0004b12ff1d2b0c51c7c7e9a0d2d7732
ASTRONOMY_TAG=v2.1.19
ASTRONOMY_COMMIT=61dc07020aaa6885d2c7f688a4d82beaf6edb9ef
# TLS for the matchmaking client. mbedTLS rather than OpenSSL: it is a plain
# CMake project that cross compiles without a bespoke configure system, and
# curl's mbedTLS backend reads a directory of certificates, which is the shape
# Android's trust store already has.
#
# Both are taken from the release tarballs, not from git. mbedTLS generates
# several sources (error.c, version_features.c, the PSA driver wrappers) from
# Jinja templates; the tarball ships them ready made, a git checkout would need
# Python with jinja2 on the runner and the framework submodule on top.
MBEDTLS_VERSION=3.6.7
MBEDTLS_SHA256=a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6
CURL_VERSION=8.21.0
CURL_TAG=curl-8_21_0
CURL_SHA256=d9b327997999045a24cda50f3983e69e51c516bd8be6ef9842fc7f99135e33bb
# Automatic port forwarding. miniupnpc lives in a subdirectory of the miniupnp
# repository, which also holds the daemon we have no use for.
# The Smacker videos. Only the demuxer and the two decoders for them are built,
# so this is a few megabytes rather than the whole of FFmpeg, and
# src/bflib_fmvids.cpp then compiles unchanged like every other module whose
# library was missing.
FFMPEG_VERSION=7.1
FFMPEG_SHA256=40973d44970dbc83ef302b0609f2e74982be2d85916dd2ee7472d30678a7abe6
MINIUPNP_TAG=miniupnpc_2_3_3
MINIUPNP_COMMIT=bf4215a7574f88aa55859db9db00e3ae58cf42d6
LIBNATPMP_COMMIT=134fc89e2781e154e40042641f4d8bcbe42579f1
LUAJIT_COMMIT=1edc3e52b67eaf6ce5f809be8e17d6862594b8bc
CENTIJSON_COMMIT=93395382de7ea59f7348759b78d5b2044370fcce

log() { printf '\033[1;34m[deps]\033[0m %s\n' "$*"; }

# fetch_tarball <name> <url> <strip-dir-inside-archive> <sha256>
fetch_tarball() {
    local name="$1" url="$2" inner="$3" sha256="$4"
    local target="${DEPS_DIR}/${name}"
    if [ "$(cat "${target}/.stamp" 2>/dev/null)" = "${sha256}" ]; then
        log "${name} already present"
        return 0
    fi
    log "downloading ${name} from ${url}"
    rm -rf "${target}" "${target}.tmp"
    mkdir -p "${target}.tmp"
    curl -fL --proto '=https' --retry 3 --retry-delay 5 -o "${DEPS_DIR}/${name}.archive" "${url}"
    echo "${sha256}  ${DEPS_DIR}/${name}.archive" | sha256sum -c --quiet -
    # No -z: the archives are a mix of gzip and bzip2 and GNU tar picks the
    # decompressor itself.
    tar -xf "${DEPS_DIR}/${name}.archive" -C "${target}.tmp"
    mv "${target}.tmp/${inner}" "${target}"
    rm -rf "${target}.tmp" "${DEPS_DIR}/${name}.archive"
    echo "${sha256}" > "${target}/.stamp"
}

# fetch_git <name> <repo> <ref> <commit it has to be>
fetch_git() {
    local name="$1" repo="$2" ref="$3" commit="$4"
    local target="${DEPS_DIR}/${name}"
    if [ "$(cat "${target}/.stamp" 2>/dev/null)" = "${commit}" ]; then
        log "${name} already present"
        return 0
    fi
    log "cloning ${name} at ${ref}"
    rm -rf "${target}"
    # Full-ref clones are cheap here; --depth with an arbitrary commit needs
    # server side support that is not guaranteed, so fetch the single object.
    mkdir -p "${target}"
    git -C "${target}" init -q
    git -C "${target}" remote add origin "${repo}"
    git -C "${target}" fetch -q --depth 1 origin "${ref}"
    git -C "${target}" checkout -q FETCH_HEAD
    local got
    got="$(git -C "${target}" rev-parse HEAD)"
    if [ "${got}" != "${commit}" ]; then
        echo "${name}: ${ref} is ${got}, expected ${commit}" >&2
        exit 1
    fi
    echo "${commit}" > "${target}/.stamp"
}

# SDL reads SDL_HINT_ANDROID_BLOCK_ON_PAUSE once, when video starts. Whether the
# engine may stop depends on what it is doing when the activity pauses, so this
# makes SDL read the hint at every pause, after the events the engine answers it
# from. src/android.cpp explains the rest.
reread_block_on_pause_hint() {
    local file="${DEPS_DIR}/SDL3/src/video/android/SDL_androidevents.c"
    local line='    Android_BlockOnPause = SDL_GetHintBoolean(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, true);'
    if grep -qxF "${line}" "${file}"; then
        return 0
    fi
    sed -i "s/^    if (Android_BlockOnPause) {\$/${line}\n&/" "${file}"
    if [ "$(grep -cxF "${line}" "${file}")" != "1" ]; then
        echo "SDL_androidevents.c no longer looks the way this patch expects" >&2
        exit 1
    fi
}

fetch_tarball SDL3 \
    "https://github.com/libsdl-org/SDL/releases/download/release-${SDL3_VERSION}/SDL3-${SDL3_VERSION}.tar.gz" \
    "SDL3-${SDL3_VERSION}" "${SDL3_SHA256}"
reread_block_on_pause_hint

fetch_tarball SDL3_image \
    "https://github.com/libsdl-org/SDL_image/releases/download/release-${SDL3_IMAGE_VERSION}/SDL3_image-${SDL3_IMAGE_VERSION}.tar.gz" \
    "SDL3_image-${SDL3_IMAGE_VERSION}" "${SDL3_IMAGE_SHA256}"

fetch_tarball SDL3_mixer \
    "https://github.com/libsdl-org/SDL_mixer/releases/download/release-${SDL3_MIXER_VERSION}/SDL3_mixer-${SDL3_MIXER_VERSION}.tar.gz" \
    "SDL3_mixer-${SDL3_MIXER_VERSION}" "${SDL3_MIXER_SHA256}"

fetch_tarball mbedtls \
    "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-${MBEDTLS_VERSION}/mbedtls-${MBEDTLS_VERSION}.tar.bz2" \
    "mbedtls-${MBEDTLS_VERSION}" "${MBEDTLS_SHA256}"

fetch_tarball curl \
    "https://github.com/curl/curl/releases/download/${CURL_TAG}/curl-${CURL_VERSION}.tar.gz" \
    "curl-${CURL_VERSION}" "${CURL_SHA256}"

fetch_git zlib       https://github.com/madler/zlib.git           "${ZLIB_TAG}"         "${ZLIB_COMMIT}"
fetch_git libspng    https://github.com/randy408/libspng.git      "${SPNG_TAG}"         "${SPNG_COMMIT}"
fetch_git openal     https://github.com/kcat/openal-soft.git      "${OPENAL_TAG}"       "${OPENAL_COMMIT}"
fetch_git enet6      https://github.com/SirLynix/enet6.git        "${ENET6_TAG}"        "${ENET6_COMMIT}"
fetch_git astronomy  https://github.com/cosinekitty/astronomy.git "${ASTRONOMY_TAG}"    "${ASTRONOMY_COMMIT}"
fetch_git luajit     https://github.com/LuaJIT/LuaJIT.git         "${LUAJIT_COMMIT}"    "${LUAJIT_COMMIT}"
fetch_git centijson  https://github.com/mity/centijson.git        "${CENTIJSON_COMMIT}" "${CENTIJSON_COMMIT}"
fetch_tarball ffmpeg \
    "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
    "ffmpeg-${FFMPEG_VERSION}" "${FFMPEG_SHA256}"

fetch_git miniupnp   https://github.com/miniupnp/miniupnp.git     "${MINIUPNP_TAG}"     "${MINIUPNP_COMMIT}"
fetch_git libnatpmp  https://github.com/miniupnp/libnatpmp.git    "${LIBNATPMP_COMMIT}" "${LIBNATPMP_COMMIT}"

# Several upstream projects ship their own Gradle wrapper for their samples and
# demos. We never run those, but a stray gradle-wrapper.jar in the workspace
# trips wrapper validation in CI, so drop them.
find "${DEPS_DIR}" -name 'gradle-wrapper.jar' -delete 2>/dev/null || true

log "all dependencies ready in ${DEPS_DIR}"
