package com.example.kcalert;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Config {
    static final class Group {
        String name = "Kelompok";
        double pct = 5;
        boolean enabled = true;
        Set<String> tokens = new HashSet<>();
    }

    int windowMin = 15;        // jendela waktu perubahan (menit, maks 240)
    double pct = 5;            // ambang global (mode Semua & Token pilihan)
    int dir = 0;               // 0 naik&turun, 1 naik, 2 turun
    double minVol = 500000;    // min volume 24h (USDT), hanya mode Semua
    int cooldownMin = 30;      // jeda antar alert per token
    int intervalSec = 30;      // interval polling
    int scope = 0;             // 0 semua, 1 token pilihan, 2 kelompok
    double maxSpread = 1.0;    // spread bid-ask maks (%), 0 = nonaktif
    boolean adaptive = false;  // ambang naik untuk token volatil
    double k = 3;              // pengali volatilitas (mode adaptif)
    boolean wake = false;      // wake lock CPU
    Set<String> tokens = new HashSet<>();
    List<Group> groups = new ArrayList<>();

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("kc", Context.MODE_PRIVATE); }

    static Config load(Context ctx) {
        SharedPreferences sp = sp(ctx);
        Config c = new Config();
        try {
            c.windowMin = sp.getInt("win", c.windowMin);
            c.pct = Double.parseDouble(sp.getString("pct", String.valueOf(c.pct)));
            c.dir = sp.getInt("dir", c.dir);
            c.minVol = Double.parseDouble(sp.getString("vol", String.valueOf(c.minVol)));
            c.cooldownMin = sp.getInt("cd", c.cooldownMin);
            c.intervalSec = sp.getInt("int", c.intervalSec);
            c.scope = sp.contains("scope") ? sp.getInt("scope", 0) : (sp.getBoolean("all", true) ? 0 : 1);
            c.maxSpread = Double.parseDouble(sp.getString("spr", String.valueOf(c.maxSpread)));
            c.adaptive = sp.getBoolean("adp", false);
            c.k = Double.parseDouble(sp.getString("k", String.valueOf(c.k)));
            c.wake = sp.getBoolean("wake", false);
            String t = sp.getString("tok", "");
            if (!t.isEmpty()) c.tokens.addAll(Arrays.asList(t.split(",")));
            JSONArray a = new JSONArray(sp.getString("groups", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Group g = new Group();
                g.name = o.optString("n", "Kelompok");
                g.pct = o.optDouble("p", 5);
                g.enabled = o.optBoolean("e", true);
                String gt = o.optString("t", "");
                if (!gt.isEmpty()) g.tokens.addAll(Arrays.asList(gt.split(",")));
                c.groups.add(g);
            }
        } catch (Exception ignored) { }
        if (c.windowMin > 240) c.windowMin = 240;
        if (c.windowMin < 1) c.windowMin = 1;
        return c;
    }

    void save(Context ctx) {
        JSONArray a = new JSONArray();
        try {
            for (Group g : groups) {
                JSONObject o = new JSONObject();
                o.put("n", g.name);
                o.put("p", g.pct);
                o.put("e", g.enabled);
                o.put("t", TextUtils.join(",", g.tokens));
                a.put(o);
            }
        } catch (Exception ignored) { }
        sp(ctx).edit()
                .putInt("win", windowMin).putString("pct", String.valueOf(pct))
                .putInt("dir", dir).putString("vol", String.valueOf(minVol))
                .putInt("cd", cooldownMin).putInt("int", intervalSec)
                .putInt("scope", scope).putString("spr", String.valueOf(maxSpread))
                .putBoolean("adp", adaptive).putString("k", String.valueOf(k))
                .putBoolean("wake", wake)
                .putString("tok", TextUtils.join(",", tokens))
                .putString("groups", a.toString()).apply();
    }

    // ---- status runtime (dipakai UI, service, dan pengawas) ----
    static void setRunning(Context c, boolean v) { sp(c).edit().putBoolean("run", v).apply(); }
    static boolean running(Context c) { return sp(c).getBoolean("run", false); }
    static void beat(Context c) { sp(c).edit().putLong("hb", System.currentTimeMillis()).apply(); }
    static long lastBeat(Context c) { return sp(c).getLong("hb", 0); }
    static void setStatus(Context c, String s) { sp(c).edit().putString("stat", s).apply(); }
    static String status(Context c) { return sp(c).getString("stat", ""); }
    /** Batas detak terakhir sebelum layanan dianggap tidak responsif. */
    static long staleLimitMs(Config c) { return Math.max(300_000L, c.intervalSec * 6000L); }
}
