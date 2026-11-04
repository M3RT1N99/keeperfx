package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

public class GameLanguageTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aDeviceGetsItsOwnLanguage() {
        assertEquals("GER", GameLanguage.forLocale(Locale.GERMANY));
        assertEquals("JPN", GameLanguage.forLocale(Locale.JAPAN));
        assertEquals("KOR", GameLanguage.forLocale(Locale.KOREA));
        assertEquals("UKR", GameLanguage.forLocale(new Locale("uk", "UA")));
    }

    @Test
    public void chineseGoesByRegion() {
        assertEquals("CHI", GameLanguage.forLocale(Locale.SIMPLIFIED_CHINESE));
        assertEquals("CHT", GameLanguage.forLocale(Locale.TAIWAN));
        assertEquals("CHT", GameLanguage.forLocale(new Locale("zh", "HK")));
    }

    @Test
    public void norwegianIsFoundUnderEitherWrittenForm() {
        assertEquals("NOR", GameLanguage.forLocale(new Locale("nb", "NO")));
        assertEquals("NOR", GameLanguage.forLocale(new Locale("nn", "NO")));
    }

    @Test
    public void everyLanguageInTheBoxHasItsLocale() {
        final String[][] expected = {
            {"ENG", "en"}, {"GER", "de"}, {"FRE", "fr"}, {"ITA", "it"}, {"SPA", "es"}, {"DUT", "nl"},
            {"POL", "pl"}, {"SWE", "sv"}, {"CZE", "cs"}, {"RUS", "ru"}, {"UKR", "uk"}, {"HUN", "hu"},
            {"DAN", "da"}, {"NOR", "nb"}, {"POR", "pt"}, {"KOR", "ko"}, {"JPN", "ja"},
            {"CHI", "zh-CN"}, {"CHT", "zh-TW"}, {"ARA", "ar"}, {"LAT", "la"},
        };
        assertEquals(expected.length, GameLanguage.all().size());
        for (String[] row : expected) {
            assertEquals(row[0], row[1], GameLanguage.localeOf(row[0]).toLanguageTag());
        }
    }

    /** A phone set to a language gets the same box entry the launcher would then speak it from. */
    @Test
    public void aLocaleFindsItsWayBackToItsLanguage() {
        for (GameLanguage.Entry entry : GameLanguage.all()) {
            assertEquals(entry.code, GameLanguage.forLocale(GameLanguage.localeOf(entry.code)));
        }
    }

    @Test
    public void norwegianIsAlsoFoundUnderTheOldTag() {
        assertEquals("NOR", GameLanguage.forLocale(new Locale("no", "NO")));
    }

    @Test
    public void anUnknownCodeSpeaksEnglish() {
        assertEquals("en", GameLanguage.localeOf("XXX").toLanguageTag());
    }

    @Test
    public void anUnknownLanguageIsEnglish() {
        assertEquals("ENG", GameLanguage.forLocale(new Locale("sw", "KE")));
    }

    /** The engine stops at "Setting up game failed" without the text file. */
    @Test
    public void aLanguageWithoutItsTextsRunsInEnglish() throws IOException {
        final File root = folder.newFolder("keeperfx");
        TestArchives.write(new File(root, "fxdata/gtext_jpn.dat"), "x");
        assertEquals("JPN", GameLanguage.usableCode("JPN", root));
        assertEquals("ENG", GameLanguage.usableCode("HUN", root));
    }

    @Test
    public void theLanguageLineIsReplacedInPlace() {
        assertEquals(Arrays.asList("; comment", "LANGUAGE=JPN", "OTHER=1"), GameLanguage.withLanguage(
            Arrays.asList("; comment", "LANGUAGE=ENG", "OTHER=1"), "JPN"));
    }

    @Test
    public void nothingIsWrittenWhenItAlreadySaysThat() {
        assertNull(GameLanguage.withLanguage(Arrays.asList("LANGUAGE=JPN", "OTHER=1"), "JPN"));
        assertNull(GameLanguage.withLanguage(Arrays.asList("  LANGUAGE=JPN  "), "JPN"));
    }

    @Test
    public void aMissingLineIsAdded() {
        assertEquals(Arrays.asList("OTHER=1", "LANGUAGE=JPN"),
            GameLanguage.withLanguage(Arrays.asList("OTHER=1"), "JPN"));
    }
}
