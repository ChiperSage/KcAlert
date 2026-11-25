package com.example.kcalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class Notif {
    static final String CH_STATUS = "status", CH_ALERT = "alert";

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Alert harga", NotificationManager.IMPORTANCE_HIGH));
    }

    static Notification build(Context c, String ch, String title, String text, String big,
                              boolean alert, Class<?> target) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, ch) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).setContentText(text);
        if (big != null) b.setStyle(new Notification.BigTextStyle().bigText(big));
        if (target != null) {
            b.setContentIntent(PendingIntent.getActivity(c, 0, new Intent(c, target),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        if (alert) {
            b.setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        } else {
            b.setOngoing(true);
        }
        return b.build();
    }

    static void post(Context c, int id, String ch, String title, String text, String big,
                     boolean alert, Class<?> target) {
        channels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(id, build(c, ch, title, text, big, alert, target));
    }
}
