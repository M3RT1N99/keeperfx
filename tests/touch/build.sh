#!/bin/bash
# Build the touch tests with any C compiler.
#
#   build.sh <output file>
#
# Run from anywhere. Environment:
#   CC             C compiler (default gcc)
#   CFLAGS_EXTRA   more compile flags, e.g. -fsanitize=address,undefined
#
# These modules include neither SDL nor the engine, so nothing else is needed.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
# shellcheck disable=SC2086
"${CC:-gcc}" -std=gnu11 -O1 -g -Wall -Wextra -Werror ${CFLAGS_EXTRA:-} -I"$root/src" \
    "$here/touch_gesture_tests.c" "$here/touch_steer_tests.c" "$here/android_lan_rules_tests.c" \
    "$root/src/touch_gesture.c" "$root/src/touch_steer.c" -lm -o "$1"
