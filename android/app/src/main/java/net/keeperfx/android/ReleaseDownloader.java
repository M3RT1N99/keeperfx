/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file ReleaseDownloader.java
 *     Fetches and unpacks an official KeeperFX release.
 * @par Purpose:
 *     Mirrors what the desktop launcher does: ask keeperfx.net which release is
 *     current, download the archive and unpack it into the app's private
 *     storage. That leaves the player only having to supply the handful of
 *     files an original Dungeon Keeper must provide.
 * @par Comment:
 *     The release is a 7z archive of roughly 360 MB. This class runs the jobs and
 *     keeps the records; ReleaseApi asks what is current, ArchiveUnpacker writes
 *     the files and AlphaBackup keeps the way back from the patch.
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
import java.util.Collections;
import java.util.List;

public final class ReleaseDownloader {

    private static final String TAG = "KeeperFX";

    public interface Listener {
        /**
         * @param percent 0..100, or -1 when the total size is unknown.
         * @param detail null when there is nothing to add.
         */
        void onStage(Message stage, int percent, Message detail);
    }

    private final Context context;
    private final Listener listener;

    private final ArchiveUnpacker.Progress unpackProgress = new ArchiveUnpacker.Progress() {
        @Override
        public void onFiles(int unpacked) {
            stage(R.string.stage_unpacking, -1, new Message(R.string.stage_unpacked_files, unpacked));
        }

        @Override
        public boolean isCancelled() {
            return ReleaseDownloader.this.isCancelled();
        }
    };

