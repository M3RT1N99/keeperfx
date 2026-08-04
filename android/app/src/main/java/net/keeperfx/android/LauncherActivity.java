/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file LauncherActivity.java
 *     Touch launcher for the Android port.
 * @par Purpose:
 *     Imports the game data, verifies it, exposes the settings that have to be
 *     decided before the engine starts, and launches it.
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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.util.List;

public class LauncherActivity extends Activity {

    private static final int REQUEST_PICK_TREE = 1001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private TextView statusView;
    private TextView detailView;
    private Button playButton;
    private Button importButton;
    private ProgressBar progressBar;
    private RadioGroup inputModeGroup;
    private CheckBox noIntroBox;
    private CheckBox noSoundBox;
    private EditText extraArgsField;

    private DataImporter runningImport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        prefs = new Prefs(this);

        statusView = findViewById(R.id.status);
        detailView = findViewById(R.id.detail);
        playButton = findViewById(R.id.play);
        importButton = findViewById(R.id.importData);
        progressBar = findViewById(R.id.progress);
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

        importButton.setOnClickListener(v -> pickGameFolder());
        playButton.setOnClickListener(v -> startGame());
        findViewById(R.id.removeData).setOnClickListener(v -> confirmRemoveData());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ---------------------------------------------------------------- status

    private void refreshStatus() {
        final File gameDir = GameData.gameDirectory(this);
        final List<String> missing = GameData.findMissingEntries(this);
        if (missing.isEmpty()) {
            statusView.setText(getString(R.string.status_ready, GameData.describeSize(gameDir)));
            detailView.setText(gameDir.getAbsolutePath());
            playButton.setEnabled(true);
        } else {
            statusView.setText(R.string.status_incomplete);
            final StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.status_missing_header)).append('\n');
            int shown = 0;
            for (String entry : missing) {
                if (shown++ >= 12) {
                    sb.append("  … ").append(missing.size() - 12).append(" more\n");
                    break;
                }
                sb.append("  • ").append(entry).append('\n');
            }
            detailView.setText(sb.toString().trim());
            playButton.setEnabled(false);
        }
    }

    // ---------------------------------------------------------------- import

    private void pickGameFolder() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.import_title)
            .setMessage(R.string.import_explanation)
            .setPositiveButton(R.string.import_choose, (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_PICK_TREE);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_TREE || resultCode != RESULT_OK || data == null) {
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
        startImport(treeUri);
    }

    private void startImport(Uri treeUri) {
        setBusy(true);
        statusView.setText(R.string.status_importing);
        detailView.setText("");

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
                    refreshStatus();
                    if (!success) {
                        new AlertDialog.Builder(LauncherActivity.this)
                            .setTitle(R.string.import_failed)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    }
                });
            }
        });
        runningImport = importer;
        new Thread(() -> importer.importTree(treeUri), "kfx-import").start();
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        importButton.setEnabled(!busy);
        playButton.setEnabled(!busy && GameData.isComplete(this));
    }

    private void confirmRemoveData() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.remove_title)
            .setMessage(R.string.remove_explanation)
            .setPositiveButton(R.string.remove_confirm, (dialog, which) -> {
                DataImporter.deleteRecursively(GameData.gameDirectory(this));
                refreshStatus();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------- launch

    private void startGame() {
        if (!GameData.isComplete(this)) {
            refreshStatus();
            return;
        }
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_ARGUMENTS, prefs.buildArguments(this));
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        if (runningImport != null) {
            runningImport.cancel();
        }
        super.onDestroy();
    }
}
