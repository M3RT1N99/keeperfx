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
