package com.vlessvpn.app.network;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import androidx.annotation.NonNull;

import com.vlessvpn.app.service.AutoConnectManager;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

/**
 * WifiMonitor — стабильная версия без таймера (Android 13–16)
 *
 * Что делает:
 * ✔ мгновенно ловит LTE при переходе с Wi-Fi
 * ✔ мгновенно ловит Wi-Fi даже при активном VPN
 * ✔ не запускает VPN повторно после ручного отключения на LTE
 * ✔ не использует Handler
 * ✔ чистые логи
 */
public class WifiMonitor {

    private static final String TAG = "WifiMonitor";

    private static ConnectivityManager.NetworkCallback defaultCallback;
    private static ConnectivityManager.NetworkCallback wifiCallback;

    private static Context appContext;

    // защита от повторного отключения
    private static volatile boolean disconnectInProgress = false;

    // Отслеживаем тип последней физической сети (1 = Wi-Fi, 2 = Cellular)
    // Это предотвращает автозапуск VPN при ручном отключении (когда сеть просто "падает" на LTE)
    private static volatile int lastNetworkType = 0;

    // ============================================================
    // START
    // ============================================================

    public static void startMonitoring(Context context) {
        if (defaultCallback != null) return;

        appContext = context.getApplicationContext();

        ConnectivityManager cm =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return;

        ServerRepository repo = new ServerRepository(appContext);

        // Инициализируем текущее состояние при запуске монитора,
        // чтобы не триггерить ложное подключение, если мы УЖЕ на LTE
        lastNetworkType = isWifiConnected(appContext) ? 1 : 2;

        // ============================================================
        // DEFAULT CALLBACK (идеально ловит LTE)
        // ============================================================

        defaultCallback = new ConnectivityManager.NetworkCallback() {

            @Override
            public void onAvailable(@NonNull Network network) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) return;

                boolean isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);

                // Игнорируем сам VPN-туннель как физическую сеть
                if (isVpn) return;

                boolean isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                boolean isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);

                if (isWifi) {
                    lastNetworkType = 1; // Запоминаем, что мы на Wi-Fi
                } else if (isCellular) {
                    // Если мы перешли НА LTE именно С Wi-Fi
                    if (lastNetworkType == 1) {
                        if (repo.isAutoConnectOnWifiDisconnect() && !VpnTunnelService.isRunning) {
                            FileLogger.i(TAG, "Wi-Fi потерян -> переход на LTE. Запускаем VPN");
                            AutoConnectManager.startAutoConnect(appContext);
                        }
                    } else {
                        // Мы и так были на LTE (или юзер только что выключил VPN руками).
                        // Игнорируем, чтобы не создать вечный цикл переподключения.
                        // FileLogger.d(TAG, "Смена дефолтной сети на LTE (без перехода с Wi-Fi) — игнорируем");
                    }

                    lastNetworkType = 2; // Запоминаем, что теперь мы на LTE
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                //FileLogger.i(TAG, "Default Network потеряна");
            }
        };

        // ============================================================
        // WIFI CALLBACK (ловит Wi-Fi даже при активном VPN)
        // ============================================================

        NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        wifiCallback = new ConnectivityManager.NetworkCallback() {

            @Override
            public void onAvailable(@NonNull Network network) {
                //FileLogger.i(TAG, "Wi-Fi подключён");

                disconnectInProgress = false;

                AutoConnectManager.cancelAutoConnect();

                if (VpnTunnelService.isRunning && !disconnectInProgress) {

                    disconnectInProgress = true;

                    FileLogger.i(TAG, "Wi-Fi → отключаем VPN");

                    Intent i = new Intent(appContext, VpnTunnelService.class);
                    i.setAction(VpnTunnelService.ACTION_DISCONNECT);
                    appContext.startService(i);
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                //FileLogger.i(TAG, "Wi-Fi пропал");

                disconnectInProgress = false;
            }
        };

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(defaultCallback);
            } else {
                NetworkRequest req = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();

                cm.registerNetworkCallback(req, defaultCallback);
            }

            cm.registerNetworkCallback(wifiRequest, wifiCallback);

            //FileLogger.i(TAG, "WifiMonitor успешно запущен");

        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка старта WifiMonitor", e);
        }
    }

    // ============================================================
    // STOP
    // ============================================================

    public static void stopMonitoring() {

        if (appContext == null) return;

        ConnectivityManager cm =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return;

        try {

            if (defaultCallback != null) {
                cm.unregisterNetworkCallback(defaultCallback);
                defaultCallback = null;
            }

            if (wifiCallback != null) {
                cm.unregisterNetworkCallback(wifiCallback);
                wifiCallback = null;
            }

            FileLogger.i(TAG, "WifiMonitor остановлен");

        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка остановки WifiMonitor", e);
        }
    }

    // ============================================================
    // Проверка Wi-Fi
    // ============================================================

    public static boolean isWifiConnected(Context ctx) {

        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            Network activeNetwork = cm.getActiveNetwork();

            if (activeNetwork != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                return caps != null &&
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }

        } else {

            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);

                if (caps != null
                        && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true;
                }
            }
        }

        return false;
    }
}