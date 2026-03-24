package com.vlessvpn.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
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
import com.vlessvpn.app.util.AppBlacklistManager;

public class SettingsActivity extends AppCompatActivity {

    private ServerRepository repository;
    private EditText  etUrls;
    private SeekBar   seekInterval;
    private TextView  tvIntervalValue;
    private SeekBar   seekTopCount;
    private TextView  tvTopCountValue;
    private Switch    switchScanOnStart;
    private Switch    switchForceMobile;
    private Switch    switchAutoConnectWifi;

    private Switch switchDisableNightCheck;
    private TextView tvNightCheckInfo;

    private Switch switchAutoConnectAfterScan;
    private Switch switchDeepCheck;

    // ════════════════════════════════════════════════════════════════
    // ← НОВЫЕ: Для интервала сканирования
    // ════════════════════════════════════════════════════════════════
    private SeekBar   seekScanInterval;
    private TextView  tvScanIntervalValue;

    private void updateAodStatus() {
        android.view.accessibility.AccessibilityManager am =
            (android.view.accessibility.AccessibilityManager)
                getSystemService(ACCESSIBILITY_SERVICE);
        boolean enabled = false;
        if (am != null) {
            for (android.accessibilityservice.AccessibilityServiceInfo info :
                    am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info.getId().contains("AodOverlayService")) {
                    enabled = true;
                    break;
                }
            }
        }
        android.util.Log.i("AodOverlay", "Accessibility enabled: " + enabled);
        // Находим TextView статуса если есть
        android.widget.TextView tvAod = findViewById(R.id.tv_aod_status);
        if (tvAod != null) {
            tvAod.setText(enabled ? "✓ AOD активен" : "✗ AOD выключен — включите в Настройки → Спец. возможности → VlessVPN AOD");
            tvAod.setTextColor(enabled ? 0xFF4CAF50 : 0xFFFF9800);
        }
    }

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


    @Override
    protected void onResume() {
        super.onResume();
        // Показываем статус AOD сервиса
        updateAodStatus();

        // ... остальной код ...

        // ← НОВОЕ: Обновить счётчик чёрного списка
        TextView tvBlacklistCount = findViewById(R.id.tv_blacklist_count);
        if (tvBlacklistCount != null) {
            AppBlacklistManager blm = new AppBlacklistManager(this);
            int count = blm.getBlacklistCount();
            tvBlacklistCount.setText("Выбрано приложений: " + count);
        }
    }

    private void initViews() {
        etUrls              = findViewById(R.id.et_config_urls);
        seekInterval        = findViewById(R.id.seek_interval);
        tvIntervalValue     = findViewById(R.id.tv_interval_value);
        seekTopCount        = findViewById(R.id.seek_top_count);
        tvTopCountValue     = findViewById(R.id.tv_top_count_value);
        switchScanOnStart   = findViewById(R.id.switch_scan_on_start);
        switchForceMobile   = findViewById(R.id.switch_force_mobile);
        switchAutoConnectWifi = findViewById(R.id.switch_auto_connect_wifi);

        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: SeekBar для интервала сканирования (10мин - 24ч)
        // ════════════════════════════════════════════════════════════════
        seekScanInterval = findViewById(R.id.seek_scan_interval);
        tvScanIntervalValue = findViewById(R.id.tv_scan_interval_value);

        if (seekScanInterval != null && tvScanIntervalValue != null) {
            seekScanInterval.setMax(5);  // 0..143 → 10..1440 минут (шаг 10 мин)
            seekScanInterval.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean u) {
                    int minutes = (p + 1) * 10;  // 10, 20, 30... 1440
                    tvScanIntervalValue.setText("Проверять лист: каждые " + formatInterval(minutes));
                }
            });
        }

        // SeekBar интервал: 1-24 часов
        seekInterval.setMax(23);
        seekInterval.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int h = p + 1;
                tvIntervalValue.setText("Скачивать листы: каждые " + h + " " + hoursLabel(h));
            }
        });

        // SeekBar количество серверов: 1-30
        seekTopCount.setMax(29);
        seekTopCount.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int cnt = p + 1;
                tvTopCountValue.setText("Топ серверов: " + cnt);
            }
        });

        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> saveSettings());

        Button btnAppBlacklist = findViewById(R.id.btn_app_blacklist);
        TextView tvBlacklistCount = findViewById(R.id.tv_blacklist_count);

        // Показать количество приложений в чёрном списке
        AppBlacklistManager blm = new AppBlacklistManager(this);
        int count = blm.getBlacklistCount();
        tvBlacklistCount.setText("Выбрано приложений: " + count);

        btnAppBlacklist.setOnClickListener(v -> {
            Intent intent = new Intent(SettingsActivity.this, AppBlacklistActivity.class);
            startActivity(intent);
        });

        // ← НОВОЕ: Переключатель ночного режима
        switchDisableNightCheck = findViewById(R.id.switch_disable_night_check);
        tvNightCheckInfo = findViewById(R.id.tv_night_check_info);

        switchDisableNightCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            tvNightCheckInfo.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        switchAutoConnectWifi = findViewById(R.id.switch_auto_connect_wifi);
        switchAutoConnectAfterScan = findViewById(R.id.switch_auto_connect_after_scan);
        switchDeepCheck = findViewById(R.id.switch_deep_check);
    }

    private void loadCurrentSettings() {
        // URL-ы
        String[] urls = repository.getConfigUrls();
        etUrls.setText(String.join("\n", urls));

        // Интервал обновления
        int hours = repository.getUpdateIntervalHours();
        seekInterval.setProgress(hours - 1);
        tvIntervalValue.setText("Скачивать листы: каждые " + hours + " " + hoursLabel(hours));

        // Количество серверов в топе
        int topCount = repository.getTopCount();
        seekTopCount.setProgress(topCount - 1);
        tvTopCountValue.setText("Выводить на экран серверов: " + topCount);

        // Сканировать при запуске
        switchScanOnStart.setChecked(repository.isScanOnStart());

        // Тест через LTE
        switchForceMobile.setChecked(repository.isForceMobileTests());

        // Авто-подключение при потере WiFi
        switchAutoConnectWifi.setChecked(repository.isAutoConnectOnWifiDisconnect());

        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: Интервал сканирования
        // ════════════════════════════════════════════════════════════════
        if (seekScanInterval != null && tvScanIntervalValue != null) {
            int scanMinutes = repository.getScanIntervalMinutes();
            seekScanInterval.setProgress((scanMinutes / 10) - 1);
            tvScanIntervalValue.setText("Проверять лист: каждые " + formatInterval(scanMinutes));
        }
        // ← НОВОЕ: Ночной режим
        switchDisableNightCheck.setChecked(repository.isDisableNightCheck());
        tvNightCheckInfo.setVisibility(repository.isDisableNightCheck() ? View.VISIBLE : View.GONE);
        switchAutoConnectAfterScan.setChecked(repository.isAutoConnectAfterScan());
        switchDeepCheck.setChecked(repository.isDeepCheckOnConnect());
    }

    private void saveSettings() {
        // URL-ы
        String urlsText = etUrls.getText().toString().trim();
        if (urlsText.isEmpty()) urlsText = ServerRepository.DEFAULT_CONFIG_URL;
        repository.saveConfigUrls(urlsText);

        // Интервал обновления
        int newInterval = seekInterval.getProgress() + 1;
        repository.saveUpdateInterval(newInterval);

        // Количество серверов
        int newTopCount = seekTopCount.getProgress() + 1;
        repository.saveTopCount(newTopCount);

        // Сканирование при запуске
        repository.saveScanOnStart(switchScanOnStart.isChecked());

        // Тест через LTE
        repository.saveForceMobileTests(switchForceMobile.isChecked());

        // Авто-подключение при потере WiFi
        repository.saveAutoConnectOnWifiDisconnect(switchAutoConnectWifi.isChecked());

        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: Сохранить интервал сканирования
        // ════════════════════════════════════════════════════════════════
        int newScanInterval = 30;  // по умолчанию
        if (seekScanInterval != null) {
            newScanInterval = (seekScanInterval.getProgress() + 1) * 10;
            repository.saveScanIntervalMinutes(newScanInterval);
        }
        // ← НОВОЕ: Сохранить ночной режим
        repository.saveDisableNightCheck(switchDisableNightCheck.isChecked());

        // ════════════════════════════════════════════════════════════════
        // ← ИСПРАВЛЕНО: Перепланируем ОБА задания (раздельно)
        // ════════════════════════════════════════════════════════════════
        BackgroundMonitorService.scheduleDownload(this, newInterval);
        BackgroundMonitorService.scheduleScan(this, newScanInterval);

        // Уведомление пользователя
        if (switchAutoConnectWifi.isChecked()) {
            Toast.makeText(this, "Настройки сохранены\n✅ Авто-подключение: ВКЛ", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
        }
        repository.saveAutoConnectOnWifiDisconnect(switchAutoConnectWifi.isChecked());
        repository.saveAutoConnectAfterScan(switchAutoConnectAfterScan.isChecked());
        repository.saveDeepCheckOnConnect(switchDeepCheck.isChecked());
        finish();
    }

    private String formatInterval(int minutes) {
        if (minutes < 60) return minutes + " мин";
        else if (minutes < 1440) return (minutes / 60) + " ч";
        else return (minutes / 1440) + " дн";
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