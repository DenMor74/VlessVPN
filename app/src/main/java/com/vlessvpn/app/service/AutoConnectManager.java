package com.vlessvpn.app.service;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AutoConnectManager — авто-подключение VPN с перебором серверов
 */
public class AutoConnectManager {

    private static final String TAG = "AutoConnectManager";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int VPN_STARTUP_WAIT_MS = 15000;
    private static final int SERVER_SWITCH_DELAY_MS = 3000;

    private static ExecutorService executor = Executors.newSingleThreadExecutor();
    private static Handler mainHandler = new Handler(Looper.getMainLooper());

    private static int currentServerIndex = 0;
    private static List<VlessServer> serverList = null;
    private static volatile boolean isFailoverInProgress = false;
    private static volatile boolean shouldCancelConnection = false;

    public static void startAutoConnect(Context context) {
        if (isFailoverInProgress) {
            FileLogger.d(TAG, "Перебор уже выполняется — игнорируем запрос");
            return;
        }

        executor.execute(() -> {
            isFailoverInProgress = true;
            shouldCancelConnection = false;

            try {
                ServerRepository repo = new ServerRepository(context);
                serverList = repo.getTopServersSync();

                if (serverList == null || serverList.isEmpty()) {
                    FileLogger.w(TAG, "Нет серверов в списке для авто-подключения");
                    mainHandler.post(() ->
                            com.vlessvpn.app.util.StatusBus.post("⚠️ Нет серверов для подключения"));
                    return;
                }

                FileLogger.i(TAG, "═══════════════════════════════════════");
                FileLogger.i(TAG, "Начинаем авто-подключение. Серверов: " + serverList.size());
                FileLogger.i(TAG, "═══════════════════════════════════════");

                // 1. Сначала пробуем последний рабочий сервер
                VlessServer lastServer = repo.getLastWorkingServer();
                if (lastServer != null) {
                    FileLogger.i(TAG, "Пробуем последний рабочий: " + lastServer.host);
                    if (tryConnectAndVerify(context, lastServer)) {
                        repo.saveLastWorkingServer(lastServer);
                        return;
                    }
                }

                // 2. Перебираем по списку
                currentServerIndex = 0;
                tryNextServer(context);

            } finally {
                isFailoverInProgress = false;
            }
        });
    }

    public static void cancelAutoConnect() {
        FileLogger.i(TAG, "Отмена авто-подключения (WiFi восстановлен)");
        shouldCancelConnection = true;
        isFailoverInProgress = false;
    }

    private static void tryNextServer(Context context) {
        if (shouldCancelConnection) {
            FileLogger.i(TAG, "Подключение отменено — WiFi восстановлен");
            isFailoverInProgress = false;
            return;
        }

        if (serverList == null || currentServerIndex >= serverList.size()) {
            FileLogger.w(TAG, "═══════════════════════════════════════");
            FileLogger.w(TAG, "Список серверов исчерпан. Повтор через 5 минут...");
            FileLogger.w(TAG, "═══════════════════════════════════════");

            mainHandler.post(() ->
                    com.vlessvpn.app.util.StatusBus.post("⚠️ Нет рабочих серверов. Повтор через 5 мин..."));

            mainHandler.postDelayed(() -> {
                if (!shouldCancelConnection) {
                    currentServerIndex = 0;
                    isFailoverInProgress = false;
                    startAutoConnect(context);
                }
            }, 5 * 60 * 1000L);
            return;
        }

        VlessServer server = serverList.get(currentServerIndex);
        FileLogger.i(TAG, "═══════════════════════════════════════");
        FileLogger.i(TAG, "Пробуем сервер [" + currentServerIndex + "]: " + server.host + ":" + server.port);
        FileLogger.i(TAG, "═══════════════════════════════════════");

        mainHandler.post(() ->
                com.vlessvpn.app.util.StatusBus.post("🔄 Подключение: " + server.remark));

        if (tryConnectAndVerify(context, server)) {
            FileLogger.i(TAG, "✅ Успешное подключение на сервере [" + currentServerIndex + "]");
            isFailoverInProgress = false;
        } else {
            FileLogger.w(TAG, "❌ Сервер [" + currentServerIndex + "] не работает, пробуем следующий...");
            currentServerIndex++;
            mainHandler.postDelayed(() -> tryNextServer(context), SERVER_SWITCH_DELAY_MS);
        }
    }

