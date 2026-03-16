package com.vlessvpn.app.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;

import com.vlessvpn.app.service.AutoConnectManager;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

/**
 * WifiMonitor — отслеживает ПОДКЛЮЧЕНИЕ/ОТКЛЮЧЕНИЕ от WiFi сети
 *
 * Использует NetworkCallback для Android 10+ (надёжнее BroadcastReceiver)
 */
public class WifiMonitor extends BroadcastReceiver {

    private static final String TAG = "WifiMonitor";

    private static volatile boolean isConnectedToWifiNetwork = false;
    private static volatile long lastStateChangeTime = 0;
    private static final int DEBOUNCE_MS = 5000;

    // NetworkCallback для Android 10+
    private static ConnectivityManager.NetworkCallback networkCallback;
    private static boolean callbackRegistered = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;

        FileLogger.d(TAG, "=== onReceive: action=" + action);

        ServerRepository repo = new ServerRepository(context);
        boolean autoConnectEnabled = repo.isAutoConnectOnWifiDisconnect();

        FileLogger.d(TAG, "Авто-режим включён: " + autoConnectEnabled);

        if (!autoConnectEnabled) {
            FileLogger.d(TAG, "Авто-режим выключен — игнорируем событие");
            return;
        }

        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
            FileLogger.d(TAG, "Получен CONNECTIVITY_CHANGE");
            handleConnectivityChange(context);
        } else if (android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
            FileLogger.d(TAG, "Получен NETWORK_STATE_CHANGED");
            handleWifiNetworkStateChange(context, intent);
        }
    }

    /**
     * Зарегистрировать NetworkCallback для надёжного мониторинга (Android 10+)
     * Вызывать из Application или MainActivity при старте
     */
    public static void registerNetworkCallback(Context context) {
        if (callbackRegistered) {
            FileLogger.d(TAG, "NetworkCallback уже зарегистрирован");
            return;
        }

        ServerRepository repo = new ServerRepository(context);
        boolean autoConnectEnabled = repo.isAutoConnectOnWifiDisconnect();

        if (!autoConnectEnabled) {
            FileLogger.d(TAG, "Авто-режим выключен — не регистрируем NetworkCallback");
            return;
        }

        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    FileLogger.i(TAG, "🟢 NetworkCallback: WiFi сеть доступна");
                    handleWifiStateChange(context, true);  // ← Теперь работает
                }
            }

            @Override
            public void onLost(Network network) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    FileLogger.i(TAG, "🔴 NetworkCallback: WiFi сеть потеряна");
                    handleWifiStateChange(context, false);  // ← Теперь работает
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    FileLogger.d(TAG, "NetworkCallback: WiFi capabilities changed, hasInternet=" + hasInternet);
                    handleWifiStateChange(context, hasInternet);  // ← Теперь работает
                }
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        try {
            cm.registerNetworkCallback(request, networkCallback);
            callbackRegistered = true;
            FileLogger.i(TAG, "✅ NetworkCallback зарегистрирован");
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка регистрации NetworkCallback: " + e.getMessage());
        }
    }

    /**
     * Отрегистрировать NetworkCallback при остановке приложения
     */
    public static void unregisterNetworkCallback(Context context) {
        if (!callbackRegistered) return;

        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        try {
            cm.unregisterNetworkCallback(networkCallback);
            callbackRegistered = false;
            FileLogger.i(TAG, "NetworkCallback отрегистрирован");
        } catch (Exception e) {
            FileLogger.w(TAG, "Ошибка от регистрации NetworkCallback: " + e.getMessage());
        }
    }

    private void handleConnectivityChange(Context context) {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        boolean nowConnectedToWifi = isWifiConnected(cm);
        FileLogger.d(TAG, "handleConnectivityChange: WiFi подключён = " + nowConnectedToWifi);

        handleWifiStateChange(context, nowConnectedToWifi);
    }

    private void handleWifiNetworkStateChange(Context context, Intent intent) {
        NetworkInfo networkInfo = intent.getParcelableExtra(
                android.net.wifi.WifiManager.EXTRA_NETWORK_INFO);

        if (networkInfo == null) {
            FileLogger.d(TAG, "handleWifiNetworkStateChange: networkInfo = null");
            return;
        }

        boolean connected = networkInfo.isConnected();
        FileLogger.d(TAG, "handleWifiNetworkStateChange: connected = " + connected);

        handleWifiStateChange(context, connected);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ← ИСПРАВЛЕНО: добавлен static для вызова из статического контекста
    // ════════════════════════════════════════════════════════════════════════
    private static synchronized void handleWifiStateChange(Context context, boolean connected) {
        long now = System.currentTimeMillis();

        // Защита от дребезга
        if (now - lastStateChangeTime < DEBOUNCE_MS) {
            FileLogger.d(TAG, "Пропускаем событие (debounce): прошло " + (now - lastStateChangeTime) + " мс");
            return;
        }

        if (connected != isConnectedToWifiNetwork) {
            lastStateChangeTime = now;
            isConnectedToWifiNetwork = connected;

            FileLogger.i(TAG, "══════════════════════════════════════");

            if (!connected) {
                FileLogger.i(TAG, "⚠️ ПОТЕРЯНО подключение к WiFi");
                FileLogger.i(TAG, "→ Запускаем AutoConnectManager");
                AutoConnectManager.startAutoConnect(context);
            } else {
                FileLogger.i(TAG, "✅ ВОССТАНОВЛЕНО подключение к WiFi");
                FileLogger.i(TAG, "→ Останавливаем VPN");
                stopVpnService(context);  // ← Вызов статического метода
            }

            FileLogger.i(TAG, "══════════════════════════════════════");
        } else {
            FileLogger.d(TAG, "Состояние не изменилось (connected=" + connected + ")");
        }
    }

    private boolean isWifiConnected(ConnectivityManager cm) {
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            if (caps != null) {
                boolean isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                FileLogger.d(TAG, "isWifiConnected: isWifi=" + isWifi + ", hasInternet=" + hasInternet);
                return isWifi && hasInternet;
            }
        }
        FileLogger.d(TAG, "isWifiConnected: false (no active network or capabilities)");
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ← ИСПРАВЛЕНО: static для вызова из handleWifiStateChange()
    // ════════════════════════════════════════════════════════════════════════
    private static void stopVpnService(Context context) {
        FileLogger.i(TAG, "stopVpnService: отправляем ACTION_DISCONNECT");

        // Отменяем авто-подключение если WiFi восстановился
        AutoConnectManager.cancelAutoConnect();

        Intent stopIntent = new Intent(context, VpnTunnelService.class);
        stopIntent.setAction(VpnTunnelService.ACTION_DISCONNECT);
        context.startService(stopIntent);
    }
}