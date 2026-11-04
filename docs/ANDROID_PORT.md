# KeeperFX on Android

An unofficial, native ARM64 port: an APK with a touch launcher that installs
KeeperFX and starts the engine, built from this fork's source, on top of it. It is
not made or supported by the KeeperFX team, and every build says so in its version
string, its log and its release notes.

This document describes how the port is built and how it works, for someone
reading the code. Rules for working on it are in `AGENTS.md`.

## What is in the APK

| Library | Notes |
|---|---|
| `libkeeperfx.so` | The whole engine for `arm64-v8a`, with the libraries named below linked in statically |
| `libSDL3.so`, `libSDL3_image.so`, `libSDL3_mixer.so` | The SDL versions the desktop ports pin, from source |
| `libopenal.so` | OpenAL Soft 1.24.3, because 1.25 needs a newer libc++ than NDK r26 has |
| `libc++_shared.so` | From the NDK |

Linked in: zlib and minizip, libspng, enet6, centijson, centitoml, astronomy, curl
with mbedTLS for the lobby server's `wss://`, miniupnpc and libnatpmp, LuaJIT, and
FFmpeg cut down to the Smacker demuxer and its two decoders for the intro and the
cutscenes.

LuaJIT is built with `-DLUAJIT_DISABLE_JIT`: the interpreter is API compatible, and
Android's W^X policy makes writable-executable mappings unreliable across vendors.

The APK holds no game data except `config/`, about 2 MB, packaged as an asset (see
below). Nothing from Dungeon Keeper is in it.

## The port inside the engine

`src/android.cpp` is the entry point. `SDLActivity.nativeRunMain()` calls its `main`
on SDL's thread, it consumes `-datadir` and `chdir()`s there, sets the SDL hints, and
calls `kfxmain()`. The engine then resolves its data against the working directory as
it does everywhere. `src/android.h` declares what shared code calls on Android.
`src/kfx/platform/PlatformAndroid.h` is `PlatformLinux` under another name, since
bionic provides what that uses.

Only the software renderer exists here. The OpenGL backend speaks desktop GL through
glad and Android only has GLES, so `android/app/CMakeLists.txt` leaves it out, and
`RendererResolveType()` sends a request for it to the software renderer, which is the
normal path, since the bundled configuration asks for OpenGL.

Nothing is stubbed. That CMake file also leaves out `PlatformWindows.cpp` and
`WindowCompositorWin.cpp`, and takes everything else under `src/`.

A few things differ under `#ifdef __ANDROID__` in shared files:

- `SDL_IOFromFile` does not resolve a relative path against the working directory on
  Android, it looks at the internal storage root and in the APK's assets. What goes to
  SDL by name, music, speech and screenshots, goes through `android_absolute_path()`.
  Everything through `LbFileOpen` is plain stdio and unaffected.
- Bionic's iconv lacks the double byte code pages, so `bflib_text.c` decodes them
  through `java.lang.String` with `android_codepage_to_utf8()`, as Shift_JIS, GBK and
  EUC-KR. Bytes no table knows become U+FFFD and the game starts, as on Windows.
- The draw limit also holds in the menus, in `wait_at_frontend()`, through
  `LbSleepUntilExt()`. `LbSleepFor` and `LbSleepUntil` busy-spin any wait under 20 ms,
  and that kept a core saturated.
- `android_frame_watch()` logs one line a minute when frames were slow, as a summary
  because a line per slow frame made the next frame slow.
- `bflib_crash.c` keeps its signal handlers and drops backtraces, since bionic has no
  `<execinfo.h>`.

The launcher passes `-fps_draw 60` by default, because the engine's own default is
unlimited, which holds a core flat out in the software renderer. The extra arguments
field comes later on the command line and wins.

### Audio

Effects play through OpenAL and music through SDL3_mixer. `src/android.cpp` asks SDL
for the `openslES` audio driver, and the log says which one opened. Speech plays
through its own OpenAL source rather than a mixer track, because the mixer path
stuttered with every other layer ruled out: mp3 goes straight into
the source through dr_mp3, anything else through SDL3_mixer's decoder, and the mixer
remains the fallback.

If a language's speech file is absent, `play_description_speech()` falls back to the
first folder in the campaign's `[speech]` block. A video with no audio device plays
silently rather than failing.

### ARM64

What arm64 needs beyond the desktop builds:

- `-fsigned-char`, because plain `char` is unsigned on ARM and the engine uses -1 as a
  sentinel in `char` all over
