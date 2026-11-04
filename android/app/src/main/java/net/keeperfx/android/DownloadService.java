/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file DownloadService.java
 *     Runs the downloads outside the launcher's lifetime.
 * @par Purpose:
 *     The game data is around 360 MB. A plain thread owned by the activity dies
 *     when Android reclaims the process after the player presses Home, which
 *     with a download that long is a near certainty. A foreground service with
 *     a progress notification is the supported way to keep working, and it also
 *     gives the player somewhere to watch and cancel from.
 * @par Comment:
 *     The activity does not bind. It registers as an observer of the static
 *     state this service publishes, which keeps the two independent: closing
 *     and reopening the launcher mid download just re-attaches to whatever is
 *     still running.
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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DownloadService extends Service {

    private static final String TAG = "KeeperFX";

    public static final String ACTION_START = "net.keeperfx.android.DOWNLOAD_START";
    public static final String ACTION_CANCEL = "net.keeperfx.android.DOWNLOAD_CANCEL";
    /** Ordinals of UpdateManager.Kind, in the order they should run. */
    public static final String EXTRA_KINDS = "kinds";

    private static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 1;

    // ------------------------------------------------------------ shared state

    public static final class State {
        public final boolean running;
        public final String stage;
        /** 0..100, or -1 when the length is unknown. */
        public final int percent;
        public final String detail;
        /** Set when a job failed, until an observer has shown it; null otherwise. */
        public final String error;

        State(boolean running, String stage, int percent, String detail, String error) {
            this.running = running;
            this.stage = stage;
            this.percent = percent;
            this.detail = detail;
            this.error = error;
        }
    }

    public interface Observer {
        void onDownloadState(State state);
    }

    private static final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final State IDLE = new State(false, "", -1, "", null);
    private static volatile State currentState = IDLE;
    private static volatile boolean cancelRequested = false;

    public static void addObserver(Observer observer) {
        observers.add(observer);
        observer.onDownloadState(currentState);
    }

    public static void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    public static boolean isRunning() {
        return currentState.running;
    }

    /**
     * A failure is shown once. It stays in the state until then because the launcher
     * may be away when it happens, but an observer gets the state again every time it
     * comes back, which would raise the same dialog on every return to the launcher.
     */
    static void errorShown() {
        if (!currentState.running) {
            currentState = IDLE;
        }
    }

    private static void publish(State state) {
        currentState = state;
        mainHandler.post(() -> {
            for (Observer observer : observers) {
                observer.onDownloadState(state);
            }
        });
    }

    // ------------------------------------------------------------ entry points

    public static void start(Context context, List<UpdateManager.Item> items) {
        final int[] kinds = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            kinds[i] = items.get(i).kind.ordinal();
        }
        start(context, kinds);
    }

    /** Takes the alpha patch off, with the same protection from the process going away. */
    static void startAlphaRevert(Context context) {
        start(context, new int[] {UpdateManager.Kind.ALPHA_REVERT.ordinal()});
    }

    private static void start(Context context, int[] kinds) {
        // Running from this moment on, not from the first progress report: until
        // then the launcher would still let the player press Play, or start the
        // same thing again.
        publish(new State(true, context.getString(R.string.notification_preparing), -1, "", null));
        try {
            context.startForegroundService(new Intent(context, DownloadService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_KINDS, kinds));
        } catch (IllegalStateException e) {
            // Android refuses when the app is not on screen any more.
            Log.w(TAG, "Cannot start the download service", e);
            publish(new State(false, "", -1, "", "Cannot start: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------ service

    private Thread worker;
    /** Only touched by the worker, and by the main thread once the worker is done. */
    private volatile Intent installIntent;
    private int lastPercent;
    private String lastStage = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelRequested = true;
            if (worker == null) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction()) || worker != null) {
            return START_NOT_STICKY;
        }

        // Before anything else: Android kills an app that was started with
        // startForegroundService() and did not get here.
        startForegroundWith(getString(R.string.notification_preparing), -1);
        final int[] kinds = intent.getIntArrayExtra(EXTRA_KINDS);
        cancelRequested = false;
        installIntent = null;

        worker = new Thread(() -> runJobs(kinds), "kfx-download-service");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runJobs(int[] kinds) {
        String failure = null;
        for (int ordinal : kinds) {
            if (cancelRequested) {
                failure = getString(R.string.notification_cancelled);
                break;
            }
            failure = runJob(UpdateManager.Kind.values()[ordinal]);
            if (failure != null) {
                break;
            }
        }

        final String finalFailure = failure;
        publish(new State(false, "", -1, "", finalFailure));
        mainHandler.post(() -> {
            showFinishedNotification(finalFailure);
            worker = null;
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        });
    }

    /** @return null on success, otherwise the failure message. */
    private String runJob(UpdateManager.Kind kind) {
        final String[] failure = new String[1];

        switch (kind) {
            case GAME_DATA:
            case GAME_ALPHA:
            case ALPHA_REVERT:
            case MUSIC: {
                final ReleaseDownloader downloader =
                    new ReleaseDownloader(this, new ReleaseDownloader.Listener() {
                        @Override
                        public void onStage(String stage, int percent, String detail) {
                            report(stage, percent, detail);
                        }

                        @Override
                        public void onFinished(boolean success, String message) {
                            failure[0] = success ? null : message;
                        }
                    });
                if (kind == UpdateManager.Kind.GAME_DATA) {
                    downloader.run();
                } else if (kind == UpdateManager.Kind.GAME_ALPHA) {
                    downloader.runAlpha();
                } else if (kind == UpdateManager.Kind.ALPHA_REVERT) {
                    downloader.revertAlpha();
                } else {
                    downloader.runMusic();
                }
                break;
            }
            case APP: {
                final AppUpdater updater = new AppUpdater(this, new AppUpdater.Listener() {
                    @Override
                    public void onProgress(int percent, String detail) {
                        report(getString(R.string.status_downloading_app), percent, detail);
                    }

                    @Override
                    public void onReadyToInstall(Intent install) {
                        installIntent = install;
                    }

                    @Override
                    public void onFinished(boolean success, String message) {
                        failure[0] = success ? null : message;
                    }
                });
                try {
                    final AppRelease available = AppUpdater.checkForUpdate(this);
                    if (available == null) {
                        return null; // someone else already installed it
                    }
                    updater.downloadAndInstall(available);
                } catch (Exception e) {
                    Log.e(TAG, "App update failed", e);
                    return "App update failed: " + e.getMessage();
                }
                break;
            }
        }
        return failure[0];
    }

    private void report(String stage, int percent, String detail) {
        publish(new State(true, stage, percent, detail, null));
        // A notification per 256 KB is more than Android accepts: it starts dropping
        // them, and then possibly the last one that says how it all ended.
        if (percent != lastPercent || !stage.equals(lastStage)) {
            lastPercent = percent;
            lastStage = stage;
            updateNotification(stage, percent);
        }
    }

    // ------------------------------------------------------------ notification

    private PendingIntent launcherIntent() {
        // Back to the launcher that is already open rather than a second one on top.
        return PendingIntent.getActivity(this, 0,
            new Intent(this, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent cancelIntent() {
        return PendingIntent.getService(this, 0,
            new Intent(this, DownloadService.class).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification(String text, int percent, boolean ongoing) {
        final Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(launcherIntent())
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true);
        if (ongoing) {
            if (percent >= 0) {
                builder.setProgress(100, percent, false);
            } else {
                builder.setProgress(0, 0, true);
            }
            builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(android.R.string.cancel), cancelIntent()).build());
        } else {
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done);
        }
        return builder.build();
    }

    private void startForegroundWith(String text, int percent) {
        final Notification notification = buildNotification(text, percent, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text, int percent) {
        final NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(text, percent, true));
    }

    private void showFinishedNotification(String failure) {
        final NotificationManager manager = getSystemService(NotificationManager.class);
        final Intent install = (failure == null) ? installIntent : null;
        if (install != null) {
            // The installer cannot open itself when the launcher is not on screen,
            // and Android drops that without a word. The notification still can.
            manager.notify(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_install_update))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(PendingIntent.getActivity(this, 1, install,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build());
            return;
        }
        final String text = (failure == null)
            ? getString(R.string.notification_done)
            : failure;
        manager.notify(NOTIFICATION_ID, buildNotification(text, -1, false));
    }

    /** Polled by the downloaders so a cancel takes effect mid transfer. */
    static boolean isCancelRequested() {
        return cancelRequested;
    }
}
