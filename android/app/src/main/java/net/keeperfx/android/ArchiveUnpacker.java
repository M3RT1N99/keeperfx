/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file ArchiveUnpacker.java
 *     Puts the contents of a downloaded archive on disk.
 * @par Comment:
 *     Windows binaries in the release are skipped, they are dead weight here.
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

import android.util.Log;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ArchiveUnpacker {

    private static final String TAG = "KeeperFX";

    /** Extensions that only matter to the Windows build. */
    private static final String[] SKIPPED_SUFFIXES = {
        ".exe", ".dll", ".map", ".pdb", ".bat",
    };

    /** Reports back to whoever runs the unpack, and lets them stop it. */
    interface Progress {
        void onFiles(int unpacked);

        boolean isCancelled();
    }

    private ArchiveUnpacker() {
    }

    static boolean isSkipped(String name) {
        final String lower = name.toLowerCase(Locale.US);
        for (String suffix : SKIPPED_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unpacked over whatever is already there rather than replacing it, the way
     * the desktop launcher installs a release. Everything the archive does not
     * contain therefore survives: the files imported from an original Dungeon
     * Keeper, save games, downloaded music, screenshots, and any campaigns, map
     * packs or mods the player added. Files that a release retires are handled by
     * removeRetiredFiles() instead.
     *
     * @return the relative paths of every file written, for the manifest.
     */
    static List<String> unpackSevenZ(File archive, File destination, Progress progress)
            throws IOException {
        FileUtil.ensureDirectory(destination);
        final List<String> written = new ArrayList<>();
        try (SevenZFile sevenZ = SevenZFile.builder().setFile(archive).get()) {
            requireRoom(sevenZ, destination);
            SevenZArchiveEntry entry;
            final byte[] buffer = new byte[256 * 1024];
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (progress.isCancelled()) {
                    return written;
                }
                final String name = entry.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                final File out = ArchivePaths.resolve(destination, name);
                if (out == null) {
                    Log.w(TAG, "Skipping entry outside the destination: " + name);
                    continue;
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                if (isSkipped(name)) {
                    continue;
                }
                FileUtil.ensureDirectory(out.getParentFile());
                try (OutputStream os = new FileOutputStream(out)) {
                    int read;
                    while ((read = sevenZ.read(buffer)) > 0) {
                        os.write(buffer, 0, read);
                    }
                }
                written.add(ArchivePaths.relativePath(name));
                if ((written.size() % 100) == 0) {
                    progress.onFiles(written.size());
                }
            }
        }
        Log.i(TAG, "Unpacked " + written.size() + " files");
        return written;
    }

    /**
     * Fails before the first file is touched when the rest cannot fit. Running into
     * a full disk half way leaves a torso of what used to be a working installation.
     */
    private static void requireRoom(SevenZFile sevenZ, File destination) throws IOException {
        final long needed = bytesNeeded(sevenZ.getEntries(), destination);
        final long usable = destination.getUsableSpace();
        if (needed > usable) {
            throw new IOException("Not enough free storage: unpacking needs "
                + GameData.describeBytes(needed) + " and " + GameData.describeBytes(usable)
                + " are free");
        }
    }

    /** What the entries add to what is on disk: a file they replace gives its room back. */
    static long bytesNeeded(Iterable<SevenZArchiveEntry> entries, File destination)
            throws IOException {
        long needed = 0;
        for (SevenZArchiveEntry entry : entries) {
            final File out = ArchivePaths.resolve(destination, entry.getName());
            if (out != null && !entry.isDirectory() && !isSkipped(entry.getName())) {
                needed += Math.max(0, entry.getSize() - out.length());
            }
        }
        return needed;
    }

    /**
     * Unpacks a zip into one folder, all or nothing.
     *
     * The presence of a single track is what says the music is installed, so an
     * unpack that fails half way must not leave any: the launcher would not offer
     * the download again and the rest would stay missing for good. Hence the
     * detour. Nothing reaches the folder until everything has been unpacked.
     *
     * The music archive carries a music/ prefix on some releases; flattening it
     * means the tracks always land directly in the folder.
     *
     * @param staging somewhere on the same file system that can be emptied
     * @return how many files it installed; 0 when cancelled.
     */
    static int unzipInstead(File archive, File target, File staging, Progress progress)
            throws IOException {
        FileUtil.deleteTree(staging);
        FileUtil.ensureDirectory(staging);
        try {
            final int files = unzipFlat(archive, staging, progress);
            if (progress.isCancelled()) {
                return 0;
            }
            if (files == 0) {
                throw new IOException("The archive holds no files");
            }
            FileUtil.ensureDirectory(target);
            for (File file : staging.listFiles()) {
                final File installed = new File(target, file.getName());
                if (!file.renameTo(installed)) {
                    throw new IOException("Cannot write " + installed.getAbsolutePath());
                }
            }
            return files;
        } finally {
            FileUtil.deleteTree(staging);
        }
    }

    /**
     * ZipFile rather than ZipInputStream: it starts from the directory at the end
     * of the archive, so a file that was cut short is an error, where the stream
     * reads up to the cut and takes that for the end if it falls between two entries.
     */
    private static int unzipFlat(File archive, File target, Progress progress) throws IOException {
        int files = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && !progress.isCancelled()) {
                final ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                final int slash = name.lastIndexOf('/');
                if (slash >= 0) {
                    name = name.substring(slash + 1);
                }
                if (entry.isDirectory() || name.isEmpty()) {
                    continue;
                }
                final File out = ArchivePaths.resolve(target, name);
                if (out == null) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream os = new FileOutputStream(out)) {
                    FileUtil.copy(in, os);
                }
                files++;
            }
        }
        return files;
    }

    /**
     * Deletes the files a release has retired, listed per version in
     * launcher-auto-file-removal.txt, which ships inside the archive.
     *
     * Installing over the top leaves files behind that newer releases no longer
     * contain; this is the mechanism the desktop launcher uses for them, and it
     * is why not wiping the directory is safe.
     *
     * A patch retires files of the release underneath it as well. Those go into
     * its backup instead, so that taking it off brings them back and the
     * release's manifest, which still lists them, comes true again.
     *
     * @param patchBackup the alpha backup when a patch is being applied, otherwise null
     * @param releaseFiles what the release's manifest lists; only read for a patch
     */
    static void removeRetiredFiles(File root, File patchBackup, Set<String> releaseFiles) {
        final File list = new File(root, "launcher-auto-file-removal.txt");
        if (!list.isFile()) {
            return;
        }
        int removed = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(list), StandardCharsets.UTF_8))) {
            String line;
            boolean sectionApplies = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    // Entries are listed under the version that retired them,
                    // so every section up to the one being installed applies.
                    sectionApplies = true;
                    continue;
                }
                if (!sectionApplies) {
                    continue;
                }
                // The leading slash is how the list writes its paths, not an absolute one.
                final String relative = ArchivePaths.relativePath(
                    line.startsWith("/") ? line.substring(1) : line);
                final File victim = ArchivePaths.resolve(root, relative);
                if (victim != null && victim.isFile()) {
                    final boolean gone = (patchBackup != null && releaseFiles.contains(relative))
                        ? moveIntoBackup(victim, new File(patchBackup, relative))
                        : victim.delete();
                    if (gone) {
                        removed++;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not process the file removal list", e);
            return;
        }
        if (removed > 0) {
            Log.i(TAG, "Removed " + removed + " files retired by this release");
        }
    }

    /** A copy already in the backup is the release's own and is left alone. */
    private static boolean moveIntoBackup(File file, File kept) {
        if (kept.isFile()) {
            return file.delete();
        }
        final File parent = kept.getParentFile();
        return parent != null && (parent.isDirectory() || parent.mkdirs()) && file.renameTo(kept);
    }
}
