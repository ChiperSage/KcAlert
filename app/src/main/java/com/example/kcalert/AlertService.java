package com.example.kcalert;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AlertService extends Service {
    static volatile boolean running = false;

    private static final long NOTIFY_MIN_GAP_MS = 60_000L;   // maksimal 1 notifikasi alert per menit
    private static final long STALE_AFTER_MS = 5 * 60_000L;  // gagal ambil data selama ini -> peringatan

    private ScheduledExecutorService ex;
    private final AlertEngine engine = new AlertEngine();
    private final List<AlertEngine.Hit> pending = new ArrayList<>();
    private long lastNotify = 0, firstFailAt = 0;
    private int fails = 0, counter = 0;
    private boolean staleNotified = false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        Notif.channels(this);
        startForeground(1, Notif.build(this, Notif.CH_STATUS, "KC Alert aktif", "Memulai...", null, false, MainActivity.class));
        running = true;
        getSharedPreferences("kc", MODE_PRIVATE).edit()
                .putBoolean("enabled", true).putLong("hb", System.currentTimeMillis()).apply();
        if (ex != null) ex.shutdownNow();

        // pulihkan riwayat dari database agar restart tidak membuat bot "buta" selama satu jendela
        try {
            engine.restore(Store.get(this).loadSamples(), Store.get(this).lastAlerts());
        } catch (Throwable e) {
            engine.reset();
        }
        fails = 0; firstFailAt = 0; staleNotified = false;
        Watchdog.schedule(this);

        final ScheduledExecutorService me = Executors.newSingleThreadScheduledExecutor();
        ex = me;
        me.schedule(() -> tick(me), 0, TimeUnit.SECONDS);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (ex != null) ex.shutdownNow();
        super.onDestroy();
    }

    private void tick(ScheduledExecutorService me) {
        if (me.isShutdown()) return;
        int base = 30;
        PowerManager.WakeLock wl = null;
        try {
            wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kcalert:tick");
            wl.acquire(45_000L);
            Config cfg = Config.load(this);
            base = cfg.intervalSec;

            KucoinApi.Result r = KucoinApi.fetch();
            long now = r.serverTime > 0 ? r.serverTime : System.currentTimeMillis(); // waktu server, kebal jam HP salah
            List<AlertEngine.Hit> hits = engine.process(r.ticks, cfg, now);
            List<AlertEngine.Added> added = engine.drainAdded();

            // sukses
            fails = 0; firstFailAt = 0;
            getSharedPreferences("kc", MODE_PRIVATE).edit().putLong("hb", System.currentTimeMillis()).apply();
            if (staleNotified) {
                staleNotified = false;
                Notif.post(this, 2, Notif.CH_ALERT, "Pemantauan pulih", "Data KuCoin kembali normal.", null, true, MainActivity.class);
            }

            // simpan ke database; kegagalan DB tidak boleh mematikan alert
            try {
                Store st = Store.get(this);
                st.saveSamples(added);
                st.pruneSamples(now - (cfg.windowMin * 60_000L + 10 * 60_000L));
                if (!hits.isEmpty()) st.logAlerts(hits, now);
            } catch (Throwable ignored) { }

            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String thr = cfg.scope == 2 ? "per kelompok" : ">= " + cfg.pct + "%";
            status("Pantau " + engine.tracked + " token | " + cfg.windowMin + "m " + thr + " | " + time);

            pending.addAll(hits);
            if (!pending.isEmpty() && System.currentTimeMillis() - lastNotify >= NOTIFY_MIN_GAP_MS) {
                sendPending(cfg);
                lastNotify = System.currentTimeMillis();
            }
        } catch (Throwable e) {
            fails++;
            if (firstFailAt == 0) firstFailAt = System.currentTimeMillis();
            status("Error (" + fails + "x): " + describe(e));
            if (!staleNotified && System.currentTimeMillis() - firstFailAt >= STALE_AFTER_MS) {
                staleNotified = true;
                Notif.post(this, 2, Notif.CH_ALERT, "Data KuCoin tidak bisa diambil",
                        "Gagal lebih dari 5 menit. Alert tertunda sampai koneksi pulih.", null, true, MainActivity.class);
            }
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
        }

        // jadwal berikutnya; setelah gagal, jeda naik bertahap (maks 5 menit)
        long delay = fails == 0 ? base : Math.min(base * (1L << Math.min(fails, 5)), 300L);
        try {
            if (!me.isShutdown()) me.schedule(() -> tick(me), delay, TimeUnit.SECONDS);
        } catch (Throwable ignored) { }
    }

    private void sendPending(Config cfg) {
        // satu entri per token (perubahan terbesar), urut dari yang terbesar
        Map<String, AlertEngine.Hit> best = new LinkedHashMap<>();
        for (AlertEngine.Hit h : pending) {
            AlertEngine.Hit o = best.get(h.symbol);
            if (o == null || Math.abs(h.chg) > Math.abs(o.chg)) best.put(h.symbol, h);
        }
        List<AlertEngine.Hit> list = new ArrayList<>(best.values());
        Collections.sort(list, (a, b) -> Double.compare(Math.abs(b.chg), Math.abs(a.chg)));
        pending.clear();

        int n = list.size();
        StringBuilder sb = new StringBuilder();
        int max = Math.min(n, 10);
        for (int i = 0; i < max; i++) {
            AlertEngine.Hit h = list.get(i);
            if (h.group != null) sb.append('[').append(h.group).append("] ");
            sb.append(h.symbol).append("  ")
              .append(String.format(Locale.US, "%+.2f%%", h.chg)).append("  @")
              .append(price(h.price)).append('\n');
        }
        if (n > max) sb.append("+").append(n - max).append(" lainnya (lihat Riwayat)");
        String title = cfg.scope == 2
                ? n + " alert kelompok (" + cfg.windowMin + "m)"
                : n + " alert (" + cfg.windowMin + "m, >= " + cfg.pct + "%)";
        int tracked = engine.tracked;
        if (cfg.scope == 0 && tracked >= 20 && n * 100 / tracked >= 30)
            title = "Pasar bergerak serentak: " + n + "/" + tracked + " token";
        String first = list.get(0).symbol + " " + String.format(Locale.US, "%+.2f%%", list.get(0).chg);
        Notif.post(this, 100 + (counter++ % 20), Notif.CH_ALERT, title, first, sb.toString().trim(), true, HistoryActivity.class);
    }

    private static String price(double p) {
        return p >= 1 ? String.format(Locale.US, "%.4f", p) : String.format(Locale.US, "%.8f", p);
    }

    private void status(String text) {
        Notif.post(this, 1, Notif.CH_STATUS, "KC Alert aktif", text, text, false, MainActivity.class);
    }

    /** Terjemahkan error TLS/sertifikat menjadi petunjuk yang bisa langsung dicek pengguna. */
    private static String describe(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String n = t.getClass().getName();
            String m = String.valueOf(t.getMessage());
            if (n.contains("CertPath") || n.contains("SSLHandshake") || n.contains("CertificateException")
                    || m.contains("Chain validation") || m.contains("Trust anchor")) {
                return "Sertifikat HTTPS ditolak. Cek tanggal & jam HP (harus otomatis), "
                        + "matikan VPN/pemblokir iklan/DNS filter, dan update Android. (" + m + ")";
            }
        }
        return String.valueOf(e.getMessage());
    }
}
