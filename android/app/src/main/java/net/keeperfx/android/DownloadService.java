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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
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
        /** Set once when a job failed; null otherwise. */
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
    private static volatile State currentState = new State(false, "", -1, "", null);
    private static volatile boolean cancelRequested = false;

    public static void addObserver(Observer observer) {
        observers.add(observer);
        observer.onDownloadState(currentState);
    }

    public static void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    public static State getState() {
        return currentState;
    }

    public static boolean isRunning() {
        return currentState.running;
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
        final Intent intent = new Intent(context, DownloadService.class)
            .setAction(ACTION_START)
            .putExtra(EXTRA_KINDS, kinds);
        context.startForegroundService(intent);
    }

    public static void cancel(Context context) {
        cancelRequested = true;
        context.startService(new Intent(context, DownloadService.class).setAction(ACTION_CANCEL));
    }

    // ------------------------------------------------------------ service

    private Thread worker;

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
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction()) || worker != null) {
            return START_NOT_STICKY;
        }

        final int[] kinds = intent.getIntArrayExtra(EXTRA_KINDS);
        if (kinds == null || kinds.length == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }

        cancelRequested = false;
        startForegroundWith(getString(R.string.notification_preparing), -1);

        worker = new Thread(() -> runJobs(kinds), "kfx-download-service");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runJobs(int[] kinds) {
        final UpdateManager.Kind[] all = UpdateManager.Kind.values();
        final List<UpdateManager.Kind> jobs = new ArrayList<>();
        for (int ordinal : kinds) {
            if (ordinal >= 0 && ordinal < all.length) {
                jobs.add(all[ordinal]);
            }
        }

        String failure = null;
        for (UpdateManager.Kind kind : jobs) {
            if (cancelRequested) {
                failure = getString(R.string.notification_cancelled);
                break;
            }
            failure = runJob(kind);
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
        final Object done = new Object();

        switch (kind) {
            case GAME_DATA:
            case MUSIC: {
                final ReleaseDownloader downloader =
                    new ReleaseDownloader(this, new ReleaseDownloader.Listener() {
                        @Override
                        public void onStage(String stage, int percent, String detail) {
                            publish(new State(true, stage, percent, detail, null));
                            updateNotification(stage, percent);
                        }

                        @Override
                        public void onFinished(boolean success, String message) {
                            failure[0] = success ? null : message;
                            synchronized (done) {
                                done.notifyAll();
                            }
                        }
                    });
                // The downloader is synchronous; run it here and wait on nothing.
                if (kind == UpdateManager.Kind.GAME_DATA) {
                    downloader.run();
                } else {
                    downloader.runMusic();
                }
                break;
            }
            case APP: {
                final AppUpdater updater = new AppUpdater(this, new AppUpdater.Listener() {
                    @Override
                    public void onProgress(int percent, String detail) {
                        final String stage = getString(R.string.status_downloading_app);
                        publish(new State(true, stage, percent, detail, null));
                        updateNotification(stage, percent);
                    }

                    @Override
                    public void onFinished(boolean success, String message) {
                        failure[0] = success ? null : message;
                    }
                });
                try {
                    final AppUpdater.Available available = AppUpdater.checkForUpdate(this);
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

    // ------------------------------------------------------------ notification

    private PendingIntent launcherIntent() {
        return PendingIntent.getActivity(this, 0,
            new Intent(this, LauncherActivity.class),
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
