package com.example.kcalert;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Riwayat alert");
        List<String> rows;
        try { rows = Store.get(this).recentAlerts(200); } catch (Throwable e) { rows = new ArrayList<>(); }
        if (rows.isEmpty()) rows.add("Belum ada alert.");
        ListView lv = new ListView(this);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows));
        setContentView(lv);
    }
}
