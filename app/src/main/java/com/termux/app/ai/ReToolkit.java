package com.termux.app.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.ui.TerminalCommands;
import com.termux.app.ui.Picker;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs the {@code re-setup} script and runs it in the terminal so the user can watch it. */
public final class ReToolkit {

    private ReToolkit() { }

    public static void promptInstall(@NonNull final Activity activity) {
        promptInstall(activity, null);
    }

    /** @param onDone runs on the UI thread after an in-app install finishes (not for the terminal route). */
    public static void promptInstall(@NonNull final Activity activity, @Nullable final Runnable onDone) {
        final String[] labels = {
            "Core tools — radare2, jadx, apktool, binutils, gdb (recommended)",
            "Core + Ghidra — large download, runs inside an Ubuntu proot",
            "Linux environment (proot-distro) — Debian, Ubuntu, Alpine, Arch or Fedora…",
        };
        Picker.show(activity, "Install reverse-engineering tools",
            "A speed test runs first, then you get a live progress bar with time remaining.",
            labels, -1, which -> {
                if (which == 2) promptLinux(activity);
                else installInline(activity, which == 0 ? "core" : "all", onDone);
            });
    }

    private static void promptLinux(@NonNull final Activity activity) {
        final String[] ids = {"debian", "ubuntu", "alpine", "archlinux", "fedora"};
        final String[] labels = {
            "Debian — recommended, widest package support",
            "Ubuntu",
            "Alpine — smallest download",
            "Arch Linux",
            "Fedora",
        };
        Picker.show(activity, "Choose a Linux",
            "Runs without root. Open it with re-linux; your home folder appears as /work inside.",
            labels, -1, which -> install(activity, "linux " + ids[which]));
    }

    /** @param mode {@code core}, {@code ghidra} or {@code all}. */
    public static void install(@NonNull Context context, @NonNull String mode) {
        File bin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        if (!bin.isDirectory()) {
            Toast.makeText(context, "Open the Terminal once and let the first-run setup finish, then try again.",
                Toast.LENGTH_LONG).show();
            return;
        }
        try {
            File script = new File(bin, "re-setup");
            writeRaw(context, R.raw.cremux_re_setup, script);
            script.setReadable(true, false);
            script.setExecutable(true, false);
        } catch (IOException e) {
            Toast.makeText(context, "Could not write re-setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        TerminalCommands.run(context, "re-setup " + mode);
    }

    /**
     * Runs the installer inside a progress dialog (no trip to the terminal). Falls back to the terminal on failure
     * so the user can read the full output.
     *
     * @param mode {@code core}, {@code ghidra} or {@code all}
     */
    public static void installInline(@NonNull final Activity activity, @NonNull final String mode,
                                     @Nullable final Runnable onDone) {
        final Context app = activity.getApplicationContext();
        try {
            File script = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "re-setup");
            writeRaw(app, R.raw.cremux_re_setup, script);
            script.setReadable(true, false);
            script.setExecutable(true, false);
        } catch (IOException e) {
            Toast.makeText(activity, "Could not write re-setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        final ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        box.addView(bar);
        final TextView line = new TextView(activity);
        line.setTextSize(13);
        line.setSingleLine(false);
        line.setMaxLines(4);
        line.setGravity(Gravity.START);
        line.setPadding(0, pad / 2, 0, 0);
        line.setText("Starting…");
        box.addView(line);

        final AtomicBoolean cancel = new AtomicBoolean(false);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("Installing tools")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel", (d, w) -> cancel.set(true))
            .create();
        dialog.show();

        new Thread(() -> {
            final String[] last = {""};
            int rc;
            try {
                rc = LiveShell.run(app, "re-setup \"$1\" 2>&1", new File(TermuxConstants.TERMUX_HOME_DIR_PATH), l -> {
                    // The script redraws a progress bar with carriage returns: show only the newest frame.
                    String t = l.substring(l.lastIndexOf('\r') + 1).trim();
                    if (t.isEmpty()) return;
                    last[0] = t;
                    activity.runOnUiThread(() -> line.setText(t));
                }, cancel, mode);
            } catch (IOException | RuntimeException e) {
                last[0] = e.getMessage() == null ? e.toString() : e.getMessage();
                rc = -1;
            }
            final int code = rc;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                dialog.dismiss();
                if (code == 0) {
                    Toast.makeText(activity, "Tools installed.", Toast.LENGTH_LONG).show();
                } else if (code == LiveShell.CANCELLED) {
                    Toast.makeText(activity, "Install cancelled. Run it again to continue; finished parts are kept.",
                        Toast.LENGTH_LONG).show();
                } else {
                    new AlertDialog.Builder(activity)
                        .setTitle("Install did not finish")
                        .setMessage(last[0].isEmpty() ? "The installer exited with code " + code + "." : last[0])
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Retry in terminal", (d, w) -> install(activity, mode))
                        .show();
                }
                if (onDone != null) onDone.run();
            });
        }, "cremux-re-install").start();
    }

    private static void writeRaw(Context context, int rawId, File target) throws IOException {
        InputStream in = context.getResources().openRawResource(rawId);
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            FileOutputStream out = new FileOutputStream(target);
            try {
                out.write(buf.toByteArray());
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
