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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class LauncherActivity extends Activity implements DownloadService.Observer {

    private static final int REQUEST_PICK_KEEPERFX = 1001;
    private static final int REQUEST_PICK_ORIGINAL_DK = 1002;
    private static final int REQUEST_PICK_ORIGINAL_DK_ZIP = 1003;
    private static final int REQUEST_PICK_BUNDLE = 1004;

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
    private Button removeDataButton;
    private Button updateAppButton;
    private Button sendButton;
    private Button receiveButton;
    private Button cancelJobButton;
    private View exportPanel;
    private TextView exportStatus;
    private Button exportShareButton;
    private Button exportDeleteButton;
    private RadioGroup inputModeGroup;
    private CheckBox useAlphaBox;
    private Spinner languageSpinner;
    private TextView languageNote;
    private CheckBox backButtonBox;
    private CheckBox noIntroBox;
    private CheckBox noSoundBox;
    private EditText extraArgsField;

    private DataImporter runningImport;
    private boolean busy = false;
    private boolean downloadWasRunning = false;
    private int loadedSettingsGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        // Before anything below looks at the installation or reads a setting.
        PhoneTransfer.cleanUpAtStart(this);
        loadedSettingsGeneration = DownloadService.settingsGeneration();

        prefs = new Prefs(this);

        keeperfxStatus = findViewById(R.id.keeperfxStatus);
        originalDkStatus = findViewById(R.id.originalDkStatus);
        detailView = findViewById(R.id.detail);
        progressBar = findViewById(R.id.progress);
        installButton = findViewById(R.id.installKeeperfx);
        importKeeperfxButton = findViewById(R.id.importKeeperfx);
        importOriginalButton = findViewById(R.id.importOriginalDk);
        playButton = findViewById(R.id.play);
        removeDataButton = findViewById(R.id.removeData);
        updateAppButton = findViewById(R.id.updateApp);
        updateAppButton.setText(getString(R.string.button_update_app,
            AppUpdater.installedVersionName(this)));
        sendButton = findViewById(R.id.sendToPhone);
        receiveButton = findViewById(R.id.receiveFromPhone);
        cancelJobButton = findViewById(R.id.cancelJob);
        exportPanel = findViewById(R.id.exportPanel);
        exportStatus = findViewById(R.id.exportStatus);
        exportShareButton = findViewById(R.id.exportShare);
        exportDeleteButton = findViewById(R.id.exportDelete);
        inputModeGroup = findViewById(R.id.inputMode);
        useAlphaBox = findViewById(R.id.useAlpha);
        languageSpinner = findViewById(R.id.language);
        languageNote = findViewById(R.id.languageNote);
        backButtonBox = findViewById(R.id.backButton);
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
        final List<GameLanguage.Entry> languages = GameLanguage.all();
        final ArrayAdapter<GameLanguage.Entry> languageAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, languages);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);
        languageSpinner.setSelection(GameLanguage.indexOf(prefs.getLanguage()));
        useAlphaBox.setChecked(prefs.isAlphaEnabled());
        backButtonBox.setChecked(prefs.isBackButtonShown());
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
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                final String code = GameLanguage.codeAt(position);
                prefs.setLanguage(code);
                GameLanguage.applyToConfig(LauncherActivity.this, code);
                refreshLanguageNote();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        useAlphaBox.setOnCheckedChangeListener((v, checked) -> {
            if (!checked && patchIsOn() && refuseWhileGameOpen()) {
                v.setChecked(true);
                return;
            }
            prefs.setAlphaEnabled(checked);
            if (!checked && patchIsOn()) {
                // Restores the files the patch replaced from the copies taken
                // before it was applied, so switching back deletes nothing and
                // costs no download. refreshStatus() runs when it finishes.
                setBusy(true);
                DownloadService.startAlphaRevert(this);
            } else {
                // Both the version shown at the top and the list of downloads
                // depend on this, so refresh one and ask again for the other.
                refreshStatus();
                checkForUpdateInBackground();
            }
        });
        backButtonBox.setOnCheckedChangeListener((v, checked) -> prefs.setBackButtonShown(checked));
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
        importKeeperfxButton.setOnClickListener(v -> {
            if (!refuseWhileGameOpen()) {
                pickFolder(REQUEST_PICK_KEEPERFX,
                    R.string.import_keeperfx_title, R.string.import_keeperfx_explanation);
            }
        });
        importOriginalButton.setOnClickListener(v -> {
            if (!refuseWhileGameOpen()) {
                askOriginalDkSource();
            }
        });
        sendButton.setOnClickListener(v -> confirmSend());
        receiveButton.setOnClickListener(v -> askForReceivedFile());
        cancelJobButton.setOnClickListener(v -> DownloadService.requestCancel());
        exportShareButton.setOnClickListener(v -> {
            final File export = TransferBundle.finishedExport(PhoneTransfer.exportDirectory(this));
            if (export != null) {
                startActivity(PhoneTransfer.shareSheet(this, export));
            }
            refreshExportPanel();
        });
        exportDeleteButton.setOnClickListener(v -> {
            PhoneTransfer.deleteExport(this);
            refreshExportPanel();
        });
        playButton.setOnClickListener(v -> startGame());
        removeDataButton.setOnClickListener(v -> confirmRemoveData());
        updateAppButton.setOnClickListener(v -> checkAppUpdate());
        findViewById(R.id.showLog).setOnClickListener(
            v -> startActivity(new Intent(this, LogActivity.class)));
        findViewById(R.id.touchTuning).setOnClickListener(
            v -> startActivity(new Intent(this, TouchTuningActivity.class)));
    }

    /**
     * Every view here is loaded from the settings in onCreate(). Their saved state can
     * only be older than that, and restoring it fires their listeners, which would
     * write it back over what an import just put in place, as far as taking the alpha
     * patch off again.
     */
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadService.addObserver(this);
        refreshStatus();
        if (!busy && !DownloadService.isRunning()) {
            if (!prefs.isAlphaEnabled() && patchIsOn()) {
                // Switched off, but taking it off never finished: Android took the
                // process, or a download was running at the time. With the game still
                // open it waits, this comes round again when the player returns.
                if (!GameProcess.isAlive(this)) {
                    setBusy(true);
                    DownloadService.startAlphaRevert(this);
                }
            } else {
                checkForUpdateInBackground();
            }
        }
    }

    /**
     * A patch that was cut short has no version recorded, only its backup says it is
     * there. The verification calls that no damage because the patch is offered again,
     * which does not happen while it is switched off, so it has to come off instead.
     */
    private boolean patchIsOn() {
        return !prefs.getInstalledAlphaVersion().isEmpty()
            || GameData.alphaBackupDirectory(this).isDirectory();
    }

    @Override
    protected void onPause() {
        DownloadService.removeObserver(this);
        super.onPause();
    }

    // ---------------------------------------------------------------- status

    private void refreshStatus() {
        final boolean hasKeeperfx = GameData.isKeeperfxInstalled(this);
        final boolean hasOriginal = GameData.hasOriginalDkFiles(this);
        final String installed = prefs.getInstalledVersion();

        if (hasKeeperfx) {
            final String version = installed.isEmpty() && prefs.getInstalledAlphaVersion().isEmpty()
                ? getString(R.string.version_unknown)
                : GameData.describeInstalledVersion(this);
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

        final StringBuilder sb = new StringBuilder();
        final String alphaVersion = prefs.getInstalledAlphaVersion();
        if (!alphaVersion.isEmpty() && BuildConfig.ENGINE_BUILD > 0
                && !ReleaseApi.fitsEngine(alphaVersion, BuildConfig.ENGINE_BUILD)) {
            sb.append(getString(R.string.status_engine_mismatch,
                ReleaseApi.buildOf(alphaVersion), BuildConfig.ENGINE_BUILD)).append("\n\n");
        }
        if (hasKeeperfx && hasOriginal) {
            sb.append(GameData.gameDirectory(this).getAbsolutePath());
        } else {
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
        }
        detailView.setText(sb.toString().trim());
        refreshLanguageNote();
        refreshExportPanel();

        setBusy(busy);
    }

    /**
     * Many hundreds of megabytes of the player's storage are in that file, and it has
     * to stay until the other side has read it, which nothing here can see happen.
     */
    private void refreshExportPanel() {
        final File export = TransferBundle.finishedExport(PhoneTransfer.exportDirectory(this));
        exportPanel.setVisibility(export != null ? View.VISIBLE : View.GONE);
        if (export != null) {
            exportStatus.setText(getString(R.string.send_waiting, export.getName(),
                GameData.describeBytes(export.length())));
        }
    }

    private void refreshLanguageNote() {
        final String language = prefs.getLanguage();
        // Before anything is installed there is nothing to say about what is missing.
        final boolean missing = GameData.isKeeperfxInstalled(this) && !language.equals(
            GameLanguage.usableCode(language, GameData.gameDirectory(this)));
        languageNote.setVisibility(missing ? View.VISIBLE : View.GONE);
        if (missing) {
            languageNote.setText(getString(R.string.language_not_installed,
                GameLanguage.displayName(language)));
        }
    }

    /**
     * Quietly counts what could be downloaded so the button can say so before
     * the player taps it. Silent on failure; being offline must not get in the
     * way of starting the game.
     */
    private void checkForUpdateInBackground() {
        new Thread(() -> {
            final List<UpdateManager.Item> items = UpdateManager.check(this).items;
            postIfAlive(() -> {
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
        if (DownloadService.isRunning()) {
            Toast.makeText(this, R.string.updates_running, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        progressBar.setIndeterminate(true);
        keeperfxStatus.setText(R.string.status_checking_updates);

        new Thread(() -> {
            final UpdateManager.Result found = UpdateManager.check(this);
            postIfAlive(() -> {
                setBusy(false);
                refreshStatus();
                if (!found.items.isEmpty()) {
                    showUpdateDialog(found);
                } else if (found.unanswered) {
                    showMessage(R.string.updates_unanswered_title,
                        getString(R.string.updates_unanswered));
                } else {
                    showMessage(R.string.updates_none_title, getString(R.string.updates_none));
                }
            });
        }, "kfx-update-scan").start();
    }

    private void showUpdateDialog(UpdateManager.Result found) {
        final List<UpdateManager.Item> items = found.items;
        final StringBuilder body = new StringBuilder(UpdateManager.describe(items)).append('\n');
        if (UpdateManager.contains(items, UpdateManager.Kind.GAME_DATA)) {
            body.append('\n').append(getString(R.string.updates_replaces_data));
        }
        if (found.unanswered) {
            body.append('\n').append(getString(R.string.updates_unanswered_more));
        }
        body.append('\n').append(getString(R.string.updates_use_wifi));

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.updates_available_title, items.size()))
            .setMessage(body.toString().trim())
            .setPositiveButton(R.string.updates_download_all,
                (dialog, which) -> runUpdates(items))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Hands the list to the foreground service. It keeps running when the launcher
     * is closed, which a 360 MB transfer needs, and reports back through
     * onDownloadState().
     *
     * An app update the system would not install stays behind, and only that: the
     * game data must not depend on a player granting a permission for something else.
     * The next tap on the app update asks again.
     */
    private void runUpdates(List<UpdateManager.Item> items) {
        if (UpdateManager.changesInstallation(items) && refuseWhileGameOpen()) {
            return;
        }
        final boolean appLeftOut = UpdateManager.contains(items, UpdateManager.Kind.APP)
            && !AppUpdater.canInstallPackages(this);
        final List<UpdateManager.Item> runnable =
            appLeftOut ? UpdateManager.without(items, UpdateManager.Kind.APP) : items;
        if (!runnable.isEmpty()) {
            requestNotificationPermission();
            setBusy(true);
            DownloadService.start(this, runnable);
        }
        if (appLeftOut) {
            askForInstallPermission(!runnable.isEmpty());
        }
    }

    /**
     * Android 11 ends every process of an app when this permission is granted to it, and
     * the downloads that were just started would go with them. There the settings wait
     * until the next tap, when the app update is all that is left and nothing is running.
     */
    private void askForInstallPermission(boolean othersRunning) {
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.updates_permission_title);
        if (othersRunning && Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            dialog.setMessage(R.string.updates_permission_rest_running_later)
                .setPositiveButton(android.R.string.ok, null);
        } else {
            dialog.setMessage(othersRunning
                    ? R.string.updates_permission_rest_running : R.string.updates_permission)
                .setPositiveButton(R.string.updates_permission_open, (d, w) -> startActivity(
                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton(android.R.string.cancel, null);
        }
        dialog.show();
    }

    /**
     * Without this the progress notification is silently dropped on Android 13
     * and newer. The download itself still runs either way, so the result is
     * not checked.
     */
    private void requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }

    @Override
    public void onDownloadState(DownloadService.State state) {
        if (!state.running && loadedSettingsGeneration != DownloadService.settingsGeneration()) {
            // An import just replaced the settings every view below was loaded from.
            recreate();
            return;
        }
        // The service's idle state must not unlock the buttons while a folder
        // import is still copying - the observer fires with the current state
        // the moment it attaches, which is also every onResume().
        setBusy(state.running || runningImport != null);
        cancelJobButton.setVisibility(state.running ? View.VISIBLE : View.GONE);
        if (state.running) {
            downloadWasRunning = true;
            // The file it describes may be the one on its way out right now.
            exportPanel.setVisibility(View.GONE);
            keeperfxStatus.setText(state.stage);
            detailView.setText(state.detail);
            if (state.percent >= 0) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(state.percent);
            } else {
                progressBar.setIndeterminate(true);
            }
            return;
        }
        refreshStatus();
        if (state.error != null) {
            DownloadService.errorShown();
            showMessage(state.errorTitle, state.error);
        }
        final PhoneTransfer.ReceivedFile received = DownloadService.takeReceivedFile();
        if (received != null) {
            offerToDeleteReceivedFile(received);
        }
        // One finished download can open the way to the next: the alpha patch and
        // the music are only offered once the release is in place, and on a fresh
        // install nothing else would tell the player there is more to fetch.
        if (downloadWasRunning) {
            downloadWasRunning = false;
            checkForUpdateInBackground();
        }
    }

    @Override
    public void onExportReady(File file) {
        startActivity(PhoneTransfer.shareSheet(this, file));
    }

    // ------------------------------------------------------ to another phone

    private void confirmSend() {
        if (DownloadService.isRunning()) {
            Toast.makeText(this, R.string.updates_running, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        progressBar.setIndeterminate(true);
        new Thread(() -> {
            final PhoneTransfer.SendOffer offer = PhoneTransfer.checkExport(this);
            postIfAlive(() -> {
                setBusy(false);
                refreshStatus();
                showSendDialog(offer);
            });
        }, "kfx-send-check").start();
    }

    private void showSendDialog(PhoneTransfer.SendOffer offer) {
        if (offer.refusal != null) {
            showMessage(R.string.send_title, offer.refusal);
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.send_title)
            .setMessage(getString(R.string.send_offer,
                offer.version,
                offer.music ? getString(R.string.send_offer_music) : "",
                offer.saves,
                GameData.describeBytes(offer.bytes),
                offer.files,
                GameData.describeBytes(offer.needed),
                GameData.describeBytes(offer.free)))
            .setPositiveButton(R.string.send_confirm, (dialog, which) -> {
                requestNotificationPermission();
                setBusy(true);
                DownloadService.startBundleExport(this);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------- from another phone

    private void askForReceivedFile() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.receive_title)
            .setMessage(R.string.receive_explanation)
            .setPositiveButton(R.string.receive_choose, (dialog, which) -> {
                final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // As with the original game's zip: the reader decides, not the label.
                intent.setType("*/*");
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, PhoneTransfer.downloadsUri());
                startActivityForResult(intent, REQUEST_PICK_BUNDLE);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void inspectReceivedFile(Uri file) {
        setBusy(true);
        progressBar.setIndeterminate(true);
        new Thread(() -> {
            final PhoneTransfer.ReceiveOffer offer = PhoneTransfer.inspect(this, file);
            postIfAlive(() -> {
                setBusy(false);
                refreshStatus();
                showReceiveDialog(file, offer);
            });
        }, "kfx-receive-check").start();
    }

    private void showReceiveDialog(Uri file, PhoneTransfer.ReceiveOffer offer) {
        if (offer.refusal != null) {
            showMessage(R.string.receive_title, offer.refusal);
            return;
        }
        final TransferBundle.Marker marker = offer.marker;
        final StringBuilder body = new StringBuilder(getString(R.string.receive_offer,
            GameData.describeVersion(marker.release, marker.alpha),
            marker.saves,
            GameData.describeBytes(marker.bytes)));
        final int savesHere = offer.savesHere;
        body.append("\n\n").append(!offer.installedHere
            ? getString(R.string.receive_nothing_here)
            : savesHere > 0
                ? getString(R.string.receive_replaces, savesHere)
                : getString(R.string.receive_replaces_no_saves));
        body.append("\n\n").append(getString(R.string.receive_room,
            GameData.describeBytes(marker.bytes), GameData.describeBytes(offer.free)));
        body.append("\n\n").append(getString(R.string.receive_trust));
        final String thisApp = AppUpdater.installedVersionName(this);
        if (!marker.app.equals(thisApp) || marker.engine != BuildConfig.ENGINE_BUILD) {
            body.append("\n\n").append(getString(R.string.receive_other_build,
                marker.app, marker.engine, thisApp, BuildConfig.ENGINE_BUILD));
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.receive_title)
            .setMessage(body.toString())
            .setPositiveButton(R.string.receive_confirm, (dialog, which) -> {
                requestNotificationPermission();
                setBusy(true);
                DownloadService.startBundleImport(this, file);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void offerToDeleteReceivedFile(PhoneTransfer.ReceivedFile file) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.receive_done_title)
            .setMessage(getString(R.string.receive_done, file.where))
            .setPositiveButton(R.string.receive_delete, (dialog, which) -> new Thread(() -> {
                final boolean deleted = PhoneTransfer.deleteReceived(this, file.uri);
                postIfAlive(() -> {
                    if (!deleted) {
                        showMessage(R.string.receive_done_title,
                            getString(R.string.receive_not_deleted, file.where));
                    }
                });
            }, "kfx-receive-delete").start())
            .setNegativeButton(R.string.receive_keep, null)
            .show();
    }

    // ------------------------------------------------------------- app update

    /**
     * Checks only for a newer build of the app. The combined download button
     * covers this too, but nothing outside the app will ever offer it, so it
     * gets an entry of its own that also shows which build is running.
     */
    private void checkAppUpdate() {
        if (DownloadService.isRunning()) {
            Toast.makeText(this, R.string.updates_running, Toast.LENGTH_SHORT).show();
            return;
        }
        updateAppButton.setEnabled(false);
        updateAppButton.setText(R.string.app_update_check);

        new Thread(() -> {
            AppRelease available = null;
            boolean reachable = true;
            try {
                available = AppUpdater.checkForUpdate(this);
            } catch (Exception e) {
                reachable = false;
            }
            final AppRelease found = available;
            final boolean online = reachable;
            postIfAlive(() -> {
                updateAppButton.setEnabled(true);
                updateAppButton.setText(getString(R.string.button_update_app,
                    AppUpdater.installedVersionName(this)));
                if (!online) {
                    showMessage(R.string.app_update_title,
                        getString(R.string.app_update_offline));
                    return;
                }
                if (found == null) {
                    showMessage(R.string.app_update_title, getString(R.string.app_update_none,
                        AppUpdater.installedVersionName(this)));
                    return;
                }
                final StringBuilder text = new StringBuilder(
                    getString(R.string.app_update_available, found.versionName,
                        AppUpdater.installedVersionName(this)));
                final String changelog = found.changelog();
                if (!changelog.isEmpty()) {
                    text.append("\n\n")
                        .append(getString(R.string.app_update_changes))
                        .append('\n')
                        .append(changelog);
                }
                new AlertDialog.Builder(this)
                    .setTitle(R.string.app_update_title)
                    .setMessage(text.toString())
                    .setPositiveButton(R.string.app_update_install, (d, w) -> {
                        final List<UpdateManager.Item> only = new ArrayList<>();
                        only.add(new UpdateManager.Item(UpdateManager.Kind.APP,
                            found.versionName, "", found));
                        runUpdates(only);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        }, "kfx-app-update-check").start();
    }

    // ---------------------------------------------------------------- import

    /**
     * Asks whether the original game is a folder or an archive.
     *
     * A CD rip is very often passed around as one zip, and unpacking it on a
     * phone just to point a folder picker at the result is a chore. Both routes
     * look for the same handful of files and put them in the same places.
     */
    private void askOriginalDkSource() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.import_original_title)
            .setMessage(R.string.import_original_explanation)
            .setPositiveButton(R.string.import_choose_folder,
                (dialog, which) -> openFolderPicker(REQUEST_PICK_ORIGINAL_DK))
            .setNeutralButton(R.string.import_choose_zip, (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // Not every provider labels a zip with the same type, so the
                // filter is widened and the reader decides.
                intent.setType("*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_PICK_ORIGINAL_DK_ZIP);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void pickFolder(int requestCode, int titleRes, int explanationRes) {
        new AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(explanationRes)
            .setPositiveButton(R.string.import_choose,
                (dialog, which) -> openFolderPicker(requestCode))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void openFolderPicker(int requestCode) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQUEST_PICK_BUNDLE) {
            if (data.getData() != null) {
                inspectReceivedFile(data.getData());
            }
            return;
        }
        if (requestCode != REQUEST_PICK_KEEPERFX && requestCode != REQUEST_PICK_ORIGINAL_DK
         && requestCode != REQUEST_PICK_ORIGINAL_DK_ZIP) {
            return;
        }
        final Uri treeUri = data.getData();
        if (treeUri == null) {
            return;
        }
        startImport(treeUri, requestCode == REQUEST_PICK_KEEPERFX,
            requestCode == REQUEST_PICK_ORIGINAL_DK_ZIP);
    }

    private void startImport(Uri treeUri, boolean keeperfxRelease, boolean fromZip) {
        // Again: the picker can take a while, and the recent apps can bring the game back.
        if (refuseWhileGameOpen()) {
            return;
        }
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
                postIfAlive(() -> {
                    runningImport = null;
                    setBusy(false);
                    refreshStatus();
                    if (!success) {
                        showMessage(R.string.import_failed, message);
                    }
                });
            }
        });
        runningImport = importer;
        new Thread(() -> {
            if (keeperfxRelease) {
                importer.importKeeperfxTree(treeUri);
            } else if (fromZip) {
                importer.importOriginalDkZip(treeUri);
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
        useAlphaBox.setEnabled(!value);
        // Deleting the installation under a running install leaves a mess that the
        // install then records as complete.
        removeDataButton.setEnabled(!value);
        importKeeperfxButton.setEnabled(!value);
        importOriginalButton.setEnabled(!value);
        final boolean complete = GameData.isComplete(this);
        playButton.setEnabled(!value && complete);
        sendButton.setEnabled(!value && complete);
        // Not tied to anything being installed: on a new phone this is the way in.
        receiveButton.setEnabled(!value);
        exportShareButton.setEnabled(!value);
        exportDeleteButton.setEnabled(!value);
    }

    /**
     * For what a background thread has to say once it is done, which can be after
     * the player has left. Opening a dialog on a destroyed activity crashes the app.
     */
    private void postIfAlive(Runnable action) {
        mainHandler.post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                action.run();
            }
        });
    }

    /**
     * For everything that replaces or removes files under the installation: the engine
     * can be alive behind the launcher, with those files open and, in a network game,
     * still running.
     *
     * @return whether the game is open, in which case the player has been told so.
     */
    private boolean refuseWhileGameOpen() {
        if (!GameProcess.isAlive(this)) {
            return false;
        }
        showMessage(R.string.game_still_open_title, getString(R.string.game_still_open));
        return true;
    }

    private void showMessage(int titleRes, String message) {
        new AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void confirmRemoveData() {
        if (refuseWhileGameOpen()) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.remove_title)
            .setMessage(R.string.remove_explanation)
            .setPositiveButton(R.string.remove_confirm, (dialog, which) -> {
                if (refuseWhileGameOpen()) {
                    return;
                }
                setBusy(true);
                // Thousands of files, which is more than the main thread may spend.
                new Thread(() -> {
                    FileUtil.deleteTree(GameData.gameDirectory(this));
                    // Not only the version: the alpha backup, the archive cache
                    // and the manifests all describe the installation that was
                    // just deleted, and an alpha backup that survives a reset
                    // would "restore" pieces of it into the next one.
                    ReleaseDownloader.wipeInstallState(this);
                    postIfAlive(() -> {
                        setBusy(false);
                        refreshStatus();
                    });
                }, "kfx-reset").start();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void startGame() {
        if (!GameData.isComplete(this)) {
            refreshStatus();
            return;
        }
        // Only back to it: what follows would rewrite files the engine has open.
        if (GameProcess.isAlive(this)) {
            startActivity(new Intent(this, GameActivity.class));
            return;
        }
        // Refreshed on every launch rather than only at install time, so that
        // updating the APK also updates the configuration its engine expects.
        setBusy(true);
        new Thread(() -> {
            String failure = null;
            try {
                BundledConfig.install(this);
                // After the bundled copy, which is what creates keeperfx.cfg the
                // first time round and would otherwise hand the engine its
                // built in English.
                GameLanguage.applyToConfig(this, prefs.getLanguage());
            } catch (Exception e) {
                failure = e.getMessage();
            }
            final String message = failure;
            postIfAlive(() -> {
                setBusy(false);
                if (message != null) {
                    showMessage(R.string.config_failed, message);
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
        super.onDestroy();
    }
}
