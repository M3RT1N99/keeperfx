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
    private static final String KEY_EXTRA_ARGS = "extra_args";
    private static final String KEY_INSTALLED_VERSION = "installed_version";
    private static final String KEY_INSTALLED_ALPHA = "installed_alpha";
    private static final String KEY_LANGUAGE = "game_language";

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

    public boolean isNoIntro() {
        return prefs.getBoolean(KEY_NO_INTRO, true);
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
