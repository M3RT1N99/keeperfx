package net.keeperfx.android;

/** What a transfer job gets told when nobody is watching. */
class TestProgress implements TransferBundle.Progress {

    static final TransferBundle.Progress QUIET = new TestProgress();

    @Override
    public void onBytes(long done, long total) {
    }

    @Override
    public boolean isCancelled() {
        return false;
    }
}
