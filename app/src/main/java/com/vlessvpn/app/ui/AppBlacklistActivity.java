package com.vlessvpn.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vlessvpn.app.R;
import com.vlessvpn.app.model.AppInfo;
import com.vlessvpn.app.util.AppBlacklistManager;
import com.vlessvpn.app.util.FileLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * AppBlacklistActivity — экран выбора приложений для чёрного списка.
 */
public class AppBlacklistActivity extends AppCompatActivity {

    private static final String TAG = "AppBlacklistActivity";

    private RecyclerView recyclerView;
    private AppBlacklistAdapter adapter;
    private AppBlacklistManager blacklistManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_blacklist);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Приложения вне VPN");
        }

        blacklistManager = new AppBlacklistManager(this);
        initViews();
        loadApps();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recycler_apps);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    private void loadApps() {
        new Thread(() -> {
            List<AppInfo> apps = blacklistManager.getAllInstalledApps(this);
            runOnUiThread(() -> {
                adapter = new AppBlacklistAdapter(apps);
                recyclerView.setAdapter(adapter);
            });
        }).start();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    // Адаптер для списка приложений
    // ════════════════════════════════════════════════════════════════

    private class AppBlacklistAdapter extends RecyclerView.Adapter<AppBlacklistAdapter.ViewHolder> {

        private final List<AppInfo> apps;

        public AppBlacklistAdapter(List<AppInfo> apps) {
            this.apps = apps;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app_blacklist, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int position) {
            AppInfo app = apps.get(position);

            h.appIcon.setImageDrawable(app.icon);
            h.appName.setText(app.appName);
            h.appPackage.setText(app.packageName);

            // ════════════════════════════════════════════════════════════════
            // ← ИСПРАВЛЕНО: Устанавливаем чекбокс БЕЗ триггера
            // ════════════════════════════════════════════════════════════════
            h.checkbox.setOnCheckedChangeListener(null);  // ← Сбрасываем listener
            h.checkbox.setChecked(app.isSelected);

            // ← Устанавливаем listener ПОСЛЕ установки значения
            h.checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                app.isSelected = isChecked;
                if (isChecked) {
                    blacklistManager.addToBlacklist(app.packageName);
                    FileLogger.d(TAG, "Добавлено: " + app.packageName);
                } else {
                    blacklistManager.removeFromBlacklist(app.packageName);
                    FileLogger.d(TAG, "Удалено: " + app.packageName);
                }
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView appIcon;
            TextView appName;
            TextView appPackage;
            CheckBox checkbox;

            ViewHolder(View v) {
                super(v);
                appIcon = v.findViewById(R.id.app_icon);
                appName = v.findViewById(R.id.app_name);
                appPackage = v.findViewById(R.id.app_package);
                checkbox = v.findViewById(R.id.checkbox);
            }
        }
    }
}