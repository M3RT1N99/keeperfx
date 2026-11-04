/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file AppVersion.java
 *     Orders the app's own version numbers.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
package net.keeperfx.android;

final class AppVersion {

    private AppVersion() {
    }

    /**
     * Orders two app versions against each other, the way "sort -V" does in the
     * workflow that refuses to publish one behind the newest release.
     *
     * A version is the port's own "major.minor" and nothing else. The major is weighted
     * so that a bump still comes out above every minor below it, and anything else
     * sorts lowest: a tag somebody made by hand must never look like an update.
     */
    static int rank(String versionName) {
        final String[] parts = versionName.trim().split("\\.", -1);
        if (parts.length != 2) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[0]) * 100000 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean isNewer(String candidate, String installed) {
        return rank(candidate) > rank(installed);
    }
}
