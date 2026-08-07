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
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GameData {

    private static final String TAG = "KeeperFX";

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
        // The English campaign speech, which is what every other language falls
        // back to. Without it the land view and the campaign menu are silent
        // and the log only says a file could not be loaded.
        "campgns/keeporig_eng",
        // The sound effect bank and the speech bank. load_sound_banks() reads
        // sound/sound.dat and sound/speech*.dat, and neither was checked
        // anywhere - only the three .sbk files the Qt launcher copies - so an
        // installation without them reported itself complete and then played
        // nothing at all.
        "sound/sound.dat",
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
            final File f = resolveIgnoringCase(root, file);
            // Directories count too, and an empty one is as useless as none.
            final boolean present = f.isFile()
                || (f.isDirectory() && f.list() != null && f.list().length > 0);
            if (!present) {
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

    // --------------------------------------------------------- install manifest

    /**
     * The manifests written while unpacking, kept outside the game directory so
     * the engine never mistakes them for data. One for the stable release, one
     * for the alpha patch laid over it; each line is "<size>\t<relative path>".
     */
    public static File stableManifest(Context context) {
        return new File(new File(context.getFilesDir(), "manifests"), "stable.txt");
    }

    public static File alphaManifest(Context context) {
        return new File(new File(context.getFilesDir(), "manifests"), "alpha.txt");
    }

    public static boolean hasManifest(Context context) {
        return stableManifest(context).isFile();
    }

    /** What a verification found, split by which download repairs it. */
    public static final class VerifyReport {
        public final List<String> stableProblems = new ArrayList<>();
        public final List<String> alphaProblems = new ArrayList<>();

        public boolean isClean() {
            return stableProblems.isEmpty() && alphaProblems.isEmpty();
        }
    }

    /**
     * Compares every file the archives said they unpacked with what is on disk.
     *
     * This exists because presence checks lie: a device in the wild carried a
     * torso of an interrupted unpack - campgns/keeporig_ger held files, just
     * not the ones the land view asks for - and the handful of spot checks
     * above called that installation complete.
     *
     * A file the alpha patch overwrote is expected at the patch's size, and
     * damage to it is alpha damage, because re-applying the patch is what fixes
     * it. Files the bundled configuration overlay owns are checked for
     * existence only; they are deliberately replaced with the APK's own copies,
     * so their size says nothing about the health of the unpacked release.
     */
    public static VerifyReport verifyInstalledFiles(Context context) {
        final Map<String, Long> stable = new LinkedHashMap<>();
        final Map<String, Long> alpha = new LinkedHashMap<>();
        readManifest(stableManifest(context), stable);
        readManifest(alphaManifest(context), alpha);
        final Map<String, Long> expected = new LinkedHashMap<>(stable);
        expected.putAll(alpha);

        final VerifyReport report = new VerifyReport();
        if (expected.isEmpty()) {
            return report;
        }
        final Set<String> overlay = BundledConfig.relativePaths(context);
        final File root = gameDirectory(context);
        for (Map.Entry<String, Long> want : expected.entrySet()) {
            final String path = want.getKey();
            final File f = new File(root, path);
            boolean damaged;
            if (!f.isFile()) {
                damaged = true;
            } else {
                final String lower = path.toLowerCase(Locale.US).replace('\\', '/');
                damaged = f.length() != want.getValue()
                    && !overlay.contains(lower)
                    && !lower.equals("keeperfx.cfg");
            }
            if (damaged) {
                (alpha.containsKey(path) ? report.alphaProblems : report.stableProblems)
                    .add(path);
            }
        }
        return report;
    }

    private static void readManifest(File file, Map<String, Long> into) {
        if (!file.isFile()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    continue;
                }
                try {
                    into.put(line.substring(tab + 1), Long.parseLong(line.substring(0, tab)));
                } catch (NumberFormatException ignored) {
                    // A garbled line loses one file's check, not the whole list.
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read " + file.getName(), e);
        }
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
