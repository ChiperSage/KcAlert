package com.example.kcalert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class AlertEngine {
    static final class Sample { final long t; final double p; Sample(long t, double p) { this.t = t; this.p = p; } }

    static final class State {
        final ArrayDeque<Sample> s = new ArrayDeque<>();
        long lastSample, lastAlert, lastSeen;
        boolean armed = true;
    }

    static final class Hit {
        final String symbol, group; final double chg, price, vol, thr;
        Hit(String s, double c, double p, double v, double thr, String group) {
            symbol = s; chg = c; price = p; vol = v; this.thr = thr; this.group = group;
        }
    }

    private final Map<String, State> map = new HashMap<>();
    int tracked = 0;

    void reset() { map.clear(); tracked = 0; }

    List<Hit> process(List<KucoinApi.Tick> ticks, Config cfg, long now) {
        long winMs = cfg.windowMin * 60_000L;

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

            // sampling ~1 menit sekali
            if (now - st.lastSample >= 55_000L) {
                st.s.addLast(new Sample(now, t.last));
                st.lastSample = now;
            }
            // buang sampel lama, sisakan satu sampel di/sebelum (now - window)
            while (st.s.size() > 1) {
                Iterator<Sample> it = st.s.iterator();
                it.next();
                if (it.next().t <= now - winMs) st.s.removeFirst(); else break;
            }
            Sample ref = st.s.peekFirst();
            if (ref == null || ref.t > now - winMs + 60_000L || ref.p <= 0) continue; // riwayat belum cukup

            double chg = (t.last - ref.p) / ref.p * 100.0;
            double abs = Math.abs(chg);
            boolean dirOk = cfg.dir == 0 || (cfg.dir == 1 && chg > 0) || (cfg.dir == 2 && chg < 0);

            if (abs < thr * 0.5) st.armed = true; // re-arm (histeresis)
            if (dirOk && abs >= thr && st.armed && now - st.lastAlert >= cfg.cooldownMin * 60_000L) {
                st.armed = false;
                st.lastAlert = now;
                hits.add(new Hit(t.symbol, chg, t.last, t.volValue, thr, gname));
            }
        }
        tracked = tr;

        // bersihkan token yang sudah lama tidak terlihat
        Iterator<Map.Entry<String, State>> it = map.entrySet().iterator();
        while (it.hasNext()) if (it.next().getValue().lastSeen < now - winMs * 2) it.remove();

        Collections.sort(hits, (a, b) -> Double.compare(Math.abs(b.chg), Math.abs(a.chg)));
        return hits;
    }
}
