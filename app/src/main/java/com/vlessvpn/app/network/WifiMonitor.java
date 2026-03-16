package com.vlessvpn.app.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Надежный монитор Wi-Fi.
 * Будится системой через Manifest (android.net.wifi.STATE_CHANGE).
 */
public class WifiMonitor extends BroadcastReceiver {

    private static final String TAG = "WifiMonitor";
    private static final String PREFS_NAME = "wifi_monitor_prefs";
    private static final String KEY_WAS_WIFI = "was_wifi_connected";

    // Пул потоков для защиты от краша "Cannot access database on the main thread"
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;

        // Слушаем только официальный системный бродкаст Wi-Fi
        if (!WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) return;

        // Используем ApplicationContext для безопасности фонового потока
        final Context appContext = context.getApplicationContext();

        // Запускаем в фоне, чтобы не заблокировать Main Thread (иначе будет краш Room DB)
        executor.execute(() -> {
            ServerRepository repo = new ServerRepository(appContext);
            boolean autoConnectEnabled = repo.isAutoConnectOnWifiDisconnect();

            if (!autoConnectEnabled) return; // Настройка выключена

            boolean isWifiConnected = checkWifiConnected(appContext);
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean wasWifiConnected = prefs.getBoolean(KEY_WAS_WIFI, false);

            if (isWifiConnected != wasWifiConnected) {
                prefs.edit().putBoolean(KEY_WAS_WIFI, isWifiConnected).apply();

                if (!isWifiConnected) {
                    FileLogger.w(TAG, "🔴 Wi-Fi отключен! Запускаем VPN-защиту...");
                    startVpn(appContext, repo);
                } else {
                    FileLogger.i(TAG, "🟢 Wi-Fi подключен! Отключаем VPN...");
                    if (VpnTunnelService.isRunning) {
                        stopVpn(appContext);
                    }
                }
            }
        });
    }

    private boolean checkWifiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        return false;
    }

    private void startVpn(Context context, ServerRepository repo) {
        if (VpnTunnelService.isRunning) return;

        List<VlessServer> topServers = repo.getTopServersSync();
        if (topServers.isEmpty()) {
            FileLogger.e(TAG, "Нет серверов для автоподключения!");
            return;
        }

        VlessServer bestServer = topServers.get(0);
        FileLogger.i(TAG, "Авто-запуск с сервером: " + bestServer.host);

        Intent vpnIntent = new Intent(context, VpnTunnelService.class);
        vpnIntent.setAction(VpnTunnelService.ACTION_CONNECT);
        vpnIntent.putExtra(VpnTunnelService.EXTRA_SERVER, new Gson().toJson(bestServer));

        ContextCompat.startForegroundService(context, vpnIntent);
    }

    private void stopVpn(Context context) {
        Intent stopIntent = new Intent(context, VpnTunnelService.class);
        stopIntent.setAction(VpnTunnelService.ACTION_DISCONNECT);
        try {
            // Используем безопасный startService, чтобы избежать краша ForegroundService
            context.startService(stopIntent);
        } catch (IllegalStateException e) {
            FileLogger.w(TAG, "Используем stopService напрямую: " + e.getMessage());
            context.stopService(new Intent(context, VpnTunnelService.class));
        }
    }
}