package com.termux.app.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/** Preferences and visual effects private to the redesigned UI. */
public final class UiPrefs {

    private static final String FILE = "redesign_prefs";
    public static final String KEY_BLUR = "ui_blur";
    public static final String KEY_AMOLED = "ui_amoled";
    public static final String KEY_NOTIFICATIONS = "ui_notifications";
    /** Download the build output (only the build that fits this device) when a workflow run passes. */
    public static final String KEY_GH_AUTO_DOWNLOAD = "gh_auto_download";

    private UiPrefs() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean getBoolean(Context c, String key, boolean def) {
        return prefs(c).getBoolean(key, def);
    }

    public static void setBoolean(Context c, String key, boolean value) {
        prefs(c).edit().putBoolean(key, value).apply();
    }

    /** AMOLED Black is the default; it only turns off once the user picks another theme. */
    public static boolean isAmoled(Context c) {
        return getBoolean(c, KEY_AMOLED, true);
    }

    /**
     * Set the app-wide night mode from the {@code night-mode} property, forcing dark when AMOLED is active
     * (AMOLED is a dark palette, so it must not follow a light system theme).
     */
    public static void applyNightMode(Context c, String propertyValue) {
        com.termux.shared.termux.theme.TermuxThemeUtils.setAppNightMode(isAmoled(c) ? "true" : propertyValue);
    }

    /** Show a dialog, applying the blur-behind effect on Android 12+ when enabled. */
    public static Dialog show(@NonNull Context context, @NonNull AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        // Background threads post dialogs after the screen may be gone: showing then throws BadTokenException.
        if (context instanceof android.app.Activity) {
            android.app.Activity a = (android.app.Activity) context;
            if (a.isFinishing() || a.isDestroyed()) return dialog;
        }
        applyBlur(context, dialog);
        try {
            dialog.show();
        } catch (RuntimeException ignored) {
            // Window token already invalid.
        }
        return dialog;
    }

    public static void applyBlur(Context context, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        boolean blur = getBoolean(context, KEY_BLUR, true);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(blur ? 0.35f : 0.6f);
        if (blur && Build.VERSION.SDK_INT >= 31) {
            try {
                window.addFlags(0x00000004); // FLAG_BLUR_BEHIND
                WindowManager.LayoutParams lp = window.getAttributes();
                WindowManager.LayoutParams.class.getMethod("setBlurBehindRadius", int.class).invoke(lp, 40);
                window.setAttributes(lp);
            } catch (Exception ignored) {
                // Blur is purely cosmetic.
            }
        }
    }
}
