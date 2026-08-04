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

Anything published from here — GitHub releases, artifacts, forum posts — says
"unofficial" in its title and its notes for the same reason.

One deliberate exception: `MATCHMAKING_VERSION` in `src/net_matchmaking.c` is
built from `VER_MAJOR`/`VER_MINOR`/`VER_RELEASE` only and does **not** carry the
suffix. That is what keeps the online lobby browser compatible with official
clients. Do not "fix" it.

## Do not send this fork's changes upstream on your own

Open pull requests against `dkfans/keeperfx` only when the repository owner
asks for it in that specific instance. Reporting a bug, replying to a
maintainer, or pushing a branch to this fork is not the same thing as proposing
a change to their project.

## Keep the port additive

The Android work lives alongside the existing ports rather than replacing
anything:

- New platform code goes in new files (`src/android.cpp`,
  `src/bflib_input_touch.cpp`), guarded by `#ifdef __ANDROID__` where it sits in
  a shared file.
- Modules that need a library the port cannot bundle are **excluded in
  `android/app/CMakeLists.txt`** and replaced by a stub next to them, so no
  existing source file grows a platform branch.
- A change to shared engine code has to leave Windows and Linux behaving
  exactly as before. The CI workflow builds the Linux x86_64 target for
  precisely this reason — treat a regression there as a blocker.

## Conventions

- Commit messages are plain English prose. No bullet lists, no scope prefixes
  beyond the occasional `android:`, and they explain **why** rather than
  restating the diff.
- Comments explain why, in the style of the surrounding code. No decorative
  banners, no commented-out code.
- Merge `upstream/master` regularly rather than letting the fork drift:
  `git fetch upstream master && git merge upstream/master`.

## Building

The desktop builds are upstream's; see `docs/` and `build-cmake.sh`. For
Android:

```bash
bash android/scripts/fetch-deps.sh     # once, downloads pinned sources
cd android && gradle assembleRelease   # JDK 17, Gradle 8.11+, NDK 26.3.11579264
```

`.github/workflows/build-android.yml` does both in CI and publishes the APK.

## Private notes

Session-to-session working notes live in `AGENTS.local.md`, which is untracked
on purpose — it holds device logs, measured facts and things that are only
interesting mid-task. Read it if it is there; do not commit it.
