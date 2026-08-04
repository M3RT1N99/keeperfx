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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public final class ReleaseDownloader {

    private static final String TAG = "KeeperFX";
    private static final String API_LATEST = "https://keeperfx.net/api/v1/release/stable/latest";

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

            extract(archive);
            if (isCancelled()) {
                finish(false, "Cancelled while unpacking");
                return;
            }

            new Prefs(context).setInstalledVersion(version);
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

    private void extract(File archive) throws IOException {
        final File destination = GameData.gameDirectory(context);
        // A fresh release replaces the previous one. The files from the original
        // game live in the same tree, so they have to be imported again after
        // this; the launcher says so and the check picks it up.
        DataImporter.deleteRecursively(destination);
        if (!destination.mkdirs()) {
            throw new IOException("Cannot create " + destination.getAbsolutePath());
        }

        listener.onStage("Unpacking", 0, "");
        final String canonicalRoot = destination.getCanonicalPath() + File.separator;
        int entries = 0;

        try (SevenZFile sevenZ = SevenZFile.builder().setFile(archive).get()) {
            SevenZArchiveEntry entry;
            final byte[] buffer = new byte[256 * 1024];
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (isCancelled()) {
                    return;
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
