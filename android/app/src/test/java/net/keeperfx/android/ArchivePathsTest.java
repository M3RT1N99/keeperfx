package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class ArchivePathsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void anOrdinaryEntryStaysAsItIs() {
        assertEquals("fxdata/a.cfg", ArchivePaths.relativePath("fxdata/a.cfg"));
        assertEquals("keeperfx.cfg", ArchivePaths.relativePath("keeperfx.cfg"));
    }

    @Test
    public void harmlessOddnessIsCleanedUp() {
        assertEquals("fxdata/a.cfg", ArchivePaths.relativePath("./fxdata//a.cfg"));
        assertEquals("fxdata/a.cfg", ArchivePaths.relativePath("fxdata\\a.cfg"));
        assertEquals("fxdata", ArchivePaths.relativePath("fxdata/"));
    }

    @Test
    public void aDotDotInAName() {
        assertEquals("a..b/c..", ArchivePaths.relativePath("a..b/c.."));
    }

    @Test
    public void climbingIsRefusedWhereverItHappens() {
        for (String name : new String[] {
                "..", "../a", "a/../b", "a/b/../../..", "..\\a", "a\\..\\..\\b", "./../a"}) {
            assertNull(name, ArchivePaths.relativePath(name));
        }
    }

    @Test
    public void absolutePathsAreRefused() {
        for (String name : new String[] {"/etc/hosts", "\\etc\\hosts", "C:\\a", "c:/a", "C:a"}) {
            assertNull(name, ArchivePaths.relativePath(name));
        }
    }

    @Test
    public void nothingAtAllIsRefused() {
        for (String name : new String[] {null, "", ".", "./", "/", "\\"}) {
            assertNull(name, ArchivePaths.relativePath(name));
        }
    }

    @Test
    public void aSafeNameResolvesUnderTheRoot() throws IOException {
        final File root = folder.newFolder("root");
        assertEquals(new File(root, "a/b.txt"), ArchivePaths.resolve(root, "a/b.txt"));
        assertNull(ArchivePaths.resolve(root, "../b.txt"));
    }

    /** A sibling whose name starts with the root's is not inside it. */
    @Test
    public void aSiblingWithTheSamePrefixIsNotInside() throws IOException {
        final File root = folder.newFolder("root");
        final File sibling = folder.newFolder("root2");
        Files.createSymbolicLink(new File(root, "link").toPath(), sibling.toPath());
        assertNull(ArchivePaths.resolve(root, "link/a.txt"));
        assertNotNull(ArchivePaths.resolve(root, "linked/a.txt"));
    }
}
