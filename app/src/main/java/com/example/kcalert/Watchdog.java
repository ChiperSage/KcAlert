package com.example.kcalert;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Alarm berkala: jika Android mematikan service, coba hidupkan lagi; jika ditolak sistem, beri tahu pengguna. */
public class Watchdog extends BroadcastReceiver {
    static final long PERIOD_MS = 10 * 60_000L;

    static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 1, new Intent(c, Watchdog.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + PERIOD_MS, pi(c));
    }

    @Override public void onReceive(Context ctx, Intent intent) {
        if (!ctx.getSharedPreferences("kc", Context.MODE_PRIVATE).getBoolean("enabled", false)) return; // dihentikan pengguna
        if (!AlertService.running) {
            boolean ok = false;
            try {
                Intent i = new Intent(ctx, AlertService.class);
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
                ok = true;
            } catch (Throwable ignored) { }
            if (!ok) {
                Notif.post(ctx, 3, Notif.CH_ALERT, "Pemantauan berhenti",
                        "Android menghentikan aplikasi. Ketuk untuk melanjutkan.", null, true, MainActivity.class);
            }
        }
        schedule(ctx);
    }
}