    private static boolean tryConnectAndVerify(Context context, VlessServer server) {
        if (shouldCancelConnection) {
            FileLogger.i(TAG, "Подключение отменено во время tryConnectAndVerify");
            return false;
        }

        if (VpnTunnelService.isRunning) {
            FileLogger.d(TAG, "VPN уже запущен, проверяем текущий сервер...");
            boolean alreadyWorking = performConnectivityCheck();
            if (alreadyWorking) {
                FileLogger.i(TAG, "Текущий VPN работает — не нужно переключаться");
                return true;
            }
            FileLogger.d(TAG, "Текущий VPN не работает — переподключаем");
        }

        Intent intent = new Intent(context, VpnTunnelService.class);
        intent.setAction(VpnTunnelService.ACTION_CONNECT);
        intent.putExtra(VpnTunnelService.EXTRA_SERVER, new Gson().toJson(server));
        intent.putExtra(VpnTunnelService.EXTRA_AUTO_CONNECT, true);

        FileLogger.i(TAG, "Запускаем VpnTunnelService...");

        // ════════════════════════════════════════════════════════════════════════
        // ← ИСПРАВЛЕНО: Используем startForegroundService для Android 8+
        // ════════════════════════════════════════════════════════════════════════
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
            FileLogger.d(TAG, "Запуск через startForegroundService (Android 8+)");
        } else {
            context.startService(intent);
            FileLogger.d(TAG, "Запуск через startService (Android 7 и ниже)");
        }

        FileLogger.i(TAG, "Ожидаем поднятие VPN (" + (VPN_STARTUP_WAIT_MS / 1000) + " сек)...");
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < VPN_STARTUP_WAIT_MS) {
            try {
                Thread.sleep(500);

                if (shouldCancelConnection) {
                    FileLogger.i(TAG, "Подключение отменено во время ожидания VPN");
                    Intent disconnectIntent = new Intent(context, VpnTunnelService.class);
                    disconnectIntent.setAction(VpnTunnelService.ACTION_DISCONNECT);
                    context.startService(disconnectIntent);
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                FileLogger.e(TAG, "Прервано ожидание VPN");
                return false;
            }
        }

        if (!VpnTunnelService.isRunning) {
            FileLogger.w(TAG, "Сервис остановился во время ожидания");
            return false;
        }

        FileLogger.i(TAG, "Проверяем доступность интернета...");
        boolean isWorking = performConnectivityCheck();

        if (isWorking) {
            FileLogger.i(TAG, "✅ Сервер работает: " + server.host);
            mainHandler.post(() ->
                    com.vlessvpn.app.util.StatusBus.post("✅ Подключено: " + server.remark));

            ServerRepository repo = new ServerRepository(context);
            repo.saveLastWorkingServer(server);
            return true;
        } else {
            FileLogger.w(TAG, "❌ Сервер не отвечает: " + server.host);
            mainHandler.post(() ->
                    com.vlessvpn.app.util.StatusBus.post("❌ Не работает: " + server.remark));

            FileLogger.i(TAG, "Отключаем VPN перед переключением...");
            Intent disconnectIntent = new Intent(context, VpnTunnelService.class);
            disconnectIntent.setAction(VpnTunnelService.ACTION_DISCONNECT);
            context.startService(disconnectIntent);

            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {}

            return false;
        }
    }

    private static boolean performConnectivityCheck() {
        try {
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                    new java.net.InetSocketAddress("127.0.0.1", 10808));
            java.net.URL url = new java.net.URL("http://cp.cloudflare.com/generate_204");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection(proxy);

            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CONNECT_TIMEOUT_MS);

            int code = conn.getResponseCode();
            boolean ok = (code == 204 || code == 200);

            FileLogger.d(TAG, "Connectivity check: HTTP " + code + " → " + (ok ? "OK" : "FAIL"));
            conn.disconnect();

            return ok;
        } catch (Exception e) {
            FileLogger.d(TAG, "Connectivity check FAIL: " + e.getMessage());
            return false;
        }
    }

    public static void resetServerIndex() {
        currentServerIndex = 0;
        FileLogger.d(TAG, "Сброшен индекс перебора серверов");
    }

    public static boolean isFailoverInProgress() {
        return isFailoverInProgress;
    }
}