- `-ffp-contract=off`, because arm64 clang fuses multiply and add by default and the
  x86 builds do not, which can change float results in a lockstep game
- unaligned reads through `memcpy` in `bflib_vidraw.c` and `engine_render.c`
- `get_named_field_value()` and `assign_default()` in `config.c` through `memcpy`, and
  a `long` field read at its full width on Android
- `check_map_for_gold()` starts its `unsigned short` array at an even offset, and
  `fill_in_explored_area()` reads a byte instead of a misaligned 4-byte load
- `#pragma pack(1)` is off under `__ANDROID__` in the headers whose structs hold
  pointers in static tables

## Two processes

The launcher is the app's main process. `GameActivity` and `NetworkGameService` run in
`:game` (`android:process` in the manifest), because SDL does not let a second activity
start in a process whose engine has run and a native crash takes the process with it.
So `pidof net.keeperfx.android` finds the launcher and `pidof net.keeperfx.android:game`
the engine. No static crosses between them, and `GameProcess.isAlive()` asks
`ActivityManager`. The `:game` process ends with its activity: `GameActivity` kills it
in `finish()` once the engine's main has returned, and in `onDestroy()`.

**Only Play starts a game.** The launcher's Play refreshes the configuration, then
starts `GameActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`, which
replaces any record the system kept. With the game alive it only brings it back, past
`onCreate`. `GameActivity.onCreate()` hands over to the launcher and removes its task
when it finds no arguments, a saved state, `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` or an
unfinished import. That covers a card in the task switcher, a process the system took
and a reboot.

**The refusals.** With the game open behind the launcher, anything that replaces or
deletes files it has open is refused with "The game is still open": downloads that
change the installation, the two folder imports, Reset and taking the alpha patch off,
through `refuseWhileGameOpen()`, and importing an installation, through `PhoneTransfer`.
Play is locked during a job by `setBusy()`.

**Behind a paused activity.** Alone, the engine stops: SDL blocks in its event pump and
OpenAL's device is paused. In a network game it carries on, because stopping would hold
everyone else up until the 15 s `PEER_TIMEOUT_MS`. It draws nothing, sound is muted, and
it pumps events twice a second. `GameActivity.onPause()` starts `NetworkGameService`, a
foreground service that does no work and ends itself after five minutes: on API 36 the
network of a process off screen is cut after about ten seconds and it is frozen after
about a minute.

SDL3 reads `SDL_HINT_ANDROID_BLOCK_ON_PAUSE` once when video starts, too early to know
which of the two a pause is, so `reread_block_on_pause_hint()` in
`android/scripts/fetch-deps.sh` adds one line to SDL that reads it at every pause.
`background_watch()` in `src/android.cpp` sets it from an event watch before that.

## Getting a playable installation

Two halves, as on the desktop:

| Half | Where it comes from | Size |
|---|---|---|
| KeeperFX release | `keeperfx.net/api/v1/release/stable/latest` | 374,587,275 bytes for 1.4.0 |
| Original Dungeon Keeper files | The player's own copy | 14 files |

The launcher's first button reads **Install KeeperFX** until the release is there and
**Check for downloads** afterwards. It offers everything fetchable at once, then what
that made possible:

- **The release**, a 7z that `DownloadService` fetches and `ArchiveUnpacker` unpacks
  into `files/keeperfx`, skipping Windows binaries. It unpacks over what is there, so
  saves and the imported originals stay.
- **The alpha patch**, on by default, because the engine is built from upstream's
  master and the stable release lacks what it has gained, the CJK fonts among them.
  `ReleaseApi.alphaForEngine()` asks for the patch numbered `ENGINE_BUILD`, which is the
  one that fits, and falls back to the newest, in which case the launcher says the two
  differ. `AlphaBackup` copies aside what the patch replaces, so turning the option off
  restores the plain release without a download.
- **The music**, workshop item 393, the same as the desktop launcher.
- **The app itself**, see "Versions and releases".

**Import original Dungeon Keeper** takes a folder or a zip through the system picker,
searches it case insensitively and copies only these:

```
data/    bluepal.dat  bluepall.dat  dogpal.pal   hitpall.dat  lightng.pal
         redpal.col   redpall.dat   slab0-0.dat  slab0-1.dat  vampal.pal
         whitepal.col
sound/   atmos1.sbk   atmos2.sbk    bullfrog.sbk
```

This is the list the official Qt launcher copies.

