package com.example.kcalert;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText etWin, etPct, etVol, etCd, etInt;
    private Spinner spDir, spScope;
    private Button btnPick, btnGroups;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Config c = Config.load(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        etWin = field(root, "Jendela waktu (menit)", String.valueOf(c.windowMin), false);
        etPct = field(root, "Ambang global (%) - mode Semua & Token pilihan", num(c.pct), true);
        label(root, "Arah");
        spDir = new Spinner(this);
        spDir.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Naik & turun", "Naik saja", "Turun saja"}));
        spDir.setSelection(c.dir);
        root.addView(spDir);

        label(root, "Cakupan token (berlaku langsung)");
        spScope = new Spinner(this);
        spScope.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Semua token (filter volume)", "Token pilihan (1 ambang)", "Kelompok (ambang per kelompok)"}));
        spScope.setSelection(c.scope);
        spScope.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                Config cc = Config.load(MainActivity.this);
                if (cc.scope != pos) { cc.scope = pos; cc.save(MainActivity.this); }
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        root.addView(spScope);
        btnPick = new Button(this);
        btnPick.setOnClickListener(v -> startActivity(new Intent(this, TokenPickerActivity.class)));
        root.addView(btnPick);
        btnGroups = new Button(this);
        btnGroups.setOnClickListener(v -> startActivity(new Intent(this, GroupsActivity.class)));
        root.addView(btnGroups);

        etVol = field(root, "Min volume 24h (USDT, mode Semua)", num(c.minVol), true);
        etCd = field(root, "Cooldown per token (menit)", String.valueOf(c.cooldownMin), false);
        etInt = field(root, "Interval cek (detik, min 10)", String.valueOf(c.intervalSec), false);

        Button start = new Button(this);
        start.setText("Mulai / terapkan");
        start.setOnClickListener(v -> start());
        Button stop = new Button(this);
        stop.setText("Berhenti");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, AlertService.class));
            Toast.makeText(this, "Dihentikan", Toast.LENGTH_SHORT).show();
        });
        root.addView(start);
        root.addView(stop);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        Config c = Config.load(this);
        int active = 0;
        for (Config.Group g : c.groups) if (g.enabled && !g.tokens.isEmpty()) active++;
        btnPick.setText("Pilih token (" + c.tokens.size() + " dipilih)");
        btnGroups.setText("Kelompok & ambang (" + active + " aktif dari " + c.groups.size() + ")");
        spScope.setSelection(c.scope); // editor token/kelompok bisa mengubah mode
    }

    private void start() {
        Config c = Config.load(this);
        try {
            c.windowMin = clamp(Integer.parseInt(etWin.getText().toString().trim()), 1, 1440);
            c.pct = Double.parseDouble(etPct.getText().toString().trim());
            if (c.pct <= 0) throw new NumberFormatException();
            c.dir = spDir.getSelectedItemPosition();
            c.minVol = Double.parseDouble(etVol.getText().toString().trim());
            c.cooldownMin = clamp(Integer.parseInt(etCd.getText().toString().trim()), 1, 1440);
            c.intervalSec = clamp(Integer.parseInt(etInt.getText().toString().trim()), 10, 600);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Isi angka yang valid", Toast.LENGTH_SHORT).show();
            return;
        }
        if (c.scope == 1 && c.tokens.isEmpty()) {
            Toast.makeText(this, "Pilih minimal satu token, atau ganti cakupan", Toast.LENGTH_LONG).show();
            return;
        }
        if (c.scope == 2) {
            boolean ok = false;
            for (Config.Group g : c.groups) if (g.enabled && !g.tokens.isEmpty()) ok = true;
            if (!ok) {
                Toast.makeText(this, "Belum ada kelompok aktif yang berisi token", Toast.LENGTH_LONG).show();
                return;
            }
        }
        c.save(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Intent i = new Intent(this, AlertService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "Berjalan. Alert muncul setelah riwayat cukup (sepanjang jendela).", Toast.LENGTH_LONG).show();
    }

    private void label(LinearLayout root, String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setPadding(0, 24, 0, 0);
        root.addView(tv);
    }

    private EditText field(LinearLayout root, String lbl, String val, boolean decimal) {
        label(root, lbl);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setText(val);
        root.addView(e);
        return e;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String num(double d) { return d == (long) d ? String.valueOf((long) d) : String.valueOf(d); }
}
