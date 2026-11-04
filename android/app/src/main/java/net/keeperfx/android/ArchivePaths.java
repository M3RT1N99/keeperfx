/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file ArchivePaths.java
 *     Decides where a name out of a downloaded archive may end up.
 * @par Purpose:
 *     The archives come from a server and their entry names reach file operations
 *     in the app's private storage, next to its settings. Every path that started
 *     as an entry name goes through here, also the ones read back from a list
 *     written earlier.
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

import java.io.File;
import java.io.IOException;

final class ArchivePaths {

    private ArchivePaths() {
    }

    /**
     * The path an entry may be unpacked to, relative to the installation, or null
     * when it may not be unpacked at all: empty, absolute, or climbing with "..".
     * The official archives are built on Windows, so a backslash is a separator.
     */
    static String relativePath(String entryName) {
        if (entryName == null) {
            return null;
        }
        final String name = entryName.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            return null;
        }
        final StringBuilder path = new StringBuilder();
        for (String part : name.split("/")) {
            if (part.equals("..")) {
                return null;
            }
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(part);
        }
        return (path.length() > 0) ? path.toString() : null;
    }

    /**
     * The file a name stands for under root, or null when it may not be touched.
     * The canonical comparison is what catches a symbolic link on the way out,
     * which no look at the name alone can see.
     */
    static File resolve(File root, String entryName) throws IOException {
        final String relative = relativePath(entryName);
        if (relative == null) {
            return null;
        }
        final File file = new File(root, relative);
        final String canonicalRoot = root.getCanonicalPath() + File.separator;
        return file.getCanonicalPath().startsWith(canonicalRoot) ? file : null;
    }
}
