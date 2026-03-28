package com.vlessvpn.app.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.gson.Gson;
import com.vlessvpn.app.R;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.network.WifiMonitor;
import com.vlessvpn.app.service.BackgroundMonitorService;
import com.vlessvpn.app.service.UpdateDownloadService;
import com.vlessvpn.app.service.VpnController;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;
import com.vlessvpn.app.util.UpdateChecker;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private MainViewModel viewModel;
    private ServerAdapter serverAdapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // UI
    private TextView tvStatus;
    private TextView tvConnectedServer;
    private ImageButton btnDisconnect;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private View tvEmptyState;
    private TextView tvLastStatus;
    private View panelDeepCheck;
    private View panelSpeedTest;
    private TextView tvSpeedTest;
    private ImageButton btnSpeedTest;
    private ImageButton btnDeepCheckRefresh;
    private TextView tvTraffic;
    private TextView tvAutoConnectStatus;
    private TextView tvStatusMode;
    private TextView tvServerCounts;
    private TextView tvLastUpdate;
    private TextView tvLastScan;

    private VlessServer pendingServer = null;
    private boolean receiverRegistered = false;
    private UpdateChecker updateChecker;

    // Activity Result для запроса VPN-разрешения
    private final ActivityResultLauncher<Intent> vpnPermLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && pendingServer != null) {
                    doStartVpn(pendingServer);
                    pendingServer = null;
                } else {
                    Toast.makeText(this, "Разрешение VPN отклонено", Toast.LENGTH_SHORT).show();
                    pendingServer = null;
                }
            });

    // Broadcast Receivers
    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            refreshStatus();
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();

            if (StatusBus.ACTION_STATUS_CHANGED.equals(action)) {
                String message = intent.getStringExtra(StatusBus.EXTRA_MESSAGE);
                boolean isRunning = intent.getBooleanExtra(StatusBus.EXTRA_IS_RUNNING, false);
                int total = intent.getIntExtra(StatusBus.EXTRA_TOTAL, 0);
                int ok = intent.getIntExtra(StatusBus.EXTRA_OK, 0);
                int fail = intent.getIntExtra(StatusBus.EXTRA_FAIL, 0);

                if (message != null && !message.isEmpty()) {
                    mainHandler.post(() -> {
                        if (isRunning) {
                            tvStatusMode.setText("🔍 " + message);
                        } else {
                            tvStatusMode.setText("✅ " + message);
                        }

                        if (total > 0) {
                            tvServerCounts.setText("📊 Всего: " + total + " | ✓ Рабочих: " + ok + " | ✗ Нет связи: " + fail);
                        }
                    });
                }
            }

            if (StatusBus.ACTION_SERVER_EVENT.equals(action)) {
                String serverId = intent.getStringExtra(StatusBus.EXTRA_SERVER_ID);
                String host = intent.getStringExtra(StatusBus.EXTRA_HOST);
                String status = intent.getStringExtra(StatusBus.EXTRA_STATUS);
                String detail = intent.getStringExtra(StatusBus.EXTRA_DETAIL);

                ServerAdapter.TestStatus st;
                switch (status) {
                    case "pinging": st = ServerAdapter.TestStatus.PINGING; break;
                    case "testing": st = ServerAdapter.TestStatus.TESTING; break;
                    case "ok": st = ServerAdapter.TestStatus.OK; break;
                    case "fail": st = ServerAdapter.TestStatus.FAIL; break;
                    default: st = ServerAdapter.TestStatus.IDLE;
                }

                final String sid = serverId != null ? serverId : host;
                final String det = detail;

                mainHandler.post(() -> {
                    if (serverAdapter != null) {
                        serverAdapter.updateServerStatus(sid, st, det);
                    }
                });
            }
        }
    };

    // ================================================
    // Lifecycle
    // ================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.i(TAG, "=== onCreate ===");

        setContentView(R.layout.activity_main);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        initViews();
        setupRecyclerView();
        observeData();

        updateAutoConnectStatus();
        updateInfoPanel();

        WifiMonitor.startMonitoring(this);
        checkUpdateIfNeeded();
        setCustomActionBarTitle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAutoConnectStatus();
        updateInfoPanel();
        refreshStatus();

        // VPN Receiver
        IntentFilter vpnFilter = new IntentFilter("com.vlessvpn.VPN_STATUS_CHANGED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnReceiver, vpnFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vpnReceiver, vpnFilter);
        }
        receiverRegistered = true;

        // StatusBus Receiver
        IntentFilter statusFilter = new IntentFilter(StatusBus.ACTION_STATUS_CHANGED);
        statusFilter.addAction(StatusBus.ACTION_SERVER_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, statusFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(statusReceiver, statusFilter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            try { unregisterReceiver(vpnReceiver); } catch (Exception ignored) {}
            try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    // ================================================
    // Инициализация UI
    // ================================================

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvConnectedServer = findViewById(R.id.tv_connected_server);
        btnDisconnect = findViewById(R.id.btn_disconnect);
        recyclerView = findViewById(R.id.recycler_servers);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        tvEmptyState = findViewById(R.id.tv_empty_state);

        tvLastStatus = findViewById(R.id.tv_last_status);
        panelDeepCheck = findViewById(R.id.panel_deep_check);
        panelSpeedTest = findViewById(R.id.panel_speed_test);
        tvSpeedTest = findViewById(R.id.tv_speed_test);
        btnSpeedTest = findViewById(R.id.btn_speed_test);
        btnDeepCheckRefresh = findViewById(R.id.btn_deep_check_refresh);

        tvTraffic = findViewById(R.id.tv_traffic);
        tvAutoConnectStatus = findViewById(R.id.tv_auto_connect_status);
        tvStatusMode = findViewById(R.id.tv_status_mode);
        tvServerCounts = findViewById(R.id.tv_server_counts);
        tvLastUpdate = findViewById(R.id.tv_last_update);
        tvLastScan = findViewById(R.id.tv_last_scan);

        btnDisconnect.setOnClickListener(v -> VpnController.getInstance(this).handleDisconnectButton());

        // Speed Test
        if (btnSpeedTest != null) {
            btnSpeedTest.setOnClickListener(v -> {
                if (VpnTunnelService.isRunning) {
                    btnSpeedTest.setImageResource(R.drawable.ic_hourglass);
                    btnSpeedTest.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
                    if (tvSpeedTest != null) tvSpeedTest.setText("Идёт тест скорости...");
                    VpnTunnelService svc = VpnTunnelService.getInstance();
                    if (svc != null) svc.runSpeedTest();
                } else {
                    Toast.makeText(this, "Подключите VPN для теста скорости", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Deep Check
        if (btnDeepCheckRefresh != null) {
            btnDeepCheckRefresh.setOnClickListener(v -> {
                if (VpnTunnelService.isRunning) {
                    btnDeepCheckRefresh.setImageResource(R.drawable.ic_hourglass);
                    btnDeepCheckRefresh.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
                    if (tvLastStatus != null) tvLastStatus.setText("Определяем IP...");
                    VpnTunnelService svc = VpnTunnelService.getInstance();
                    if (svc != null) svc.runDeepCheck();
                }
            });
        }

        swipeRefresh.setOnRefreshListener(() -> {
            if (VpnTunnelService.isRunning) {
                Toast.makeText(this, "Отключаем VPN для проверки...", Toast.LENGTH_SHORT).show();
                VpnController.getInstance(this).disconnect(true);
                mainHandler.postDelayed(() -> {
                    viewModel.forceRefreshServers();
                    swipeRefresh.setRefreshing(false);
                }, 2500);
            } else {
                viewModel.forceRefreshServers();
                mainHandler.postDelayed(() -> swipeRefresh.setRefreshing(false), 3000);
            }
        });

        swipeRefresh.setEnabled(false); // как было у тебя
    }

    private void setupRecyclerView() {
        serverAdapter = new ServerAdapter(this::onConnectClicked);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(serverAdapter);
    }

    // ================================================
    // Наблюдатели
    // ================================================

    private void observeData() {
        viewModel.getAllServers().observe(this, servers -> {
            if (servers != null) {
                serverAdapter.setServers(servers);
                boolean empty = servers.isEmpty();
                tvEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                updateServerCounts(servers);
            }
        });

        viewModel.getIsConnected().observe(this, this::renderConnectionState);

        viewModel.getConnectedServer().observe(this, server -> {
            if (server != null) {
                tvConnectedServer.setText(server.remark.isEmpty() ? server.host : server.remark);
                serverAdapter.setConnectedServerId(server.id);
                // Сбрасываем панели при каждом новом сервере
                resetSpeedAndIpPanels();
            } else {
                tvConnectedServer.setText("");
                serverAdapter.setConnectedServerId(null);
            }
        });

        viewModel.getLastStatusMessage().observe(this, msg -> {
            if (msg == null || msg.isEmpty()) return;

            if (msg.contains("↑") && msg.contains("↓")) { // трафик
                if (VpnTunnelService.isRunning && tvTraffic != null) {
                    tvTraffic.setText(msg);
                }
            } else if (msg.startsWith("⏱")) { // скорость
                if (tvSpeedTest != null) {
                    tvSpeedTest.setText(msg);
                    boolean ok = msg.contains("✓");
                    tvSpeedTest.setTextColor(ok ? 0xFF4CAF50 : 0xFFFF5252);
                    if (btnSpeedTest != null) {
                        btnSpeedTest.setImageResource(R.drawable.ic_play);
                        btnSpeedTest.setImageTintList(android.content.res.ColorStateList.valueOf(ok ? 0xFF4CAF50 : 0xFFFF5252));
                    }
                }
            } else if (tvStatusMode != null) {
                tvStatusMode.setText(VpnTunnelService.isRunning ? msg : "🔍 " + msg);
            }
        });

        viewModel.getLastIpResult().observe(this, ip -> {
            if (tvLastStatus == null) return;

            if (ip == null || ip.isEmpty()) {
                if (VpnTunnelService.isRunning) {
                    tvLastStatus.setText("Определяем IP...");
                    tvLastStatus.setTextColor(0xFFFFFFFF);
                }
                return;
            }

            tvLastStatus.setText(ip);
            boolean ok = ip.contains("✓");
            tvLastStatus.setTextColor(ok ? 0xFF4CAF50 : 0xFFFF5252);

            if (btnDeepCheckRefresh != null) {
                btnDeepCheckRefresh.setImageResource(R.drawable.ic_refresh);
                btnDeepCheckRefresh.setImageTintList(android.content.res.ColorStateList.valueOf(ok ? 0xFF4CAF50 : 0xFFFF5252));
            }
        });
    }

    // ================================================
    // Вспомогательные методы
    // ================================================

    private void updateServerCounts(List<VlessServer> displayedServers) {
        new Thread(() -> {
            ServerRepository repo = new ServerRepository(this);
            List<VlessServer> all = repo.getAllServersSync();
            int total = all.size();
            int working = (int) all.stream().filter(s -> s.trafficOk).count();
            int failed = total - working;

            mainHandler.post(() -> {
                if (tvServerCounts != null) {
                    tvServerCounts.setText("📊 Всего: " + total + " | ✓ Рабочих: " + working + " | ✗ Нет связи: " + failed);
                }
            });
        }).start();
    }

    private void updateInfoPanel() {
        updateLastUpdateTime();
        updateLastScanTime();
    }

    private void updateLastUpdateTime() {
        if (tvLastUpdate == null) return;
        ServerRepository repo = new ServerRepository(this);
        long ts = repo.getLastUpdateTimestamp();
        String text = (ts == 0) ? "📥 Обновлено: не обновлялось" :
                "📥 Обновлено: " + getTimeAgo(ts);
        tvLastUpdate.setText(text);
    }

    private void updateLastScanTime() {
        if (tvLastScan == null) return;
        ServerRepository repo = new ServerRepository(this);
        long ts = repo.getLastScanTimestamp();
        String text = (ts == 0) ? "🔍 Проверка: не проверялся" :
                "🔍 Проверка: " + getTimeAgo(ts);
        tvLastScan.setText(text);
    }

    private String getTimeAgo(long timestamp) {
        long mins = (System.currentTimeMillis() - timestamp) / 60000;
        if (mins < 1) return "только что";
        if (mins < 60) return mins + " мин назад";
        if (mins < 1440) return (mins / 60) + " ч назад";
        return (mins / 1440) + " дн назад";
    }

    private void updateAutoConnectStatus() {
        if (tvAutoConnectStatus == null) return;
        boolean enabled = new ServerRepository(this).isAutoConnectOnWifiDisconnect();
        tvAutoConnectStatus.setText(enabled ? "🟢 Авто-режим: ВКЛ" : "🔴 Авто-режим: ВЫКЛ");
        tvAutoConnectStatus.setTextColor(enabled ? Color.parseColor("#2E7D32") : Color.parseColor("#E65100"));
    }

    private void setCustomActionBarTitle() {
        if (getSupportActionBar() == null) return;

        getSupportActionBar().setDisplayShowCustomEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        View customView = LayoutInflater.from(this).inflate(R.layout.actionbar_title, null);
        ActionBar.LayoutParams params = new ActionBar.LayoutParams(
                ActionBar.LayoutParams.WRAP_CONTENT,
                ActionBar.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);

        getSupportActionBar().setCustomView(customView, params);

        try {
            PackageInfo info = getPackageInfo();
            TextView tvAppName = customView.findViewById(R.id.tv_actionbar_app_name);
            TextView tvVersion = customView.findViewById(R.id.tv_actionbar_version);
            tvAppName.setText(getString(R.string.app_name));
            tvVersion.setText("v" + info.versionName);
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка установки заголовка", e);
        }
    }

    private PackageInfo getPackageInfo() throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return getPackageManager().getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0));
        } else {
            return getPackageManager().getPackageInfo(getPackageName(), 0);
        }
    }

    // ================================================
    // VPN Управление (через VpnController)
    // ================================================

    private void onConnectClicked(VlessServer server) {
        if (server == null) return;

        FileLogger.i(TAG, "onConnectClicked: " + server.host);

        VpnController controller = VpnController.getInstance(this);

        if (VpnTunnelService.isRunning) {
            VlessServer current = VpnTunnelService.getCurrentServer(); // или connectedServer
            boolean isSame = current != null && current.id.equals(server.id);

            if (isSame) {
                controller.handleDisconnectButton();
            } else {
                // Переключение на другой сервер
                FileLogger.i(TAG, "Переключаемся на сервер: " + server.host);
                controller.disconnect(true);
                mainHandler.postDelayed(() -> controller.connect(server, false), 1000);
            }
            return;
        }

        // Запрос разрешения VPN
        Intent perm = VpnService.prepare(this);
        if (perm != null) {
            pendingServer = server;
            vpnPermLauncher.launch(perm);
        } else {
            doStartVpn(server);
        }
    }

    private void doStartVpn(VlessServer server) {
        if (server == null) {
            Toast.makeText(this, "Ошибка: сервер не выбран", Toast.LENGTH_SHORT).show();
            return;
        }

        FileLogger.i(TAG, "doStartVpn: " + server.host);

        if (tvStatusMode != null) {
            tvStatusMode.setText("⏳ Подключение к " + (server.remark.isEmpty() ? server.host : server.remark));
        }

        VpnController.getInstance(this).connect(server, false);

        Toast.makeText(this, "⏳ Подключение...", Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(this::refreshStatus, 800);
    }

    private void disconnectVpn() {
        VpnController.getInstance(this).handleDisconnectButton();
        renderConnectionState(false);
    }

    private void refreshStatus() {
        viewModel.refreshVpnStatus();
    }

    /** Сбрасывает панели скорости и IP при смене сервера */
    private void resetSpeedAndIpPanels() {
        if (tvSpeedTest != null) {
            tvSpeedTest.setText("⏱ Тест скорости");
            tvSpeedTest.setTextColor(0xFFFFFFFF);
        }
        if (btnSpeedTest != null) {
            btnSpeedTest.setImageResource(R.drawable.ic_play);
            btnSpeedTest.setImageTintList(
                android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        }
        if (panelSpeedTest != null) panelSpeedTest.setBackgroundColor(0xFF111827);
        // IP — очищаем через ViewModel (отдельная LiveData)
        viewModel.clearIpResult();
    }

    private void renderConnectionState(boolean connected) {
        if (connected) {
            tvStatus.setText("🟢 Подключено");
            tvStatus.setTextColor(getColor(R.color.color_connected));
            btnDisconnect.setVisibility(View.VISIBLE);

            if (panelSpeedTest != null) panelSpeedTest.setVisibility(View.VISIBLE);
            boolean deepEnabled = new ServerRepository(this).isDeepCheckOnConnect();
            if (panelDeepCheck != null) panelDeepCheck.setVisibility(deepEnabled ? View.VISIBLE : View.GONE);

            if (tvStatusMode != null) tvStatusMode.setText("🟢 VPN активен");
        } else {
            tvStatus.setText("🔴 Отключено");
            tvStatus.setTextColor(getColor(R.color.color_disconnected));
            btnDisconnect.setVisibility(View.INVISIBLE);
            tvConnectedServer.setText("");
            tvTraffic.setText(" ");

            if (panelDeepCheck != null) panelDeepCheck.setVisibility(View.GONE);
            if (panelSpeedTest != null) panelSpeedTest.setVisibility(View.GONE);

            if (tvStatusMode != null) tvStatusMode.setText("Готов к подключению");
            viewModel.clearIpResult();
        }
    }

    // ================================================
    // Меню
    // ================================================

    @SuppressLint("RestrictedApi")
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (menu instanceof MenuBuilder) {
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.menu_log) {
            showLogDialog();
            return true;
        }
        if (id == R.id.action_check_update) {
            checkUpdateManual();
            return true;
        }
        if (id == R.id.action_scan) {
            if (VpnTunnelService.isRunning) {
                Toast.makeText(this, "⚠️ Отключите VPN для сканирования", Toast.LENGTH_SHORT).show();
                return true;
            }
            BackgroundMonitorService.runScanNow(this);
            Toast.makeText(this, "🔍 Проверка текущего списка...", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_refresh) {
            viewModel.forceRefreshServers();
            Toast.makeText(this, "Скачивание и проверка серверов...", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_share_log) {
            FileLogger.shareLog(this);
            return true;
        }
        if (id == R.id.menu_clear_log) {
            new AlertDialog.Builder(this)
                    .setTitle("Очистка лога")
                    .setMessage("Удалить ВСЕ записи лога?\n\nЭто действие необратимо.")
                    .setPositiveButton("Да, очистить", (dialog, which) -> {
                        FileLogger.clearLog();
                        Toast.makeText(this, "Лог полностью очищен", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showLogDialog() {
        String logText = FileLogger.getRecentLogs(5);
        final Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        dialog.setContentView(R.layout.dialog_log);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        ScrollView scrollLog = dialog.findViewById(R.id.scroll_log);
        TextView tvLog = dialog.findViewById(R.id.tv_log);
        tvLog.setText(logText);
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));

        dialog.findViewById(R.id.btn_share).setOnClickListener(v -> {
            FileLogger.shareLog(this);
            dialog.dismiss();
        });
        dialog.findViewById(R.id.btn_copy).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("VlessVPN Log", logText);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show();
        });
        dialog.findViewById(R.id.btn_to_end).setOnClickListener(v -> scrollLog.fullScroll(View.FOCUS_DOWN));
        dialog.findViewById(R.id.btn_close).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    // ================================================
    // Обновления приложения
    // ================================================

    private void checkUpdateIfNeeded() {
        if (!UpdateChecker.shouldCheckUpdate(this)) return;

        updateChecker = new UpdateChecker(this, new UpdateChecker.OnUpdateCheckListener() {
            @Override
            public void onUpdateAvailable(String versionName, int versionCode, String downloadUrl, String changelog) {
                runOnUiThread(() -> showUpdateDialog(versionName, versionCode, downloadUrl, changelog));
            }

            @Override
            public void onNoUpdate() {
                FileLogger.d(TAG, "Обновлений нет");
            }

            @Override
            public void onError(String error) {
                FileLogger.w(TAG, "Ошибка проверки обновлений: " + error);
            }
        });
        updateChecker.checkForUpdate();
        UpdateChecker.markUpdateChecked(this);
    }

    private void showUpdateDialog(String versionName, int versionCode, String downloadUrl, String changelog) {
        new AlertDialog.Builder(this)
                .setTitle("📦 Доступно обновление")
                .setMessage("Версия: " + versionName + "\n\n" + (changelog.isEmpty() ? "Доступна новая версия!" : changelog))
                .setPositiveButton("Скачать", (dialog, which) -> {
                    Intent intent = new Intent(this, UpdateDownloadService.class);
                    intent.putExtra(UpdateDownloadService.EXTRA_DOWNLOAD_URL, downloadUrl);
                    intent.putExtra(UpdateDownloadService.EXTRA_VERSION_NAME, versionName);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                })
                .setNegativeButton("Позже", null)
                .show();
    }

    private void checkUpdateManual() {
        Toast.makeText(this, "🔍 Проверка обновлений...", Toast.LENGTH_SHORT).show();
        updateChecker = new UpdateChecker(this, new UpdateChecker.OnUpdateCheckListener() {
            @Override
            public void onUpdateAvailable(String versionName, int versionCode, String downloadUrl, String changelog) {
                runOnUiThread(() -> showUpdateDialog(versionName, versionCode, downloadUrl, changelog));
            }

            @Override
            public void onNoUpdate() {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "✅ Установлена последняя версия", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Ошибка: " + error, Toast.LENGTH_LONG).show());
            }
        });
        updateChecker.checkForUpdate();
    }
}