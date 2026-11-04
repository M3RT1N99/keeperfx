package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
        facts.alphaEnabled = true;
        facts.alphaVersion = "1.4.0.5591";
        facts.hasMusic = true;
        facts.appVersionName = "1.57";
        facts.latestStable = STABLE;
        facts.alphaForEngine = ALPHA;
        facts.engineBuild = 5591;
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

    /** What the launcher refuses while the game is open. */
    @Test
    public void onlyAnAppUpdateLeavesTheInstallationAlone() {
        final UpdateManager.Item app = new UpdateManager.Item(UpdateManager.Kind.APP, "", "", null);
        final UpdateManager.Item music = new UpdateManager.Item(UpdateManager.Kind.MUSIC, "", "", null);
        final UpdateManager.Item data = new UpdateManager.Item(UpdateManager.Kind.GAME_DATA, "", "", null);
        final UpdateManager.Item alpha = new UpdateManager.Item(UpdateManager.Kind.GAME_ALPHA, "", "", null);
        assertFalse(UpdateManager.changesInstallation(Arrays.asList(app)));
        assertFalse(UpdateManager.changesInstallation(Collections.emptyList()));
        assertTrue(UpdateManager.changesInstallation(Arrays.asList(app, music)));
        assertTrue(UpdateManager.changesInstallation(Arrays.asList(data)));
        assertTrue(UpdateManager.changesInstallation(Arrays.asList(alpha, app)));
    }

    /** An imported folder has no version, and the sentence used to have a hole there. */
    @Test
    public void theAlphaOfferOnAnImportedFolderReadsLikeASentence() {
        facts.stableVersion = "";
        facts.alphaVersion = "";
        final String text = UpdateManager.describe(UpdateManager.plan(facts));
        assertTrue(text, text.contains("needs and this installation does not have"));
        assertFalse(text, text.contains("needs and  does"));
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
    public void theZipIsTheMusicArchiveAndA7zIsNot() throws Exception {
        assertEquals("https://x/music.zip", ReleaseApi.musicArchiveUrl(new org.json.JSONObject(
            "{\"workshop_item\":{\"files\":[{\"url\":\"https://x/music.7z\"},{\"url\":\"https://x/music.ZIP\"}]}}"
                .replace("music.ZIP", "music.zip"))));
        assertEquals("", ReleaseApi.musicArchiveUrl(new org.json.JSONObject(
            "{\"workshop_item\":{\"files\":[{\"url\":\"https://x/music.7z\"}]}}")));
        assertEquals("", ReleaseApi.musicArchiveUrl(new org.json.JSONObject(
            "{\"workshop_item\":{}}")));
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
    public void aPatchThatFitsTheEngineIsOfferedWithoutFurtherAdo() {
        facts.alphaVersion = "";
        assertEquals("KeeperFX alpha 1.4.0.5591", plannedTitle(0));
        assertFalse(plannedDetail(0).contains("no patch for engine"));
    }

    @Test
    public void aPatchForAnotherBuildSaysSo() {
        facts.alphaVersion = "";
        facts.alphaForEngine = new ReleaseApi.ReleaseInfo("1.4.0.5700", "https://example.test/a", 1);
        assertEquals("KeeperFX alpha 1.4.0.5700", plannedTitle(0));
        assertTrue(plannedDetail(0).contains("no patch for engine build 5591"));
    }

    @Test
    public void aDriftedPatchIsReplacedByTheOneThatFits() {
        facts.alphaVersion = "1.4.0.5598";
        assertEquals("KeeperFX alpha 1.4.0.5591", plannedTitle(0));
        assertTrue(plannedDetail(0).startsWith("Replaces 1.4.0.5598"));
    }

    /** The API losing an old build must not pull a working install away from its engine. */
    @Test
    public void aPatchThatFitsStaysEvenWhenOnlyANewerOneIsOffered() {
        facts.alphaForEngine = new ReleaseApi.ReleaseInfo("1.4.0.5700", "https://example.test/a", 1);
        assertEquals(Collections.emptyList(), plannedKinds());
    }

    @Test
    public void aBuildThatDoesNotKnowItsEngineFollowsTheNewest() {
        facts.engineBuild = 0;
        facts.alphaVersion = "1.4.0.5591";
        facts.alphaForEngine = new ReleaseApi.ReleaseInfo("1.4.0.5700", "https://example.test/a", 1);
        assertEquals("KeeperFX alpha 1.4.0.5700", plannedTitle(0));
    }

    @Test
    public void theBuildOfAPatchIsTheEndOfItsVersion() {
        assertEquals(5591, ReleaseApi.buildOf("1.4.0.5591"));
        assertEquals(0, ReleaseApi.buildOf(""));
        assertEquals(0, ReleaseApi.buildOf("unknown"));
        assertTrue(ReleaseApi.fitsEngine("1.4.0.5591", 5591));
        assertFalse(ReleaseApi.fitsEngine("1.4.0.5592", 5591));
        assertFalse("an unknown engine fits nothing", ReleaseApi.fitsEngine("x", 0));
    }

    @Test
    public void aSharedLogSaysWhetherTheDataFits() {
        assertEquals("alpha patch matches", GameData.describeDataFit(5591, "1.4.0.5591"));
        assertTrue(GameData.describeDataFit(5591, "1.4.0.5700").startsWith("MISMATCH"));
        assertTrue(GameData.describeDataFit(5591, "").startsWith("no alpha"));
        assertTrue(GameData.describeDataFit(0, "1.4.0.5591").startsWith("unknown"));
    }

    @Test
    public void beingOfflineHidesOnlyWhatNeedsTheNetwork() {
        facts.latestStable = null;
        facts.alphaForEngine = null;
        facts.hasMusic = false;
        assertEquals(Arrays.asList(UpdateManager.Kind.MUSIC), plannedKinds());
    }

    /** The servers of a device that is current: nothing newer anywhere. */
    private static class ServersWithNothingNew implements UpdateManager.Servers {
        @Override
        public ReleaseApi.ReleaseInfo latestStable() throws IOException, JSONException {
            return STABLE;
        }

        @Override
        public ReleaseApi.ReleaseInfo alphaForEngine(int engineBuild)
                throws IOException, JSONException {
            return ALPHA;
        }

        @Override
        public AppRelease appUpdate() throws IOException, JSONException {
            return null;
        }
    }

    private static final class NoServerAnswers extends ServersWithNothingNew {
        @Override
        public ReleaseApi.ReleaseInfo latestStable() throws IOException {
            throw new IOException("offline");
        }

        @Override
        public ReleaseApi.ReleaseInfo alphaForEngine(int engineBuild) throws IOException {
            throw new IOException("offline");
        }

        @Override
        public AppRelease appUpdate() throws IOException {
            throw new IOException("offline");
        }
    }

    /** What the servers say is not known yet at this point on a real device. */
    private UpdateManager.Result ask(UpdateManager.Servers servers) {
        facts.latestStable = null;
        facts.alphaForEngine = null;
        return UpdateManager.ask(facts, servers);
    }

    @Test
    public void whenEveryServerAnswersAnEmptyListMeansCurrent() {
        final UpdateManager.Result result = ask(new ServersWithNothingNew());
        assertEquals(Collections.emptyList(), result.items);
        assertFalse(result.unanswered);
    }

    /**
     * Airplane mode, nothing installed. The empty list used to bring up "Everything is
     * up to date" over a status line saying KeeperFX is not installed.
     */
    @Test
    public void nothingOfferedBecauseNoServerAnsweredIsNotCurrent() {
        facts.dataInstalled = false;
        facts.stableVersion = "";
        final UpdateManager.Result result = ask(new NoServerAnswers());
        assertEquals(Collections.emptyList(), result.items);
        assertTrue(result.unanswered);
    }

    @Test
    public void whatNeedsNoServerIsStillOfferedWhenNoneAnswers() {
        facts.hasMusic = false;
        final UpdateManager.Result result = ask(new NoServerAnswers());
        assertEquals(UpdateManager.Kind.MUSIC, result.items.get(0).kind);
        assertTrue(result.unanswered);
    }

    @Test
    public void theAlphaIsNotAskedForWhenItIsNotWanted() {
        facts.alphaEnabled = false;
        facts.alphaVersion = "";
        final UpdateManager.Result result = ask(new ServersWithNothingNew() {
            @Override
            public ReleaseApi.ReleaseInfo alphaForEngine(int engineBuild) throws IOException {
                throw new IOException("nothing should have asked");
            }
        });
        assertFalse(result.unanswered);
    }

    /**
     * Sixty requests an hour without an account, and the check runs on every return to
     * the launcher, so a shared address gets there. The answer is an error, in real
     * HTTP, and the app has to be told that apart from being current.
     */
    @Test
    public void aRateLimitOnTheAppQuestionIsNoAnswer() throws Exception {
        for (String status : new String[] {"403 rate limit exceeded", "429 Too Many Requests"}) {
            final String limited = serveOnce("HTTP/1.1 " + status + "\r\n"
                + "Content-Type: application/json\r\nConnection: close\r\n\r\n"
                + "{\"message\":\"API rate limit exceeded\"}");
            final UpdateManager.Result result = ask(
                new ServersWithNothingNew() {
                    @Override
                    public AppRelease appUpdate() throws IOException, JSONException {
                        try {
                            return AppUpdater.newerThan("1.57", Http.getJson(limited));
                        } catch (IOException e) {
                            // That it was the status, and not this server falling over.
                            assertTrue(e.getMessage(),
                                e.getMessage().contains("HTTP " + status.substring(0, 3)));
                            throw e;
                        }
                    }
                });
            assertEquals(Collections.emptyList(), result.items);
            assertTrue(status, result.unanswered);
        }
    }

    /** @return where to ask for the one answer it has. */
    private static String serveOnce(final String response) throws IOException {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final Thread thread = new Thread(() -> {
            try (ServerSocket closing = server; Socket socket = closing.accept()) {
                final BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1));
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    // Only the end of the request matters.
                }
                socket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return "http://127.0.0.1:" + server.getLocalPort() + "/latest";
    }

    @Test
    public void aReleaseWithNoApkDoesNotCountAsCurrent() throws Exception {
        try {
            AppUpdater.newerThan("1.57", new JSONObject("{\"tag_name\":\"android-v1.60\"}"));
            fail("null here would read as being on the newest build");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no APK"));
        }
    }

    @Test
    public void theAppIsCurrentOrItIsNot() throws Exception {
        final JSONObject latest = new JSONObject("{\"tag_name\":\"android-v1.60\",\"assets\":"
            + "[{\"name\":\"keeperfx-android-arm64.apk\",\"browser_download_url\":\"https://x/a\"}]}");
        assertEquals("1.60", AppUpdater.newerThan("1.57", latest).versionName);
        assertNull(AppUpdater.newerThan("1.60", latest));
    }

    @Test
    public void theDialogListsTitleDetailAndWhatChangedInTheApp() {
        facts.alphaVersion = "";
        facts.appUpdate = new AppRelease("1.60", "https://example.test/app.apk", 1,
            Arrays.asList("first change", "second change"));
        final List<UpdateManager.Item> items = UpdateManager.plan(facts);
        final String text = UpdateManager.describe(items);
        assertTrue(text, text.contains("\u2022 KeeperFX alpha 1.4.0.5591\n    Files the current engine"));
        assertTrue(text, text.contains("\u2022 KeeperFX app 1.60\n    Currently 1.57\n"
            + "    \u2022 first change\n    \u2022 second change\n"));
        assertTrue(UpdateManager.contains(items, UpdateManager.Kind.APP));
        assertFalse(UpdateManager.contains(items, UpdateManager.Kind.GAME_DATA));
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

    /** No permission to install the app: what is left runs, in the order it was planned. */
    @Test
    public void leavingTheAppOutKeepsTheRestInOrder() {
        facts.alphaVersion = "";
        facts.hasMusic = false;
        facts.appUpdate = new AppRelease("1.60", "https://example.test/app.apk", 1,
            Collections.<String>emptyList());
        final List<UpdateManager.Item> rest =
            UpdateManager.without(UpdateManager.plan(facts), UpdateManager.Kind.APP);
        assertEquals(2, rest.size());
        assertEquals(UpdateManager.Kind.GAME_ALPHA, rest.get(0).kind);
        assertEquals(UpdateManager.Kind.MUSIC, rest.get(1).kind);
    }
}
