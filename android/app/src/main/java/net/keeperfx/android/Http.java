/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file Http.java
 *     The launcher's HTTP plumbing.
 * @author   KeeperFX Team
 * @date     04 Nov 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
package net.keeperfx.android;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class Http {

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_REDIRECTS = 5;

    /** The server says this does not exist, which can be an answer and not a failure. */
    static final class NotFoundException extends Message.Failure {
        NotFoundException() {
            super(R.string.http_status, HttpURLConnection.HTTP_NOT_FOUND);
        }
    }

    private Http() {
    }

    /**
     * Opens url, continuing from the given byte when it is above 0.
     *
     * HttpURLConnection will not follow a redirect that changes protocol, and
     * both GitHub and keeperfx.net hand off to a CDN, so the hops are walked
     * here.
     */
    static HttpURLConnection open(String url, long from) throws IOException {
        return open(url, from, null);
    }

    private static HttpURLConnection open(String url, long from, String accept) throws IOException {
        HttpURLConnection connection = connect(url, from, accept);
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
            connection = connect(redirectTarget(url, next), from, accept);
            url = connection.getURL().toString();
            status = connection.getResponseCode();
        }
        return connection;
    }

    /**
     * Where a redirect leads. A Location can be relative. Android refuses plain
     * http for this app anyway, but "file:" and friends would reach the cast below,
     * and a download should not depend on the platform noticing.
     */
    static String redirectTarget(String from, String location) throws IOException {
        final URL origin = new URL(from);
        final URL next = new URL(origin, location);
        if (origin.getProtocol().equals("https") && !next.getProtocol().equals("https")) {
            throw new IOException("Refusing a redirect from https to " + next.getProtocol());
        }
        return next.toString();
    }

    private static HttpURLConnection connect(String url, long from, String accept)
            throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        if (from > 0) {
            connection.setRequestProperty("Range", "bytes=" + from + "-");
        }
        if (accept != null) {
            connection.setRequestProperty("Accept", accept);
        }
        return connection;
    }

    static JSONObject getJson(String url) throws IOException, JSONException {
        final HttpURLConnection connection = open(url, 0, "application/json");
        try {
            final int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new NotFoundException();
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new Message.Failure(R.string.http_status, status);
            }
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                FileUtil.copy(in, body);
            }
            return new JSONObject(body.toString("UTF-8"));
        } finally {
            connection.disconnect();
        }
    }
}
