package net.keeperfx.android;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/** The launcher's texts as the resource files hold them, read the way the build reads them. */
final class StringResources {

    private static final File RES = new File("src/main/res");
    private static final Map<Integer, String> ENGLISH_BY_ID = new HashMap<>();

    /** Words for a message as an English phone gets them, for tests that are about the words. */
    static final Message.Strings ENGLISH = (resource, args) -> {
        final String raw = englishById().get(resource);
        if (raw == null) {
            throw new AssertionError("no string with the id " + resource);
        }
        final String text = unescape(raw);
        return (args.length == 0) ? text : String.format(Locale.US, text, args);
    };

    private StringResources() {
    }

    static String text(Message message) {
        return message.text(ENGLISH);
    }

    /** One language's file. */
    static final class Language {
        final String folder;
        /** Name to text, in the file's order, with its backslashes as they are written. */
        final Map<String, String> strings = new LinkedHashMap<>();
        /** The same, for what is marked as not to be translated. */
        final Map<String, String> notTranslatable = new LinkedHashMap<>();
        /** Anything in the file that is not a plain string. */
        final List<String> otherElements = new ArrayList<>();

        Language(String folder) {
            this.folder = folder;
        }
    }

    static Language english() throws Exception {
        return read("values");
    }

    /** Whichever languages there are folders for. */
    static List<Language> translations() throws Exception {
        final List<Language> languages = new ArrayList<>();
        for (String folder : RES.list()) {
            if (folder.startsWith("values-") && new File(RES, folder + "/strings.xml").isFile()) {
                languages.add(read(folder));
            }
        }
        return languages;
    }

    private static Language read(String folder) throws Exception {
        final Language language = new Language(folder);
        final Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new File(RES, folder + "/strings.xml")).getDocumentElement();
        final NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) {
                continue;
            }
            final Element element = (Element) children.item(i);
            if (!element.getTagName().equals("string")) {
                language.otherElements.add(element.getTagName() + " " + element.getAttribute("name"));
            } else if (element.getAttribute("translatable").equals("false")) {
                language.notTranslatable.put(element.getAttribute("name"), element.getTextContent());
            } else {
                language.strings.put(element.getAttribute("name"), element.getTextContent());
            }
        }
        return language;
    }

    /** What the build makes of a string's text: white space runs together, then the escapes. */
    static String unescape(String raw) {
        final String collapsed = raw.replaceAll("\\s+", " ").trim();
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < collapsed.length(); i++) {
            final char c = collapsed.charAt(i);
            if (c == '\\' && i + 1 < collapsed.length()) {
                i++;
                out.append(collapsed.charAt(i) == 'n' ? '\n' : collapsed.charAt(i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static synchronized Map<Integer, String> englishById() {
        if (ENGLISH_BY_ID.isEmpty()) {
            try {
                final Language english = english();
                for (Field field : R.string.class.getFields()) {
                    final String name = field.getName();
                    final String text = english.strings.containsKey(name)
                        ? english.strings.get(name) : english.notTranslatable.get(name);
                    if (text != null) {
                        ENGLISH_BY_ID.put(field.getInt(null), text);
                    }
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return ENGLISH_BY_ID;
    }
}
