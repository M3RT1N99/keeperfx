/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file AppUpdater.java
 *     Self update for the sideloaded APK.
 * @par Purpose:
 *     The app is not distributed through a store, so nothing else will ever
 *     offer it a newer build. CI publishes each build as a GitHub release; this
 *     class asks the API for the newest one, compares it against the running
 *     build and hands a downloaded APK to the system installer.
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

import java.io.File;
import java.io.IOException;
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

    public interface Listener {
        void onProgress(int percent, String detail);

        /** The system installer's intent, in case it has to be opened some other way. */
        void onReadyToInstall(Intent install);

        void onFinished(boolean success, String message);
    }

    private final Context context;
    private final Listener listener;

    public AppUpdater(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    // ------------------------------------------------------------ version check

    public static String installedVersionName(Context context) {
        try {
            return context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Blocking; call from a background thread. Returns null when up to date. */
    static AppRelease checkForUpdate(Context context) throws IOException, org.json.JSONException {
        return newerThan(installedVersionName(context), Http.getJson(LATEST_RELEASE_URL));
    }

    /**
     * @param latest the newest release as the API lists it
     * @return that release when it is newer, null when the app is current
     * @throws IOException when the release cannot tell, since null would call that current
     */
    static AppRelease newerThan(String installed, org.json.JSONObject latest)
            throws IOException, org.json.JSONException {
        final AppRelease release = AppRelease.fromGithub(latest);
        if (release == null) {
            throw new IOException("The newest release carries no version or no APK");
        }
        if (!AppVersion.isNewer(release.versionName, installed)) {
            return null;
        }
        Log.i(TAG, "Update available: " + release.versionName);
        return release;
    }

    // ------------------------------------------------------------ download

    /** True when the system will let this app hand an APK to the installer. */
    public static boolean canInstallPackages(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /** Runs synchronously; the caller provides the thread. */
    void downloadAndInstall(AppRelease available) {
        try {
            // Named after the version so a partial download of an older build
            // is never mistaken for this one and resumed into nonsense.
            final File target = new File(new File(context.getFilesDir(), "updates"),
                "keeperfx-" + available.versionName + ".apk");
            FileUtil.ensureDirectory(target.getParentFile());
            FileUtil.deleteOtherVersions(target, "keeperfx-");

            listener.onProgress(-1, "");
            if (!download(available.apkUrl, target, available.apkSize)) {
                listener.onFinished(false, "Download cancelled");
                return;
            }

            final Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".files", target);
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);
            listener.onReadyToInstall(intent);
            context.startActivity(intent);

            listener.onFinished(true, "");
        } catch (Exception e) {
            Log.e(TAG, "Update failed", e);
            listener.onFinished(false, "Update failed: " + e.getMessage());
        }
    }

    /** @return true when the file is complete, false when cancelled. */
    private boolean download(String url, File target, long expectedSize) throws IOException {
        return ResumableDownload.fetch(url, target, expectedSize, new ResumableDownload.Progress() {
            @Override
            public void onBytes(long done, long total) {
                final int percent = (total > 0) ? (int) (done * 100 / total) : -1;
                listener.onProgress(percent, String.format(Locale.US, "%s of %s",
                    GameData.describeBytes(done), GameData.describeBytes(total)));
            }

            @Override
            public boolean isCancelled() {
                return DownloadService.isCancelRequested();
            }
        });
    }
}
