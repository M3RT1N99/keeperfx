# AGENTS.md

Instructions for AI coding agents working in this repository.

**This is a fork of [dkfans/keeperfx](https://github.com/dkfans/keeperfx), not
the official project.** Everything below follows from that.

## Builds from this repository must say they are unofficial

`version.mk` sets `PACKAGE_SUFFIX=Unofficial`. Upstream leaves it empty, which
is exactly what an official release build uses, so without a suffix nothing
distinguishes a package built here from one the KeeperFX team made — not the
version string the game shows, not the log header, not a screenshot attached to
a bug report.

Keep it set. If you add a build system or a workflow, make sure the suffix
reaches the version string there too:

- The **Makefile** reads `version.mk` directly.
- The **root `CMakeLists.txt`** takes `version.mk`'s value as the default for
  the `PACKAGE_SUFFIX` cache variable. A `-DPACKAGE_SUFFIX=…` on the command
  line still wins, which is how the upstream release workflows keep producing
  unsuffixed packages.
- The **Android build** composes `Android-Unofficial`: `android/app/build.gradle`
  deliberately passes an empty `-DPACKAGE_SUFFIX` so that
  `android/app/CMakeLists.txt` can combine `version.mk`'s value with the
  platform name.
- The **Android CI workflow** passes `-Pkeeperfx.packageSuffix` explicitly, to
  get the commit into the version string, and that overrides the composition
  above. It therefore reads `version.mk` itself and produces
  `Android-Unofficial_<sha>`. An APK built by CI once carried a plain
  `Android_<sha>` for exactly this reason, so the workflow now refuses an APK
  whose library lacks the suffix. Check the built artifact, not the source, when
  changing any of this:

  ```bash
  unzip -p app.apk lib/arm64-v8a/libkeeperfx.so | strings | grep -E '^1\.4\.'
  ```

  That prints the version with its suffix and also a bare `1.4.0`. The bare one
  is `src/net_lan.c`'s label for a LAN host too old to announce a version, not
  this build's version.

Anything published from here — GitHub releases, artifacts, forum posts — says
"unofficial" in its title and its notes for the same reason.

One deliberate exception: what other clients compare carries no suffix.
`net_current_version` in `src/net_main.h`, the `version` in a lobby's metadata
(`src/net_lobby.c`) and in the punch request (`src/net_matchmaking.c`) are built
from `VER_MAJOR`, `VER_MINOR`, `VER_RELEASE` and `VER_BUILD` only. Do not "fix"
that. Only the `list` request in `src/net_matchmaking.c` sends the whole
`VER_STRING`, which is upstream's own line.

## Do not send this fork's changes upstream on your own

Open pull requests against `dkfans/keeperfx` only when the repository owner
asks for it in that specific instance. Reporting a bug, replying to a
maintainer, or pushing a branch to this fork is not the same thing as proposing
a change to their project.

## Keep the port additive

The Android work lives alongside the existing ports rather than replacing
anything:

- New platform code goes in new files: `src/android.cpp`, `src/android.h`,
  `src/android_lan_rules.h`, `src/kfx/platform/PlatformAndroid.h`, the
  `src/touch_*` modules, `src/bflib_input_touch.cpp`.
- A change to a shared file stays inside `#ifdef __ANDROID__`, or it is a hook or
  a portability fix that changes nothing on the desktop, such as the `touch_*`
  calls in `src/front_input.c`, which do nothing while the touch layer is in its
  desktop default.
- Nothing is stubbed. Every library the engine needs is built from source for
  Android, FFmpeg included, cut down to the Smacker video. `android/app/CMakeLists.txt`
  leaves out only the Windows platform files and the OpenGL backend, and
  `RendererResolveType()` sends a request for OpenGL to the software renderer.
- A change to shared engine code has to leave Windows and Linux behaving
  exactly as before. The CI workflow builds the Linux x86_64 target for
  precisely this reason, and a failure there stops the release.

## Multiplayer stays compatible

Lockstep peers have to compute the same thing, so this fork makes no change to
the wire format, the checksums or the simulation.

- `src/bflib_enet.cpp`, `src/net_holepunch.c` and `src/net_lan.[ch]` have small
  additions under `#ifdef __ANDROID__`, for finding and reaching a game on a local
  network and nothing else. Keep it that way, and look at the diff again after every
  merge from upstream.
- `-fsigned-char` and `-ffp-contract=off` on the `keeperfx` target give arm64 the
  semantics of the x86 builds. Do not remove them.
- `VER_BUILD` goes on the wire and has to be the number an official client built
  from the same source announces. On Android it is the commit count at the merge
  base with `dkfans/keeperfx`, `keeperfx.engineBuild` in Gradle and
  `BuildConfig.ENGINE_BUILD` in the launcher, never this fork's own count.
  `net_versions_match()` compares all four numbers, so a phone can only start a
  game with builds of that same number, and an upstream merge moves it.

## Releases reach installed apps

`.github/workflows/build-android.yml` publishes a release from every push to
`feature/android-support` and from every manual run. Its `publish` job needs both
the APK and the Linux regression job, and installed apps offer what it publishes,
so do not push to that branch or start the workflow unless the owner asks. Work
happens on other branches.

- Installed updaters look for the release asset `keeperfx-android-arm64.apk` behind
  tags named `android-v<version>`. Do not rename either.
- The app's version is `1.<minor>`, counted by first-parent commits: the first commit
  after `keeperfx.portBaseCommit` is `1.<keeperfx.portBaseMinor>`, and each later one
  adds one (`android/gradle.properties`). So a release commit that names its own parent
  as the base carries exactly the minor it writes, and anything merged into
  `feature/android-support` must fast-forward it or keep the incoming branch as first
  parent, or the count comes out wrong. The job refuses a version behind the newest
  release, by `sort -V`, and `AppVersion` has to agree with that. `versionCode` counts
  every commit of `HEAD` instead, because it has to keep rising over everything ever
  installed.
- The notes start with the paragraph saying this is unofficial, which the KeeperFX team
  asked for, and the title says "(unofficial)". They carry no test matrix.

## Rules of the Android code

- The engine has a process of its own. `GameActivity` and `NetworkGameService` run
  in `:game`, everything else in the launcher's process, and no static crosses
  between them. `pidof net.keeperfx.android` finds the launcher,
  `pidof net.keeperfx.android:game` the engine, and `GameProcess.isAlive()` is how
  the launcher asks.
- Only Play starts a game. `GameActivity.onCreate()` sends a start from a task
  switcher card, a saved state or a restart to the launcher.
- Whatever replaces or deletes installation files asks `GameProcess.isAlive()` first,
  in the launcher through `refuseWhileGameOpen()`, because the engine has them open.
- `NewApi` is fatal in lint, because `minSdk` is 28 and only API 36 gets run here.
  A hit in our code gets a version check. A hit in third party code gets read: if
  its guard is really there, `android/app/lint.xml` ignores that one method with
  the reason next to it, and nothing wider.
- Everything `android/scripts/fetch-deps.sh` downloads is pinned to a checksum or a
  commit. To move a pin, change it, check the value the failure prints against what
  the project publishes, and put it next to the pin. The script's one-line SDL patch
  stops the script if SDL's source no longer looks as it expects.
- Every word a player reads comes from `res/values/strings.xml`, whole sentences with
  numbered placeholders. A class without a Context names a `Message`, and it becomes words
  where Android is, through the activity's or the service's own context: the application's
  does not have the chosen language. Log lines and the preamble of a shared log stay
  English. `StringResourcesTest` holds every `values-*` folder against the English.
- The language box decides the launcher's language as well as the game's, through
  `GameLanguage.localized()` in `attachBaseContext`. The game's own window keeps the phone's
  configuration, and does not mirror: its overlay asks for the right, not the end.
- What decides something goes in code a host can test. `src/touch_gesture.c`,
  `src/touch_steer.c`, `src/touch_hand.c` and `src/android_lan_rules.h` include neither SDL nor the
  engine and are pinned by `tests/touch`, and the launcher's decisions by the JVM
  tests under `android/app/src/test`.
- `Prefs.TOUCH_TUNABLES` mirrors the table in `touch_tune()`: same names, defaults
  and ranges, or the engine ignores a value.
- A received bundle or a downloaded archive is untrusted. Every name goes through
  `ArchivePaths`, and settings through the allow-list in `Prefs.acceptTransferred()`,
  which keeps the extra arguments out.

## Do not claim what did not happen

Never claim a test that did not happen. A commit message says "by reading" when nothing
ran, and says what ran when something did. What has been measured, and what has not, is
kept in `AGENTS.local.md`. Release notes and everything else a player reads carry no test
matrix, and neither do the public documents.

## Conventions

- Commit messages are plain English prose. No bullet lists, no scope prefixes
  beyond the occasional `android:`, and they explain **why** rather than
  restating the diff.
- Comments explain why, in the style of the surrounding code. No decorative
  banners, no commented-out code.
- Merge `upstream/master` regularly rather than letting the fork drift:
  `git fetch upstream master && git merge upstream/master`.

## Building

The desktop builds are upstream's; see `docs/` and `build-cmake.sh`. For Android,
with JDK 17, Gradle 8.11+, NDK 26.3.11579264 and CMake 3.22.1:

```bash
bash android/scripts/fetch-deps.sh     # once, downloads pinned sources
cd android && gradle assembleRelease \
  -Pkeeperfx.buildNumber="$(git rev-list --count HEAD)" \
  -Pkeeperfx.engineBuild="$(git rev-list --count "$(git merge-base HEAD upstream/master)")" \
  -Pkeeperfx.gitRevision="$(git describe --always)"
```

Without those properties `VER_BUILD` is 0 and the APK cannot update an installed
one, which is enough for a compile check and nothing else. If a desktop build has
run in the same tree, delete `src/ver_defs.h` first: sources find it before the
Android one, and the APK would report the desktop's version and suffix.

```bash
bash tests/touch/build.sh /tmp/kfx_touch_tests && /tmp/kfx_touch_tests
cd android && gradle testReleaseUnitTest
bash android/scripts/emulator-smoke.sh <apk> <folder of the original game>
```

`.github/workflows/build-android.yml` runs the first two, builds the APK and the
Linux x86_64 target, and publishes. See `docs/ANDROID_PORT.md` for how the port
works.

## Private notes

Session-to-session working notes live in `AGENTS.local.md`, which is untracked
on purpose — it holds device logs, measured facts and things that are only
interesting mid-task. Read it if it is there; do not commit it.
