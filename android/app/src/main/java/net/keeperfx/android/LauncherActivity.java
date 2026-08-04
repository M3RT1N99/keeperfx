/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file LauncherActivity.java
 *     Touch launcher for the Android port.
 * @par Purpose:
 *     Follows the same flow as the desktop Qt launcher: install or update the
 *     KeeperFX release, copy the files an original Dungeon Keeper has to
 *     supply, expose the settings that must be decided before the engine
 *     starts, then launch it.
 * @author   KeeperFX Team
 * @date     04 Aug 2026
 * @par  Copying and copyrights:
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 */
/******************************************************************************/
package net.keeperfx.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.List;

public class LauncherActivity extends Activity {

    private static final int REQUEST_PICK_KEEPERFX = 1001;
    private static final int REQUEST_PICK_ORIGINAL_DK = 1002;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Prefs prefs;

    private TextView keeperfxStatus;
    private TextView originalDkStatus;
    private TextView detailView;
    private ProgressBar progressBar;
    private Button installButton;
    private Button importKeeperfxButton;
    private Button importOriginalButton;
    private Button playButton;
    private RadioGroup inputModeGroup;
    private CheckBox noIntroBox;
    private CheckBox noSoundBox;
    private EditText extraArgsField;

    private DataImporter runningImport;
    private ReleaseDownloader runningDownload;
    private AppUpdater runningAppUpdate;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        prefs = new Prefs(this);

        keeperfxStatus = findViewById(R.id.keeperfxStatus);
        originalDkStatus = findViewById(R.id.originalDkStatus);
        detailView = findViewById(R.id.detail);
        progressBar = findViewById(R.id.progress);
        installButton = findViewById(R.id.installKeeperfx);
        importKeeperfxButton = findViewById(R.id.importKeeperfx);
        importOriginalButton = findViewById(R.id.importOriginalDk);
        playButton = findViewById(R.id.play);
        inputModeGroup = findViewById(R.id.inputMode);
        noIntroBox = findViewById(R.id.noIntro);
        noSoundBox = findViewById(R.id.noSound);
        extraArgsField = findViewById(R.id.extraArgs);

        switch (prefs.getInputMode()) {
            case Prefs.INPUT_TOUCH:
                inputModeGroup.check(R.id.inputTouch);
                break;
            case Prefs.INPUT_POINTER:
                inputModeGroup.check(R.id.inputPointer);
                break;
            default:
                inputModeGroup.check(R.id.inputAuto);
                break;
        }
        noIntroBox.setChecked(prefs.isNoIntro());
        noSoundBox.setChecked(prefs.isNoSound());
        extraArgsField.setText(prefs.getExtraArguments());

        inputModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.inputTouch) {
                prefs.setInputMode(Prefs.INPUT_TOUCH);
            } else if (checkedId == R.id.inputPointer) {
                prefs.setInputMode(Prefs.INPUT_POINTER);
            } else {
                prefs.setInputMode(Prefs.INPUT_AUTO);
            }
        });
        noIntroBox.setOnCheckedChangeListener((v, checked) -> prefs.setNoIntro(checked));
        noSoundBox.setOnCheckedChangeListener((v, checked) -> prefs.setNoSound(checked));
        extraArgsField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                prefs.setExtraArguments(s.toString());
            }
        });

        installButton.setOnClickListener(v -> confirmInstallOrUpdate());
        importKeeperfxButton.setOnClickListener(v -> pickFolder(REQUEST_PICK_KEEPERFX,
            R.string.import_keeperfx_title, R.string.import_keeperfx_explanation));
        importOriginalButton.setOnClickListener(v -> pickFolder(REQUEST_PICK_ORIGINAL_DK,
            R.string.import_original_title, R.string.import_original_explanation));
        playButton.setOnClickListener(v -> startGame());
        findViewById(R.id.removeData).setOnClickListener(v -> confirmRemoveData());
        findViewById(R.id.showLog).setOnClickListener(
            v -> startActivity(new Intent(this, LogActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (!busy) {
            checkForUpdateInBackground();
        }
    }

    // ---------------------------------------------------------------- status

    private void refreshStatus() {
        final boolean hasKeeperfx = GameData.isKeeperfxInstalled(this);
        final boolean hasOriginal = GameData.hasOriginalDkFiles(this);
        final String installed = prefs.getInstalledVersion();

        if (hasKeeperfx) {
            final String version = installed.isEmpty()
                ? getString(R.string.version_unknown) : installed;
            keeperfxStatus.setText(getString(R.string.status_keeperfx_ready, version,
                GameData.describeSize(GameData.gameDirectory(this))));
        } else {
            keeperfxStatus.setText(R.string.status_keeperfx_missing);
        }
        keeperfxStatus.setTextColor(getColor(hasKeeperfx ? R.color.kfx_ok : R.color.kfx_warn));

        if (hasOriginal) {
            originalDkStatus.setText(R.string.status_original_ready);
        } else {
            final int missing = GameData.findMissingOriginalDkFiles(this).size();
            originalDkStatus.setText(getString(R.string.status_original_missing, missing));
        }
        originalDkStatus.setTextColor(getColor(hasOriginal ? R.color.kfx_ok : R.color.kfx_warn));

        installButton.setText(hasKeeperfx
            ? getString(R.string.button_update_keeperfx)
            : getString(R.string.button_install_keeperfx));

        if (hasKeeperfx && hasOriginal) {
            detailView.setText(GameData.gameDirectory(this).getAbsolutePath());
        } else {
            final StringBuilder sb = new StringBuilder();
            if (!hasKeeperfx) {
                sb.append(getString(R.string.hint_install_keeperfx)).append("\n\n");
            }
            if (!hasOriginal) {
                sb.append(getString(R.string.hint_import_original)).append('\n');
                final List<String> missing = GameData.findMissingOriginalDkFiles(this);
                int shown = 0;
                for (String entry : missing) {
                    if (shown++ >= 8) {
                        sb.append("  … ").append(missing.size() - 8).append(" more\n");
                        break;
                    }
                    sb.append("  • ").append(entry).append('\n');
                }
            }
            detailView.setText(sb.toString().trim());
        }

        setBusy(busy);
    }

    /**
     * Quietly counts what could be downloaded so the button can say so before
     * the player taps it. Silent on failure; being offline must not get in the
     * way of starting the game.
     */
    private void checkForUpdateInBackground() {
        new Thread(() -> {
            final List<UpdateManager.Item> items = UpdateManager.check(this);
            mainHandler.post(() -> {
                if (!items.isEmpty() && !busy) {
                    installButton.setText(getString(R.string.button_updates_waiting, items.size()));
                }
            });
        }, "kfx-update-check").start();
    }

    // ------------------------------------------------------- install / update

    /**
     * One entry point for everything that can be fetched: the KeeperFX release,
     * the background music and a newer build of this app.
     */
    private void confirmInstallOrUpdate() {
        setBusy(true);
        progressBar.setIndeterminate(true);
        keeperfxStatus.setText(R.string.status_checking_updates);

        new Thread(() -> {
            final List<UpdateManager.Item> items = UpdateManager.check(this);
            mainHandler.post(() -> {
                setBusy(false);
                refreshStatus();
                if (items.isEmpty()) {
                    new AlertDialog.Builder(this)
                        .setTitle(R.string.updates_none_title)
                        .setMessage(R.string.updates_none)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }
                showUpdateDialog(items);
            });
        }, "kfx-update-scan").start();
    }

    private void showUpdateDialog(List<UpdateManager.Item> items) {
        final StringBuilder body = new StringBuilder();
        boolean replacesData = false;
        for (UpdateManager.Item item : items) {
            body.append("• ").append(item.title).append('\n');
            if (!item.detail.isEmpty()) {
                body.append("    ").append(item.detail).append('\n');
            }
            if (item.kind == UpdateManager.Kind.GAME_DATA) {
                replacesData = true;
            }
        }
        if (replacesData) {
            body.append('\n').append(getString(R.string.updates_replaces_data));
        }
        body.append('\n').append(getString(R.string.updates_use_wifi));

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.updates_available_title, items.size()))
            .setMessage(body.toString().trim())
            .setPositiveButton(R.string.updates_download_all,
                (dialog, which) -> runUpdates(items, 0))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Works through the list one item at a time; the app update comes last. */
    private void runUpdates(List<UpdateManager.Item> items, int index) {
        if (index >= items.size()) {
            setBusy(false);
            refreshStatus();
            return;
        }
        final UpdateManager.Item item = items.get(index);
        setBusy(true);
        progressBar.setIndeterminate(true);
        detailView.setText("");

        switch (item.kind) {
            case GAME_DATA:
                runDownloader(downloader -> downloader.run(), items, index);
                break;
            case MUSIC:
                runDownloader(downloader -> downloader.runMusic(), items, index);
                break;
            case APP:
                runAppUpdate(item, items, index);
                break;
        }
    }

    private interface DownloaderAction {
        void run(ReleaseDownloader downloader);
    }

    private void runDownloader(DownloaderAction action, List<UpdateManager.Item> items, int index) {
        final ReleaseDownloader downloader = new ReleaseDownloader(this,
            new ReleaseDownloader.Listener() {
                @Override
                public void onStage(String stage, int percent, String detail) {
                    mainHandler.post(() -> {
                        keeperfxStatus.setText(stage);
                        detailView.setText(detail);
                        if (percent >= 0) {
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(percent);
                        } else {
                            progressBar.setIndeterminate(true);
                        }
                    });
                }

                @Override
                public void onFinished(boolean success, String message) {
                    mainHandler.post(() -> {
                        runningDownload = null;
                        if (!success) {
                            setBusy(false);
                            refreshStatus();
                            showError(R.string.install_failed, message);
                            return;
                        }
                        runUpdates(items, index + 1);
                    });
                }
            });
        runningDownload = downloader;
        new Thread(() -> action.run(downloader), "kfx-download").start();
    }

    private void runAppUpdate(UpdateManager.Item item, List<UpdateManager.Item> items, int index) {
        if (!AppUpdater.canInstallPackages(this)) {
            setBusy(false);
            new AlertDialog.Builder(this)
                .setTitle(R.string.updates_permission_title)
                .setMessage(R.string.updates_permission)
                .setPositiveButton(R.string.updates_permission_open, (d, w) -> startActivity(
                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }

        final AppUpdater updater = new AppUpdater(this, new AppUpdater.Listener() {
            @Override
            public void onProgress(int percent, String detail) {
                mainHandler.post(() -> {
                    keeperfxStatus.setText(R.string.status_downloading_app);
                    detailView.setText(detail);
                    if (percent >= 0) {
                        progressBar.setIndeterminate(false);
                        progressBar.setProgress(percent);
                    } else {
                        progressBar.setIndeterminate(true);
                    }
                });
            }

            @Override
            public void onFinished(boolean success, String message) {
                mainHandler.post(() -> {
                    runningAppUpdate = null;
                    if (!success) {
                        setBusy(false);
                        refreshStatus();
                        showError(R.string.install_failed, message);
                        return;
                    }
                    // The system installer is now in front; continuing the list
                    // behind it would be confusing, and it is the last item.
                    runUpdates(items, index + 1);
                });
            }
        });
        runningAppUpdate = updater;
        new Thread(() -> updater.downloadAndInstall(item.appUpdate), "kfx-app-update").start();
    }

    // ---------------------------------------------------------------- import

    private void pickFolder(int requestCode, int titleRes, int explanationRes) {
        new AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(explanationRes)
            .setPositiveButton(R.string.import_choose, (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent, requestCode);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode != REQUEST_PICK_KEEPERFX && requestCode != REQUEST_PICK_ORIGINAL_DK) {
            return;
        }
        final Uri treeUri = data.getData();
        if (treeUri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Not every provider offers persistable permissions; the copy below
            // only needs the grant that is alive for this activity result.
        }
        startImport(treeUri, requestCode == REQUEST_PICK_KEEPERFX);
    }

    private void startImport(Uri treeUri, boolean keeperfxRelease) {
        setBusy(true);
        progressBar.setIndeterminate(true);
        detailView.setText("");
        if (keeperfxRelease) {
            keeperfxStatus.setText(R.string.status_importing);
        } else {
            originalDkStatus.setText(R.string.status_searching_original);
        }

        final DataImporter importer = new DataImporter(this, new DataImporter.Listener() {
            @Override
            public void onProgress(int filesCopied, String currentPath) {
                mainHandler.post(() -> detailView.setText(
                    getString(R.string.status_import_progress, filesCopied, currentPath)));
            }

            @Override
            public void onFinished(boolean success, String message) {
                mainHandler.post(() -> {
                    runningImport = null;
                    setBusy(false);
                    if (success && keeperfxRelease) {
                        // A hand picked folder carries no version we can trust.
                        prefs.setInstalledVersion("");
                    }
                    refreshStatus();
                    if (!success) {
                        showError(R.string.import_failed, message);
                    }
                });
            }
        });
        runningImport = importer;
        new Thread(() -> {
            if (keeperfxRelease) {
                importer.importKeeperfxTree(treeUri);
            } else {
                importer.importOriginalDkTree(treeUri);
            }
        }, "kfx-import").start();
    }

    // ----------------------------------------------------------------- misc

    private void setBusy(boolean value) {
        busy = value;
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        installButton.setEnabled(!value);
        importKeeperfxButton.setEnabled(!value);
        importOriginalButton.setEnabled(!value);
        playButton.setEnabled(!value && GameData.isComplete(this));
    }

    private void showError(int titleRes, String message) {
        new AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void confirmRemoveData() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.remove_title)
            .setMessage(R.string.remove_explanation)
            .setPositiveButton(R.string.remove_confirm, (dialog, which) -> {
                DataImporter.deleteRecursively(GameData.gameDirectory(this));
                prefs.setInstalledVersion("");
                refreshStatus();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void startGame() {
        if (!GameData.isComplete(this)) {
            refreshStatus();
            return;
        }
        // Refreshed on every launch rather than only at install time, so that
        // updating the APK also updates the configuration its engine expects.
        setBusy(true);
        new Thread(() -> {
            String failure = null;
            try {
                BundledConfig.install(this);
            } catch (Exception e) {
                failure = e.getMessage();
            }
            final String message = failure;
            mainHandler.post(() -> {
                setBusy(false);
                if (message != null) {
                    showError(R.string.config_failed, message);
                    return;
                }
                Intent intent = new Intent(this, GameActivity.class);
                intent.putExtra(GameActivity.EXTRA_ARGUMENTS, prefs.buildArguments(this));
                startActivity(intent);
            });
        }, "kfx-config").start();
    }

    @Override
    protected void onDestroy() {
        if (runningImport != null) {
            runningImport.cancel();
        }
        if (runningDownload != null) {
            runningDownload.cancel();
        }
        if (runningAppUpdate != null) {
            runningAppUpdate.cancel();
        }
        super.onDestroy();
    }
}
