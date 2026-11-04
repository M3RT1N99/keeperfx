package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    private File staging() {
        return new File(folder.getRoot(), "staging");
    }

    @Test
    public void aZipIsFlattenedIntoTheFolder() throws IOException {
        final File archive = TestArchives.zip(folder.newFile("m.zip"), TestArchives.entries(
            "music/", "",
            "music/keeper02.ogg", "two",
            "keeper03.ogg", "three"));
        assertEquals(2, ArchiveUnpacker.unzipAllOrNothing(archive, root, staging(), TestArchives.QUIET));
        assertEquals("two", TestArchives.read(new File(root, "keeper02.ogg")));
        assertEquals("three", TestArchives.read(new File(root, "keeper03.ogg")));
        assertFalse(staging().exists());
    }

    @Test
    public void aZipEntryCannotClimbOutEither() throws IOException {
        final File archive = TestArchives.zip(folder.newFile("m.zip"), TestArchives.entries(
            "..", "x", "../../evil.ogg", "x", "fine.ogg", "y"));
        ArchiveUnpacker.unzipAllOrNothing(archive, root, staging(), TestArchives.QUIET);
        assertFalse(sandboxFile("evil.ogg").exists());
        // Flattening takes the climb out of the name, the file itself is harmless.
        assertTrue(new File(root, "evil.ogg").exists());
        assertTrue(new File(root, "fine.ogg").exists());
    }

    /** An error page delivered with a 200, say. */
    @Test
    public void somethingThatIsNotAZipIsAFailureAndNotZeroTracks() throws IOException {
        final File archive = folder.newFile("m.zip");
        TestArchives.write(archive, "<html>503 Service Unavailable</html>");
        try {
            ArchiveUnpacker.unzipAllOrNothing(archive, root, staging(), TestArchives.QUIET);
            fail("nothing was installed, so it has to say so");
        } catch (IOException expected) {
            assertEquals(0, root.list().length);
        }
    }

    /** One track on disk is what stops the launcher offering the music again. */
    @Test
    public void aDamagedZipInstallsNoTracksAtAll() throws IOException {
        final File whole = TestArchives.zip(folder.newFile("m.zip"), TestArchives.entries(
            "keeper02.ogg", "two two two two two two two two",
            "keeper03.ogg", "three three three three three three"));
        final byte[] bytes = java.nio.file.Files.readAllBytes(whole.toPath());
        final File torn = folder.newFile("torn.zip");
        java.nio.file.Files.write(torn.toPath(), java.util.Arrays.copyOf(bytes, bytes.length / 2));
        try {
            ArchiveUnpacker.unzipAllOrNothing(torn, root, staging(), TestArchives.QUIET);
            fail("half an archive has to be an error");
        } catch (IOException expected) {
            assertEquals(0, root.list().length);
            assertFalse(staging().exists());
        }
    }

    @Test
    public void aZipWithoutFilesIsAFailureToo() throws IOException {
        final File archive = TestArchives.zip(folder.newFile("m.zip"), TestArchives.entries("music/", ""));
        try {
            ArchiveUnpacker.unzipAllOrNothing(archive, root, staging(), TestArchives.QUIET);
            fail("nothing was installed, so it has to say so");
        } catch (IOException expected) {
            assertEquals("The archive holds no files", expected.getMessage());
        }
    }

    @Test
    public void aCancelledZipInstallsNothing() throws IOException {
        final File archive = TestArchives.zip(folder.newFile("m.zip"), TestArchives.entries(
            "keeper02.ogg", "two", "keeper03.ogg", "three"));
        final int installed = ArchiveUnpacker.unzipAllOrNothing(archive, root, staging(),
            new ArchiveUnpacker.Progress() {
                @Override
                public void onFiles(int unpacked) {
                }

                @Override
                public boolean isCancelled() {
                    return true;
                }
            });
        assertEquals(0, installed);
        assertEquals(0, root.list().length);
    }

    @Test
    public void onlyWhatAnUnpackAddsCountsAgainstTheFreeSpace() throws IOException {
        TestArchives.write(new File(root, "fxdata/replaced.cfg"), "1234567890");
        final File archive = TestArchives.sevenZ(folder.newFile("a.7z"), TestArchives.entries(
            "fxdata/replaced.cfg", "123",
            "fxdata/new.cfg", "1234",
            "keeperfx.exe", "not unpacked anyway",
            "../outside.txt", "not unpacked anyway"));
        try (org.apache.commons.compress.archivers.sevenz.SevenZFile sevenZ =
                org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(archive).get()) {
            assertEquals(4, ArchiveUnpacker.bytesNeeded(sevenZ.getEntries(), root));
        }
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
