package net.keeperfx.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogClipboardTest {

    @Test
    public void aLogThatFitsGoesWhole() {
        assertEquals("one\ntwo\n", LogActivity.clipboardPart("one\ntwo\n", 8));
    }

    @Test
    public void aLongLogKeepsItsEndFromAWholeLineAndSaysSomethingIsMissing() {
        assertEquals("… earlier lines omitted …\n\nthird line\n",
            LogActivity.clipboardPart("first line\nsecond line\nthird line\n", 15));
    }

    /** All of what the log screen holds comes to 1.05 MB in the parcel, which ended the app. */
    @Test
    public void theLargestLogTheScreenHoldsFitsInATransaction() {
        final StringBuilder log = new StringBuilder();
        while (log.length() < 512 * 1024) {
            log.append("a line of a long session\n");
        }
        final String part = LogActivity.clipboardPart(log.toString(),
            LogActivity.MAX_CLIPBOARD_CHARS);
        assertTrue(String.valueOf(part.length()), part.length() * 2 < 512 * 1024);
    }
}
