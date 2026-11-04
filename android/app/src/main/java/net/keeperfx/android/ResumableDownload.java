/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file ResumableDownload.java
 *     HTTP download that picks up where it left off.
 * @par Purpose:
 *     The game data is around 360 MB. Losing all of it because the app was
 *     closed, the network dropped or the battery ran out is not acceptable, so
 *     the partial file is kept and continued with a Range request.
 * @par Comment:
 *     GitHub's release assets answer 206 Partial Content, which covers the app
 *     update and the KeeperFX release. The music archive on keeperfx.net does
 *     not advertise ranges; that case falls back to starting over, which is
 *     handled by checking the response rather than by assuming either way.
 *
 *     Everything in flight lives under a name of its own, so a file under the
 *     target's name is whole and nobody has to wonder about an archive they find.
 *     Name a download after its version to have a partial only ever continued
 *     with the bytes it started with.
 * @author   KeeperFX Team
 * @date     05 Aug 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
package net.keeperfx.android;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ResumableDownload {

    private static final String TAG = "KeeperFX";

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-\\d+/(\\d+|\\*)");

    public interface Progress {
        /**
         * @param done  bytes on disk, including anything carried over
         * @param total expected total, or -1 when the server did not say
         */
        void onBytes(long done, long total);

        boolean isCancelled();
    }

    private ResumableDownload() {
    }

    /** Where a download of target keeps its bytes until they are all there. */
    static File partialOf(File target) {
        return new File(target.getPath() + ".part");
    }

    /**
     * Whether a whole download of this size is already on disk. Without a size to
     * compare with, a file under the target's name still is whole.
     */
    static boolean isComplete(File target, long expectedSize) {
        return target.isFile() && (expectedSize <= 0 || target.length() == expectedSize);
    }

    /**
     * Downloads url into target, continuing an earlier attempt when the partial
     * file is there and the server allows it.
     *
     * The size is what the caller was promised by whatever API named the file,
     * or -1. Two protections come from knowing it: a partial file larger than
     * the whole download cannot be a piece of it and is thrown away instead of
     * resumed into garbage, and a transfer that ends early is an error rather
     * than a quietly truncated archive.
     *
     * @return true when the file is complete, false when cancelled. On a failure
     *         or a cancel the partial file stays for the next attempt.
     */
    public static boolean fetch(String url, File target, long expectedSize, Progress progress)
            throws IOException {
        if (isComplete(target, expectedSize)) {
            return true;
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        FileUtil.ensureDirectory(target.getParentFile());

        final File partial = partialOf(target);
        long existing = partial.isFile() ? partial.length() : 0;
        if (expectedSize > 0 && existing > expectedSize) {
            Log.w(TAG, "Partial file is larger than the download itself, starting over");
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            existing = 0;
        }
        if (expectedSize > 0 && existing == expectedSize) {
            // The process died between the last byte and the rename.
            moveIntoPlace(partial, target);
            return true;
        }

        HttpURLConnection connection = Http.open(url, existing);
        try {
            int status = connection.getResponseCode();
            if (existing > 0 && !canContinue(status, connection, existing, expectedSize)) {
                Log.i(TAG, "Cannot continue the partial file (HTTP " + status + "), starting over");
                connection.disconnect();
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
                existing = 0;
                connection = Http.open(url, 0);
                status = connection.getResponseCode();
            }
            final boolean append;
            if (status == HttpURLConnection.HTTP_PARTIAL && existing > 0) {
                append = true;
                Log.i(TAG, "Resuming at " + existing + " bytes");
            } else if (status == HttpURLConnection.HTTP_OK) {
                if (existing > 0) {
                    Log.i(TAG, "Server ignored the range request, starting over");
                }
                append = false;
                existing = 0;
            } else {
                throw new Message.Failure(R.string.http_status, status);
            }

            final long remaining = connection.getContentLengthLong();
            final long total = (remaining >= 0) ? existing + remaining : -1;
            final long expected = (total > 0) ? total : expectedSize;
            FileUtil.requireRoom(target.getParentFile(), expected - existing, R.string.no_room_download);
            long done = existing;

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(partial, append)) {
                final byte[] buffer = new byte[256 * 1024];
                long lastReported = -1;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (progress.isCancelled()) {
                        return false;
                    }
                    out.write(buffer, 0, read);
                    done += read;
                    // Report at most every 256 KB; the UI cannot use more.
                    if (done - lastReported >= 256 * 1024) {
                        lastReported = done;
                        progress.onBytes(done, total);
                    }
                }
                progress.onBytes(done, total);
            }
            // A stream can end cleanly without being complete - a proxy that
            // drops the connection, a chunked response cut short - and an
            // archive missing its tail unpacks into exactly the kind of torso
            // this launcher once installed without noticing.
            if (expected > 0 && done != expected) {
                if (done > expected) {
                    // More than the download is supposed to be: the file on
                    // disk is not this download. Resuming it would not help.
                    //noinspection ResultOfMethodCallIgnored
                    partial.delete();
                }
                throw new Message.Failure(R.string.download_cut_short,
                    GameData.describeBytes(done), GameData.describeBytes(expected));
            }
            moveIntoPlace(partial, target);
            return true;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Whether the answer to a range request really continues what is on disk. A 416
     * means the partial is stale. A 206 for another start or another total would
     * splice two different files together; an API can promise a new archive under
     * a name that was used before.
     */
    private static boolean canContinue(int status, HttpURLConnection connection, long existing,
            long expectedSize) {
        if (status == 416) {
            return false;
        }
        if (status != HttpURLConnection.HTTP_PARTIAL) {
            return true;
        }
        final String range = connection.getHeaderField("Content-Range");
        final Matcher match = CONTENT_RANGE.matcher(range != null ? range.trim() : "");
        if (!match.matches() || Long.parseLong(match.group(1)) != existing) {
            return false;
        }
        return expectedSize <= 0 || match.group(2).equals("*")
            || Long.parseLong(match.group(2)) == expectedSize;
    }

    private static void moveIntoPlace(File partial, File target) throws IOException {
        if (!partial.renameTo(target)) {
            throw new Message.Failure(R.string.file_cannot_write, target.getAbsolutePath());
        }
    }
}
