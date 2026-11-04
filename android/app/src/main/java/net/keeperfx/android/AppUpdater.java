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

public final class AppUpdater {

    private static final String TAG = "KeeperFX";

    /**
     * Newest release published by .github/workflows/build-android.yml. Each build has
     * its own tag, so the API is what points at the current one, and it carries the
     * release notes in the same response.
     */
    private static final String LATEST_RELEASE_URL =
        "https://api.github.com/repos/M3RT1N99/keeperfx/releases/latest";

    private AppUpdater() {
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
            throw new Message.Failure(R.string.release_unusable);
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

    /**
     * Fetches the APK. Runs synchronously; the caller provides the thread.
     *
     * @return the system installer's intent for it, or null when cancelled.
     */
    static Intent download(Context context, AppRelease available, ResumableDownload.Progress progress)
            throws IOException {
        // Named after the version so a partial download of an older build
        // is never mistaken for this one and resumed into nonsense.
        final File target = new File(new File(context.getFilesDir(), "updates"),
            "keeperfx-" + available.versionName + ".apk");
        FileUtil.ensureDirectory(target.getParentFile());
        FileUtil.deleteOtherVersions(target, "keeperfx-");

        if (!ResumableDownload.fetch(available.apkUrl, target, available.apkSize, progress)) {
            return null;
        }
        final Uri uri = FileProvider.getUriForFile(
            context, context.getPackageName() + ".files", target);
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
