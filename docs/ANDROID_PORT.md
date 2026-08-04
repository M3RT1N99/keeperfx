# KeeperFX on Android

Native ARM64 Android port: an APK with a touch launcher that imports an existing
KeeperFX installation and starts the unmodified engine on top of it.

This document describes what the port does, what it deliberately leaves out, and
how to build it.

---

## Status

**Verified:** the APK builds end to end in CI. `libkeeperfx.so` is a 4.7 MB
aarch64 ELF that exports `SDL_main`; every `DT_NEEDED` entry resolves either
inside the package (SDL2, SDL2_image, SDL2_mixer, SDL2_net, OpenAL,
`libc++_shared`) or against a system library. The manifest declares both
activities, and all launcher classes plus `SDLActivity` are in `classes.dex`.
The same workflow builds the Linux x86_64 target, which stays green.

**Not verified:** the engine has never been executed on Android. Nobody has
launched this APK on a device or emulator. Expect runtime work on at least the
video mode setup, the touch gestures against the real GUI, and the data paths.
Treat the current state as "compiles, links and installs", not "plays".

## What is in the APK

| Component | Notes |
|---|---|
| `libkeeperfx.so` | The full engine, compiled for `arm64-v8a` |
| `libSDL2.so` + image/mixer/net | Built from the pinned SDL2 sources |
| `libopenal.so` | OpenAL Soft, OpenSL ES backend |
| `LauncherActivity` | Data import, verification and settings |
| `GameActivity` | `SDLActivity` subclass that starts the engine |

**No game data.** The APK contains the engine and nothing else — no KeeperFX
release files and nothing from Dungeon Keeper.

---

## Getting to a playable installation

A playable installation is two independent halves, exactly as on the desktop,
and the launcher follows the same flow as the Qt launcher:

| Half | Where it comes from | Size |
|---|---|---|
| KeeperFX release | Downloaded from `keeperfx.net/api/v1/release/stable/latest`, or imported from a folder | ~360 MB |
| Original Dungeon Keeper files | Picked out of the player's own Dungeon Keeper folder | 14 files |

**Install KeeperFX** asks the same API the desktop launcher uses, downloads the
7z release and unpacks it. Windows binaries in the archive are skipped. The
installed version is recorded, and every time the launcher opens it re-queries
the API; when a newer release exists the button turns into *Update to X*.

**Import original Dungeon Keeper** takes any folder — the game directory, its
`DATA` subfolder, a mounted CD image — and searches it for the files below,
case insensitively, then copies only those. Nothing else is taken.

```
data/    bluepal.dat  bluepall.dat  dogpal.pal   hitpall.dat  lightng.pal
         redpal.col   redpall.dat   slab0-0.dat  slab0-1.dat  vampal.pal
         whitepal.col
sound/   atmos1.sbk   atmos2.sbk    bullfrog.sbk
```

This is the list the official Qt launcher copies. `docs/files_required_from_original_dk.txt`
additionally names `main.pal` and `mapfadeg.dat`; they are copied when found but
no longer required — the engine generates the fade table itself.

**Import KeeperFX folder** stays available for anyone who already has an
installation on the device and would rather not download 360 MB.

Reinstalling or updating KeeperFX replaces the whole tree, so the original game
files have to be imported again afterwards. The launcher shows both halves
separately and says which one is missing.

---

## Controls

The port does **not** use SDL's touch-to-mouse translation. That emulation
collapses every finger into one synthetic mouse, which makes multi touch
impossible; it is switched off explicitly with `SDL_HINT_TOUCH_MOUSE_EVENTS=0`.

Instead `src/bflib_input_touch.cpp` consumes `SDL_FINGER*` events directly and
feeds the result into the same device independent layer the keyboard and the
controller already use — the pattern established by the controller support in
PR #4657.

| Gesture | Action |
|---|---|
| Tap | Left click at the touched point |
| Press and hold (~0.4 s) | Right click — slap, drop creature, cancel |
| Drag one finger | Left button held: paint a dig area or a room |
| Drag two fingers | Pan the map; the world follows the fingers |
| Pinch two fingers | Zoom in and out |
| Twist two fingers | Rotate the camera |
| Three finger tap | Toggle the map view |
| Four finger tap | Pause menu |

Positioning is **absolute**: the in-game pointer jumps to the finger through
`LbMouseSetPositionInitial()`, which does not warp the host cursor. Camera
gestures are published as axis values on `Gkey_MoveUp/Down/Left/Right`,
`Gkey_ZoomIn/ZoomOut` and `Gkey_RotateCW/RotateCCW`, so they honour the player's
own key bindings and required no changes inside `front_input.c` beyond two
additive lookups.

### Input mode

The launcher offers three modes, passed to the engine as `-inputmode`:

* **Automatic** (default) — follows the device used last. Touch the screen and
  the touch scheme takes over; move an attached mouse or press a key and it
  steps aside immediately. Until a touch screen has been used at all, the layer
  stays completely inert.
* **Touch** — always on.
* **Mouse & keyboard** — always off.

`-inputmode` also works on the desktop ports, where the default is
**Mouse & keyboard**; a Windows or Linux machine with a touch screen therefore
behaves exactly as before unless the option is passed explicitly.

