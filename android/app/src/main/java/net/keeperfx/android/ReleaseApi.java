/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file ReleaseApi.java
 *     What keeperfx.net says can be downloaded.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
package net.keeperfx.android;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

final class ReleaseApi {

    private static final String TAG = "KeeperFX";

    private static final String API_LATEST = "https://keeperfx.net/api/v1/release/stable/latest";
    /**
     * An alpha patch, a ~33 MB archive that is laid over the stable release rather
     * than replacing it.
     *
     * This port matters more than it does on the desktop: the engine here is
     * built from master, while the data comes from the last stable release, and
     * anything the engine gained since then is simply absent. That is what made
     * a device log complain about missing fxdata/font12.fxfont, font16.fxfont
     * and sounds.cfg - all three are in the alpha patch and none are in 1.4.0.
     */
    private static final String API_ALPHA_BUILD = "https://keeperfx.net/api/v1/release/alpha/build/";
    private static final String API_ALPHA_LATEST = "https://keeperfx.net/api/v1/release/alpha/latest";
    /** Workshop item 393 holds the music, the same one the desktop launcher uses. */
    private static final String API_MUSIC = "https://keeperfx.net/api/v1/workshop/item/393";

    /** What the release API says about one archive. */
    static final class ReleaseInfo {
        final String version;
        final String downloadUrl;
        final long sizeInBytes;

        ReleaseInfo(String version, String downloadUrl, long sizeInBytes) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.sizeInBytes = sizeInBytes;
        }

        /**
         * The version is what an installation is recorded under and what its archive is
         * named after, and nothing recorded means a folder the player brought. An answer
         * without one is no answer, which whoever asked already knows what to do with.
         */
        static ReleaseInfo fromJson(JSONObject release) throws Message.Failure {
            final String version = release.optString("version", "");
            if (version.isEmpty()) {
                throw new Message.Failure(R.string.release_no_version);
            }
            return new ReleaseInfo(version, release.optString("download_url", ""),
                release.optLong("size_in_bytes", -1));
        }
    }

    private ReleaseApi() {
    }

    /** Asks which release is current. Blocking; call from a background thread. */
    static ReleaseInfo latestStable() throws IOException, JSONException {
        return ReleaseInfo.fromJson(Http.getJson(API_LATEST).getJSONObject("release"));
    }

    /**
     * The alpha patch to lay over the release under an engine of this build.
     * Blocking.
     *
     * Every commit upstream gets a patch numbered like the merge base this engine
     * is built from, and that one is what fits: the newest one drifts away from the
     * engine as soon as upstream moves. Only when the API does not have it, or a
     * local build does not know its number, does the newest have to do. Being
     * better than nothing, it still is offered, and fitsEngine() tells.
     */
    static ReleaseInfo alphaForEngine(int engineBuild) throws IOException, JSONException {
        if (engineBuild > 0) {
            try {
                return ReleaseInfo.fromJson(
                    Http.getJson(API_ALPHA_BUILD + engineBuild).getJSONObject("alpha_build"));
            } catch (Http.NotFoundException noSuchBuild) {
                Log.w(TAG, "No alpha patch for engine build " + engineBuild + ", taking the newest");
            }
        }
        return ReleaseInfo.fromJson(Http.getJson(API_ALPHA_LATEST).getJSONObject("alpha_build"));
    }

    /** The build number a patch's version ends in: 5591 for 1.4.0.5591. 0 when it has none. */
    static int buildOf(String alphaVersion) {
        try {
            return Integer.parseInt(alphaVersion.substring(alphaVersion.lastIndexOf('.') + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean fitsEngine(String alphaVersion, int engineBuild) {
        return engineBuild > 0 && buildOf(alphaVersion) == engineBuild;
    }

    /** Where the music archive can be fetched. Blocking; empty when the workshop lists none. */
    static String musicArchiveUrl() throws IOException, JSONException {
        return musicArchiveUrl(Http.getJson(API_MUSIC));
    }

    /** The zip: that is what java.util.zip reads, and the item carries one next to a 7z. */
    static String musicArchiveUrl(JSONObject workshopResponse) throws JSONException {
        final JSONArray files = workshopResponse.getJSONObject("workshop_item").optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                final String url = files.getJSONObject(i).optString("url", "");
                if (url.toLowerCase(Locale.US).endsWith(".zip")) {
                    return url;
                }
            }
        }
        return "";
    }
}
