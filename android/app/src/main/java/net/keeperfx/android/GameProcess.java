/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file GameProcess.java
 *     Whether the engine is alive, asked from outside its process.
 * @par Purpose:
 *     The engine owns a process, the way the exe does on a desktop. SDL does not
 *     let a second activity start in a process whose engine has run, and a native
 *     crash takes the process with it. The launcher's process is the one that
 *     changes the installation and the settings, and a static of the engine's
 *     cannot be read from there. This can.
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

import android.app.ActivityManager;
import android.content.Context;

import java.util.List;

final class GameProcess {

    /** As android:process says for GameActivity and NetworkGameService in the manifest. */
    private static final String NAME_AFTER_PACKAGE = ":game";

    private GameProcess() {
    }

    /**
     * Whether the engine may be alive, paused behind the launcher, with its files open.
     * That process only lives as long as its activity, so its being there is the answer.
     */
    static boolean isAlive(Context context) {
        final List<ActivityManager.RunningAppProcessInfo> running =
            context.getSystemService(ActivityManager.class).getRunningAppProcesses();
        if (running == null) {
            return false;
        }
        final String name = context.getPackageName() + NAME_AFTER_PACKAGE;
        for (ActivityManager.RunningAppProcessInfo process : running) {
            if (name.equals(process.processName)) {
                return true;
            }
        }
        return false;
    }
}
