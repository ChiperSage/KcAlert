package com.example.kcalert;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Checklist token. Extra "g" >= 0: edit kelompok ke-g (nama, ambang, token). Tanpa extra: token pilihan. */
public class TokenPickerActivity extends Activity {
    private final List<String> all = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ArrayAdapter<String> adapter;
    private TextView tvCount;
    private EditText etSearch, etName, etPct;
    private int g = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        g = getIntent().getIntExtra("g", -1);
        Config cfg = Config.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        if (g >= 0) {
            if (g >= cfg.groups.size()) { finish(); return; }
            Config.Group gr = cfg.groups.get(g);
            setTitle("Edit kelompok");
            selected.addAll(gr.tokens);
            TextView l1 = new TextView(this);
            l1.setText("Nama kelompok");
            root.addView(l1);
            etName = new EditText(this);
            etName.setSingleLine(true);
            etName.setText(gr.name);
            root.addView(etName);
            TextView l2 = new TextView(this);
            l2.setText("Ambang kelompok (%)");
            root.addView(l2);
            etPct = new EditText(this);
            etPct.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etPct.setText(gr.pct == (long) gr.pct ? String.valueOf((long) gr.pct) : String.valueOf(gr.pct));
            root.addView(etPct);
        } else {
            setTitle("Pilih token");
            selected.addAll(cfg.tokens);
        }

        etSearch = new EditText(this);
        etSearch.setHint("Cari token, mis. BTC");
        etSearch.setSingleLine(true);
        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            public void onTextChanged(CharSequence s, int a, int c, int d) { }
            public void afterTextChanged(Editable e) { applyFilter(); }
        });
        root.addView(etSearch);

        tvCount = new TextView(this);
        root.addView(tvCount);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button bAll = new Button(this);
        bAll.setText("Pilih semua");
        bAll.setOnClickListener(v -> { selected.addAll(shown); refresh(); });
        Button bNone = new Button(this);
        bNone.setText("Hapus semua");
        bNone.setOnClickListener(v -> { selected.removeAll(shown); refresh(); });
        row.addView(bAll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(bNone, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        ListView lv = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, shown) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                ((CheckedTextView) v).setChecked(selected.contains(getItem(pos)));
                return v;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, v, pos, id) -> {
            String s = shown.get(pos);
            if (!selected.remove(s)) selected.add(s);
            refresh();
        });
        root.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button save = new Button(this);
        save.setText("Simpan");
        save.setOnClickListener(v -> save());
        root.addView(save);
        setContentView(root);

        updateCount();
        new Thread(() -> {
            try {
                List<KucoinApi.Tick> t = KucoinApi.fetch().ticks;
                Collections.sort(t, (x, y) -> Double.compare(y.volValue, x.volValue));
                List<String> syms = new ArrayList<>();
                for (KucoinApi.Tick k : t) syms.add(k.symbol);
                ui.post(() -> { all.clear(); all.addAll(syms); applyFilter(); });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Gagal memuat: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void save() {
        Config c = Config.load(this);
        if (g >= 0) {
            if (g < c.groups.size()) {
                Config.Group gr = c.groups.get(g);
                double v;
                try {
                    v = Double.parseDouble(etPct.getText().toString().trim());
                    if (v <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Ambang tidak valid", Toast.LENGTH_SHORT).show();
                    return;
                }
                String nm = etName.getText().toString().trim();
                if (!nm.isEmpty()) gr.name = nm;
                gr.pct = v;
                gr.tokens.clear();
                gr.tokens.addAll(selected);
                if (!selected.isEmpty() && gr.enabled) c.scope = 2; // ada kelompok aktif -> mode kelompok
            }
        } else {
            c.tokens.clear();
            c.tokens.addAll(selected);
            if (!selected.isEmpty()) c.scope = 1; // ada pilihan -> mode token pilihan
        }
        c.save(this);
        finish();
    }

    private void applyFilter() {
        String q = etSearch.getText().toString().trim().toUpperCase(Locale.US);
        shown.clear();
        for (String s : all) if (q.isEmpty() || s.contains(q)) shown.add(s);
        refresh();
    }

    private void refresh() {
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        tvCount.setText("Dipilih: " + selected.size() + "  |  Tampil: " + shown.size());
    }
}
