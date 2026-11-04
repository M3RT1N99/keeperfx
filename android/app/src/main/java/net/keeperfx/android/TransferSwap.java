/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file TransferSwap.java
 *     Replaces an installation with a received one, all of it or none of it.
 * @par Purpose:
 *     An installation is more than one folder: the game, the manifests, the alpha
 *     backup and the launcher's settings describe each other, and no single rename
 *     moves them together. So a file stands in as the point of no return. Before it
 *     exists nothing live has been touched and whatever is lying around can simply
 *     go; once it exists everything has passed its checks, and whoever finds it,
 *     be it the job or the next start after a process died, finishes the swap.
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

class TransferSwap {

    private static final String TAG = "KeeperFX";

    /** Takes the settings that came along and the versions they belong to. */
    interface Settings {
        /** @return false when they could not be written. */
        boolean replace(Map<String, String> values, String release, String alpha);
    }

    private final File[] live;
    private final File liveBackup;
    private final File[] staleAfterwards;
    private final File work;
    private final File trash;
    private final File aside;
    private final File commitMark;
    private final File markerFile;
    private final File settingsFile;
    private final Settings settings;
    final TransferBundle.Staging staging;

    /**
     * @param staleAfterwards what describes the replaced installation and goes with it
     * @param work somewhere on the same file system as the rest, used only for this
     * @param trash the same, where finished work waits to be deleted
     */
    TransferSwap(File install, File manifests, File backup, File[] staleAfterwards, File work,
            File trash, Settings settings) {
        this.live = new File[] {install, manifests, backup};
        this.liveBackup = backup;
        this.staleAfterwards = staleAfterwards;
        this.work = work;
        this.trash = trash;
        this.aside = new File(work, "old");
        this.commitMark = new File(work, "commit");
        this.markerFile = new File(work, "marker.properties");
        this.settingsFile = new File(work, "settings.properties");
        this.settings = settings;
        this.staging = new TransferBundle.Staging(new File(work, "new"), install, manifests, backup);
    }

    /**
     * Installs what the stream holds, or leaves everything as it was and says why.
     *
     * @param overlay the files this app's configuration overlay owns, lower case
     * @param appVersion the version of this app, which decides how far the list of
     *        files in the bundle can be held against it
     */
    TransferBundle.Marker install(InputStream in, Set<String> overlay, String appVersion,
            TransferBundle.Progress progress) throws IOException {
        // Room for this one is the point, so what an earlier one left goes first. Not
        // simply into the trash: past its point of no return that holds the only whole
        // installation there is.
        recover();
        if (commitMark.exists()) {
            throw new IOException("An earlier import could neither be finished nor undone");
        }
        FileUtil.deleteTree(trash);
        FileUtil.ensureDirectory(staging.root);
        try {
            final TransferBundle.Unpacked unpacked = TransferBundle.unpack(in, staging, progress);
            final TransferBundle.Marker marker = unpacked.marker;
            TransferBundle.verify(staging, marker, overlay, marker.app.equals(appVersion));
            // A cancel up to here costs nothing, and from here on it would cost the
            // old installation, so this is the last moment to honour one.
            if (progress.isCancelled()) {
                throw new TransferBundle.CancelledException();
            }
            // Even when empty. The presence of this is how the swap knows a folder is
            // still to come, and the backup of a patch this phone will no longer have
            // would "restore" pieces of it into the new installation.
            FileUtil.ensureDirectory(staging.backup);
            commit(marker, Prefs.acceptTransferred(unpacked.offeredSettings));
            return marker;
        } finally {
            if (!commitMark.exists()) {
                discard();
            }
            FileUtil.deleteTree(trash);
        }
    }

    private void commit(TransferBundle.Marker marker, Map<String, String> values)
            throws IOException {
        FileUtil.writeAtomically(markerFile, marker.toBytes());
        FileUtil.writeAtomically(settingsFile, TransferBundle.encodeSettings(values));
        // Everything the rest needs is on disk, so a dead process can pick it up from here.
        FileUtil.writeAtomically(commitMark, new byte[0]);
        try {
            finish();
        } catch (IOException e) {
            rollBack();
            throw e;
        }
    }

    /**
     * For a start after a process death. An unfinished swap is finished, and anything
     * else left behind is an unpack nobody is coming back for. Quick; only the
     * deletion of what is thrown out takes time, and emptyTrash() is for that.
     */
    void recover() {
        if (!work.exists()) {
            return;
        }
        if (commitMark.exists()) {
            try {
                finish();
                return;
            } catch (IOException e) {
                // There is still a whole old installation to go back to.
                Log.e(TAG, "Could not finish replacing the installation", e);
                rollBack();
                return;
            }
        }
        discard();
    }

    void emptyTrash() {
        FileUtil.deleteTree(trash);
    }

    /** Can run any number of times, up to any step of an earlier run: all it looks at is the disk. */
    private void finish() throws IOException {
        for (File now : live) {
            final File incoming = new File(staging.root, now.getName());
            // A folder the file brought is still in staging until its turn has come,
            // so that is what says how far an earlier run got.
            if (incoming.exists()) {
                if (now.exists()) {
                    FileUtil.ensureDirectory(aside);
                    move(now, new File(aside, now.getName()));
                }
                FileUtil.ensureDirectory(now.getParentFile());
                move(incoming, now);
            }
        }
        // Only removes an empty one, which is one that was never there on the other phone.
        //noinspection ResultOfMethodCallIgnored
        liveBackup.delete();
        applySettings();
        for (File stale : staleAfterwards) {
            FileUtil.deleteTree(stale);
        }
        discard();
    }

    private void applySettings() throws IOException {
        final TransferBundle.Marker marker =
            TransferBundle.Marker.parse(Files.readAllBytes(markerFile.toPath()));
        final Map<String, String> values =
            TransferBundle.decodeSettings(Files.readAllBytes(settingsFile.toPath()));
        if (!settings.replace(values, marker.release, marker.alpha)) {
            throw new IOException("Cannot write the launcher's settings");
        }
    }

    /**
     * Back to before the swap started, from any step of it. Settings were written
     * last and a failure there left the old ones standing. Keeps the commit mark
     * unless everything is back: a next start can still finish the swap, which
     * beats declaring a half moved installation clean.
     */
    private void rollBack() {
        boolean restored = true;
        for (File now : live) {
            final File incoming = new File(staging.root, now.getName());
            final File kept = new File(aside, now.getName());
            try {
                if (!incoming.exists() && now.exists()) {
                    move(now, incoming);
                }
                if (kept.exists()) {
                    move(kept, now);
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not put " + now.getName() + " back", e);
                restored = false;
            }
        }
        if (restored) {
            discard();
        }
    }

    /** Takes the work folder out of the way in one step, commit mark and all. */
    private void discard() {
        if (!work.exists()) {
            return;
        }
        try {
            FileUtil.ensureDirectory(trash);
            move(work, new File(trash, Long.toString(System.nanoTime())));
        } catch (IOException e) {
            Log.w(TAG, "Could not move the transfer's work folder aside", e);
            //noinspection ResultOfMethodCallIgnored
            commitMark.delete();
            FileUtil.deleteTree(work);
        }
    }

    /** One step of a swap, and what a test makes fail. */
    void move(File from, File to) throws IOException {
        if (!from.renameTo(to)) {
            throw new IOException("Cannot move " + from + " to " + to);
        }
    }
}
