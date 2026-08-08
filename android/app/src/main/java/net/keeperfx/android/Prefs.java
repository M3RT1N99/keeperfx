/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file Prefs.java
 *     Launcher settings.
 * @par Purpose:
 *     Holds the handful of options the launcher offers and turns them into the
 *     command line the engine is started with.
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
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Prefs {

    private static final String FILE = "keeperfx-launcher";

    /** Matches enum TouchControlModes in src/bflib_touch.h. */
    public static final int INPUT_AUTO = 0;
    public static final int INPUT_TOUCH = 1;
    public static final int INPUT_POINTER = 2;

    private static final String KEY_INPUT_MODE = "input_mode";
    private static final String KEY_BACK_BUTTON = "back_button";
    private static final String KEY_NO_INTRO = "no_intro";
    private static final String KEY_NO_SOUND = "no_sound";
    private static final String KEY_DRAW_FPS = "draw_fps";
    private static final String KEY_EXTRA_ARGS = "extra_args";
    private static final String KEY_INSTALLED_VERSION = "installed_version";
    private static final String KEY_INSTALLED_ALPHA = "installed_alpha";
    private static final String KEY_LANGUAGE = "game_language";
    private static final String KEY_USE_ALPHA = "use_alpha";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int getInputMode() {
        return prefs.getInt(KEY_INPUT_MODE, INPUT_AUTO);
    }

    public void setInputMode(int mode) {
        prefs.edit().putInt(KEY_INPUT_MODE, mode).apply();
    }

    /** Whether the game draws a back arrow over its top right corner. */
    public boolean isBackButtonShown() {
        return prefs.getBoolean(KEY_BACK_BUTTON, true);
    }

    public void setBackButtonShown(boolean value) {
        prefs.edit().putBoolean(KEY_BACK_BUTTON, value).apply();
    }

    /**
     * Off by default, so the game opens the way the original does.
     *
     * It used to default to on for a plain reason: the port could not play a
     * video at all, so the intro was four seconds of nothing. FFmpeg's Smacker
     * decoder is built in now, so there is no longer a reason to skip it. The
     * checkbox stays for anyone who would rather get on with it.
     */
    public boolean isNoIntro() {
        return prefs.getBoolean(KEY_NO_INTRO, false);
    }

    public void setNoIntro(boolean value) {
        prefs.edit().putBoolean(KEY_NO_INTRO, value).apply();
    }

    public boolean isNoSound() {
        return prefs.getBoolean(KEY_NO_SOUND, false);
    }

    public void setNoSound(boolean value) {
        prefs.edit().putBoolean(KEY_NO_SOUND, value).apply();
    }

    /**
     * Version of the KeeperFX release currently unpacked, or an empty string.
     * Only set by the downloader; a manually imported folder carries no version
     * we could trust, so it stays empty and update checks stay silent for it.
     */
    public String getInstalledVersion() {
        return prefs.getString(KEY_INSTALLED_VERSION, "");
    }

    public void setInstalledVersion(String version) {
        prefs.edit().putString(KEY_INSTALLED_VERSION, version == null ? "" : version).apply();
    }

    /**
     * Version of the alpha patch laid over the release, or an empty string.
     *
     * Kept apart from the release version: an alpha is a patch on top of the
     * stable release, not a replacement for it, so both are installed at once.
     */
    public String getInstalledAlphaVersion() {
        return prefs.getString(KEY_INSTALLED_ALPHA, "");
    }

    public void setInstalledAlphaVersion(String version) {
        prefs.edit().putString(KEY_INSTALLED_ALPHA, version == null ? "" : version).apply();
    }

    /**
     * Language the game runs in, as one of the engine's own codes.
     *
     * Defaults to whatever the device is set to rather than to English, so a
     * player who never opens the setting still gets their own language.
     */
    public String getLanguage() {
        return prefs.getString(KEY_LANGUAGE, GameLanguage.forDevice());
    }

    public void setLanguage(String code) {
        prefs.edit().putString(KEY_LANGUAGE, code).apply();
    }

    /**
     * Whether to lay the newest alpha patch over the stable release.
     *
     * On by default, and deliberately so: the engine in this APK is built from
     * master, and the stable release does not carry the data files it has
     * gained since - fxdata/font12.fxfont among them. Turning it off is the
     * choice to run a strictly official data set instead.
     */
    public boolean isAlphaEnabled() {
        return prefs.getBoolean(KEY_USE_ALPHA, true);
    }

    public void setAlphaEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_USE_ALPHA, value).apply();
    }

    /**
     * The frame rate the engine is asked to draw at; 0 leaves it alone.
     *
     * The engine's own default is unlimited: DELTA_TIME=ON with
     * FRAMES_PER_SECOND=0 renders as many frames as the CPU can produce, which
     * on a phone means one big core pinned flat out in the software renderer.
     * The SoC heats up, the governor pulls the clocks, and the game stutters in
     * bursts - picture and audio together, since the mixer threads starve with
     * it. 60 divides a 120 Hz panel evenly and leaves headroom; the value is
     * passed as "-fps_draw", so anything in the extra arguments field still
     * overrides it, the engine parsing the last occurrence.
     */
    public int getDrawFps() {
        return prefs.getInt(KEY_DRAW_FPS, 60);
    }

    public void setDrawFps(int fps) {
        prefs.edit().putInt(KEY_DRAW_FPS, fps).apply();
    }

    /** One knob of the engine's -touchtune startup option. */
    public static final class TouchTunable {
        public final String key;
        public final float def;
        public final float min;
        public final float max;
        public final float step;

        TouchTunable(String key, float def, float min, float max, float step) {
            this.key = key;
            this.def = def;
            this.min = min;
            this.max = max;
            this.step = step;
        }
    }

    /**
     * Mirrors the table in src/bflib_input_touch.cpp touch_tune() - the names,
     * defaults and ranges must match, or the engine warns and ignores a value.
     */
    public static final TouchTunable[] TOUCH_TUNABLES = {
        new TouchTunable("panspeed",      7.0f,   1.0f,   60.0f,  1.0f),
        new TouchTunable("longpress",   420.0f, 120.0f, 2000.0f, 10.0f),
        new TouchTunable("dragslop",     12.0f,   2.0f,   80.0f,  1.0f),
        new TouchTunable("commitpan",    20.0f,   4.0f,  200.0f,  1.0f),
        new TouchTunable("commitpinch",  34.0f,   4.0f,  200.0f,  1.0f),
        new TouchTunable("committwist",  0.30f,  0.02f,   2.0f, 0.01f),
        new TouchTunable("flickdecay",   0.90f,   0.0f,  0.99f, 0.01f),
    };

    public float getTouchTune(TouchTunable tunable) {
        return prefs.getFloat("tune_" + tunable.key, tunable.def);
    }

    public void setTouchTune(TouchTunable tunable, float value) {
        prefs.edit().putFloat("tune_" + tunable.key, value).apply();
    }

    public void resetTouchTuning() {
        final SharedPreferences.Editor editor = prefs.edit();
        for (TouchTunable tunable : TOUCH_TUNABLES) {
            editor.remove("tune_" + tunable.key);
        }
        editor.apply();
    }

    /** Formats one value the way the engine's atof() reads it back. */
    public static String formatTouchTune(TouchTunable tunable, float value) {
        if (tunable.step < 1.0f) {
            return String.format(Locale.US, "%.2f", value);
        }
        return Integer.toString(Math.round(value));
    }

    /**
     * The -touchtune value for every knob moved off its default, or null when
     * none was. Only moved knobs are passed, so engine-side default changes
     * keep reaching players who never opened the tuning screen.
     */
    private String buildTouchTuneValue() {
        final StringBuilder sb = new StringBuilder();
        for (TouchTunable tunable : TOUCH_TUNABLES) {
            final float value = getTouchTune(tunable);
            if (Math.abs(value - tunable.def) < tunable.step / 2.0f) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(tunable.key).append('=').append(formatTouchTune(tunable, value));
        }
        return (sb.length() > 0) ? sb.toString() : null;
    }

    public String getExtraArguments() {
        return prefs.getString(KEY_EXTRA_ARGS, "");
    }

    public void setExtraArguments(String value) {
        prefs.edit().putString(KEY_EXTRA_ARGS, value == null ? "" : value.trim()).apply();
    }

    private static String inputModeArgument(int mode) {
        switch (mode) {
            case INPUT_TOUCH:
                return "touch";
            case INPUT_POINTER:
                return "kbm";
            default:
                return "auto";
        }
    }

    /**
     * Builds the argument vector handed to the engine.
     *
     * argv[0] is supplied by SDL, so this is everything after it.
     */
    public String[] buildArguments(Context context) {
        final File gameDir = GameData.gameDirectory(context);
        final List<String> args = new ArrayList<>();

        // Handled by src/android.cpp: it chdir()s here and strips the option
        // before the engine's own parser sees the command line.
        args.add("-datadir");
        args.add(gameDir.getAbsolutePath());

        args.add("-inputmode");
        args.add(inputModeArgument(getInputMode()));

        if (isNoIntro()) {
            args.add("-nointro");
        }
        if (isNoSound()) {
            args.add("-nosound");
        }
        if (getDrawFps() > 0) {
            args.add("-fps_draw");
            args.add(Integer.toString(getDrawFps()));
        }
        // Before the extra arguments, so a hand-typed -touchtune still wins:
        // the engine applies the options in order and the last name counts.
        final String touchTune = buildTouchTuneValue();
        if (touchTune != null) {
            args.add("-touchtune");
            args.add(touchTune);
        }

        final String extra = getExtraArguments();
        if (!extra.isEmpty()) {
            for (String token : extra.split("\\s+")) {
                if (!token.isEmpty()) {
                    args.add(token);
                }
            }
        }
        return args.toArray(new String[0]);
    }
}
