package com.vlessvpn.app.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vlessvpn.app.R;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.network.WifiMonitor;
import com.vlessvpn.app.service.BackgroundMonitorService;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;
import com.google.gson.Gson;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.view.WindowManager;
import android.content.ClipboardManager;
import android.content.ClipData;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private MainViewModel viewModel;
    private ServerAdapter serverAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // UI
    private TextView    tvStatus;
    private TextView    tvConnectedServer;
    private Button      btnDisconnect;
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
    private TextView    tvTraffic;
    private TextView    tvLastUpdate;
    private TextView    tvAutoConnectStatus;

    private VlessServer pendingServer = null;
    private boolean     receiverRegistered = false;

    @SuppressLint("RestrictedApi")
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (menu instanceof MenuBuilder) {
            @SuppressLint("RestrictedApi") MenuBuilder builder = (MenuBuilder) menu;
            builder.setOptionalIconsVisible(true);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    // ── VPN разрешение ───────────────────────────────────────────────────────
    private final ActivityResultLauncher<Intent> vpnPermLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && pendingServer != null) {
                            doStartVpn(pendingServer);
                            pendingServer = null;
                        } else {
                            Toast.makeText(this, "Разрешение VPN отклонено", Toast.LENGTH_SHORT).show();
                        }
                    });

    // ── Broadcast receiver для VPN статуса ───────────────────────────────────
    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            //FileLogger.i(TAG, "=== VPN_STATUS_CHANGED broadcast получен ===");
            refreshStatus();
        }
    };

    // ── Broadcast receiver для StatusBus (прогресс сканирования) ─────────────
