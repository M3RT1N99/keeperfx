/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file LogActivity.java
 *     In-app viewer for the engine log.
 * @par Purpose:
 *     keeperfx.log lives in the app's private storage, and a release signed APK
 *     cannot be reached with "adb run-as", so without this screen the most
 *     useful diagnostic on the device would be unreadable. The log is flushed
 *     after every line, so it survives a crash and shows what the engine was
 *     doing last.
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
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

public class LogActivity extends Activity {

    /** Reading the whole file would be pointless on screen and slow to share. */
    private static final int MAX_BYTES = 512 * 1024;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView logView;
    private TextView headerView;
    private String currentLog = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        headerView = findViewById(R.id.logHeader);
        logView = findViewById(R.id.logText);

        ((Button) findViewById(R.id.logShare)).setOnClickListener(v -> shareLog());
        ((Button) findViewById(R.id.logCopy)).setOnClickListener(v -> copyLog());
        ((Button) findViewById(R.id.logRefresh)).setOnClickListener(v -> loadLog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadLog();
    }

    // ------------------------------------------------------------------ load

    private static File logFile(Context context) {
        return new File(GameData.gameDirectory(context), "keeperfx.log");
    }

    private void loadLog() {
        headerView.setText(R.string.log_loading);
        new Thread(() -> {
            final File file = logFile(this);
            final String text;
            final String header;
            if (!file.isFile()) {
                text = "";
                header = getString(R.string.log_none);
            } else {
                String body;
                try {
                    body = readTail(file, MAX_BYTES);
                } catch (IOException e) {
                    body = "Could not read the log: " + e.getMessage();
                }
                text = body;
                header = getString(R.string.log_header,
                    GameData.describeBytes(file.length()),
                    java.text.DateFormat.getDateTimeInstance()
                        .format(new java.util.Date(file.lastModified())));
            }
            mainHandler.post(() -> {
                currentLog = text;
                headerView.setText(header);
                logView.setText(text.isEmpty() ? getString(R.string.log_empty_hint) : text);
            });
        }, "kfx-log-read").start();
    }

    /** Reads at most maxBytes from the end of the file, aligned to a line start. */
    private static String readTail(File file, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            final long length = raf.length();
            final long from = Math.max(0, length - maxBytes);
            raf.seek(from);
            final byte[] buffer = new byte[(int) Math.min(length - from, maxBytes)];
            raf.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            if (from > 0) {
                final int nl = text.indexOf('\n');
                if (nl >= 0 && nl + 1 < text.length()) {
                    text = text.substring(nl + 1);
                }
                text = "… earlier lines omitted …\n\n" + text;
            }
            return text;
        }
    }

    // ----------------------------------------------------------------- share

    /** A short preamble so a shared log identifies the device and build. */
    private String diagnosticsHeader() {
        final Prefs prefs = new Prefs(this);
        final StringBuilder sb = new StringBuilder();
        sb.append("KeeperFX for Android\n");
        sb.append("app version    : ").append(appVersion()).append('\n');
        sb.append("KeeperFX data  : ")
          .append(GameData.describeInstalledVersion(this))
          .append('\n');
        sb.append("device         : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("android        : ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("abi            : ").append(String.join(", ", Build.SUPPORTED_ABIS)).append('\n');
        sb.append("input mode     : ").append(inputModeName(prefs.getInputMode())).append('\n');
        sb.append("data complete  : ").append(GameData.isComplete(this)).append('\n');
        sb.append("------------------------------------------------------------\n\n");
        return sb.toString();
    }

    private static String inputModeName(int mode) {
        switch (mode) {
            case Prefs.INPUT_TOUCH: return "touch";
            case Prefs.INPUT_POINTER: return "mouse & keyboard";
            default: return "automatic";
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void shareLog() {
        new Thread(() -> {
            try {
                final File outDir = new File(getCacheDir(), "shared-logs");
                //noinspection ResultOfMethodCallIgnored
                outDir.mkdirs();
                final File out = new File(outDir, "keeperfx-log.txt");
                try (Writer writer = new OutputStreamWriter(
                        new FileOutputStream(out), StandardCharsets.UTF_8)) {
                    writer.write(diagnosticsHeader());
                    writer.write(currentLog);
                }
                final android.net.Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".files", out);
                final Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.putExtra(Intent.EXTRA_SUBJECT, "KeeperFX Android log");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                mainHandler.post(() -> startActivity(
                    Intent.createChooser(intent, getString(R.string.log_share))));
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this,
                    "Could not share the log: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "kfx-log-share").start();
    }

    private void copyLog() {
        final ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
            "KeeperFX log", diagnosticsHeader() + currentLog));
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
    }
}
