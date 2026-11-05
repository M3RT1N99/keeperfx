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

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class UpdateManager {

    private static final String TAG = "KeeperFX";

    public enum Kind {
        GAME_DATA,
        GAME_ALPHA,
        MUSIC,
        APP,
        /** Never offered; the launcher's switch for the patch runs it. */
        ALPHA_REVERT,
        /** Never offered either; these two have buttons of their own. */
        BUNDLE_EXPORT,
        BUNDLE_IMPORT,
    }

    public static final class Item {
        public final Kind kind;
        final Message title;
        final Message[] lines;
        /** Only set for Kind.APP. */
        final AppRelease appUpdate;

        Item(Kind kind, Message title, AppRelease appUpdate, Message... lines) {
            this.kind = kind;
            this.title = title;
            this.appUpdate = appUpdate;
            this.lines = lines;
        }
    }

    private UpdateManager() {
    }

    static boolean contains(List<Item> items, Kind kind) {
        for (Item item : items) {
            if (item.kind == kind) {
                return true;
            }
        }
        return false;
    }

    /** An app update waits for the player's own say in the system's installer, the rest does not. */
    static boolean changesInstallation(List<Item> items) {
        for (Item item : items) {
            if (item.kind != Kind.APP) {
                return true;
            }
        }
        return false;
    }

    static List<Item> without(List<Item> items, Kind kind) {
        final List<Item> rest = new ArrayList<>(items);
        rest.removeIf(item -> item.kind == kind);
        return rest;
    }

    /**
     * The list as a dialog shows it.
     *
     * The point of publishing a release per build is that the player can see why an
     * update is being offered. This dialog is the one they actually reach, so the
     * changelog has to be here and not only in the app-only dialog behind the
     * version button.
     */
    static String describe(List<Item> items, Message.Strings strings) {
        final StringBuilder body = new StringBuilder();
        for (Item item : items) {
            body.append("\u2022 ").append(item.title.text(strings)).append('\n');
            for (Message line : item.lines) {
                body.append("    ").append(line.text(strings)).append('\n');
            }
            if (item.appUpdate != null && !item.appUpdate.changelog().isEmpty()) {
                for (String line : item.appUpdate.changelog().split("\n")) {
                    body.append("    ").append(line).append('\n');
                }
            }
        }
        return body.toString();
    }

    /** What the dialog says about what a check found. */
    static String describe(Result found, Message.Strings strings) {
        final StringBuilder body = new StringBuilder(describe(found.items, strings));
        if (goesOverAnInstallation(found.items)) {
            body.append('\n').append(new Message(R.string.updates_replaces_data).text(strings));
        }
        if (found.unanswered) {
            body.append('\n').append(new Message(R.string.updates_unanswered_more).text(strings));
        }
        return body.append('\n').append(new Message(R.string.updates_use_wifi).text(strings)).toString();
    }

    /** With nothing there, what stays of it is no news: that is a new player's first dialog. */
    private static boolean goesOverAnInstallation(List<Item> items) {
        for (Item item : items) {
            if (item.kind == Kind.GAME_DATA) {
                return !Arrays.asList(item.lines).contains(new Message(R.string.missing_nothing_installed));
            }
        }
        return false;
    }

    /**
     * Android 11 ends every process of an app when the permission to install apps is
     * granted to it, and downloads that are running would go with them. There the settings
     * wait until the next tap, when the app update is all that is left and nothing is
     * running. Not tried on such a device: from reading the platform's source.
     */
    static boolean installPermissionHasToWait(boolean othersRunning, int sdk) {
        return othersRunning && sdk == android.os.Build.VERSION_CODES.R;
    }

    /** What a check found. */
    public static final class Result {
        public final List<Item> items;
        /**
         * A server did not answer, so an empty list does not mean nothing is waiting: an
         * app update nobody could ask about looks just like there being none.
         */
        public final boolean unanswered;

        Result(List<Item> items, boolean unanswered) {
            this.items = items;
            this.unanswered = unanswered;
        }
    }

    /**
     * Everything the list of downloads depends on. The fields that come from the
     * network are null when the question could not be answered: being offline
     * for one item does not hide the others.
     */
    static final class Facts {
        boolean dataInstalled;
        List<Message> missingEntries = new ArrayList<>();
        String stableVersion = "";
        boolean hasManifest;
        InstallManifest.Report verified = new InstallManifest.Report();
        boolean alphaEnabled;
        String alphaVersion = "";
        boolean alphaCached;
        int engineBuild;
        boolean hasMusic;
        String appVersionName = "";

        ReleaseApi.ReleaseInfo latestStable;
        ReleaseApi.ReleaseInfo alphaForEngine;
        AppRelease appUpdate;
    }

    /** The questions that go over the network, apart so that a test can have them fail. */
    interface Servers {
        ReleaseApi.ReleaseInfo latestStable() throws IOException, JSONException;

        ReleaseApi.ReleaseInfo alphaForEngine(int engineBuild) throws IOException, JSONException;

        /** Null when the app is current; not being able to tell has to throw. */
        AppRelease appUpdate() throws IOException, JSONException;
    }

    /** Blocking; call from a background thread. */
    public static Result check(Context context) {
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
        facts.engineBuild = BuildConfig.ENGINE_BUILD;
        facts.hasMusic = GameData.hasMusic(context);
        facts.appVersionName = BuildConfig.VERSION_NAME;

        return ask(facts, new Servers() {
            @Override
            public ReleaseApi.ReleaseInfo latestStable() throws IOException, JSONException {
                return ReleaseApi.latestStable();
            }

            @Override
            public ReleaseApi.ReleaseInfo alphaForEngine(int engineBuild)
                    throws IOException, JSONException {
                final ReleaseApi.ReleaseInfo alpha = ReleaseApi.alphaForEngine(engineBuild);
                facts.alphaCached = ReleaseDownloader.isAlphaCached(context, alpha);
                return alpha;
            }

            @Override
            public AppRelease appUpdate() throws IOException, JSONException {
                return AppUpdater.checkForUpdate();
            }
        });
    }

    /**
     * Fills in what the servers say and plans from that. No connection, a server error
     * and GitHub's limit on anonymous requests all end up here the same way: what
     * depends on that answer stays off the list, and the result says the list is not
     * the whole story.
     */
    static Result ask(Facts facts, Servers servers) {
        boolean unanswered = false;
        try {
            facts.latestStable = servers.latestStable();
        } catch (Exception e) {
            Log.w(TAG, "Could not check the KeeperFX release", e);
            unanswered = true;
        }
        if (facts.dataInstalled && facts.alphaEnabled) {
            try {
                facts.alphaForEngine = servers.alphaForEngine(facts.engineBuild);
            } catch (Exception e) {
                Log.w(TAG, "Could not check the KeeperFX alpha", e);
                unanswered = true;
            }
        }
        try {
            facts.appUpdate = servers.appUpdate();
        } catch (Exception e) {
            Log.w(TAG, "Could not check for an app update", e);
            unanswered = true;
        }
        return new Result(plan(facts), unanswered);
    }

    /** Decides what to offer. No Android and no network, so a test can pin it. */
    static List<Item> plan(Facts f) {
        final List<Item> items = new ArrayList<>();

        // --- KeeperFX release -------------------------------------------------
        if (f.latestStable != null) {
            final ReleaseApi.ReleaseInfo release = f.latestStable;
            final String current = f.stableVersion;
            final Message size = GameData.describeBytes(release.sizeInBytes);
            final Message latest = new Message(R.string.update_release, release.version);
            if (!f.dataInstalled) {
                // Says what is actually wrong rather than just offering a
                // download: the check now looks inside the directories, so it
                // can name the files that are absent instead of leaving the
                // player to guess from silence and black screens.
                final Message data = new Message(R.string.update_release_size, size);
                items.add(f.missingEntries.isEmpty()
                    ? new Item(Kind.GAME_DATA, latest, null, data)
                    : new Item(Kind.GAME_DATA, latest, null, data, missingLine(f.missingEntries)));
            } else if (current.isEmpty()) {
                // A hand imported folder carries no version, and there is no way
                // to tell how complete it is - the one that prompted this came
                // with no map pack levels at all. Always offer the real release.
                items.add(new Item(Kind.GAME_DATA, latest, null,
                    new Message(R.string.update_replaces_folder, size)));
            } else if (!current.equals(release.version)) {
                items.add(new Item(Kind.GAME_DATA, latest, null,
                    new Message(R.string.update_from_version, current, size)));
            } else if (!f.hasManifest) {
                // Data from a build older than the manifests, which an APK update keeps.
                // Nothing can say it is damaged, but nothing can say it is whole either, and
                // Export refuses it and sends the player here. Installing writes the manifest.
                items.add(new Item(Kind.GAME_DATA, latest, null,
                    new Message(R.string.update_release_size, size)));
            } else if (!f.verified.stableProblems.isEmpty()) {
                final List<String> gaps = f.verified.stableProblems;
                items.add(new Item(Kind.GAME_DATA, new Message(R.string.update_repair, current), null,
                    new Message(R.string.update_release_damaged, gaps.size(), size),
                    new Message(R.string.update_for_example,
                        Message.plain(gaps.subList(0, Math.min(3, gaps.size())).toArray()))));
            }
        }

        // --- Alpha patch ------------------------------------------------------
        // The engine in this APK is built from master while the data comes from
        // the last stable release, so anything the engine gained since then is
        // missing - a device log showed exactly that for fxdata/font12.fxfont,
        // font16.fxfont and sounds.cfg, all three of which the alpha patch
        // carries. It is only meaningful once the release it patches is there,
        // and that check must never ask for a file only this patch brings.
        if (f.dataInstalled && f.alphaEnabled && f.alphaForEngine != null) {
            final ReleaseApi.ReleaseInfo alpha = f.alphaForEngine;
            final String current = f.alphaVersion;
            // Nothing to fetch if the archive is still on the device from a
            // previous switch, and saying "33 MB" then would be a plain untruth
            // about what pressing the button costs.
            final Message cost = f.alphaCached
                ? new Message(R.string.update_cost_cached)
                : new Message(R.string.update_cost_download, GameData.describeBytes(alpha.sizeInBytes));
            if (alpha.version.equals(current)) {
                if (!f.verified.alphaProblems.isEmpty()) {
                    // The patch is current but files it brought are gone or wrong;
                    // re-applying it is the download that fixes them.
                    items.add(new Item(Kind.GAME_ALPHA,
                        new Message(R.string.update_repair_alpha, alpha.version), null,
                        new Message(R.string.update_alpha_damaged, f.verified.alphaProblems.size()),
                        cost));
                }
            } else if (!ReleaseApi.fitsEngine(current, f.engineBuild)) {
                // What is installed stays if it fits, even when the API has lost
                // that build and has only something newer to suggest.
                final Message title = new Message(R.string.update_alpha, alpha.version);
                final Message what = alphaOffer(current, f.stableVersion);
                items.add(ReleaseApi.fitsEngine(alpha.version, f.engineBuild)
                    ? new Item(Kind.GAME_ALPHA, title, null, what, cost)
                    : new Item(Kind.GAME_ALPHA, title, null, what, cost,
                        new Message(R.string.update_alpha_other_build, f.engineBuild)));
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
            items.add(new Item(Kind.MUSIC, new Message(R.string.update_music), null,
                new Message(R.string.update_music_size), new Message(R.string.update_music_why)));
        }

        // --- The app itself ---------------------------------------------------
        if (f.appUpdate != null) {
            items.add(new Item(Kind.APP,
                new Message(R.string.update_app, f.appUpdate.versionName), f.appUpdate,
                new Message(R.string.update_app_current, f.appVersionName)));
        }
        return items;
    }

    /** The first few paths, or the sentence that stands in for them all. */
    private static Message missingLine(List<Message> gaps) {
        if (gaps.size() == 1 && !gaps.get(0).isPlain()) {
            return gaps.get(0);
        }
        final Message firstFew = Message.plain(gaps.subList(0, Math.min(4, gaps.size())).toArray());
        return (gaps.size() > 4)
            ? new Message(R.string.update_missing_and_more, firstFew, gaps.size() - 4)
            : new Message(R.string.update_missing, firstFew);
    }

    private static Message alphaOffer(String currentAlpha, String stableVersion) {
        if (!currentAlpha.isEmpty()) {
            return new Message(R.string.update_alpha_replaces, currentAlpha);
        }
        // An imported folder carries no version to name here.
        return stableVersion.isEmpty()
            ? new Message(R.string.update_alpha_adds_to_folder)
            : new Message(R.string.update_alpha_adds, stableVersion);
    }
}
