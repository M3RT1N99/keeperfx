/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file AppRelease.java
 *     What a GitHub release of this app offers.
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

import java.util.ArrayList;
import java.util.List;

final class AppRelease {

    private static final String TAG_PREFIX = "android-v";
    private static final String APK_ASSET = "keeperfx-android-arm64.apk";

    final String versionName;
    final String apkUrl;
    /** Size of the APK as the release lists it, or -1; guards the download. */
    final long apkSize;
    /** Changelog lines from the release notes, without their bullets. */
    final List<String> changes;

    AppRelease(String versionName, String apkUrl, long apkSize, List<String> changes) {
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.apkSize = apkSize;
        this.changes = changes;
    }

    /**
     * @return the release, or null when it is not one this updater can act on:
     *         no version in its tag, or no APK attached.
     */
    static AppRelease fromGithub(JSONObject release) throws JSONException {
        final String tag = release.optString("tag_name", "");
        // Only a versioned tag carries a version. The android-latest pointer,
        // which exists so older builds can still find an update, is a
        // prerelease and should never turn up here, but if it does its name
        // must not be mistaken for a version number.
        if (!tag.startsWith(TAG_PREFIX) || tag.length() == TAG_PREFIX.length()) {
            return null;
        }
        String apkUrl = "";
        long apkSize = -1;
        final JSONArray assets = release.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                final JSONObject asset = assets.getJSONObject(i);
                if (APK_ASSET.equals(asset.optString("name"))) {
                    apkUrl = asset.optString("browser_download_url", "");
                    apkSize = asset.optLong("size", -1);
                    break;
                }
            }
        }
        if (apkUrl.isEmpty()) {
            return null;
        }
        return new AppRelease(tag.substring(TAG_PREFIX.length()), apkUrl, apkSize,
            parseChangelog(release.optString("body", "")));
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

    /** Ready to drop into a dialog; empty when the notes carried no list. */
    String changelog() {
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
