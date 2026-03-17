package com.vlessvpn.app.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.util.FileLogger;

import android.net.NetworkRequest;
import android.net.ConnectivityManager.NetworkCallback;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V2RayManager — управляет жизненным циклом v2ray core.
 *
 * ТОЧНАЯ СХЕМА из исходников v2rayNG:
 *
 * 1. startup() → возвращает TUN fd (long)
 *    Go runtime вызывает startup() перед началом работы и получает fd TUN интерфейса.
 *    TUN должен быть создан ДО startLoop().
 *
 * 2. startLoop(configJson) → один аргумент (не два!)
 *    Конфиг должен содержать inbound с tag="tun" и protocol="tun".
 *
 * 3. protect: — Go вызывает vpnProtect через JNI напрямую через ServiceControl.
 *    onEmitStatus НЕ используется для protect в актуальных версиях.
 *    НО: мы оставляем обработку "protect:N" в onEmitStatus как fallback.
 *
 * 4. shutdown() → Go сигнализирует об остановке.
 */
public class V2RayManager {

    private static final String TAG = "V2RayManager";

    private static volatile boolean coreEnvReady = false;
    private static final Object envLock = new Object();

    public static void initEnvOnce(Context ctx) {
        if (coreEnvReady) return;
        synchronized (envLock) {
            if (coreEnvReady) return;
            try {
                String assetsPath = ctx.getFilesDir().getAbsolutePath() + "/";
                FileLogger.i(TAG, "initCoreEnv path=" + assetsPath);

                // ИСПРАВЛЕННАЯ СТРОКА: Передаем путь ПЕРВЫМ аргументом, а ВТОРЫМ — пустую строку
                libv2ray.Libv2ray.initCoreEnv(assetsPath, "");

                coreEnvReady = true;
                FileLogger.i(TAG, "Инициализация ядра - OK v=" + libv2ray.Libv2ray.checkVersionX());
            } catch (Exception e) {
                FileLogger.e(TAG, "Инициализация ядра - failed", e);
            }
        }
    }

    public interface StatusCallback {
        void onStarted(VlessServer server);
        void onStopped();
        void onError(String error);
        void onStatsUpdate(long uploadBytes, long downloadBytes);
    }

    private libv2ray.CoreController coreController = null;
    private VlessServer currentServer = null;
    private final Context context;
    private final StatusCallback callback;

    public V2RayManager(Context context, StatusCallback callback) {
        this.context  = context;
        this.callback = callback;
    }

