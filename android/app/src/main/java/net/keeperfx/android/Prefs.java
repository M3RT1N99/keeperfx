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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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
    /** What travels in an export apart from the language and the knobs, by how it is stored. */
    private static final List<String> TRAVELLING_INTS = Arrays.asList(KEY_INPUT_MODE, KEY_DRAW_FPS);
    private static final List<String> TRAVELLING_BOOLEANS =
        Arrays.asList(KEY_BACK_BUTTON, KEY_NO_INTRO, KEY_NO_SOUND, KEY_USE_ALPHA);

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    Prefs(SharedPreferences prefs) {
        this.prefs = prefs;
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
     * we could trust, so it stays empty and the real release keeps being offered.
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
     * Whether to lay the alpha patch for this engine over the stable release.
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
        /** What the tuning screen calls it. */
        final int label;
        final int hint;

        TouchTunable(String key, float def, float min, float max, float step, int label, int hint) {
            this.key = key;
            this.def = def;
            this.min = min;
            this.max = max;
            this.step = step;
            this.label = label;
            this.hint = hint;
        }

        boolean isDefault(float value) {
            return Math.abs(value - def) < step / 2.0f;
        }

        /** How many steps there are from one end of its range to the other. */
        int steps() {
            return Math.round((max - min) / step);
        }

        private String preference() {
            return "tune_" + key;
        }
    }

    /**
     * Mirrors the table in src/bflib_input_touch.cpp touch_tune() - the names,
     * defaults and ranges must match, or the engine warns and ignores a value.
     */
    public static final TouchTunable[] TOUCH_TUNABLES = {
        new TouchTunable("longpress",   420.0f, 120.0f, 2000.0f, 10.0f,
            R.string.tuning_longpress, R.string.tuning_longpress_hint),
        new TouchTunable("dragslop",     12.0f,   2.0f,   80.0f,  1.0f,
            R.string.tuning_dragslop, R.string.tuning_dragslop_hint),
        new TouchTunable("panslop",       1.4f,   0.2f,   10.0f,  0.1f,
            R.string.tuning_panslop, R.string.tuning_panslop_hint),
        new TouchTunable("zoomgate",      2.0f,   0.2f,   20.0f,  0.1f,
            R.string.tuning_zoomgate, R.string.tuning_zoomgate_hint),
        new TouchTunable("rotategate",   0.10f,  0.02f,    1.0f, 0.01f,
            R.string.tuning_rotategate, R.string.tuning_rotategate_hint),
        new TouchTunable("flickdecay",   0.93f,   0.0f,  0.99f, 0.01f,
            R.string.tuning_flickdecay, R.string.tuning_flickdecay_hint),
        new TouchTunable("looksens",     0.20f,  0.05f,  0.60f, 0.01f,
            R.string.tuning_looksens, R.string.tuning_looksens_hint),
        new TouchTunable("stickradius",  15.0f,   8.0f,  25.0f,  1.0f,
            R.string.tuning_stickradius, R.string.tuning_stickradius_hint),
        new TouchTunable("handhold",    250.0f, 120.0f, 1000.0f, 10.0f,
            R.string.tuning_handhold, R.string.tuning_handhold_hint),
        new TouchTunable("handrain",    100.0f,  50.0f,  500.0f, 10.0f,
            R.string.tuning_handrain, R.string.tuning_handrain_hint),
        new TouchTunable("slaprate",    150.0f, 100.0f,  500.0f, 10.0f,
            R.string.tuning_slaprate, R.string.tuning_slaprate_hint),
    };

    public float getTouchTune(TouchTunable tunable) {
        return prefs.getFloat(tunable.preference(), tunable.def);
    }

    public void setTouchTune(TouchTunable tunable, float value) {
        prefs.edit().putFloat(tunable.preference(), value).apply();
    }

    /** Everything on the tuning screen back to where it started. */
    public void resetTouchTuning() {
        final SharedPreferences.Editor editor = prefs.edit();
        for (TouchTunable tunable : TOUCH_TUNABLES) {
            editor.remove(tunable.preference());
        }
        editor.remove(KEY_DRAW_FPS);
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
            if (tunable.isDefault(value)) {
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

    // --------------------------------------------------- export and import

    /**
     * What may come along in an imported file. It may not be from the player's own
     * device, so this keeps what it knows, in the form it knows it in, and
     * drops the rest. The extra arguments stay out above all: they would hand
     * someone else's file the engine's command line.
     */
    static Map<String, String> acceptTransferred(Map<String, String> offered) {
        final Map<String, String> accepted = new TreeMap<>();
        for (Map.Entry<String, String> entry : offered.entrySet()) {
            if (isAcceptable(entry.getKey(), entry.getValue())) {
                accepted.put(entry.getKey(), entry.getValue());
            }
        }
        return accepted;
    }

    private static boolean isAcceptable(String key, String value) {
        if (TRAVELLING_BOOLEANS.contains(key)) {
            return "true".equals(value) || "false".equals(value);
        }
        switch (key) {
            case KEY_INPUT_MODE:
                return isIntBetween(value, INPUT_AUTO, INPUT_POINTER);
            case KEY_DRAW_FPS:
                return isIntBetween(value, 0, 1000);
            case KEY_LANGUAGE:
                return GameLanguage.codeAt(GameLanguage.indexOf(value)).equals(value);
            default:
                final TouchTunable tunable = tunableOf(key);
                return tunable != null && isFloatBetween(value, tunable.min, tunable.max);
        }
    }

    private static TouchTunable tunableOf(String key) {
        for (TouchTunable tunable : TOUCH_TUNABLES) {
            if (key.equals(tunable.preference())) {
                return tunable;
            }
        }
        return null;
    }

    private static boolean isIntBetween(String value, int min, int max) {
        try {
            final int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isFloatBetween(String value, float min, float max) {
        try {
            final float parsed = Float.parseFloat(value);
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The player's settings as they go into an exported file. */
    Map<String, String> transferableSettings() {
        final Map<String, String> all = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            all.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return acceptTransferred(all);
    }

    /**
     * Replaces the settings that travel and the versions the swap just put in place.
     * In one write that is on disk when this returns, since the next process has to
     * find the versions matching the files. What the other device left at its default
     * goes back to the default here too. Whatever does not travel stays this device's.
     *
     * @param accepted what acceptTransferred() let through
     * @return false when it could not be written
     */
    boolean replaceWithTransferred(Map<String, String> accepted, String release, String alpha) {
        final SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (isTransferKey(key)) {
                editor.remove(key);
            }
        }
        for (Map.Entry<String, String> entry : accepted.entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();
            if (TRAVELLING_INTS.contains(key)) {
                editor.putInt(key, Integer.parseInt(value));
            } else if (TRAVELLING_BOOLEANS.contains(key)) {
                editor.putBoolean(key, Boolean.parseBoolean(value));
            } else if (KEY_LANGUAGE.equals(key)) {
                editor.putString(key, value);
            } else {
                editor.putFloat(key, Float.parseFloat(value));
            }
        }
        editor.putString(KEY_INSTALLED_VERSION, release);
        editor.putString(KEY_INSTALLED_ALPHA, alpha);
        return editor.commit();
    }

    private static boolean isTransferKey(String key) {
        return TRAVELLING_INTS.contains(key) || TRAVELLING_BOOLEANS.contains(key)
            || KEY_LANGUAGE.equals(key) || tunableOf(key) != null;
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
