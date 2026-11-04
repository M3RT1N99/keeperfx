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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
     * The videos are the intro sequence; without them the game opens on a black
     * screen and the log is the only clue.
     *
     * Only what the stable release itself ships belongs here, because a gap in
     * this list is answered by offering that release again. A file only the alpha
     * patch brings, such as fxdata/font12.fxfont, would keep an install "not
     * installed" for good, since the patch is not offered before the release is
     * in place. The patch's own files are checked through its manifest once it has
     * been applied, and the engine runs without them until then.
     */
    private static final String[] KEEPERFX_CONTENT_FILES = {
        "ldata/intromix.smk",
        "ldata/bullfrog.smk",
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
    public static List<Message> findMissingKeeperfxEntries(Context context) {
        if (installInProgress(context).exists()) {
            return Collections.singletonList(new Message(R.string.missing_install_unfinished));
        }
        return findMissingKeeperfxEntries(gameDirectory(context));
    }

    /**
     * Stands from the moment files of a release start to be overwritten until the
     * whole of it is in place. Whatever ended the install in between - a full disk,
     * a cancel, Android killing the process - what is left is a torso that no spot
     * check can tell from an installation, and that the player would then play.
     */
    static File installInProgress(Context context) {
        return new File(manifestDirectory(context), "install-in-progress");
    }

    static void markInstallInProgress(Context context) throws IOException {
        FileUtil.writeAtomically(installInProgress(context), new byte[0]);
    }

    /** @return the paths that are missing, or a sentence when it is not about any one of them. */
    static List<Message> findMissingKeeperfxEntries(File root) {
        if (!root.isDirectory()) {
            return Collections.singletonList(new Message(R.string.missing_nothing_installed));
        }
        final List<Message> missing = new ArrayList<>();
        for (String dir : KEEPERFX_DIRECTORIES) {
            final File d = resolveIgnoringCase(root, dir);
            // Existing but empty counts as missing. A half finished install used to
            // pass this check and then surface as silence and black screens, with the
            // launcher insisting the data was complete.
            final String[] entries = d.list();
            if (entries == null || entries.length == 0) {
                missing.add(Message.plain(dir + "/"));
            }
        }
        for (String file : KEEPERFX_CONTENT_FILES) {
            final File f = resolveIgnoringCase(root, file);
            // Directories count too, and an empty one is as useless as none.
            final boolean present = f.isFile()
                || (f.isDirectory() && f.list() != null && f.list().length > 0);
            if (!present) {
                missing.add(Message.plain(file));
            }
        }
        for (String file : KEEPERFX_FILES) {
            if (!resolveIgnoringCase(root, file).isFile()) {
                missing.add(Message.plain(file));
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
        return findMissingOriginalDkFiles(gameDirectory(context));
    }

    static List<String> findMissingOriginalDkFiles(File root) {
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
        return new File(manifestDirectory(context), TransferBundle.STABLE_MANIFEST);
    }

    public static File alphaManifest(Context context) {
        return new File(manifestDirectory(context), TransferBundle.ALPHA_MANIFEST);
    }

    static File manifestDirectory(Context context) {
        return new File(context.getFilesDir(), "manifests");
    }

    public static boolean hasManifest(Context context) {
        return stableManifest(context).isFile();
    }

    /** Every file the release was recorded unpacking, by relative path. */
    public static Set<String> stableManifestPaths(Context context) {
        return readManifest(stableManifest(context)).keySet();
    }

    /**
     * Where the alpha patch keeps the release's own copies of the files it
     * replaced or retired, so that taking it off again needs no download.
     */
    public static File alphaBackupDirectory(Context context) {
        return new File(context.getFilesDir(), "alpha-backup");
    }

    /** Compares what the manifests list with what is on disk. */
    public static InstallManifest.Report verifyInstalledFiles(Context context) {
        return InstallManifest.verify(gameDirectory(context),
            readManifest(stableManifest(context)), readManifest(alphaManifest(context)),
            alphaBackupDirectory(context), BundledConfig.relativePaths(context));
    }

    private static Map<String, Long> readManifest(File file) {
        try {
            return InstallManifest.read(file);
        } catch (IOException e) {
            Log.w(TAG, "Could not read " + file.getName(), e);
            return Collections.emptyMap();
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

    /**
     * What the installation actually is, release and patch together.
     *
     * The alpha is laid over the release rather than replacing it, so naming
     * only one of them is misleading - and after switching the patch on or off
     * the number at the top of the launcher has to change to match, or nobody
     * can tell which of the two they are looking at.
     */
    public static Message describeInstalledVersion(Context context) {
        final Prefs prefs = new Prefs(context);
        return describeVersion(prefs.getInstalledVersion(), prefs.getInstalledAlphaVersion());
    }

    static Message describeVersion(String release, String alpha) {
        final Message base = release.isEmpty()
            ? new Message(R.string.version_imported_folder) : Message.plain(release);
        return alpha.isEmpty() ? base : new Message(R.string.version_with_alpha, base, alpha);
    }

    /**
     * What the launcher says under its two status lines: what is in the way of playing,
     * or where the installation is when nothing is. A patch that is not the engine's own
     * gets mentioned either way, since it explains a lot of strange behaviour.
     *
     * @param missingOriginal files from the original game that are not there
     * @param alphaVersion the patch laid over the release, or empty
     */
    static String describeProblems(Message.Strings strings, boolean hasKeeperfx,
            List<String> missingOriginal, String alphaVersion, int engineBuild, String directory) {
        final StringBuilder text = new StringBuilder();
        if (!alphaVersion.isEmpty() && engineBuild > 0
                && !ReleaseApi.fitsEngine(alphaVersion, engineBuild)) {
            text.append(new Message(R.string.status_engine_mismatch,
                ReleaseApi.buildOf(alphaVersion), engineBuild).text(strings)).append("\n\n");
        }
        if (hasKeeperfx && missingOriginal.isEmpty()) {
            return text.append(directory).toString();
        }
        if (!hasKeeperfx) {
            text.append(new Message(R.string.hint_install_keeperfx).text(strings)).append("\n\n");
        }
        if (!missingOriginal.isEmpty()) {
            text.append(new Message(R.string.hint_import_original).text(strings)).append('\n');
            for (String entry : missingOriginal.subList(0, Math.min(8, missingOriginal.size()))) {
                text.append("  \u2022 ").append(entry).append('\n');
            }
            if (missingOriginal.size() > 8) {
                text.append("  ").append(new Message(R.string.status_missing_more,
                    missingOriginal.size() - 8).text(strings)).append('\n');
            }
        }
        return text.toString().trim();
    }

    /**
     * Whether the data fits the engine, for the header of a shared log. A mismatch
     * explains a lot of strange reports, and nothing else would reveal it.
     */
    static String describeDataFit(int engineBuild, String alphaVersion) {
        if (alphaVersion.isEmpty()) {
            return "no alpha patch, release only";
        }
        if (engineBuild <= 0) {
            return "unknown, this build does not know its engine's number";
        }
        return ReleaseApi.fitsEngine(alphaVersion, engineBuild)
            ? "alpha patch matches"
            : "MISMATCH, alpha patch is build " + ReleaseApi.buildOf(alphaVersion);
    }

    /** Human readable size of the installation, for the launcher status line. */
    public static Message describeSize(File dir) {
        return describeBytes(directorySize(dir));
    }

    public static Message describeBytes(long bytes) {
        if (bytes < 1024) {
            return new Message(R.string.size_bytes, Math.max(0, bytes));
        }
        final int[] units = {R.string.size_kb, R.string.size_mb, R.string.size_gb};
        int unit = 0;
        double value = bytes / 1024.0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return new Message(units[unit], value);
    }

    /** How far a transfer has got. A total nobody has said stays out of it, rather than being 0. */
    static Message describeProgress(long done, long total) {
        return (total > 0)
            ? new Message(R.string.progress_bytes, describeBytes(done), describeBytes(total))
            : describeBytes(done);
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