**Folder** imports a whole KeeperFX installation from a folder instead. **Reset** deletes
the installation and everything that describes it, the saves included.

`InstallManifest` records every file an unpack writes, by path and size, in
`files/manifests/`, and the check for downloads verifies it: what is missing or damaged
becomes a *Repair* item. Presence checks once called an interrupted unpack complete. The
check skips what the bundled configuration overlays.

`BundledConfig` lays the engine's own `config/` tree from the APK's assets over the
installation at every Play, because the engine and the data have different origins and
a config file the engine expects can be missing from the release. `keeperfx.cfg` is kept
on a Play, and replaced after an install, a patch or an import.

`LogActivity` shows `keeperfx.log`, which `src/logging.c` flushes per line so that it
survives a crash, and adds a header with the versions and the state of the data when
it is shared.

### Saves

Saves are `save/fx1gNNNN.sav` in the installation. Upstream replaced the old dump of
`struct Game` with the KFXS format, described in `src/kfx/save/FORMAT.txt`: chunks,
zlib, and a schema that matches fields by name and converts between widths, about a
quarter of a megabyte per save. Nothing in the format ties a save to a platform, and
loading only warns about a different version.

### Export and import

**Export installation** packs the whole installation, saves and settings included, into
one zip and hands it to the system's share sheet, which offers whatever the device has.
Whatever receives the file decides where it lands, so **Import installation** on the other
device asks the player to pick it, starting at Download. An installation is only exported
when its manifest verifies clean.

- `TransferBundle` writes and reads the file. It is a stream with no seeking, so the
  first entry, `keeperfx-bundle.properties`, declares how many entries and bytes follow.
  An imported file is untrusted: every name goes through `ArchivePaths`, only the install
  folder, the two manifests, the alpha backup and the settings can be written at all, and
  settings pass the allow-list in `Prefs.acceptTransferred()`, which keeps the extra
  arguments out. The dialog says the game runs the scripts inside.
- `TransferSwap` puts it in place, all or nothing. A file stands in as the point of no
  return: before it exists nothing live has been touched, and once it exists whoever
  finds it finishes the swap, the job or the next start.
- `PhoneTransfer` connects those two, which know nothing of Android, to content URIs and
  the share sheet.

The packed file stays in `files/exports` until it is deleted or for a day. A file from
another build can be imported, and the launcher says so.

### Languages

The launcher's language box has the 21 languages the engine knows. It decides the game's
`LANGUAGE=` and the launcher's own language, so every one of them can be read on every
phone, Latin included, which no phone offers. `GameLanguage.localized()` sets the locale in
`attachBaseContext` of the launcher's activities and of what shows notifications, and a
change recreates the screen. The folders are `values-<code>`, with `values-nb` for
Norwegian and `values-zh-rCN` and `values-zh-rTW` for the two Chinese. What a language does
not have yet falls back to English. Arabic mirrors the launcher; the game's picture does not
mirror.

Every word comes from `res/values/strings.xml`. Classes without a Context, which the JVM
tests drive, name a `Message`, a string and its arguments, and it becomes words where Android
is. Failures carry one too, as `Message.Failure`, and what the platform throws stays as the
detail of a sentence. Log lines and the preamble of a shared log stay English.

## Controls

The port does not use SDL's touch-to-mouse translation, which collapses every finger
into one synthetic mouse. `src/android.cpp` turns it off, and
`src/bflib_input_touch.cpp` takes the finger events.

| Gesture | Action |
|---|---|
| Tap | Left click at the touched point |
| Hold about 0.42 s | Right click |
| Drag one finger | Left button held: tag or paint |
| Two fingers | Hold the map: move, pinch and twist together |
| Release with speed | The map rolls on; a touch catches it |
| Two finger tap | Right click where the first finger landed |
| Three finger tap | Escape |
| System back, back arrow | Escape |

The back arrow and a right click toggle sit in the top right corner, added by
`GameActivity` and on by default; while the toggle is on a plain tap is a right click.
The back key is trapped with `SDL_HINT_ANDROID_TRAP_BACK_BUTTON` and mapped to `KC_ESCAPE`
in `bflib_inputctrl.cpp`, which also keeps a press alive until the next look at the
keyboard, since a level only looks once per game turn.

The launcher offers three modes, passed as `-inputmode`: automatic (default, follows the
device used last, and the back key does not count as using a keyboard), touch, and mouse
and keyboard. On the desktop ports the default is mouse and keyboard, which leaves the
whole layer inert.

