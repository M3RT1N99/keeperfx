/******************************************************************************/
// Free implementation of Bullfrog's Dungeon Keeper strategy game.
/******************************************************************************/
/**
 * @file PhoneTransfer.java
 *     Exports a whole installation and imports one, as far as Android is involved.
 * @par Purpose:
 *     An export goes into one file and from there to the system's share sheet, which
 *     offers whatever this device has. No target has a way into another app's storage:
 *     whatever receives the file decides where it lands, and the player then picks it
 *     in the import here. This class connects the launcher and the service to
 *     TransferBundle and TransferSwap, which do the actual work and know nothing about
 *     Android, and holds what has to know about content URIs and the share sheet.
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

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

final class PhoneTransfer {

    private static final String TAG = "KeeperFX";

    private PhoneTransfer() {
    }

    static File exportDirectory(Context context) {
        return new File(context.getFilesDir(), "exports");
    }

    // ----------------------------------------------------------------- export

    /** What the confirmation names, or why an export is not offered. */
    static final class ExportOffer {
        /** Null when it can go ahead. */
        final Message refusal;
        final Message version;
        final boolean music;
        final int saves;
        final int files;
        final long bytes;
        final long needed;
        final long free;

        ExportOffer(Message refusal) {
            this.refusal = refusal;
            this.version = null;
            this.music = false;
            this.saves = 0;
            this.files = 0;
            this.bytes = 0;
            this.needed = 0;
            this.free = 0;
        }

        ExportOffer(Message version, boolean music, TransferBundle.Plan plan, long free) {
            this.refusal = null;
            this.version = version;
            this.music = music;
            this.saves = plan.saves;
            this.files = plan.files();
            this.bytes = plan.bytes();
            this.needed = plan.fileSizeAtMost();
            this.free = free;
        }
    }

    /**
     * Only an installation known to be whole gets exported: the other device replaces what
     * it has with this, so a damaged one would spread the damage.
     */
    private static TransferBundle.Plan planExport(Context context) throws IOException {
        if (!GameData.isComplete(context)) {
            throw new TransferBundle.RefusedException(R.string.export_not_installed);
        }
        if (!GameData.hasManifest(context)) {
            throw new TransferBundle.RefusedException(R.string.export_no_manifest);
        }
        final InstallManifest.Report report = GameData.verifyInstalledFiles(context);
        if (!report.isClean()) {
            final int damaged = report.stableProblems.size() + report.alphaProblems.size();
            throw new TransferBundle.RefusedException(R.string.export_damaged, damaged);
        }
        return TransferBundle.plan(GameData.gameDirectory(context),
            GameData.manifestDirectory(context), GameData.alphaBackupDirectory(context),
            new Prefs(context).transferableSettings());
    }

    /** Blocking, it looks at every file; call from a background thread. */
    static ExportOffer checkExport(Context context) {
        try {
            final TransferBundle.Plan plan = planExport(context);
            final File directory = context.getFilesDir();
            final File previous = TransferBundle.finishedExport(exportDirectory(context));
            final long reclaimable = (previous == null) ? 0 : previous.length();
            FileUtil.requireRoom(directory, plan.fileSizeAtMost(), reclaimable,
                R.string.no_room_export);
            return new ExportOffer(GameData.describeInstalledVersion(context),
                GameData.hasMusic(context), plan, directory.getUsableSpace() + reclaimable);
        } catch (TransferBundle.RefusedException | FileUtil.NoRoomException e) {
            return new ExportOffer(e.message);
        } catch (IOException e) {
            Log.e(TAG, "Could not look at the installation", e);
            return new ExportOffer(new Message(R.string.export_cannot_look, Message.detailOf(e)));
        }
    }

    /** Runs on the service's thread. Checks everything again: the dialog may be old by now. */
    static File runExport(Context context, TransferBundle.Progress progress) throws IOException {
        final TransferBundle.Plan plan = planExport(context);
        final Prefs prefs = new Prefs(context);
        final String release = TransferBundle.plainVersion(prefs.getInstalledVersion());
        final String alpha = TransferBundle.plainVersion(prefs.getInstalledAlphaVersion());
        final TransferBundle.Marker marker = new TransferBundle.Marker(
            TransferBundle.plainVersion(AppUpdater.installedVersionName(context)),
            BuildConfig.ENGINE_BUILD, release, alpha, plan.entries(), plan.bytes(), plan.saves);
        return TransferBundle.writeExport(exportDirectory(context),
            TransferBundle.fileName(release, alpha), plan, marker, progress);
    }

    /**
     * The share sheet for a finished file. The grant travels on the clip as well as on
     * the flags, because that is what the chooser hands on to the target it starts; the
     * grant then belongs to that target and does not need this app to stay alive. The
     * provider is started again if the target reads after the process has gone.
     */
    static Intent shareSheet(Context context, File file) {
        final Uri uri = FileProvider.getUriForFile(
            context, context.getPackageName() + ".files", file);
        final Intent send = new Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.getName())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(file.getName(), uri));
        return Intent.createChooser(send, context.getString(R.string.export_share_title));
    }

    /** Removes the finished file and whatever points at it. */
    static void deleteExport(Context context) {
        FileUtil.deleteContents(exportDirectory(context));
        DownloadService.cancelFinishedNotification(context);
    }

    // ----------------------------------------------------------------- import

    /** Where the picker starts. Whatever received the file decides where it really is. */
    static Uri downloadsUri() {
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Download");
    }

    /** What the confirmation names, or why this file will not be imported. */
    static final class ImportOffer {
        /** Null when it can go ahead. */
        final Message refusal;
        final TransferBundle.Marker marker;
        final int savesHere;
        final boolean installedHere;
        final long free;

        ImportOffer(Message refusal) {
            this.refusal = refusal;
            this.marker = null;
            this.savesHere = 0;
            this.installedHere = false;
            this.free = 0;
        }

        ImportOffer(TransferBundle.Marker marker, int savesHere, boolean installedHere,
                long free) {
            this.refusal = null;
            this.marker = marker;
            this.savesHere = savesHere;
            this.installedHere = installedHere;
            this.free = free;
        }
    }

    /** Blocking, it reads from a content provider; call from a background thread. */
    static ImportOffer inspect(Context context, Uri file) {
        if (GameProcess.isAlive(context)) {
            return new ImportOffer(new Message(R.string.game_still_open));
        }
        try (InputStream in = context.getContentResolver().openInputStream(file)) {
            if (in == null) {
                return new ImportOffer(new Message(R.string.import_bundle_cannot_read));
            }
            final TransferBundle.Marker marker = TransferBundle.readMarker(in);
            final File directory = context.getFilesDir();
            FileUtil.requireRoom(directory, marker.bytes, R.string.no_room_import);
            final File install = GameData.gameDirectory(context);
            return new ImportOffer(marker, TransferBundle.countSaves(install),
                install.isDirectory(), directory.getUsableSpace());
        } catch (TransferBundle.RefusedException | FileUtil.NoRoomException e) {
            return new ImportOffer(e.message);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Could not read the picked file", e);
            return new ImportOffer(new Message(R.string.import_bundle_cannot_read_detail,
                Message.detailOf(e)));
        }
    }

    /** Runs on the service's thread. Leaves everything as it was unless this returns. */
    static void runImport(Context context, Uri file, TransferBundle.Progress progress)
            throws IOException {
        if (GameProcess.isAlive(context)) {
            throw new TransferBundle.RefusedException(R.string.game_still_open);
        }
        try (InputStream in = context.getContentResolver().openInputStream(file)) {
            if (in == null) {
                throw new Message.Failure(R.string.import_bundle_cannot_read);
            }
            swap(context).install(in, BundledConfig.relativePaths(context),
                AppUpdater.installedVersionName(context), progress);
        }
    }

    private static TransferSwap swap(Context context) {
        final File files = context.getFilesDir();
        final Prefs prefs = new Prefs(context);
        return new TransferSwap(GameData.gameDirectory(context),
            GameData.manifestDirectory(context), GameData.alphaBackupDirectory(context),
            // All of it belongs to what is being replaced: what was on its way to being
            // installed there, and the previous installation's own copy for sending.
            new File[] {
                new File(files, "alpha-cache"),
                new File(files, "downloads"),
                new File(files, "updates"),
                exportDirectory(context),
            },
            transferWork(context), new File(files, "transfer-trash"),
            prefs::replaceWithTransferred);
    }

    private static File transferWork(Context context) {
        return new File(context.getFilesDir(), "transfer");
    }

    /**
     * Whether an import is running or a dead process left one behind, for the engine's
     * process to ask. It can only see the disk: this process's service is not its own.
     */
    static boolean importUnfinished(Context context) {
        return transferWork(context).exists();
    }

    /**
     * For every start of the launcher, before anything looks at the installation: picks
     * up after a process that died in the middle of an import. Quick, when there is
     * nothing to do it is one look at the disk.
     */
    static void cleanUpAtStart(Context context) {
        if (DownloadService.isRunning()) {
            return;
        }
        final TransferSwap swap = swap(context);
        swap.recover();
        if (TransferBundle.removeExpired(exportDirectory(context), System.currentTimeMillis())) {
            DownloadService.cancelFinishedNotification(context);
        }
        new Thread(swap::emptyTrash, "kfx-transfer-cleanup").start();
    }

    // ---------------------------------------------------- after an import

    /** The file an import read, for offering to delete it afterwards. */
    static final class ImportedFile {
        final Uri uri;
        final Message where;

        ImportedFile(Uri uri, Message where) {
            this.uri = uri;
            this.where = where;
        }
    }

    /** Asked while the job still holds its grant, which ends with it. */
    static ImportedFile describeImported(Context context, Uri file) {
        return new ImportedFile(file, whereIs(documentIdOf(file), displayName(context, file)));
    }

    private static String displayName(Context context, Uri file) {
        try (Cursor cursor = context.getContentResolver().query(file,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "The provider does not name the file", e);
        }
        return "";
    }

    private static String documentIdOf(Uri file) {
        try {
            return DocumentsContract.getDocumentId(file);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * Words for where a picked file is. Android's own storage spells the path out in
     * its document ids ("primary:Download/x.zip"), other providers use opaque numbers,
     * and for those the name is all anyone can say.
     */
    static Message whereIs(String documentId, String displayName) {
        final int colon = documentId.indexOf(':');
        if (colon > 0 && documentId.indexOf('/', colon) > 0) {
            return Message.plain(documentId.substring(colon + 1));
        }
        return displayName.isEmpty()
            ? new Message(R.string.import_bundle_picked_file) : Message.plain(displayName);
    }

    /**
     * The launcher holds the grant that makes this possible for as long as the activity
     * that picked the file lives, and many a provider does not allow it in the first
     * place, so not being able to is an ordinary answer.
     *
     * @return whether the file is gone
     */
    static boolean deleteImported(Context context, Uri file) {
        try {
            return DocumentsContract.deleteDocument(context.getContentResolver(), file);
        } catch (Exception e) {
            Log.i(TAG, "Could not delete the received file", e);
            return false;
        }
    }
}
