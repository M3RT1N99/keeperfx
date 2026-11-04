package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class AppVersionTest {

    @Test
    public void minorIsComparedAsANumberNotAsText() {
        assertTrue(AppVersion.isNewer("1.10", "1.9"));
        assertTrue(AppVersion.isNewer("1.100", "1.99"));
        assertFalse(AppVersion.isNewer("1.9", "1.10"));
    }

    @Test
    public void theFirstReleaseIsBehindEveryLaterOne() {
        assertTrue(AppVersion.isNewer("1.1", "1.0"));
        assertFalse(AppVersion.isNewer("1.0", "1.1"));
    }

    @Test
    public void theSameVersionIsNotAnUpdate() {
        assertFalse(AppVersion.isNewer("1.52", "1.52"));
    }

    @Test
    public void aMajorBumpBeatsEveryMinorBelowIt() {
        assertTrue(AppVersion.isNewer("2.0", "1.99999"));
    }

    @Test
    public void whatDoesNotParseSortsLowest() {
        assertEquals(0, AppVersion.rank("unknown"));
        assertEquals(0, AppVersion.rank(""));
        assertEquals(0, AppVersion.rank("1."));
        assertEquals(0, AppVersion.rank("1"));
        assertEquals(0, AppVersion.rank("1.4.0"));
        assertFalse(AppVersion.isNewer("garbage", "1.0"));
        assertTrue(AppVersion.isNewer("1.0", "garbage"));
    }

    /**
     * The workflow decides with sort -V whether a version may go out, and installed apps
     * with this whether it is an update. Where the two differ a release could go out
     * that nothing installs. Skipped where there is no GNU sort, which is not on CI.
     */
    @Test
    public void itAgreesWithTheSortInTheWorkflow() throws Exception {
        final List<String> versions = new ArrayList<>(Arrays.asList("1.0", "1.1", "1.2", "1.9",
            "1.10", "1.11", "1.99", "1.100", "1.101", "1.999", "1.1000", "2.0", "2.1", "2.10", "10.0"));
        Collections.shuffle(versions, new Random(1));
        final List<String> byRank = new ArrayList<>(versions);
        byRank.sort(Comparator.comparingInt(AppVersion::rank));
        assertEquals(sortedByTheShell(versions), byRank);
    }

    private static List<String> sortedByTheShell(List<String> lines) throws Exception {
        final Process sort;
        try {
            sort = new ProcessBuilder("sort", "-V").redirectErrorStream(true).start();
        } catch (IOException noSort) {
            assumeTrue("no sort here", false);
            throw noSort;
        }
        sort.getOutputStream().write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        sort.getOutputStream().close();
        final String out = new String(sort.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assumeTrue("not a sort that knows -V", sort.waitFor() == 0);
        return Arrays.asList(out.trim().split("\\s+"));
    }
}
