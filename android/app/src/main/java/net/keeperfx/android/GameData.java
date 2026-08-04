/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file GameData.java
 *     Location and verification of the imported KeeperFX game folder.
 * @par Purpose:
 *     The APK ships no game data at all. Both the KeeperFX release files and
 *     the files that have to come from an original Dungeon Keeper CD are
 *     imported by the player into the app's private storage; this class knows
 *     where that is and what a complete installation looks like.
 * @author   KeeperFX Team
 * @date     04 Aug 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
package net.keeperfx.android;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GameData {

    private GameData() {
    }

    /** Directories every KeeperFX installation has. */
    private static final String[] REQUIRED_DIRECTORIES = {
        "data",
        "sound",
        "fxdata",
        "creatrs",
        "campgns",
        "levels",
    };

    /**
     * Files that must be copied from an original Dungeon Keeper CD, taken from
     * docs/files_required_from_original_dk.txt.
     */
    private static final String[] REQUIRED_ORIGINAL_FILES = {
        "data/bluepal.dat",
        "data/bluepall.dat",
        "data/dogpal.pal",
        "data/hitpall.dat",
        "data/lightng.pal",
        "data/main.pal",
        "data/mapfadeg.dat",
        "data/redpal.col",
        "data/redpall.dat",
        "data/slab0-0.dat",
        "data/slab0-1.dat",
        "data/vampal.pal",
        "data/whitepal.col",
        "sound/atmos1.sbk",
        "sound/atmos2.sbk",
        "sound/bullfrog.sbk",
    };

    /** Files shipped by the KeeperFX release itself. */
    private static final String[] REQUIRED_KEEPERFX_FILES = {
        "keeperfx.cfg",
        "campgns/keeporig.cfg",
    };

    /** Root of the imported installation; also the engine's working directory. */
    public static File gameDirectory(Context context) {
        return new File(context.getFilesDir(), "keeperfx");
    }

    /**
     * Checks the installation.
     *
     * @return the list of missing entries; empty when the installation is complete.
     */
    public static List<String> findMissingEntries(Context context) {
        final File root = gameDirectory(context);
        final List<String> missing = new ArrayList<>();
        if (!root.isDirectory()) {
            missing.add("(no game folder imported yet)");
            return missing;
        }
        for (String dir : REQUIRED_DIRECTORIES) {
            if (!resolveIgnoringCase(root, dir).isDirectory()) {
                missing.add(dir + "/");
            }
        }
        for (String file : REQUIRED_KEEPERFX_FILES) {
            if (!resolveIgnoringCase(root, file).isFile()) {
                missing.add(file);
            }
        }
        for (String file : REQUIRED_ORIGINAL_FILES) {
            if (!resolveIgnoringCase(root, file).isFile()) {
                missing.add(file + "  (from the original Dungeon Keeper CD)");
            }
        }
        return missing;
    }

    public static boolean isComplete(Context context) {
        return findMissingEntries(context).isEmpty();
    }

    /**
     * Resolves a relative path, retrying case insensitively.
     *
     * The engine opens data files with the exact casing of the DOS originals,
     * but the folders players import are often all lower or all upper case.
     * The importer normalises names on the way in; this keeps verification in
     * step with whatever actually landed on disk.
     */
    private static File resolveIgnoringCase(File root, String relative) {
        File current = root;
        for (String part : relative.split("/")) {
            File direct = new File(current, part);
            if (direct.exists()) {
                current = direct;
                continue;
            }
            File found = null;
            String[] entries = current.list();
            if (entries != null) {
                for (String entry : entries) {
                    if (entry.equalsIgnoreCase(part)) {
                        found = new File(current, entry);
                        break;
                    }
                }
            }
            if (found == null) {
                return direct; // does not exist; the caller reports it as missing
            }
            current = found;
        }
        return current;
    }

    /** Human readable size of the imported folder, for the launcher status line. */
    public static String describeSize(File dir) {
        long bytes = directorySize(dir);
        if (bytes <= 0) {
            return "empty";
        }
        final String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        double value = bytes;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private static long directorySize(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        long total = 0;
        for (File child : children) {
            total += child.isDirectory() ? directorySize(child) : child.length();
        }
        return total;
    }
}
