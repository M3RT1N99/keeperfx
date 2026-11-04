/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file Message.java
 *     A text for the player, named rather than written out.
 * @par Purpose:
 *     Most of the launcher's decisions run in classes that know nothing of Android and
 *     are driven by JVM tests, and those have no resources to read. They say which
 *     string they mean and what goes into it, and whoever stands where Android is turns
 *     that into words, in the language the launcher is set to. A context is no use for
 *     this further in: the application's own does not have that language.
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

import android.content.Context;

import java.io.IOException;
import java.util.Arrays;

final class Message {

    /** Stands in for a resource where there are no words, only names. */
    private static final int PLAIN = 0;

    /** What one string resource comes out as, its arguments already finished. */
    interface Strings {
        String get(int resource, Object[] args);
    }

    /** Whatever went wrong, in words the player can read. */
    static class Failure extends IOException {
        final Message message;

        Failure(Message message) {
            super(message.toString());
            this.message = message;
        }

        Failure(int resource, Object... args) {
            this(new Message(resource, args));
        }
    }

    final int resource;
    /** Strings, numbers, and other messages to be put in as words themselves. */
    final Object[] args;

    Message(int resource, Object... args) {
        this.resource = resource;
        this.args = args;
    }

    /** Nothing to translate: a path or a file name, or several, separated by commas. */
    static Message plain(Object... pieces) {
        return new Message(PLAIN, pieces);
    }

    boolean isPlain() {
        return resource == PLAIN;
    }

    /**
     * What an exception has to say. Everything thrown here with the player in mind can
     * say it in their language, and the rest is the platform's own words, such as a
     * timeout, which are better than nothing as the detail of a sentence.
     */
    static Message detailOf(Exception e) {
        if (e instanceof Failure) {
            return ((Failure) e).message;
        }
        return plain(e.getMessage() != null ? e.getMessage() : e.toString());
    }

    String text(Strings strings) {
        final Object[] finished = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            finished[i] = (args[i] instanceof Message) ? ((Message) args[i]).text(strings) : args[i];
        }
        if (!isPlain()) {
            return strings.get(resource, finished);
        }
        final StringBuilder joined = new StringBuilder();
        for (Object piece : finished) {
            joined.append(joined.length() > 0 ? ", " : "").append(piece);
        }
        return joined.toString();
    }

    /** The context decides the language: an activity's or a service's own, not the application's. */
    static Strings strings(Context context) {
        return (resource, args) -> (args.length == 0)
            ? context.getString(resource) : context.getString(resource, args);
    }

    String in(Context context) {
        return text(strings(context));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Message && resource == ((Message) other).resource
            && Arrays.deepEquals(args, ((Message) other).args);
    }

    @Override
    public int hashCode() {
        return 31 * resource + Arrays.deepHashCode(args);
    }

    /** For a log, where nothing can look the resource up. */
    @Override
    public String toString() {
        return "Message(0x" + Integer.toHexString(resource) + ", " + Arrays.deepToString(args) + ")";
    }
}
