package com.vlessvpn.app.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.widget.SwitchCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vlessvpn.app.R;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.network.WifiMonitor;
import com.vlessvpn.app.service.BackgroundMonitorService;
import com.vlessvpn.app.service.UpdateDownloadService;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;
import com.google.gson.Gson;
import com.vlessvpn.app.util.UpdateChecker;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.view.WindowManager;
import android.content.ClipboardManager;
import android.content.ClipData;
import java.util.List;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.ActionBar;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private MainViewModel viewModel;
    private ServerAdapter serverAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // UI
    private TextView    tvStatus;
    private TextView    tvConnectedServer;
    private ImageButton btnDisconnect;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private View        tvEmptyState;
    private View        panelProgress;
    private ProgressBar progressSpinner;
    private ProgressBar progressBar;
    private TextView    tvProgressTitle;
    private TextView    tvProgressDetail;
    private TextView    tvCountTotal;
    private TextView    tvCountOk;
    private TextView    tvCountFail;
    private TextView    tvLastStatus;
    private View        panelDeepCheck;
    private View        panelSpeedTest;
    private TextView    tvSpeedTest;
    private android.widget.ImageButton btnSpeedTest;
    private android.widget.ImageButton btnDeepCheckRefresh;
    private TextView    tvTraffic;
    // private TextView    tvLastUpdate;
    private TextView    tvAutoConnectStatus;

    private VlessServer pendingServer = null;
    private boolean     receiverRegistered = false;
    private UpdateChecker updateChecker;

    private TextView tvStatusMode;
    private TextView tvServerCounts;
    private TextView tvLastUpdate;
    private TextView tvLastScan;

    @SuppressLint("RestrictedApi")
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (menu instanceof MenuBuilder) {
            @SuppressLint("RestrictedApi") MenuBuilder builder = (MenuBuilder) menu;
            builder.setOptionalIconsVisible(true);
        }
        return super.onPrepareOptionsMenu(menu);
    }



    private final ActivityResultLauncher<Intent> vpnPermLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && pendingServer != null) {
                            doStartVpn(pendingServer);  // ← Проверка уже внутри doStartVpn()
                            pendingServer = null;
                        } else {
                            Toast.makeText(this, "Разрешение VPN отклонено", Toast.LENGTH_SHORT).show();
                            pendingServer = null;
                        }
                    });


    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            //FileLogger.i(TAG, "=== VPN_STATUS_CHANGED broadcast получен ===");

            refreshStatus();
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();

            if (StatusBus.ACTION_STATUS_CHANGED.equals(action)) {
                String message = intent.getStringExtra(StatusBus.EXTRA_MESSAGE);
                boolean isRunning = intent.getBooleanExtra(StatusBus.EXTRA_IS_RUNNING, false);
                int progress = intent.getIntExtra(StatusBus.EXTRA_PROGRESS, 0);
                int total = intent.getIntExtra(StatusBus.EXTRA_TOTAL, 0);
                int ok = intent.getIntExtra(StatusBus.EXTRA_OK, 0);
                int fail = intent.getIntExtra(StatusBus.EXTRA_FAIL, 0);

               // FileLogger.d(TAG, "Progress: " + message + " run=" + isRunning);


                if (message != null && !message.isEmpty()) {
                    mainHandler.post(() -> {
                        // ════════════════════════════════════════════════════
                        // ← Обновляем ТОЛЬКО строку режима
                        // ════════════════════════════════════════════════════
                        if (isRunning) {
                            tvStatusMode.setText("🔍 " + message);

                        } else {
                            tvStatusMode.setText("✅ " + message);
                        }

                        // ════════════════════════════════════════════════════
                        // ← Обновляем счётчики если есть данные
                        // ════════════════════════════════════════════════════
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
                long ping = intent.getLongExtra(StatusBus.EXTRA_PING, -1);
                String detail = intent.getStringExtra(StatusBus.EXTRA_DETAIL);

                ServerAdapter.TestStatus st;
                switch (status) {
                    case "pinging": st = ServerAdapter.TestStatus.PINGING; break;
                    case "testing": st = ServerAdapter.TestStatus.TESTING; break;
                    case "ok":      st = ServerAdapter.TestStatus.OK;      break;
                    case "fail":    st = ServerAdapter.TestStatus.FAIL;    break;
                    default:        st = ServerAdapter.TestStatus.IDLE;
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

    // ── Lifecycle ────────────────────────────────────────────────────────────

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
        WifiMonitor.startMonitoring(this);
        checkUpdateIfNeeded();
        setCustomActionBarTitle();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // ════════════════════════════════════════════════════════════════
        // ← Обновляем информацию о листе при каждом входе
        // ════════════════════════════════════════════════════════════════
        updateAutoConnectStatus();
        updateInfoPanel();  // ← Обновить всю панель


        // Регистрируем VPN receiver
        IntentFilter vpnFilter = new IntentFilter("com.vlessvpn.VPN_STATUS_CHANGED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnReceiver, vpnFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vpnReceiver, vpnFilter);
        }
        receiverRegistered = true;

        IntentFilter statusFilter = new IntentFilter(StatusBus.ACTION_STATUS_CHANGED);
        statusFilter.addAction(StatusBus.ACTION_SERVER_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, statusFilter, Context.RECEIVER_EXPORTED);
           // FileLogger.i(TAG, "StatusBus Receiver: EXPORTED");
        } else {
            registerReceiver(statusReceiver, statusFilter);
            //FileLogger.i(TAG, "StatusBus Receiver: зарегистрирован");
        }

        refreshStatus();
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

    private void setCustomActionBarTitle() {
        if (getSupportActionBar() != null) {
            // Включаем отображение кастомного view
            getSupportActionBar().setDisplayShowCustomEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);  // ← Скрыть стандартный заголовок

            // Загружаем кастомный layout
            LayoutInflater inflater = LayoutInflater.from(this);
            View customView = inflater.inflate(R.layout.actionbar_title, null);

            // Устанавливаем layout params
            ActionBar.LayoutParams params = new ActionBar.LayoutParams(
                    ActionBar.LayoutParams.WRAP_CONTENT,
                    ActionBar.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.CENTER;  // ← Центрировать заголовок
            getSupportActionBar().setCustomView(customView, params);

            // Получаем версию и устанавливаем
            try {
                PackageInfo info = getPackageInfo();
                String versionName = info.versionName;

                TextView tvAppName = customView.findViewById(R.id.tv_actionbar_app_name);
                TextView tvVersion = customView.findViewById(R.id.tv_actionbar_version);

                tvAppName.setText(getString(R.string.app_name));
                tvVersion.setText("v" + versionName);

                //FileLogger.i(TAG, "Кастомный заголовок установлен");

            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка получения версии: " + e.getMessage());
            }
        }
    }

// ════════════════════════════════════════════════════════════════
// ← Вспомогательный метод (совместимость API 33+)
// ════════════════════════════════════════════════════════════════

    private String fmtBytes(long bytes) {
        if (bytes < 1024)             return bytes + " B";
        if (bytes < 1024 * 1024)      return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private PackageInfo getPackageInfo() throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return getPackageManager().getPackageInfo(
                    getPackageName(),
                    PackageManager.PackageInfoFlags.of(0)
            );
        } else {
            return getPackageManager().getPackageInfo(
                    getPackageName(),
                    0
            );
        }
    }

// ════════════════════════════════════════════════════════════════
// ← НОВЫЙ МЕТОД: Информация о текущем листе
// ════════════════════════════════════════════════════════════════


    // ── Init ─────────────────────────────────────────────────────────────────

    private void initViews() {
        tvStatus          = findViewById(R.id.tv_status);
        tvConnectedServer = findViewById(R.id.tv_connected_server);
        btnDisconnect     = findViewById(R.id.btn_disconnect);
        recyclerView      = findViewById(R.id.recycler_servers);
        swipeRefresh      = findViewById(R.id.swipe_refresh);
        tvEmptyState      = findViewById(R.id.tv_empty_state);
//        panelProgress     = findViewById(R.id.panel_progress);
//        progressSpinner   = findViewById(R.id.progress_spinner);
//        progressBar       = findViewById(R.id.progress_bar);
//        tvProgressTitle   = findViewById(R.id.tv_progress_title);
//        tvProgressDetail  = findViewById(R.id.tv_progress_detail);
//        tvCountTotal      = findViewById(R.id.tv_count_total);
//        tvCountOk         = findViewById(R.id.tv_count_ok);
//        tvCountFail       = findViewById(R.id.tv_count_fail);
        tvLastStatus         = findViewById(R.id.tv_last_status);
        panelDeepCheck       = findViewById(R.id.panel_deep_check);
        panelSpeedTest       = findViewById(R.id.panel_speed_test);
        tvSpeedTest          = findViewById(R.id.tv_speed_test);
        btnSpeedTest         = findViewById(R.id.btn_speed_test);
        if (btnSpeedTest != null) {
            btnSpeedTest.setOnClickListener(v -> {
                if (VpnTunnelService.isRunning) {
                    if (tvSpeedTest != null) tvSpeedTest.setText("⏱ Тест скорости...");
                    com.vlessvpn.app.service.VpnTunnelService svc =
                        com.vlessvpn.app.service.VpnTunnelService.getInstance();
                    if (svc != null) svc.runSpeedTest();
                } else {
                    android.widget.Toast.makeText(MainActivity.this,
                        "Подключите VPN для теста скорости",
                        android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
        btnDeepCheckRefresh  = findViewById(R.id.btn_deep_check_refresh);
        if (btnDeepCheckRefresh != null) {
            btnDeepCheckRefresh.setOnClickListener(v -> {
                if (VpnTunnelService.isRunning && com.vlessvpn.app.service.BackgroundMonitorService.class != null) {
                    tvLastStatus.setText("🔬 Проверка...");
                    new Thread(() -> {
                        com.vlessvpn.app.service.VpnTunnelService svc =
                            com.vlessvpn.app.service.VpnTunnelService.getInstance();
                        if (svc != null) svc.runDeepCheck();
                    }).start();
                }
            });
        }
        tvTraffic         = findViewById(R.id.tv_traffic);
        tvLastUpdate      = findViewById(R.id.tv_last_update);
        tvAutoConnectStatus = findViewById(R.id.tv_auto_connect_status);
        tvStatusMode = findViewById(R.id.tv_status_mode);
        tvServerCounts = findViewById(R.id.tv_server_counts);
        tvLastUpdate = findViewById(R.id.tv_last_update);
        tvLastScan = findViewById(R.id.tv_last_scan);
        btnDisconnect.setOnClickListener(v -> disconnectVpn());

        swipeRefresh.setOnRefreshListener(() -> {
            if (VpnTunnelService.isRunning) {
                Toast.makeText(this, "Отключаем VPN для проверки...", Toast.LENGTH_SHORT).show();
                disconnectVpn();
                mainHandler.postDelayed(() -> {
                    viewModel.forceRefreshServers();
                    mainHandler.postDelayed(() -> swipeRefresh.setRefreshing(false), 3000);
                }, 1500);
            } else {
                viewModel.forceRefreshServers();
                mainHandler.postDelayed(() -> swipeRefresh.setRefreshing(false), 3000);
            }
        });
        // ════════════════════════════════════════════════════════════════
        // ← ОТКЛЮЧИТЬ свайп вниз:
        // ════════════════════════════════════════════════════════════════
        swipeRefresh.setEnabled(false);
    }

    private void setupRecyclerView() {
        serverAdapter = new ServerAdapter(this::onConnectClicked);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(serverAdapter);
    }

    // ── Observe ──────────────────────────────────────────────────────────────

    private void observeData() {
        viewModel.getAllServers().observe(this, servers -> {
            if (servers != null) {
                //FileLogger.i(TAG, "OBSERVE: " + servers.size() + " серверов");
                serverAdapter.setServers(servers);
                boolean empty = servers.isEmpty();
                tvEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);

                // ════════════════════════════════════════════════════════════════
                // ← Обновлять счётчики серверов
                // ════════════════════════════════════════════════════════════════
                updateServerCounts(servers);
            }
        });

        viewModel.getIsConnected().observe(this, connected -> {
            renderConnectionState(connected);
        });

        viewModel.getConnectedServer().observe(this, server -> {
            if (server != null) {
                tvConnectedServer.setText(server.remark.isEmpty() ? server.host : server.remark);
                serverAdapter.setConnectedServerId(server.id);
            } else {
                tvConnectedServer.setText("—");
                serverAdapter.setConnectedServerId(null);
            }
        });

        // Трафик и статус сканирования через LiveData (надёжнее broadcast в том же процессе)
        viewModel.getLastStatusMessage().observe(this, msg -> {
            if (msg == null || msg.isEmpty()) return;

            if (msg.contains("↑") && msg.contains("↓")) {
                // Трафик
                if (VpnTunnelService.isRunning) {
                    if (tvTraffic != null) tvTraffic.setText(msg);
                } else {
                    if (tvTraffic != null) tvTraffic.setText(" ");
                }
            } else if (msg.startsWith("🔬")) {
                // Результат глубокой проверки IP → panel_deep_check
                if (VpnTunnelService.isRunning && tvLastStatus != null) {
                    tvLastStatus.setText(msg);
                    boolean ok = msg.contains("✓");
                    tvLastStatus.setTextColor(ok ? 0xFF4CAF50 : 0xFFFF5252);
                    if (btnDeepCheckRefresh != null) btnDeepCheckRefresh.setImageTintList(
                        android.content.res.ColorStateList.valueOf(ok ? 0xFF4CAF50 : 0xFFFF5252));
                    if (panelDeepCheck != null) {
                        panelDeepCheck.setBackgroundColor(ok ? 0xFF0D1F0D : 0xFF1F0D0D);
                        panelDeepCheck.setVisibility(View.VISIBLE);
                    }
                }
            } else if (msg.startsWith("⏱")) {
                // Результат теста скорости → panel_speed_test
                if (tvSpeedTest != null) {
                    tvSpeedTest.setText(msg);
                    boolean ok = msg.contains("✓");
                    tvSpeedTest.setTextColor(ok ? 0xFF4CAF50 : 0xFFFF5252);
                    if (btnSpeedTest != null) btnSpeedTest.setImageTintList(
                        android.content.res.ColorStateList.valueOf(ok ? 0xFF4CAF50 : 0xFFFF5252));
                    if (panelSpeedTest != null)
                        panelSpeedTest.setBackgroundColor(ok ? 0xFF0D1F0D : 0xFF1F0D0D);
                }
            } else {
                // Прогресс сканирования → tvStatusMode
                if (tvStatusMode != null) {
                    tvStatusMode.setText(VpnTunnelService.isRunning ? msg : "🔍 " + msg);
                }
            }
        });
    }

// ════════════════════════════════════════════════════════════════
// В updateServerCounts() — запускать в фоне
// ════════════════════════════════════════════════════════════════

    private void updateServerCounts(List<VlessServer> displayedServers) {
        // ════════════════════════════════════════════════════════════════
        // ← ВАЖНО: Запускаем в фоне (не на главном потоке!)
        // ════════════════════════════════════════════════════════════════
        new Thread(() -> {
            ServerRepository repo = new ServerRepository(this);
            List<VlessServer> allServers = repo.getAllServersSync();

            int total = allServers.size();
            int working = 0;
            int failed = 0;

            for (VlessServer s : allServers) {
                if (s.trafficOk) {
                    working++;
                } else {
                    failed++;
                }
            }

            int finalTotal = total;
            int finalWorking = working;
            int finalFailed = failed;

            // ← Обновляем UI на главном потоке
            mainHandler.post(() -> {
               // FileLogger.i(TAG, "Server counts: total=" + finalTotal + ", working=" + finalWorking + ", failed=" + finalFailed);

                if (tvServerCounts != null) {
                    tvServerCounts.setText("📊 Всего: " + finalTotal + " | ✓ Рабочих: " + finalWorking + " | ✗ Нет связи: " + finalFailed);
                }
            });
        }).start();
    }
// ════════════════════════════════════════════════════════════════
// ← НОВЫЙ МЕТОД: Обновить всю информацию панели
// ════════════════════════════════════════════════════════════════

    private void updateInfoPanel() {
        updateLastUpdateTime();
        updateLastScanTime();
    }

    private void updateLastUpdateTime() {
        if (tvLastUpdate == null) return;

        ServerRepository repo = new ServerRepository(this);
        long ts = repo.getLastUpdateTimestamp();

        String text;
        if (ts == 0) {
            text = "📥 Обновлено: не обновлялось";
        } else {
            long mins = (System.currentTimeMillis() - ts) / 60000;
            String ago;
            if (mins < 1)        ago = "только что";
            else if (mins < 60)  ago = mins + " мин назад";
            else if (mins < 1440) ago = (mins / 60) + " ч назад";
            else                  ago = (mins / 1440) + " дн назад";
            text = "📥 Обновлено: " + ago;
        }

        tvLastUpdate.setText(text);
    }

    private void updateLastScanTime() {
        if (tvLastScan == null) return;

        ServerRepository repo = new ServerRepository(this);
        long ts = repo.getLastScanTimestamp();

        String text;
        if (ts == 0) {
            text = "🔍 Проверка: не проверялся";
        } else {
            long mins = (System.currentTimeMillis() - ts) / 60000;
            String ago;
            if (mins < 1)        ago = "только что";
            else if (mins < 60)  ago = mins + " мин назад";
            else if (mins < 1440) ago = (mins / 60) + " ч назад";
            else                  ago = (mins / 1440) + " дн назад";
            text = "🔍 Проверка: " + ago;
        }

        tvLastScan.setText(text);
    }

    private void updateAutoConnectStatus() {
        if (tvAutoConnectStatus == null) return;

        ServerRepository repo = new ServerRepository(this);
        boolean autoConnectEnabled = repo.isAutoConnectOnWifiDisconnect();
        //SwitchCompat autoSwitch = findViewById(R.id.tv_auto_connect_status);
        if (autoConnectEnabled) {
            //autoSwitch.setChecked(true); // или false
            tvAutoConnectStatus.setText("🟢 Авто-режим: ВКЛ");
            //tvAutoConnectStatus.setBackgroundColor(Color.parseColor("#E8F5E9"));
            //tvAutoConnectStatus.setTextColor(Color.parseColor("#2E7D32"));
            tvAutoConnectStatus.setVisibility(View.VISIBLE);
        } else {
            //autoSwitch.setChecked(false); // или false
            //tvAutoConnectStatus.setText("🔴 Авто-режим: ВЫКЛ");
            //tvAutoConnectStatus.setBackgroundColor(Color.parseColor("#FFF3E0"));
            tvAutoConnectStatus.setTextColor(Color.parseColor("#E65100"));
            tvAutoConnectStatus.setVisibility(View.VISIBLE);
        }
    }

    // ── VPN управление ───────────────────────────────────────────────────────

// ════════════════════════════════════════════════════════════════
// В onConnectClicked() — исправить логику
// ════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════
// В onConnectClicked() — исправить переключение серверов
// ════════════════════════════════════════════════════════════════

    private void onConnectClicked(VlessServer server) {
        if (server == null) return;

        FileLogger.i(TAG, "onConnectClicked: " + server.host);

        if (VpnTunnelService.isRunning) {
            VlessServer connected = VpnTunnelService.connectedServer;
            boolean isSameServer = connected != null && connected.id.equals(server.id);

            if (isSameServer) {
                // Нажали на кнопку стоп подключённого сервера — отключаем
                FileLogger.i(TAG, "Отключаем VPN (нажата кнопка стоп)");
                disconnectVpn();
            } else {
                // Нажали на другой сервер — переключаемся
                FileLogger.i(TAG, "Переключаемся на: " + server.host);
                final VlessServer next = server;
                disconnectVpn();
                mainHandler.postDelayed(() -> doStartVpn(next), 800);
            }
            return;
        }

        // VPN не активен — подключаемся
        Intent perm = VpnService.prepare(this);
        if (perm != null) {
            pendingServer = server;
            vpnPermLauncher.launch(perm);
        } else {
            doStartVpn(server);
        }
    }

// ════════════════════════════════════════════════════════════════
// В doStartVpn() — добавить проверку на null
// ════════════════════════════════════════════════════════════════

    private void doStartVpn(VlessServer server) {
        // ════════════════════════════════════════════════════════════════
        // ← ПРОВЕРКА: server не должен быть null
        // ════════════════════════════════════════════════════════════════
        if (server == null) {
            FileLogger.e(TAG, "ERROR: doStartVpn called with null server!");
            Toast.makeText(this, "Ошибка: сервер не найден", Toast.LENGTH_SHORT).show();
            return;
        }

        FileLogger.i(TAG, "Старт VPN: " + server.host);

        // Обновить строку режима
        if (tvStatusMode != null) {
            tvStatusMode.setText("⏳ Подключение к " + (server.remark.isEmpty() ? server.host : server.remark));
        }

        Intent i = new Intent(this, VpnTunnelService.class);
        i.setAction(VpnTunnelService.ACTION_CONNECT);
        i.putExtra(VpnTunnelService.EXTRA_SERVER, new Gson().toJson(server));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        Toast.makeText(this, "⏳ Подключение к " + server.remark, Toast.LENGTH_SHORT).show();

        tvStatus.setText("⏳ Подключение...");
        tvStatus.setTextColor(getColor(R.color.color_disconnected));
        btnDisconnect.setVisibility(View.VISIBLE);

        mainHandler.postDelayed(this::refreshStatus, 1000);
        mainHandler.postDelayed(this::refreshStatus, 3000);
        mainHandler.postDelayed(this::refreshStatus, 6000);
    }


// ════════════════════════════════════════════════════════════════
// В disconnectVpn() — обновить статус
// ════════════════════════════════════════════════════════════════

    private void disconnectVpn() {
        FileLogger.i(TAG, "disconnectVpn");
        Intent i = new Intent(this, VpnTunnelService.class);
        i.setAction(VpnTunnelService.ACTION_DISCONNECT);
        startService(i);

        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: Сразу обновить UI
        // ════════════════════════════════════════════════════════════════
        renderConnectionState(false);

        if (tvProgressTitle != null) {
            tvProgressTitle.setText("Отключено");
        }

        mainHandler.postDelayed(this::refreshStatus, 1000);
    }

    private void refreshStatus() {
        viewModel.refreshVpnStatus();
    }

// ════════════════════════════════════════════════════════════════
// В renderConnectionState() — очистить прогресс
// ════════════════════════════════════════════════════════════════

    private void renderConnectionState(boolean connected) {
        if (connected) {
            tvStatus.setText("🟢 Подключено");
            tvStatus.setTextColor(getColor(R.color.color_connected));
            btnDisconnect.setVisibility(View.VISIBLE);
            // Панель скорости — показываем, сбрасываем в белый дефолт
            if (panelSpeedTest != null) {
                panelSpeedTest.setVisibility(View.VISIBLE);
                panelSpeedTest.setBackgroundColor(0xFF111827);
            }
            if (tvSpeedTest != null) {
                tvSpeedTest.setText("⏱ Тест скорости");
                tvSpeedTest.setTextColor(0xFFFFFFFF);
            }
            if (btnSpeedTest != null) {
                btnSpeedTest.setImageResource(R.drawable.ic_play);
                btnSpeedTest.setImageTintList(
                    android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            }
            // Панель IP — показываем если галочка включена, иначе скрываем
            boolean deepEnabled = new com.vlessvpn.app.storage.ServerRepository(
                    MainActivity.this).isDeepCheckOnConnect();
            if (panelDeepCheck != null)
                panelDeepCheck.setVisibility(deepEnabled ? View.VISIBLE : View.GONE);
            if (tvLastStatus != null) {
                tvLastStatus.setText(deepEnabled ? "Определяем IP..." : "");
                tvLastStatus.setTextColor(0xFFFFFFFF);
            }
            if (btnDeepCheckRefresh != null) {
                btnDeepCheckRefresh.setImageResource(
                    deepEnabled ? R.drawable.ic_hourglass : R.drawable.ic_refresh);
                btnDeepCheckRefresh.setImageTintList(
                    android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            }
            if (tvStatusMode != null) tvStatusMode.setText("🟢 VPN активен");

        } else {
            tvStatus.setText("🔴 Отключено");
            tvStatus.setTextColor(getColor(R.color.color_disconnected));
            btnDisconnect.setVisibility(View.INVISIBLE);
            tvConnectedServer.setText("—");
            tvTraffic.setText(" ");
            if (panelDeepCheck != null) panelDeepCheck.setVisibility(View.GONE);
            if (panelSpeedTest != null) {
                panelSpeedTest.setVisibility(View.GONE);
                if (tvSpeedTest != null) tvSpeedTest.setText("⏱ Тест скорости");
            }
            if (tvStatusMode != null) tvStatusMode.setText("Готов к подключению");
        }
    }

    private Handler trafficHandler = new Handler(Looper.getMainLooper());
    private Runnable trafficRunnable;


    // ── Меню ─────────────────────────────────────────────────────────────────

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_log) {
            showLogDialog();
            return true;
        }

        if (id == R.id.action_check_update) {
            // ← Ручная проверка обновлений
            checkUpdateManual();
            return true;
        }
/*        if (id == R.id.action_download) {
            ServerRepository repo = new ServerRepository(this);
            repo.resetUpdateTime();
            BackgroundMonitorService.runDownloadNow(this);
            Toast.makeText(this, "📥 Скачивание новых списков...", Toast.LENGTH_SHORT).show();
            return true;
        }*/

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
        } else if (id == R.id.action_refresh) {
            viewModel.forceRefreshServers();
            Toast.makeText(this, "Скачивание и проверка серверов...", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_share_log) {
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
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
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

        dialog.findViewById(R.id.btn_to_end).setOnClickListener(v ->
                scrollLog.fullScroll(View.FOCUS_DOWN)
        );

        dialog.findViewById(R.id.btn_close).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }


// ════════════════════════════════════════════════════════════════
// ← НОВЫЙ МЕТОД: Автопроверка (не чаще 1 раза в 24 часа)
// ════════════════════════════════════════════════════════════════

    private void checkUpdateIfNeeded() {
        if (!UpdateChecker.shouldCheckUpdate(this)) {
            FileLogger.d(TAG, "Проверка обновлений — слишком рано");
            return;
        }

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

// ════════════════════════════════════════════════════════════════
// ← НОВЫЙ МЕТОД: Диалог обновления
// ════════════════════════════════════════════════════════════════

    private void showUpdateDialog(String versionName, int versionCode, String downloadUrl, String changelog) {
        new AlertDialog.Builder(this)
                .setTitle("📦 Доступно обновление")
                .setMessage("Версия: " + versionName + "\n\n" +
                        (changelog.isEmpty() ? "Доступна новая версия приложения!" : changelog))
                .setPositiveButton("Скачать", (dialog, which) -> {
                    // Запускаем скачивание
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

// ════════════════════════════════════════════════════════════════
// ← НОВЫЙ МЕТОД: Ручная проверка (с прогрессом)
// ════════════════════════════════════════════════════════════════

    private void checkUpdateManual() {
        Toast.makeText(this, "🔍 Проверка обновлений...", Toast.LENGTH_SHORT).show();

        updateChecker = new UpdateChecker(this, new UpdateChecker.OnUpdateCheckListener() {
            @Override
            public void onUpdateAvailable(String versionName, int versionCode, String downloadUrl, String changelog) {
                runOnUiThread(() -> showUpdateDialog(versionName, versionCode, downloadUrl, changelog));
            }

            @Override
            public void onNoUpdate() {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "✅ Установлена последняя версия", Toast.LENGTH_SHORT).show()
                );
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "❌ Ошибка: " + error, Toast.LENGTH_LONG).show()
                );
            }
        });

        updateChecker.checkForUpdate();
    }
}