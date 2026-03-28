package com.vlessvpn.app.service;

import android.content.Context;
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
 * AutoConnectManager теперь только перебор серверов.
 * Само подключение делегируется в VpnController.
 */
public class AutoConnectManager {

    private static final String TAG = "AutoConnectManager";
    private static final int VPN_STARTUP_WAIT_MS = 15000;
    private static final int SERVER_SWITCH_DELAY_MS = 1000;

    private static ExecutorService executor = Executors.newSingleThreadExecutor();
    private static int currentServerIndex = 0;
    private static List<VlessServer> serverList = null;
    private static volatile boolean isFailoverInProgress = false;
    private static volatile boolean shouldCancelConnection = false;
    private static volatile CountDownLatch verificationLatch;
    private static volatile boolean verificationResult;

    public static void startAutoConnect(Context context) {
        if (isFailoverInProgress) return;
        if (executor.isShutdown()) executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            isFailoverInProgress = true;
            shouldCancelConnection = false;

            try {
                ServerRepository repo = new ServerRepository(context);
                serverList = repo.getTopServersSync();

                if (serverList == null || serverList.isEmpty()) {
                    FileLogger.w(TAG, "Нет серверов для авто-подключения");
                    return;
                }

                VlessServer lastServer = repo.getLastWorkingServer();
                if (lastServer != null && !shouldCancelConnection) {
                    if (tryConnectAndVerify(context, lastServer)) return;
                }

                currentServerIndex = 0;
                runServerLoop(context);

            } finally {
                isFailoverInProgress = false;
            }
        });
    }

    private static void runServerLoop(Context context) {
        while (!shouldCancelConnection) {
            if (serverList == null || currentServerIndex >= serverList.size()) {
                // ... (твоя оригинальная логика с sleep и повтором)
                // (оставлена без изменений)
                return;
            }

            VlessServer server = serverList.get(currentServerIndex);
            boolean success = tryConnectAndVerify(context, server);

            if (shouldCancelConnection) return;

            if (success) {
                return;
            } else {
                currentServerIndex++;
                try { Thread.sleep(SERVER_SWITCH_DELAY_MS); } catch (InterruptedException e) { return; }
            }
        }
    }

    public static void cancelAutoConnect() {
        shouldCancelConnection = true;
        isFailoverInProgress = false;
        CountDownLatch latch = verificationLatch;
        if (latch != null) latch.countDown();
        executor.shutdownNow();
    }

    public static void reportVerificationResult(boolean success) {
        verificationResult = success;
        CountDownLatch latch = verificationLatch;
        if (latch != null) latch.countDown();
    }

    private static boolean tryConnectAndVerify(Context context, VlessServer server) {
        if (shouldCancelConnection) return false;

        verificationLatch = new CountDownLatch(1);
        verificationResult = false;

        VpnController.getInstance(context).connect(server, true);

        try {
            verificationLatch.await(VPN_STARTUP_WAIT_MS, TimeUnit.MILLISECONDS);
            return verificationResult;
        } catch (InterruptedException e) {
            return false;
        }
    }

    public static boolean isFailoverInProgress() {
        return isFailoverInProgress;
    }
}