`touch_assist_gui_pointer()` in `front_input.c` moves the pointer onto the nearest button
when a tap lands within a finger's width of one, in or around a menu, and leaves the
minimap and the play area alone. An edge of the screen only scrolls under a finger
dragging there (`touch_allows_edge_scroll()`), since a pointer stays where the last
finger left it.

The launcher's touch tuning screen and `-touchtune name=value,...` adjust `longpress`,
`dragslop`, `panslop`, `zoomgate`, `rotategate`, `flickdecay`, `looksens` and `stickradius`.

### Two fingers hold the map

`src/touch_gesture.c` is the maths, with no SDL and no engine. `src/touch_camera.c` writes
the previous and the destination pose of the local camera on every drawn frame, from
`touch_frame_update()` before the cameras are interpolated, so a finger reaches the
picture in the same frame even though the game's input runs at 20 turns a second. Position
and angle reach the dungeon camera as absolute values in every turn's packet. Zoom only
moves in flags of one step, so `touch_camera_packet_zoom()` sends flags that chase the
local zoom, and `update_local_cameras()` masks them out of its own step.

Each of the three movements opens on its own gate and they flow together, as in a map
app, and a point stays under the finger at every zoom and rotated.

The land view and the parchment map have no such camera and still go through the game
key lane, `touch_game_key_axis_value()` on `Gkey_MoveUp` and friends.

### A possessed creature

`src/touch_steer.c` holds the rules, again without SDL or engine, and `src/touch_possess.c`
the wiring and the drawing.

| Where a finger lands | What it is |
|---|---|
| The left 45% of the width beside the panel | A floating stick that walks the creature |
| Anywhere else | Turns the view by its travel, and a tap attacks |
| The fly buttons, for a creature that flies | Up and down |
| The panel, a menu, the pause box | The game's pointer |

A two finger tap is a right click, which leaves the creature, and three fingers are
Escape. The look goes straight into the packet with `touch_possess_take_look()` rather
than through the pointer, at 0.2 degrees per pixel on a screen 720 high, and what the
creature's turning limit keeps back stays owed. The ability buttons on the panel take a
tap through `gui_choose_instance()`, which sends what the number keys send.

### Ends and pauses

The key to finish a level is Space, which a touch screen does not have.

- **A won level** ends from the menu's Quit: `gui_quit_game()` sends `PckA_FinishGame` on
  Android, so the win counts. A plain quit would throw it away. No tap ends it, because
  the game goes on behind the victory line and a stray tap must not.
- **A lost level** ends from a tap on the play area, via `finish_level_requested()`, not
  on the panel, a menu or the minimap. Nothing is at stake there.
- **A paused game** goes on from a tap on the pause box at the top, which is upstream's
  `get_paused_message_inputs()`. While a creature is steered a finger on the box gets the
  pointer role, and on the box a tap is a left click whatever the right click toggle says.
  A joiner that times out pauses the game this way.

### The lobby keyboard

On a desktop the lobby holds text input open. On a phone that is the keyboard, covering
Start Game and Cancel, so on Android only a tap on the chat line raises it
(`frontnet_chat_line_tapped()`), and the line being typed is drawn at the top of the
menu while it is up. `GameActivity.watchKeyboard()` tells SDL when the keyboard's own hide
button took it away, which SDL does not hear of by itself.

## Network

### Versions

`ver_defs.h.in` takes `VER_BUILD` from `BUILD_NUMBER`, and `build.gradle` passes the
commit count at the merge base with upstream, 5591 now. `net_versions_match()` compares
all four numbers, and `check_frontend_version_mismatch()` refuses to start a level on a
difference and shows both versions.

| Other side | Result |
|---|---|
| This port, same merge base | Plays |
| An official alpha with the same number | Same source, and the same number from `git rev-list --count` |
| Official stable 1.4.0, which announces 5136 | `net_lobby.c` lets it into a phone's lobby and the level does not start. A phone will not join a stable host from the list |
| Any other official build | Refused at the join, either way round |

An upstream merge moves the number. `MATCHMAKING_VERSION` no longer exists upstream. A
lobby's metadata carries the four numbers, and the `list` request sends the whole
`VER_STRING`, suffix included. The bare `1.4.0` in the library is `net_lan.c`'s label for
a LAN host too old to announce a version.

The game is deterministic lockstep and the fork changes no wire format, checksum or
simulation.

### LAN and hotspots

