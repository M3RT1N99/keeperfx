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
import android.content.Context;
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
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class LauncherActivity extends Activity implements DownloadService.Observer {

    private static final int REQUEST_PICK_KEEPERFX = 1001;
    private static final int REQUEST_PICK_ORIGINAL_DK = 1002;
    private static final int REQUEST_PICK_ORIGINAL_DK_ZIP = 1003;
    private static final int REQUEST_PICK_BUNDLE = 1004;
    private static final String STATE_SCROLL = "scrollY";

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
    private Button exportButton;
    private Button importButton;
    private Button cancelJobButton;
    private View exportPanel;
    private TextView exportStatus;
    private Button exportShareButton;
    private Button exportDeleteButton;
    private CheckBox useAlphaBox;
    private Spinner languageSpinner;
    private TextView languageNote;

    private DataImporter runningImport;
    private boolean workRunning = false;
    private boolean busy = false;
    private boolean downloadWasRunning = false;
    private int loadedSettingsGeneration;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(GameLanguage.localized(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        if (savedInstanceState != null) {
            // A change of language lands here: the box is half way down, and the player
            // should still be looking at it.
            final int scrollY = savedInstanceState.getInt(STATE_SCROLL);
            final View scroll = findViewById(R.id.scroll);
            scroll.post(() -> scroll.scrollTo(0, scrollY));
        }

        // Before anything below looks at the installation or reads a setting.
        PhoneTransfer.cleanUpAtStart(this);
        loadedSettingsGeneration = DownloadService.settingsGeneration();

        prefs = new Prefs(this);

        findViews();
        bindSettings();
        bindButtons();
    }

    private void findViews() {
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
        updateAppButton.setText(getString(R.string.button_update_app, BuildConfig.VERSION_NAME));
        exportButton = findViewById(R.id.exportInstallation);
        importButton = findViewById(R.id.importInstallation);
        cancelJobButton = findViewById(R.id.cancelJob);
        exportPanel = findViewById(R.id.exportPanel);
        exportStatus = findViewById(R.id.exportStatus);
        exportShareButton = findViewById(R.id.exportShare);
        exportDeleteButton = findViewById(R.id.exportDelete);
        useAlphaBox = findViewById(R.id.useAlpha);
        languageSpinner = findViewById(R.id.language);
        languageNote = findViewById(R.id.languageNote);
    }

    /** Every setting from what is stored, and every change back into it. */
    private void bindSettings() {
        final RadioGroup inputModeGroup = findViewById(R.id.inputMode);
        final CheckBox backButtonBox = findViewById(R.id.backButton);
        final CheckBox noIntroBox = findViewById(R.id.noIntro);
        final CheckBox noSoundBox = findViewById(R.id.noSound);
        final EditText extraArgsField = findViewById(R.id.extraArgs);

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
                // This also fires by itself when the box is first laid out.
                final boolean changed = !code.equals(prefs.getLanguage());
                prefs.setLanguage(code);
                GameLanguage.applyToConfig(LauncherActivity.this, code);
                if (changed) {
                    // Views read their texts once, and this screen speaks it too.
                    recreate();
                } else {
                    refreshLanguageNote();
                }
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
    }

    private void bindButtons() {
        installButton.setOnClickListener(v -> confirmInstallOrUpdate());
        importKeeperfxButton.setOnClickListener(v -> {
            if (!refuseWhileGameOpen()) {
                askKeeperfxFolder();
            }
        });
        importOriginalButton.setOnClickListener(v -> {
            if (!refuseWhileGameOpen()) {
                askOriginalDkSource();
            }
        });
        exportButton.setOnClickListener(v -> confirmExport());
        importButton.setOnClickListener(v -> askForExportedFile());
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
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SCROLL, findViewById(R.id.scroll).getScrollY());
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
        final List<String> missingOriginal = GameData.findMissingOriginalDkFiles(this);
        final boolean hasOriginal = missingOriginal.isEmpty();

        if (hasKeeperfx) {
            keeperfxStatus.setText(getString(R.string.status_keeperfx_ready,
                GameData.describeInstalledVersion(this).in(this),
                GameData.describeSize(GameData.gameDirectory(this)).in(this)));
        } else {
            keeperfxStatus.setText(R.string.status_keeperfx_missing);
        }
        keeperfxStatus.setTextColor(getColor(hasKeeperfx ? R.color.kfx_ok : R.color.kfx_warn));

        if (hasOriginal) {
            originalDkStatus.setText(R.string.status_original_ready);
        } else {
            originalDkStatus.setText(getString(R.string.status_original_missing, missingOriginal.size()));
        }
        originalDkStatus.setTextColor(getColor(hasOriginal ? R.color.kfx_ok : R.color.kfx_warn));

        installButton.setText(hasKeeperfx
            ? getString(R.string.button_update_keeperfx)
            : getString(R.string.button_install_keeperfx));

        detailView.setText(GameData.describeProblems(Message.strings(this), hasKeeperfx, missingOriginal,
            prefs.getInstalledAlphaVersion(), BuildConfig.ENGINE_BUILD,
            GameData.gameDirectory(this).getAbsolutePath()));
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
            exportStatus.setText(getString(R.string.export_waiting, export.getName(),
                GameData.describeBytes(export.length()).in(this)));
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
                // With nothing installed the button keeps its name: that is the name the
                // hint above it tells a new player to look for.
                if (!items.isEmpty() && !busy && GameData.isKeeperfxInstalled(this)) {
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
        if (refuseWhileJobRuns()) {
            return;
        }
        whileBusy("kfx-update-scan", () -> UpdateManager.check(this), found -> {
            if (!found.items.isEmpty()) {
                showUpdateDialog(found);
            } else if (found.unanswered) {
                showMessage(R.string.updates_unanswered_title, getString(R.string.updates_unanswered));
            } else {
                showMessage(R.string.updates_none_title, getString(R.string.updates_none));
            }
        });
        keeperfxStatus.setText(R.string.status_checking_updates);
    }

    private void showUpdateDialog(UpdateManager.Result found) {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.updates_available_title, found.items.size()))
            .setMessage(UpdateManager.describe(found, Message.strings(this)))
            .setPositiveButton(R.string.updates_download_all,
                (dialog, which) -> runUpdates(found.items))
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

    private void askForInstallPermission(boolean othersRunning) {
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.updates_permission_title);
        if (UpdateManager.installPermissionHasToWait(othersRunning, Build.VERSION.SDK_INT)) {
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
        // import is still copying or a Reset still deleting - the observer fires
        // with the current state the moment it attaches, which is also every onResume().
        setBusy(state.running || runningImport != null || workRunning);
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
        final PhoneTransfer.ImportedFile imported = DownloadService.takeImportedFile();
        if (imported != null) {
            offerToDeleteImportedFile(imported);
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

    // ----------------------------------------------------------------- export

    private void confirmExport() {
        if (!refuseWhileJobRuns()) {
            whileBusy("kfx-export-check", () -> PhoneTransfer.checkExport(this), this::showExportDialog);
        }
    }

    private void showExportDialog(PhoneTransfer.ExportOffer offer) {
        if (offer.refusal != null) {
            showMessage(R.string.export_title, offer.refusal.in(this));
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.export_title)
            .setMessage(offer.describe(Message.strings(this)))
            .setPositiveButton(R.string.export_confirm, (dialog, which) -> {
                requestNotificationPermission();
                setBusy(true);
                DownloadService.startBundleExport(this);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // --------------------------------------------------------- import a whole one

    private void askForExportedFile() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.import_bundle_title)
            .setMessage(R.string.import_bundle_explanation)
            .setPositiveButton(R.string.import_bundle_choose, (dialog, which) ->
                openFilePicker(REQUEST_PICK_BUNDLE, PhoneTransfer.downloadsUri()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void inspectPickedFile(Uri file) {
        whileBusy("kfx-import-check", () -> PhoneTransfer.inspect(this, file),
            offer -> showImportDialog(file, offer));
    }

    private void showImportDialog(Uri file, PhoneTransfer.ImportOffer offer) {
        if (offer.refusal != null) {
            showMessage(R.string.import_bundle_title, offer.refusal.in(this));
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.import_bundle_title)
            .setMessage(offer.describe(Message.strings(this), BuildConfig.VERSION_NAME,
                BuildConfig.ENGINE_BUILD))
            .setPositiveButton(R.string.import_bundle_confirm, (dialog, which) -> {
                requestNotificationPermission();
                setBusy(true);
                DownloadService.startBundleImport(this, file);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void offerToDeleteImportedFile(PhoneTransfer.ImportedFile file) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.import_bundle_done_title)
            .setMessage(getString(R.string.import_bundle_done, file.where.in(this)))
            .setPositiveButton(R.string.import_bundle_delete, (dialog, which) -> new Thread(() -> {
                final boolean deleted = PhoneTransfer.deleteImported(this, file.uri);
                postIfAlive(() -> {
                    if (!deleted) {
                        showMessage(R.string.import_bundle_done_title,
                            getString(R.string.import_bundle_not_deleted, file.where.in(this)));
                    }
                });
            }, "kfx-import-delete").start())
            .setNegativeButton(R.string.import_bundle_keep, null)
            .show();
    }

    // ------------------------------------------------------------- app update

    /**
     * Checks only for a newer build of the app. The combined download button
     * covers this too, but nothing outside the app will ever offer it, so it
     * gets an entry of its own that also shows which build is running.
     */
    private void checkAppUpdate() {
        if (refuseWhileJobRuns()) {
            return;
        }
        updateAppButton.setEnabled(false);
        updateAppButton.setText(R.string.app_update_check);

        new Thread(() -> {
            AppRelease available = null;
            boolean reachable = true;
            try {
                available = AppUpdater.checkForUpdate();
            } catch (Exception e) {
                reachable = false;
            }
            final AppRelease found = available;
            final boolean online = reachable;
            postIfAlive(() -> {
                updateAppButton.setEnabled(true);
                updateAppButton.setText(getString(R.string.button_update_app, BuildConfig.VERSION_NAME));
                if (!online) {
                    showMessage(R.string.app_update_title,
                        getString(R.string.app_update_offline));
                    return;
                }
                if (found == null) {
                    showMessage(R.string.app_update_title, getString(R.string.app_update_none,
                        BuildConfig.VERSION_NAME));
                    return;
                }
                new AlertDialog.Builder(this)
                    .setTitle(R.string.app_update_title)
                    .setMessage(found.describe(BuildConfig.VERSION_NAME, Message.strings(this)))
                    .setPositiveButton(R.string.app_update_install, (d, w) ->
                        runUpdates(Collections.singletonList(new UpdateManager.Item(
                            UpdateManager.Kind.APP, Message.plain(found.versionName), found))))
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
            .setNeutralButton(R.string.import_choose_zip, (dialog, which) ->
                openFilePicker(REQUEST_PICK_ORIGINAL_DK_ZIP, null))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void askKeeperfxFolder() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.import_keeperfx_title)
            .setMessage(R.string.import_keeperfx_explanation)
            .setPositiveButton(R.string.import_choose,
                (dialog, which) -> openFolderPicker(REQUEST_PICK_KEEPERFX))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Any type: not every provider labels a zip with the same one, so the filter is wide
     * and whatever reads the file decides.
     *
     * @param startAt where the picker opens, or null for where it was last
     */
    private void openFilePicker(int requestCode, Uri startAt) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (startAt != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, startAt);
        }
        startActivityForResult(intent, requestCode);
    }

    private void openFolderPicker(int requestCode) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_PICK_BUNDLE) {
            inspectPickedFile(data.getData());
        } else {
            startImport(requestCode, data.getData());
        }
    }

    private void startImport(int requestCode, Uri picked) {
        // Again: the picker can take a while, and the recent apps can bring the game back.
        if (refuseWhileGameOpen()) {
            return;
        }
        setBusy(true);
        progressBar.setIndeterminate(true);
        detailView.setText("");
        if (requestCode == REQUEST_PICK_KEEPERFX) {
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
            public void onFinished(Message failure) {
                postIfAlive(() -> {
                    runningImport = null;
                    setBusy(false);
                    refreshStatus();
                    if (failure != null) {
                        showMessage(R.string.import_failed, failure.in(LauncherActivity.this));
                    }
                });
            }
        });
        runningImport = importer;
        new Thread(() -> {
            if (requestCode == REQUEST_PICK_KEEPERFX) {
                importer.importKeeperfxTree(picked);
            } else if (requestCode == REQUEST_PICK_ORIGINAL_DK_ZIP) {
                importer.importOriginalDkZip(picked);
            } else {
                importer.importOriginalDkTree(picked);
            }
        }, "kfx-import").start();
    }

    // ----------------------------------------------------------------- misc

    private void setBusy(boolean value) {
        busy = value;
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        installButton.setEnabled(!value);
        useAlphaBox.setEnabled(!value);
        // Changing it recreates the screen, which would cancel a folder import, and it
        // writes to the configuration that an install is unpacking.
        languageSpinner.setEnabled(!value);
        // Deleting the installation under a running install leaves a mess that the
        // install then records as complete.
        removeDataButton.setEnabled(!value);
        importKeeperfxButton.setEnabled(!value);
        importOriginalButton.setEnabled(!value);
        final boolean complete = GameData.isComplete(this);
        playButton.setEnabled(!value && complete);
        exportButton.setEnabled(!value && complete);
        // Not tied to anything being installed: on a new phone this is the way in.
        importButton.setEnabled(!value);
        exportShareButton.setEnabled(!value);
        exportDeleteButton.setEnabled(!value);
    }

    /** @return whether a job is running, in which case the player has been told so. */
    private boolean refuseWhileJobRuns() {
        if (!DownloadService.isRunning()) {
            return false;
        }
        Toast.makeText(this, R.string.updates_running, Toast.LENGTH_SHORT).show();
        return true;
    }

    /**
     * Does what would hold up the screen on a thread of its own, with the buttons locked,
     * and then what follows from it, if the player is still here.
     */
    private <T> void whileBusy(String threadName, Supplier<T> work, Consumer<T> then) {
        workRunning = true;
        setBusy(true);
        progressBar.setIndeterminate(true);
        new Thread(() -> {
            final T result = work.get();
            postIfAlive(() -> {
                workRunning = false;
                setBusy(false);
                refreshStatus();
                then.accept(result);
            });
        }, threadName).start();
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
                // Thousands of files, which is more than the main thread may spend.
                whileBusy("kfx-reset", () -> {
                    FileUtil.deleteTree(GameData.gameDirectory(this));
                    // Not only the version: the alpha backup, the archive cache
                    // and the manifests all describe the installation that was
                    // just deleted, and an alpha backup that survives a reset
                    // would "restore" pieces of it into the next one.
                    ReleaseDownloader.wipeInstallState(this);
                    return null;
                }, nothing -> { });
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
            Message failure = null;
            try {
                BundledConfig.install(this);
                // After the bundled copy, which is what creates keeperfx.cfg the
                // first time round and would otherwise hand the engine its
                // built in English.
                GameLanguage.applyToConfig(this, prefs.getLanguage());
            } catch (Exception e) {
                failure = Message.detailOf(e);
            }
            final Message message = failure;
            postIfAlive(() -> {
                setBusy(false);
                if (message != null) {
                    showMessage(R.string.config_failed, message.in(this));
                    return;
                }
                // The process is gone, but the system may have kept the record of a game it
                // took from the background. Without the flags this start would bring that
                // back, with the arguments of its own start and a saved state, which
                // GameActivity sends to the launcher: Play would seem to have done nothing.
                Intent intent = new Intent(this, GameActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
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
