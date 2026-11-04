/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file FileUtil.java
 *     The file operations the installers share.
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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class FileUtil {

    /** Nothing is wrong with what was being installed, there is just no room for it. */
    static final class NoRoomException extends IOException {
        NoRoomException(String message) {
            super(message);
        }
    }

    private FileUtil() {
    }

    /** Says so up front, rather than after running into a full disk half way. */
    static void requireRoom(File directory, long bytes, String what) throws NoRoomException {
        final long usable = directory.getUsableSpace();
        if (bytes > usable) {
            throw new NoRoomException("Not enough free storage: " + what + " needs "
                + GameData.describeBytes(bytes) + " and " + GameData.describeBytes(usable)
                + " are free");
        }
    }

    static void ensureDirectory(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir.getAbsolutePath());
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        final byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    /**
     * Writes a whole file or none at all, so that a full disk or a dying process
     * cannot leave half of one under a name that later counts as present.
     */
    static void writeAtomically(File target, InputStream content) throws IOException {
        ensureDirectory(target.getParentFile());
        final File partial = new File(target.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            copy(content, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw e;
        }
        if (!partial.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new IOException("Cannot write " + target.getAbsolutePath());
        }
    }

    static void writeAtomically(File target, byte[] content) throws IOException {
        writeAtomically(target, new ByteArrayInputStream(content));
    }

    static void copyAtomically(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from)) {
            writeAtomically(to, in);
        }
    }

    /** Best effort: whatever cannot be removed is what the next install overwrites. */
    static void deleteTree(File root) {
        final File[] entries = root.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                deleteTree(entry);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        root.delete();
    }

    /**
     * Clears out earlier downloads of the same thing. A file and its partial stay;
     * they are this download and what it resumes from.
     */
    static void deleteOtherVersions(File keep, String prefix) {
        final File[] entries = keep.getParentFile().listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            final String name = entry.getName();
            if (name.startsWith(prefix) && !name.equals(keep.getName())
                    && !name.equals(ResumableDownload.partialOf(keep).getName())) {
                //noinspection ResultOfMethodCallIgnored
                entry.delete();
            }
        }
    }
}
