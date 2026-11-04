/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file NetworkGameService.java
 *     Keeps a network game reachable while its activity is away.
 * @par Purpose:
 *     The engine carries on behind a paused activity when other players depend
 *     on it (see src/android.cpp), but Android does not let a process in that state
 *     do so for long: measured on API 36, its network is cut about ten seconds
 *     after it leaves the screen and the process is frozen about a minute later.
 *     With the peers' 15 second timeout, that leaves no time for much more than a
 *     glance at a notification. A foreground service lifts both for as long as it
 *     runs. It does no work of its own.
 * @par Comment:
 *     It ends itself after a few minutes. A game nobody came back to is lost
 *     anyway, and this way a forgotten phone goes back to being frozen instead of
 *     running the engine in a pocket.
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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class NetworkGameService extends Service {

    private static final String TAG = "KeeperFX";
    private static final String CHANNEL_ID = "network_game";
    private static final int NOTIFICATION_ID = 2;
    private static final long KEEP_ALIVE_MS = 5 * 60 * 1000;
    private static final String ACTION_STOP = "net.keeperfx.android.NETWORK_GAME_STOP";

    /** Whether a start has been asked for since the last stop. Main thread only. */
    private static boolean startRequested = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Has to be called while the activity is still on screen, which the rules for
     * starting a foreground service ask for. Being refused is no reason to take
     * the game down with it: it then stays alive for as long as Android lets it.
     */
    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, NetworkGameService.class));
            startRequested = true;
        } catch (IllegalStateException e) {
            Log.w(TAG, "Cannot keep the network game alive in the background: " + e);
        }
    }

    /**
     * Sent as a request of its own rather than through stopService(). A quick return
     * could get there before the service has reached startForeground(), and Android
     * answers a service stopped at that point by killing the app. This way the
     * service gets through the start first.
     */
    static void stop(Context context) {
        if (startRequested) {
            startRequested = false;
            context.startService(
                new Intent(context, NetworkGameService.class).setAction(ACTION_STOP));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
            getString(R.string.notification_channel_network_game),
            NotificationManager.IMPORTANCE_LOW));
        final Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_network_game))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                new Intent(this, GameActivity.class), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(this::stopSelf, KEEP_ALIVE_MS);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
