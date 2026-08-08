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
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

public final class ResumableDownload {

    private static final String TAG = "KeeperFX";

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_REDIRECTS = 5;

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

    /**
     * Downloads url into target, continuing an earlier attempt when the file is
     * already partly there and the server allows it.
     *
     * @return true when the file is complete, false when cancelled.
     */
    public static boolean fetch(String url, File target, Progress progress) throws IOException {
        return fetch(url, target, -1, progress);
    }

    /**
     * Same, with the size the caller was promised by whatever API named the
     * file. Two protections come from knowing it: a partial file larger than
     * the whole download cannot be a piece of it and is thrown away instead of
     * resumed into garbage, and a transfer that ends early is an error rather
     * than a quietly truncated archive.
     */
    public static boolean fetch(String url, File target, long expectedSize, Progress progress)
            throws IOException {
        long existing = target.isFile() ? target.length() : 0;
        if (expectedSize > 0 && existing > expectedSize) {
            Log.w(TAG, "Partial file is larger than the download itself, starting over");
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            existing = 0;
        }

        HttpURLConnection connection = open(url, existing);
        try {
            final int status = connection.getResponseCode();
            boolean append = false;

            if (status == HttpURLConnection.HTTP_PARTIAL) {
                append = true;
                Log.i(TAG, "Resuming at " + existing + " bytes");
            } else if (status == HttpURLConnection.HTTP_OK) {
                if (existing > 0) {
                    Log.i(TAG, "Server ignored the range request, starting over");
                }
                existing = 0;
            } else if (status == 416) {
                // Requested range beyond the file: what is on disk is already
                // the whole thing, or it is stale. Start over to be sure.
                connection.disconnect();
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                existing = 0;
                connection = open(url, 0);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Server returned HTTP " + connection.getResponseCode());
                }
            } else {
                throw new IOException("Server returned HTTP " + status);
            }

            final long remaining = connection.getContentLengthLong();
            final long total = (remaining >= 0) ? existing + remaining : -1;
            long done = existing;

            final File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = openOutput(target, append)) {
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
            final long expected = (total > 0) ? total : expectedSize;
            if (expected > 0 && done != expected) {
                if (done > expected) {
                    // More than the download is supposed to be: the file on
                    // disk is not this download. Resuming it would not help.
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                }
                throw new IOException(
                    "Download ended at " + done + " of " + expected + " bytes");
            }
            return true;
        } finally {
            connection.disconnect();
        }
    }

    private static OutputStream openOutput(File target, boolean append) throws IOException {
        if (!append) {
            return new FileOutputStream(target);
        }
        // Seek to the end rather than trusting append mode across filesystems.
        final RandomAccessFile raf = new RandomAccessFile(target, "rw");
        raf.seek(raf.length());
        return new OutputStream() {
            @Override public void write(int b) throws IOException {
                raf.write(b);
            }

            @Override public void write(byte[] b, int off, int len) throws IOException {
                raf.write(b, off, len);
            }

            @Override public void close() throws IOException {
                raf.close();
            }
        };
    }

    /**
     * HttpURLConnection will not follow a redirect that changes protocol, and
     * both GitHub and keeperfx.net hand off to a CDN.
     */
    private static HttpURLConnection open(String url, long from) throws IOException {
        HttpURLConnection connection = connect(url, from);
        int hops = 0;
        int status = connection.getResponseCode();
        while ((status == HttpURLConnection.HTTP_MOVED_PERM
             || status == HttpURLConnection.HTTP_MOVED_TEMP
             || status == HttpURLConnection.HTTP_SEE_OTHER
             || status == 307 || status == 308) && hops++ < MAX_REDIRECTS) {
            final String next = connection.getHeaderField("Location");
            connection.disconnect();
            if (next == null) {
                throw new IOException("Redirect without a Location header");
            }
            connection = connect(next, from);
            status = connection.getResponseCode();
        }
        return connection;
    }

    private static HttpURLConnection connect(String url, long from) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        if (from > 0) {
            connection.setRequestProperty("Range", "bytes=" + from + "-");
        }
        return connection;
    }
}
