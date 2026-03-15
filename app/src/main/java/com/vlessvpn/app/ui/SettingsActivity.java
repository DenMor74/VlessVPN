package com.vlessvpn.app.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.vlessvpn.app.R;
import com.vlessvpn.app.service.BackgroundMonitorService;
import com.vlessvpn.app.storage.ServerRepository;

public class SettingsActivity extends AppCompatActivity {

    private ServerRepository repository;
    private EditText  etUrls;
    private SeekBar   seekInterval;
    private TextView  tvIntervalValue;
    private SeekBar   seekTopCount;
    private TextView  tvTopCountValue;
    private Switch    switchScanOnStart;
    private Switch switchForceMobile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Настройки");
        }

        repository = new ServerRepository(this);
        initViews();
        loadCurrentSettings();
    }

    private void initViews() {
        etUrls           = findViewById(R.id.et_config_urls);
        seekInterval     = findViewById(R.id.seek_interval);
        tvIntervalValue  = findViewById(R.id.tv_interval_value);
        seekTopCount     = findViewById(R.id.seek_top_count);
        tvTopCountValue  = findViewById(R.id.tv_top_count_value);
        switchScanOnStart = findViewById(R.id.switch_scan_on_start);
        Button btnSave       = findViewById(R.id.btn_save);
        Button btnRefreshNow = findViewById(R.id.btn_refresh_now);
        switchForceMobile = findViewById(R.id.switch_force_mobile);

        // SeekBar интервал: 1-24 часов
        seekInterval.setMax(23);
        seekInterval.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int h = p + 1;
                tvIntervalValue.setText("Каждые " + h + " " + hoursLabel(h));
            }
        });

        // SeekBar количество серверов: 1-30
        seekTopCount.setMax(29); // 0..29 → 1..30
        seekTopCount.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int cnt = p + 1;
                tvTopCountValue.setText("Топ серверов: " + cnt);
            }
        });

        btnSave.setOnClickListener(v -> saveSettings());

        btnRefreshNow.setOnClickListener(v -> {
            repository.resetUpdateTime();
            repository.resetAllTestTimes();
            BackgroundMonitorService.runImmediately(this);
            Toast.makeText(this, "Обновление запущено...", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadCurrentSettings() {
        // URL-ы
        String[] urls = repository.getConfigUrls();
        etUrls.setText(String.join("\n", urls));

        // Интервал обновления
        int hours = repository.getUpdateIntervalHours();
        seekInterval.setProgress(hours - 1);
        tvIntervalValue.setText("Каждые " + hours + " " + hoursLabel(hours));

        // Количество серверов в топе
        int topCount = repository.getTopCount();
        seekTopCount.setProgress(topCount - 1);
        tvTopCountValue.setText("Топ серверов: " + topCount);

        // Сканировать при запуске
        switchScanOnStart.setChecked(repository.isScanOnStart());

        switchForceMobile.setChecked(repository.isForceMobileTests());
    }

    private void saveSettings() {
        // URL-ы
        String urlsText = etUrls.getText().toString().trim();
        if (urlsText.isEmpty()) urlsText = ServerRepository.DEFAULT_CONFIG_URL;
        repository.saveConfigUrls(urlsText);

        // Интервал
        int newInterval = seekInterval.getProgress() + 1;
        repository.saveUpdateInterval(newInterval);

        // Количество серверов
        int newTopCount = seekTopCount.getProgress() + 1;
        repository.saveTopCount(newTopCount);

        // Сканирование при запуске
        repository.saveScanOnStart(switchScanOnStart.isChecked());

        // Перепланируем WorkManager
        BackgroundMonitorService.schedule(this, newInterval);

        repository.saveForceMobileTests(switchForceMobile.isChecked());

        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
        finish();
    }

    private String hoursLabel(int hours) {
        if (hours % 10 == 1 && hours % 100 != 11) return "час";
        if (hours % 10 >= 2 && hours % 10 <= 4 && (hours % 100 < 10 || hours % 100 >= 20)) return "часа";
        return "часов";
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }

    // Простой адаптер SeekBar.OnSeekBarChangeListener
    private static abstract class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}
