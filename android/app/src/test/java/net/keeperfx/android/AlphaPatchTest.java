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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lays a real patch over a real release and takes it off again, the way the
 * launcher does, and asks the verification and the plan what they make of it.
 */
public class AlphaPatchTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File root;
    private File backupDir;
    private File stableManifest;
    private File alphaManifest;

    @Before
    public void installTheRelease() throws IOException {
        root = folder.newFolder("keeperfx");
        backupDir = new File(folder.getRoot(), "alpha-backup");
        stableManifest = new File(folder.getRoot(), "manifests/stable.txt");
        alphaManifest = new File(folder.getRoot(), "manifests/alpha.txt");

        final File release = TestArchives.sevenZ(folder.newFile("release.7z"), TestArchives.entries(
            "fxdata/shared.cfg", "release",
            "fxdata/untouched.cfg", "release",
            "campgns/retired.dat", "release"));
        final List<String> unpacked = ArchiveUnpacker.unpackSevenZ(release, root, TestArchives.QUIET);
        InstallManifest.write(stableManifest, root, unpacked);
    }

    private File thePatch() throws IOException {
        return TestArchives.sevenZ(new File(folder.getRoot(), "patch.7z"), TestArchives.entries(
            "fxdata/shared.cfg", "patched, and longer than before",
            "fxdata/font12.fxfont", "only the patch has this",
            "launcher-auto-file-removal.txt", "[1.4.0.5500]\n/campgns/retired.dat\n"));
    }

    private File applyPatch() throws IOException {
        return applyArchive(thePatch());
    }

    private File applyArchive(File patch) throws IOException {
        new AlphaBackup(root, backupDir).extendFor(patch);
        final List<String> unpacked = ArchiveUnpacker.unpackSevenZ(patch, root, TestArchives.QUIET);
        final Set<String> releaseFiles = InstallManifest.read(stableManifest).keySet();
        ArchiveUnpacker.removeRetiredFiles(root, backupDir, releaseFiles);
        InstallManifest.write(alphaManifest, root, unpacked);
        return patch;
    }

    /**
     * Cancels the unpack after this many entries and stops where runAlpha() stops
     * then: the backup is whole, those files are replaced, and neither the retirement
     * list nor the manifest has run. A killed process leaves the same behind.
     */
    private void cancelAfter(File patch, final int entries) throws IOException {
        new AlphaBackup(root, backupDir).extendFor(patch);
        ArchiveUnpacker.unpackSevenZ(patch, root, new ArchiveUnpacker.Progress() {
            private int asked;

            @Override
            public void onFiles(int unpacked) {
            }

            @Override
            public boolean isCancelled() {
                return asked++ >= entries;
            }
        });
    }

    private static UpdateManager.Facts factsOfAnInstallation(InstallManifest.Report report,
            String alphaVersion) {
        final UpdateManager.Facts facts = new UpdateManager.Facts();
        facts.dataInstalled = true;
        facts.stableVersion = "1.4.0";
        facts.hasManifest = true;
        facts.verified = report;
        facts.alphaEnabled = true;
        facts.alphaVersion = alphaVersion;
        facts.hasMusic = true;
        facts.latestStable = new ReleaseApi.ReleaseInfo("1.4.0", "https://example.test/s", 1);
        facts.alphaForEngine = new ReleaseApi.ReleaseInfo("1.4.0.5591", "https://example.test/a", 1);
        return facts;
    }

    private InstallManifest.Report verify() throws IOException {
        final Map<String, Long> stable = InstallManifest.read(stableManifest);
        final Map<String, Long> alpha = InstallManifest.read(alphaManifest);
        return InstallManifest.verify(root, stable, alpha, backupDir,
            Collections.<String>emptySet());
    }

    private String contents(String relative) throws IOException {
        return TestArchives.read(new File(root, relative));
    }

    @Test
    public void thePatchReplacesAddsAndRetires() throws IOException {
        applyPatch();
        assertEquals("patched, and longer than before", contents("fxdata/shared.cfg"));
        assertEquals("only the patch has this", contents("fxdata/font12.fxfont"));
        assertEquals("release", contents("fxdata/untouched.cfg"));
        assertFalse(new File(root, "campgns/retired.dat").exists());
    }

    /**
     * The loop this pins: the patch retires a file of the release, the release's
     * manifest still lists it, the verification answered with "Repair", whose
     * download clears the patch, which retires the same file again.
     */
    @Test
    public void aFileThePatchRetiredIsNoReasonToRepairTheRelease() throws IOException {
        applyPatch();

        final InstallManifest.Report report = verify();
        assertEquals(Collections.emptyList(), report.stableProblems);
        assertEquals(Collections.emptyList(), report.alphaProblems);

        assertEquals(Collections.emptyList(),
            UpdateManager.plan(factsOfAnInstallation(report, "1.4.0.5591")));
    }

    /**
     * Each replaced file is whole, so the game plays on. Reporting it as damage to the
     * release offered the 374 MB release first, when applying the patch again is all
     * it takes.
     */
    @Test
    public void aPatchCutShortIsNotDamageToTheRelease() throws IOException {
        cancelAfter(thePatch(), 1);
        assertEquals("patched, and longer than before", contents("fxdata/shared.cfg"));
        assertFalse(new File(root, "fxdata/font12.fxfont").exists());
        assertFalse(alphaManifest.exists());

        final InstallManifest.Report report = verify();
        assertEquals(Collections.emptyList(), report.stableProblems);
        assertEquals(Collections.emptyList(), report.alphaProblems);

        final List<UpdateManager.Item> offered =
            UpdateManager.plan(factsOfAnInstallation(report, ""));
        assertEquals(1, offered.size());
        assertEquals(UpdateManager.Kind.GAME_ALPHA, offered.get(0).kind);
    }

    /** The copy in the backup is what excuses a file, and only the patch's files have one. */
    @Test
    public void aPatchCutShortExcusesOnlyTheFilesItSaved() throws IOException {
        cancelAfter(thePatch(), 1);
        TestArchives.write(new File(root, "fxdata/untouched.cfg"), "torn, and of another size");
        assertEquals(Collections.singletonList("fxdata/untouched.cfg"), verify().stableProblems);
    }

    /** The manifest on disk is then the earlier patch's, which never heard of this file. */
    @Test
    public void aNewerPatchCutShortIsNotDamageEither() throws IOException {
        applyPatch();
        final File newer = TestArchives.sevenZ(new File(folder.getRoot(), "newer.7z"),
            TestArchives.entries(
                "fxdata/untouched.cfg", "patched for the first time",
                "fxdata/shared.cfg", "patched again"));
        cancelAfter(newer, 1);
        assertEquals("patched for the first time", contents("fxdata/untouched.cfg"));
        assertTrue(verify().isClean());
    }

    @Test
    public void applyingAPatchCutShortAgainFinishesIt() throws IOException {
        final File patch = thePatch();
        cancelAfter(patch, 2);
        applyArchive(patch);

        assertTrue(verify().isClean());
        assertEquals("only the patch has this", contents("fxdata/font12.fxfont"));
        assertFalse(new File(root, "campgns/retired.dat").exists());

        new AlphaBackup(root, backupDir).restore();
        assertEquals("release", contents("fxdata/shared.cfg"));
        assertFalse(new File(root, "fxdata/font12.fxfont").exists());
    }

    /** What switching the patch off does to one that never finished. */
    @Test
    public void aPatchCutShortComesOffLikeAWholeOne() throws IOException {
        cancelAfter(thePatch(), 2);

        new AlphaBackup(root, backupDir).restore();

        assertEquals("release", contents("fxdata/shared.cfg"));
        assertFalse(new File(root, "fxdata/font12.fxfont").exists());
        assertFalse(backupDir.exists());
        assertTrue(verify().isClean());
    }

    @Test
    public void aFileMissingForAnyOtherReasonIsStillReported() throws IOException {
        applyPatch();
        assertTrue(new File(root, "fxdata/untouched.cfg").delete());
        assertEquals(Collections.singletonList("fxdata/untouched.cfg"), verify().stableProblems);
    }

    @Test
    public void damageToAPatchedFileIsDamageToThePatch() throws IOException {
        applyPatch();
        TestArchives.write(new File(root, "fxdata/shared.cfg"), "torn");
        final InstallManifest.Report report = verify();
        assertEquals(Collections.emptyList(), report.stableProblems);
        assertEquals(Collections.singletonList("fxdata/shared.cfg"), report.alphaProblems);
    }

    @Test
    public void takingThePatchOffRestoresTheReleaseExactly() throws IOException {
        applyPatch();

        new AlphaBackup(root, backupDir).restore();
        assertTrue(alphaManifest.delete());

        assertEquals("release", contents("fxdata/shared.cfg"));
        assertEquals("release", contents("campgns/retired.dat"));
        assertFalse("what only the patch brought goes with it",
            new File(root, "fxdata/font12.fxfont").exists());
        assertFalse(backupDir.exists());
        assertTrue(verify().isClean());
    }

    /**
     * A second patch can touch files the first did not. Those are still the
     * release's own and this is the only chance to save them, while what the
     * first patch replaced must not be saved again as if it were the release's.
     */
    @Test
    public void aNewerPatchExtendsTheBackupWithoutRetakingIt() throws IOException {
        applyPatch();
        final File newer = TestArchives.sevenZ(new File(folder.getRoot(), "newer.7z"),
            TestArchives.entries(
                "fxdata/shared.cfg", "patched again",
                "fxdata/untouched.cfg", "patched for the first time",
                "fxdata/font12.fxfont", "the patch's own, newer",
                "fxdata/font16.fxfont", "added by the newer patch"));
        applyArchive(newer);

        new AlphaBackup(root, backupDir).restore();

        assertEquals("release", contents("fxdata/shared.cfg"));
        assertEquals("release", contents("fxdata/untouched.cfg"));
        assertFalse(new File(root, "fxdata/font12.fxfont").exists());
        assertFalse(new File(root, "fxdata/font16.fxfont").exists());
    }

    /**
     * The backup goes first and reads the names before any check of the unpack,
     * and a revert deletes what it wrote down. A name climbing out would copy a
     * file of the app's over into the backup and delete it on the way back.
     */
    @Test
    public void aClimbingEntryReachesNeitherTheBackupNorTheAddedList() throws IOException {
        final File settings = new File(folder.getRoot(), "shared_prefs.xml");
        TestArchives.write(settings, "the player's settings");
        final File patch = TestArchives.sevenZ(new File(folder.getRoot(), "evil.7z"),
            TestArchives.entries(
                "../shared_prefs.xml", "overwritten",
                "../../elsewhere.txt", "x",
                "/abs.txt", "x",
                "fxdata/shared.cfg", "patched"));
        applyArchive(patch);
        assertEquals("the player's settings", TestArchives.read(settings));
        assertFalse(TestArchives.read(new File(backupDir, "added.txt")).contains(".."));

        new AlphaBackup(root, backupDir).restore();

        assertEquals("the player's settings", TestArchives.read(settings));
        assertEquals("release", contents("fxdata/shared.cfg"));
    }

    /** The list is only a file on disk, written by whichever build came before. */
    @Test
    public void aRevertDoesNotBelieveThePathsOnItsList() throws IOException {
        final File settings = new File(folder.getRoot(), "shared_prefs.xml");
        TestArchives.write(settings, "the player's settings");
        applyPatch();
        TestArchives.write(new File(backupDir, "added.txt"), "../shared_prefs.xml\n/etc/hosts\n");

        new AlphaBackup(root, backupDir).restore();

        assertTrue(settings.exists());
    }

    /**
     * What a full reinstall does first. Left behind, the patch's own file would pass
     * for the release's when the patch comes back, and a revert would restore it.
     */
    @Test
    public void discardingTheBackupTakesWhatOnlyThePatchBroughtWithIt() throws IOException {
        applyPatch();
        new AlphaBackup(root, backupDir).discard();
        assertFalse(new File(root, "fxdata/font12.fxfont").exists());
        assertFalse(backupDir.exists());
        assertEquals("nothing else is touched",
            "patched, and longer than before", contents("fxdata/shared.cfg"));
    }

    /** A process dying in the middle of the copy leaves this behind. */
    @Test
    public void halfACopyInTheBackupIsNotRestored() throws IOException {
        applyPatch();
        TestArchives.write(new File(backupDir, "fxdata/other.cfg.part"), "torn");

        new AlphaBackup(root, backupDir).restore();

        assertFalse(new File(root, "fxdata/other.cfg.part").exists());
    }

    @Test
    public void aRetiredFileAlreadyInTheBackupKeepsTheReleaseCopy() throws IOException {
        applyPatch();
        TestArchives.write(new File(root, "campgns/retired.dat"), "came back in some other way");
        ArchiveUnpacker.removeRetiredFiles(root, backupDir,
            InstallManifest.read(stableManifest).keySet());
        assertFalse(new File(root, "campgns/retired.dat").exists());
        assertEquals("release", TestArchives.read(new File(backupDir, "campgns/retired.dat")));
    }
}
