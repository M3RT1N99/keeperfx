#!/usr/bin/env bash
#******************************************************************************
#  Free implementation of Bullfrog's Dungeon Keeper strategy game.
#******************************************************************************
#   @file android/scripts/emulator-smoke.sh
#      Plays through a fresh install the way a new player does.
#  @par Purpose:
#      Installs an APK on a clean slate, drives the launcher through the release,
#      the alpha patch, the music and the import of the original game, presses Play
#      and checks that a level really starts and keeps running. Exit code 0 means
#      all of that was seen happening. Anything else stops it with a reason and
#      what the device had to say.
#  @par Comment:
#      Plain adb and the launcher's own views, no test code in the app. It needs a
#      device that allows "adb root", to read the engine log out of the app's
#      private storage, which in practice means an emulator image. It downloads
#      the real release, so give it a few minutes and a network.
#
#      emulator-smoke.sh <apk> <folder of the original game>
#
#      The folder is what a player would pick in the import, with DATA and SOUND
#      somewhere inside. SMOKE_ARGS replaces the engine options, which by default
#      skip the intro and go straight to the first level.
#  @author   KeeperFX Team
#  @date     04 Nov 2026
#
#      This program is free software; you can redistribute it and/or modify
#      it under the terms of the GNU General Public License as published by
#      the Free Software Foundation; either version 2 of the License, or
#      (at your option) any later version.
#
#******************************************************************************
# Without pipefail on purpose: "adb ... | grep -q" would fail whenever grep has its
# answer before adb has finished writing, and read as "not on screen".
set -eu

PKG=net.keeperfx.android
ID="${PKG}:id"
SMOKE_ARGS="${SMOKE_ARGS:--nointro -level 1}"
DOWNLOAD_TIMEOUT_S=2400
LEVEL_TIMEOUT_S=180
PICKED_FOLDER=kfx-smoke-originals
GAME_DIR="/data/data/${PKG}/files/keeperfx"
LOG="${GAME_DIR}/keeperfx.log"

say() { printf '\033[1;34m[smoke]\033[0m %s\n' "$*"; }

fail() {
    printf '\033[1;31m[smoke] FAILED:\033[0m %s\n' "$*" >&2
    {
        echo "--- on screen"
        ui_summary || true
        echo "--- engine log"
        adb shell tail -n 15 "${LOG}" 2>/dev/null | cut -c1-160 || true
        echo "--- crashes"
        adb logcat -d -b crash 2>/dev/null | tail -n 15 | cut -c1-160 || true
    } >&2
    exit 1
}