// ════════════════════════════════════════════════════════════════
// В MainActivity.java — упростить statusReceiver
// ════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════
// В statusReceiver.onReceive() — НЕ скрывать панель после завершения
// ════════════════════════════════════════════════════════════════

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();
            //FileLogger.i(TAG, "statusReceiver: " + action);

            if (StatusBus.ACTION_STATUS_CHANGED.equals(action)) {
                String message = intent.getStringExtra(StatusBus.EXTRA_MESSAGE);
                boolean isRunning = intent.getBooleanExtra(StatusBus.EXTRA_IS_RUNNING, false);
                int progress = intent.getIntExtra(StatusBus.EXTRA_PROGRESS, 0);
                int total = intent.getIntExtra(StatusBus.EXTRA_TOTAL, 0);
                int ok = intent.getIntExtra(StatusBus.EXTRA_OK, 0);
                int fail = intent.getIntExtra(StatusBus.EXTRA_FAIL, 0);

                //FileLogger.d(TAG, "Progress: " + message + " run=" + isRunning);

                if (message != null && !message.isEmpty()) {
                    mainHandler.post(() -> {
                        tvProgressTitle.setText(message);
                        tvLastStatus.setText(message);  // ← Прогресс/итог здесь
                    });
                }
                mainHandler.post(() -> {
                    // ← ВАЖНО: НЕ скрываем панель после завершения!
                    // Только останавливаем спиннер
                    progressSpinner.setVisibility(isRunning ? View.VISIBLE : View.GONE);
                    progressBar.setProgress(progress);

                    if (total > 0) {
                        tvCountTotal.setText("В листе: " + total);
                        tvCountOk.setText("✓ Рабочих: " + ok);
                        tvCountFail.setText("✗ Нет связи: " + fail);
                    }
                });
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

// ════════════════════════════════════════════════════════════════
// В onResume() — регистрируем с RECEIVER_EXPORTED
// ════════════════════════════════════════════════════════════════


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
    }

    @Override
    protected void onResume() {
        super.onResume();

        // ════════════════════════════════════════════════════════════════
        // ← Обновляем информацию о листе при каждом входе
        // ════════════════════════════════════════════════════════════════
        updateSheetInfo();
        updateLastUpdateTime();
        updateAutoConnectStatus();

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
            FileLogger.i(TAG, "StatusBus Receiver: EXPORTED");
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

// ════════════════════════════════════════════════════════════════
// ← НОВЫЙ МЕТОД: Информация о текущем листе
// ════════════════════════════════════════════════════════════════
    private void updateSheetInfo() {
        // ════════════════════════════════════════════════════════════════
        // ← ЗАПУСКАЕМ В ФОНЕ (не на главном потоке!)
        // ════════════════════════════════════════════════════════════════
        new Thread(() -> {
            ServerRepository repo = new ServerRepository(this);

            // Получаем все серверы (в фоне — можно sync)
            List<VlessServer> allServers = repo.getAllServersSync();
            int total = allServers.size();

            // Считаем рабочие
            int working = 0;
            for (VlessServer s : allServers) {
                if (s.trafficOk) working++;
            }

            // Время последней проверки
            long lastScan = repo.getLastScanTimestamp();
            String scanAgo;
            if (lastScan == 0) {
                scanAgo = "не проверялся";
            } else {
                long mins = (System.currentTimeMillis() - lastScan) / 60000;
                if (mins < 1) scanAgo = "только что";
                else if (mins < 60) scanAgo = mins + " мин назад";
                else if (mins < 1440) scanAgo = (mins / 60) + " ч назад";
                else scanAgo = (mins / 1440) + " дн назад";
            }

            // ← Обновляем UI на главном потоке
            String sheetInfo = "📊 Всего: " + total + " | ✓ Рабочих: " + working + " | Последняя проверка: " + scanAgo;

            mainHandler.post(() -> {
                if (tvLastStatus != null) {
                    tvLastStatus.setText(sheetInfo);
                    tvLastStatus.setVisibility(View.VISIBLE);
                }
            });

            FileLogger.i(TAG, "Sheet info: " + sheetInfo);
        }).start();
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    private void initViews() {
        tvStatus          = findViewById(R.id.tv_status);
        tvConnectedServer = findViewById(R.id.tv_connected_server);
        btnDisconnect     = findViewById(R.id.btn_disconnect);
        recyclerView      = findViewById(R.id.recycler_servers);
        swipeRefresh      = findViewById(R.id.swipe_refresh);
        tvEmptyState      = findViewById(R.id.tv_empty_state);
        panelProgress     = findViewById(R.id.panel_progress);
        progressSpinner   = findViewById(R.id.progress_spinner);
        progressBar       = findViewById(R.id.progress_bar);
        tvProgressTitle   = findViewById(R.id.tv_progress_title);
        tvProgressDetail  = findViewById(R.id.tv_progress_detail);
        tvCountTotal      = findViewById(R.id.tv_count_total);
        tvCountOk         = findViewById(R.id.tv_count_ok);
        tvCountFail       = findViewById(R.id.tv_count_fail);
        tvLastStatus      = findViewById(R.id.tv_last_status);
        tvTraffic         = findViewById(R.id.tv_traffic);
        tvLastUpdate      = findViewById(R.id.tv_last_update);
        tvAutoConnectStatus = findViewById(R.id.tv_auto_connect_status);

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
               // FileLogger.i(TAG, "═══════════════════════════════════════");
               // FileLogger.i(TAG, "OBSERVE: servers.size() = " + servers.size());

                serverAdapter.setServers(servers);
                boolean empty = servers.isEmpty();

                tvEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);

              //  FileLogger.i(TAG, "tvEmptyState = " + (empty ? "VISIBLE" : "GONE"));
               // FileLogger.i(TAG, "recyclerView = " + (empty ? "GONE" : "VISIBLE"));
               // FileLogger.i(TAG, "═══════════════════════════════════════");
            }
        });

        viewModel.getIsConnected().observe(this, connected -> {
           // FileLogger.d(TAG, "isConnected.observe: " + connected);
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

        viewModel.getIsWorking().observe(this, working -> {
            panelProgress.setVisibility(working ? View.VISIBLE : View.GONE);
            progressSpinner.setVisibility(working ? View.VISIBLE : View.GONE);
            swipeRefresh.setRefreshing(working);
        });
    }

    private void updateLastUpdateTime() {
        if (tvLastUpdate == null) return;
        long ts = new ServerRepository(this).getLastUpdateTimestamp();
        if (ts == 0) {
            tvLastUpdate.setText("Список не обновлялся");
        } else {
            long mins = (System.currentTimeMillis() - ts) / 60000;
            String ago;
            if (mins < 1)        ago = "только что";
            else if (mins < 60)  ago = mins + " мин назад";
            else if (mins < 1440) ago = (mins / 60) + " ч назад";
            else                  ago = (mins / 1440) + " дн назад";
            tvLastUpdate.setText("Обновлено: " + ago);
        }
    }

    private void updateAutoConnectStatus() {
        if (tvAutoConnectStatus == null) return;

        ServerRepository repo = new ServerRepository(this);
        boolean autoConnectEnabled = repo.isAutoConnectOnWifiDisconnect();

        if (autoConnectEnabled) {
            tvAutoConnectStatus.setText("🟢 Авто-режим: ВКЛ");
            tvAutoConnectStatus.setBackgroundColor(Color.parseColor("#E8F5E9"));
            tvAutoConnectStatus.setTextColor(Color.parseColor("#2E7D32"));
            tvAutoConnectStatus.setVisibility(View.VISIBLE);
        } else {
            tvAutoConnectStatus.setText("🔴 Авто-режим: ВЫКЛ");
            tvAutoConnectStatus.setBackgroundColor(Color.parseColor("#FFF3E0"));
            tvAutoConnectStatus.setTextColor(Color.parseColor("#E65100"));
            tvAutoConnectStatus.setVisibility(View.VISIBLE);
        }
    }

    // ── VPN управление ───────────────────────────────────────────────────────

    private void onConnectClicked(VlessServer server) {
        FileLogger.i(TAG, "=== Ручное подключение: " + server.host);
        pendingServer = server;
        Intent perm = VpnService.prepare(this);
        if (perm != null) {
            vpnPermLauncher.launch(perm);
        } else {
            doStartVpn(server);
            pendingServer = null;
        }
    }

    private void doStartVpn(VlessServer server) {
        FileLogger.i(TAG, "=== Старт VPN: " + server.host + ":" + server.port);
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

    private void disconnectVpn() {
        FileLogger.i(TAG, "=== Отключение VPN");
        Intent i = new Intent(this, VpnTunnelService.class);
        i.setAction(VpnTunnelService.ACTION_DISCONNECT);
        startService(i);
        renderConnectionState(false);
        mainHandler.postDelayed(this::refreshStatus, 1000);
    }

    private void refreshStatus() {
        viewModel.refreshVpnStatus();
    }

    private void renderConnectionState(boolean connected) {
        if (connected) {
            tvStatus.setText("🟢 Подключено");
            tvStatus.setTextColor(getColor(R.color.color_connected));
            btnDisconnect.setVisibility(View.VISIBLE);
            tvTraffic.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setText("🔴 Отключено");
            tvStatus.setTextColor(getColor(R.color.color_disconnected));
            btnDisconnect.setVisibility(View.GONE);
            tvConnectedServer.setText("—");
            tvTraffic.setVisibility(View.GONE);
            tvTraffic.setText("");
        }
    }

    // ── Меню ─────────────────────────────────────────────────────────────────

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_log) {
            showLogDialog();
            return true;
        }
        int id = item.getItemId();

        if (id == R.id.action_download) {
            ServerRepository repo = new ServerRepository(this);
            repo.resetUpdateTime();
            BackgroundMonitorService.runDownloadNow(this);
            Toast.makeText(this, "📥 Скачивание новых списков...", Toast.LENGTH_SHORT).show();
            return true;
        }

        if (id == R.id.action_scan) {
            if (VpnTunnelService.isRunning) {
                Toast.makeText(this, "⚠️ Отключите VPN для сканирования", Toast.LENGTH_SHORT).show();
                return true;
            }
            BackgroundMonitorService.runScanNow(this);
            Toast.makeText(this, "🔍 Сканирование текущего списка...", Toast.LENGTH_SHORT).show();
            return true;
        }

        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_refresh) {
            viewModel.forceRefreshServers();
            Toast.makeText(this, "Обновление серверов...", Toast.LENGTH_SHORT).show();
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
}