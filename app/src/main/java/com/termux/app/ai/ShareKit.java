package com.termux.app.ai;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Hands result files (signed APKs, zips) to Android's installer, share sheet and file manager. */
final class ShareKit {

    private ShareKit() { }

    /** A content URI for a file under the terminal home, or null for anything else (never credentials). */
    private static Uri uriFor(@NonNull Context c, @NonNull File f) {
        try {
            File canon = f.getCanonicalFile();
            String home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH).getCanonicalPath() + "/";
            if (!canon.isFile() || !canon.getPath().startsWith(home) || AiSafety.isSensitivePath(canon.getPath())) return null;
            return FileProvider.getUriForFile(c, c.getPackageName() + ".cremux.share", canon);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    static void install(@NonNull Activity a, @NonNull File apk) {
        Uri uri = uriFor(a, apk);
        if (uri == null) {
            openDownloads(a);
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(a, "No installer found. The APK is also in Downloads/Cremux.", Toast.LENGTH_LONG).show();
            openDownloads(a);
        }
    }

    static void share(@NonNull Activity a, @NonNull List<File> files, @NonNull String mime) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) {
            Uri u = uriFor(a, f);
            if (u != null) uris.add(u);
        }
        if (uris.isEmpty()) {
            Toast.makeText(a, "Nothing to share.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // ClipData makes the grant apply to every URI on all Android versions.
        ClipData clip = ClipData.newRawUri("result", uris.get(0));
        for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(clip);
        a.startActivity(Intent.createChooser(i, "Share"));
    }

    static void openDownloads(@NonNull Activity a) {
        try {
            a.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(a, "Open the Downloads app and look in the Cremux folder.", Toast.LENGTH_LONG).show();
        }
    }

    static void copy(@NonNull Context c, @NonNull String label, @NonNull String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
    }
}
