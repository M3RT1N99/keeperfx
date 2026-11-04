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
        GAME_ALPHA,
        MUSIC,
        APP,
    }

    public static final class Item {
        public final Kind kind;
        public final String title;
        public final String detail;
        /** Only set for Kind.APP. */
        final AppRelease appUpdate;

        Item(Kind kind, String title, String detail, AppRelease appUpdate) {
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
     * Everything the list of downloads depends on. The fields that come from the
     * network are null when the question could not be answered: being offline
     * for one item does not hide the others.
     */
    static final class Facts {
        boolean dataInstalled;
        List<String> missingEntries = new ArrayList<>();
        String stableVersion = "";
        boolean hasManifest;
        InstallManifest.Report verified = new InstallManifest.Report();
        boolean alphaEnabled;
        String alphaVersion = "";
        boolean alphaCached;
        boolean hasMusic;
        String appVersionName = "";

        ReleaseApi.ReleaseInfo latestStable;
        ReleaseApi.ReleaseInfo latestAlpha;
        AppRelease appUpdate;
    }

    /** Blocking; call from a background thread. */
    public static List<Item> check(Context context) {
        final Prefs prefs = new Prefs(context);
        final Facts facts = new Facts();
        facts.missingEntries = GameData.findMissingKeeperfxEntries(context);
        facts.dataInstalled = facts.missingEntries.isEmpty();
        facts.stableVersion = prefs.getInstalledVersion();
        facts.hasManifest = GameData.hasManifest(context);
        // Local disk work, no network: every file the archives said they
        // unpacked, compared with what is actually there.
        facts.verified = GameData.verifyInstalledFiles(context);
        facts.alphaEnabled = prefs.isAlphaEnabled();
        facts.alphaVersion = prefs.getInstalledAlphaVersion();
        facts.hasMusic = GameData.hasMusic(context);
        facts.appVersionName = AppUpdater.installedVersionName(context);

        try {
            facts.latestStable = ReleaseApi.latestStable();
        } catch (Exception e) {
            Log.w(TAG, "Could not check the KeeperFX release", e);
        }
        if (facts.dataInstalled && facts.alphaEnabled) {
            try {
                facts.latestAlpha = ReleaseApi.latestAlpha();
                facts.alphaCached = ReleaseDownloader.isAlphaCached(context, facts.latestAlpha);
            } catch (Exception e) {
                Log.w(TAG, "Could not check the KeeperFX alpha", e);
            }
        }
        try {
            facts.appUpdate = AppUpdater.checkForUpdate(context);
        } catch (Exception e) {
            Log.w(TAG, "Could not check for an app update", e);
        }
        return plan(facts);
    }

    /** Decides what to offer. No Android and no network, so a test can pin it. */
    static List<Item> plan(Facts f) {
        final List<Item> items = new ArrayList<>();

        // --- KeeperFX release -------------------------------------------------
        if (f.latestStable != null) {
            final ReleaseApi.ReleaseInfo release = f.latestStable;
            final String current = f.stableVersion;
            final String size = GameData.describeBytes(release.sizeInBytes);
            if (!f.dataInstalled) {
                // Says what is actually wrong rather than just offering a
                // download: the check now looks inside the directories, so it
                // can name the files that are absent instead of leaving the
                // player to guess from silence and black screens.
                final List<String> gaps = f.missingEntries;
                final StringBuilder detail = new StringBuilder("Game data, ").append(size);
                if (!gaps.isEmpty()) {
                    detail.append("\n    missing: ");
                    for (int i = 0; i < gaps.size() && i < 4; i++) {
                        if (i > 0) {
                            detail.append(", ");
                        }
                        detail.append(gaps.get(i));
                    }
                    if (gaps.size() > 4) {
                        detail.append(" and ").append(gaps.size() - 4).append(" more");
                    }
                }
                items.add(new Item(Kind.GAME_DATA,
                    "KeeperFX " + release.version,
                    detail.toString(), null));
            } else if (current.isEmpty()) {
                // A hand imported folder carries no version, and there is no way
                // to tell how complete it is - the one that prompted this came
                // with no map pack levels at all. Always offer the real release.
                items.add(new Item(Kind.GAME_DATA,
                    "KeeperFX " + release.version,
                    "Replaces the imported folder with the official release, " + size, null));
            } else if (!current.equals(release.version)) {
                items.add(new Item(Kind.GAME_DATA,
                    "KeeperFX " + release.version,
                    "Update from " + current + ", " + size, null));
            } else if (!f.hasManifest) {
                // Installed by a build that did not record what it unpacked. A
                // device in the wild carried the torso of an interrupted unpack
                // for weeks - campgns/keeporig_ger existed but held no speech -
                // and nothing could tell. Reinstalling once writes the manifest
                // and settles the question for good.
                items.add(new Item(Kind.GAME_DATA,
                    "Verify KeeperFX " + current,
                    "This installation predates file verification."
                        + "\n    Reinstalling checks every file once, " + size, null));
            } else if (!f.verified.stableProblems.isEmpty()) {
                final List<String> gaps = f.verified.stableProblems;
                final StringBuilder detail = new StringBuilder()
                    .append(gaps.size())
                    .append(gaps.size() == 1 ? " file" : " files")
                    .append(" missing or damaged, reinstalling repairs them, ")
                    .append(size)
                    .append("\n    e.g. ");
                for (int i = 0; i < gaps.size() && i < 3; i++) {
                    if (i > 0) {
                        detail.append(", ");
                    }
                    detail.append(gaps.get(i));
                }
                items.add(new Item(Kind.GAME_DATA,
                    "Repair KeeperFX " + current,
                    detail.toString(), null));
            }
        }

        // --- Alpha patch ------------------------------------------------------
        // The engine in this APK is built from master while the data comes from
        // the last stable release, so anything the engine gained since then is
        // missing - a device log showed exactly that for fxdata/font12.fxfont,
        // font16.fxfont and sounds.cfg, all three of which the alpha patch
        // carries. It is only meaningful once the release it patches is there,
        // and that check must never ask for a file only this patch brings.
        if (f.dataInstalled && f.alphaEnabled && f.latestAlpha != null
                && !f.latestAlpha.version.isEmpty()) {
            final ReleaseApi.ReleaseInfo alpha = f.latestAlpha;
            final String current = f.alphaVersion;
            // Nothing to fetch if the archive is still on the device from a
            // previous switch, and saying "33 MB" then would be a plain untruth
            // about what pressing the button costs.
            final String cost = f.alphaCached
                ? "already downloaded, only needs applying"
                : GameData.describeBytes(alpha.sizeInBytes);
            if (!alpha.version.equals(current)) {
                items.add(new Item(Kind.GAME_ALPHA,
                    "KeeperFX alpha " + alpha.version,
                    (current.isEmpty()
                        ? "Files the current engine needs and 1.4.0 does not have, "
                        : "Update from " + current + ", ")
                        + cost, null));
            } else if (!f.verified.alphaProblems.isEmpty()) {
                // The patch is current but files it brought are gone or wrong;
                // re-applying it is the download that fixes them.
                items.add(new Item(Kind.GAME_ALPHA,
                    "Repair KeeperFX alpha " + alpha.version,
                    f.verified.alphaProblems.size()
                        + (f.verified.alphaProblems.size() == 1 ? " file" : " files")
                        + " missing or damaged, " + cost, null));
            }
        }

        // --- Background music -------------------------------------------------
        // Only worth offering once the game data is in place; it unpacks into
        // the installation directory.
        if (f.dataInstalled && !f.hasMusic) {
            // Checked on every sweep, not only on a fresh install: the music is
            // not part of the release on any platform, so an installation that
            // is otherwise complete is still silent without it, and the log
            // only says "Cannot load music from ./music/keeper02.ogg".
            items.add(new Item(Kind.MUSIC,
                "Background music",
                "Not included in the release, about 42 MB"
                    + "\n    the game plays no music until this is installed", null));
        }

        // --- The app itself ---------------------------------------------------
        if (f.appUpdate != null) {
            items.add(new Item(Kind.APP,
                "KeeperFX app " + f.appUpdate.versionName,
                "Currently " + f.appVersionName, f.appUpdate));
        }
        return items;
    }
}
