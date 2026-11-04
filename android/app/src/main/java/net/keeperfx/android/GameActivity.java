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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
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

    private static final String TAG = "KeeperFX";

    /**
     * Android has no key meaning "the next tap is a right click", so the toggle
     * borrows one the game never uses. bflib_inputctrl.cpp watches for it and
     * flips the mode instead of treating it as a key press.
     *
     * It must be a key SDL actually delivers. NUM_LOCK was tried first and does
     * nothing at all: it is missing from SDL's Android keycode table, so the
     * press never reached the engine. MENU is universal on Android and the
     * engine already maps it, to a code nothing is bound to.
     */
    private static final int KEYCODE_STICKY_RIGHT_CLICK = KeyEvent.KEYCODE_MENU;

    /** Present but not competing with the game for attention. */
    private static final float IDLE_ALPHA = 0.55f;

    /** Whether the engine may be alive, paused behind the launcher, with its files open. */
    private static volatile boolean open = false;

    private String[] arguments = new String[0];
    private boolean rightClickActive = false;
    private boolean keyboardVisible = false;
    private WifiManager.MulticastLock multicastLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Android can bring this back from the recent apps without the launcher ever
        // starting, and that must not play on a swap a dead process left half done.
        PhoneTransfer.finishInterruptedImport(this);
        open = true;
        String[] extra = null;
        if (getIntent() != null) {
            extra = getIntent().getStringArrayExtra(EXTRA_ARGUMENTS);
        }
        if (extra != null) {
            arguments = extra;
        }
        // Window flags only, before super.onCreate() creates SDL's surface. The
        // engine takes its video mode from the first size it is handed and
        // never revisits it, so the bars have to be gone by the time the window
        // is measured or the whole session runs at 1404x664 on a 1544x720
        // panel. These three are plain layout parameters and need no decor
        // view; goImmersive() does, because getInsetsController() reaches
        // through it, and calling it here threw before the game ever started.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        super.onCreate(savedInstanceState);
        goImmersive();
        watchKeyboard();
        if (new Prefs(this).isBackButtonShown()) {
            addBackButton();
            addRightClickToggle();
        }
    }

    /**
     * Tells SDL when the keyboard has gone away by a route it does not hear of.
     *
     * SDL only learns of it from a Back key reaching its text field. The hide button in
     * the keyboard's own window does not come by there, and SDL would go on believing
     * text input is running and the keyboard is up: nothing could raise it again, since
     * asking SDL to start what it thinks is started does nothing. Before Android 11 that
     * button is the system's back key, which does arrive. A keyboard hidden on SDL's own
     * orders has already handed the focus back, so that case is told nothing.
     */
    private void watchKeyboard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        findViewById(android.R.id.content).setOnApplyWindowInsetsListener((view, insets) -> {
            final boolean visible = insets.isVisible(WindowInsets.Type.ime());
            if (keyboardVisible && !visible && mTextEdit != null && mTextEdit.hasFocus()) {
                SDLActivity.onNativeKeyboardFocusLost();
            }
            keyboardVisible = visible;
            return view.onApplyWindowInsets(insets);
        });
    }

    @Override
    protected void onDestroy() {
        open = false;
        super.onDestroy();
    }

    static boolean isOpen() {
        return open;
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
        button.setAlpha(IDLE_ALPHA);
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
     * Puts a right click toggle under the back arrow.
     *
     * Right click is the verb Dungeon Keeper uses most after left click, and on
     * a touch screen it is reachable by a two finger tap or by holding - both
     * of which work, and neither of which anyone finds without being told. A
     * button that stays pressed makes it visible: while it is on, an ordinary
     * tap slaps, drops and undesignates, and a drag undesignates an area.
     *
     * The state lives in the engine, not here, so the button only asks it to
     * flip and colours itself from what it asked for.
     */
    private void addRightClickToggle() {
        final float density = getResources().getDisplayMetrics().density;
        final int size = Math.round(48 * density);
        final int margin = Math.round(10 * density);
        final ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_right_click);
        button.setBackgroundResource(R.drawable.overlay_round);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setContentDescription(getString(R.string.game_right_click));
        button.setAlpha(IDLE_ALPHA);
        button.setOnClickListener(view -> {
            rightClickActive = !rightClickActive;
            view.setAlpha(rightClickActive ? 1.0f : IDLE_ALPHA);
            view.setSelected(rightClickActive);
            sendKey(view, KEYCODE_STICKY_RIGHT_CLICK);
        });
        final FrameLayout.LayoutParams params =
            new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        // Directly under the back arrow: same column, one button lower.
        params.topMargin = margin + size + margin;
        params.rightMargin = margin;
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
        sendKey(button, KeyEvent.KEYCODE_ESCAPE);
    }

    /**
     * Presses and releases one key.
     *
     * The release is delayed because the game samples the keyboard once per
     * frame and would otherwise see a key that was never held.
     */
    private void sendKey(View button, int keyCode) {
        SDLActivity.onNativeKeyDown(keyCode);
        button.postDelayed(() -> SDLActivity.onNativeKeyUp(keyCode), 80);
    }

    @Override
    protected void onResume() {
        super.onResume();
        acquireMulticastLock();
        NetworkGameService.stop(this);
    }

    @Override
    protected void onPause() {
        releaseMulticastLock();
        // Here rather than from the engine's own background event, which arrives
        // a moment later: a foreground service can only be started from the screen.
        if (!mBrokenLibraries && nativeNetworkGameActive()) {
            NetworkGameService.start(this);
        }
        super.onPause();
    }

    /** Whether other players are depending on this engine right now. */
    private static native boolean nativeNetworkGameActive();

    /**
     * Lets the device see local network game announcements.
     *
     * Games on a LAN find each other by broadcasting once a second. Wi-Fi
     * hardware drops everything that is not addressed to the
     * device while it is saving power, so without this lock the game list stays
     * empty even though both machines are on the same network. It is tied to
     * the activity being in the foreground: nobody is browsing for games behind
     * a paused activity, and a game already joined talks to its peers directly.
     */
    private void acquireMulticastLock() {
        if (multicastLock != null) {
            return;
        }
        try {
            final WifiManager wifi =
                (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return;
            }
            multicastLock = wifi.createMulticastLock("keeperfx-lan");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Exception e) {
            // A device without Wi-Fi, or a manufacturer that refuses the lock.
            // Local network play is then simply unavailable; nothing else is.
            Log.w(TAG, "Cannot hold a multicast lock: " + e);
            multicastLock = null;
        }
    }

    /**
     * Which network a local game's socket belongs on, asked by the engine when it
     * makes one (android_lan_bind_socket), and again once a second while a peer of a
     * running game is not answering (android_lan_follow_network).
     *
     * A hotspot without internet leaves the Wi-Fi unvalidated with cellular as the
     * default network, and a socket that is not told otherwise then sends its
     * traffic for the hotspot out over cellular, where it is lost. With Wi-Fi as the
     * default, or with no Wi-Fi network at all, which is what the hotspot's owner
     * sees of its own hotspot, the routing is right as it stands.
     *
     * @return the network's handle, 0 when there is no Wi-Fi network, -1 when Wi-Fi
     *     is the default network.
     */
    @SuppressWarnings("deprecation") // getAllNetworks: the callback that replaces it would
    // have to keep up with the networks all the time, for an answer that is rarely needed.
    private long lanNetworkHandle() {
        final ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        final NetworkCapabilities active =
            connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        if (active != null && active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return -1;
        }
        for (Network network : connectivity.getAllNetworks()) {
            final NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network.getNetworkHandle();
            }
        }
        return 0;
    }

    private void releaseMulticastLock() {
        if (multicastLock == null) {
            return;
        }
        try {
            multicastLock.release();
        } catch (Exception e) {
            Log.w(TAG, "Cannot release the multicast lock: " + e);
        }
        multicastLock = null;
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            hideBarsBeforeAndroid11();
            return;
        }
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /**
     * The insets controller only arrived with Android 11, and calling it on 9 or 10
     * ends the app on every Play. These flags are what the controller replaced,
     * the same set SDL uses for its own fullscreen.
     */
    @SuppressWarnings("deprecation")
    private void hideBarsBeforeAndroid11() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
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
            "SDL3",
            "SDL3_image",
            "SDL3_mixer",
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
