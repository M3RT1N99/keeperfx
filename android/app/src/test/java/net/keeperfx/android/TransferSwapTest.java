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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class TransferSwapTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** What Android does to a process: nothing after this runs, no finally is any help. */
    private static final class Died extends RuntimeException {
    }

    private File bundle;
    private Map<String, String> whatASwapMustEndWith;

    /** A phone with something of its own on it. */
    private TransferPhone receiver(String name) throws IOException {
        final TransferPhone phone = new TransferPhone(folder.newFolder(name));
        phone.installEverything("B");
        phone.prefs.setExtraArguments("-own");
        phone.prefs.setLanguage("FRE");
        return phone;
    }

    private static TransferSwap swapWithSteps(TransferPhone phone, AtomicInteger stepsLeft,
            boolean dies) {
        return new TransferSwap(phone.install, phone.manifests, phone.backup,
            new File[] {phone.alphaCache, phone.downloads, phone.updates, phone.exports},
            phone.work, phone.trash, phone.prefs::replaceWithTransferred) {
            @Override
            void move(File from, File to) throws IOException {
                if (stepsLeft.getAndDecrement() <= 0) {
                    if (dies) {
                        throw new Died();
                    }
                    throw new IOException("the disk says no");
                }
                super.move(from, to);
            }
        };
    }

    private void install(TransferSwap swap) throws IOException {
        try (InputStream in = new FileInputStream(bundle)) {
            swap.install(in, Collections.<String>emptySet(), TransferPhone.APP, TestProgress.QUIET);
        }
    }

    @Before
    public void aFileFromAnotherPhone() throws IOException {
        final TransferPhone a = new TransferPhone(folder.newFolder("a"));
        a.installEverything("A");
        a.prefs.setNoIntro(true);
        a.prefs.setLanguage("GER");
        bundle = a.export();
        final TransferPhone done = receiver("done");
        done.importFile(bundle);
        whatASwapMustEndWith = done.snapshot();
    }

    @Test
    public void aSwapThatRanThroughLeavesOnlyTheNewInstallation() throws IOException {
        assertTrue(whatASwapMustEndWith.containsKey("keeperfx/keeperfx.cfg"));
        assertTrue(whatASwapMustEndWith.get("keeperfx/keeperfx.cfg").startsWith("A "));
        assertFalse(whatASwapMustEndWith.containsKey("transfer/"));
        assertFalse(whatASwapMustEndWith.containsKey("transfer-trash/"));
    }

    /**
     * A process can die between any two steps. Whichever one it was, the next start
     * has to end up where an uninterrupted swap does, down to the settings.
     */
    @Test
    public void aProcessDyingAtAnyStepIsFinishedAtTheNextStart() throws IOException {
        int death = 0;
        int deaths = 0;
        while (true) {
            final TransferPhone phone = receiver("die" + death);
            final Map<String, String> before = phone.snapshot();
            boolean died = false;
            try {
                install(swapWithSteps(phone, new AtomicInteger(death), true));
            } catch (Died expected) {
                died = true;
                deaths++;
            }
            if (!died) {
                assertTrue("a swap takes several steps, " + deaths + " were tried", deaths >= 6);
                return;
            }
            assertTrue("step " + death + ": something happened before it died",
                phone.work.exists() || before.equals(phone.snapshot()));
            // The next process: a new object, with only the disk to go by.
            phone.swap().recover();
            phone.swap().emptyTrash();
            assertEquals("died at step " + death, whatASwapMustEndWith, phone.snapshot());
            death++;
        }
    }

    /** The other half of a process death: after everything moved, before the settings. */
    @Test
    public void aDeathOnTheSettingsIsFinishedAtTheNextStartToo() throws IOException {
        final TransferPhone phone = receiver("settings");
        final TransferSwap dying = new TransferSwap(phone.install, phone.manifests, phone.backup,
            new File[0], phone.work, phone.trash, (values, release, alpha) -> {
                throw new Died();
            });
        try {
            install(dying);
            fail("it should have died");
        } catch (Died expected) {
            assertTrue(phone.work.exists());
        }
        assertEquals("-own", phone.prefs.getExtraArguments());
        assertEquals("FRE", phone.prefs.getLanguage());

        phone.swap().recover();
        phone.swap().emptyTrash();

        assertEquals(whatASwapMustEndWith, phone.snapshot());
    }

    /** Running again on a finished disk has to find nothing to do, whatever it was. */
    @Test
    public void recoveringTwiceIsNoDifferentFromOnce() throws IOException {
        final TransferPhone phone = receiver("twice");
        try {
            install(swapWithSteps(phone, new AtomicInteger(1), true));
            fail("it should have died");
        } catch (Died expected) {
            phone.swap().recover();
            phone.swap().recover();
            phone.swap().emptyTrash();
            assertEquals(whatASwapMustEndWith, phone.snapshot());
        }
    }

    /**
     * A step can also just fail while the process lives on. Then nothing has happened
     * as far as anyone can tell.
     */
    @Test
    public void aStepFailingAtAnyPointPutsEverythingBack() throws IOException {
        // Seven moves in a whole swap. The last one only clears up after a finished swap.
        for (int failure = 0; failure < 7; failure++) {
            final TransferPhone phone = receiver("fail" + failure);
            final Map<String, String> before = phone.snapshot();
            final AtomicInteger steps = new AtomicInteger(failure);
            final TransferSwap failing = new TransferSwap(phone.install, phone.manifests, phone.backup,
                new File[0], phone.work, phone.trash, phone.prefs::replaceWithTransferred) {
                private boolean failed = false;

                @Override
                void move(File from, File to) throws IOException {
                    // One failure only, so that the way back can succeed.
                    if (!failed && steps.getAndDecrement() <= 0) {
                        failed = true;
                        throw new IOException("the disk says no");
                    }
                    super.move(from, to);
                }
            };
            try {
                install(failing);
                // Past the last move: the swap had no step left to fail.
                assertEquals(whatASwapMustEndWith.get("keeperfx/keeperfx.cfg"),
                    phone.snapshot().get("keeperfx/keeperfx.cfg"));
            } catch (IOException expected) {
                assertEquals("failed at step " + failure, before, phone.snapshot());
            }
        }
    }

    /**
     * Moving the old folder aside worked and nothing after it did, the way back
     * included. That folder is all there is now, and the next attempt, even one that
     * goes nowhere, must not throw it out with the rest.
     */
    @Test
    public void aSwapStuckHalfWayIsNotThrownAwayByTheNextAttempt() throws IOException {
        final TransferPhone phone = receiver("stuck");
        try {
            install(swapWithSteps(phone, new AtomicInteger(1), false));
            fail("it could not move anything after the first folder");
        } catch (IOException expected) {
            assertFalse("half way, and it knows", phone.install.exists());
        }
        final File notABundle = folder.newFile();
        TestArchives.write(notABundle, "not a bundle");
        try {
            phone.importFile(notABundle);
            fail("this file has to be refused");
        } catch (TransferBundle.RefusedException expected) {
            assertEquals(whatASwapMustEndWith, phone.snapshot());
        }
    }

    @Test
    public void settingsThatCannotBeWrittenPutEverythingBack() throws IOException {
        final TransferPhone phone = receiver("nowrite");
        final Map<String, String> before = phone.snapshot();
        phone.preferences.refuseCommit = true;
        try {
            phone.importFile(bundle);
            fail("it could not write the settings");
        } catch (IOException expected) {
            assertEquals(before, phone.snapshot());
        }
    }

    @Test
    public void aFreshPhoneCanBeInterruptedToo() throws IOException {
        int death = 0;
        while (true) {
            final TransferPhone fresh = new TransferPhone(folder.newFolder("fresh" + death));
            boolean died = false;
            try {
                install(swapWithSteps(fresh, new AtomicInteger(death), true));
            } catch (Died expected) {
                died = true;
            }
            if (!died) {
                return;
            }
            fresh.swap().recover();
            fresh.swap().emptyTrash();
            final Map<String, String> ended = fresh.snapshot();
            assertEquals("died at step " + death, whatASwapMustEndWith.get("keeperfx/keeperfx.cfg"),
                ended.get("keeperfx/keeperfx.cfg"));
            assertEquals("died at step " + death, whatASwapMustEndWith.get("manifests/stable.txt"),
                ended.get("manifests/stable.txt"));
            assertEquals("1.4.0", fresh.prefs.getInstalledVersion());
            assertFalse(fresh.work.exists());
            death++;
        }
    }

    /** An unpack with nobody coming back for it, the kill the device test does too. */
    @Test
    public void whatADeadUnpackLeftIsThrownOutAtTheNextStart() throws IOException {
        final TransferPhone phone = receiver("dead");
        final Map<String, String> before = phone.snapshot();
        final TransferBundle.Staging staging = phone.swap().staging;
        TestArchives.write(new File(staging.install, "save/half.sav"), "half");
        TestArchives.write(new File(staging.manifests, "stable.txt"), "1\tx\n");

        phone.swap().recover();
        phone.swap().emptyTrash();

        assertEquals(before, phone.snapshot());
    }

    @Test
    public void nothingLeftBehindMeansNothingToDo() throws IOException {
        final TransferPhone phone = receiver("nothing");
        final Map<String, String> before = phone.snapshot();
        phone.swap().recover();
        phone.swap().emptyTrash();
        assertEquals(before, phone.snapshot());
    }

    @Test
    public void whatAnEarlierAttemptLeftIsNoObstacle() throws IOException {
        final TransferPhone phone = receiver("again");
        TestArchives.write(new File(phone.swap().staging.install, "save/fx1g0000.sav"), "left over");
        phone.importFile(bundle);
        assertEquals(whatASwapMustEndWith, phone.snapshot());
    }
}
