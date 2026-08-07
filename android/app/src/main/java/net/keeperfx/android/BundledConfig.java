/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file BundledConfig.java
 *     Lays the engine's own configuration over the installed game data.
 * @par Purpose:
 *     On the desktop the engine and the files in config/ ship as one release,
 *     so they always match. Here the engine is built from source while the data
 *     comes from whatever release the player installs or imports, and the two
 *     drift apart: a config file the engine expects can be missing, or an
 *     option can carry a value this engine's parser does not know.
 *
 *     The whole config/ tree is therefore packaged into the APK - roughly two
 *     megabytes of cfg, toml and lua - and copied over the installation, which
 *     restores the pairing the desktop builds have by construction.
 * @par Comment:
 *     keeperfx.cfg holds player settings and is only written when absent.
 *     Everything else is engine coupled and is replaced.
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
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class BundledConfig {

    private static final String TAG = "KeeperFX";

    /** Asset subdirectory the Gradle copy task fills from the repository's config/. */
    private static final String ASSET_ROOT = "kfx-config";

    /** Player settings; never clobbered once it exists. */
    private static final String USER_SETTINGS = "keeperfx.cfg";

    private BundledConfig() {
    }

    /**
     * Copies the bundled configuration into the installation, keeping the
     * player's own keeperfx.cfg.
     *
     * @return the number of files written.
     */
    public static int install(Context context) throws IOException {
        return install(context, false);
    }

    /**
     * Copies the bundled configuration into the installation.
     *
     * @param replaceUserSettings replace keeperfx.cfg as well. True right after
     *        an install or import, where it belongs to the data that just
     *        arrived and may come from a version whose options this engine does
     *        not parse. False on a normal launch, where it holds settings the
     *        player has since changed.
     * @return the number of files written.
     */
    public static int install(Context context, boolean replaceUserSettings) throws IOException {
        final File destination = GameData.gameDirectory(context);
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Cannot create " + destination.getAbsolutePath());
        }
        final int written = copyDirectory(
            context.getAssets(), ASSET_ROOT, destination, replaceUserSettings);
        Log.i(TAG, "Applied " + written + " bundled configuration files"
            + (replaceUserSettings ? " including keeperfx.cfg" : ""));
        return written;
    }

    private static int copyDirectory(AssetManager assets, String assetPath, File target,
            boolean replaceUserSettings) throws IOException {
        final String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            return 0;
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Cannot create " + target.getAbsolutePath());
        }

        int written = 0;
        for (String entry : entries) {
            final String childAsset = assetPath + "/" + entry;
            final File childFile = new File(target, entry);

            // AssetManager has no isDirectory(); a non-empty listing means a
            // directory, and anything else is treated as a file.
            final String[] grandChildren = assets.list(childAsset);
            if (grandChildren != null && grandChildren.length > 0) {
                written += copyDirectory(assets, childAsset, childFile, replaceUserSettings);
                continue;
            }

            if (USER_SETTINGS.equalsIgnoreCase(entry) && childFile.isFile()
                && !replaceUserSettings) {
                continue; // the player's own settings win on a normal launch
            }
            copyFile(assets, childAsset, childFile);
            written++;
        }
        return written;
    }

    /**
     * Relative paths of every file the overlay owns, lowercase with forward
     * slashes.
     *
     * The install verification needs them: these files are replaced with the
     * APK's own copies after every install and launch, so their size on disk
     * says nothing about the health of the unpacked release.
     */
    public static Set<String> relativePaths(Context context) {
        final Set<String> paths = new HashSet<>();
        try {
            collectPaths(context.getAssets(), ASSET_ROOT, "", paths);
        } catch (IOException e) {
            Log.w(TAG, "Could not list the bundled configuration", e);
        }
        return paths;
    }

    private static void collectPaths(AssetManager assets, String assetPath, String relative,
            Set<String> into) throws IOException {
        final String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            return;
        }
        for (String entry : entries) {
            final String childAsset = assetPath + "/" + entry;
            final String childRelative = relative.isEmpty() ? entry : relative + "/" + entry;
            final String[] grandChildren = assets.list(childAsset);
            if (grandChildren != null && grandChildren.length > 0) {
                collectPaths(assets, childAsset, childRelative, into);
            } else {
                into.add(childRelative.toLowerCase(Locale.US));
            }
        }
    }

    private static void copyFile(AssetManager assets, String assetPath, File target)
            throws IOException {
        try (InputStream in = assets.open(assetPath);
             OutputStream out = new FileOutputStream(target)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }
}
