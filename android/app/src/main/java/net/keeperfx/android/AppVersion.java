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
     * Orders two app versions against each other.
     *
     * A version is the port's own "major.minor" and nothing else - which
     * KeeperFX the engine is has its own place and moves for entirely separate
     * reasons. The major is weighted so that a bump still comes out above every
     * minor below it. Anything that does not parse sorts lowest, which is what
     * the versions from before this scheme did.
     */
    static int rank(String versionName) {
        try {
            final String port = versionName.trim();
            final int dot = port.indexOf('.');
            if (dot < 0) {
                return Integer.parseInt(port) * 100000;
            }
            final int major = Integer.parseInt(port.substring(0, dot).trim());
            final int minor = Integer.parseInt(port.substring(dot + 1).trim());
            return major * 100000 + minor;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean isNewer(String candidate, String installed) {
        return rank(candidate) > rank(installed);
    }
}
