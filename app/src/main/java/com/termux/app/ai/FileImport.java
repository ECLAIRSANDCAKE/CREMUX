package com.termux.app.ai;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Copies a file picked with the system file picker into {@code ~/re} so terminal tools can reach it. */
final class FileImport {

    private FileImport() { }

    @NonNull
    static File reDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "re");
    }

    /** {@code Downloads/Cremux}: the terminal's home is invisible to other apps, so exports are copied here. */
    @NonNull
    static File downloadsDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Cremux");
    }

    /** False when the Storage permission is still missing, in which case a copy to Downloads would fail. */
    static boolean hasStorageAccess(@NonNull Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true;
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    /** Copies {@code file} into {@link #downloadsDir()} (replacing a file of the same name). Blocking. */
    @NonNull
    static File copyToDownloads(@NonNull File file) throws IOException {
        File dl = downloadsDir();
        if (!dl.isDirectory() && !dl.mkdirs()) throw new IOException("the Downloads folder is not writable");
        File target = new File(dl, file.getName());
        FileInputStream in = new FileInputStream(file);
        try {
            FileOutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        return target;
    }

    /** Blocking; call from a background thread. */
    @NonNull
    static File copyToHome(@NonNull Context context, @NonNull Uri uri) throws IOException {
        String name = sanitize(displayName(context, uri));
        File dir = reDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create ~/re");
        File target = new File(dir, name);
        if (target.exists()) target = new File(dir, System.currentTimeMillis() / 1000 + "_" + name);

        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open the file");
        try {
            FileOutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        return target;
    }

    @NonNull
    private static String displayName(@NonNull Context context, @NonNull Uri uri) {
        Cursor c = null;
        try {
            c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (RuntimeException ignored) {
            // Fall back to the last path segment.
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    @NonNull
    static String sanitize(@NonNull String name) {
        String n = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "file";
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }
}
