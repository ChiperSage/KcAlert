package com.example.kcalert;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Riwayat harga (agar tahan restart) dan riwayat alert (agar notifikasi yang terlewat tetap bisa dilihat). */
final class Store extends SQLiteOpenHelper {
    private static Store inst;

    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private Store(Context c) { super(c, "kc.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE samples(sym TEXT NOT NULL, t INTEGER NOT NULL, p REAL NOT NULL)");
        db.execSQL("CREATE INDEX idx_s ON samples(sym, t)");
        db.execSQL("CREATE INDEX idx_t ON samples(t)");
        db.execSQL("CREATE TABLE alerts(t INTEGER NOT NULL, sym TEXT NOT NULL, grp TEXT, chg REAL, price REAL, thr REAL)");
        db.execSQL("CREATE INDEX idx_a ON alerts(t)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { }

    synchronized void saveSamples(List<AlertEngine.Added> list) {
        if (list.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            SQLiteStatement st = db.compileStatement("INSERT INTO samples(sym,t,p) VALUES(?,?,?)");
            for (AlertEngine.Added a : list) {
                st.clearBindings();
                st.bindString(1, a.sym);
                st.bindLong(2, a.t);
                st.bindDouble(3, a.p);
                st.executeInsert();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized void pruneSamples(long before) {
        getWritableDatabase().delete("samples", "t < ?", new String[]{String.valueOf(before)});
    }

    synchronized Map<String, List<AlertEngine.Sample>> loadSamples() {
        Map<String, List<AlertEngine.Sample>> m = new HashMap<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT sym,t,p FROM samples ORDER BY sym,t", null);
        try {
            while (c.moveToNext()) {
                String s = c.getString(0);
                List<AlertEngine.Sample> l = m.get(s);
                if (l == null) { l = new ArrayList<>(); m.put(s, l); }
                l.add(new AlertEngine.Sample(c.getLong(1), c.getDouble(2)));
            }
        } finally {
            c.close();
        }
        return m;
    }

    synchronized void logAlerts(List<AlertEngine.Hit> hits, long t) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (AlertEngine.Hit h : hits) {
                ContentValues v = new ContentValues();
                v.put("t", t);
                v.put("sym", h.symbol);
                v.put("grp", h.group);
                v.put("chg", h.chg);
                v.put("price", h.price);
                v.put("thr", h.thr);
                db.insert("alerts", null, v);
            }
            db.execSQL("DELETE FROM alerts WHERE rowid NOT IN (SELECT rowid FROM alerts ORDER BY t DESC LIMIT 1000)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized Map<String, Long> lastAlerts() {
        Map<String, Long> m = new HashMap<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT sym, MAX(t) FROM alerts GROUP BY sym", null);
        try { while (c.moveToNext()) m.put(c.getString(0), c.getLong(1)); } finally { c.close(); }
        return m;
    }

    synchronized List<String> recentAlerts(int limit) {
        List<String> rows = new ArrayList<>();
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.US);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT t,sym,grp,chg,price FROM alerts ORDER BY t DESC LIMIT ?", new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                String g = c.isNull(2) ? "" : "[" + c.getString(2) + "] ";
                double p = c.getDouble(4);
                String price = p >= 1 ? String.format(Locale.US, "%.4f", p) : String.format(Locale.US, "%.8f", p);
                rows.add(f.format(new Date(c.getLong(0))) + "  " + g + c.getString(1) + "  "
                        + String.format(Locale.US, "%+.2f%%", c.getDouble(3)) + "  @" + price);
            }
        } finally {
            c.close();
        }
        return rows;
    }
}
