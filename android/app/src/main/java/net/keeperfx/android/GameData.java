/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file GameData.java
 *     Location and verification of the installed game data.
 * @par Purpose:
 *     A playable installation is made of two independent halves, exactly as on
 *     the desktop: the KeeperFX release, and the handful of files that have to
 *     come from an original Dungeon Keeper. This class knows where they live
 *     and can report on each half separately, so the launcher can guide the
 *     player to whichever one is still missing.
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class GameData {

    private GameData() {
    }

    /** Directories and files that only the KeeperFX release provides. */
    private static final String[] KEEPERFX_DIRECTORIES = {
        "data",
        "fxdata",
        "creatrs",
        "campgns",
        "levels",
        "ldata",
    };

    /**
     * Content the engine asks for by name, so an install that is merely present
     * is told apart from one that is usable.
     *
     * The four videos are the intro sequence; without them the game opens on a
     * black screen and the log is the only clue. sounds.cfg and the fonts came
     * after the 1.4.0 release, so their absence also means the alpha patch has
     * not been laid over it.
     */
    private static final String[] KEEPERFX_CONTENT_FILES = {
        "ldata/intromix.smk",
        "ldata/bullfrog.smk",
        "fxdata/sounds.cfg",
        "fxdata/font12.fxfont",
    };

    private static final String[] KEEPERFX_FILES = {
        "keeperfx.cfg",
        "campgns/keeporig.cfg",
    };

    /**
     * Files that must come from an original Dungeon Keeper, mirroring the list
     * the official Qt launcher copies. The KeeperFX release ships a data/ and a
     * sound/ directory but none of these.
     */
    public static final String[] ORIGINAL_DK_DATA_FILES = {
        "bluepal.dat",
        "bluepall.dat",
        "dogpal.pal",
        "hitpall.dat",
        "lightng.pal",
        "redpal.col",
        "redpall.dat",
        "slab0-0.dat",
        "slab0-1.dat",
        "vampal.pal",
        "whitepal.col",
    };

    public static final String[] ORIGINAL_DK_SOUND_FILES = {
        "atmos1.sbk",
        "atmos2.sbk",
        "bullfrog.sbk",
    };

    /**
     * Copied when present but never required. docs/files_required_from_original_dk.txt
     * still lists them; the engine generates or no longer needs them.
     */
    public static final String[] ORIGINAL_DK_OPTIONAL_FILES = {
        "main.pal",
        "mapfadeg.dat",
    };

    /** Root of the installation; also the engine's working directory. */
    public static File gameDirectory(Context context) {
        return new File(context.getFilesDir(), "keeperfx");
    }

    // ------------------------------------------------------------ KeeperFX half

    /** Entries of the KeeperFX release that are still missing. */
    public static List<String> findMissingKeeperfxEntries(Context context) {
        final File root = gameDirectory(context);
        final List<String> missing = new ArrayList<>();
        if (!root.isDirectory()) {
            missing.add("(nothing installed yet)");
            return missing;
        }
        for (String dir : KEEPERFX_DIRECTORIES) {
            final File d = resolveIgnoringCase(root, dir);
            if (!d.isDirectory()) {
                missing.add(dir + "/");
            } else {
                // Existing but empty counts as missing. A half finished install
                // used to pass this check and then surface as silence and black
                // screens, with the launcher insisting the data was complete.
                final String[] entries = d.list();
                if (entries == null || entries.length == 0) {
                    missing.add(dir + "/ (empty)");
                }
            }
        }
        for (String file : KEEPERFX_CONTENT_FILES) {
            if (!resolveIgnoringCase(root, file).isFile()) {
                missing.add(file);
            }
        }
        for (String file : KEEPERFX_FILES) {
            if (!resolveIgnoringCase(root, file).isFile()) {
                missing.add(file);
            }
        }
        return missing;
    }

    public static boolean isKeeperfxInstalled(Context context) {
        return findMissingKeeperfxEntries(context).isEmpty();
    }

    // --------------------------------------------------------- original DK half

    /** Files from the original game that are still missing, as relative paths. */
    public static List<String> findMissingOriginalDkFiles(Context context) {
        final File root = gameDirectory(context);
        final List<String> missing = new ArrayList<>();
        for (String name : ORIGINAL_DK_DATA_FILES) {
            if (!resolveIgnoringCase(root, "data/" + name).isFile()) {
                missing.add("data/" + name);
            }
        }
        for (String name : ORIGINAL_DK_SOUND_FILES) {
            if (!resolveIgnoringCase(root, "sound/" + name).isFile()) {
                missing.add("sound/" + name);
            }
        }
        return missing;
    }

    public static boolean hasOriginalDkFiles(Context context) {
        return findMissingOriginalDkFiles(context).isEmpty();
    }

    /** Every original file name the importer should look for, required or not. */
    public static List<String> allOriginalDkFileNames() {
        final List<String> names = new ArrayList<>();
        names.addAll(Arrays.asList(ORIGINAL_DK_DATA_FILES));
        names.addAll(Arrays.asList(ORIGINAL_DK_SOUND_FILES));
        names.addAll(Arrays.asList(ORIGINAL_DK_OPTIONAL_FILES));
        return names;
    }

    /** Sound bank names go to sound/, everything else to data/. */
    public static String targetSubdirectoryFor(String fileName) {
        for (String name : ORIGINAL_DK_SOUND_FILES) {
            if (name.equalsIgnoreCase(fileName)) {
                return "sound";
            }
        }
        return "data";
    }

    // ------------------------------------------------------------------ overall

    public static boolean isComplete(Context context) {
        return isKeeperfxInstalled(context) && hasOriginalDkFiles(context);
    }

    /**
     * Background music is a separate download on the desktop too - it is not
     * part of the release - so its absence never blocks starting the game.
     */
    public static boolean hasMusic(Context context) {
        final File music = new File(gameDirectory(context), "music");
        final String[] entries = music.list();
        if (entries == null) {
            return false;
        }
        for (String entry : entries) {
            if (entry.toLowerCase(Locale.US).endsWith(".ogg")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a relative path, retrying case insensitively.
     *
     * The engine opens data files with the exact casing of the DOS originals,
     * while the folders players import are often all lower or all upper case.
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

    /** Human readable size of the installation, for the launcher status line. */
    /**
     * What the installation actually is, release and patch together.
     *
     * The alpha is laid over the release rather than replacing it, so naming
     * only one of them is misleading - and after switching the patch on or off
     * the number at the top of the launcher has to change to match, or nobody
     * can tell which of the two they are looking at.
     */
    public static String describeInstalledVersion(Context context) {
        final Prefs prefs = new Prefs(context);
        final String release = prefs.getInstalledVersion();
        final String alpha = prefs.getInstalledAlphaVersion();
        final String base = release.isEmpty() ? "imported folder" : release;
        return alpha.isEmpty() ? base : base + " + alpha " + alpha;
    }

    public static String describeSize(File dir) {
        return describeBytes(directorySize(dir));
    }

    public static String describeBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
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
