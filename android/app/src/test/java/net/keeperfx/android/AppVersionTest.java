package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppVersionTest {

    @Test
    public void minorIsComparedAsANumberNotAsText() {
        assertTrue(AppVersion.isNewer("1.100", "1.99"));
        assertTrue(AppVersion.isNewer("1.57", "1.52"));
        assertFalse(AppVersion.isNewer("1.52", "1.57"));
    }

    @Test
    public void theSameVersionIsNotAnUpdate() {
        assertFalse(AppVersion.isNewer("1.52", "1.52"));
    }

    @Test
    public void aMajorBumpBeatsEveryMinorBelowIt() {
        assertTrue(AppVersion.isNewer("2.0", "1.99999"));
    }

    /** Builds from before the port had its own number find their way to every newer one. */
    @Test
    public void theOldEngineStyleVersionsSortBelowEveryPortVersion() {
        assertEquals(0, AppVersion.rank("1.4.0.5319"));
        assertEquals(0, AppVersion.rank("1.4.0_5319"));
        assertTrue(AppVersion.isNewer("1.0", "1.4.0.5319"));
        assertTrue(AppVersion.isNewer("1.0", "1.4.0_5319"));
    }

    /** An old tag that is still around must never be offered over a current build. */
    @Test
    public void anOldEngineStyleTagIsNeverAnUpdate() {
        assertFalse(AppVersion.isNewer("1.4.0.5324", "1.52"));
    }

    @Test
    public void whatDoesNotParseSortsLowest() {
        assertEquals(0, AppVersion.rank("unknown"));
        assertEquals(0, AppVersion.rank(""));
        assertEquals(0, AppVersion.rank("1."));
        assertFalse(AppVersion.isNewer("garbage", "1.0"));
        assertTrue(AppVersion.isNewer("1.0", "garbage"));
    }
}
