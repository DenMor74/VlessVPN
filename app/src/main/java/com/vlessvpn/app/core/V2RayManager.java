package com.vlessvpn.app.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.util.FileLogger;

import java.io.FileDescriptor;
import java.io.IOException;

public class V2RayManager {

    private long lastTotalTx = 0;
    private long lastTotalRx = 0;
    private long lastQueryTime = 0;

    private static final String TAG = "V2RayManager";

    private static volatile boolean coreEnvReady = false;
    private static final Object     envLock      = new Object();

    // ════════════════════════════════════════════════════════════════
    // ← НОВОЕ: Сеть для тестов (LTE) — используется в onEmitStatus
    // ════════════════════════════════════════════════════════════════
    private static Network sTestCellularNetwork = null;

    public static void initEnvOnce(Context ctx) {
        if (coreEnvReady) return;
        synchronized (envLock) {
            if (coreEnvReady) return;
            try {
                String path = ctx.getFilesDir().getAbsolutePath() + "/";
                libv2ray.Libv2ray.initCoreEnv(path, "");
                coreEnvReady = true;
                FileLogger.i(TAG, "Core OK v=" + libv2ray.Libv2ray.checkVersionX());
            } catch (Exception e) {
                FileLogger.e(TAG, "initCoreEnv failed", e);
            }
        }
    }

    public long[] getStats() {
        try {
            long currentTotalTx = TrafficStats.getTotalTxBytes();
            long currentTotalRx = TrafficStats.getTotalRxBytes();
            long currentTime = System.currentTimeMillis();

            long timeDelta = currentTime - lastQueryTime;

            if (timeDelta < 500) {
                return new long[]{currentTotalTx - lastTotalTx, currentTotalRx - lastTotalRx};
            }

            long up = currentTotalTx - lastTotalTx;
            long down = currentTotalRx - lastTotalRx;

            lastTotalTx = currentTotalTx;
            lastTotalRx = currentTotalRx;
            lastQueryTime = currentTime;

            return new long[]{up, down};

        } catch (Exception e) {
            FileLogger.e(TAG, "getStats error: " + e.getMessage());
            return new long[]{0, 0};
        }
    }

    public interface StatusCallback {
        void onStarted(VlessServer server);
        void onStopped();
        void onError(String error);
        void onStatsUpdate(long uploadBytes, long downloadBytes);
    }

    private libv2ray.CoreController coreController;
    private VlessServer currentServer;
    private final Context        context;
    private final StatusCallback callback;

    public V2RayManager(Context context, StatusCallback callback) {
        this.context  = context;
        this.callback = callback;
    }

    public boolean start(VlessServer server) {
        stop();
        currentServer = server;
        lastTotalTx = TrafficStats.getTotalTxBytes();
        lastTotalRx = TrafficStats.getTotalRxBytes();
        lastQueryTime = System.currentTimeMillis();
        try {
            initEnvOnce(context);
            String cfg = V2RayConfigBuilder.build(server, 10808, -1);
            FileLogger.i(TAG, "start: " + server.host);
            coreController = libv2ray.Libv2ray.newCoreController(new VpnCallback(server));
            coreController.startLoop(cfg, 0);

            if (!coreController.getIsRunning()) {
                FileLogger.e(TAG, "v2ray не запустился");
                if (callback != null) callback.onError("v2ray не запустился");
                return false;
            }

            FileLogger.i(TAG, "v2ray запущен");
            if (callback != null) callback.onStarted(server);

            while (coreController != null && coreController.getIsRunning()) {
                Thread.sleep(500);
            }
            return true;

        } catch (InterruptedException e) {
            return true;
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка v2ray", e);
            coreController = null;
            currentServer  = null;
            if (callback != null)
                callback.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            return false;
        }
    }

    public void stop() {
        libv2ray.CoreController ctrl = coreController;
        coreController = null;
        currentServer  = null;
        if (ctrl != null) {
            try { ctrl.stopLoop(); }
            catch (Exception e) { FileLogger.w(TAG, "stopLoop: " + e.getMessage()); }
        }
    }

    public boolean isRunning() {
        return coreController != null && coreController.getIsRunning();
    }

    // ── Измерение задержки ────────────────────────────────

