/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file UpdateManager.java
 *     Works out what is missing or out of date and can be fetched.
 * @par Purpose:
 *     Three things can be downloaded: the app itself, the KeeperFX release, and
 *     the background music. Rather than a button for each, the launcher asks
 *     this class once and offers whatever came back.
 * @par Comment:
 *     The files that must come from an original Dungeon Keeper are deliberately
 *     absent from this list. They cannot be distributed and the player has to
 *     supply them from their own copy.
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
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class UpdateManager {

    private static final String TAG = "KeeperFX";

    public enum Kind {
        GAME_DATA,
        MUSIC,
        APP,
    }

    public static final class Item {
        public final Kind kind;
        public final String title;
        public final String detail;
        /** Only set for Kind.APP. */
        public final AppUpdater.Available appUpdate;

        Item(Kind kind, String title, String detail, AppUpdater.Available appUpdate) {
            this.kind = kind;
            this.title = title;
            this.detail = detail;
            this.appUpdate = appUpdate;
        }

        @Override
        public String toString() {
            return detail.isEmpty() ? title : title + "\n" + detail;
        }
    }

    private UpdateManager() {
    }

    /**
     * Blocking; call from a background thread. Network failures for one item do
     * not hide the others.
     */
    public static List<Item> check(Context context) {
        final List<Item> items = new ArrayList<>();
        final Prefs prefs = new Prefs(context);

        // --- KeeperFX release -------------------------------------------------
        try {
            final ReleaseDownloader.ReleaseInfo release = ReleaseDownloader.queryLatestRelease();
            final boolean installed = GameData.isKeeperfxInstalled(context);
            final String current = prefs.getInstalledVersion();
            if (!installed) {
                items.add(new Item(Kind.GAME_DATA,
                    "KeeperFX " + release.version,
                    "Game data, " + GameData.describeBytes(release.sizeInBytes), null));
            } else if (!current.isEmpty() && !current.equals(release.version)) {
                items.add(new Item(Kind.GAME_DATA,
                    "KeeperFX " + release.version,
                    "Update from " + current + ", " + GameData.describeBytes(release.sizeInBytes),
                    null));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not check the KeeperFX release", e);
        }

        // --- Background music -------------------------------------------------
        // Only worth offering once the game data is in place; it unpacks into
        // the installation directory.
        if (GameData.isKeeperfxInstalled(context) && !GameData.hasMusic(context)) {
            items.add(new Item(Kind.MUSIC,
                "Background music",
                "Not included in the release, about 42 MB", null));
        }

        // --- The app itself ---------------------------------------------------
        try {
            final AppUpdater.Available app = AppUpdater.checkForUpdate(context);
            if (app != null) {
                items.add(new Item(Kind.APP,
                    "KeeperFX app " + app.versionName,
                    "Currently " + AppUpdater.installedVersionName(context), app));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not check for an app update", e);
        }

        return items;
    }
}
