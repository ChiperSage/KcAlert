package com.example.kcalert;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class GroupsActivity extends Activity {
    private final List<Config.Group> data = new ArrayList<>();
    private ArrayAdapter<Config.Group> adapter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Kelompok & ambang");
        final Context ctx = this;
        final int pad = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(this);
        hint.setText("Centang = aktif. Ketuk untuk edit nama, ambang, dan token. Tahan untuk hapus.");
        root.addView(hint);

        Button add = new Button(this);
        add.setText("Tambah kelompok");
        add.setOnClickListener(v -> {
            Config c = Config.load(this);
            Config.Group g = new Config.Group();
            g.name = "Kelompok " + (c.groups.size() + 1);
            c.groups.add(g);
            c.save(this);
            startActivity(new Intent(this, TokenPickerActivity.class).putExtra("g", c.groups.size() - 1));
        });
        root.addView(add);

        ListView lv = new ListView(this);
        adapter = new ArrayAdapter<Config.Group>(this, 0, data) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                final Config.Group g = getItem(pos);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(pad, pad, pad, pad);
                final CheckBox cb = new CheckBox(ctx);
                cb.setChecked(g.enabled);
                cb.setFocusable(false);
                cb.setOnClickListener(v -> { g.enabled = cb.isChecked(); persist(); });
                TextView tv = new TextView(ctx);
                tv.setTextSize(16);
                tv.setText(g.name + "\nambang >= " + num(g.pct) + "%  |  " + g.tokens.size() + " token");
                row.addView(cb);
                row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, v, pos, id) ->
                startActivity(new Intent(this, TokenPickerActivity.class).putExtra("g", pos)));
        lv.setOnItemLongClickListener((parent, v, pos, id) -> {
            final Config.Group g = data.get(pos);
            new AlertDialog.Builder(this)
                    .setMessage("Hapus kelompok \"" + g.name + "\"?")
                    .setPositiveButton("Hapus", (d, w) -> { data.remove(g); persist(); adapter.notifyDataSetChanged(); })
                    .setNegativeButton("Batal", null)
                    .show();
            return true;
        });
        root.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        data.clear();
        data.addAll(Config.load(this).groups);
        adapter.notifyDataSetChanged();
    }

    private void persist() {
        Config c = Config.load(this);
        c.groups = new ArrayList<>(data);
        c.save(this);
    }

    private static String num(double d) { return d == (long) d ? String.valueOf((long) d) : String.valueOf(d); }
}
