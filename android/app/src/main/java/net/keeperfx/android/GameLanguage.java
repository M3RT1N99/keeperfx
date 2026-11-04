/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file GameLanguage.java
 *     Language of the game, and the mapping from the device's own.
 * @par Purpose:
 *     The engine takes its language from LANGUAGE= in keeperfx.cfg, which has
 *     no command line equivalent, so the launcher writes it there. The default
 *     follows the language the phone is set to, which is almost always the one
 *     the player wants and saves them finding the setting at all.
 * @par Comment:
 *     Codes are the ones config_keeperfx.c accepts. Only those with a matching
 *     Android language tag are offered for automatic selection; the rest are
 *     still selectable by hand.
 *
 *     The engine refuses to start without the text file of its language, and the
 *     official data has none for several languages the engine knows: Hungarian,
 *     Danish, Norwegian and Arabic right now. So what is written is what is
 *     installed, whatever a device is set to.
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
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GameLanguage {

    private static final String TAG = "KeeperFX";

    /** One offered language: the engine's code and what to show for it. */
    public static final class Entry {
        public final String code;
        public final String label;

        Entry(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Every code config_keeperfx.c's lang_type[] accepts, with the ISO 639-1
     * tag Android reports for it. An empty tag means the language has no plain
     * equivalent to match a device against; it can still be picked by hand.
     */
    private static final String[][] LANGUAGES = {
        {"ENG", "en", "English"},
        {"GER", "de", "Deutsch"},
        {"FRE", "fr", "Français"},
        {"ITA", "it", "Italiano"},
        {"SPA", "es", "Español"},
        {"DUT", "nl", "Nederlands"},
        {"POL", "pl", "Polski"},
        {"SWE", "sv", "Svenska"},
        {"CZE", "cs", "Čeština"},
        {"RUS", "ru", "Русский"},
        {"UKR", "uk", "Українська"},
        {"HUN", "hu", "Magyar"},
        {"DAN", "da", "Dansk"},
        {"NOR", "no", "Norsk"},
        {"POR", "pt", "Português"},
        {"KOR", "ko", "한국어"},
        {"JPN", "ja", "日本語"},
        {"CHI", "zh", "简体中文"},
        {"CHT", "", "繁體中文"},
        {"ARA", "ar", "العربية"},
        {"LAT", "", "Latina"},
    };

    private GameLanguage() {
    }

    public static List<Entry> all() {
        final List<Entry> entries = new ArrayList<>();
        for (String[] row : LANGUAGES) {
            entries.add(new Entry(row[0], row[2] + "  (" + row[0] + ")"));
        }
        return entries;
    }

    public static int indexOf(String code) {
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i][0].equalsIgnoreCase(code)) {
                return i;
            }
        }
        return 0;
    }

    public static String codeAt(int index) {
        if (index < 0 || index >= LANGUAGES.length) {
            return "ENG";
        }
        return LANGUAGES[index][0];
    }

    /**
     * The engine code matching the language the device is set to.
     *
     * Traditional Chinese is picked out by region rather than by language, the
     * two Chinese variants sharing a tag.
     */
    public static String forDevice() {
        return forLocale(Locale.getDefault());
    }

    static String forLocale(Locale locale) {
        final String language = locale.getLanguage();
        // Android reports Norwegian as one of its two written forms.
        if ("nb".equals(language) || "nn".equals(language)) {
            return "NOR";
        }
        if ("zh".equals(language)) {
            final String country = locale.getCountry();
            if ("TW".equals(country) || "HK".equals(country) || "MO".equals(country)) {
                return "CHT";
            }
            return "CHI";
        }
        for (String[] row : LANGUAGES) {
            if (!row[1].isEmpty() && row[1].equals(language)) {
                return row[0];
            }
        }
        return "ENG";
    }

    /** What the engine can run in: the language asked for, or English without its texts. */
    static String usableCode(String code, File gameDirectory) {
        final String textFile = "fxdata/gtext_" + code.toLowerCase(Locale.US) + ".dat";
        return new File(gameDirectory, textFile).isFile() ? code : "ENG";
    }

    /** The name a language is listed under, for telling the player it is not there. */
    static String displayName(String code) {
        return LANGUAGES[indexOf(code)][2];
    }

    /**
     * Rewrites the LANGUAGE= line in keeperfx.cfg.
     *
     * The file is the player's own and is never replaced by the bundled
     * configuration once it exists, so this edits the line in place and leaves
     * every other setting, and the comments around them, untouched. Nothing is
     * written when the file is not there yet; the bundled copy already carries
     * a LANGUAGE line and this runs again on the next launch.
     *
     * The chosen language is written even when its campaign speech is not
     * installed: LANGUAGE= governs the menus and texts too, and the engine
     * falls back per missing speech file on its own, which beats flipping the
     * whole interface to English over some absent mp3s.
     */
    public static void applyToConfig(Context context, String code) {
        final File root = GameData.gameDirectory(context);
        final File config = new File(root, "keeperfx.cfg");
        if (!config.isFile()) {
            return;
        }
        final String usable = usableCode(code, root);
        if (!usable.equals(code)) {
            Log.w(TAG, "This installation has no texts for " + code + ", running in " + usable);
        }
        try {
            // Latin-1 reads any bytes and writes them back unchanged, and the line
            // that matters is plain ASCII. A hand imported file may be anything, and
            // failing on it would leave whichever language it happens to name.
            final List<String> lines = withLanguage(
                Files.readAllLines(config.toPath(), StandardCharsets.ISO_8859_1), usable);
            if (lines != null) {
                FileUtil.writeAtomically(config,
                    (String.join("\n", lines) + "\n").getBytes(StandardCharsets.ISO_8859_1));
                Log.i(TAG, "Game language set to " + usable);
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not set the game language", e);
        }
    }

    /** The lines with LANGUAGE= set to code, or null when they already say that. */
    static List<String> withLanguage(List<String> lines, String code) {
        final String wanted = "LANGUAGE=" + code;
        final List<String> result = new ArrayList<>(lines);
        for (int i = 0; i < result.size(); i++) {
            final String trimmed = result.get(i).trim();
            if (trimmed.toUpperCase(Locale.US).startsWith("LANGUAGE=")) {
                if (trimmed.equals(wanted)) {
                    return null;
                }
                result.set(i, wanted);
                return result;
            }
        }
        result.add(wanted);
        return result;
    }
}
