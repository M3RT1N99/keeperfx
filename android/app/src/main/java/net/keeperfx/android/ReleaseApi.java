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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

final class ReleaseApi {

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

        static ReleaseInfo fromJson(JSONObject release) {
            return new ReleaseInfo(
                release.optString("version", ""),
                release.optString("download_url", ""),
                release.optLong("size_in_bytes", -1));
        }
    }

    private ReleaseApi() {
    }

    /** Asks which release is current. Blocking; call from a background thread. */
    static ReleaseInfo latestStable() throws IOException, JSONException {
        return ReleaseInfo.fromJson(Http.getJson(API_LATEST).getJSONObject("release"));
    }

    /** Asks which alpha patch is current. Blocking. */
    static ReleaseInfo latestAlpha() throws IOException, JSONException {
        return ReleaseInfo.fromJson(Http.getJson(API_ALPHA).getJSONObject("alpha_build"));
    }

    /**
     * Where the music archive can be fetched. Blocking; empty when the workshop
     * lists none. The zip is preferred over the 7z: java.util.zip reads it
     * directly.
     */
    static String musicArchiveUrl() throws IOException, JSONException {
        return musicArchiveUrl(Http.getJson(API_MUSIC));
    }

    static String musicArchiveUrl(JSONObject workshopResponse) throws JSONException {
        final JSONArray files = workshopResponse.getJSONObject("workshop_item").optJSONArray("files");
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
}
