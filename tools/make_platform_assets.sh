#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$ROOT/pkg}"

test -d "$DEST" || {
    echo "error: generated package directory not found at $DEST" >&2
    exit 1
}

# Generated graphics and translations are already in DEST. Add all complete
# KeeperFX-owned campaigns, maps, multiplayer content and configuration.
for source in campgns levels multiplayer; do
    mkdir -p "$DEST/$source"
    cp -R "$ROOT/$source"/. "$DEST/$source"/
done
for source in creatrs fxdata mods; do
    mkdir -p "$DEST/$source"
    cp -R "$ROOT/config/$source"/. "$DEST/$source"/
done

cp "$ROOT/docs/keeperfx_readme.txt" "$DEST/"
cp "$ROOT/docs/launcher-auto-file-removal.txt" "$DEST/"

# Platform packages add their own executable and runtime libraries later.
find "$DEST" -maxdepth 1 -type f \( \
    -iname '*.exe' -o \
    -iname '*.dll' -o \
    -iname '*.map' -o \
    -iname '*.pdb' -o \
    -iname '*.7z' \
    \) -delete

# Never allow copyrighted files from the original Dungeon Keeper installation
# into a generated KeeperFX asset artifact.
while IFS= read -r required; do
    case "$required" in
        ./*)
            required="${required#./}"
            required_dir="$DEST/$(dirname "$required")"
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

test -d "$DEST/campgns"
test -d "$DEST/data"
test -d "$DEST/fxdata"
test -d "$DEST/levels"
test -d "$DEST/multiplayer"
test -f "$DEST/data/creature.jty"
test -f "$DEST/fxdata/sounds.cfg"

echo "Platform-neutral KeeperFX assets ready at $DEST"
