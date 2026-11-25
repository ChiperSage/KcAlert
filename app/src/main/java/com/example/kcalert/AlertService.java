package com.example.kcalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AlertService extends Service {
    private static final String CH_STATUS = "status", CH_ALERT = "alert";
    private ScheduledExecutorService ex;
    private final AlertEngine engine = new AlertEngine();
    private int counter = 0;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        createChannels();
        startForeground(1, build(CH_STATUS, "KC Alert aktif", "Memulai...", null, false));
        if (ex != null) ex.shutdownNow();
        engine.reset();
        Config c = Config.load(this);
        ex = Executors.newSingleThreadScheduledExecutor();
        ex.scheduleWithFixedDelay(this::tick, 0, c.intervalSec, TimeUnit.SECONDS);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (ex != null) ex.shutdownNow();
        super.onDestroy();
    }

    private void tick() {
        try {
            Config cfg = Config.load(this);
            List<AlertEngine.Hit> hits = engine.process(KucoinApi.fetch(), cfg, System.currentTimeMillis());
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String thr = cfg.scope == 2 ? "per kelompok" : ">= " + cfg.pct + "%";
            status("Pantau " + engine.tracked + " token | " + cfg.windowMin + "m " + thr + " | " + time);
            if (!hits.isEmpty()) notifyHits(hits, cfg);
        } catch (Throwable e) {
            status("Error: " + e.getMessage());
        }
    }

    private void notifyHits(List<AlertEngine.Hit> hits, Config cfg) {
        int n = hits.size();
        StringBuilder sb = new StringBuilder();
        int max = Math.min(n, 10);
        for (int i = 0; i < max; i++) {
            AlertEngine.Hit h = hits.get(i);
            if (h.group != null) sb.append('[').append(h.group).append("] ");
            sb.append(h.symbol).append("  ")
              .append(String.format(Locale.US, "%+.2f%%", h.chg)).append("  @")
              .append(price(h.price)).append('\n');
        }
        if (n > max) sb.append("+").append(n - max).append(" lainnya");
        String title = cfg.scope == 2
                ? n + " alert kelompok (" + cfg.windowMin + "m)"
                : n + " alert (" + cfg.windowMin + "m, >= " + cfg.pct + "%)";
        if (cfg.scope == 0 && engine.tracked >= 20 && n * 100 / engine.tracked >= 30)
            title = "Pasar bergerak serentak: " + n + "/" + engine.tracked + " token";
        String first = hits.get(0).symbol + " " + String.format(Locale.US, "%+.2f%%", hits.get(0).chg);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(100 + (counter++ % 20), build(CH_ALERT, title, first, sb.toString().trim(), true));
    }

    private static String price(double p) {
        return p >= 1 ? String.format(Locale.US, "%.4f", p) : String.format(Locale.US, "%.8f", p);
    }

    private void status(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, build(CH_STATUS, "KC Alert aktif", text, null, false));
    }

    private Notification build(String ch, String title, String text, String big, boolean alert) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).setContentText(text);
        if (big != null) b.setStyle(new Notification.BigTextStyle().bigText(big));
        if (alert) {
            b.setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        } else {
            b.setOngoing(true);
        }
        return b.build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Alert harga", NotificationManager.IMPORTANCE_HIGH));
    }
}
