package com.vlessvpn.app.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.util.FileLogger;



public class V2RayManager {

    private long lastTotalTx = 0;
    private long lastTotalRx = 0;
    private long lastQueryTime = 0;

    private static final String TAG = "V2RayManager";

    private static volatile boolean coreEnvReady = false;
    private static final Object     envLock      = new Object();

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
            // ════════════════════════════════════════════════════════════════
            // ← Получаем ОБЩИЙ трафик устройства
            // ════════════════════════════════════════════════════════════════
            long currentTotalTx = TrafficStats.getTotalTxBytes();
            long currentTotalRx = TrafficStats.getTotalRxBytes();
            long currentTime = System.currentTimeMillis();

            // ════════════════════════════════════════════════════════════════
            // ← Вычисляем разницу с последнего замера (байты за секунду)
            // ════════════════════════════════════════════════════════════════
            long timeDelta = currentTime - lastQueryTime;

            if (timeDelta < 500) {
                // Слишком быстро — возвращаем последние значения
                return new long[]{currentTotalTx - lastTotalTx, currentTotalRx - lastTotalRx};
            }

            long up = currentTotalTx - lastTotalTx;
            long down = currentTotalRx - lastTotalRx;

            // ════════════════════════════════════════════════════════════════
            // ← Сохраняем для следующего замера
            // ════════════════════════════════════════════════════════════════
            lastTotalTx = currentTotalTx;
            lastTotalRx = currentTotalRx;
            lastQueryTime = currentTime;

            // ════════════════════════════════════════════════════════════════
            // ← Логирование (для отладки)
            // ════════════════════════════════════════════════════════════════
            //FileLogger.d(TAG, "queryStats: up=" + up + " down=" + down + " (timeDelta=" + timeDelta + "ms)");

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

    /** Запускает v2ray core. Блокирует поток до остановки. */
    public boolean start(VlessServer server) {
        stop();
        currentServer = server;
        // ════════════════════════════════════════════════════════════════
        // ← Сбросить счётчики при старте VPN
        // ════════════════════════════════════════════════════════════════
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

    // ── Измерение задержки (для ScanWorker) ────────────────────────────────

    /**
     * Статический measureDelay через Xray core.
     * Безопасно вызывается из параллельных потоков ScanWorker.
     */
    public static long measureDelay(Context ctx, String configJson) {
        initEnvOnce(ctx);
        try {
            return libv2ray.Libv2ray.measureOutboundDelay(configJson,
                    "https://www.google.com/generate_204");
        } catch (Exception e) {
            return -1;
        }
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
     * Не привязывается к VpnService, не возвращает TunFd, не делает protectSocket.
     *
     * @param context Context приложения
     * @param multiplexConfigJson Конфиг, сгенерированный V2RayConfigBuilder.buildMultiplexTestConfig
     * @return true если запустилось
     */
    public static boolean startSilentMultiplexInstance(Context context, String multiplexConfigJson) {
        stopSilentMultiplexInstance(); // На всякий случай убиваем старый
        initEnvOnce(context);

        try {
            // Создаем заглушку (SilentCallback), которая ни при каких условиях
            // не будет дергать VpnTunnelService, чтобы не будить экран!
            silentCoreController = libv2ray.Libv2ray.newCoreController(new libv2ray.CoreCallbackHandler() {
                @Override
                public long startup() {
                    // Возвращаем -1, потому что нам не нужен TUN интерфейс для теста
                    return -1;
                }

                @Override
                public long shutdown() {
                    return 0;
                }

                @Override
                public long onEmitStatus(long level, String message) {
                    // ИГНОРИРУЕМ запросы "protect:", так как мы привяжем
                    // весь процесс к LTE сети на уровне Android (bindProcessToNetwork)
                    return 0;
                }
            });

            // Запускаем ядро в фоне
            // ВАЖНО: 0 (или -1 в зависимости от вашей версии libv2ray) означает отсутствие tunFd
            silentCoreController.startLoop(multiplexConfigJson, 0);

            // Даем ядру немного времени на инициализацию портов
            int retries = 0;
            while (!silentCoreController.getIsRunning() && retries < 5) {
                Thread.sleep(200);
                retries++;
            }

            if (!silentCoreController.getIsRunning()) {
                FileLogger.e(TAG, "Тихий v2ray не запустился для тестов");
                return false;
            }

            FileLogger.i(TAG, "Тихий v2ray (Мультиплекс) успешно запущен");
            return true;

        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка тихого старта v2ray", e);
            silentCoreController = null;
            return false;
        }
    }

    /**
     * Останавливает "тихий" инстанс после завершения тестов.
     */
    public static void stopSilentMultiplexInstance() {
        if (silentCoreController != null) {
            try {
                silentCoreController.stopLoop();
                FileLogger.i(TAG, "Тихий v2ray остановлен");
            } catch (Exception e) {
                FileLogger.w(TAG, "stopSilentMultiplexLoop: " + e.getMessage());
            }
            silentCoreController = null;
        }
    }

}
