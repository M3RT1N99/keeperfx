/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file GameActivity.java
 *     SDL activity that hosts the KeeperFX engine.
 * @par Purpose:
 *     Loads the native libraries and hands the engine the command line the
 *     launcher assembled, most importantly the imported data directory and the
 *     selected input mode.
 * @author   KeeperFX Team
 * @date     04 Aug 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
package net.keeperfx.android;

import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

public class GameActivity extends SDLActivity {

    /** Extra carrying the full argv the launcher wants the engine to see. */
    public static final String EXTRA_ARGUMENTS = "net.keeperfx.android.ARGUMENTS";

    private String[] arguments = new String[0];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String[] extra = null;
        if (getIntent() != null) {
            extra = getIntent().getStringArrayExtra(EXTRA_ARGUMENTS);
        }
        if (extra != null) {
            arguments = extra;
        }
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyRenderScale();
    }

    /**
     * Renders below the panel's native resolution and lets the compositor scale
     * the result up.
     *
     * The engine draws straight into the window surface - lbScreenSurface and
     * lbDrawSurface are the same SDL surface - so it has no scaling of its own,
     * and on Android SDL always hands out the full display size. On a phone
     * that puts a interface designed around a 640x480 screen onto 1544x720
     * physical pixels, which is what makes the buttons too small to hit.
     *
     * Fixing the SurfaceView size is the Android-native answer: the surface is
     * smaller, the compositor scales it in hardware, and SDL reports the smaller
     * size, so pointer coordinates and the touch layer follow without any
     * engine change.
     */
    private void applyRenderScale() {
        final int percent = new Prefs(this).getRenderScalePercent();
        if (percent >= 100 || mSurface == null) {
            return;
        }
        final SurfaceHolder holder = mSurface.getHolder();
        if (holder == null) {
            return;
        }
        final DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        // Even dimensions keep the scaler on whole pixels.
        final int width = Math.max(640, (metrics.widthPixels * percent / 100) & ~1);
        final int height = Math.max(400, (metrics.heightPixels * percent / 100) & ~1);
        Log.i("KeeperFX", "Render scale " + percent + "%: " + width + "x" + height);
        holder.setFixedSize(width, height);
    }

    /**
     * Listed in dependency order. The dynamic linker would resolve most of
     * these on its own, but SDL expects its own library to be loaded before
     * nativeRunMain() is reached.
     */
    @Override
    protected String[] getLibraries() {
        return new String[] {
            "c++_shared",
            "SDL2",
            "SDL2_image",
            "SDL2_mixer",
            "SDL2_net",
            "openal",
            "keeperfx",
        };
    }

    @Override
    protected String[] getArguments() {
        return arguments;
    }

    @Override
    protected String getMainSharedObject() {
        return getContext().getApplicationInfo().nativeLibraryDir + "/libkeeperfx.so";
    }

    @Override
    protected String getMainFunction() {
        return "SDL_main";
    }
}