[ $# -eq 2 ] || { echo "usage: $0 <apk> <folder of the original game>" >&2; exit 2; }
APK="$1"
ORIGINALS="$2"
[ -f "${APK}" ] || { echo "no such APK: ${APK}" >&2; exit 2; }
[ -d "${ORIGINALS}" ] || { echo "no such folder: ${ORIGINALS}" >&2; exit 2; }

# --- Looking at and touching the screen --------------------------------------

# One view per line. A dump fails while the screen is changing, so it gets a few tries.
ui() {
    local attempt xml
    for attempt in 1 2 3 4 5; do
        if adb shell uiautomator dump /sdcard/kfx-ui.xml 2>/dev/null | grep -q 'dumped to'; then
            xml="$(adb exec-out cat /sdcard/kfx-ui.xml)"
            printf '%s' "${xml}" | tr '>' '\n'
            return 0
        fi
        sleep 1
    done
    return 1
}

ui_summary() {
    ui | grep -E 'text="[^"]+"' | sed -E 's/.* text="([^"]*)".*/\1/' | cut -c1-120 | head -n 20
}

# Prints the text of the first view with this resource id.
text_of() {
    ui | grep -F "resource-id=\"$1\"" | head -n1 | sed -E 's/.* text="([^"]*)".*/\1/'
}

on_screen() {
    ui | grep -qF "$1"
}

tap_bounds() {
    local b
    b="$(sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')"
    [ -n "${b}" ] || return 1
    # shellcheck disable=SC2086
    set -- ${b}
    adb shell input tap "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))"
}

# Taps the first view carrying this resource id, or fails.
tap() {
    ui | grep -F "resource-id=\"$1\"" | head -n1 | tap_bounds \
        || fail "nothing to tap with the id $1"
}

# Taps the first view with exactly this text, or fails.
tap_text() {
    ui | grep -F "text=\"$1\"" | head -n1 | tap_bounds \
        || fail "nothing to tap saying \"$1\""
}

wait_for() {
    local what="$1" seconds="$2" i
    for ((i = 0; i < seconds; i++)); do
        if on_screen "${what}"; then
            return 0
        fi
        sleep 1
    done
    fail "waited ${seconds}s for ${what}"
}

scroll_to_top() {
    local i
    for i in 1 2 3 4 5; do adb shell input swipe 360 300 360 1400 150; done
}

scroll_to_bottom() {
    local i
    for i in 1 2 3 4 5; do adb shell input swipe 360 1400 360 300 150; done
}

back_to_launcher() {
    adb shell am start -W -n "${PKG}/.LauncherActivity" > /dev/null
}

# --- A clean slate -----------------------------------------------------------

adb get-state > /dev/null || fail "no device"
adb root > /dev/null 2>&1 || true
adb wait-for-device
adb shell id | grep -q 'uid=0' || fail "this device does not allow adb root, the engine log stays out of reach"

say "installing $(basename "${APK}") on a clean slate"
adb uninstall "${PKG}" > /dev/null 2>&1 || true
# -g answers the notification permission, which otherwise stops the first download
# behind a system dialog.
adb install -g "${APK}" > /dev/null || fail "adb install refused the APK"
adb logcat -c
adb logcat -b crash -c
back_to_launcher
wait_for "resource-id=\"${ID}/installKeeperfx\"" 20

# --- Everything there is to download -----------------------------------------

# The dialog offers everything at once and the next one what that made possible, the
# release first, then the alpha patch and the music. Ends on "up to date".
download_everything() {
    local round i
    for round in 1 2 3 4 5; do
        scroll_to_top
        tap "${ID}/installKeeperfx"
        wait_for 'resource-id="android:id/button1"' 90
        if ! on_screen 'resource-id="android:id/button2"'; then
            say "nothing more to download"
            tap "android:id/button1"
            return 0
        fi
        local offered
        offered="$(text_of android:id/message | sed 's/&#10;/\n/g' | grep -F '•' | cut -c1-70)"
        say "round ${round} offers:"
        printf '%s\n' "${offered}"
        # A build older than the newest release is offered that as well, and it does
        # not belong in this test: the installer would replace the APK under test.
        if ! printf '%s\n' "${offered}" | grep -vqF 'KeeperFX app'; then
            say "only an app update left, leaving that alone"
            tap "android:id/button2"
            return 0
        fi
        tap "android:id/button1"
        for ((i = 0; i < 15; i++)); do
            if adb shell dumpsys activity services "${PKG}" | grep -q DownloadService; then
                break
            fi
            sleep 1
        done
        for ((i = 0; i < DOWNLOAD_TIMEOUT_S; i++)); do
            if ! adb shell dumpsys activity services "${PKG}" | grep -q DownloadService; then
                break
            fi
            sleep 1
        done
        [ "${i}" -lt "${DOWNLOAD_TIMEOUT_S}" ] || fail "still downloading after ${DOWNLOAD_TIMEOUT_S}s"
        sleep 2
        back_to_launcher
        sleep 2
        if on_screen "alertTitle"; then
            fail "a download ended with: $(text_of android:id/message)"
        fi
    done
    fail "still something to download after ${round} rounds"
}

download_everything

prefs() {
    adb shell cat "/data/data/${PKG}/shared_prefs/keeperfx-launcher.xml"
}
prefs | grep -q 'name="installed_version">[^<]' || fail "the release is not recorded as installed"
prefs | grep -q 'name="installed_alpha">[^<]' || fail "the alpha patch is not recorded as installed"
adb shell ls "${GAME_DIR}/music" | grep -qi '\.ogg' || fail "no music tracks arrived"
if [ "$(adb shell ls /data/data/${PKG}/files/manifests | grep -c install-in-progress)" != "0" ]; then
    fail "an install is still marked as unfinished"
fi
say "installed: $(text_of "${ID}/keeperfxStatus")"

# --- The original game, through the document picker --------------------------

say "importing the original game from ${ORIGINALS}"
adb shell "rm -rf /sdcard/Download/${PICKED_FOLDER}"
adb shell "mkdir -p /sdcard/Download/${PICKED_FOLDER}"
adb push -q "${ORIGINALS}/." "/sdcard/Download/${PICKED_FOLDER}/" > /dev/null \
    || fail "cannot copy the original game to the device"
scroll_to_top
tap "${ID}/importOriginalDk"
wait_for 'resource-id="android:id/button1"' 10
tap "android:id/button1"
wait_for 'USE THIS FOLDER' 20
# It opens wherever it was last, which after a first run may already be the folder.
for step in 1 2 3; do
    if on_screen "Files in ${PICKED_FOLDER}"; then
        break
    elif on_screen "text=\"${PICKED_FOLDER}\""; then
        tap_text "${PICKED_FOLDER}"
    else
        tap_text "Download"
    fi
    sleep 2
done
on_screen "Files in ${PICKED_FOLDER}" || fail "could not find the folder in the picker"
tap "android:id/button1"
wait_for 'text="ALLOW"' 10
tap_text "ALLOW"

for ((i = 0; i < 120; i++)); do
    sleep 1
    # The importer ends with a dialog when anything is missing, and the status line
    # says so in any case.
    if on_screen 'alertTitle'; then
        fail "the import ended with: $(text_of android:id/message | sed 's/&#10;/ /g' | cut -c1-300)"
    fi
    if on_screen "Original Dungeon Keeper files present"; then
        break
    fi
done
on_screen "Original Dungeon Keeper files present" || fail "the import did not finish"
adb shell "rm -rf /sdcard/Download/${PICKED_FOLDER}"
say "$(text_of "${ID}/originalDkStatus")"

# --- Play --------------------------------------------------------------------

# The engine options go straight into the launcher's settings, while it is not looking.
# Typing them into its field depends on the keyboard of the device.
PREFS="/data/data/${PKG}/shared_prefs/keeperfx-launcher.xml"
APP_UID="$(adb shell stat -c %u "/data/data/${PKG}" | tr -d '\r')"
adb shell am force-stop "${PKG}"
adb shell "sed -i 's#</map>#<string name=\"extra_args\">${SMOKE_ARGS}</string></map>#' ${PREFS}"
adb shell chown "${APP_UID}:${APP_UID}" "${PREFS}"
back_to_launcher
sleep 2
scroll_to_bottom
ui | grep -F "resource-id=\"${ID}/play\"" | grep -qF 'enabled="true"' || fail "Play is not enabled"
say "pressing Play"
tap "${ID}/play"

for ((i = 0; i < LEVEL_TIMEOUT_S; i++)); do
    sleep 1
    if adb shell cat "${LOG}" 2>/dev/null | grep -q 'Setting up game failed\|Basic engine initialization failed'; then
        fail "the engine gave up during setup"
    fi
    if adb shell cat "${LOG}" 2>/dev/null | grep -q 'Started level'; then
        break
    fi
    if [ -z "$(adb shell pidof "${PKG}" | tr -d '\r')" ]; then
        fail "the process died before a level started"
    fi
done
[ "${i}" -lt "${LEVEL_TIMEOUT_S}" ] || fail "no level started within ${LEVEL_TIMEOUT_S}s"
say "$(adb shell cat "${LOG}" | grep -m1 'Started level' | tr -d '\r')"

# --- Is it really playing? ---------------------------------------------------

PID="$(adb shell pidof "${PKG}" | tr -d '\r')"
sleep 15
[ "$(adb shell pidof "${PKG}" | tr -d '\r')" = "${PID}" ] || fail "the process died after the level started"
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | grep -q 'GameActivity' \
    || fail "the game is not what is on screen"
frame_a="$(adb exec-out screencap -p | md5sum)"
sleep 5
frame_b="$(adb exec-out screencap -p | md5sum)"
[ "${frame_a}" != "${frame_b}" ] || fail "the picture has not changed in 5 seconds"
if adb logcat -d -b crash | grep -q "${PKG}"; then
    fail "something crashed along the way"
fi
if adb shell cat "${LOG}" | grep -qE 'FatalError|Fatal error'; then
    fail "the engine log reports a fatal error"
fi

say "version: $(adb shell head -n 1 "${LOG}" | tr -d '\r' | cut -c1-100)"
say "passed"