    public static long measureDelay(Context ctx, String configJson) {
        initEnvOnce(ctx);
        try {
            return libv2ray.Libv2ray.measureOutboundDelay(configJson,
                    "https://www.google.com/generate_204");
        } catch (Exception e) {
            return -1;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ← НОВЫЕ МЕТОДЫ: Управление тестовой сетью
    // ════════════════════════════════════════════════════════════════

    /**
     * Установить мобильную сеть для тестов (вызывать ПЕРЕД запуском мультиплекса)
     */
    public static void setTestNetworkForMultiplex(Network cellularNet) {
        sTestCellularNetwork = cellularNet;
        FileLogger.i(TAG, "Test network set: " + (cellularNet != null ? "LTE/5G" : "null"));
    }

    /**
     * Очистить тестовую сеть (вызывать ПОСЛЕ завершения тестов)
     */
    public static void clearTestNetwork() {
        sTestCellularNetwork = null;
        FileLogger.i(TAG, "Test network cleared");
    }

    // ── CoreCallbackHandler ───────────────────────────────────────────────

    private class VpnCallback implements libv2ray.CoreCallbackHandler {
        VpnCallback(VlessServer server) {}

        @Override
        public long startup() {
            return com.vlessvpn.app.service.VpnTunnelService.getTunFd();
        }

        @Override
        public long shutdown() {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (callback != null) callback.onStopped();
            });
            return 0;
        }

        @Override
        public long onEmitStatus(long level, String message) {
            if (message == null || message.isEmpty()) return 0;

            // Fallback protect (некоторые версии libv2ray шлют через onEmitStatus)
            if (message.startsWith("protect:") || message.startsWith("Protect:")) {
                try {
                    int fd = Integer.parseInt(message.substring(8).trim());
                    com.vlessvpn.app.service.VpnTunnelService.protectSocket(fd);
                } catch (Exception ignored) {}
            }
            return 0;
        }
    }

    // ── МЕТОДЫ ДЛЯ ТИХОГО ТЕСТИРОВАНИЯ (БЕЗ VPN И AOD) ─────────────────────────

    private static libv2ray.CoreController silentCoreController;

    /**
     * Запускает единый инстанс ядра с мультиплекс-конфигом в "тихом" режиме.
     * Теперь: сокеты привязываются к LTE если задана sTestCellularNetwork.
     */
    public static boolean startSilentMultiplexInstance(Context context, String multiplexConfigJson) {
        stopSilentMultiplexInstance();
        initEnvOnce(context);

        try {
            silentCoreController = libv2ray.Libv2ray.newCoreController(new libv2ray.CoreCallbackHandler() {
                @Override
                public long startup() {
                    return -1;  // Не нужен TUN интерфейс для теста
                }

                @Override
                public long shutdown() {
                    return 0;
                }

                @Override
                public long onEmitStatus(long level, String message) {
                    // ════════════════════════════════════════════════════════════════
                    // ← Обрабатываем protect-запросы от v2ray
                    // ════════════════════════════════════════════════════════════════
                    if (message != null && !message.isEmpty()) {
                        if (message.startsWith("protect:") || message.startsWith("Protect:")) {
                            try {
                                int fd = Integer.parseInt(message.substring(8).trim());

                                // ════════════════════════════════════════════════════
                                // ← Если задана тестовая сеть — привязываем сокет к ней
                                // ════════════════════════════════════════════════════
                                Network testNet = sTestCellularNetwork;
                                if (testNet != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    // ← ИСПРАВЛЕНО: bindSocket() возвращает void, не int!
                                    FileDescriptor fdObj = intToFileDescriptor(fd);
                                    if (fdObj != null) {
                                        try {
                                            testNet.bindSocket(fdObj);  // ← Просто вызываем, без присваивания
                                            // FileLogger.d(TAG, "Socket fd=" + fd + " bound to LTE");
                                            return 0;  // Успех
                                        } catch (IOException e) {
                                            FileLogger.w(TAG, "bindSocket failed: " + e.getMessage());
                                            // Если ошибка — пробуем fallback к protect
                                        }
                                    }
                                }

                                // Fallback: protect для обхода VPN (если не тест)
                                // if (VpnTunnelService.vpnInterface != null) {
                                //    VpnTunnelService.protectSocket(fd);
                                //}

                            } catch (Exception e) {
                                FileLogger.w(TAG, "onEmitStatus error: " + e.getMessage());
                            }
                        }
                    }
                    return 0;
                }
            });

            silentCoreController.startLoop(multiplexConfigJson, 0);

            int retries = 0;
            while (!silentCoreController.getIsRunning() && retries < 5) {
                Thread.sleep(200);
                retries++;
            }

            if (!silentCoreController.getIsRunning()) {
                FileLogger.e(TAG, "Тихий v2ray не запустился для тестов");
                return false;
            }

            return true;

        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка тихого старта v2ray", e);
            silentCoreController = null;
            return false;
        }
    }

    public static void stopSilentMultiplexInstance() {
        if (silentCoreController != null) {
            try {
                silentCoreController.stopLoop();
            } catch (Exception e) {
                FileLogger.w(TAG, "stopSilentMultiplexLoop: " + e.getMessage());
            }
            silentCoreController = null;
        }
    }

    // ════════════════════════════════════════════════════════════════
// ← Вспомогательный метод: int fd → FileDescriptor
// ════════════════════════════════════════════════════════════════
    private static FileDescriptor intToFileDescriptor(int fd) {
        if (fd < 0) return null;
        try {
            FileDescriptor fdObj = new FileDescriptor();
            java.lang.reflect.Field f = FileDescriptor.class.getDeclaredField("descriptor");
            f.setAccessible(true);
            f.setInt(fdObj, fd);
            return fdObj;
        } catch (Exception e) {
            FileLogger.w(TAG, "intToFileDescriptor failed: " + e.getMessage());
            return null;
        }
    }

}