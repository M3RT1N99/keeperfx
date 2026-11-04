package net.keeperfx.android;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds real archives, so the tests go through the same code a download does. */
final class TestArchives {

    /** What an unpack gets told when nobody is watching and nothing is cancelled. */
    static final ArchiveUnpacker.Progress QUIET = new ArchiveUnpacker.Progress() {
        @Override
        public void onFiles(int unpacked) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    private TestArchives() {
    }

    /** Name, contents, name, contents... */
    static Map<String, String> entries(String... namesAndContents) {
        final Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < namesAndContents.length; i += 2) {
            entries.put(namesAndContents[i], namesAndContents[i + 1]);
        }
        return entries;
    }

    static File sevenZ(File file, Map<String, String> entries) throws IOException {
        try (SevenZOutputFile out = new SevenZOutputFile(file)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                final SevenZArchiveEntry archiveEntry = new SevenZArchiveEntry();
                archiveEntry.setName(entry.getKey());
                out.putArchiveEntry(archiveEntry);
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeArchiveEntry();
            }
        }
        return file;
    }

    static File zip(File file, Map<String, String> entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    static void write(File file, String contents) throws IOException {
        FileUtil.ensureDirectory(file.getParentFile());
        Files.write(file.toPath(), contents.getBytes(StandardCharsets.UTF_8));
    }

    static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
