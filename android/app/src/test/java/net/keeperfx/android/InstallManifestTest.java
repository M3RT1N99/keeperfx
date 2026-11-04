package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InstallManifestTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File root;
    private File manifest;
    private File backup;

    @Before
    public void prepare() throws IOException {
        root = folder.newFolder("keeperfx");
        manifest = new File(folder.getRoot(), "manifests/stable.txt");
        backup = new File(folder.getRoot(), "alpha-backup");
    }

    private InstallManifest.Report verify(Set<String> overlay) throws IOException {
        return InstallManifest.verify(root, InstallManifest.read(manifest),
            Collections.<String, Long>emptyMap(), backup, overlay);
    }

    @Test
    public void whatIsWrittenIsWhatIsReadBack() throws IOException {
        TestArchives.write(new File(root, "a/b.dat"), "12345");
        assertEquals(1, InstallManifest.write(manifest, root, Arrays.asList("a/b.dat", "gone.dat")));
        final Map<String, Long> read = InstallManifest.read(manifest);
        assertEquals(Collections.singletonMap("a/b.dat", 5L), read);
    }

    @Test
    public void noManifestIsAnEmptyOneAndCleanly() throws IOException {
        assertTrue(InstallManifest.read(manifest).isEmpty());
        assertTrue(verify(Collections.<String>emptySet()).isClean());
    }

    @Test
    public void aGarbledLineLosesOnlyItself() throws IOException {
        TestArchives.write(manifest, "x\tbad.dat\nno tab here\n\t\n3\tgood.dat\n");
        assertEquals(Collections.singletonMap("good.dat", 3L), InstallManifest.read(manifest));
    }

    @Test
    public void aMissingOrResizedFileIsDamage() throws IOException {
        TestArchives.write(new File(root, "a.dat"), "12345");
        TestArchives.write(new File(root, "b.dat"), "12345");
        TestArchives.write(new File(root, "c.dat"), "12345");
        InstallManifest.write(manifest, root, Arrays.asList("a.dat", "b.dat", "c.dat"));

        assertTrue(new File(root, "a.dat").delete());
        TestArchives.write(new File(root, "b.dat"), "1234");

        assertEquals(Arrays.asList("a.dat", "b.dat"),
            verify(Collections.<String>emptySet()).stableProblems);
    }

    /** The overlay replaces these on every launch, so their size says nothing. */
    @Test
    public void theConfigurationOverlayMayChangeWhatItOwns() throws IOException {
        TestArchives.write(new File(root, "fxdata/Magic.cfg"), "12345");
        TestArchives.write(new File(root, "keeperfx.cfg"), "12345");
        InstallManifest.write(manifest, root, Arrays.asList("fxdata/Magic.cfg", "keeperfx.cfg"));
        TestArchives.write(new File(root, "fxdata/Magic.cfg"), "the APK's own copy");
        TestArchives.write(new File(root, "keeperfx.cfg"), "with the player's settings");

        assertTrue(verify(new HashSet<>(Collections.singletonList("fxdata/magic.cfg"))).isClean());
    }

    @Test
    public void butItHasToExist() throws IOException {
        TestArchives.write(new File(root, "keeperfx.cfg"), "12345");
        InstallManifest.write(manifest, root, Collections.singletonList("keeperfx.cfg"));
        assertTrue(new File(root, "keeperfx.cfg").delete());
        assertEquals(Collections.singletonList("keeperfx.cfg"),
            verify(new HashSet<>(Collections.singletonList("keeperfx.cfg"))).stableProblems);
    }
}
