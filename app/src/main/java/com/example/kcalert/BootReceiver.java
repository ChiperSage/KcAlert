package com.example.kcalert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!ctx.getSharedPreferences("kc", Context.MODE_PRIVATE).getBoolean("enabled", false)) return;
        try {
            Intent i = new Intent(ctx, AlertService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Throwable t) {
            Notif.post(ctx, 3, Notif.CH_ALERT, "HP baru dinyalakan",
                    "Ketuk untuk melanjutkan pemantauan alert.", null, true, MainActivity.class);
        }
    }
}