---

## What the Android build leaves out

Three modules depend on libraries that are not bundled. They are replaced by
Android-only stubs, so no existing source file needed conditional compilation:

| Excluded | Replaced by | Why |
|---|---|---|
| `bflib_fmvids.cpp` | `android_stub_fmvids.cpp` | FFmpeg is not bundled; intro and outro movies are skipped |
| `net_matchmaking.c` | `android_stub_matchmaking.c` | Needs libcurl **and** OpenSSL for `wss://`; the public lobby browser is unavailable |
| `net_portforward.cpp` | `android_stub_portforward.c` | Needs miniupnpc and libnatpmp |

Multiplayer over direct IP works — enet6 is fully included. Automatic port
forwarding would help on home Wi-Fi but cannot work on mobile networks anyway,
where carrier grade NAT sits above the router.

**LuaJIT is built with `-DLUAJIT_DISABLE_JIT`.** The interpreter is API and ABI
identical, and Android's W^X policy makes writable-executable mappings
unreliable across vendors. Level scripts are not performance critical.

---

## ARM64 portability

The engine had never been compiled for a strict-alignment 64-bit target. The
portability fixes on this branch are the same set that the macOS ARM64 work
validated on real hardware:

* unaligned 16/32-bit reads in `bflib_vidraw.c` and `engine_render.c` replaced
  by `memcpy`, which is UB-free and still compiles to an unaligned load
* `get_named_field_value()` / `assign_default()` in `config.c` read and write
  every field through `memcpy`
* `check_map_for_gold()` rounds the `unsigned short` sub-array up to an even
  offset instead of starting it at an odd byte
* `fill_in_explored_area()` reads a byte instead of doing a misaligned 4-byte load
* `#pragma pack(1)` removed from structs that are never serialised, so their
  members stop being unaligned by construction
* `find_command_desc()` no longer indexes past the end of a command name

`bflib_crash.c` keeps its POSIX signal handlers but drops backtraces, because
bionic has no `<execinfo.h>`.

---

## Building

### In CI

`.github/workflows/build-android.yml` builds the APK on every push to the
branch and uploads it as an artifact. The same workflow also builds the Linux
x86_64 target as a regression guard, because the port touches shared sources.

### Locally

Requirements: JDK 17, Gradle 8.11+, Android SDK with NDK `26.3.11579264` and
CMake `3.22.1`, plus `bash`, `curl`, `git` and `make` for the dependency step.

```bash
bash android/scripts/fetch-deps.sh     # pinned dependency sources, ~15 min once
cd android
gradle assembleRelease
```

The APK lands in `android/app/build/outputs/apk/release/`.

`fetch-deps.sh` writes to `android/.deps/` and is idempotent; that directory is
what CI caches. CMake itself never touches the network.

### Signing

`android/app/keeperfx-ci.keystore` is committed on purpose. An APK has to be
signed to be installable at all, and a stable key lets CI builds be installed
over each other instead of forcing an uninstall. The key is public and grants
nothing — these builds are for sideloading, not for the Play Store.

---

## Installing and running

Requires **Android 9 (API 28)** or newer on an arm64 device. The floor is set by
`iconv`, which `bflib_text.c` uses for the double byte languages and which
bionic only gained in API 28.

1. Build or download the APK and install it (allow installation from unknown
   sources).
2. Open KeeperFX and tap **Install KeeperFX**. It downloads and unpacks the
   current release; use Wi-Fi, it is around 360 MB.
3. Put your Dungeon Keeper folder somewhere on the device — internal storage, an
   SD card, or a mounted CD image — and tap **Import original Dungeon Keeper**.
   The launcher finds and copies the 14 files it needs.
4. Both status lines turn green. Tap **Play**.

Anyone who already has a KeeperFX installation on the device can skip step 2 and
use **Import KeeperFX folder** instead.

The imported copy lives in `/data/data/net.keeperfx.android/files/keeperfx`,
which is also the engine's working directory, so saves, screenshots and
`keeperfx.log` all land there.

---

## Layout

```
android/
├── app/
│   ├── CMakeLists.txt              native build: engine + all dependencies
│   ├── keeperfx-ci.keystore        sideload signing key
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/net/keeperfx/android/
│       │   ├── LauncherActivity.java   UI, install, import, settings
│       │   ├── GameActivity.java       SDLActivity subclass
│       │   ├── ReleaseDownloader.java  release API, download, 7z unpack
│       │   ├── DataImporter.java       folder copy and original-game search
│       │   ├── GameData.java           paths and the two-half check
│       │   └── Prefs.java              settings to command line
│       └── res/
├── scripts/fetch-deps.sh           pinned dependency sources
└── gradle.properties               ABI, NDK and CMake pins

src/
├── android.cpp                     platform layer (entry point, -datadir, file find)
├── android_stub_*.{c,cpp}          replacements for the three excluded modules
├── bflib_touch.h                   touch API, SDL-free so C modules can include it
└── bflib_input_touch.cpp           gesture recognition
```

The Windows and Linux build files are untouched apart from one added source
file (`bflib_input_touch.cpp`) in `Makefile` and `linux.mk`.
