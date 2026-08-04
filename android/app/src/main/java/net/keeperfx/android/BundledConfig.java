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

public final class BundledConfig {

    private static final String TAG = "KeeperFX";

    /** Asset subdirectory the Gradle copy task fills from the repository's config/. */
    private static final String ASSET_ROOT = "kfx-config";

    /** Player settings; never clobbered once it exists. */
    private static final String USER_SETTINGS = "keeperfx.cfg";

    private BundledConfig() {
    }

    /**
     * Copies the bundled configuration into the installation.
     *
     * @return the number of files written.
     */
    public static int install(Context context) throws IOException {
        final File destination = GameData.gameDirectory(context);
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Cannot create " + destination.getAbsolutePath());
        }
        final int written = copyDirectory(context.getAssets(), ASSET_ROOT, destination);
        Log.i(TAG, "Applied " + written + " bundled configuration files");
        return written;
    }

    private static int copyDirectory(AssetManager assets, String assetPath, File target)
            throws IOException {
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
                written += copyDirectory(assets, childAsset, childFile);
                continue;
            }

            if (USER_SETTINGS.equalsIgnoreCase(entry) && childFile.isFile()) {
                continue; // the player's own settings win
            }
            copyFile(assets, childAsset, childFile);
            written++;
        }
        return written;
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
