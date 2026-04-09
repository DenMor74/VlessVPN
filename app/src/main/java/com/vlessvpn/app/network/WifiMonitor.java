package com.vlessvpn.app.network;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import com.vlessvpn.app.service.AutoConnectManager;
import com.vlessvpn.app.service.VpnController;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

/**
 * WifiMonitor — мониторинг Wi-Fi / LTE.
 * Теперь всё управление VPN — через VpnController.
 */
public class WifiMonitor {

    private static final String TAG = "WifiMonitor";
    private static final long WIFI_LOST_DELAY_MS = 2_000L;

    private static ConnectivityManager.NetworkCallback defaultCallback;
    private static ConnectivityManager.NetworkCallback wifiSpecificCallback;

    private static Context appContext;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static volatile boolean wifiLostPending = false;

    public static void startMonitoring(Context context) {
        if (defaultCallback != null) return;
        appContext = context.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        ServerRepository repo = new ServerRepository(appContext);

        FileLogger.i(TAG, "WifiMonitor старт. Текущий Wi-Fi: " + isWifiConnected(appContext));

        defaultCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                FileLogger.i(TAG, "onAvailable");

                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) return;

                boolean isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                boolean isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);

                if (isVpn) return;

                VpnController controller = VpnController.getInstance(appContext);

                if (isWifi) {
                    FileLogger.i(TAG, "Default Network: Wi-Fi подключён");
                    wifiLostPending = false;
                    handler.removeCallbacks(onWifiLostRunnable);

                    controller.resetManualDisconnectFlag();   // ← сбрасываем флаг при появлении Wi-Fi

                    if (repo.isAutoConnectOnWifiDisconnect() && VpnTunnelService.isRunning) {
                        FileLogger.i(TAG, "Wi-Fi появился → отключаем VPN");
                        controller.disconnect(false);
                    }
                }
                else if (isCellular) {
                    FileLogger.i(TAG, "Default Network: LTE активен");

                    // НЕ запускаем авто-подключение, если пользователь вручную отключил
                    if (repo.isAutoConnectOnWifiDisconnect() &&
                            !VpnTunnelService.isRunning &&
                            !controller.isUserManuallyDisconnected()) {

                        FileLogger.i(TAG, "LTE → запускаем авто-подключение");
                        controller.startAutoConnect();
                    } else if (controller.isUserManuallyDisconnected()) {
                        FileLogger.i(TAG, "Пользователь вручную отключил VPN — авто-подключение заблокировано на LTE");
                    }
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (repo.isAutoConnectOnWifiDisconnect()) {
                    FileLogger.w(TAG, "Default Network потеряна (возможно Wi-Fi отвалился). Ждём LTE...");
                    wifiLostPending = true;
                    handler.removeCallbacks(onWifiLostRunnable);
                    handler.postDelayed(onWifiLostRunnable, WIFI_LOST_DELAY_MS);
                }
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(defaultCallback);
            } else {
                NetworkRequest defaultReq = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                cm.registerNetworkCallback(defaultReq, defaultCallback);
            }
            FileLogger.i(TAG, "WifiMonitor успешно запущен");
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка старта WifiMonitor", e);
        }
        // Регистрируем отдельный callback только на Wi-Fi (для надёжного отключения VPN)
        registerWifiSpecificCallback();
    }

    private static void registerWifiSpecificCallback() {
        if (wifiSpecificCallback != null) return;

        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        wifiSpecificCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                //FileLogger.i(TAG, "Wi-Fi появился");
                wifiLostPending = false;
                handler.removeCallbacks(onWifiLostRunnable);

                ServerRepository repo = new ServerRepository(appContext);
                if (repo.isAutoConnectOnWifiDisconnect() && VpnTunnelService.isRunning) {
                    FileLogger.i(TAG, "Wi-Fi → отключаем VPN");
                    VpnController.getInstance(appContext).disconnect(false);
                }

                // Сбрасываем флаг ручного отключения
                VpnController.getInstance(appContext).resetManualDisconnectFlag();
            }

            @Override
            public void onLost(@NonNull Network network) {
                //FileLogger.w(TAG, "Wi-Fi Specific Callback: onLost");
                // Здесь ничего не запускаем — отключение Wi-Fi обрабатывает default callback
            }
        };

        try {
            cm.registerNetworkCallback(wifiRequest, wifiSpecificCallback);
            FileLogger.i(TAG, "Wi-Fi specific callback успешно зарегистрирован");
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка регистрации Wi-Fi specific callback", e);
        }
    }


    private static final Runnable onWifiLostRunnable = () -> {
        if (!wifiLostPending) return;
        wifiLostPending = false;

        VpnController controller = VpnController.getInstance(appContext);

        if (VpnTunnelService.isRunning ||
                AutoConnectManager.isFailoverInProgress() ||
                controller.isUserManuallyDisconnected()) {
            return;
        }

        if (hasCellularInternet(appContext)) {
            FileLogger.i(TAG, "Страховка: LTE обнаружен → запускаем VPN");
            controller.startAutoConnect();
        }
    };

    public static void stopMonitoring() {
        handler.removeCallbacks(onWifiLostRunnable);
        if (appContext == null || defaultCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            cm.unregisterNetworkCallback(defaultCallback);
            defaultCallback = null;
            FileLogger.i(TAG, "WifiMonitor остановлен");
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка остановки WifiMonitor", e);
        }
        if (wifiSpecificCallback != null) {
            try {
                cm.unregisterNetworkCallback(wifiSpecificCallback);
                wifiSpecificCallback = null;
                FileLogger.i(TAG, "Wi-Fi specific callback остановлен");
            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка остановки Wi-Fi specific callback", e);
            }
        }
    }

    private static boolean hasCellularInternet(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isWifiConnected(Context ctx) {
        // (оставлен без изменений — твой оригинальный метод)
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
        } else {
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true;
                }
            }
        }
        return false;
    }
}