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
#      Idempotent - a stamp file per dependency is written once it is unpacked.
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
SDL2_VERSION=2.32.10
SDL2_IMAGE_VERSION=2.8.12
SDL2_MIXER_VERSION=2.8.2
SDL2_NET_VERSION=2.4.0

ZLIB_TAG=v1.3.1
SPNG_TAG=v0.7.4
# 1.25 needs std::format and std::lexicographical_compare_three_way, which the
# libc++ shipped with NDK r26 does not provide. 1.24.3 is C++17 and builds clean.
OPENAL_TAG=1.24.3
ENET6_TAG=v6.1.3
ASTRONOMY_TAG=v2.1.19
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
CURL_VERSION=8.21.0
CURL_TAG=curl-8_21_0
# Automatic port forwarding. miniupnpc lives in a subdirectory of the miniupnp
# repository, which also holds the daemon we have no use for.
# The Smacker videos. Only the demuxer and the two decoders for them are built,
# so this is a few megabytes rather than the whole of FFmpeg, and
# src/bflib_fmvids.cpp then compiles unchanged like every other module whose
# library was missing.
FFMPEG_VERSION=7.1
MINIUPNP_TAG=miniupnpc_2_3_3
LIBNATPMP_COMMIT=134fc89e2781e154e40042641f4d8bcbe42579f1
LUAJIT_COMMIT=1edc3e52b67eaf6ce5f809be8e17d6862594b8bc
CENTIJSON_COMMIT=93395382de7ea59f7348759b78d5b2044370fcce

log() { printf '\033[1;34m[deps]\033[0m %s\n' "$*"; }

# fetch_tarball <name> <url> <strip-dir-inside-archive>
fetch_tarball() {
    local name="$1" url="$2" inner="$3"
    local target="${DEPS_DIR}/${name}"
    if [ -f "${target}/.stamp" ]; then
        log "${name} already present"
        return 0
    fi
    log "downloading ${name} from ${url}"
    rm -rf "${target}" "${target}.tmp"
    mkdir -p "${target}.tmp"
    curl -fL --retry 3 --retry-delay 5 -o "${DEPS_DIR}/${name}.archive" "${url}"
    # No -z: the archives are a mix of gzip and bzip2 and GNU tar picks the
    # decompressor itself.
    tar -xf "${DEPS_DIR}/${name}.archive" -C "${target}.tmp"
    mv "${target}.tmp/${inner}" "${target}"
    rm -rf "${target}.tmp" "${DEPS_DIR}/${name}.archive"
    touch "${target}/.stamp"
}

# fetch_git <name> <repo> <ref> [--recurse]
fetch_git() {
    local name="$1" repo="$2" ref="$3"
    shift 3
    local target="${DEPS_DIR}/${name}"
    if [ -f "${target}/.stamp" ]; then
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
    if [ "${1:-}" = "--recurse" ]; then
        git -C "${target}" submodule update -q --init --recursive --depth 1
    fi
    touch "${target}/.stamp"
}

fetch_tarball SDL2 \
    "https://github.com/libsdl-org/SDL/releases/download/release-${SDL2_VERSION}/SDL2-${SDL2_VERSION}.tar.gz" \
    "SDL2-${SDL2_VERSION}"

fetch_tarball SDL2_image \
    "https://github.com/libsdl-org/SDL_image/releases/download/release-${SDL2_IMAGE_VERSION}/SDL2_image-${SDL2_IMAGE_VERSION}.tar.gz" \
    "SDL2_image-${SDL2_IMAGE_VERSION}"

fetch_tarball SDL2_mixer \
    "https://github.com/libsdl-org/SDL_mixer/releases/download/release-${SDL2_MIXER_VERSION}/SDL2_mixer-${SDL2_MIXER_VERSION}.tar.gz" \
    "SDL2_mixer-${SDL2_MIXER_VERSION}"

fetch_tarball SDL2_net \
    "https://github.com/libsdl-org/SDL_net/releases/download/release-${SDL2_NET_VERSION}/SDL2_net-${SDL2_NET_VERSION}.tar.gz" \
    "SDL2_net-${SDL2_NET_VERSION}"

fetch_tarball mbedtls \
    "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-${MBEDTLS_VERSION}/mbedtls-${MBEDTLS_VERSION}.tar.bz2" \
    "mbedtls-${MBEDTLS_VERSION}"

fetch_tarball curl \
    "https://github.com/curl/curl/releases/download/${CURL_TAG}/curl-${CURL_VERSION}.tar.gz" \
    "curl-${CURL_VERSION}"

fetch_git zlib       https://github.com/madler/zlib.git           "${ZLIB_TAG}"
fetch_git libspng    https://github.com/randy408/libspng.git      "${SPNG_TAG}"
fetch_git openal     https://github.com/kcat/openal-soft.git      "${OPENAL_TAG}"
fetch_git enet6      https://github.com/SirLynix/enet6.git        "${ENET6_TAG}"
fetch_git astronomy  https://github.com/cosinekitty/astronomy.git "${ASTRONOMY_TAG}"
fetch_git luajit     https://github.com/LuaJIT/LuaJIT.git         "${LUAJIT_COMMIT}"
fetch_git centijson  https://github.com/mity/centijson.git        "${CENTIJSON_COMMIT}"
fetch_tarball ffmpeg     "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"     "ffmpeg-${FFMPEG_VERSION}"

fetch_git miniupnp   https://github.com/miniupnp/miniupnp.git     "${MINIUPNP_TAG}"
fetch_git libnatpmp  https://github.com/miniupnp/libnatpmp.git    "${LIBNATPMP_COMMIT}"

# Several upstream projects ship their own Gradle wrapper for their samples and
# demos. We never run those, but a stray gradle-wrapper.jar in the workspace
# trips wrapper validation in CI, so drop them.
find "${DEPS_DIR}" -name 'gradle-wrapper.jar' -delete 2>/dev/null || true

log "all dependencies ready in ${DEPS_DIR}"
