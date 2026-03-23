package com.vlessvpn.app.network;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;

import com.vlessvpn.app.service.AutoConnectManager;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

/**
 * WifiMonitor — мониторинг сетевых переходов.
 *
 * Улучшения:
 * 1. Ждём появления мобильного интернета (VALIDATED) перед запуском VPN
 * 2. Задержка после onLost — LTE поднимается ~1-2 сек
 * 3. wasWifiConnected инициализируется реальным состоянием WiFi
 */
public class WifiMonitor {

    private static final String TAG = "WifiMonitor";
    private static final long WIFI_LOST_DELAY_MS = 2_000L; // ждём LTE после потери WiFi

    private static ConnectivityManager.NetworkCallback wifiCallback;
    private static ConnectivityManager.NetworkCallback defaultCallback;
    private static Context appContext;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    // Флаги состояния
    private static volatile boolean wifiLostPending  = false; // WiFi потерян, ждём LTE
    private static volatile boolean hasInternet      = false; // есть любой интернет

    public static void startMonitoring(Context context) {
        if (wifiCallback != null) return;

        appContext = context.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        ServerRepository repo = new ServerRepository(appContext);

        // Инициализируем реальным состоянием WiFi
        boolean wifiNow = isWifiConnected(appContext);
        hasInternet = hasAnyInternet(appContext);
        FileLogger.i(TAG, "WifiMonitor старт: wifi=" + wifiNow + " internet=" + hasInternet);

        // ── Callback 1: слушаем WiFi ─────────────────────────────────────────
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                FileLogger.i(TAG, "Wi-Fi подключён → отменяем авто-подключение");
                wifiLostPending = false;
                handler.removeCallbacks(onWifiLostRunnable);
                AutoConnectManager.cancelAutoConnect();

                // Если VPN запущен через LTE — отключаем (вернулись на WiFi)
                if (VpnTunnelService.isRunning) {
                    Intent i = new Intent(appContext, VpnTunnelService.class);
                    i.setAction(VpnTunnelService.ACTION_DISCONNECT);
                    appContext.startService(i);
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                FileLogger.w(TAG, "Wi-Fi ОТКЛЮЧЁН — ждём " + WIFI_LOST_DELAY_MS + "мс");
                if (!repo.isAutoConnectOnWifiDisconnect()) {
                    FileLogger.d(TAG, "Авто-подключение выключено в настройках");
                    return;
                }
                wifiLostPending = true;
                // Ждём немного — LTE может подняться сам
                handler.removeCallbacks(onWifiLostRunnable);
                handler.postDelayed(onWifiLostRunnable, WIFI_LOST_DELAY_MS);
            }
        };

        // ── Callback 2: слушаем появление любого интернета (LTE) ────────────
        defaultCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) return;
                boolean isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                if (isVpn) return; // наш VPN — игнорируем

                hasInternet = true;
                FileLogger.d(TAG, "Интернет появился (не VPN)");

                // Если WiFi потерян и ждём LTE — запускаем VPN теперь
                if (wifiLostPending && !VpnTunnelService.isRunning
                        && !AutoConnectManager.isFailoverInProgress()) {
                    FileLogger.i(TAG, "LTE появился после потери WiFi → запускаем VPN");
                    wifiLostPending = false;
                    handler.removeCallbacks(onWifiLostRunnable);
                    AutoConnectManager.startAutoConnect(appContext);
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                // Проверяем остался ли ещё какой-то интернет
                hasInternet = hasAnyInternet(appContext);
                FileLogger.d(TAG, "Сеть потеряна, hasInternet=" + hasInternet);
            }
        };

        // Регистрируем оба callback
        NetworkRequest wifiReq = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        cm.registerNetworkCallback(wifiReq, wifiCallback);

        // DEFAULT — любая сеть с интернетом (кроме VPN)
        NetworkRequest defaultReq = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
        cm.registerNetworkCallback(defaultReq, defaultCallback);

        FileLogger.i(TAG, "WifiMonitor запущен (WiFi + DEFAULT callbacks)");
    }

    // Запускаем VPN если интернет уже есть через LTE
    private static final Runnable onWifiLostRunnable = () -> {
        if (!wifiLostPending) return;
        wifiLostPending = false;

        if (VpnTunnelService.isRunning || AutoConnectManager.isFailoverInProgress()) return;

        if (hasAnyInternet(appContext)) {
            FileLogger.i(TAG, "WiFi потерян, LTE уже есть → запускаем VPN");
            AutoConnectManager.startAutoConnect(appContext);
        } else {
            FileLogger.w(TAG, "WiFi потерян, нет интернета — ждём появления LTE");
            // defaultCallback.onAvailable запустит VPN когда LTE появится
        }
    };

    public static void stopMonitoring() {
        handler.removeCallbacks(onWifiLostRunnable);
        if (appContext == null) return;
        ConnectivityManager cm = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            if (wifiCallback    != null) { cm.unregisterNetworkCallback(wifiCallback);    wifiCallback = null; }
            if (defaultCallback != null) { cm.unregisterNetworkCallback(defaultCallback); defaultCallback = null; }
            FileLogger.i(TAG, "WifiMonitor остановлен");
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка остановки", e);
        }
    }

    /** Есть ли хоть какой-то интернет (не VPN) */
    private static boolean hasAnyInternet(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return true;
            }
        }
        return false;
    }

    /** Подключён ли WiFi с интернетом */
    public static boolean isWifiConnected(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return true;
            }
        }
        return false;
    }
}