A hotspot without internet leaves Wi-Fi unvalidated with cellular as the default network,
and a phone that owns a hotspot has no Wi-Fi network at all. Upstream's discovery goes to
255.255.255.255, which follows the default network. So on Android:

- `lan_service()` also sends to each subnet's own broadcast address, from
  `android_lan_broadcast_targets()`, which reads `getifaddrs` anew each time.
  `lan_subnet_broadcast()` in `src/android_lan_rules.h` is the arithmetic.
- `android_lan_bind_socket()` puts a local game's socket on the Wi-Fi network when Wi-Fi
  exists and is not the default, asking `GameActivity.lanNetworkHandle()`. An online game
  stays unbound and follows the default.
- `holepunch_stun_query()` skips STUN for a local game, because a gateway that swallows
  DNS held the game for about a minute with three lookups.
- `lan_follow_network_when_quiet()` in `bflib_enet.cpp` asks again, once a second, only
  when every connected peer has gone quiet for two seconds, or for a host alone in its
  lobby. Wi-Fi that comes back is a new network object, and a socket bound to the old one
  gets nothing but `ENETUNREACH`. One quiet peer among others that are heard is not
  enough (`lan_should_follow_network()`), or a client on a long level load could take a
  host off the hotspot its other clients are on.

`GameActivity` also holds a multicast lock while on screen.

Online play goes through the upstream lobby server. curl verifies the server against
`/system/etc/security/cacerts`, its compiled in `CURL_CA_PATH`, which may be stale on
Android 14 and later, where the trust store moved into an updatable module. miniupnpc and
libnatpmp are linked.

## Versions and releases

| Number | Example | What it is |
|---|---|---|
| `versionName` | `1.0` | The port's own, counted by first-parent commits, so a merge from upstream counts as one: the first commit after `keeperfx.portBaseCommit` is `1.<keeperfx.portBaseMinor>`, and each later one adds one |
| `versionCode` | `1045785` | From `version.mk` and the commit count of `HEAD`. Android only needs it to rise |
| `ENGINE_BUILD` | `5591` | The commit count at the merge base with upstream: the wire version, and which alpha patch fits |
| `VER_STRING` | `1.4.0.5591 Android-Unofficial_7452fa083` | What the game and the log show |

`.github/workflows/build-android.yml` runs on a push to `feature/android-support`, on a
manual run, and on a pull request made ready for review or given a reviewer:

- `build-android` runs the launcher's unit tests and the touch tests, builds the APK,
  checks that the libraries are there and that the suffix is in `libkeeperfx.so`, and
  uploads an artifact named `keeperfx-android-arm64-unofficial-<rev>`. It holds a token
  that can only read, because it runs code from a long list of third party projects.
- `build-linux-regression` builds the Linux x86_64 target, because the port touches shared
  sources.
- `publish` `needs` both and runs for a push or a manual run. It refuses a version behind
  the newest release, then creates `android-v<version>` with `keeperfx-android-arm64.apk`.
  The notes say that it is unofficial, what it is, what it can do and how to install it,
  and list the changes when there is an earlier release to compare with. The asset's name
  and the form of the tag must not change, and the heading of the changes is what the
  updater reads them by.

`AppUpdater` asks GitHub's `/releases/latest` and `AppRelease` looks for that asset. On
Android 11 granting the install permission ends every process of the app, so the launcher
holds that offer back until no other download is running. That comes from the platform's
source.

`android/app/keeperfx-ci.keystore` is committed so that CI builds install over each other.
That means anyone can sign an APK that Android accepts as an update, so the updater's real
trust is its TLS connection to GitHub. These builds are for sideloading, not for a store.

## Building

Requirements: JDK 17, Gradle 8.11+, Android SDK with NDK `26.3.11579264` and CMake
`3.22.1`, plus `bash`, `curl`, `git`, `tar`, `make` and a host C compiler for LuaJIT.

```bash
bash android/scripts/fetch-deps.sh
cd android
gradle assembleRelease \
  -Pkeeperfx.buildNumber="$(git rev-list --count HEAD)" \
  -Pkeeperfx.engineBuild="$(git rev-list --count "$(git merge-base HEAD upstream/master)")" \
  -Pkeeperfx.gitRevision="$(git describe --always)"
```

This needs the `upstream` remote. The APK lands in
`android/app/build/outputs/apk/release/`. Without those properties `VER_BUILD` is 0 and
`versionCode` too low to update an installed build.

