/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file AlphaBackup.java
 *     The way back from the alpha patch to the plain release.
 * @par Purpose:
 *     Everything the patch is about to replace is copied aside first, so that
 *     going back to the plain release later costs nothing. Without it the only
 *     way back would be the 374 MB release all over again, which is a lot to pay
 *     for changing one's mind.
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

final class AlphaBackup {

    private static final String TAG = "KeeperFX";
    /** Paths the patch added, which a revert deletes rather than restores. */
    private static final String ADDED_LIST = "added.txt";

    private final File gameDirectory;
    private final File directory;

    AlphaBackup(File gameDirectory, File directory) {
        this.gameDirectory = gameDirectory;
        this.directory = directory;
    }

    boolean exists() {
        return directory.isDirectory();
    }

    /**
     * Copies aside every installed file the archive would overwrite, and notes
     * the ones it only adds.
     *
     * Only the entry names are read, so nothing is unpacked twice. A backup
     * from an earlier patch is extended file by file, never retaken: a copy
     * already in it is the plain release's and taking it again after a patch
     * had been applied would capture the patched file and make the way back a
     * fiction. A newer patch can touch files the earlier one did not, though,
     * and those are still the release's own at this moment - this is the only
     * chance to save them, and the files it merely adds have to reach the
     * added list or a revert would leave them behind forever.
     */
    void extendFor(File archive) throws IOException {
        FileUtil.ensureDirectory(directory);
        final Set<String> added = readAddedList();
        int saved = 0;
        try (SevenZFile sevenZ = SevenZFile.builder().setFile(archive).get()) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                // The same check the unpack is about to make, and the name goes on
                // the list a revert deletes from, so it cannot wait until then.
                final File installed = ArchivePaths.resolve(gameDirectory, entry.getName());
                if (installed == null || entry.isDirectory()
                        || ArchiveUnpacker.isSkipped(entry.getName())) {
                    continue;
                }
                final String name = ArchivePaths.relativePath(entry.getName());
                // A file the earlier patch added is on disk in the patch's own
                // version; backing that up as if it were the release's would
                // make a revert restore it. It stays on the added list instead.
                if (!installed.isFile() || added.contains(name)) {
                    added.add(name);
                    continue;
                }
                final File target = new File(directory, name);
                if (target.isFile()) {
                    continue;
                }
                // A failure here has to stop the patch: skipping would silently cost
                // this file its way back. And it may not leave half a copy, which
                // the next patch would take for the release's own.
                FileUtil.copyAtomically(installed, target);
                saved++;
            }
        }
        final StringBuilder lines = new StringBuilder();
        for (String name : added) {
            lines.append(name).append('\n');
        }
        try (OutputStream out = new FileOutputStream(new File(directory, ADDED_LIST))) {
            out.write(lines.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "Saved " + saved + " files before applying the alpha patch");
    }

    private Set<String> readAddedList() throws IOException {
        final Set<String> added = new LinkedHashSet<>();
        final File list = new File(directory, ADDED_LIST);
        if (list.isFile()) {
            for (String line : Files.readAllLines(list.toPath(), StandardCharsets.UTF_8)) {
                final String name = line.trim();
                if (!name.isEmpty()) {
                    added.add(name);
                }
            }
        }
        return added;
    }

    /**
     * Restores what the patch replaced and deletes what it added.
     *
     * @return how many files came back.
     */
    int restore() throws IOException {
        final int restored = restoreTree(directory, gameDirectory);
        discard();
        return restored;
    }

    /** Gives up the way back: deletes what the patch added and forgets the saved copies. */
    void discard() throws IOException {
        for (String name : readAddedList()) {
            final File added = ArchivePaths.resolve(gameDirectory, name);
            if (added != null) {
                //noinspection ResultOfMethodCallIgnored
                added.delete();
            }
        }
        FileUtil.deleteTree(directory);
    }

    private static int restoreTree(File from, File to) throws IOException {
        final File[] entries = from.listFiles();
        if (entries == null) {
            return 0;
        }
        int restored = 0;
        for (File entry : entries) {
            final File target = new File(to, entry.getName());
            if (entry.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                target.mkdirs();
                restored += restoreTree(entry, target);
            } else if (!entry.getName().equals(ADDED_LIST)
                    && !entry.getName().endsWith(".part")) {
                FileUtil.copyAtomically(entry, target);
                restored++;
            }
        }
        return restored;
    }
}
