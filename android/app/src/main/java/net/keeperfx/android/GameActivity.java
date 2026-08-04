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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

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
        // Use the whole panel, cutout included, so the surface is as large as
        // it can be and stays that size.
        getWindow().getAttributes().layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        goImmersive();
        if (new Prefs(this).isBackButtonShown()) {
            addBackButton();
        }
    }

    /**
     * Puts a back arrow over the top right corner of the game.
     *
     * The engine leaves menus on Escape, which a phone has no key for. The
     * system back gesture is mapped onto it and so is a two finger tap, but
     * neither is discoverable, and a player who cannot find the way out of a
     * menu is stuck in it. The status panel is anchored to the left, so the
     * opposite corner is the one place the button covers nothing but scenery.
     */
    private void addBackButton() {
        final float density = getResources().getDisplayMetrics().density;
        final int size = Math.round(48 * density);
        final int margin = Math.round(10 * density);
        final ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_game_back);
        button.setBackgroundResource(R.drawable.overlay_round);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setContentDescription(getString(R.string.game_back));
        // Present but not competing with the game for attention.
        button.setAlpha(0.55f);
        button.setOnClickListener(view -> sendEscape(view));
        final FrameLayout.LayoutParams params =
            new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        params.topMargin = margin;
        params.rightMargin = margin;
        // addContentView() stacks onto the window's own frame, so SDL's view
        // hierarchy is left exactly as SDLActivity built it.
        addContentView(button, params);
    }

    /**
     * Feeds the engine an Escape press.
     *
     * SDL translates the Android key code into its own, so this arrives as an
     * ordinary keyboard event and no engine code has to know about it. The
     * release is delayed because the game samples the keyboard once per frame
     * and would otherwise see a key that was never held.
     */
    private void sendEscape(View button) {
        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
        button.postDelayed(() -> SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE), 80);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // The bars come back after a system dialog or a swipe from the edge,
            // and each time they do the surface changes size under the engine.
            goImmersive();
        }
    }

    /**
     * Hides the status and navigation bars for good.
     *
     * The engine fixes its drawing surface when the video mode is set and has
     * no way to follow a later resize, so a navigation bar appearing mid game
     * shrinks the window while the engine keeps drawing at the old size and the
     * bottom of the picture is lost. A device log showed the two sizes this
     * produces, 1544x720 and 1404x664. Staying immersive keeps it at one.
     */
    private void goImmersive() {
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
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