    /**
     * Запускает v2ray core. Блокирует поток. Вызывать из фонового потока.
     *
     * Перед вызовом VpnTunnelService должен создать TUN интерфейс —
     * startup() запрашивает fd немедленно при startLoop().
     */
    public boolean start(VlessServer server) {
        stop(); // остановить предыдущий если есть

        currentServer = server;

        try {
            initEnvOnce(context);

            String configJson = V2RayConfigBuilder.build(server, 10808);
            FileLogger.i(TAG, "v2ray start: " + server.host + ":" + server.port
                + " flow=" + server.flow + " net=" + server.networkType
                + " sec=" + server.security + " sni=" + server.sni);
            // FileLogger.i(TAG, "CONFIG: " + configJson);

            coreController = libv2ray.Libv2ray.newCoreController(new VpnCallback(server));

            // startLoop(configJson) — Go внутри вызывает startup() чтобы получить TUN fd
            // startup() должен вернуть fd ДО того как Go начнёт читать пакеты
            coreController.startLoop(configJson, 0);

            // Проверяем что запустился
            if (!coreController.getIsRunning()) {
                FileLogger.e(TAG, "v2ray не запустился (isRunning=false сразу)");
                if (callback != null) callback.onError("v2ray не запустился");
                return false;
            }

            // Успешный запуск — уведомляем
            FileLogger.i(TAG, "v2ray isRunning=true → onStarted");
            if (callback != null) callback.onStarted(server);

            // Поллинг пока работает
            while (coreController != null && coreController.getIsRunning()) {
                Thread.sleep(500);
            }

            FileLogger.i(TAG, "v2ray loop завершился");
            return true;

        } catch (InterruptedException e) {
            FileLogger.i(TAG, "Поток v2ray прерван");
            return true;
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка v2ray", e);
            coreController = null;
            currentServer  = null;
            if (callback != null) callback.onError(e.getMessage() != null ? e.getMessage() : e.toString());
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

    public long[] getStats() {
        libv2ray.CoreController ctrl = coreController;
        if (ctrl == null) return new long[]{0, 0};
        try {
            long up   = ctrl.queryStats("proxy", "uplink");
            long down = ctrl.queryStats("proxy", "downlink");
            if (callback != null) callback.onStatsUpdate(up, down);
            return new long[]{up, down};
        } catch (Exception e) { return new long[]{0, 0}; }
    }

    // ════════════════════════════════════════════════════════════════
    //  CoreCallbackHandler — точная копия логики v2rayNG
    // ════════════════════════════════════════════════════════════════

    private class VpnCallback implements libv2ray.CoreCallbackHandler {
        private final VlessServer server;
        VpnCallback(VlessServer server) { this.server = server; }

        /**
         * startup() — Go вызывает при старте, ожидает TUN fd.
         *
         * ВАЖНО: вызывается СИНХРОННО внутри startLoop() из Go goroutine.
         * TUN интерфейс должен быть уже создан к этому моменту.
         * Возвращаем fd. Go будет читать/писать IP пакеты в этот fd.
         */
        @Override
        public long startup() {
            int fd = com.vlessvpn.app.service.VpnTunnelService.getTunFd();
            FileLogger.i(TAG, "startup() → fd=" + fd);
            if (fd <= 0) FileLogger.e(TAG, "startup(): TUN fd не готов!");
            return (long) fd;
        }

        /**
         * shutdown() — Go завершил работу.
         * Вызываем onStopped через main thread чтобы не блокировать Go goroutine.
         */
        @Override
        public long shutdown() {
            FileLogger.i(TAG, "shutdown() от Go");
            new Handler(Looper.getMainLooper()).post(() -> {
                if (callback != null) callback.onStopped();
            });
            return 0;
        }

        /**
         * onEmitStatus() — статусные сообщения от Go core.
         *
         * В v2rayNG этот метод возвращает 0 и ничего не делает.
         * protect() вызывается Go через JNI напрямую (не через этот метод).
         *
         * НО: в нашей реализации без ServiceControl интерфейса
         * мы оставляем обработку "protect:N" как fallback на случай
         * если конкретная версия libv2ray всё же шлёт через onEmitStatus.
         */
        @Override
        public long onEmitStatus(long level, String message) {
            if (message == null || message.isEmpty()) return 0;
            FileLogger.i(TAG, "[go L" + level + "] " + message);

            // Fallback protect — на случай если Go шлёт через onEmitStatus
            if (message.startsWith("protect:") || message.startsWith("Protect:")) {
                try {
                    int fd = Integer.parseInt(message.substring(8).trim());
                    boolean ok = com.vlessvpn.app.service.VpnTunnelService.protectSocket(fd);
                    FileLogger.i(TAG, "onEmitStatus protect(" + fd + ")=" + ok);
                } catch (Exception ignored) {}
                return 0;
            }

            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  measureDelay для тестирования серверов
    // ════════════════════════════════════════════════════════════════
    /**
     * Измеряет задержку outbound через мобильные данные (LTE/5G),
     * даже если Wi-Fi подключён и активен.
     *
     * @return задержка в мс (>0 — успех), -1 — нет cellular / ошибка, -2 — таймаут
     */
    public static long measureDelayOnCellular(Context ctx, String configJson) {
        initEnvOnce(ctx);

        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            FileLogger.w(TAG, "ConnectivityManager недоступен");
            return -1;
        }

        // Проверяем, есть ли cellular с интернетом
        Network cellularNet = getCellularNetwork(ctx);
        if (cellularNet == null) {
            FileLogger.w(TAG, "Нет доступной cellular-сети с интернетом");
            return -1;
        }

        // Если cellular уже default — меряем напрямую (редко, но бывает)
        Network currentDefault = cm.getActiveNetwork();
        if (currentDefault != null && currentDefault.equals(cellularNet)) {
            return measureOutboundDelayStandard(ctx, configJson);
        }

        // Подготавливаем запрос на cellular
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)  // чтобы была реальная проверка интернета
                .build();

        final AtomicLong delayResult = new AtomicLong(-1);
        final AtomicBoolean boundSuccess = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);

        NetworkCallback callback = new NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                FileLogger.i(TAG, "Cellular доступна: " + network);
                boolean bound = cm.bindProcessToNetwork(network);
                if (bound) {
                    boundSuccess.set(true);
                    FileLogger.i(TAG, "Процесс успешно привязан к cellular");
                    long delay = measureOutboundDelayStandard(ctx, configJson);
                    delayResult.set(delay);
                } else {
                    FileLogger.e(TAG, "bindProcessToNetwork вернул false");
                }
                latch.countDown();
            }

            @Override
            public void onUnavailable() {
                FileLogger.e(TAG, "Cellular unavailable (возможно, выключена)");
                latch.countDown();
            }

            @Override
            public void onLost(Network network) {
                FileLogger.w(TAG, "Cellular потеряна во время теста");
                latch.countDown();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                // Если после onAvailable capabilities изменились (редко)
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    // Можно повторить меру, но обычно не нужно
                }
            }
        };

        try {
            // Запрашиваем сеть (Android поднимет cellular если нужно)
            cm.requestNetwork(request, callback);

            // Ждём до 15 сек (подъём cellular + handshake Reality может занять время)
            boolean ok = latch.await(15, TimeUnit.SECONDS);

            // Восстанавливаем default сеть (очень важно!)
            cm.bindProcessToNetwork(null);

            // Отменяем callback, чтобы не висел
            cm.unregisterNetworkCallback(callback);

            if (!ok) {
                FileLogger.w(TAG, "Таймаут ожидания cellular");
                return -2;
            }

            if (boundSuccess.get()) {
                long delay = delayResult.get();
                FileLogger.i(TAG, "Задержка через LTE: " + delay + " мс");
                return delay;
            } else {
                return -1;
            }
        } catch (SecurityException | InterruptedException e) {
            FileLogger.e(TAG, "Ошибка при requestNetwork / bindProcessToNetwork", e);
            return -1;
        } catch (Exception e) {
            FileLogger.e(TAG, "Неожиданная ошибка в measureOnCellular", e);
            return -1;
        } finally {
            // На всякий случай — отвязываем процесс
            cm.bindProcessToNetwork(null);
        }
    }

    // Вспомогательный: обычный measure (для fallback)
    private static long measureOutboundDelayStandard(Context ctx, String configJson) {
        try {
            String testUrl = "https://connectivitycheck.gstatic.com/generate_204";  // стабильный, быстрый
            // Альтернативы: "https://www.google.com/generate_204", "https://1.1.1.1"
            long delay = libv2ray.Libv2ray.measureOutboundDelay(configJson, testUrl);
            FileLogger.d(TAG, "measure standard = " + delay + "ms");
            return delay >= 0 ? delay : -1;
        } catch (Exception e) {
            FileLogger.d(TAG, "measure err: " + e.getMessage());
            return -1;
        }
    }

    // Твой существующий метод поиска cellular — оставь или слегка улучши
    private static Network getCellularNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;

        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                FileLogger.d(TAG, "Найдена cellular: " + net);
                return net;
            }
        }
        return null;
    }


    public static long measureDelay(Context ctx, String configJson) {
        initEnvOnce(ctx);
        try {
            // В Xray для фоновой проверки серверов используется специальный
            // статический метод. Он не требует создания CoreController
            // и безопасно работает в параллельных потоках (TestWorker).

            String testUrl = "https://www.google.com/generate_204";  // или "https://connectivitycheck.gstatic.com/generate_204"
            // или даже "https://1.1.1.1" для Cloudflare

            long delay = libv2ray.Libv2ray.measureOutboundDelay(configJson, testUrl);

           // FileLogger.d(TAG, "measureDelay=" + delay + "ms");
            return delay;
        } catch (Exception e) {
           // FileLogger.d(TAG, "measureDelay err: " + e.getMessage());
            return -1;
        }
    }

    private static Network getPhysicalNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network fallback = null;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
             && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return net;
            fallback = net;
        }
        return fallback;
    }
}
