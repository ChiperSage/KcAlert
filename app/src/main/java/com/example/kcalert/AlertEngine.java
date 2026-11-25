package com.example.kcalert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class AlertEngine {
    /** Kondisi harus bertahan segini lama (minimal 2 kali cek) sebelum alert, untuk menyaring lonjakan satu kali. */
    static final long CONFIRM_MS = 20_000L;

    static final class Sample { final long t; final double p; Sample(long t, double p) { this.t = t; this.p = p; } }

    static final class Added {
        final String sym; final long t; final double p;
        Added(String s, long t, double p) { sym = s; this.t = t; this.p = p; }
    }

    static final class State {
        final ArrayDeque<Sample> s = new ArrayDeque<>();
        long lastSample, lastAlert, lastSeen, condSince;
        boolean armed = true;
    }

    static final class Hit {
        final String symbol, group; final double chg, price, vol, thr;
        Hit(String s, double c, double p, double v, double thr, String group) {
            symbol = s; chg = c; price = p; vol = v; this.thr = thr; this.group = group;
        }
    }

    private final Map<String, State> map = new HashMap<>();
    private final List<Added> added = new ArrayList<>();
    volatile int tracked = 0;

    synchronized void reset() { map.clear(); added.clear(); tracked = 0; }

    /** Pulihkan riwayat harga dan waktu alert terakhir dari database setelah restart. */
    synchronized void restore(Map<String, List<Sample>> samples, Map<String, Long> lastAlerts) {
        map.clear();
        for (Map.Entry<String, List<Sample>> e : samples.entrySet()) {
            State st = new State();
            for (Sample s : e.getValue()) { st.s.addLast(s); st.lastSample = s.t; }
            st.lastSeen = st.lastSample;
            Long la = lastAlerts.get(e.getKey());
            if (la != null) { st.lastAlert = la; st.armed = false; }
            map.put(e.getKey(), st);
        }
    }

    /** Sampel baru sejak pemanggilan terakhir (untuk disimpan ke database). */
    synchronized List<Added> drainAdded() {
        List<Added> copy = new ArrayList<>(added);
        added.clear();
        return copy;
    }

    synchronized List<Hit> process(List<KucoinApi.Tick> ticks, Config cfg, long now) {
        long winMs = cfg.windowMin * 60_000L;
        long slack = Math.max(360_000L, cfg.intervalSec * 2000L); // toleransi celah data

        // mode kelompok: token -> kelompok (jika di >1 kelompok, ambang terkecil dipakai)
        Map<String, Config.Group> gmap = null;
        if (cfg.scope == 2) {
            gmap = new HashMap<>();
            for (Config.Group g : cfg.groups) {
                if (!g.enabled) continue;
                for (String s : g.tokens) {
                    Config.Group o = gmap.get(s);
                    if (o == null || g.pct < o.pct) gmap.put(s, g);
                }
            }
        }

        List<Hit> hits = new ArrayList<>();
        int tr = 0;
        for (KucoinApi.Tick t : ticks) {
            if (t.last <= 0) continue;
            double thr = cfg.pct;
            String gname = null;
            if (cfg.scope == 0) {
                if (t.volValue < cfg.minVol) continue; // filter volume hanya mode Semua
            } else if (cfg.scope == 1) {
                if (!cfg.tokens.contains(t.symbol)) continue;
            } else {
                Config.Group g = gmap.get(t.symbol);
                if (g == null) continue;
                thr = g.pct;
                gname = g.name;
            }

            State st = map.get(t.symbol);
            if (st == null) { st = new State(); map.put(t.symbol, st); }
            st.lastSeen = now;
            tr++;

            // jam mundur / data dari masa depan: buang riwayat token ini
            if (st.lastSample > now) { st.s.clear(); st.lastSample = 0; st.condSince = 0; }

            // sampling ~1 menit sekali
            if (now - st.lastSample >= 55_000L) {
                st.s.addLast(new Sample(now, t.last));
                st.lastSample = now;
                added.add(new Added(t.symbol, now, t.last));
            }
            // buang sampel lama, sisakan satu sampel di/sebelum (now - window)
            while (st.s.size() > 1) {
                Iterator<Sample> it = st.s.iterator();
                it.next();
                if (it.next().t <= now - winMs) st.s.removeFirst(); else break;
            }
            Sample ref = st.s.peekFirst();
            // riwayat belum cukup, atau ada celah data (bot sempat mati / koneksi putus lama)
            if (ref == null || ref.p <= 0 || ref.t > now - winMs + 60_000L || now - ref.t > winMs + slack) {
                st.condSince = 0;
                continue;
            }

            double chg = (t.last - ref.p) / ref.p * 100.0;
            double abs = Math.abs(chg);
            boolean dirOk = cfg.dir == 0 || (cfg.dir == 1 && chg > 0) || (cfg.dir == 2 && chg < 0);
            boolean cond = dirOk && abs >= thr;

            if (abs < thr * 0.5) st.armed = true; // re-arm (histeresis)
            if (cond) { if (st.condSince == 0) st.condSince = now; } else st.condSince = 0;
            boolean confirmed = cond && now - st.condSince >= CONFIRM_MS;

            if (confirmed && st.armed && now - st.lastAlert >= cfg.cooldownMin * 60_000L) {
                st.armed = false;
                st.lastAlert = now;
                st.condSince = 0;
                hits.add(new Hit(t.symbol, chg, t.last, t.volValue, thr, gname));
            }
        }
        tracked = tr;

        // bersihkan token yang sudah lama tidak terlihat
        Iterator<Map.Entry<String, State>> it = map.entrySet().iterator();
        while (it.hasNext()) if (it.next().getValue().lastSeen < now - winMs * 2 - slack) it.remove();

        Collections.sort(hits, (a, b) -> Double.compare(Math.abs(b.chg), Math.abs(a.chg)));
        return hits;
    }
}