    public ReleaseDownloader(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** A cancel comes from the notification, the launcher may well be closed. */
    private boolean isCancelled() {
        return DownloadService.isCancelRequested();
    }

    /**
     * Downloads the background music archive and unpacks it into music/.
     *
     * Music is not part of the release on any platform; the desktop launcher
     * fetches it from the same workshop item. Runs synchronously.
     *
     * @return null when it is done, otherwise what went wrong.
     */
    public Message runMusic() {
        File archive = null;
        try {
            stage(R.string.stage_music_lookup, -1, null);
            final String url = ReleaseApi.musicArchiveUrl();
            if (url.isEmpty()) {
                return new Message(R.string.job_no_music_download);
            }

            archive = new File(downloadDirectory(), "keeperfx-music-archive.zip");
            FileUtil.deleteOtherVersions(archive, "keeperfx-music");
            if (!download(url, archive, -1, new Message(R.string.stage_music_download))) {
                return new Message(R.string.job_download_cancelled);
            }

            stage(R.string.stage_music_unpack, -1, null);
            final File musicDir = new File(GameData.gameDirectory(context), "music");
            final int files = ArchiveUnpacker.unzipAllOrNothing(archive, musicDir,
                new File(downloadDirectory(), "music-unpacking"), unpackProgress);
            if (isCancelled()) {
                return new Message(R.string.job_unpack_cancelled);
            }
            Log.i(TAG, "Installed " + files + " music tracks");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Music download failed", e);
            return failure(e);
        } finally {
            // Only ever a whole archive under this name. What is still partial
            // is what the next attempt resumes from.
            if (archive != null && !isCancelled()) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
        }
    }

    /**
     * Downloads the alpha patch for this engine and lays it over the installation.
     *
     * Deliberately the same unpacking path as a full release: the archive has
     * the same layout, only fewer files, and it merges rather than replaces. The
     * recorded version is left alone, because what is installed is still the
     * stable release with a patch on top.
     *
     * @return null when it is done, otherwise what went wrong.
     */
    public Message runAlpha() {
        File archive = null;
        try {
            stage(R.string.stage_alpha_check, -1, null);
            final ReleaseApi.ReleaseInfo alpha = ReleaseApi.alphaForEngine(BuildConfig.ENGINE_BUILD);
            if (alpha.downloadUrl.isEmpty()) {
                return new Message(R.string.job_no_download_address);
            }
            final String version = alpha.version.isEmpty() ? "unknown" : alpha.version;
            Log.i(TAG, "KeeperFX alpha for engine build " + BuildConfig.ENGINE_BUILD + ": "
                + version + " at " + alpha.downloadUrl);

            // Kept between switches. Turning the patch off restores the files
            // it replaced from a local copy, so turning it back on should not
            // cost 33 MB again just because it went away in between.
            archive = alphaArchiveCache(context, version);
            FileUtil.deleteOtherVersions(archive, "alpha-");
            if (ResumableDownload.isComplete(archive, alpha.sizeInBytes)) {
                stage(R.string.stage_archive_cached, -1, null);
            } else if (!download(alpha.downloadUrl, archive, alpha.sizeInBytes,
                    new Message(R.string.stage_download_version, version))) {
                return new Message(R.string.job_download_cancelled);
            }

            ArchiveUnpacker.requireRoomFor(archive, GameData.gameDirectory(context));
            stage(R.string.stage_saving_replaced, -1, null);
            alphaBackup().extendFor(archive);
            final List<String> unpacked = unpack(archive);
            if (isCancelled()) {
                return new Message(R.string.job_unpack_cancelled);
            }
            final File root = GameData.gameDirectory(context);
            ArchiveUnpacker.removeRetiredFiles(root, GameData.alphaBackupDirectory(context),
                GameData.stableManifestPaths(context));
            writeManifest(GameData.alphaManifest(context), unpacked);

            new Prefs(context).setInstalledAlphaVersion(version);
            Log.i(TAG, "KeeperFX alpha " + version + " applied");
            return null;
        } catch (FileUtil.NoRoomException e) {
            return e.message;
        } catch (Exception e) {
            Log.e(TAG, "Alpha download failed", e);
            // Kept on success, see above. After a failure it may be what failed,
            // and keeping it would have every further attempt fail the same way.
            if (archive != null) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
            return failure(e);
        }
    }

    /**
     * Whether the alpha archive for a version is already on the device.
     *
     * Asked before the list of downloads is worded, so an entry that costs
     * nothing but unpacking does not present itself as a 33 MB download.
     */
    static boolean isAlphaCached(Context context, ReleaseApi.ReleaseInfo alpha) {
        return ResumableDownload.isComplete(alphaArchiveCache(context, alpha.version),
            alpha.sizeInBytes);
    }

    /**
     * The alpha archive for one version, kept so switching back and forth is
     * free. Named after the version, so another alpha is still fetched and the
     * old one can be dropped.
     */
    private static File alphaArchiveCache(Context context, String version) {
        return new File(new File(context.getFilesDir(), "alpha-cache"), "alpha-" + version + ".7z");
    }

    private AlphaBackup alphaBackup() {
        return new AlphaBackup(GameData.gameDirectory(context),
            GameData.alphaBackupDirectory(context));
    }

    /**
     * Puts the installation back to the plain release without downloading it.
     *
     * Runs synchronously and is quick: the backup is some tens of megabytes of
     * configuration and data files, not the 374 MB release.
     *
     * @return null when it is done, otherwise what went wrong.
     */
    public Message revertAlpha() {
        try {
            final Prefs prefs = new Prefs(context);
            final AlphaBackup backup = alphaBackup();
            if (!backup.exists()) {
                prefs.setInstalledAlphaVersion("");
                return null;
            }
            stage(R.string.stage_restoring_release, -1, null);
            final int restored = backup.restore();
            prefs.setInstalledAlphaVersion("");
            // The restored files are the stable release's own copies again, so
            // the stable manifest describes them and the patch's does not.
            //noinspection ResultOfMethodCallIgnored
            GameData.alphaManifest(context).delete();
            Log.i(TAG, "Alpha patch removed, " + restored + " files restored");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Could not remove the alpha patch", e);
            return failure(e);
        }
    }

    /**
     * Forgets everything recorded about the current installation: both
     * versions, the alpha backup and archive cache, and the manifests.
     *
     * For "Reset" and for a hand-imported folder. All of it describes files
     * that are gone or replaced, and the pieces are not harmless individually:
     * an alpha backup that survives a reset would "restore" fragments of the
     * deleted installation into the next one, and a stale manifest makes the
     * verification report damage that is really just a different install.
     */
    public static void wipeInstallState(Context context) {
        final Prefs prefs = new Prefs(context);
        prefs.setInstalledVersion("");
        prefs.setInstalledAlphaVersion("");
        FileUtil.deleteTree(GameData.alphaBackupDirectory(context));
        FileUtil.deleteTree(new File(context.getFilesDir(), "alpha-cache"));
        // Hundreds of megabytes of partial release, and what "Reset" is often for.
        FileUtil.deleteTree(new File(context.getFilesDir(), "downloads"));
        FileUtil.deleteTree(new File(context.getFilesDir(), "updates"));
        // A copy of everything being forgotten, the player's saves among them.
        FileUtil.deleteTree(PhoneTransfer.exportDirectory(context));
        DownloadService.cancelFinishedNotification(context);
        //noinspection ResultOfMethodCallIgnored
        GameData.stableManifest(context).delete();
        //noinspection ResultOfMethodCallIgnored
        GameData.alphaManifest(context).delete();
        //noinspection ResultOfMethodCallIgnored
        GameData.installInProgress(context).delete();
    }

    /**
     * Fetches the release and lays it over the installation. Runs synchronously; the
     * caller provides the thread.
     *
     * @return null when it is done, otherwise what went wrong.
     */
    public Message run() {
        File archive = null;
        try {
            stage(R.string.stage_release_check, -1, null);
            final ReleaseApi.ReleaseInfo release = ReleaseApi.latestStable();
            final String version = release.version.isEmpty() ? "unknown" : release.version;
            if (release.downloadUrl.isEmpty()) {
                return new Message(R.string.job_no_download_address);
            }
            Log.i(TAG, "Latest KeeperFX release: " + version + " at " + release.downloadUrl);

            archive = new File(downloadDirectory(), "keeperfx-release-" + version + ".7z");
            FileUtil.deleteOtherVersions(archive, "keeperfx-release");
            if (!download(release.downloadUrl, archive, release.sizeInBytes,
                    new Message(R.string.stage_download_version, version))) {
                return new Message(R.string.job_download_cancelled);
            }

            // Everything before this point leaves the installation as it was.
            ArchiveUnpacker.requireRoomFor(archive, GameData.gameDirectory(context));

            // A partial install must not look complete, so forget the recorded
            // version until the new one is fully unpacked. Not any earlier: a
            // download that fails has not touched what is installed.
            final Prefs prefs = new Prefs(context);
            GameData.markInstallInProgress(context);
            prefs.setInstalledVersion("");
            // A full release replaces the patched files with its own, so the patch
            // is no longer applied and has to be offered again. What only the patch
            // brought goes first: left behind it would pass for the release's own
            // when the patch comes back, and a revert would then restore it.
            alphaBackup().discard();
            prefs.setInstalledAlphaVersion("");
            //noinspection ResultOfMethodCallIgnored
            GameData.alphaManifest(context).delete();

            final List<String> unpacked = unpack(archive);
            if (isCancelled()) {
                return new Message(R.string.job_unpack_cancelled);
            }
            ArchiveUnpacker.removeRetiredFiles(GameData.gameDirectory(context), null,
                Collections.<String>emptySet());
            writeManifest(GameData.stableManifest(context), unpacked);
            prefs.setInstalledVersion(version);
            //noinspection ResultOfMethodCallIgnored
            GameData.installInProgress(context).delete();
            Log.i(TAG, "KeeperFX " + version + " installed");
            return null;
        } catch (FileUtil.NoRoomException e) {
            // The archive is fine and free storage is all that is missing, so the
            // next attempt should not have to fetch it again.
            archive = null;
            return e.message;
        } catch (Exception e) {
            Log.e(TAG, "Release download failed", e);
            return failure(e);
        } finally {
            // Only ever a whole archive under this name, whether it unpacked or
            // turned out to be damaged. What is still partial is what the next
            // attempt resumes from, and a cancelled unpack can start over for free.
            if (archive != null && !isCancelled()) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
        }
    }

    /** @return true when the file is complete, false when cancelled. */
    private boolean download(String url, File target, long expectedSize, Message stage)
            throws IOException {
        return ResumableDownload.fetch(url, target, expectedSize, new ResumableDownload.Progress() {
            @Override
            public void onBytes(long done, long total) {
                final long known = (total > 0) ? total : expectedSize;
                final int percent = (known > 0) ? (int) (done * 100 / known) : -1;
                listener.onStage(stage, percent, new Message(R.string.progress_bytes,
                    GameData.describeBytes(done), GameData.describeBytes(known)));
            }

            @Override
            public boolean isCancelled() {
                return ReleaseDownloader.this.isCancelled();
            }
        });
    }

    private File downloadDirectory() throws IOException {
        // Not the cache directory: Android may clear that while a 360 MB
        // transfer is in flight, throwing away everything fetched so far.
        final File dir = new File(context.getFilesDir(), "downloads");
        FileUtil.ensureDirectory(dir);
        return dir;
    }

    /** @return the relative paths of every file written, for the manifest. */
    private List<String> unpack(File archive) throws IOException {
        stage(R.string.stage_unpacking, 0, null);
        final List<String> written = ArchiveUnpacker.unpackSevenZ(archive,
            GameData.gameDirectory(context), unpackProgress);
        if (!isCancelled()) {
            // The release predates the engine we just built, so its configuration
            // can be missing files or carry values this parser rejects.
            stage(R.string.stage_applying_config, -1, null);
            BundledConfig.install(context, true);
        }
        return written;
    }

    private void writeManifest(File manifest, List<String> names) throws IOException {
        final int listed = InstallManifest.write(manifest, GameData.gameDirectory(context), names);
        Log.i(TAG, "Manifest " + manifest.getName() + " lists " + listed + " files");
    }

    private void stage(int stage, int percent, Message detail) {
        listener.onStage(new Message(stage), percent, detail);
    }

    private static Message failure(Exception e) {
        return (e instanceof Message.Failure)
            ? ((Message.Failure) e).message
            : new Message(R.string.job_failed, Message.detailOf(e));
    }
}
