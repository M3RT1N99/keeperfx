/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file DataImporter.java
 *     Copies game data from shared storage into the app.
 * @par Purpose:
 *     The engine works on ordinary POSIX paths, which the Storage Access
 *     Framework cannot provide, so anything the player picks is copied into the
 *     app's private directory first.
 *
 *     Two modes exist, matching the two halves of an installation: a full copy
 *     of a KeeperFX folder, and a targeted search that pulls just the files an
 *     original Dungeon Keeper has to supply out of whatever folder the player
 *     points at.
 * @par Comment:
 *     Traversal uses DocumentsContract queries rather than DocumentFile: a
 *     KeeperFX installation has several thousand files and DocumentFile issues
 *     one query per entry, which takes minutes on the same data set.
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

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DataImporter {

    private static final String TAG = "KeeperFX";

    /** Depth and node budget for the original-game search, so picking a whole
     *  SD card cannot turn into an unbounded scan. */
    private static final int DK_SEARCH_MAX_DEPTH = 6;
    private static final int DK_SEARCH_MAX_DIRECTORIES = 4000;

    /** Reported back to the launcher; always called on a background thread. */
    public interface Listener {
        void onProgress(int filesCopied, String currentPath);

        void onFinished(boolean success, String message);
    }

    private final Context context;
    private final Listener listener;
    private volatile boolean cancelled = false;
    private int filesCopied = 0;

    public DataImporter(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
    }

    // ------------------------------------------------------- KeeperFX release

    /**
     * Replaces the current installation with the contents of the picked tree.
     * Runs synchronously; the caller provides the thread.
     */
    public void importKeeperfxTree(Uri treeUri) {
        final File destination = GameData.gameDirectory(context);
        try {
            deleteRecursively(destination);
            if (!destination.mkdirs()) {
                finish(false, "Cannot create " + destination.getAbsolutePath());
                return;
            }

            final String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            final Uri rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);

            // Some file managers hand back the parent of the folder the player
            // meant. If the tree contains exactly one directory and none of the
            // files we expect, descend into it.
            final PendingDir start = resolveStartDirectory(treeUri, rootUri, destination);
            copyDirectory(treeUri, start.documentUri, start.target);

            if (cancelled) {
                deleteRecursively(destination);
                finish(false, "Import cancelled");
                return;
            }

            // The imported folder can come from any KeeperFX version, so pair
            // its configuration back up with the engine in this APK.
            BundledConfig.install(context);

            finish(true, "Copied " + filesCopied + " files");
        } catch (Exception e) {
            Log.e(TAG, "Import failed", e);
            deleteRecursively(destination);
            finish(false, "Import failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------- original Dungeon Keeper

    /**
     * Searches the picked tree for the files an original Dungeon Keeper has to
     * supply and copies them into the installation.
     *
     * The player may point at the game folder itself, at its DATA subfolder or
     * at a mounted CD image, so the search walks down rather than expecting a
     * fixed layout. Names are matched case insensitively because the originals
     * are upper case on the CD and lower case in most re-releases.
     */
    public void importOriginalDkTree(Uri treeUri) {
        final File destination = GameData.gameDirectory(context);
        try {
            if (!destination.isDirectory() && !destination.mkdirs()) {
                finish(false, "Cannot create " + destination.getAbsolutePath());
                return;
            }

            final Set<String> wanted = new HashSet<>();
            for (String name : GameData.allOriginalDkFileNames()) {
                wanted.add(name.toLowerCase(Locale.US));
            }
            final Set<String> found = new HashSet<>();

            final String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            final Uri rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
            searchAndCopy(treeUri, rootUri, wanted, found);

            if (cancelled) {
                finish(false, "Import cancelled");
                return;
            }

            final List<String> stillMissing = GameData.findMissingOriginalDkFiles(context);
            if (stillMissing.isEmpty()) {
                finish(true, "Copied " + filesCopied + " files from the original game");
            } else {
                final StringBuilder sb = new StringBuilder();
                sb.append("Copied ").append(filesCopied).append(" files, but these are still missing:\n\n");
                for (String name : stillMissing) {
                    sb.append("  ").append(name).append('\n');
                }
                sb.append("\nPick the folder that holds the original game's DATA and SOUND folders.");
                finish(false, sb.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Original game import failed", e);
            finish(false, "Import failed: " + e.getMessage());
        }
    }

    private void searchAndCopy(Uri treeUri, Uri rootUri, Set<String> wanted, Set<String> found)
            throws IOException {
        final ContentResolver resolver = context.getContentResolver();
        final Deque<SearchDir> queue = new ArrayDeque<>();
        queue.add(new SearchDir(rootUri, 0));
        int visited = 0;

        while (!queue.isEmpty() && !cancelled && found.size() < wanted.size()) {
            final SearchDir current = queue.poll();
            if (++visited > DK_SEARCH_MAX_DIRECTORIES) {
                Log.w(TAG, "Stopping the search after " + DK_SEARCH_MAX_DIRECTORIES + " folders");
                break;
            }

            final String parentId = DocumentsContract.getDocumentId(current.uri);
            final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
            final List<SearchDir> subdirectories = new ArrayList<>();

            try (Cursor cursor = resolver.query(childrenUri, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
            }, null, null, null)) {
                if (cursor == null) {
                    continue;
                }
                while (cursor.moveToNext() && !cancelled) {
                    final String documentId = cursor.getString(0);
                    final String displayName = cursor.getString(1);
                    final String mimeType = cursor.getString(2);
                    if (displayName == null || displayName.contains("/")) {
                        continue;
                    }
                    final Uri childUri =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        if (current.depth < DK_SEARCH_MAX_DEPTH) {
                            subdirectories.add(new SearchDir(childUri, current.depth + 1));
                        }
                        continue;
                    }

                    final String key = displayName.toLowerCase(Locale.US);
                    if (!wanted.contains(key) || found.contains(key)) {
                        continue;
                    }
                    final File targetDir = new File(GameData.gameDirectory(context),
                        GameData.targetSubdirectoryFor(displayName));
                    if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
                        throw new IOException("Cannot create " + targetDir.getAbsolutePath());
                    }
                    copyFile(childUri, new File(targetDir, key));
                    found.add(key);
                    listener.onProgress(filesCopied, displayName);
                }
            }
            queue.addAll(subdirectories);
        }
    }

    private static final class SearchDir {
        final Uri uri;
        final int depth;

        SearchDir(Uri uri, int depth) {
            this.uri = uri;
            this.depth = depth;
        }
    }

    // ------------------------------------------------------------------ shared

    private static final class PendingDir {
        final Uri documentUri;
        final File target;

        PendingDir(Uri documentUri, File target) {
            this.documentUri = documentUri;
            this.target = target;
        }
    }

    private PendingDir resolveStartDirectory(Uri treeUri, Uri rootUri, File destination) {
        final ContentResolver resolver = context.getContentResolver();
        final String rootId = DocumentsContract.getDocumentId(rootUri);
        final Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId);

        int directoryCount = 0;
        String onlyDirectoryId = null;
        boolean sawExpectedEntry = false;

        try (Cursor cursor = resolver.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        }, null, null, null)) {
            if (cursor == null) {
                return new PendingDir(rootUri, destination);
            }
            while (cursor.moveToNext()) {
                final String id = cursor.getString(0);
                final String name = cursor.getString(1);
                final boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
                if (isDir) {
                    directoryCount++;
                    onlyDirectoryId = id;
                    if ("fxdata".equalsIgnoreCase(name) || "creatrs".equalsIgnoreCase(name)) {
                        sawExpectedEntry = true;
                    }
                } else if (name != null && name.equalsIgnoreCase("keeperfx.cfg")) {
                    sawExpectedEntry = true;
                }
            }
        }

        if (!sawExpectedEntry && directoryCount == 1 && onlyDirectoryId != null) {
            Log.i(TAG, "Descending into the single subfolder of the picked tree");
            return new PendingDir(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, onlyDirectoryId),
                destination);
        }
        return new PendingDir(rootUri, destination);
    }

    private void copyDirectory(Uri treeUri, Uri directoryUri, File targetRoot) throws IOException {
        final ContentResolver resolver = context.getContentResolver();
        final Deque<PendingDir> queue = new ArrayDeque<>();
        queue.push(new PendingDir(directoryUri, targetRoot));

        while (!queue.isEmpty() && !cancelled) {
            final PendingDir current = queue.pop();
            if (!current.target.isDirectory() && !current.target.mkdirs()) {
                throw new IOException("Cannot create " + current.target.getAbsolutePath());
            }

            final String parentId = DocumentsContract.getDocumentId(current.documentUri);
            final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);

            try (Cursor cursor = resolver.query(childrenUri, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
            }, null, null, null)) {
                if (cursor == null) {
                    continue;
                }
                while (cursor.moveToNext() && !cancelled) {
                    final String documentId = cursor.getString(0);
                    final String displayName = cursor.getString(1);
                    final String mimeType = cursor.getString(2);
                    if (displayName == null || displayName.equals(".") || displayName.equals("..")
                        || displayName.contains("/")) {
                        continue;
                    }
                    final Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    final File childFile = new File(current.target, displayName);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        queue.push(new PendingDir(childUri, childFile));
                    } else {
                        copyFile(childUri, childFile);
                    }
                }
            }
        }
    }

    private void copyFile(Uri source, File target) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("Cannot read " + source);
            }
            final byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        filesCopied++;
        if ((filesCopied % 25) == 0) {
            listener.onProgress(filesCopied, target.getName());
        }
    }

    private void finish(boolean success, String message) {
        listener.onProgress(filesCopied, "");
        listener.onFinished(success, message);
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        // Nothing sensible to do on failure; the caller reports the end state.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
