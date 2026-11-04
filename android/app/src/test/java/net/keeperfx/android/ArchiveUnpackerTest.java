package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ArchiveUnpackerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File root;

    @Before
    public void makeTheInstallation() throws IOException {
        root = folder.newFolder("sandbox", "keeperfx");
    }

    private File sandboxFile(String name) {
        return new File(root.getParentFile(), name);
    }

    @Test
    public void entriesLandUnderTheirPathAndAreListed() throws IOException {
        final File archive = TestArchives.sevenZ(folder.newFile("a.7z"), TestArchives.entries(
            "fxdata/a.cfg", "one", "b.txt", "two"));
        final List<String> written = ArchiveUnpacker.unpackSevenZ(archive, root, TestArchives.QUIET);
        assertEquals(Arrays.asList("fxdata/a.cfg", "b.txt"), written);
        assertEquals("one", TestArchives.read(new File(root, "fxdata/a.cfg")));
        assertEquals("two", TestArchives.read(new File(root, "b.txt")));
    }

    @Test
    public void whatIsAlreadyThereSurvivesAnUnpack() throws IOException {
        TestArchives.write(new File(root, "save/fx1g0000.sav"), "keep me");
        final File archive = TestArchives.sevenZ(folder.newFile("a.7z"), TestArchives.entries(
            "fxdata/a.cfg", "one"));
        ArchiveUnpacker.unpackSevenZ(archive, root, TestArchives.QUIET);
        assertEquals("keep me", TestArchives.read(new File(root, "save/fx1g0000.sav")));
    }

    @Test
    public void windowsBinariesAreDeadWeightHere() throws IOException {
        final File archive = TestArchives.sevenZ(folder.newFile("a.7z"), TestArchives.entries(
            "keeperfx.exe", "x", "SDL3.DLL", "x", "fxdata/a.cfg", "one"));
        assertEquals(Collections.singletonList("fxdata/a.cfg"),
            ArchiveUnpacker.unpackSevenZ(archive, root, TestArchives.QUIET));
        assertFalse(new File(root, "keeperfx.exe").exists());
    }

    @Test
    public void anEntryClimbingOutOfTheInstallationIsRefused() throws IOException {
        final File archive = TestArchives.sevenZ(folder.newFile("a.7z"), TestArchives.entries(
            "../evil.txt", "x",
            "fxdata/../../evil2.txt", "x",
            "fxdata/a.cfg", "one"));
        assertEquals(Collections.singletonList("fxdata/a.cfg"),
            ArchiveUnpacker.unpackSevenZ(archive, root, TestArchives.QUIET));
        assertFalse(sandboxFile("evil.txt").exists());
        assertFalse(sandboxFile("evil2.txt").exists());
    }

    @Test
    public void anAbsoluteEntryIsRefusedRatherThanMovedInside() throws IOException {
        final File archive = TestArchives.sevenZ(folder.newFile("a.7z"), TestArchives.entries(
            "/abs.txt", "x", "C:\\abs2.txt", "x", "fxdata/a.cfg", "one"));
        assertEquals(Collections.singletonList("fxdata/a.cfg"),
            ArchiveUnpacker.unpackSevenZ(archive, root, TestArchives.QUIET));
        assertFalse(new File(root, "abs.txt").exists());
    }

    @Test
    public void aBackslashIsASeparatorAndCannotClimbEither() throws IOException {
        final File archive = TestArchives.sevenZ(folder.newFile("a.7z"), TestArchives.entries(
            "fxdata\\a.cfg", "one", "..\\evil.txt", "x"));
        assertEquals(Collections.singletonList("fxdata/a.cfg"),
            ArchiveUnpacker.unpackSevenZ(archive, root, TestArchives.QUIET));
        assertEquals("one", TestArchives.read(new File(root, "fxdata/a.cfg")));
        assertFalse(sandboxFile("evil.txt").exists());
    }

    @Test
    public void aLinkLeadingOutOfTheInstallationIsNotFollowed() throws IOException {
        final File outside = folder.newFolder("sandbox", "outside");
        java.nio.file.Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());
        final File archive = TestArchives.sevenZ(folder.newFile("a.7z"), TestArchives.entries(
            "link/evil.txt", "x", "fxdata/a.cfg", "one"));
        assertEquals(Collections.singletonList("fxdata/a.cfg"),
            ArchiveUnpacker.unpackSevenZ(archive, root, TestArchives.QUIET));
        assertFalse(new File(outside, "evil.txt").exists());
    }

    @Test
    public void aCancelStopsTheUnpackAtTheNextEntry() throws IOException {
        final File archive = TestArchives.sevenZ(folder.newFile("a.7z"), TestArchives.entries(
            "a.txt", "1", "b.txt", "2", "c.txt", "3"));
        final AtomicInteger asked = new AtomicInteger();
        final List<String> written = ArchiveUnpacker.unpackSevenZ(archive, root,
            new ArchiveUnpacker.Progress() {
                @Override
                public void onFiles(int unpacked) {
                }

                @Override
                public boolean isCancelled() {
                    return asked.incrementAndGet() > 1;
                }
            });
        assertEquals(Collections.singletonList("a.txt"), written);
        assertFalse(new File(root, "b.txt").exists());
    }

    @Test
    public void aZipIsFlattenedIntoTheFolder() throws IOException {
        final File archive = TestArchives.zip(folder.newFile("m.zip"), TestArchives.entries(
            "music/", "",
            "music/keeper02.ogg", "two",
            "keeper03.ogg", "three"));
        assertEquals(2, ArchiveUnpacker.unzipFlat(archive, root, TestArchives.QUIET));
        assertEquals("two", TestArchives.read(new File(root, "keeper02.ogg")));
        assertEquals("three", TestArchives.read(new File(root, "keeper03.ogg")));
    }

    @Test
    public void aZipEntryCannotClimbOutEither() throws IOException {
        final File archive = TestArchives.zip(folder.newFile("m.zip"), TestArchives.entries(
            "..", "x", "../../evil.ogg", "x", "fine.ogg", "y"));
        ArchiveUnpacker.unzipFlat(archive, root, TestArchives.QUIET);
        assertFalse(sandboxFile("evil.ogg").exists());
        // Flattening takes the climb out of the name, the file itself is harmless.
        assertTrue(new File(root, "evil.ogg").exists());
        assertTrue(new File(root, "fine.ogg").exists());
    }

    @Test
    public void theRetirementListCannotReachOutsideTheInstallation() throws IOException {
        TestArchives.write(sandboxFile("precious.txt"), "x");
        TestArchives.write(new File(root, "old.dat"), "x");
        TestArchives.write(new File(root, "launcher-auto-file-removal.txt"),
            "# comment\n[1.0.0]\n/old.dat\n../precious.txt\n");
        ArchiveUnpacker.removeRetiredFiles(root, null, Collections.<String>emptySet());
        assertFalse(new File(root, "old.dat").exists());
        assertTrue(sandboxFile("precious.txt").exists());
    }
}
