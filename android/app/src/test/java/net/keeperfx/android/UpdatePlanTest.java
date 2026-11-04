package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UpdatePlanTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private UpdateManager.Facts facts;

    private static final ReleaseApi.ReleaseInfo STABLE =
        new ReleaseApi.ReleaseInfo("1.4.0", "https://example.test/stable.7z", 374587275);
    private static final ReleaseApi.ReleaseInfo ALPHA =
        new ReleaseApi.ReleaseInfo("1.4.0.5591", "https://example.test/alpha.7z", 23838887);

    @Before
    public void aFullyInstalledDevice() {
        facts = new UpdateManager.Facts();
        facts.dataInstalled = true;
        facts.stableVersion = "1.4.0";
        facts.hasManifest = true;
        facts.alphaEnabled = true;
        facts.alphaVersion = "1.4.0.5591";
        facts.hasMusic = true;
        facts.appVersionName = "1.57";
        facts.latestStable = STABLE;
        facts.latestAlpha = ALPHA;
    }

    private List<UpdateManager.Kind> plannedKinds() {
        final List<UpdateManager.Kind> kinds = new ArrayList<>();
        for (UpdateManager.Item item : UpdateManager.plan(facts)) {
            kinds.add(item.kind);
        }
        return kinds;
    }

    private String plannedTitle(int index) {
        return UpdateManager.plan(facts).get(index).title;
    }

    private String plannedDetail(int index) {
        return UpdateManager.plan(facts).get(index).detail;
    }

    @Test
    public void nothingIsOfferedWhenEverythingIsCurrent() {
        assertEquals(Collections.emptyList(), plannedKinds());
    }

    @Test
    public void aFreshDeviceIsOfferedTheReleaseAndNothingThatDependsOnIt() {
        facts.dataInstalled = false;
        facts.missingEntries = Arrays.asList("(nothing installed yet)");
        facts.stableVersion = "";
        facts.hasManifest = false;
        facts.alphaVersion = "";
        facts.hasMusic = false;
        assertEquals(Arrays.asList(UpdateManager.Kind.GAME_DATA), plannedKinds());
    }

    /**
     * The loop this pins: the "is it installed" check once asked for a file only
     * the patch brings, the patch is only offered once it is installed, so the
     * launcher offered the release again and again and never enabled Play.
     */
    @Test
    public void aFreshInstallMovesOnOnceTheReleaseIsOnDisk() throws IOException {
        final File root = folder.newFolder("keeperfx");
        assertEquals(Collections.singletonList("(nothing installed yet)"),
            GameData.findMissingKeeperfxEntries(new File(root, "absent")));
        assertTrue(!GameData.findMissingKeeperfxEntries(root).isEmpty());

        layOutWhatTheStableReleaseShips(root);

        facts.missingEntries = GameData.findMissingKeeperfxEntries(root);
        facts.dataInstalled = facts.missingEntries.isEmpty();
        assertEquals("the release alone has to count as installed",
            Collections.emptyList(), facts.missingEntries);
        facts.alphaVersion = "";
        facts.hasMusic = false;
        assertEquals(Arrays.asList(UpdateManager.Kind.GAME_ALPHA, UpdateManager.Kind.MUSIC),
            plannedKinds());
    }

    private static void layOutWhatTheStableReleaseShips(File root) throws IOException {
        for (String file : new String[] {
                "data/some.dat", "fxdata/some.cfg", "creatrs/some.cfg", "campgns/keeporig.cfg",
                "levels/some.dat", "ldata/intromix.smk", "ldata/bullfrog.smk",
                "campgns/keeporig_eng/good01.mp3", "sound/sound.dat", "keeperfx.cfg"}) {
            TestArchives.write(new File(root, file), "x");
        }
    }

    @Test
    public void anEmptyFolderCountsAsMissing() throws IOException {
        final File root = folder.newFolder("keeperfx");
        layOutWhatTheStableReleaseShips(root);
        FileUtil.deleteTree(new File(root, "levels"));
        assertTrue(new File(root, "levels").mkdirs());
        assertEquals(Collections.singletonList("levels/ (empty)"),
            GameData.findMissingKeeperfxEntries(root));
    }

    @Test
    public void anImportedFolderIsOfferedTheRealRelease() {
        facts.stableVersion = "";
        assertEquals(Arrays.asList(UpdateManager.Kind.GAME_DATA), plannedKinds());
        assertEquals("KeeperFX 1.4.0", plannedTitle(0));
    }

    @Test
    public void aNewerReleaseIsOffered() {
        facts.stableVersion = "1.3.2";
        assertEquals(Arrays.asList(UpdateManager.Kind.GAME_DATA), plannedKinds());
        assertTrue(plannedDetail(0).startsWith("Update from 1.3.2"));
    }

    @Test
    public void anInstallFromBeforeManifestsIsOfferedOneVerifyingReinstall() {
        facts.hasManifest = false;
        assertEquals("Verify KeeperFX 1.4.0", plannedTitle(0));
    }

    @Test
    public void aDamagedReleaseIsOfferedARepair() {
        facts.verified.stableProblems.add("campgns/keeporig_ger/good05.mp3");
        assertEquals("Repair KeeperFX 1.4.0", plannedTitle(0));
    }

    @Test
    public void damageToThePatchOnlyAsksForThePatch() {
        facts.verified.alphaProblems.add("fxdata/font12.fxfont");
        assertEquals(Arrays.asList(UpdateManager.Kind.GAME_ALPHA), plannedKinds());
        assertEquals("Repair KeeperFX alpha 1.4.0.5591", plannedTitle(0));
    }

    @Test
    public void thePatchIsNotOfferedWhenSwitchedOff() {
        facts.alphaEnabled = false;
        facts.alphaVersion = "";
        assertEquals(Collections.emptyList(), plannedKinds());
    }

    @Test
    public void aCachedPatchDoesNotClaimToCostADownload() {
        facts.alphaVersion = "";
        facts.alphaCached = true;
        assertTrue(plannedDetail(0).contains("already downloaded"));
    }

    @Test
    public void beingOfflineHidesOnlyWhatNeedsTheNetwork() {
        facts.latestStable = null;
        facts.latestAlpha = null;
        facts.hasMusic = false;
        assertEquals(Arrays.asList(UpdateManager.Kind.MUSIC), plannedKinds());
    }

    @Test
    public void anAppUpdateCarriesTheRelease() {
        final AppRelease release = new AppRelease("1.60", "https://example.test/app.apk", 1,
            Collections.<String>emptyList());
        facts.appUpdate = release;
        final List<UpdateManager.Item> items = UpdateManager.plan(facts);
        assertEquals(1, items.size());
        assertEquals(UpdateManager.Kind.APP, items.get(0).kind);
        assertEquals(release, items.get(0).appUpdate);
    }
}
