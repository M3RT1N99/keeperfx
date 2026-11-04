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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class FileUtil {

    private FileUtil() {
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

    static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            copy(in, out);
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
}
