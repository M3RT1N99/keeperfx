package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class TransferBundleTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** The phone that sends. */
    private TransferPhone a;
    /** The phone that receives, which has an installation of its own to lose. */
    private TransferPhone b;

    @Before
    public void twoPhones() throws IOException {
        a = new TransferPhone(folder.newFolder("a"));
        a.installEverything("A");
        b = new TransferPhone(folder.newFolder("b"));
        b.installEverything("B");
        b.prefs.setExtraArguments("-bs_own");
        b.prefs.setLanguage("FRE");
    }

    // -------------------------------------------------------- reading a bundle apart

    /** Name to bytes, in the file's order, the marker first. */
    private static Map<String, byte[]> readBundle(File bundle) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(bundle))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private File writeBundle(Map<String, byte[]> entries) throws IOException {
        final File file = folder.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    private static String markerOf(Map<String, byte[]> entries) {
        return new String(entries.get(TransferBundle.MARKER_ENTRY), StandardCharsets.UTF_8);
    }

    private static void setMarker(Map<String, byte[]> entries, String from, String to) {
        final String marker = markerOf(entries);
        assertTrue(from + " in " + marker, marker.contains(from));
        entries.put(TransferBundle.MARKER_ENTRY, marker.replace(from, to).getBytes(StandardCharsets.UTF_8));
    }

    private static long declared(Map<String, byte[]> entries, String key) {
        for (String line : markerOf(entries).split("\n")) {
            if (line.startsWith(key + "=")) {
                return Long.parseLong(line.substring(key.length() + 1));
            }
        }
        throw new AssertionError("no " + key);
    }

    /** A refused file changes nothing at all on the phone, and tidies up after itself. */
    private void assertRefused(File bundle, int expected) throws IOException {
        final Map<String, String> before = b.snapshot();
        try {
            b.importFile(bundle);
            fail("this file has to be refused: " + StringResources.text(new Message(expected, "x")));
        } catch (TransferBundle.RefusedException refused) {
            assertEquals(refused.message.toString(), expected, refused.message.resource);
        }
        assertEquals(before, b.snapshot());
    }

    /** For when what is wrong with a file depends on where the damage falls. */
    private void assertRefused(File bundle) throws IOException {
        final Map<String, String> before = b.snapshot();
        try {
            b.importFile(bundle);
            fail("this file has to be refused");
        } catch (TransferBundle.RefusedException refused) {
            assertEquals(before, b.snapshot());
        }
    }

    // -------------------------------------------------------------------- round trip

    @Test
    public void theOtherPhoneEndsUpLikeThisOne() throws IOException {
        a.prefs.setNoIntro(true);
        a.prefs.setLanguage("GER");
        a.prefs.setDrawFps(90);
        a.prefs.setInputMode(Prefs.INPUT_TOUCH);
        a.prefs.setBackButtonShown(false);
        a.prefs.setTouchTune(Prefs.TOUCH_TUNABLES[0], Prefs.TOUCH_TUNABLES[0].min);
        TestArchives.write(new File(a.install, "save/marker.txt"), "only on A");

        final TransferPhone fresh = new TransferPhone(folder.newFolder("fresh"));
        fresh.importFile(a.export());

        assertEquals(TransferPhone.tree(a.install), TransferPhone.tree(fresh.install));
        assertEquals(TransferPhone.tree(a.manifests), TransferPhone.tree(fresh.manifests));
        assertEquals(TransferPhone.tree(a.backup), TransferPhone.tree(fresh.backup));
        assertEquals(a.prefs.getInstalledVersion(), fresh.prefs.getInstalledVersion());
        assertEquals(a.prefs.getInstalledAlphaVersion(), fresh.prefs.getInstalledAlphaVersion());
        assertTrue(fresh.prefs.isNoIntro());
        assertEquals("GER", fresh.prefs.getLanguage());
        assertEquals(90, fresh.prefs.getDrawFps());
        assertEquals(Prefs.INPUT_TOUCH, fresh.prefs.getInputMode());
        assertFalse(fresh.prefs.isBackButtonShown());
        assertEquals(Prefs.TOUCH_TUNABLES[0].min, fresh.prefs.getTouchTune(Prefs.TOUCH_TUNABLES[0]), 0.0f);
        assertFalse("the work has to be cleaned up", fresh.work.exists());
        assertFalse(fresh.trash.exists());
    }

    /** Past this, the launcher would not call it installed. */
    @Test
    public void whatArrivesIsAPlayableVerifiedInstallation() throws IOException {
        final TransferPhone fresh = new TransferPhone(folder.newFolder("fresh"));
        fresh.importFile(a.export());
        assertEquals(Collections.emptyList(), GameData.findMissingKeeperfxEntries(fresh.install));
        assertEquals(Collections.emptyList(), GameData.findMissingOriginalDkFiles(fresh.install));
        assertTrue(InstallManifest.verify(fresh.install,
            InstallManifest.read(new File(fresh.manifests, TransferBundle.STABLE_MANIFEST)),
            InstallManifest.read(new File(fresh.manifests, TransferBundle.ALPHA_MANIFEST)),
            fresh.backup, Collections.<String>emptySet()).isClean());
    }

    @Test
    public void itReplacesWhatWasThereRatherThanMergingWithIt() throws IOException {
        TestArchives.write(new File(b.install, "save/fx1g0007.sav"), "a save only B has");
        TestArchives.write(new File(b.install, "fxdata/only_on_b.cfg"), "x");
        b.importFile(a.export());
        assertEquals(TransferPhone.tree(a.install), TransferPhone.tree(b.install));
    }

    @Test
    public void takingOffTheAlphaStillWorksOnTheOtherPhone() throws IOException {
        final TransferPhone fresh = new TransferPhone(folder.newFolder("fresh"));
        fresh.importFile(a.export());
        new AlphaBackup(fresh.install, fresh.backup).restore();
        assertEquals("A the release's version", TestArchives.read(new File(fresh.install, "fxdata/patched.cfg")));
        assertFalse(new File(fresh.install, "fxdata/font12.fxfont").exists());
        assertFalse(fresh.backup.exists());
    }

    @Test
    public void withoutAnAlphaThereIsNoBackupLeftAndNoEmptyFolderInItsPlace() throws IOException {
        FileUtil.deleteTree(a.backup);
        //noinspection ResultOfMethodCallIgnored
        new File(a.manifests, TransferBundle.ALPHA_MANIFEST).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(a.install, "fxdata/font12.fxfont").delete();
        a.prefs.setInstalledAlphaVersion("");
        b.importFile(a.export());
        assertFalse("B's backup would restore pieces of B's old patch", b.backup.exists());
        assertFalse(new File(b.manifests, TransferBundle.ALPHA_MANIFEST).exists());
        assertEquals("", b.prefs.getInstalledAlphaVersion());
    }

    @Test
    public void whatDescribesTheOldInstallationGoesWithIt() throws IOException {
        for (File stale : new File[] {b.alphaCache, b.downloads, b.updates, b.exports}) {
            TestArchives.write(new File(stale, "something"), "x");
        }
        b.importFile(a.export());
        for (File stale : new File[] {b.alphaCache, b.downloads, b.updates, b.exports}) {
            assertFalse(stale.getName(), stale.exists());
        }
    }

    // ----------------------------------------------------------------- what stays out

    @Test
    public void onlyThisGoesIntoTheFile() throws IOException {
        TestArchives.write(new File(a.install, "keeperfx.log"), "the log");
        TestArchives.write(new File(a.install, "KEEPERFX.LOG"), "the log in capitals");
        TestArchives.write(new File(a.install, "fxdata/half.cfg.part"), "half a file");
        TestArchives.write(new File(a.backup, "fxdata/half.cfg.part"), "half a file");
        TestArchives.write(new File(a.manifests, "install-in-progress"), "");
        TestArchives.write(new File(a.alphaCache, "alpha-1.4.0.5591.7z"), "an archive");
        TestArchives.write(new File(a.downloads, "keeperfx-release-1.4.0.7z.part"), "an archive");
        TestArchives.write(new File(a.updates, "keeperfx-1.60.apk"), "an apk");
        TestArchives.write(new File(a.files, "profileInstalled"), "device bound");
        Files.createSymbolicLink(new File(a.install, "link").toPath(), a.manifests.toPath());

        final Set<String> names = readBundle(a.export()).keySet();

        final Set<String> expected = new HashSet<>();
        expected.add(TransferBundle.MARKER_ENTRY);
        expected.add("state/settings.properties");
        expected.add("state/manifests/stable.txt");
        expected.add("state/manifests/alpha.txt");
        for (String path : TransferPhone.tree(a.backup).keySet()) {
            if (!path.endsWith(".part")) {
                expected.add("state/alpha-backup/" + path);
            }
        }
        for (String path : TransferPhone.tree(a.install).keySet()) {
            final String lower = path.toLowerCase();
            if (!lower.endsWith(".part") && !lower.equals("keeperfx.log") && !path.startsWith("link")) {
                expected.add("install/" + path);
            }
        }
        // Sorted, so a failure can be read.
        assertEquals(new TreeSet<>(expected), new TreeSet<>(names));
    }

    /** A manifest can list such a file, and then the other phone would call itself damaged. */
    @Test
    public void onlyTheLogInTheWorkingDirectoryIsTheEnginesLog() throws IOException {
        TestArchives.write(new File(a.install, "mods/mine/notes.log"), "a player's data");
        assertTrue(readBundle(a.export()).containsKey("install/mods/mine/notes.log"));
    }

    @Test
    public void aFolderWithNothingInItComesAlong() throws IOException {
        final TransferPhone fresh = new TransferPhone(folder.newFolder("fresh"));
        fresh.importFile(a.export());
        assertTrue(new File(fresh.install, "replays").isDirectory());
    }

    @Test
    public void theMarkerSaysWhatItShouldAndNothingElse() throws IOException {
        final Map<String, byte[]> entries = readBundle(a.export());
        assertEquals("the marker comes first", TransferBundle.MARKER_ENTRY, entries.keySet().iterator().next());
        final Set<String> keys = new HashSet<>();
        for (String line : markerOf(entries).split("\n")) {
            keys.add(line.substring(0, line.indexOf('=')));
        }
        assertEquals(new HashSet<>(Arrays.asList("format", "app", "engine", "release", "alpha",
            "entries", "bytes", "saves")), keys);
        assertEquals(entries.size() - 1, declared(entries, "entries"));
        long bytes = 0;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().equals(TransferBundle.MARKER_ENTRY)) {
                bytes += entry.getValue().length;
            }
        }
        assertEquals(bytes, declared(entries, "bytes"));
        assertEquals(2, declared(entries, "saves"));
    }

    /** The link to the last game has the same extension, and would make one save read as two. */
    @Test
    public void onlySavedGamesCountAsSaves() throws IOException {
        TestArchives.write(new File(a.install, "save/FX1G0002.SAV"), "from a folder in capitals");
        TestArchives.write(new File(a.install, "save/fx1contn.sav"), "the oldest progress file");
        TestArchives.write(new File(a.install, "save/fx1gone.sav"), "no slot number");
        assertEquals(3, TransferBundle.countSaves(a.install));
        assertEquals(0, TransferBundle.countSaves(new File(a.files, "nothing")));
    }

    @Test
    public void onlyTheMarkerHasToBeReadToSayWhatAFileHolds() throws IOException {
        final byte[] bytes = Files.readAllBytes(a.export().toPath());
        final TransferBundle.Marker marker = TransferBundle.readMarker(
            new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length / 4)));
        assertEquals("1.4.0", marker.release);
        assertEquals("1.4.0.5591", marker.alpha);
        assertEquals(TransferPhone.APP, marker.app);
        assertEquals(5591, marker.engine);
        assertEquals(2, marker.saves);
    }

    @Test
    public void theNameSaysItIsUnofficial() {
        assertEquals("KeeperFX-unofficial-1.4.0.5591.zip", TransferBundle.fileName("1.4.0", "1.4.0.5591"));
        assertEquals("KeeperFX-unofficial-1.4.0.zip", TransferBundle.fileName("1.4.0", ""));
        assertEquals("KeeperFX-unofficial.zip", TransferBundle.fileName("", ""));
        assertEquals("no way out of the folder", "KeeperFX-unofficial-..evil.zip",
            TransferBundle.fileName("../e\nv il", ""));
    }

    // ------------------------------------------------------------ settings allow-list

    @Test
    public void theExtraArgumentsNeverLeaveThePhone() throws IOException {
        a.prefs.setExtraArguments("-secret");
        final String settings = new String(
            readBundle(a.export()).get("state/settings.properties"), StandardCharsets.UTF_8);
        assertFalse(settings, settings.contains("extra_args"));
        assertFalse(settings, settings.contains("-secret"));
    }

    /** The other half of it: a file made by anything but this launcher can say anything. */
    @Test
    public void aFileCannotSetTheExtraArgumentsOrAnythingElseUnknown() throws IOException {
        final Map<String, byte[]> entries = readBundle(a.export());
        final String settings = new String(entries.get("state/settings.properties"), StandardCharsets.UTF_8)
            + "extra_args=-evil\ninstalled_version=9.9\nunknown_key=1\ntune_nosuchknob=1\n";
        final long grown = settings.length() - entries.get("state/settings.properties").length;
        entries.put("state/settings.properties", settings.getBytes(StandardCharsets.UTF_8));
        setMarker(entries, "bytes=" + declared(entries, "bytes"), "bytes=" + (declared(entries, "bytes") + grown));

        b.importFile(writeBundle(entries));

        assertEquals("what is on this phone stays", "-bs_own", b.prefs.getExtraArguments());
        assertEquals("the versions come from the marker", "1.4.0", b.prefs.getInstalledVersion());
        assertFalse(b.preferences.contains("unknown_key"));
        assertFalse(b.preferences.contains("tune_nosuchknob"));
    }

    @Test
    public void onlyValuesThatMakeSenseAreAccepted() {
        final Map<String, String> offered = new TreeMap<>();
        offered.put("input_mode", "2");
        offered.put("back_button", "false");
        offered.put("draw_fps", "120");
        offered.put("game_language", "GER");
        offered.put("no_intro", "true");
        offered.put("no_sound", "false");
        offered.put("use_alpha", "true");
        final Prefs.TouchTunable knob = Prefs.TOUCH_TUNABLES[0];
        final String knobKey = "tune_" + knob.key;
        offered.put(knobKey, Float.toString(knob.max));
        final Map<String, String> good = new TreeMap<>(offered);

        // The bad twins: every one of these reaches a command line, a config file or a switch.
        offered.put("extra_args", "-evil");
        offered.put("installed_version", "9.9");
        offered.put("installed_alpha", "9.9");
        offered.put("tune_nosuchknob", "1");
        assertEquals(good, Prefs.acceptTransferred(offered));

        for (String[] bad : new String[][] {
            {"input_mode", "3"}, {"input_mode", "-1"}, {"input_mode", "x"},
            {"back_button", "1"}, {"no_intro", "yes"}, {"no_sound", ""}, {"use_alpha", "TRUE"},
            {"draw_fps", "1001"}, {"draw_fps", "-1"}, {"draw_fps", "60 -evil"},
            // A line break here would write a line of someone else's into keeperfx.cfg.
            {"game_language", "GER\nINSTALL_PATH=/"}, {"game_language", "XXX"}, {"game_language", "ger"},
            {knobKey, "NaN"}, {knobKey, "Infinity"}, {knobKey, "abc"},
            {knobKey, Float.toString(knob.max + 1)}, {knobKey, Float.toString(knob.min - 1)},
        }) {
            assertEquals(bad[0] + "=" + bad[1], Collections.emptyMap(),
                Prefs.acceptTransferred(Collections.singletonMap(bad[0], bad[1])));
        }
    }

    @Test
    public void whatTheSenderLeftAtItsDefaultGoesBackToTheDefaultHere() throws IOException {
        b.prefs.setNoIntro(true);
        b.prefs.setTouchTune(Prefs.TOUCH_TUNABLES[0], Prefs.TOUCH_TUNABLES[0].max);
        b.importFile(a.export());
        assertFalse(b.prefs.isNoIntro());
        assertEquals(Prefs.TOUCH_TUNABLES[0].def, b.prefs.getTouchTune(Prefs.TOUCH_TUNABLES[0]), 0.0f);
    }

    // ------------------------------------------------------- state files, and traversal

    @Test
    public void nothingCanBeWrittenOutsideTheAllowedPlaces() throws IOException {
        final String[] names = {
            "../evil",
            "../../evil",
            "/evil",
            "C:\\evil",
            "evil",
            "shared_prefs/keeperfx-launcher.xml",
            "manifests/stable.txt",
            "install/../evil",
            "install/../../evil",
            "install/..\\..\\evil",
            "install/fxdata/../../../evil",
            "install\\..\\..\\evil",
            "state/../evil",
            "state/evil",
            "state/manifests/evil.txt",
            "state/manifests/install-in-progress",
            "state/manifests/../../evil",
            "state/manifests/stable.txt/../../evil",
            "state/alpha-backup/../evil",
            "state/alpha-backup/../../evil",
            "state/shared_prefs/keeperfx-launcher.xml",
            "../shared_prefs/keeperfx-launcher.xml",
            "install/",
            "state/alpha-backup/",
        };
        for (String name : names) {
            final Map<String, byte[]> entries = readBundle(a.export());
            entries.put(name, new byte[0]);
            setMarker(entries, "entries=" + declared(entries, "entries"), "entries=" + (declared(entries, "entries") + 1));
            assertRefused(writeBundle(entries), R.string.transfer_outside_game);
            assertFalse(name, new File(b.files, "evil").exists());
            assertFalse(name, new File(b.files.getParentFile(), "evil").exists());
            assertFalse(name, new File(folder.getRoot().getParentFile(), "evil").exists());
        }
    }

    @Test
    public void onlyTheNamedStateFilesAreAllowedAtAll() throws IOException {
        final TransferBundle.Staging staging = new TransferPhone(folder.newFolder("s")).swap().staging;
        assertEquals(new File(staging.manifests, "stable.txt"), staging.targetFor("state/manifests/stable.txt"));
        assertEquals(new File(staging.manifests, "alpha.txt"), staging.targetFor("state/manifests/alpha.txt"));
        assertEquals(new File(staging.backup, "added.txt"), staging.targetFor("state/alpha-backup/added.txt"));
        assertEquals(new File(staging.backup, "fxdata/a.cfg"), staging.targetFor("state/alpha-backup/fxdata/a.cfg"));
        assertEquals(new File(staging.install, "save/x.sav"), staging.targetFor("install/save/x.sav"));
        assertNull(staging.targetFor("state/manifests/other.txt"));
        assertNull(staging.targetFor("state/prefs.xml"));
    }

    // -------------------------------------------------------------- not one of ours

    @Test
    public void somethingThatIsNotAZipIsRefused() throws IOException {
        final File html = folder.newFile();
        TestArchives.write(html, "<html>503 Service Unavailable</html>");
        assertRefused(html, R.string.transfer_not_a_bundle);
        assertRefused(folder.newFile(), R.string.transfer_not_a_bundle);
    }

    @Test
    public void anUnrelatedZipIsRefused() throws IOException {
        assertRefused(TestArchives.zip(folder.newFile("holiday.zip"), TestArchives.entries(
            "photo.jpg", "x", "install/keeperfx.cfg", "x")), R.string.transfer_not_a_bundle);
    }

    @Test
    public void theMarkerHasToComeFirst() throws IOException {
        final Map<String, byte[]> entries = readBundle(a.export());
        final byte[] marker = entries.remove(TransferBundle.MARKER_ENTRY);
        entries.put(TransferBundle.MARKER_ENTRY, marker);
        assertRefused(writeBundle(entries), R.string.transfer_not_a_bundle);
    }

    @Test
    public void aMarkerFromTheFutureAsksForAnUpdate() throws IOException {
        final Map<String, byte[]> entries = readBundle(a.export());
        setMarker(entries, "format=1", "format=2");
        assertRefused(writeBundle(entries), R.string.transfer_from_newer_app);
    }

    @Test
    public void aMarkerThatMakesNoSenseIsRefused() throws IOException {
        for (String[] change : new String[][] {
            {"format=1", "format=0"},
            {"format=1", "format=x"},
            {"format=1", "nothing=1"},
            {"entries=", "entries=-"},
            {"entries=", "entries=99999999"},
            {"entries=", "entries=x"},
            {"bytes=", "bytes=-"},
            {"bytes=", "bytes=99999999999999"},
            {"saves=", "saves=-"},
            {"release=1.4.0", "release=1.4.0\\u000aevil=1 x"},
        }) {
            final Map<String, byte[]> entries = readBundle(a.export());
            setMarker(entries, change[0], change[1]);
            assertRefused(writeBundle(entries), R.string.transfer_not_a_bundle);
        }
    }

    @Test
    public void aMarkerIsNotAllowedToBeHuge() throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(TransferBundle.MARKER_ENTRY, new byte[1024 * 1024]);
        assertRefused(writeBundle(entries), R.string.transfer_not_a_bundle);
    }

    // ------------------------------------------------------- not what it says it is

    /** Reading a stream gives no other sign of a file cut off between two entries. */
    @Test
    public void aFileCutOffAnywhereIsRefused() throws IOException {
        final File whole = a.export();
        final byte[] bytes = Files.readAllBytes(whole.toPath());
        final int directory = indexOfCentralDirectory(bytes);
        assertTrue(directory > 0);
        for (int cut = 0; cut < directory; cut += 37) {
            final File torn = folder.newFile();
            Files.write(torn.toPath(), Arrays.copyOf(bytes, cut));
            final Map<String, String> before = b.snapshot();
            try {
                b.importFile(torn);
                fail("cut at " + cut + " of " + bytes.length);
            } catch (TransferBundle.RefusedException expected) {
                assertEquals("cut at " + cut, before, b.snapshot());
            }
            //noinspection ResultOfMethodCallIgnored
            torn.delete();
        }
    }

    private static int indexOfCentralDirectory(byte[] bytes) {
        return indexOf(bytes, new byte[] {'P', 'K', 1, 2});
    }

    private static int indexOf(byte[] bytes, byte[] wanted) {
        for (int i = 0; i + wanted.length <= bytes.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + wanted.length), wanted)) {
                return i;
            }
        }
        throw new AssertionError("not in the file");
    }

    /** All the entries have arrived with their checksums, which is all a stream can ask for. */
    @Test
    public void aFileThatLostOnlyItsDirectoryIsStillWhole() throws IOException {
        final byte[] bytes = Files.readAllBytes(a.export().toPath());
        final File cut = folder.newFile();
        Files.write(cut.toPath(), Arrays.copyOf(bytes, indexOfCentralDirectory(bytes)));
        b.importFile(cut);
        assertEquals(TransferPhone.tree(a.install), TransferPhone.tree(b.install));
    }

    /** In the data of an entry: the rest of a local header, its date say, no checksum covers. */
    @Test
    public void aFlippedByteIsCaught() throws IOException {
        final byte[] bytes = Files.readAllBytes(a.export().toPath());
        for (String name : new String[] {
                "install/keeperfx.cfg", "install/save/fx1g0000.sav", "install/data/bluepal.dat",
                "state/manifests/stable.txt", "state/alpha-backup/added.txt"}) {
            final byte[] damaged = bytes.clone();
            damaged[indexOf(bytes, name.getBytes(StandardCharsets.UTF_8)) + name.length() + 2] ^= 0x40;
            final File file = folder.newFile();
            Files.write(file.toPath(), damaged);
            assertRefused(file);
        }
    }

    @Test
    public void entriesMissingFromTheFileAreCaught() throws IOException {
        final Map<String, byte[]> entries = readBundle(a.export());
        assertTrue(entries.remove("install/save/settings.toml") != null);
        assertRefused(writeBundle(entries), R.string.transfer_damaged);
    }

    @Test
    public void aDeclaredCountThatDoesNotMatchIsCaught() throws IOException {
        final Map<String, byte[]> tooMany = readBundle(a.export());
        setMarker(tooMany, "entries=" + declared(tooMany, "entries"), "entries=" + (declared(tooMany, "entries") + 1));
        assertRefused(writeBundle(tooMany), R.string.transfer_damaged);

        final Map<String, byte[]> tooFew = readBundle(a.export());
        setMarker(tooFew, "entries=" + declared(tooFew, "entries"), "entries=" + (declared(tooFew, "entries") - 1));
        assertRefused(writeBundle(tooFew), R.string.transfer_holds_more);
    }

    @Test
    public void declaredBytesThatDoNotMatchAreCaught() throws IOException {
        final Map<String, byte[]> tooMany = readBundle(a.export());
        setMarker(tooMany, "bytes=" + declared(tooMany, "bytes"), "bytes=" + (declared(tooMany, "bytes") + 1));
        assertRefused(writeBundle(tooMany), R.string.transfer_damaged);

        final Map<String, byte[]> tooFew = readBundle(a.export());
        setMarker(tooFew, "bytes=" + declared(tooFew, "bytes"), "bytes=" + (declared(tooFew, "bytes") - 1));
        assertRefused(writeBundle(tooFew), R.string.transfer_holds_more);
    }

    /** The whole point of the check: it stops writing, rather than noticing at the end. */
    @Test
    public void moreBytesThanDeclaredStopsTheUnpackThere() throws IOException {
        final Map<String, byte[]> entries = readBundle(a.export());
        final long declaredBytes = declared(entries, "bytes");
        // What a zip bomb looks like from here: little in the file, a lot coming out.
        entries.put("install/fxdata/bomb.dat", new byte[32 * 1024 * 1024]);
        setMarker(entries, "entries=" + declared(entries, "entries"), "entries=" + (declared(entries, "entries") + 1));
        final File bundle = writeBundle(entries);
        assertTrue(bundle.length() < 1024 * 1024);

        final AtomicLong furthest = new AtomicLong();
        final Map<String, String> before = b.snapshot();
        try {
            b.importFile(bundle, new TestProgress() {
                @Override
                public void onBytes(long done, long total) {
                    furthest.set(Math.max(furthest.get(), done));
                }
            });
            fail("more than it declares");
        } catch (TransferBundle.RefusedException expected) {
            assertEquals(R.string.transfer_holds_more, expected.message.resource);
        }
        assertTrue(furthest.get() + " of " + declaredBytes, furthest.get() <= declaredBytes);
        assertEquals(before, b.snapshot());
    }

    /** ZipOutputStream will not write a name twice, so this one comes out of the bytes. */
    @Test
    public void theSameNameTwiceIsCaught() throws IOException {
        final byte[] bytes = Files.readAllBytes(a.export().toPath());
        final byte[] other = "install/sound/atmos2.sbk".getBytes(StandardCharsets.UTF_8);
        final byte[] taken = "install/sound/atmos1.sbk".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(taken, 0, bytes, indexOf(bytes, other), other.length);
        final File twice = folder.newFile();
        Files.write(twice.toPath(), bytes);
        assertRefused(twice, R.string.transfer_damaged);
    }

    @Test
    public void aSaveCountThatDoesNotMatchIsCaught() throws IOException {
        final Map<String, byte[]> entries = readBundle(a.export());
        setMarker(entries, "saves=2", "saves=7");
        assertRefused(writeBundle(entries), R.string.transfer_damaged);
    }

    @Test
    public void anIncompleteInstallationIsRefused() throws IOException {
        //noinspection ResultOfMethodCallIgnored
        new File(a.install, "data/bluepal.dat").delete();
        assertRefused(a.export(), R.string.transfer_not_complete);
    }

    @Test
    public void noListOfFilesIsRefused() throws IOException {
        //noinspection ResultOfMethodCallIgnored
        new File(a.manifests, TransferBundle.STABLE_MANIFEST).delete();
        assertRefused(a.export(), R.string.transfer_damaged);
    }

    @Test
    public void aListOfFilesThatContradictsThemIsRefusedFromThisApp() throws IOException {
        TestArchives.write(new File(a.manifests, TransferBundle.STABLE_MANIFEST),
            "1\tkeeperfx.cfg\n1\tldata/intromix.smk\n");
        assertRefused(a.export(), R.string.transfer_damaged);
    }

    /**
     * Another app's overlay can have replaced a file this app has no reason to expect
     * another size of, and that must not stand between a player and their game.
     */
    @Test
    public void anotherBuildIsNotRefusedOverItsOverlay() throws IOException {
        TestArchives.write(new File(a.manifests, TransferBundle.STABLE_MANIFEST),
            "1\tkeeperfx.cfg\n1\tldata/intromix.smk\n");
        final File bundle = a.export();
        final TransferPhone fresh = new TransferPhone(folder.newFolder("fresh"));
        try (InputStream in = new FileInputStream(bundle)) {
            fresh.swap().install(in, Collections.<String>emptySet(), "another app", TestProgress.QUIET);
        }
        assertEquals(TransferPhone.tree(a.install), TransferPhone.tree(fresh.install));
    }

    @Test
    public void asMuchAsADiskCanHoldIsNoReasonToFillIt() throws IOException {
        final Map<String, byte[]> entries = readBundle(a.export());
        setMarker(entries, "bytes=" + declared(entries, "bytes"), "bytes=" + (1L << 42));
        final Map<String, String> before = b.snapshot();
        try {
            b.importFile(writeBundle(entries));
            fail("no test machine has 4 TB free, I hope");
        } catch (FileUtil.NoRoomException expected) {
            final String text = StringResources.text(expected.message);
            assertTrue(text, text.contains("unpacking this file needs 4096.0 GB"));
            assertTrue(text, text.contains("Free now: "));
        }
        assertEquals(before, b.snapshot());
    }

    // ------------------------------------------------------------------------ cancel

    @Test
    public void aCancelAtAnyPointChangesNothing() throws IOException {
        final File bundle = a.export();
        final Map<String, String> before = b.snapshot();
        for (int after : new int[] {0, 1, 5, 20, 40}) {
            final AtomicInteger asked = new AtomicInteger();
            try {
                b.importFile(bundle, new TestProgress() {
                    @Override
                    public boolean isCancelled() {
                        return asked.incrementAndGet() > after;
                    }
                });
                fail("cancelled after " + after);
            } catch (TransferBundle.CancelledException expected) {
                assertEquals("cancelled after " + after, before, b.snapshot());
            }
        }
    }

    /** The cancel that comes in during the last check, when all the bytes are through. */
    @Test
    public void aCancelBeforeTheSwapStillCounts() throws IOException {
        final File bundle = a.export();
        final Map<String, String> before = b.snapshot();
        final AtomicInteger total = new AtomicInteger();
        b.importFile(bundle, new TestProgress() {
            @Override
            public boolean isCancelled() {
                total.incrementAndGet();
                return false;
            }
        });
        // Back to where it was, then again up to and including the very last question.
        final TransferPhone again = new TransferPhone(folder.newFolder("again"));
        again.installEverything("B");
        again.prefs.setExtraArguments("-bs_own");
        again.prefs.setLanguage("FRE");
        assertEquals(before, again.snapshot());
        final AtomicInteger asked = new AtomicInteger();
        try {
            again.importFile(bundle, new TestProgress() {
                @Override
                public boolean isCancelled() {
                    return asked.incrementAndGet() >= total.get();
                }
            });
            fail("the last question was answered with a cancel");
        } catch (TransferBundle.CancelledException expected) {
            assertEquals(before, again.snapshot());
        }
    }

    @Test
    public void aCancelledExportLeavesNothingBehind() throws IOException {
        try {
            a.export(new TestProgress() {
                @Override
                public boolean isCancelled() {
                    return true;
                }
            });
            fail("cancelled");
        } catch (TransferBundle.CancelledException expected) {
            assertEquals(Collections.emptyList(), Arrays.asList(a.exports.list()));
        }
    }

    // ---------------------------------------------------------------- the export file

    @Test
    public void aPartialFileIsNeverOffered() throws IOException {
        final File finished = a.export();
        final AtomicInteger checks = new AtomicInteger();
        a.export(new TestProgress() {
            @Override
            public void onBytes(long done, long total) {
                checks.incrementAndGet();
                assertNull("nothing finished while packing, and the previous one is gone",
                    TransferBundle.finishedExport(a.exports));
                assertTrue(new File(a.exports, finished.getName() + ".part").isFile());
            }
        });
        assertTrue(checks.get() > 0);
        assertEquals(finished, TransferBundle.finishedExport(a.exports));
        assertEquals(Collections.singletonList(finished.getName()), Arrays.asList(a.exports.list()));
    }

    @Test
    public void aNewExportReplacesTheLast() throws IOException {
        a.export();
        a.prefs.setInstalledAlphaVersion("");
        final File second = a.export();
        assertEquals("KeeperFX-unofficial-1.4.0.zip", second.getName());
        assertEquals(Collections.singletonList(second.getName()), Arrays.asList(a.exports.list()));
    }

    @Test
    public void whatItReplacesCountsAsFreeRoom() {
        final File directory = a.files;
        final long more = 1024L * 1024 * 1024 * 1024;
        final long needed = directory.getUsableSpace() + more;
        try {
            FileUtil.requireRoom(directory, needed, 0, R.string.no_room_export);
            fail("that much is not free");
        } catch (FileUtil.NoRoomException expected) {
            assertEquals(R.string.no_room_export, expected.message.resource);
        }
        try {
            FileUtil.requireRoom(directory, needed, 2 * more, R.string.no_room_export);
        } catch (FileUtil.NoRoomException e) {
            fail(StringResources.text(e.message));
        }
    }

    @Test
    public void aFileThatChangesWhilePackingFailsTheExportRatherThanLying() throws IOException {
        final TransferBundle.Plan plan = TransferBundle.plan(a.install, a.manifests, a.backup,
            a.prefs.transferableSettings());
        TestArchives.write(new File(a.install, "save/fx1g0000.sav"), "grew after the plan was made, by a lot");
        final TransferBundle.Marker marker = new TransferBundle.Marker(TransferPhone.APP, 1, "1", "",
            plan.entries(), plan.bytes(), plan.saves);
        try {
            TransferBundle.writeExport(a.exports, "x.zip", plan, marker, TestProgress.QUIET);
            fail("the marker would no longer be true");
        } catch (Message.Failure expected) {
            assertEquals(new Message(R.string.export_file_changed, "install/save/fx1g0000.sav"), expected.message);
            assertEquals(Collections.emptyList(), Arrays.asList(a.exports.list()));
        }
    }

    @Test
    public void aFileShrinkingWhilePackingFailsToo() throws IOException {
        final TransferBundle.Plan plan = TransferBundle.plan(a.install, a.manifests, a.backup,
            a.prefs.transferableSettings());
        TestArchives.write(new File(a.install, "save/fx1g0000.sav"), "");
        final TransferBundle.Marker marker = new TransferBundle.Marker(TransferPhone.APP, 1, "1", "",
            plan.entries(), plan.bytes(), plan.saves);
        try {
            TransferBundle.writeExport(a.exports, "x.zip", plan, marker, TestProgress.QUIET);
            fail("the marker would no longer be true");
        } catch (Message.Failure expected) {
            assertEquals(new Message(R.string.export_file_changed, "install/save/fx1g0000.sav"), expected.message);
        }
    }

    @Test
    public void aDayOldExportGoesAtTheNextStartAndAYoungerOneStays() throws IOException {
        final long day = 24L * 60 * 60 * 1000;
        final File export = a.export();
        final long now = export.lastModified();

        assertFalse(TransferBundle.removeExpired(a.exports, now + day - 1000));
        assertTrue(export.exists());

        assertTrue(TransferBundle.removeExpired(a.exports, now + day + 1000));
        assertFalse(export.exists());
        assertNull(TransferBundle.finishedExport(a.exports));
    }

    @Test
    public void whatADeadProcessLeftHalfWrittenGoesWhateverItsAge() throws IOException {
        final File export = a.export();
        final File partial = new File(a.exports, "KeeperFX-unofficial-x.zip.part");
        TestArchives.write(partial, "half");
        assertFalse("that is not a finished one", TransferBundle.removeExpired(a.exports, partial.lastModified()));
        assertFalse(partial.exists());
        assertTrue(export.exists());
    }

    @Test
    public void noFolderMeansNothingToDo() {
        assertFalse(TransferBundle.removeExpired(new File(a.files, "nothing"), 0));
        assertNull(TransferBundle.finishedExport(new File(a.files, "nothing")));
    }

    @Test
    public void thereIsAlwaysSomethingToSayAboutWhereAFileIs() {
        assertEquals(Message.plain("Download/Inbox/x.zip"), PhoneTransfer.whereIs("primary:Download/Inbox/x.zip", "x.zip"));
        assertEquals(Message.plain("x.zip"), PhoneTransfer.whereIs("msf:1234", "x.zip"));
        assertEquals(new Message(R.string.import_bundle_picked_file), PhoneTransfer.whereIs("", ""));
    }
}
