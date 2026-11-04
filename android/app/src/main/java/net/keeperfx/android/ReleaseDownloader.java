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
import java.util.Locale;

public final class ReleaseDownloader {

    private static final String TAG = "KeeperFX";

    public interface Listener {
        /** @param percent 0..100, or -1 when the total size is unknown. */
        void onStage(String stage, int percent, String detail);

        void onFinished(boolean success, String message);
    }

    private final Context context;
    private final Listener listener;

    private final ArchiveUnpacker.Progress unpackProgress = new ArchiveUnpacker.Progress() {
        @Override
        public void onFiles(int unpacked) {
            listener.onStage("Unpacking", -1, unpacked + " files");
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
     */
    public void runMusic() {
        File archive = null;
        try {
            listener.onStage("Looking up the music archive", -1, "");
            final String url = ReleaseApi.musicArchiveUrl();
            if (url.isEmpty()) {
                finish(false, "The workshop API returned no music download");
                return;
            }

            archive = new File(downloadDirectory(), "keeperfx-music-archive.zip");
            FileUtil.deleteOtherVersions(archive, "keeperfx-music");
            if (!download(url, archive, -1, "music")) {
                finish(false, "Download cancelled");
                return;
            }

            listener.onStage("Unpacking the music", -1, "");
            final File musicDir = new File(GameData.gameDirectory(context), "music");
            final int files = ArchiveUnpacker.unzipAllOrNothing(archive, musicDir,
                new File(downloadDirectory(), "music-unpacking"), unpackProgress);
            if (isCancelled()) {
                finish(false, "Cancelled while unpacking");
                return;
            }
            finish(true, "Installed " + files + " music tracks");
        } catch (Exception e) {
            Log.e(TAG, "Music download failed", e);
            finish(false, "Failed: " + e.getMessage());
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
     */
    public void runAlpha() {
        File archive = null;
        try {
            listener.onStage("Checking for the alpha patch", -1, "");
            final ReleaseApi.ReleaseInfo alpha = ReleaseApi.alphaForEngine(BuildConfig.ENGINE_BUILD);
            if (alpha.downloadUrl.isEmpty()) {
                finish(false, "The alpha API returned no download URL");
                return;
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
                listener.onStage("Using the archive already downloaded", -1, "");
            } else if (!download(alpha.downloadUrl, archive, alpha.sizeInBytes, version)) {
                finish(false, "Download cancelled");
                return;
            }

            ArchiveUnpacker.requireRoomFor(archive, GameData.gameDirectory(context));
            listener.onStage("Saving the files being replaced", -1, "");
            alphaBackup().extendFor(archive);
            final List<String> unpacked = unpack(archive);
            if (isCancelled()) {
                finish(false, "Cancelled while unpacking");
                return;
            }
            final File root = GameData.gameDirectory(context);
            ArchiveUnpacker.removeRetiredFiles(root, GameData.alphaBackupDirectory(context),
                GameData.stableManifestPaths(context));
            writeManifest(GameData.alphaManifest(context), unpacked);

            new Prefs(context).setInstalledAlphaVersion(version);
            finish(true, "KeeperFX alpha " + version + " applied");
        } catch (FileUtil.NoRoomException e) {
            finish(false, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Alpha download failed", e);
            // Kept on success, see above. After a failure it may be what failed,
            // and keeping it would have every further attempt fail the same way.
            if (archive != null) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
            finish(false, "Failed: " + e.getMessage());
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
     */
    public void revertAlpha() {
        try {
            final Prefs prefs = new Prefs(context);
            final AlphaBackup backup = alphaBackup();
            if (!backup.exists()) {
                prefs.setInstalledAlphaVersion("");
                finish(true, "No alpha patch to remove");
                return;
            }
            listener.onStage("Restoring the release", -1, "");
            final int restored = backup.restore();
            prefs.setInstalledAlphaVersion("");
            // The restored files are the stable release's own copies again, so
            // the stable manifest describes them and the patch's does not.
            //noinspection ResultOfMethodCallIgnored
            GameData.alphaManifest(context).delete();
            finish(true, "Alpha patch removed, " + restored + " files restored");
        } catch (Exception e) {
            Log.e(TAG, "Could not remove the alpha patch", e);
            finish(false, "Failed: " + e.getMessage());
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
        //noinspection ResultOfMethodCallIgnored
        GameData.stableManifest(context).delete();
        //noinspection ResultOfMethodCallIgnored
        GameData.alphaManifest(context).delete();
        //noinspection ResultOfMethodCallIgnored
        GameData.installInProgress(context).delete();
    }

    /** Runs synchronously; the caller provides the thread. */
    public void run() {
        File archive = null;
        try {
            listener.onStage("Checking for the current release", -1, "");
            final ReleaseApi.ReleaseInfo release = ReleaseApi.latestStable();
            final String version = release.version.isEmpty() ? "unknown" : release.version;
            if (release.downloadUrl.isEmpty()) {
                finish(false, "The release API returned no download URL");
                return;
            }
            Log.i(TAG, "Latest KeeperFX release: " + version + " at " + release.downloadUrl);

            archive = new File(downloadDirectory(), "keeperfx-release-" + version + ".7z");
            FileUtil.deleteOtherVersions(archive, "keeperfx-release");
            if (!download(release.downloadUrl, archive, release.sizeInBytes, version)) {
                finish(false, "Download cancelled");
                return;
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
                finish(false, "Cancelled while unpacking");
                return;
            }
            ArchiveUnpacker.removeRetiredFiles(GameData.gameDirectory(context), null,
                Collections.<String>emptySet());
            writeManifest(GameData.stableManifest(context), unpacked);
            prefs.setInstalledVersion(version);
            //noinspection ResultOfMethodCallIgnored
            GameData.installInProgress(context).delete();
            finish(true, "KeeperFX " + version + " installed");
        } catch (FileUtil.NoRoomException e) {
            // The archive is fine and free storage is all that is missing, so the
            // next attempt should not have to fetch it again.
            archive = null;
            finish(false, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Release download failed", e);
            finish(false, "Failed: " + e.getMessage());
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
    private boolean download(String url, File target, long expectedSize, String version)
            throws IOException {
        final String stage = "music".equals(version)
            ? "Downloading the music"
            : "Downloading KeeperFX " + version;
        return ResumableDownload.fetch(url, target, expectedSize, new ResumableDownload.Progress() {
            @Override
            public void onBytes(long done, long total) {
                final long known = (total > 0) ? total : expectedSize;
                final int percent = (known > 0) ? (int) (done * 100 / known) : -1;
                listener.onStage(stage, percent, String.format(Locale.US, "%s of %s",
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
        listener.onStage("Unpacking", 0, "");
        final List<String> written = ArchiveUnpacker.unpackSevenZ(archive,
            GameData.gameDirectory(context), unpackProgress);
        if (!isCancelled()) {
            // The release predates the engine we just built, so its configuration
            // can be missing files or carry values this parser rejects.
            listener.onStage("Applying configuration", -1, "");
            BundledConfig.install(context, true);
        }
        return written;
    }

    private void writeManifest(File manifest, List<String> names) throws IOException {
        final int listed = InstallManifest.write(manifest, GameData.gameDirectory(context), names);
        Log.i(TAG, "Manifest " + manifest.getName() + " lists " + listed + " files");
    }

    private void finish(boolean success, String message) {
        listener.onFinished(success, message);
    }
}
