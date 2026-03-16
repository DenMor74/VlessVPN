package com.vlessvpn.app.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import androidx.annotation.NonNull;

import com.vlessvpn.app.service.AutoConnectManager;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

/**
 * WifiMonitor — мониторинг подключения/отключения Wi-Fi.
 * Использует NetworkCallback (работает на Android 8–16).
 * При отключении Wi-Fi вызывает AutoConnectManager.startAutoConnect().
 */
public class WifiMonitor {

    private static final String TAG = "WifiMonitor";
    private static ConnectivityManager.NetworkCallback callback;
    private static Context appContext;

    public static void startMonitoring(Context context) {
        if (callback != null) return;

        appContext = context.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        ServerRepository repo = new ServerRepository(appContext);

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        callback = new ConnectivityManager.NetworkCallback() {
            private boolean wasWifiConnected = true;

            @Override
            public void onAvailable(@NonNull Network network) {
                wasWifiConnected = true;
                FileLogger.i(TAG, "Wi-Fi подключён → отменяем авто-подключение");
                AutoConnectManager.cancelAutoConnect();
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (!wasWifiConnected) return;
                wasWifiConnected = false;

                if (!repo.isAutoConnectOnWifiDisconnect()) {
                    FileLogger.d(TAG, "Авто-подключение при отключении WiFi выключено в настройках");
                    return;
                }

                if (!VpnTunnelService.isRunning && !AutoConnectManager.isFailoverInProgress()) {
                    FileLogger.w(TAG, "Wi-Fi ОТКЛЮЧЁН! Запускаем AutoConnectManager");
                    AutoConnectManager.startAutoConnect(appContext);
                }
            }
        };

        cm.registerNetworkCallback(request, callback);
        FileLogger.i(TAG, "WifiMonitor запущен (NetworkCallback)");
    }

    public static void stopMonitoring() {
        if (callback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(callback);
                callback = null;
                FileLogger.i(TAG, "WifiMonitor остановлен");
            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка остановки мониторинга", e);
            }
        }
    }
}