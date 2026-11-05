package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Talks real HTTP to a server small enough to misbehave on request. */
public class ResumableDownloadTest {

    private static final String BODY = "0123456789abcdefghij";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ServerSocket server;
    private Thread serverThread;
    private final List<String> rangesAsked = Collections.synchronizedList(new ArrayList<String>());

    private interface Responder {
        /** @param range the first byte asked for, or -1 */
        void respond(long range, OutputStream out) throws IOException;
    }

    private String url;
    private File target;

    @Before
    public void prepare() throws IOException {
        target = new File(folder.getRoot(), "archive.7z");
    }

    private void serve(final Responder responder) throws IOException {
        server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        url = "http://127.0.0.1:" + server.getLocalPort() + "/archive.7z";
        serverThread = new Thread(() -> {
            try {
                while (true) {
                    try (Socket socket = server.accept()) {
                        final BufferedReader in = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.ISO_8859_1));
                        long range = -1;
                        String line;
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            final Matcher m = Pattern.compile("(?i)range: bytes=(\\d+)-").matcher(line);
                            if (m.matches()) {
                                range = Long.parseLong(m.group(1));
                                rangesAsked.add(m.group(1));
                            }
                        }
                        responder.respond(range, socket.getOutputStream());
                    }
                }
            } catch (IOException endOfTest) {
                // The socket closes when the test is done.
            }
        });
        serverThread.start();
    }

    @After
    public void stop() throws Exception {
        if (server != null) {
            server.close();
            serverThread.join(2000);
        }
    }

    private static void answer(OutputStream out, String status, String headers, String body)
            throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nConnection: close\r\n" + headers + "\r\n" + body)
            .getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String length(String body) {
        return "Content-Length: " + body.length() + "\r\n";
    }

    /** Serves BODY the way a well behaved server does. */
    private static final Responder WELL_BEHAVED = (range, out) -> {
        if (range < 0) {
            answer(out, "200 OK", length(BODY), BODY);
        } else if (range >= BODY.length()) {
            answer(out, "416 Range Not Satisfiable", length(""), "");
        } else {
            final String rest = BODY.substring((int) range);
            answer(out, "206 Partial Content",
                length(rest) + "Content-Range: bytes " + range + "-" + (BODY.length() - 1) + "/"
                    + BODY.length() + "\r\n",
                rest);
        }
    };

    private static final ResumableDownload.Progress NO_CANCEL = new ResumableDownload.Progress() {
        @Override
        public void onBytes(long done, long total) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    private boolean fetch(long expected) throws IOException {
        return ResumableDownload.fetch(url, target, expected, NO_CANCEL);
    }

    private File partial() {
        return ResumableDownload.partialOf(target);
    }

    @Test
    public void aWholeDownloadEndsUpUnderItsNameWithNothingLeftOver() throws IOException {
        serve(WELL_BEHAVED);
        assertTrue(fetch(BODY.length()));
        assertEquals(BODY, TestArchives.read(target));
        assertFalse(partial().exists());
        assertTrue(rangesAsked.isEmpty());
    }

    @Test
    public void aPartialFileIsContinued() throws IOException {
        serve(WELL_BEHAVED);
        TestArchives.write(partial(), BODY.substring(0, 7));
        assertTrue(fetch(BODY.length()));
        assertEquals(BODY, TestArchives.read(target));
        assertEquals(Collections.singletonList("7"), rangesAsked);
    }

    @Test
    public void aFileThatIsAlreadyWholeCostsNoRequest() throws IOException {
        serve(WELL_BEHAVED);
        TestArchives.write(target, BODY);
        assertTrue(fetch(BODY.length()));
        assertEquals(BODY, TestArchives.read(target));
    }

    /** What used to poison the alpha archive cache for good. */
    @Test
    public void aTruncatedTransferNeverAppearsUnderTheTargetName() throws IOException {
        serve((range, out) -> answer(out, "200 OK", length(BODY), BODY.substring(0, 5)));
        try {
            fetch(BODY.length());
            fail("a short transfer has to be an error");
        } catch (IOException expected) {
            assertFalse(target.exists());
            assertEquals(5, partial().length());
        }
    }

    @Test
    public void aTransferEndingCleanlyButShortOfThePromiseIsAnError() throws IOException {
        // No Content-Length, so only the size the API promised can tell.
        serve((range, out) -> answer(out, "200 OK", "", BODY.substring(0, 5)));
        try {
            fetch(BODY.length());
            fail("a short transfer has to be an error");
        } catch (IOException expected) {
            assertFalse(target.exists());
        }
    }

    @Test
    public void aServerIgnoringTheRangeStartsOver() throws IOException {
        serve((range, out) -> answer(out, "200 OK", length(BODY), BODY));
        TestArchives.write(partial(), "stale bytes");
        assertTrue(fetch(BODY.length()));
        assertEquals(BODY, TestArchives.read(target));
    }

    @Test
    public void aRefusedRangeStartsOver() throws IOException {
        serve((range, out) -> {
            if (range >= 0) {
                answer(out, "416 Range Not Satisfiable", length(""), "");
            } else {
                answer(out, "200 OK", length(BODY), BODY);
            }
        });
        TestArchives.write(partial(), "stale");
        assertTrue(fetch(-1));
        assertEquals(BODY, TestArchives.read(target));
    }

    @Test
    public void aRangeForAnotherStartIsNotSplicedOn() throws IOException {
        serve((range, out) -> {
            if (range >= 0) {
                final String rest = BODY.substring(3);
                answer(out, "206 Partial Content",
                    length(rest) + "Content-Range: bytes 3-19/20\r\n", rest);
            } else {
                answer(out, "200 OK", length(BODY), BODY);
            }
        });
        TestArchives.write(partial(), BODY.substring(0, 7));
        assertTrue(fetch(BODY.length()));
        assertEquals(BODY, TestArchives.read(target));
    }

    @Test
    public void aRangeOfAFileOfAnotherSizeIsNotSplicedOn() throws IOException {
        serve((range, out) -> {
            if (range >= 0) {
                answer(out, "206 Partial Content",
                    length("xxx") + "Content-Range: bytes 7-9/10\r\n", "xxx");
            } else {
                answer(out, "200 OK", length(BODY), BODY);
            }
        });
        TestArchives.write(partial(), "old bytes");
        assertTrue(fetch(BODY.length()));
        assertEquals(BODY, TestArchives.read(target));
    }

    @Test
    public void aPartialLargerThanTheDownloadIsThrownAway() throws IOException {
        serve(WELL_BEHAVED);
        TestArchives.write(partial(), BODY + BODY);
        assertTrue(fetch(BODY.length()));
        assertEquals(BODY, TestArchives.read(target));
        assertTrue(rangesAsked.isEmpty());
    }

    @Test
    public void aFileOfTheWrongSizeUnderTheTargetNameIsNotTrusted() throws IOException {
        serve(WELL_BEHAVED);
        TestArchives.write(target, "left behind by an older build");
        assertTrue(fetch(BODY.length()));
        assertEquals(BODY, TestArchives.read(target));
    }

    @Test
    public void aCancelKeepsWhatArrivedAndClaimsNothing() throws IOException {
        serve(WELL_BEHAVED);
        final boolean finished = ResumableDownload.fetch(url, target, BODY.length(),
            new ResumableDownload.Progress() {
                @Override
                public void onBytes(long done, long total) {
                }

                @Override
                public boolean isCancelled() {
                    return true;
                }
            });
        assertFalse(finished);
        assertFalse(target.exists());
    }

    @Test
    public void anErrorStatusIsAnError() throws IOException {
        serve((range, out) -> answer(out, "503 Service Unavailable", length(""), ""));
        try {
            fetch(-1);
            fail("a 503 has to be an error");
        } catch (IOException expected) {
            assertFalse(target.exists());
        }
    }

    /** How the launcher tells that the API has no patch for a build from it being down. */
    @Test
    public void aMissingDocumentIsAnAnswerAndAnyOtherErrorIsNot() throws Exception {
        serve((range, out) -> answer(out, "404 Not Found", length(""), ""));
        try {
            Http.getJson(url);
            fail("a 404 has to throw");
        } catch (Http.NotFoundException expected) {
            assertEquals(new Message(R.string.http_status, 404), expected.message);
        }
        server.close();
        serverThread.join(2000);
        serve((range, out) -> answer(out, "500 Internal Server Error", length(""), ""));
        try {
            Http.getJson(url);
            fail("a 500 has to throw");
        } catch (Http.NotFoundException wrong) {
            fail("a 500 does not say the document does not exist");
        } catch (Message.Failure expected) {
            assertEquals(new Message(R.string.http_status, 500), expected.message);
        }
    }

    @Test
    public void jsonComesBackParsed() throws Exception {
        final String json = "{\"alpha_build\":{\"version\":\"1.4.0.5591\",\"download_url\":\"https://x/a.7z\",\"size_in_bytes\":23838887}}";
        serve((range, out) -> answer(out, "200 OK", length(json), json));
        final ReleaseApi.ReleaseInfo info =
            ReleaseApi.ReleaseInfo.fromJson(Http.getJson(url).getJSONObject("alpha_build"));
        assertEquals("1.4.0.5591", info.version);
        assertEquals("https://x/a.7z", info.downloadUrl);
        assertEquals(23838887, info.sizeInBytes);
    }

    /** Nothing here could say what got installed, or show a version for it. */
    @Test
    public void aReleaseWithNoVersionIsNoAnswer() throws Exception {
        try {
            ReleaseApi.ReleaseInfo.fromJson(new org.json.JSONObject("{\"download_url\":\"https://x/a.7z\"}"));
            fail("no version has to throw");
        } catch (Message.Failure expected) {
            assertEquals(new Message(R.string.release_no_version), expected.message);
        }
    }

    @Test
    public void aReleaseWithoutAVersionedTagOrAnApkIsNotAnUpdate() throws Exception {
        assertNull(AppRelease.fromGithub(new org.json.JSONObject(
            "{\"tag_name\":\"macos-v1.60\",\"assets\":[{\"name\":\"keeperfx-android-arm64.apk\",\"browser_download_url\":\"https://x/a.apk\"}]}")));
        assertNull(AppRelease.fromGithub(new org.json.JSONObject(
            "{\"tag_name\":\"android-v\",\"assets\":[]}")));
        assertNull(AppRelease.fromGithub(new org.json.JSONObject(
            "{\"tag_name\":\"android-v1.60\",\"assets\":[{\"name\":\"other.apk\"}]}")));
    }

    @Test
    public void aReleaseCarriesItsVersionSizeAndChanges() throws Exception {
        final AppRelease release = AppRelease.fromGithub(new org.json.JSONObject(
            "{\"tag_name\":\"android-v1.60\",\"body\":\"intro - not a bullet\\n"
                + "## What it can do\\n- a feature\\n\\n"
                + "## Changes since 1.59\\n\\n- one\\n  - two\\n\\nOnly the newest 2 of 9 are listed.\\n\","
                + "\"assets\":[{\"name\":\"other.txt\"},{\"name\":\"keeperfx-android-arm64.apk\",\"size\":123,\"browser_download_url\":\"https://x/a.apk\"}]}"));
        assertEquals("1.60", release.versionName);
        assertEquals("https://x/a.apk", release.apkUrl);
        assertEquals(123, release.apkSize);
        assertEquals(java.util.Arrays.asList("one", "two"), release.changes);
        assertEquals("\u2022 one\n\u2022 two", release.changelog());
    }

    /** What a first release says about itself must not read as a list of changes in the updater. */
    @Test
    public void notesWithoutAComparisonHaveNoChanges() throws Exception {
        final AppRelease release = AppRelease.fromGithub(new org.json.JSONObject(
            "{\"tag_name\":\"android-v1.0\",\"body\":\"## What it can do\\n- a feature\\n- another\\n\","
                + "\"assets\":[{\"name\":\"keeperfx-android-arm64.apk\",\"browser_download_url\":\"https://x/a.apk\"}]}"));
        assertEquals(java.util.Collections.emptyList(), release.changes);
        assertEquals("", release.changelog());
    }

    /** The test above is only worth something while the workflow writes what it looks for. */
    @Test
    public void theWorkflowWritesTheHeadingTheUpdaterLooksFor() throws IOException {
        final String workflow = new String(java.nio.file.Files.readAllBytes(
            new File("../../.github/workflows/build-android.yml").toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(workflow.contains("echo \"" + AppRelease.CHANGES_HEADING + "${PREVIOUS#android-v}\""));
    }

    @Test
    public void aRedirectIsFollowedRelativeOrNot() throws IOException {
        assertEquals("https://cdn.example.test/a.7z",
            Http.redirectTarget("https://example.test/x", "https://cdn.example.test/a.7z"));
        assertEquals("https://example.test/y/a.7z",
            Http.redirectTarget("https://example.test/x", "/y/a.7z"));
    }

    @Test
    public void aRedirectCannotDropTheEncryption() throws IOException {
        for (String location : new String[] {"http://example.test/a.7z", "file:///data/secret"}) {
            try {
                Http.redirectTarget("https://example.test/x", location);
                fail(location);
            } catch (Message.Failure expected) {
                assertEquals(new Message(R.string.http_redirect_insecure), expected.message);
            }
        }
    }

    @Test
    public void aRedirectToNowhereSaysSo() throws IOException {
        serve((range, out) -> answer(out, "302 Found", length(""), ""));
        try {
            fetch(BODY.length());
            fail("a redirect without an address has to throw");
        } catch (Message.Failure expected) {
            assertEquals(new Message(R.string.http_redirect_nowhere), expected.message);
        }
    }

    @Test
    public void aRedirectIsWalkedToTheActualFile() throws IOException {
        final java.util.concurrent.atomic.AtomicInteger requests =
            new java.util.concurrent.atomic.AtomicInteger();
        serve((range, out) -> {
            if (requests.getAndIncrement() == 0) {
                answer(out, "302 Found", length("") + "Location: /moved/archive.7z\r\n", "");
            } else {
                answer(out, "200 OK", length(BODY), BODY);
            }
        });
        assertTrue(fetch(BODY.length()));
        assertEquals(BODY, TestArchives.read(target));
        assertEquals(2, requests.get());
    }
}
