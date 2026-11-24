package com.termux.app.ai;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.termux.R;

import java.util.List;

/** Tells the user a long job finished: a short buzz, plus a notification when the app is in the background. */
final class DoneAlert {

    private static final String CHANNEL = "cremux_done";
    private static final int ID = 7301;

    private DoneAlert() { }

    /** Safe from any thread. {@code open} is the screen the notification returns to. */
    static void notify(@NonNull Context context, @NonNull String title, @NonNull String text, @NonNull Class<?> open) {
        try {
            Context app = context.getApplicationContext();
            if (foreground(app)) {
                buzz(app);
                return;
            }
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Finished jobs", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent i = new Intent(app, open).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(app, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(ID, new NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
        } catch (RuntimeException ignored) {
            // An alert must never break the job that triggered it.
        }
    }

    private static boolean foreground(@NonNull Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
        if (ps == null) return false;
        int me = android.os.Process.myPid();
        for (ActivityManager.RunningAppProcessInfo p : ps) {
            if (p.pid == me) return p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static void buzz(@NonNull Context c) {
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        else v.vibrate(60);
    }
}
