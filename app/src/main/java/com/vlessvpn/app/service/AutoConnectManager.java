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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AutoConnectManager — авто-подключение VPN с перебором серверов
 */
public class AutoConnectManager {

    private static final String TAG = "AutoConnectManager";
    private static final int VPN_STARTUP_WAIT_MS = 15000;
    private static final int SERVER_SWITCH_DELAY_MS = 1000;

    private static ExecutorService executor = Executors.newSingleThreadExecutor();
    private static Handler mainHandler = new Handler(Looper.getMainLooper());

    private static int currentServerIndex = 0;
    private static List<VlessServer> serverList = null;
    private static volatile boolean isFailoverInProgress = false;
    private static volatile boolean shouldCancelConnection = false;

    private static volatile CountDownLatch verificationLatch;
    private static volatile boolean verificationResult;

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

                FileLogger.i(TAG, "Начинаем авто-подключение. Серверов: " + serverList.size());

                VlessServer lastServer = repo.getLastWorkingServer();
                if (lastServer != null) {
                    FileLogger.i(TAG, "Пробуем последний рабочий: " + lastServer.host);
                    if (tryConnectAndVerify(context, lastServer)) {
                        return;
                    }
                }

                currentServerIndex = 0;
                tryNextServer(context);

            } finally {
                // Если цикл завершился (или был прерван), сбрасываем флаг
                if (!shouldCancelConnection && (serverList == null || currentServerIndex >= serverList.size())) {
                    isFailoverInProgress = false;
                }
            }
        });
    }

    public static void cancelAutoConnect() {
        FileLogger.i(TAG, "Отмена авто-подключения (ручное отключение или смена сети)");
        shouldCancelConnection = true;
        isFailoverInProgress = false;
        if (verificationLatch != null) {
            verificationLatch.countDown();
        }
        // Очищаем отложенные задачи (например, таймер на 1 минуту)
        mainHandler.removeCallbacksAndMessages(null);
    }

    public static void reportVerificationResult(boolean success) {
        verificationResult = success;
        if (verificationLatch != null) {
            verificationLatch.countDown();
        }
    }

    private static void tryNextServer(Context context) {
        if (shouldCancelConnection) {
            isFailoverInProgress = false;
            return;
        }

        if (serverList == null || currentServerIndex >= serverList.size()) {
            FileLogger.w(TAG, "═══════════════════════════════════════");
            FileLogger.w(TAG, "Список серверов исчерпан. Повтор через 1 минуту...");
            FileLogger.w(TAG, "═══════════════════════════════════════");

            mainHandler.post(() ->
                    com.vlessvpn.app.util.StatusBus.post("⚠️ Нет рабочих серверов. Повтор через 1 мин..."));

            // Отключаем мертвый VPN, но передаем флаг system_cleanup,
            // чтобы сервис не вызвал cancelAutoConnect() и не сбросил наш таймер
            Intent disconnectIntent = new Intent(context, VpnTunnelService.class);
            disconnectIntent.setAction(VpnTunnelService.ACTION_DISCONNECT);
            disconnectIntent.putExtra("system_cleanup", true);
            context.startService(disconnectIntent);

            mainHandler.postDelayed(() -> {
                if (!shouldCancelConnection) {
                    currentServerIndex = 0;
                    isFailoverInProgress = false;
                    startAutoConnect(context);
                }
            }, 60 * 1000L);
            return;
        }

        VlessServer server = serverList.get(currentServerIndex);
        FileLogger.i(TAG, "═══════════════════════════════════════");
        FileLogger.i(TAG, "Пробуем сервер[" + currentServerIndex + "]: " + server.host + ":" + server.port);
        FileLogger.i(TAG, "═══════════════════════════════════════");

        mainHandler.post(() ->
                com.vlessvpn.app.util.StatusBus.post("🔄 Подключение: " + server.remark));

        boolean success = tryConnectAndVerify(context, server);

        if (shouldCancelConnection) {
            FileLogger.i(TAG, "Перебор прерван пользователем.");
            isFailoverInProgress = false;
            return;
        }

        if (success) {
            FileLogger.i(TAG, "✅ Успешное подключение на сервере [" + currentServerIndex + "]");
            isFailoverInProgress = false;
        } else {
            FileLogger.w(TAG, "❌ Сервер[" + currentServerIndex + "] не работает, пробуем следующий...");
            currentServerIndex++;
            mainHandler.postDelayed(() -> tryNextServer(context), SERVER_SWITCH_DELAY_MS);
        }
    }

    private static boolean tryConnectAndVerify(Context context, VlessServer server) {
        if (shouldCancelConnection) return false;

        if (VpnTunnelService.isRunning) {
            if (VpnTunnelService.checkTunnelProxyFastSync()) {
                FileLogger.i(TAG, "Текущий VPN работает — переключение не требуется");
                return true;
            }
        }

        verificationLatch = new CountDownLatch(1);
        verificationResult = false;

        Intent intent = new Intent(context, VpnTunnelService.class);
        intent.setAction(VpnTunnelService.ACTION_CONNECT);
        intent.putExtra(VpnTunnelService.EXTRA_SERVER, new Gson().toJson(server));
        intent.putExtra(VpnTunnelService.EXTRA_AUTO_CONNECT, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }

        try {
            boolean awaited = verificationLatch.await(VPN_STARTUP_WAIT_MS, TimeUnit.MILLISECONDS);
            if (shouldCancelConnection) return false;

            if (awaited && verificationResult) {
                mainHandler.post(() -> com.vlessvpn.app.util.StatusBus.post("✅ Подключено: " + server.remark));
                return true;
            } else {
                mainHandler.post(() -> com.vlessvpn.app.util.StatusBus.post("❌ Не работает: " + server.remark));
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void resetServerIndex() {
        currentServerIndex = 0;
    }

    public static boolean isFailoverInProgress() {
        return isFailoverInProgress;
    }
}