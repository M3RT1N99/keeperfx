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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AppUpdater {

    private static final String TAG = "KeeperFX";

    /**
     * Newest release published by .github/workflows/build-android.yml. Each
     * build gets its own tag so there is a history, so there is no fixed asset
     * URL to read any more; the API points at the current one and carries the
     * release notes in the same response.
     */
    private static final String LATEST_RELEASE_URL =
        "https://api.github.com/repos/M3RT1N99/keeperfx/releases/latest";

    private static final String TAG_PREFIX = "android-v";
    private static final String APK_ASSET = "keeperfx-android-arm64.apk";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    public interface Listener {
        void onProgress(int percent, String detail);

        void onFinished(boolean success, String message);
    }

    /** What the newest release offers. */
    public static final class Available {
        public final String versionName;
        public final int buildNumber;
        public final String apkUrl;
        /** Changelog lines from the release notes, without their bullets. */
        public final List<String> changes;

        Available(String versionName, int buildNumber, String apkUrl, List<String> changes) {
            this.versionName = versionName;
            this.buildNumber = buildNumber;
            this.apkUrl = apkUrl;
            this.changes = changes;
        }

        /** Ready to drop into a dialog; empty when the notes carried no list. */
        public String changelog() {
            if (changes.isEmpty()) {
                return "";
            }
            final StringBuilder sb = new StringBuilder();
            for (String line : changes) {
                sb.append("• ").append(line).append('\n');
            }
            return sb.toString().trim();
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
            return buildNumberOf(context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName);
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
        final HttpURLConnection connection = openFollowingRedirects(LATEST_RELEASE_URL);
        final StringBuilder body = new StringBuilder();
        try (InputStream in = connection.getInputStream()) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                body.append(new String(buffer, 0, read, "UTF-8"));
            }
        } finally {
            connection.disconnect();
        }

        final JSONObject release = new JSONObject(body.toString());
        final String tag = release.optString("tag_name", "");
        // Only a versioned tag carries a version. The android-latest pointer,
        // which exists so older builds can still find an update, is a
        // prerelease and should never turn up here, but if it does its name
        // must not be mistaken for a version number.
        if (!tag.startsWith(TAG_PREFIX)) {
            Log.w(TAG, "Ignoring release with unexpected tag " + tag);
            return null;
        }
        final String version = displayVersion(tag.substring(TAG_PREFIX.length()));
        if (version.isEmpty()) {
            return null;
        }

        String apkUrl = "";
        final org.json.JSONArray assets = release.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                final JSONObject asset = assets.getJSONObject(i);
                if (APK_ASSET.equals(asset.optString("name"))) {
                    apkUrl = asset.optString("browser_download_url", "");
                    break;
                }
            }
        }
        if (apkUrl.isEmpty()) {
            return null;
        }

        final Available available = new Available(version, buildNumberOf(version), apkUrl,
            parseChangelog(release.optString("body", "")));
        if (available.buildNumber <= installedBuildNumber(context)) {
            return null;
        }
        Log.i(TAG, "Update available: " + available.versionName);
        return available;
    }

    /**
     * Pulls the bullet list out of the release notes. The workflow writes one
     * line per commit subject, so this is the changelog without having to
     * understand the rest of the markdown around it.
     */
    private static List<String> parseChangelog(String notes) {
        final List<String> changes = new ArrayList<>();
        for (String raw : notes.split("\n")) {
            final String line = raw.trim();
            if (line.startsWith("- ") && line.length() > 2) {
                changes.add(line.substring(2).trim());
            }
        }
        return changes;
    }

    /**
     * Rewrites a version taken from a tag into the form the app shows.
     *
     * The tags stay fully dotted because builds up to 1.4.0.5316 read the build
     * number as the part after the last dot and would stop finding updates
     * otherwise. What is shown is "1.4.0_5320": KeeperFX's own version, an
     * underscore, then this port's build number.
     */
    private static String displayVersion(String tagVersion) {
        final int dot = tagVersion.lastIndexOf('.');
        if ((dot < 0) || (tagVersion.indexOf('_') >= 0)) {
            return tagVersion;
        }
        return tagVersion.substring(0, dot) + '_' + tagVersion.substring(dot + 1);
    }

    /**
     * The trailing number of a version name, whichever way it is separated.
     *
     * Versions read "1.4.0_5320" now, KeeperFX's own version and then this
     * port's build number, but builds up to 1.4.0.5316 wrote a plain dot and
     * their tags are still on the releases page, so both have to parse.
     */
    private static int buildNumberOf(String versionName) {
        try {
            int start = versionName.length();
            while ((start > 0) && Character.isDigit(versionName.charAt(start - 1))) {
                start--;
            }
            return (start < versionName.length())
                ? Integer.parseInt(versionName.substring(start)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
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
