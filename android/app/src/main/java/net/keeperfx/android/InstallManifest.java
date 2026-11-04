/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file InstallManifest.java
 *     Records what an unpack left behind and checks it later.
 * @par Purpose:
 *     Presence checks lie: a device in the wild carried a torso of an interrupted
 *     unpack - campgns/keeporig_ger held files, just not the ones the land view
 *     asks for - and the handful of spot checks in GameData called that
 *     installation complete. A manifest lists every file an archive put on disk.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class InstallManifest {

    /** What a verification found, split by which download repairs it. */
    static final class Report {
        final List<String> stableProblems = new ArrayList<>();
        final List<String> alphaProblems = new ArrayList<>();

        boolean isClean() {
            return stableProblems.isEmpty() && alphaProblems.isEmpty();
        }
    }

    private InstallManifest() {
    }

    /**
     * Writes one line per file, "<size>\t<relative path>".
     *
     * Sizes are taken from the disk after the configuration overlay and the
     * retirement list have run, not from the archive, so verification later
     * compares like with like; a file the retirement list deleted again simply
     * falls out. Written whole or not at all: a manifest that lies is worse than
     * none.
     *
     * @return how many files it lists.
     */
    static int write(File manifest, File root, Collection<String> names) throws IOException {
        final StringBuilder lines = new StringBuilder();
        int listed = 0;
        for (String name : names) {
            final File f = new File(root, name);
            if (f.isFile()) {
                lines.append(f.length()).append('\t').append(name).append('\n');
                listed++;
            }
        }
        FileUtil.writeAtomically(manifest, lines.toString().getBytes(StandardCharsets.UTF_8));
        return listed;
    }

    /** Relative path to size; empty when the manifest is not there. */
    static Map<String, Long> read(File file) throws IOException {
        final Map<String, Long> entries = new LinkedHashMap<>();
        if (!file.isFile()) {
            return entries;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    continue;
                }
                try {
                    entries.put(line.substring(tab + 1), Long.parseLong(line.substring(0, tab)));
                } catch (NumberFormatException ignored) {
                    // A garbled line loses one file's check, not the whole list.
                }
            }
        }
        return entries;
    }

    /**
     * Compares every file the archives said they unpacked with what is on disk.
     *
     * A file the alpha patch overwrote is expected at the patch's size, and
     * damage to it is alpha damage, because re-applying the patch is what fixes
     * it. Files the bundled configuration overlay owns are checked for
     * existence only; they are deliberately replaced with the APK's own copies,
     * so their size says nothing about the health of the unpacked release.
     *
     * A release file with a copy in the backup and not on the patch's list is the
     * patch's doing and no damage. Either the patch retired it, or it was cancelled or
     * killed half way and never got to write its manifest. Reporting it would offer
     * the 374 MB release, which clears the patch, when applying the patch again is all
     * it takes.
     *
     * @param overlay the files the configuration overlay owns, lower case
     */
    static Report verify(File root, Map<String, Long> stable, Map<String, Long> alpha,
            File alphaBackup, Set<String> overlay) {
        final Map<String, Long> expected = new LinkedHashMap<>(stable);
        expected.putAll(alpha);

        final Report report = new Report();
        for (Map.Entry<String, Long> want : expected.entrySet()) {
            final String path = want.getKey();
            final File f = new File(root, path);
            final String lower = path.toLowerCase(Locale.US).replace('\\', '/');
            final boolean damaged = !f.isFile()
                || (f.length() != want.getValue()
                    && !overlay.contains(lower)
                    && !lower.equals("keeperfx.cfg"));
            if (damaged) {
                if (alpha.containsKey(path)) {
                    report.alphaProblems.add(path);
                } else if (!new File(alphaBackup, path).isFile()) {
                    report.stableProblems.add(path);
                }
            }
        }
        return report;
    }
}
