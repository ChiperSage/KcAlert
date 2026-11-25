package com.example.kcalert;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

final class KucoinApi {
    private static final String URL_ALL = "https://api.kucoin.com/api/v1/market/allTickers";

    static final class Tick {
        final String symbol; final double last; final double volValue;
        Tick(String s, double l, double v) { symbol = s; last = l; volValue = v; }
    }

    static final class Result {
        final List<Tick> ticks; final long serverTime;
        Result(List<Tick> t, long s) { ticks = t; serverTime = s; }
    }

    static Result fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_ALL).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "kc-alert/2.0");
        try {
            int code = c.getResponseCode();
            if (code == 429 || code == 418) throw new IOException("Dibatasi KuCoin (HTTP " + code + ")");
            if (code != 200) throw new IOException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            JSONObject root = new JSONObject(sb.toString());
            if (!"200000".equals(root.optString("code"))) throw new IOException("API " + root.optString("code"));
            JSONObject data = root.getJSONObject("data");
            JSONArray arr = data.getJSONArray("ticker");
            List<Tick> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String sym = o.optString("symbol", "");
                if (!sym.endsWith("-USDT")) continue;
                try {
                    double last = Double.parseDouble(o.optString("last", ""));
                    double vol = Double.parseDouble(o.optString("volValue", ""));
                    out.add(new Tick(sym, last, vol));
                } catch (NumberFormatException ignored) { }
            }
            // respons kosong/terpotong dianggap error, bukan "tidak ada token"
            if (out.size() < 50) throw new IOException("Data ticker tidak lengkap (" + out.size() + ")");
            return new Result(out, data.optLong("time", 0));
        } finally {
            c.disconnect();
        }
    }
}
