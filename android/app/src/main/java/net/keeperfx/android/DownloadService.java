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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
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
    private static final long NOTIFICATION_INTERVAL_MS = 500;

    // ------------------------------------------------------------ shared state

    public static final class State {
        public final boolean running;
        public final String stage;
        /** 0..100, or -1 when the length is unknown. */
        public final int percent;
        public final String detail;
        /** Set when a job failed, until an observer has shown it; null otherwise. */
        public final String error;
        /** What a dialog about the failure is called. */
        public final int errorTitle;

        State(boolean running, String stage, int percent, String detail, String error,
                int errorTitle) {
            this.running = running;
            this.stage = stage;
            this.percent = percent;
            this.detail = detail;
            this.error = error;
            this.errorTitle = errorTitle;
        }
    }

    public interface Observer {
        void onDownloadState(State state);

        /** A packed file is ready, and whoever hears this is on screen to open the share sheet. */
        void onExportReady(File file);
    }

    private static final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final State IDLE = new State(false, "", -1, "", null, R.string.install_failed);
    private static volatile State currentState = IDLE;
    private static volatile boolean cancelRequested = false;
    /** Counts the imports that replaced the settings under the launcher's views. */
    private static volatile int settingsGeneration = 0;
    /** The file the last import read, until the launcher has offered to delete it. */
    private static volatile PhoneTransfer.ImportedFile importedFile = null;

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

    static int settingsGeneration() {
        return settingsGeneration;
    }

    static PhoneTransfer.ImportedFile takeImportedFile() {
        final PhoneTransfer.ImportedFile file = importedFile;
        importedFile = null;
        return file;
    }

    /** For the launcher's own cancel button; the notification may not be allowed to show. */
    static void requestCancel() {
        cancelRequested = true;
    }

    /** What is left of a finished job, when it points at something that is about to go. */
    static void cancelFinishedNotification(Context context) {
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
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

    static void startBundleExport(Context context) {
        start(context, new int[] {UpdateManager.Kind.BUNDLE_EXPORT.ordinal()});
    }

    /**
     * @param file the picked file. The read grant goes along with the intent, because
     *        the one the launcher holds ends with the activity and this job may not.
     */
    static void startBundleImport(Context context, Uri file) {
        start(context, new int[] {UpdateManager.Kind.BUNDLE_IMPORT.ordinal()},
            new Intent().setData(file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    private static void start(Context context, int[] kinds) {
        start(context, kinds, new Intent());
    }

    private static void start(Context context, int[] kinds, Intent intent) {
        // Running from this moment on, not from the first progress report: until
        // then the launcher would still let the player press Play, or start the
        // same thing again.
        publish(new State(true, context.getString(R.string.notification_preparing), -1, "", null,
            R.string.install_failed));
        try {
            context.startForegroundService(intent
                .setClass(context, DownloadService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_KINDS, kinds));
        } catch (IllegalStateException e) {
            // Android refuses when the app is not on screen any more.
            Log.w(TAG, "Cannot start the download service", e);
            publish(new State(false, "", -1, "",
                context.getString(R.string.job_cannot_start, e.getMessage()), R.string.install_failed));
        }
    }

    // ------------------------------------------------------------ service

    private Thread worker;
    /** Only touched by the worker, and by the main thread once the worker is done. */
    private volatile Intent installIntent;
    /** The same goes for these two. */
    private volatile File exportedFile;
    private volatile int doneMessage;
    private long lastNotified;
    private String lastStage = "";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(GameLanguage.localized(base));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Every time: for a channel that exists this changes the name, to the language chosen since. */
    @Override
    public void onCreate() {
        super.onCreate();
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
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
        final Uri file = intent.getData();
        cancelRequested = false;
        installIntent = null;
        exportedFile = null;
        doneMessage = R.string.notification_done;

        worker = new Thread(() -> runJobs(kinds, file), "kfx-download-service");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runJobs(int[] kinds, Uri file) {
        Message failure = null;
        int failureTitle = R.string.install_failed;
        for (int ordinal : kinds) {
            if (cancelRequested) {
                failure = new Message(R.string.job_cancelled);
                break;
            }
            final UpdateManager.Kind kind = UpdateManager.Kind.values()[ordinal];
            if (kind == UpdateManager.Kind.BUNDLE_EXPORT) {
                failureTitle = R.string.export_title;
            } else if (kind == UpdateManager.Kind.BUNDLE_IMPORT) {
                failureTitle = R.string.import_bundle_title;
            }
            failure = runJob(kind, file);
            if (failure != null) {
                break;
            }
        }

        final String finalFailure = (failure == null) ? null : failure.in(this);
        final File exported = (failure == null) ? exportedFile : null;
        publish(new State(false, "", -1, "", finalFailure, failureTitle));
        mainHandler.post(() -> {
            if (exported != null) {
                // Only for whoever is on screen now. Anyone coming back later has the
                // launcher's panel and the notification, and no use for a share sheet
                // that opens out of nowhere.
                for (Observer observer : observers) {
                    observer.onExportReady(exported);
                }
            }
            showFinishedNotification(finalFailure);
            worker = null;
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        });
    }

    /** @return null on success, otherwise what went wrong. */
    private Message runJob(UpdateManager.Kind kind, Uri file) {
        switch (kind) {
            case APP:
                return runAppUpdate();
            case BUNDLE_EXPORT:
                return runBundleExport();
            case BUNDLE_IMPORT:
                return runBundleImport(file);
            default:
                break;
        }
        // Asked again per job: a queue of them can have been going for a while.
        if (GameProcess.isAlive(this)) {
            return new Message(R.string.game_still_open);
        }
        final ReleaseDownloader downloader = new ReleaseDownloader(this, this::report);
        switch (kind) {
            case GAME_DATA:
                return downloader.run();
            case GAME_ALPHA:
                return downloader.runAlpha();
            case ALPHA_REVERT:
                return downloader.revertAlpha();
            default:
                return downloader.runMusic();
        }
    }

    private Message runAppUpdate() {
        final Message stage = new Message(R.string.status_downloading_app);
        try {
            final AppRelease available = AppUpdater.checkForUpdate();
            if (available == null) {
                return null; // someone else already installed it
            }
            report(stage, -1, null);
            final Intent install = AppUpdater.download(this, available, new JobProgress(stage));
            if (install == null) {
                return new Message(R.string.job_download_cancelled);
            }
            installIntent = install;
            startActivity(install);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "App update failed", e);
            return new Message(R.string.job_app_update_failed, Message.detailOf(e));
        }
    }

    private Message runBundleExport() {
        try {
            exportedFile = PhoneTransfer.runExport(this, new JobProgress(new Message(R.string.stage_packing)));
            return null;
        } catch (TransferBundle.CancelledException e) {
            return new Message(R.string.job_packing_cancelled);
        } catch (TransferBundle.RefusedException | FileUtil.NoRoomException e) {
            return e.message;
        } catch (Exception e) {
            Log.e(TAG, "Could not pack the installation", e);
            return new Message(R.string.job_packing_failed, Message.detailOf(e));
        }
    }

    private Message runBundleImport(Uri file) {
        try {
            PhoneTransfer.runImport(this, file,
                new JobProgress(new Message(R.string.stage_unpacking_import)));
            importedFile = PhoneTransfer.describeImported(this, file);
            settingsGeneration++;
            doneMessage = R.string.import_bundle_done_notification;
            return null;
        } catch (TransferBundle.CancelledException e) {
            return new Message(R.string.job_import_cancelled);
        } catch (TransferBundle.RefusedException | FileUtil.NoRoomException e) {
            return e.message;
        } catch (Exception e) {
            Log.e(TAG, "Could not import the picked file", e);
            return new Message(R.string.job_import_failed, Message.detailOf(e));
        }
    }

    /** What a transfer of known length tells the launcher and the notification. */
    private final class JobProgress implements ResumableDownload.Progress, TransferBundle.Progress {
        private final Message stage;

        JobProgress(Message stage) {
            this.stage = stage;
        }

        @Override
        public void onBytes(long done, long total) {
            report(stage, (total > 0) ? (int) (done * 100 / total) : -1,
                new Message(R.string.progress_bytes,
                    GameData.describeBytes(done), GameData.describeBytes(total)));
        }

        @Override
        public boolean isCancelled() {
            return cancelRequested;
        }
    }

    /** @param detail null when there is nothing to add. */
    private void report(Message stageMessage, int percent, Message detail) {
        final String stage = stageMessage.in(this);
        publish(new State(true, stage, percent, (detail == null) ? "" : detail.in(this), null,
            R.string.install_failed));
        // A notification per 256 KB, or per percent on a fast line, is more than
        // Android accepts: it starts dropping them, and then also the last one that
        // says how it all ended. Seen leaving "Downloading the music" up for good.
        final long now = SystemClock.elapsedRealtime();
        if (!stage.equals(lastStage) || now - lastNotified >= NOTIFICATION_INTERVAL_MS) {
            lastNotified = now;
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

    /**
     * Neither the system's installer nor a share sheet can open by itself when the launcher
     * is not on screen, and Android drops that without a word, so what is left to do goes
     * in a notification. The packing in particular can easily outlast the player's patience
     * with looking at the launcher.
     */
    private void showFinishedNotification(String failure) {
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (failure != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(failure, -1, false));
        } else if (installIntent != null) {
            manager.notify(NOTIFICATION_ID, tapToContinue(R.string.notification_install_update,
                android.R.drawable.stat_sys_download_done, 1, installIntent));
        } else if (exportedFile != null) {
            manager.notify(NOTIFICATION_ID, tapToContinue(R.string.export_ready_notification,
                android.R.drawable.stat_sys_upload_done, 2,
                PhoneTransfer.shareSheet(this, exportedFile).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        } else {
            manager.notify(NOTIFICATION_ID, buildNotification(getString(doneMessage), -1, false));
        }
    }

    private Notification tapToContinue(int text, int icon, int requestCode, Intent next) {
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(text))
            .setSmallIcon(icon)
            .setContentIntent(PendingIntent.getActivity(this, requestCode, next,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build();
    }

    /** Polled by the downloaders so a cancel takes effect mid transfer. */
    static boolean isCancelRequested() {
        return cancelRequested;
    }
}
