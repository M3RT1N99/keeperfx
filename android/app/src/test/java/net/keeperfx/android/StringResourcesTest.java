package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Holds every language against the English source. The languages are whichever folders
 * exist, so a new one is checked from the commit that adds it.
 */
public class StringResourcesTest {

    /** Numbered, as "%1$s" and "%2$d" and "%1$.1f" are, so that a sentence can reorder them. */
    private static final Pattern PLACEHOLDER = Pattern.compile("%\\d+\\$(\\.\\d+)?[a-zA-Z]");
    /** A conversion with no number. A percent sign in front of a word has a space after it. */
    private static final Pattern UNNUMBERED = Pattern.compile("%(\\.\\d+)?[a-zA-Z]");

    /**
     * Languages that fall back to English for what they do not have yet. Those are still
     * held to everything else here. Empty it when they are complete, along with making
     * MissingTranslation fatal in the lint block of build.gradle.
     */
    private static final Set<String> STILL_BEING_TRANSLATED = Collections.singleton("values-de");

    private static List<String> placeholders(String text) {
        final List<String> found = new ArrayList<>();
        final Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        Collections.sort(found);
        return found;
    }

    /** What a language lacks, or has that English does not. */
    private static Set<String> difference(Set<String> names, Set<String> without) {
        final Set<String> rest = new TreeSet<>(names);
        rest.removeAll(without);
        return rest;
    }

    @Test
    public void thereIsAtLeastOneTranslationToCheck() throws Exception {
        assertFalse(StringResources.translations().isEmpty());
    }

    @Test
    public void noLanguageLacksAString() throws Exception {
        final StringResources.Language english = StringResources.english();
        for (StringResources.Language language : StringResources.translations()) {
            if (!STILL_BEING_TRANSLATED.contains(language.folder)) {
                assertEquals(language.folder + " lacks these", Collections.emptySet(),
                    difference(english.strings.keySet(), language.strings.keySet()));
            }
        }
    }

    @Test
    public void noLanguageHasAStringEnglishDoesNotHave() throws Exception {
        final StringResources.Language english = StringResources.english();
        for (StringResources.Language language : StringResources.translations()) {
            assertEquals(language.folder + " has these extra", Collections.emptySet(),
                difference(language.strings.keySet(), english.strings.keySet()));
            assertEquals(language.folder + " translates what is not to be translated",
                Collections.emptyMap(), language.notTranslatable);
        }
    }

    @Test
    public void everyLanguageHasTheSamePlaceholdersAsEnglish() throws Exception {
        final StringResources.Language english = StringResources.english();
        for (StringResources.Language language : StringResources.translations()) {
            for (Map.Entry<String, String> string : language.strings.entrySet()) {
                final String source = english.strings.get(string.getKey());
                if (source != null) {
                    assertEquals(language.folder + " " + string.getKey(),
                        placeholders(source), placeholders(string.getValue()));
                }
            }
        }
    }

    private static List<StringResources.Language> everyLanguage() throws Exception {
        final List<StringResources.Language> all = new ArrayList<>(StringResources.translations());
        all.add(StringResources.english());
        return all;
    }

    @Test
    public void placeholdersAreNumberedSoThatASentenceCanReorderThem() throws Exception {
        for (StringResources.Language language : everyLanguage()) {
            for (Map.Entry<String, String> string : language.strings.entrySet()) {
                assertFalse(language.folder + " " + string.getKey() + ": " + string.getValue(),
                    UNNUMBERED.matcher(string.getValue()).find());
            }
        }
    }

    /** The build drops a straight quote without a backslash, and with it a word's marking. */
    @Test
    public void quotesAreEscaped() throws Exception {
        final Pattern bare = Pattern.compile("(?<!\\\\)[\"']");
        for (StringResources.Language language : everyLanguage()) {
            for (Map.Entry<String, String> string : language.strings.entrySet()) {
                assertFalse(language.folder + " " + string.getKey() + ": " + string.getValue(),
                    bare.matcher(string.getValue()).find());
            }
        }
    }

    /** A line break in the file is a space on the screen. */
    @Test
    public void lineBreaksAreWrittenOut() throws Exception {
        for (StringResources.Language language : everyLanguage()) {
            for (Map.Entry<String, String> string : language.strings.entrySet()) {
                assertFalse(language.folder + " " + string.getKey(), string.getValue().contains("\n"));
            }
        }
    }

    /** Plurals and arrays would need rules of their own here. The texts are worded to need none. */
    @Test
    public void thereAreOnlyPlainStrings() throws Exception {
        for (StringResources.Language language : everyLanguage()) {
            assertEquals(language.folder, Collections.emptyList(), language.otherElements);
        }
    }

    /** Whatever nothing shows is work for twenty translators and no use to anyone. */
    @Test
    public void everyStringIsUsed() throws Exception {
        final StringBuilder sources = new StringBuilder();
        try (Stream<java.nio.file.Path> files = Files.walk(new File("src/main").toPath())) {
            for (java.nio.file.Path file : files.filter(StringResourcesTest::canUseStrings)
                    .collect(Collectors.toList())) {
                sources.append(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            }
        }
        final List<String> unused = new ArrayList<>();
        for (String name : StringResources.english().strings.keySet()) {
            if (!sources.toString().contains("R.string." + name)
                    && !sources.toString().contains("@string/" + name)) {
                unused.add(name);
            }
        }
        assertEquals(Collections.emptyList(), unused);
    }

    private static boolean canUseStrings(java.nio.file.Path file) {
        final String name = file.getFileName().toString();
        return name.endsWith(".java") || (name.endsWith(".xml") && !name.equals("strings.xml"));
    }

    /** The checks above would pass on anything if the reading went wrong. */
    @Test
    public void theFilesAreReadTheWayTheBuildReadsThem() throws Exception {
        final Map<String, String> english = StringResources.english().strings;
        assertEquals("Mouse & keyboard — ignore the touch screen",
            StringResources.unescape(english.get("input_pointer")));
        assertEquals(Arrays.asList("%1$s", "%2$s"), placeholders(english.get("progress_bytes")));
        assertEquals(Arrays.asList("%1$.1f"), placeholders(english.get("size_mb")));
        assertTrue(StringResources.unescape(english.get("updates_none")).contains(
            "current.\n\nFiles"));
        assertTrue(StringResources.unescape(english.get("transfer_settings_not_written")).contains(
            "launcher's"));
        assertEquals("Files unpacked: 3",
            StringResources.text(new Message(R.string.stage_unpacked_files, 3)));
        assertEquals("1.5 MB", StringResources.text(GameData.describeBytes(1024 * 1024 * 3 / 2)));
        assertEquals("0 B", StringResources.text(GameData.describeBytes(0)));
        assertEquals("1023 B", StringResources.text(GameData.describeBytes(1023)));
        assertEquals("1.0 KB", StringResources.text(GameData.describeBytes(1024)));
        assertEquals("2.0 GB", StringResources.text(GameData.describeBytes(2L * 1024 * 1024 * 1024)));
    }
}
