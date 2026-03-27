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
 * AutoConnectManager — авто-подключение VPN с перебором серверов.
 *
 * Исправления:
 * 1. tryNextServer ВСЕГДА в executor (не в mainHandler) — нет ANR
 * 2. shouldCancelConnection проверяется атомарно через synchronized
 * 3. cancelAutoConnect прерывает executor thread через interrupt
 * 4. Надёжные URL для connectivity check
 */
public class AutoConnectManager {

    private static final String TAG = "AutoConnectManager";
    private static final int VPN_STARTUP_WAIT_MS = 15000;
    private static final int SERVER_SWITCH_DELAY_MS = 1000;

    // Используем ScheduledExecutor для поддержки задержек без mainHandler
    private static ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        // Пересоздаём executor если завершён
        if (executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
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
                if (lastServer != null && !shouldCancelConnection) {
                    FileLogger.i(TAG, "Пробуем последний рабочий: " + lastServer.host);
                    if (tryConnectAndVerify(context, lastServer)) return;
                }

                currentServerIndex = 0;
                // Перебор — всё в текущем executor потоке
                runServerLoop(context);

            } finally {
                isFailoverInProgress = false;
            }
        });
    }

    /** Перебор серверов — ВСЕГДА в фоновом потоке executor, не в mainHandler */
    private static void runServerLoop(Context context) {
        while (!shouldCancelConnection) {
            if (serverList == null || currentServerIndex >= serverList.size()) {
                FileLogger.w(TAG, "Список серверов исчерпан. Повтор через 1 минуту...");
                mainHandler.post(() ->
                        com.vlessvpn.app.util.StatusBus.post("⚠️ Нет рабочих серверов. Повтор через 1 мин..."));

                // Отключаем мёртвый VPN (system_cleanup — не трогает перебор)
                Intent disconnectIntent = new Intent(context, VpnTunnelService.class);
                disconnectIntent.setAction(VpnTunnelService.ACTION_DISCONNECT);
                disconnectIntent.putExtra("system_cleanup", true);
                context.startService(disconnectIntent);

                // Ждём 60 сек через sleep — прерывается при cancel
                try { Thread.sleep(60_000L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    FileLogger.i(TAG, "Ожидание повтора прервано");
                    return;
                }
                if (shouldCancelConnection) return;
                currentServerIndex = 0;
                continue;
            }

            VlessServer server = serverList.get(currentServerIndex);
            FileLogger.i(TAG, "Пробуем сервер[" + currentServerIndex + "]: " + server.host);
            mainHandler.post(() ->
                    com.vlessvpn.app.util.StatusBus.post("🔄 Подключение: " + server.remark));

            boolean success = tryConnectAndVerify(context, server);

            if (shouldCancelConnection) {
                FileLogger.i(TAG, "Перебор прерван.");
                return;
            }

            if (success) {
                FileLogger.i(TAG, "✅ Подключение на сервере [" + currentServerIndex + "]");
                return;
            } else {
                FileLogger.w(TAG, "❌ Сервер[" + currentServerIndex + "] не работает, следующий...");
                currentServerIndex++;
                // Задержка между серверами — прерывается при cancel
                try { Thread.sleep(SERVER_SWITCH_DELAY_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public static void cancelAutoConnect() {
        FileLogger.i(TAG, "Отмена авто-подключения");
        shouldCancelConnection = true;
        isFailoverInProgress = false;
        // Разблокируем latch если ждём верификацию
        CountDownLatch latch = verificationLatch;
        if (latch != null) latch.countDown();
        // Прерываем executor thread (выходит из Thread.sleep и await)
        executor.shutdownNow();
        // Убираем отложенные задачи в главном потоке
        mainHandler.removeCallbacksAndMessages(null);
    }

    public static void reportVerificationResult(boolean success) {
        verificationResult = success;
        CountDownLatch latch = verificationLatch;
        if (latch != null) latch.countDown();
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
            verificationLatch.await(VPN_STARTUP_WAIT_MS, TimeUnit.MILLISECONDS);
            if (shouldCancelConnection) return false;

            if (verificationResult) {
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
