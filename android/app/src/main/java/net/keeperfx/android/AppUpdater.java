/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file AppUpdater.java
 *     Self update for the sideloaded APK.
 * @par Purpose:
 *     The app is not distributed through a store, so nothing else will ever
 *     offer it a newer build. CI publishes each build to a rolling GitHub
 *     release under a fixed tag, which gives a permanent download URL; this
 *     class reads the small version.json next to it, compares against the
 *     running build and hands a downloaded APK to the system installer.
 * @par Comment:
 *     Installing needs REQUEST_INSTALL_PACKAGES and the per-app "install
 *     unknown apps" switch. Both are checked before anything is downloaded, so
 *     the player is not left with a useless file.
 *
 *     The update installs over the running app because CI signs every build
 *     with the same key; game data and settings are preserved.
 * @author   KeeperFX Team
 * @date     05 Aug 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
package net.keeperfx.android;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

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

public final class AppUpdater {

    private static final String TAG = "KeeperFX";

    /** Rolling release published by .github/workflows/build-android.yml. */
    private static final String VERSION_URL =
        "https://github.com/M3RT1N99/keeperfx/releases/download/android-latest/version.json";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    public interface Listener {
        void onProgress(int percent, String detail);

        void onFinished(boolean success, String message);
    }

    /** What the rolling release currently offers. */
    public static final class Available {
        public final String versionName;
        public final int buildNumber;
        public final String commit;
        public final String apkUrl;

        Available(String versionName, int buildNumber, String commit, String apkUrl) {
            this.versionName = versionName;
            this.buildNumber = buildNumber;
            this.commit = commit;
            this.apkUrl = apkUrl;
        }
    }

    private final Context context;
    private final Listener listener;
    private volatile boolean cancelled = false;

    public AppUpdater(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
    }

    private boolean isCancelled() {
        return cancelled || DownloadService.isCancelRequested();
    }

    // ------------------------------------------------------------ version check

    /** Build number of the running app, taken from the last part of the version name. */
    public static int installedBuildNumber(Context context) {
        try {
            final String name = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
            final int dot = name.lastIndexOf('.');
            return (dot >= 0) ? Integer.parseInt(name.substring(dot + 1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String installedVersionName(Context context) {
        try {
            return context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Blocking; call from a background thread. Returns null when up to date. */
    public static Available checkForUpdate(Context context) throws IOException,
            org.json.JSONException {
        final HttpURLConnection connection = openFollowingRedirects(VERSION_URL);
        final StringBuilder body = new StringBuilder();
        try (InputStream in = connection.getInputStream()) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                body.append(new String(buffer, 0, read, "UTF-8"));
            }
        } finally {
            connection.disconnect();
        }

        final JSONObject json = new JSONObject(body.toString());
        final Available available = new Available(
            json.optString("versionName", ""),
            json.optInt("buildNumber", 0),
            json.optString("commit", ""),
            json.optString("apk", ""));

        if (available.buildNumber <= installedBuildNumber(context) || available.apkUrl.isEmpty()) {
            return null;
        }
        Log.i(TAG, "Update available: " + available.versionName);
        return available;
    }

    // ------------------------------------------------------------ download

    /** True when the system will let this app hand an APK to the installer. */
    public static boolean canInstallPackages(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /** Runs synchronously; the caller provides the thread. */
    public void downloadAndInstall(Available available) {
        try {
            final File dir = new File(context.getFilesDir(), "updates");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            // Named after the version so a partial download of an older build
            // is never mistaken for this one and resumed into nonsense.
            final File target = new File(dir, "keeperfx-" + available.versionName + ".apk");
            for (File stale : dir.listFiles() != null ? dir.listFiles() : new File[0]) {
                if (!stale.equals(target)) {
                    //noinspection ResultOfMethodCallIgnored
                    stale.delete();
                }
            }

            listener.onProgress(-1, "");
            download(available.apkUrl, target);
            if (isCancelled()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                listener.onFinished(false, "Download cancelled");
                return;
            }

            final Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".files", target);
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);

            listener.onFinished(true, "");
        } catch (Exception e) {
            Log.e(TAG, "Update failed", e);
            listener.onFinished(false, "Update failed: " + e.getMessage());
        }
    }

    private void download(String url, File target) throws IOException {
        ResumableDownload.fetch(url, target, new ResumableDownload.Progress() {
            @Override
            public void onBytes(long done, long total) {
                final int percent = (total > 0) ? (int) (done * 100 / total) : -1;
                listener.onProgress(percent, String.format(Locale.US, "%s of %s",
                    GameData.describeBytes(done), GameData.describeBytes(total)));
            }

            @Override
            public boolean isCancelled() {
                return AppUpdater.this.isCancelled();
            }
        });
    }

    /**
     * HttpURLConnection refuses to follow a redirect that changes protocol, and
     * release downloads hop from github.com to the asset CDN.
     */
    private static HttpURLConnection openFollowingRedirects(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        int status = connection.getResponseCode();
        int hops = 0;
        while ((status == HttpURLConnection.HTTP_MOVED_PERM
             || status == HttpURLConnection.HTTP_MOVED_TEMP
             || status == HttpURLConnection.HTTP_SEE_OTHER
             || status == 307 || status == 308) && hops++ < 5) {
            final String next = connection.getHeaderField("Location");
            connection.disconnect();
            if (next == null) {
                throw new IOException("Redirect without a Location header");
            }
            connection = (HttpURLConnection) new URL(next).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            status = connection.getResponseCode();
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("Server returned HTTP " + status);
        }
        return connection;
    }
}
