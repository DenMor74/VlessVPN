package com.vlessvpn.app.ui;

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
    private TextView    tvAutoConnectStatus;  // ← НОВОЕ

    private VlessServer pendingServer = null;
    private boolean     receiverRegistered = false;

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

    // ── Broadcast receiver ───────────────────────────────────────────────────
    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            FileLogger.i(TAG, "═══════════════════════════════════════");
            FileLogger.i(TAG, "=== VPN_STATUS_CHANGED broadcast получен ===");

            if (intent != null) {
                boolean connected = intent.getBooleanExtra("connected", false);
                String serverJson = intent.getStringExtra("server");

                FileLogger.i(TAG, "connected=" + connected);
                FileLogger.i(TAG, "serverJson=" + serverJson);
            }

            // Обновляем статус
            refreshStatus();
            FileLogger.i(TAG, "═══════════════════════════════════════");
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
        updateAutoConnectStatus();  // ← НОВОЕ
        WifiMonitor.registerNetworkCallback(this);  // ← НОВОЕ
        // Запускаем фоновое сканирование при первом старте
        startService(new Intent(this, BackgroundMonitorService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLastUpdateTime();
        updateAutoConnectStatus();

        // Регистрируем receiver
        IntentFilter f = new IntentFilter("com.vlessvpn.VPN_STATUS_CHANGED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            FileLogger.i(TAG, "Receiver зарегистрирован (RECEIVER_NOT_EXPORTED)");
        } else {
            registerReceiver(vpnReceiver, f);
            FileLogger.i(TAG, "Receiver зарегистрирован");
        }
        receiverRegistered = true;

        // Принудительно обновляем статус
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            unregisterReceiver(vpnReceiver);
            receiverRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
       // WifiMonitor.unregisterNetworkCallback(this);  // ← НОВОЕ
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
        tvAutoConnectStatus = findViewById(R.id.tv_auto_connect_status);  // ← НОВОЕ

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

    private void observeData() {
        // ════════════════════════════════════════════════════════════════════════
        // Список серверов
        // ════════════════════════════════════════════════════════════════════════

        viewModel.topServers.observe(this, servers -> {
            serverAdapter.setServers(servers);
            boolean empty = servers == null || servers.isEmpty();
            tvEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        });

        viewModel.isConnected.observe(this, connected -> {
            FileLogger.d(TAG, "isConnected.observe: " + connected);
            renderConnectionState(connected);
        });

        viewModel.connectedServer.observe(this, server -> {
            FileLogger.d(TAG, "connectedServer.observe: " + (server != null ? server.host : "null"));
            if (server != null) {
                tvConnectedServer.setText(server.remark.isEmpty() ? server.host : server.remark);
                serverAdapter.setConnectedServerId(server.id);
            } else {
                tvConnectedServer.setText("—");
                serverAdapter.setConnectedServerId(null);
            }
        });

        // ════════════════════════════════════════════════════════════════════════
        // Прогресс сканирования
        // ════════════════════════════════════════════════════════════════════════

        viewModel.getIsWorking().observe(this, working -> {
            if (!working) updateLastUpdateTime();
            panelProgress.setVisibility(working ? View.VISIBLE : View.GONE);
            progressSpinner.setVisibility(working ? View.VISIBLE : View.GONE);
            tvLastStatus.setVisibility(working ? View.GONE : View.VISIBLE);
            swipeRefresh.setRefreshing(working);
        });

        viewModel.getProgressTitle().observe(this, t -> {
            if (t != null && !t.isEmpty()) tvProgressTitle.setText(t);
        });

        viewModel.getProgressDetail().observe(this, d ->
                tvProgressDetail.setText(d != null ? d : ""));

        viewModel.getProgressPercent().observe(this, p ->
                progressBar.setProgress(p != null ? p : 0));

        viewModel.getProgressCounts().observe(this, c -> {
            if (c == null) return;
            tvCountTotal.setText("В листе: " + c[0]);
            tvCountOk.setText("✓ Рабочих: " + c[1]);
            tvCountFail.setText("✗ Нет связи: " + c[2]);
        });

        // ════════════════════════════════════════════════════════════════════════
        // ← ИСПРАВЛЕНО: lastStatusMessage ТОЛЬКО для сканирования (НЕ трафик!)
        // ════════════════════════════════════════════════════════════════════════

        viewModel.getLastStatusMessage().observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                if (msg.contains("↑") || msg.contains("↓")) {
                    // Это трафик → только tvTraffic (возле статуса подключения)
                    tvTraffic.setText(msg);
                    tvTraffic.setVisibility(View.VISIBLE);
                    // tvLastStatus НЕ обновляем!
                } else {
                    // Это статус сканирования → tvLastStatus
                    tvLastStatus.setText(msg);
                    tvLastStatus.setVisibility(View.VISIBLE);
                }
            }
        });

        // ════════════════════════════════════════════════════════════════════════
        // События серверов (pinging/testing/ok/fail)
        // ════════════════════════════════════════════════════════════════════════

        viewModel.getServerEvents().observe(this, event -> {
            if (event == null) return;

            FileLogger.d(TAG, "ServerEvent: " + event.host + " → " + event.status);

            ServerAdapter.TestStatus st;
            switch (event.status) {
                case "pinging": st = ServerAdapter.TestStatus.PINGING; break;
                case "testing": st = ServerAdapter.TestStatus.TESTING; break;
                case "ok":      st = ServerAdapter.TestStatus.OK;      break;
                case "fail":    st = ServerAdapter.TestStatus.FAIL;    break;
                default:        st = ServerAdapter.TestStatus.IDLE;
            }

            String serverId = event.serverId != null ? event.serverId : event.host;
            serverAdapter.updateServerStatus(serverId, st, event.detail);
        });

        // ════════════════════════════════════════════════════════════════════════
        // StatusBus для прогресса сканирования (НЕ для статуса VPN!)
        // ════════════════════════════════════════════════════════════════════════

        StatusBus.get().observe(this, event -> {
            if (event == null) return;

            FileLogger.d(TAG, "GlobalStatus: " + event.message + " (running=" + event.isRunning + ")");

            // Обновляем только прогресс сканирования
            viewModel.setIsWorking(event.isRunning);
            viewModel.setProgressTitle(event.message);
            viewModel.setLastStatusMessage(event.message);

            // НЕ вызываем refreshVpnStatus() здесь — это делает MainViewModel!
        });
    }

    // ── VPN управление ───────────────────────────────────────────────────────

    private void onConnectClicked(VlessServer server) {
        FileLogger.i(TAG, "onConnectClicked: " + server.host);
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
        FileLogger.i(TAG, "doStartVpn: " + server.host + ":" + server.port);
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
        FileLogger.i(TAG, "disconnectVpn");
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