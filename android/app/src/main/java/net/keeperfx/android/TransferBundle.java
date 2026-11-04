/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file TransferBundle.java
 *     The one file a whole installation travels to another phone in.
 * @par Purpose:
 *     Writes that file on one phone and reads it on the other. A received file is
 *     untrusted, since nothing proves it came from the player's own phone, so the
 *     reader decides on its own where each entry may go and how much of it there
 *     may be, and it writes into a staging folder only. TransferSwap is what puts
 *     the result in place.
 * @par Comment:
 *     The file is a zip read as a stream, because a share target delivers a content
 *     stream that cannot seek. Such a reader never sees the directory at the end of
 *     the archive and takes a cut between two entries for the end of the file, which
 *     is why the first entry declares how many entries and bytes must follow.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
package net.keeperfx.android;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class TransferBundle {

    /** The first entry of every bundle. A file that starts otherwise is not one. */
    static final String MARKER_ENTRY = "keeperfx-bundle.properties";
    static final int FORMAT_VERSION = 1;

    static final String STABLE_MANIFEST = "stable.txt";
    static final String ALPHA_MANIFEST = "alpha.txt";

    private static final String INSTALL_PREFIX = "install/";
    private static final String MANIFESTS_PREFIX = "state/manifests/";
    private static final String BACKUP_PREFIX = "state/alpha-backup/";
    private static final String SETTINGS_ENTRY = "state/settings.properties";

    private static final String EXPORT_SUFFIX = ".zip";
    private static final long EXPORT_LIFETIME_MS = 24L * 60 * 60 * 1000;

    private static final String SAVE_FOLDER = "save";
    /** The engine's own test, SlotFromFilename() in src/kfx/save. */
    private static final Pattern SAVED_GAME =
        Pattern.compile("fx1g[0-9]+\\.sav", Pattern.CASE_INSENSITIVE);

    /**
     * Measured on a real installation of 662 MB: stored 663 MB in 0.5 s, this 455 MB
     * in 7.5 s and level 6 another 9 MB less for 5 s more, on one desktop core. A third
     * less is worth those seconds: that much less to send and to keep on both phones.
     */
    private static final int DEFLATE_LEVEL = Deflater.BEST_SPEED;
    private static final int BUFFER_BYTES = 64 * 1024;
    /** The marker and the settings are read into memory, so a file does not get to size them. */
    private static final int MAX_SMALL_ENTRY = 64 * 1024;
    /** Far beyond any installation; only there so that a declared count means something. */
    private static final long MAX_ENTRIES = 2_000_000L;
    private static final long MAX_BYTES = 1L << 42;
    private static final Pattern PLAIN_VERSION = Pattern.compile("[0-9A-Za-z._-]{0,64}");

    private static final String NOT_A_BUNDLE =
        "This is not a KeeperFX installation from another phone. Nothing was changed.";
    private static final String DAMAGED =
        "This file is incomplete or damaged, probably cut off on its way here. "
            + "Nothing was changed. Send it again.";

    /** Why a file will not be installed, in words for the player. */
    static final class RefusedException extends IOException {
        RefusedException(String message) {
            super(message);
        }
    }

    /** The player asked to stop. */
    static final class CancelledException extends IOException {
        CancelledException() {
            super("Cancelled");
        }
    }

    /** Reports back to whoever runs the job, and lets them stop it. */
    interface Progress {
        void onBytes(long done, long total);

        boolean isCancelled();
    }

    private TransferBundle() {
    }

    // ------------------------------------------------------------------ marker

    /** What a bundle says about itself. Holds nothing about the phone it came from. */
    static final class Marker {
        final String app;
        final int engine;
        final String release;
        final String alpha;
        /** Entries after this one. */
        final long entries;
        /** Unpacked bytes of those entries. */
        final long bytes;
        final int saves;

        Marker(String app, int engine, String release, String alpha, long entries, long bytes,
                int saves) {
            this.app = app;
            this.engine = engine;
            this.release = release;
            this.alpha = alpha;
            this.entries = entries;
            this.bytes = bytes;
            this.saves = saves;
        }

        byte[] toBytes() {
            final String text = "format=" + FORMAT_VERSION + '\n'
                + "app=" + app + '\n'
                + "engine=" + engine + '\n'
                + "release=" + release + '\n'
                + "alpha=" + alpha + '\n'
                + "entries=" + entries + '\n'
                + "bytes=" + bytes + '\n'
                + "saves=" + saves + '\n';
            return text.getBytes(StandardCharsets.UTF_8);
        }

        static Marker parse(byte[] raw) throws IOException {
            final Properties values = new Properties();
            try {
                values.load(new ByteArrayInputStream(raw));
            } catch (IllegalArgumentException e) {
                throw new RefusedException(DAMAGED);
            }
            final long format = number(values, "format", Integer.MAX_VALUE);
            if (format > FORMAT_VERSION) {
                throw new RefusedException("This file was made by a newer KeeperFX app than this "
                    + "one. Update the app here first. Nothing was changed.");
            }
            if (format != FORMAT_VERSION) {
                throw new RefusedException(NOT_A_BUNDLE);
            }
            return new Marker(version(values, "app"),
                (int) number(values, "engine", Integer.MAX_VALUE),
                version(values, "release"),
                version(values, "alpha"),
                number(values, "entries", MAX_ENTRIES),
                number(values, "bytes", MAX_BYTES),
                (int) number(values, "saves", Integer.MAX_VALUE));
        }

        private static long number(Properties values, String key, long max) throws IOException {
            try {
                final long value = Long.parseLong(values.getProperty(key, "").trim());
                if (value >= 0 && value <= max) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Refused below, the same as a number out of range.
            }
            throw new RefusedException(NOT_A_BUNDLE);
        }

        /** Ends up in the launcher's records and its dialogs, so only what a version looks like. */
        private static String version(Properties values, String key) throws IOException {
            final String value = values.getProperty(key, "").trim();
            if (!PLAIN_VERSION.matcher(value).matches()) {
                throw new RefusedException(NOT_A_BUNDLE);
            }
            return value;
        }
    }

    // ------------------------------------------------------------- what goes in

    /**
     * What stays behind, as far as a look at a file can tell: what a dying process
     * left half written, a link, which could lead anywhere, and the engine's log,
     * which says nothing about the other phone. Only a log in the working directory
     * counts as that one: deeper down it is somebody's data, and a manifest that
     * lists it would then call the other phone's installation damaged.
     */
    private static boolean isLeftOut(File file, String entryPrefix) {
        final String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".part") || Files.isSymbolicLink(file.toPath())
            || (entryPrefix.equals(INSTALL_PREFIX) && name.endsWith(".log"));
    }

    /**
     * The saved games the player would recognise. The folder holds more than those,
     * among them the link to the last game played, which has the same extension.
     */
    static int countSaves(File install) {
        final String[] names = new File(install, SAVE_FOLDER).list();
        int saves = 0;
        if (names != null) {
            for (String name : names) {
                if (SAVED_GAME.matcher(name).matches()) {
                    saves++;
                }
            }
        }
        return saves;
    }

    /** A version as the marker may carry it: the receiving side refuses anything else. */
    static String plainVersion(String version) {
        final String plain = version.replaceAll("[^0-9A-Za-z._-]", "");
        return plain.substring(0, Math.min(plain.length(), 64));
    }

    /** The name the other phone sees. Says what it is before anyone opens it. */
    static String fileName(String release, String alpha) {
        final String version = plainVersion(alpha.isEmpty() ? release : alpha);
        return version.isEmpty()
            ? "KeeperFX-unofficial.zip"
            : "KeeperFX-unofficial-" + version + ".zip";
    }

    private static final class Item {
        final String entry;
        /** Null for a directory, and for the settings, which only exist in memory. */
        final File file;
        final long size;

        Item(String entry, File file, long size) {
            this.entry = entry;
            this.file = file;
            this.size = size;
        }
    }

    /** Everything that goes into one bundle, found before any of it is written. */
    static final class Plan {
        private final List<Item> items = new ArrayList<>();
        private final byte[] settings;
        private long bytes;
        private long nameBytes;
        private int files;
        int saves;

        private Plan(byte[] settings) {
            this.settings = settings;
            this.bytes = settings.length;
            add(new Item(SETTINGS_ENTRY, null, 0));
        }

        private void add(Item item) {
            items.add(item);
            bytes += item.size;
            nameBytes += item.entry.length();
            if (item.file != null) {
                files++;
            }
        }

        long entries() {
            return items.size();
        }

        int files() {
            return files;
        }

        long bytes() {
            return bytes;
        }

        /**
         * What the file can take on disk at worst: everything incompressible,
         * plus the headers each entry costs at its start, at its end and in the
         * directory, and what deflate adds to data it cannot shrink.
         */
        long fileSizeAtMost() {
            return bytes + bytes / 1000 + 2 * nameBytes + (entries() + 1) * 128 + 4096;
        }
    }

    /**
     * Walks everything that goes along.
     *
     * @param settings the launcher settings that travel, as Prefs lets them
     */
    static Plan plan(File install, File manifests, File alphaBackup,
            Map<String, String> settings) throws IOException {
        final Plan plan = new Plan(encodeSettings(settings));
        addTree(plan, install, INSTALL_PREFIX);
        for (String name : new String[] {STABLE_MANIFEST, ALPHA_MANIFEST}) {
            final File manifest = new File(manifests, name);
            if (manifest.isFile()) {
                plan.add(new Item(MANIFESTS_PREFIX + name, manifest, manifest.length()));
            }
        }
        if (alphaBackup.isDirectory()) {
            addTree(plan, alphaBackup, BACKUP_PREFIX);
        }
        plan.saves = countSaves(install);
        return plan;
    }

    private static void addTree(Plan plan, File directory, String entryPrefix) throws IOException {
        final File[] children = directory.listFiles();
        if (children == null) {
            // Leaving it out would pass for a complete bundle.
            throw new IOException("Cannot read " + directory);
        }
        Arrays.sort(children);
        for (File child : children) {
            if (isLeftOut(child, entryPrefix)) {
                continue;
            }
            final String name = entryPrefix + child.getName();
            if (child.isDirectory()) {
                // Empty ones too: B is to end up like A, and the engine does not
                // create all of its folders itself.
                plan.add(new Item(name + "/", null, 0));
                addTree(plan, child, name + "/");
            } else {
                plan.add(new Item(name, child, child.length()));
            }
        }
    }

    static byte[] encodeSettings(Map<String, String> settings) {
        final StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(settings).entrySet()) {
            text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Only ever the output of encodeSettings() on a phone that sent it, or a lie. */
    static Map<String, String> decodeSettings(byte[] raw) throws IOException {
        final Properties values = new Properties();
        try {
            values.load(new ByteArrayInputStream(raw));
        } catch (IllegalArgumentException e) {
            throw new RefusedException(DAMAGED);
        }
        final Map<String, String> settings = new TreeMap<>();
        for (String key : values.stringPropertyNames()) {
            settings.put(key, values.getProperty(key));
        }
        return settings;
    }

    // ------------------------------------------------------------------ writing

    /** Counts what passes through, and is where a cancel takes effect mid file. */
    private static final class Meter {
        private final long total;
        private final Progress progress;
        private final byte[] buffer = new byte[BUFFER_BYTES];
        private long done;
        private int reportedPercent = -1;

        Meter(long total, Progress progress) {
            this.total = total;
            this.progress = progress;
        }

        long done() {
            return done;
        }

        void checkCancelled() throws CancelledException {
            if (progress.isCancelled()) {
                throw new CancelledException();
            }
        }

        void add(long bytes) {
            done += bytes;
            // Once per percent: the launcher redraws on each of these.
            final int percent = (total > 0) ? (int) Math.min(100, done * 100 / total) : 100;
            if (percent != reportedPercent) {
                reportedPercent = percent;
                progress.onBytes(done, total);
            }
        }

        /** Copies to the end of the stream, which is also what makes a zip check its CRC. */
        long pump(InputStream in, OutputStream out, long limit, String whenOver)
                throws IOException {
            long copied = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkCancelled();
                copied += read;
                if (copied > limit) {
                    throw new RefusedException(whenOver);
                }
                out.write(buffer, 0, read);
                add(read);
            }
            return copied;
        }
    }

    /**
     * Writes the bundle. A file that changes on the way is a failure rather than a
     * bundle that contradicts its own marker.
     */
    static void write(OutputStream out, Plan plan, Marker marker, Progress progress)
            throws IOException {
        final Meter meter = new Meter(plan.bytes, progress);
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(out, BUFFER_BYTES))) {
            zip.setLevel(DEFLATE_LEVEL);
            zip.putNextEntry(new ZipEntry(MARKER_ENTRY));
            zip.write(marker.toBytes());
            zip.closeEntry();
            for (Item item : plan.items) {
                meter.checkCancelled();
                zip.putNextEntry(new ZipEntry(item.entry));
                if (item.entry.equals(SETTINGS_ENTRY)) {
                    zip.write(plan.settings);
                    meter.add(plan.settings.length);
                } else if (item.file != null) {
                    final String changed = item.entry + " changed while it was being packed";
                    final long copied;
                    try (InputStream in = new FileInputStream(item.file)) {
                        copied = meter.pump(in, zip, item.size, changed);
                    }
                    if (copied != item.size) {
                        throw new IOException(changed);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    /** The finished file waiting in the folder for a share target, or null. */
    static File finishedExport(File directory) {
        final File[] finished = directory.listFiles((dir, name) -> name.endsWith(EXPORT_SUFFIX));
        return (finished == null || finished.length == 0) ? null : finished[0];
    }

    /**
     * Packs into the folder that holds nothing but this: the room is checked before
     * anything is touched, the last file goes only then, and the new one gets its
     * name only once it is whole, so a partial file is never offered to anyone.
     */
    static File writeExport(File directory, String fileName, Plan plan, Marker marker,
            Progress progress) throws IOException {
        FileUtil.ensureDirectory(directory);
        final File previous = finishedExport(directory);
        FileUtil.requireRoom(directory, plan.fileSizeAtMost(),
            (previous == null) ? 0 : previous.length(), "packing everything");
        FileUtil.deleteContents(directory);

        final File finished = new File(directory, fileName);
        final File partial = new File(directory, fileName + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            write(out, plan, marker, progress);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw e;
        }
        if (!partial.renameTo(finished)) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new IOException("Cannot write " + finished);
        }
        return finished;
    }

    /**
     * A file of many hundreds of megabytes must not sit there forever because the
     * player forgot about it, and it cannot go right after the share sheet opens: that
     * returns at once, long before the other side has read anything. Whatever a dead
     * process left half written is of no use to anyone, whatever its age.
     *
     * @return whether a finished file was removed.
     */
    static boolean removeExpired(File directory, long now) {
        boolean removedFinished = false;
        final File[] files = directory.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            final boolean finished = file.getName().endsWith(EXPORT_SUFFIX);
            if (!finished || now - file.lastModified() > EXPORT_LIFETIME_MS) {
                removedFinished |= file.delete() && finished;
            }
        }
        return removedFinished;
    }

    // ------------------------------------------------------------------ reading

    /** Reads just far enough to say what a file holds. */
    static Marker readMarker(InputStream in) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(in, BUFFER_BYTES))) {
            return readMarkerEntry(zip);
        } catch (ZipException | EOFException e) {
            throw new RefusedException(NOT_A_BUNDLE);
        }
    }

    private static Marker readMarkerEntry(ZipInputStream zip) throws IOException {
        final ZipEntry first = zip.getNextEntry();
        if (first == null || !MARKER_ENTRY.equals(first.getName())) {
            throw new RefusedException(NOT_A_BUNDLE);
        }
        return Marker.parse(readSmallEntry(zip));
    }

    private static byte[] readSmallEntry(ZipInputStream zip) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
            if (bytes.size() > MAX_SMALL_ENTRY) {
                throw new RefusedException(NOT_A_BUNDLE);
            }
        }
        return bytes.toByteArray();
    }

    /** Where a bundle is unpacked to, a mirror of the folders it will replace. */
    static final class Staging {
        final File root;
        final File install;
        final File manifests;
        final File backup;

        Staging(File root, File liveInstall, File liveManifests, File liveBackup) {
            this.root = root;
            this.install = new File(root, liveInstall.getName());
            this.manifests = new File(root, liveManifests.getName());
            this.backup = new File(root, liveBackup.getName());
        }

        /**
         * Where an entry goes: under the installation, or to one of the few launcher
         * files that belong to it, and null for anything else in the world.
         */
        File targetFor(String entryName) throws IOException {
            if (entryName.startsWith(INSTALL_PREFIX)) {
                return ArchivePaths.resolve(install, entryName.substring(INSTALL_PREFIX.length()));
            }
            if (entryName.startsWith(BACKUP_PREFIX)) {
                return ArchivePaths.resolve(backup, entryName.substring(BACKUP_PREFIX.length()));
            }
            if (entryName.equals(MANIFESTS_PREFIX + STABLE_MANIFEST)) {
                return new File(manifests, STABLE_MANIFEST);
            }
            if (entryName.equals(MANIFESTS_PREFIX + ALPHA_MANIFEST)) {
                return new File(manifests, ALPHA_MANIFEST);
            }
            return null;
        }
    }

    /** What an unpack found, once all of it has been checked against the marker. */
    static final class Unpacked {
        final Marker marker;
        /** As the file offered them; Prefs decides what becomes of them. */
        final Map<String, String> offeredSettings;

        Unpacked(Marker marker, Map<String, String> offeredSettings) {
            this.marker = marker;
            this.offeredSettings = offeredSettings;
        }
    }

    /**
     * Unpacks into the staging folder, which the caller throws away unless this
     * returns. Everything is refused over one bad entry: no file of ours has one, so
     * whatever does is not what the marker says it is.
     */
    static Unpacked unpack(InputStream in, Staging staging, Progress progress) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(in, BUFFER_BYTES))) {
            final Marker marker = readMarkerEntry(zip);
            FileUtil.requireRoom(staging.root, marker.bytes, "unpacking this file");

            final Meter meter = new Meter(marker.bytes, progress);
            final String tooMuch = "This file holds more than it says it does, so it is not "
                + "what it claims to be. Nothing was changed.";
            Map<String, String> settings = Collections.emptyMap();
            long entries = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                meter.checkCancelled();
                if (++entries > marker.entries) {
                    throw new RefusedException(tooMuch);
                }
                final String name = entry.getName();
                if (name.equals(SETTINGS_ENTRY)) {
                    final byte[] raw = readSmallEntry(zip);
                    meter.add(raw.length);
                    settings = decodeSettings(raw);
                    continue;
                }
                final File out = staging.targetFor(name);
                if (out == null) {
                    throw new RefusedException("This file tries to write outside the game "
                        + "(" + name + "), so it will not be installed. Nothing was changed.");
                }
                if (entry.isDirectory()) {
                    FileUtil.ensureDirectory(out);
                    continue;
                }
                // Staging starts empty, so this is a name the file holds twice, and
                // the second copy would make the first one's bytes count for nothing.
                if (out.exists()) {
                    throw new RefusedException(DAMAGED);
                }
                FileUtil.ensureDirectory(out.getParentFile());
                try (OutputStream file = new FileOutputStream(out)) {
                    meter.pump(zip, file, marker.bytes - meter.done(), tooMuch);
                }
            }
            if (entries != marker.entries || meter.done() != marker.bytes) {
                throw new RefusedException(DAMAGED);
            }
            return new Unpacked(marker, settings);
        } catch (ZipException | EOFException e) {
            throw new RefusedException(DAMAGED);
        }
    }

    /**
     * Whether what was unpacked is a playable, consistent installation.
     *
     * The list of files is only held against the disk when the file comes from the
     * same app as this one. A size on it says nothing for a file the configuration
     * overlay owns, and which files those are differs from build to build, so
     * another build could be refused here for what is just the overlay of the app
     * that sent it. Its bytes have passed their checks all the same, and the regular
     * update plan looks at the list again once it is installed.
     *
     * @param overlay the files this app's configuration overlay owns, lower case
     * @param sameApp whether the file was made by this very app
     */
    static void verify(Staging staging, Marker marker, Set<String> overlay, boolean sameApp)
            throws IOException {
        if (!GameData.findMissingKeeperfxEntries(staging.install).isEmpty()
                || !GameData.findMissingOriginalDkFiles(staging.install).isEmpty()) {
            throw new RefusedException("This file does not hold a complete installation, so the "
                + "game could not start from it. Nothing was changed.");
        }
        if (countSaves(staging.install) != marker.saves) {
            throw new RefusedException(DAMAGED);
        }
        final Map<String, Long> stable =
            InstallManifest.read(new File(staging.manifests, STABLE_MANIFEST));
        final Map<String, Long> alpha =
            InstallManifest.read(new File(staging.manifests, ALPHA_MANIFEST));
        if (stable.isEmpty()) {
            // Sending is only offered for an installation that has one.
            throw new RefusedException(DAMAGED);
        }
        if (sameApp && !InstallManifest.verify(staging.install, stable, alpha, staging.backup,
                overlay).isClean()) {
            throw new RefusedException(DAMAGED);
        }
    }
}
