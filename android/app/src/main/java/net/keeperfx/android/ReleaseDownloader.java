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
 *     The release is a 7z archive of roughly 360 MB. Windows binaries in it are
 *     skipped, they are dead weight here.
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

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ReleaseDownloader {

    private static final String LINE_END = "\n";
    private static final String TAG = "KeeperFX";
    private static final String API_LATEST = "https://keeperfx.net/api/v1/release/stable/latest";
    /**
     * The newest alpha patch, a ~33 MB archive that is laid over the stable
     * release rather than replacing it.
     *
     * This port matters more than it does on the desktop: the engine here is
     * built from master, while the data comes from the last stable release, and
     * anything the engine gained since then is simply absent. That is what made
     * a device log complain about missing fxdata/font12.fxfont, font16.fxfont
     * and sounds.cfg - all three are in the alpha patch and none are in 1.4.0.
     */
    private static final String API_ALPHA = "https://keeperfx.net/api/v1/release/alpha/latest";

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;

    /** Extensions that only matter to the Windows build. */
    private static final String[] SKIPPED_SUFFIXES = {
        ".exe", ".dll", ".map", ".pdb", ".bat",
    };

    public interface Listener {
        /** @param percent 0..100, or -1 when the total size is unknown. */
        void onStage(String stage, int percent, String detail);

        void onFinished(boolean success, String message);
    }

    /** What the release API says is current. */
    public static final class ReleaseInfo {
        public final String version;
        public final String downloadUrl;
        public final long sizeInBytes;

        ReleaseInfo(String version, String downloadUrl, long sizeInBytes) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.sizeInBytes = sizeInBytes;
        }
    }

    /**
     * Asks the API which release is current. Blocking; call from a background
     * thread. Used both by the installer and by the update check.
     */
    public static ReleaseInfo queryLatestRelease() throws IOException, org.json.JSONException {
        final JSONObject release = requestLatestRelease();
        return new ReleaseInfo(
            release.optString("version", ""),
            release.optString("download_url", ""),
            release.optLong("size_in_bytes", -1));
    }

    private final Context context;
    private final Listener listener;
    private volatile boolean cancelled = false;

    public ReleaseDownloader(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
    }

    /** A cancel can also come from the notification while the app is closed. */
    private boolean isCancelled() {
        return cancelled || DownloadService.isCancelRequested();
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
            final String url = queryMusicUrl();
            if (url.isEmpty()) {
                finish(false, "The workshop API returned no music download");
                return;
            }

            archive = new File(downloadDirectory(), "keeperfx-music.zip");
            download(url, archive, -1, "music");
            if (isCancelled()) {
                finish(false, "Download cancelled");
                return;
            }

            listener.onStage("Unpacking the music", -1, "");
            final File musicDir = new File(GameData.gameDirectory(context), "music");
            if (!musicDir.isDirectory() && !musicDir.mkdirs()) {
                throw new IOException("Cannot create " + musicDir.getAbsolutePath());
            }
            final int files = unzipInto(archive, musicDir);
            finish(true, "Installed " + files + " music tracks");
        } catch (Exception e) {
            Log.e(TAG, "Music download failed", e);
            finish(false, "Failed: " + e.getMessage());
        } finally {
            // Only a completed archive is removed. A partial one is what the
            // next attempt resumes from.
            if (archive != null && archive.exists() && !isCancelled()) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
        }
    }

    /**
     * The music lives in workshop item 393, the same one the desktop launcher
     * uses. The zip is preferred over the 7z: java.util.zip reads it directly.
     */
    private static String queryMusicUrl() throws IOException, org.json.JSONException {
        final JSONObject item = requestJson(
            "https://keeperfx.net/api/v1/workshop/item/393").getJSONObject("workshop_item");
        final org.json.JSONArray files = item.optJSONArray("files");
        String sevenZip = "";
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                final String url = files.getJSONObject(i).optString("url", "");
                if (url.toLowerCase(Locale.US).endsWith(".zip")) {
                    return url;
                }
                if (url.toLowerCase(Locale.US).endsWith(".7z")) {
                    sevenZip = url;
                }
            }
        }
        return sevenZip;
    }

    private int unzipInto(File archive, File target) throws IOException {
        final String canonicalRoot = target.getCanonicalPath() + File.separator;
        int files = 0;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new BufferedInputStream(new java.io.FileInputStream(archive)))) {
            java.util.zip.ZipEntry entry;
            final byte[] buffer = new byte[128 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (isCancelled()) {
                    return files;
                }
                // The archive carries a music/ prefix on some releases; flatten
                // it so the tracks always land directly in the music folder.
                String name = entry.getName().replace('\\', '/');
                final int slash = name.lastIndexOf('/');
                if (slash >= 0) {
                    name = name.substring(slash + 1);
                }
                if (entry.isDirectory() || name.isEmpty()) {
                    continue;
                }
                final File out = new File(target, name);
                if (!out.getCanonicalPath().startsWith(canonicalRoot)) {
                    continue;
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        os.write(buffer, 0, read);
                    }
                }
                files++;
            }
        }
        return files;
    }

    /**
     * Downloads the newest alpha patch and lays it over the installation.
     *
     * Deliberately the same unpacking path as a full release: the archive has
     * the same layout, only fewer files, and extract() already merges rather
     * than replaces. The recorded version is left alone, because what is
     * installed is still the stable release with a patch on top.
     */
    public void runAlpha() {
        File archive = null;
        try {
            listener.onStage("Checking for the current alpha", -1, "");
            final ReleaseInfo alpha = queryLatestAlpha();
            if (alpha.downloadUrl.isEmpty()) {
                finish(false, "The alpha API returned no download URL");
                return;
            }
            final String version = alpha.version.isEmpty() ? "unknown" : alpha.version;
            Log.i(TAG, "Latest KeeperFX alpha: " + version + " at " + alpha.downloadUrl);

            // Kept between switches. Turning the patch off restores the files
            // it replaced from a local copy, so turning it back on should not
            // cost 33 MB again just because it went away in between.
            final File cache = alphaArchiveCache(version);
            if (cache.isFile() && cache.length() > 0) {
                archive = cache;
                listener.onStage("Using the archive already downloaded", -1, "");
            } else {
                archive = cache;
                download(alpha.downloadUrl, archive, alpha.sizeInBytes, version);
                if (isCancelled()) {
                    finish(false, "Download cancelled");
                    return;
                }
            }

            // Everything the patch is about to replace is copied aside first, so
            // that going back to the plain release later costs nothing. Without
            // it the only way back would be the 374 MB release all over again,
            // which is a lot to pay for changing one's mind.
            listener.onStage("Saving the files being replaced", -1, "");
            backupBeforePatch(archive);
            final List<String> unpacked = extract(archive);
            if (isCancelled()) {
                finish(false, "Cancelled while unpacking");
                return;
            }
            writeManifest(GameData.alphaManifest(context), unpacked);

            new Prefs(context).setInstalledAlphaVersion(version);
            finish(true, "KeeperFX alpha " + version + " applied");
        } catch (Exception e) {
            Log.e(TAG, "Alpha download failed", e);
            finish(false, "Failed: " + e.getMessage());
        }
        // The archive is deliberately not deleted; see alphaArchiveCache().
    }

    /**
     * Whether the alpha archive for a version is already on the device.
     *
     * Asked before the list of downloads is worded, so an entry that costs
     * nothing but unpacking does not present itself as a 33 MB download.
     */
    public static boolean isAlphaCached(Context context, String version) {
        final File f = new File(new File(context.getFilesDir(), "alpha-cache"),
            "alpha-" + version + ".7z");
        return f.isFile() && f.length() > 0;
    }

    /**
     * The alpha archive for one version, kept so switching back and forth is
     * free. Named after the version, so a newer alpha is still fetched and the
     * old one can be dropped.
     */
    private File alphaArchiveCache(String version) {
        final File dir = new File(context.getFilesDir(), "alpha-cache");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final File[] stale = dir.listFiles();
        if (stale != null) {
            for (File f : stale) {
                if (!f.getName().equals("alpha-" + version + ".7z")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        return new File(dir, "alpha-" + version + ".7z");
    }

    /** Where the release's own copies of the patched files are kept. */
    private File alphaBackupDirectory() {
        return new File(context.getFilesDir(), "alpha-backup");
    }

    /** Paths the patch added, which a revert deletes rather than restores. */
    private File alphaAddedList() {
        return new File(alphaBackupDirectory(), "added.txt");
    }

    /**
     * Copies aside every installed file the archive would overwrite, and notes
     * the ones it only adds.
     *
     * Only the entry names are read, so nothing is unpacked twice. An existing
     * backup is left alone: it already holds the plain release's copies, and
     * taking it again after a patch had been applied would capture patched
     * files and make the way back a fiction.
     */
    private void backupBeforePatch(File archive) throws IOException {
        final File destination = GameData.gameDirectory(context);
        final File backup = alphaBackupDirectory();
        if (backup.isDirectory()) {
            return;
        }
        if (!backup.mkdirs()) {
            throw new IOException("Cannot create " + backup.getAbsolutePath());
        }
        final StringBuilder added = new StringBuilder();
        int saved = 0;
        try (SevenZFile sevenZ = SevenZFile.builder().setFile(archive).get()) {
            SevenZArchiveEntry entry;
            final byte[] buffer = new byte[64 * 1024];
            while ((entry = sevenZ.getNextEntry()) != null) {
                final String name = entry.getName();
                if (name == null || name.isEmpty() || entry.isDirectory() || isSkipped(name)) {
                    continue;
                }
                final File installed = new File(destination, name);
                if (!installed.isFile()) {
                    added.append(name).append(LINE_END);
                    continue;
                }
                final File target = new File(backup, name);
                final File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    continue;
                }
                try (InputStream in = new FileInputStream(installed);
                     OutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                saved++;
            }
        }
        try (OutputStream out = new FileOutputStream(alphaAddedList())) {
            out.write(added.toString().getBytes("UTF-8"));
        }
        Log.i(TAG, "Saved " + saved + " files before applying the alpha patch");
    }

    /**
     * Puts the installation back to the plain release without downloading it.
     *
     * Restores what the patch replaced and deletes what it added. Runs
     * synchronously and is quick: the backup is a few megabytes of
     * configuration and data files, not the 374 MB release.
     */
    public void revertAlpha() {
        try {
            final Prefs prefs = new Prefs(context);
            final File backup = alphaBackupDirectory();
            if (!backup.isDirectory()) {
                prefs.setInstalledAlphaVersion("");
                finish(true, "No alpha patch to remove");
                return;
            }
            final File destination = GameData.gameDirectory(context);
            listener.onStage("Restoring the release", -1, "");

            final File addedList = alphaAddedList();
            if (addedList.isFile()) {
                for (String line : Files.readAllLines(addedList.toPath(),
                        Charset.forName("UTF-8"))) {
                    final String name = line.trim();
                    if (!name.isEmpty()) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(destination, name).delete();
                    }
                }
            }
            final int restored = restoreTree(backup, destination);
            deleteTree(backup);
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

    private int restoreTree(File from, File to) throws IOException {
        final File[] entries = from.listFiles();
        if (entries == null) {
            return 0;
        }
        int restored = 0;
        final byte[] buffer = new byte[64 * 1024];
        for (File entry : entries) {
            final File target = new File(to, entry.getName());
            if (entry.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                target.mkdirs();
                restored += restoreTree(entry, target);
            } else if (!entry.getName().equals("added.txt")) {
                try (InputStream in = new FileInputStream(entry);
                     OutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                restored++;
            }
        }
        return restored;
    }

    private static void deleteTree(File root) {
        final File[] entries = root.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.isDirectory()) {
                    deleteTree(entry);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    entry.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        root.delete();
    }

    /** Runs synchronously; the caller provides the thread. */
    public void run() {
        File archive = null;
        try {
            listener.onStage("Checking for the current release", -1, "");
            final ReleaseInfo release = queryLatestRelease();
            final String version = release.version.isEmpty() ? "unknown" : release.version;
            if (release.downloadUrl.isEmpty()) {
                finish(false, "The release API returned no download URL");
                return;
            }
            Log.i(TAG, "Latest KeeperFX release: " + version + " at " + release.downloadUrl);

            // A partial install must not look complete, so forget the recorded
            // version until the new one is fully unpacked.
            new Prefs(context).setInstalledVersion("");

            archive = new File(downloadDirectory(), "keeperfx-release.7z");
            download(release.downloadUrl, archive, release.sizeInBytes, version);
            if (isCancelled()) {
                finish(false, "Download cancelled");
                return;
            }

            final List<String> unpacked = extract(archive);
            if (isCancelled()) {
                finish(false, "Cancelled while unpacking");
                return;
            }
            writeManifest(GameData.stableManifest(context), unpacked);

            // A full release overwrites whatever the alpha patch had put there,
            // so it is no longer applied and has to be offered again.
            final Prefs prefs = new Prefs(context);
            prefs.setInstalledVersion(version);
            // A full release replaces the patched files with its own, so the
            // saved copies describe nothing that is still installed.
            prefs.setInstalledAlphaVersion("");
            deleteTree(alphaBackupDirectory());
            //noinspection ResultOfMethodCallIgnored
            GameData.alphaManifest(context).delete();
            finish(true, "KeeperFX " + version + " installed");
        } catch (Exception e) {
            Log.e(TAG, "Release download failed", e);
            finish(false, "Failed: " + e.getMessage());
        } finally {
            if (archive != null && archive.exists()) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
        }
    }

    private static JSONObject requestLatestRelease() throws IOException, org.json.JSONException {
        return requestJson(API_LATEST).getJSONObject("release");
    }

    /** Asks the API which alpha patch is current. Blocking. */
    public static ReleaseInfo queryLatestAlpha() throws IOException, org.json.JSONException {
        final JSONObject build = requestJson(API_ALPHA).getJSONObject("alpha_build");
        return new ReleaseInfo(
            build.optString("version", ""),
            build.optString("download_url", ""),
            build.optLong("size_in_bytes", -1));
    }

    private static JSONObject requestJson(String url) throws IOException, org.json.JSONException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            final int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("API returned HTTP " + status + " for " + url);
            }
            final StringBuilder body = new StringBuilder();
            try (InputStream in = connection.getInputStream()) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    body.append(new String(buffer, 0, read, "UTF-8"));
                }
            }
            return new JSONObject(body.toString());
        } finally {
            connection.disconnect();
        }
    }

    private void download(String url, File target, long expectedSize, String version)
            throws IOException {
        final String stage = "music".equals(version)
            ? "Downloading the music"
            : "Downloading KeeperFX " + version;
        ResumableDownload.fetch(url, target, new ResumableDownload.Progress() {
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
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir.getAbsolutePath());
        }
        return dir;
    }

    /** @return the relative paths of every file written, for the manifest. */
    private List<String> extract(File archive) throws IOException {
        final File destination = GameData.gameDirectory(context);
        // Unpacked over whatever is already there rather than replacing it, the
        // way the desktop launcher installs a release. Everything the archive
        // does not contain therefore survives: the files imported from an
        // original Dungeon Keeper, save games, downloaded music, screenshots,
        // and any campaigns, map packs or mods the player added. Files that a
        // release retires are handled below by the removal list instead.
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Cannot create " + destination.getAbsolutePath());
        }

        listener.onStage("Unpacking", 0, "");
        final String canonicalRoot = destination.getCanonicalPath() + File.separator;
        final List<String> written = new ArrayList<>();
        int entries = 0;

        try (SevenZFile sevenZ = SevenZFile.builder().setFile(archive).get()) {
            SevenZArchiveEntry entry;
            final byte[] buffer = new byte[256 * 1024];
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (isCancelled()) {
                    return written;
                }
                final String name = entry.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                final File out = new File(destination, name);
                // Refuse anything that would escape the destination directory.
                if (!out.getCanonicalPath().startsWith(canonicalRoot)) {
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
                final File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Cannot create " + parent.getAbsolutePath());
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int read;
                    while ((read = sevenZ.read(buffer)) > 0) {
                        os.write(buffer, 0, read);
                    }
                }
                written.add(name);
                if ((++entries % 100) == 0) {
                    listener.onStage("Unpacking", -1, entries + " files");
                }
            }
        }
        Log.i(TAG, "Unpacked " + entries + " files");

        // The release predates the engine we just built, so its configuration
        // can be missing files or carry values this parser rejects.
        listener.onStage("Applying configuration", -1, "");
        BundledConfig.install(context, true);

        removeRetiredFiles(destination);
        return written;
    }

    /**
     * Records what an unpack left behind, so a later check can tell a whole
     * installation from the torso an interrupted one leaves.
     *
     * Sizes are taken from the disk after the configuration overlay and the
     * retirement list have run, not from the archive, so verification later
     * compares like with like; a file the retirement list deleted again simply
     * falls out. Written to a temporary name first: a manifest that lies is
     * worse than none.
     */
    private void writeManifest(File manifest, List<String> names) throws IOException {
        final File root = GameData.gameDirectory(context);
        final File dir = manifest.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir.getAbsolutePath());
        }
        final StringBuilder lines = new StringBuilder();
        int listed = 0;
        for (String name : names) {
            final File f = new File(root, name);
            if (f.isFile()) {
                lines.append(f.length()).append('\t').append(name).append(LINE_END);
                listed++;
            }
        }
        final File tmp = new File(manifest.getAbsolutePath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(lines.toString().getBytes("UTF-8"));
        }
        if (!tmp.renameTo(manifest)) {
            //noinspection ResultOfMethodCallIgnored
            manifest.delete();
            if (!tmp.renameTo(manifest)) {
                throw new IOException("Cannot write " + manifest.getAbsolutePath());
            }
        }
        Log.i(TAG, "Manifest " + manifest.getName() + " lists " + listed + " files");
    }

    /**
     * Deletes the files a release has retired, listed per version in
     * launcher-auto-file-removal.txt, which ships inside the archive.
     *
     * Installing over the top leaves files behind that newer releases no longer
     * contain; this is the mechanism the desktop launcher uses for them, and it
     * is why not wiping the directory is safe.
     */
    private void removeRetiredFiles(File root) {
        final File list = new File(root, "launcher-auto-file-removal.txt");
        if (!list.isFile()) {
            return;
        }
        int removed = 0;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(list), "UTF-8"))) {
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
                final String relative = line.startsWith("/") ? line.substring(1) : line;
                final File victim = new File(root, relative);
                if (victim.isFile() && victim.getCanonicalPath()
                        .startsWith(root.getCanonicalPath() + File.separator)) {
                    if (victim.delete()) {
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

    private static boolean isSkipped(String name) {
        final String lower = name.toLowerCase(Locale.US);
        for (String suffix : SKIPPED_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private void finish(boolean success, String message) {
        listener.onFinished(success, message);
    }
}