`fetch-deps.sh` writes to `android/.deps/` and CMake itself never touches the network.
Every tarball is pinned to a SHA-256 and every git source to a commit, and a mismatch stops
the script. The SHA-256 values were taken from the downloads themselves, so they prove the
sources have not changed since, not that they were right. Gradle and the Actions are pinned
by version or tag only. The script also applies the one-line SDL patch above and stops if
SDL's source no longer looks as expected.

Delete `src/ver_defs.h` if a desktop build has run in the same tree: quoted includes find it
before the generated one, and the APK would report the desktop's version.

`NewApi` is fatal in lint, which `assembleRelease` runs. `android/app/lint.xml` ignores the
one hit in SDL's sources, an API 29 call that SDL only reaches on API 29.

### Tests

| What | Where | Run with |
|---|---|---|
| Gesture maths, steering rules, LAN rules | `tests/touch`, no device, no engine, no SDL | `bash tests/touch/build.sh out && ./out` |
| The launcher's decisions: update plan, archives, resumable download, manifest, transfer, what its dialogs say, every language's strings against the English | `android/app/src/test` | `cd android && gradle testReleaseUnitTest` |
| A fresh install played through | `android/scripts/emulator-smoke.sh <apk> <folder of the original game>` | A device that allows `adb root`, in practice an emulator |

The smoke test installs on a clean slate, drives the launcher through the release, the
patch, the music and the import with plain adb, presses Play, and checks that a level
starts, keeps running and changes its picture, with nothing in the crash buffer.

## Installing and running

Requires Android 9 (API 28) or newer on an arm64 device. Lint guards that floor.

1. Install the APK, allowing unknown sources. Play Protect warns about an unknown developer.
2. Open it and tap **Install KeeperFX**.
3. Tap **Import original Dungeon Keeper** and pick your Dungeon Keeper folder.
4. Tap **Play**.

The installation lives in `/data/data/net.keeperfx.android/files/keeperfx`, which is also the
engine's working directory, so saves, screenshots and `keeperfx.log` land there. That is
private storage, and a screenshot only comes from a key: no gesture takes one.

## Layout

```
android/
├── app/
│   ├── CMakeLists.txt              native build: engine and all dependencies
│   ├── build.gradle                versions, lint, what gets packaged
│   ├── lint.xml                    the one ignored NewApi hit
│   ├── keeperfx-ci.keystore        sideload signing key
│   └── src/
│       ├── main/java/net/keeperfx/android/
│       │   ├── LauncherActivity        the launcher's screen and its dialogs
│       │   ├── GameActivity            SDLActivity subclass, in :game
│       │   ├── GameProcess             whether :game is alive
│       │   ├── NetworkGameService      keeps a network game alive off screen, in :game
│       │   ├── DownloadService         downloads, patches and transfers, in the foreground
│       │   ├── UpdateManager           what can be fetched, and the plan for it
│       │   ├── ReleaseApi, ReleaseDownloader, ResumableDownload, Http
│       │   ├── ArchiveUnpacker, ArchivePaths, FileUtil
│       │   ├── InstallManifest, AlphaBackup, BundledConfig, GameData, GameLanguage
│       │   ├── DataImporter            folder imports and the search for the originals
│       │   ├── TransferBundle, TransferSwap, PhoneTransfer   export and import
│       │   ├── Message                 a text for the player, named rather than written out
│       │   ├── AppUpdater, AppRelease, AppVersion
│       │   ├── LogActivity, TouchTuningActivity
│       │   └── Prefs                   settings, and the engine's command line
│       └── test/                   the launcher's unit tests
├── gradle.properties               ABI, NDK and CMake pins, the port version's base
└── scripts/
    ├── fetch-deps.sh               pinned dependency sources, and the SDL patch
    └── emulator-smoke.sh           a fresh install played through

src/
├── android.cpp, android.h          entry point and what shared code calls on Android
├── android_lan_rules.h             the arithmetic of finding a local game
├── kfx/platform/PlatformAndroid.h
├── bflib_touch.h                   touch API, SDL-free so C modules can include it
├── bflib_input_touch.cpp           finger events, tap and long press, the game key lane
├── touch_gesture.[ch]              the maths of holding the map
├── touch_camera.[ch]               what that does to the cameras
├── touch_steer.[ch]                the rules of steering a creature
└── touch_possess.[ch]              what those do to the game

tests/touch/                        host tests of the pure modules
```

`android.cpp` is compiled out everywhere but Android. The `touch_*` modules build on every
platform, since the CMake build collects `src/` by glob, and stay inert unless the player
passes `-inputmode`.
