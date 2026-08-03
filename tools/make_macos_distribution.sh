#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/dist/KeeperFX.app"
ASSETS="${1:-$ROOT/macos-assets}"
DIST="$ROOT/dist/KeeperFX-macOS-arm64"
ARCHIVE="$ROOT/dist/KeeperFX-macOS-arm64-complete.zip"

test -d "$APP" || {
    echo "error: build the app first with tools/make_macos_app.sh" >&2
    exit 1
}
test -d "$ASSETS" || {
    echo "error: platform assets not found at $ASSETS" >&2
    exit 1
}

rm -rf "$DIST" "$ARCHIVE"
mkdir -p "$DIST"
cp -R "$APP" "$DIST/KeeperFX.app"
cp -R "$ASSETS"/. "$DIST"/

# These belong to the Windows package and must never leak into the macOS build.
find "$DIST" -maxdepth 1 -type f \( \
    -iname '*.exe' -o \
    -iname '*.dll' -o \
    -iname '*.map' -o \
    -iname '*.pdb' -o \
    -iname '*.7z' \
    \) -delete

cp "$ROOT/docs/files_required_from_original_dk.txt" "$DIST/"
cp "$ROOT/docs/keeperfx_readme.txt" "$DIST/"
cp "$ROOT/docs/MACOS_ARM64_PORT.md" "$DIST/"

# Original Dungeon Keeper assets are copyrighted and must be supplied by the
# user from a legitimate installation. Fail if the asset job ever includes one.
while IFS= read -r required; do
    case "$required" in
        ./*)
            required="${required#./}"
            required_dir="$DIST/$(dirname "$required")"
            required_name="$(basename "$required")"
            if test -d "$required_dir" &&
                find "$required_dir" -maxdepth 1 -type f -iname "$required_name" -print -quit |
                    grep -q .
            then
                echo "error: original Dungeon Keeper file must not be distributed: $required" >&2
                exit 1
            fi
            ;;
    esac
done < "$ROOT/docs/files_required_from_original_dk.txt"

test -d "$DIST/campgns"
test -d "$DIST/data"
test -d "$DIST/fxdata"
test -d "$DIST/levels"
test -d "$DIST/multiplayer"
test -f "$DIST/fxdata/sounds.cfg"
test -f "$DIST/data/creature.jty"

ditto -c -k --keepParent "$DIST" "$ARCHIVE"
echo "Created $ARCHIVE"
