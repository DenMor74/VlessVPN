package com.vlessvpn.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.vlessvpn.app.R;
import com.vlessvpn.app.core.V2RayManager;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.network.ServerTester;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.ui.MainActivity;
import com.vlessvpn.app.util.AppBlacklistManager;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class VpnTunnelService extends VpnService {

    private static final String TAG       = "VpnTunnelService";
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int    NOTIF_ID  = 1001;

    public static final String ACTION_CONNECT    = "com.vlessvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vlessvpn.DISCONNECT";
    public static final String EXTRA_SERVER      = "server";
    public static final String EXTRA_AUTO_CONNECT = "auto_connect";

    private volatile boolean isStopping = false;

    // ── Слушатели подключения (MainViewModel) ────────────────────────────────
    private static final CopyOnWriteArrayList<Consumer<Boolean>> connectionListeners =
            new CopyOnWriteArrayList<>();

    public static void registerConnectionListener(android.content.Context ctx,
                                                   Consumer<Boolean> listener) {
        connectionListeners.add(listener);
        listener.accept(isRunning);
    }

    private static void notifyConnectionChanged(boolean connected) {
        for (Consumer<Boolean> l : connectionListeners) {
            try { l.accept(connected); }
            catch (Exception e) { FileLogger.e(TAG, "listener error: " + e.getMessage()); }
        }
    }

    // ── Статические поля (читаются из UI без bind) ───────────────────────────
    private static volatile VpnTunnelService instance;
    public  static volatile boolean    isRunning       = false;
    public  static volatile VlessServer connectedServer = null;
    public  static volatile long        totalUp         = 0;
    public  static volatile long        totalDown       = 0;

    // ── Инстанс-поля ─────────────────────────────────────────────────────────
    private final Handler mainHandler  = new Handler(Looper.getMainLooper());
    private final Handler statsHandler = new Handler(Looper.getMainLooper());

    // Предыдущие значения для расчёта скорости за секунду
    private long prevUp   = 0;
    private long prevDown = 0;

    private final Runnable statsPoller = new Runnable() {
        @Override public void run() {
            if (!isRunning) return;

            // Текущий накопленный трафик UID с момента подключения
            long[] tun = readTunStats();
            totalUp   = tun[0];
            totalDown = tun[1];

            // Скорость = разница с предыдущим замером (за ~1 сек)
            long speedUp   = Math.max(0, totalUp   - prevUp);
            long speedDown = Math.max(0, totalDown - prevDown);
            prevUp   = totalUp;
            prevDown = totalDown;

            String speedStr = "↑ " + fmtSpeed(speedUp) + "  ↓ " + fmtSpeed(speedDown);

            VlessServer srv = connectedServer;
            if (srv != null) updateNotification(speedStr, srv.host);

            StatusBus.post(speedStr, true);

            statsHandler.postDelayed(this, 1_000);
        }
    };

    // Периодическая проверка соединения (каждые 60 сек)
    private final Handler  checkHandler  = new Handler(Looper.getMainLooper());
    private ExecutorService bgExecutor;

// ════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) {
                FileLogger.d(TAG, "checkRunnable: VPN неактивен — останавливаем");
                return;
            }
            if (bgExecutor == null || bgExecutor.isShutdown()) {
                bgExecutor = Executors.newSingleThreadExecutor();
                FileLogger.w(TAG, "checkRunnable: bgExecutor пересоздан");
            }
            FileLogger.i(TAG, "=== проверка соединения ===");
            bgExecutor.execute(() -> {
                doConnectivityCheck();
                if (isRunning) {
                    checkHandler.postDelayed(checkRunnable, 60_000L);
                    FileLogger.i(TAG, "следующая проверка через 60 сек");
                }
            });
        }
    };

    private void startPeriodicCheck() {
        checkHandler.removeCallbacks(checkRunnable);
        // Первая проверка через 5 сек — Reality handshake быстрый, не нужно ждать 25 сек
        checkHandler.postDelayed(checkRunnable, 5_000L);
        FileLogger.i(TAG, "Periodic check запущен (первый через 5 сек, далее каждые 60 сек)");
    }

    private ParcelFileDescriptor vpnInterface;
    private V2RayManager         v2RayManager;
    private HevTunnel            hevTunnel;
    private Thread               v2rayThread;
    private VlessServer          currentServer;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Object connectLock    = new Object();
    private volatile long activeConnectId = 0;

    // ── Статические хелперы ──────────────────────────────────────────────────

    public static int getTunFd() { return 0; }

    public static VpnTunnelService getInstance() { return instance; }

    /** Запускает глубокую проверку вручную (кнопка обновить) */
    public void runSpeedTest() {
        if (bgExecutor != null && !bgExecutor.isShutdown())
            bgExecutor.execute(this::doSpeedTest);
    }

    public void runDeepCheck() {
        if (bgExecutor != null && !bgExecutor.isShutdown()) {
            bgExecutor.execute(this::doDeepCheck);
        }
    }

    public static boolean protectSocket(int fd) {
        VpnTunnelService svc = instance;
        return svc != null && svc.protect(fd);
    }

    public static VlessServer getCurrentServer() { return connectedServer; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        createNotificationChannel();
        V2RayManager.initEnvOnce(this);
        bgExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_DISCONNECT.equals(action)) {
            // Выполняем в фоне — fullStop содержит Thread.sleep
            new Thread(this::disconnect, "disconnect-thread").start();
            return START_NOT_STICKY;
        }

        VlessServer server = null;
        if (intent != null) {
            String json = intent.getStringExtra(EXTRA_SERVER);
            if (json != null) {
                try { server = new Gson().fromJson(json, VlessServer.class); }
                catch (Exception e) { FileLogger.e(TAG, "Ошибка десериализации: " + e.getMessage()); }
            }
        }

        if (server == null) { stopSelf(); return START_NOT_STICKY; }

        startForeground(NOTIF_ID, buildNotification("Подключение...", server.host));

        // Пересоздаём executor если был завершён (после onDestroy/disconnect)
        if (bgExecutor == null || bgExecutor.isShutdown()) {
            bgExecutor = Executors.newSingleThreadExecutor();
            FileLogger.d(TAG, "bgExecutor пересоздан");
        }

        final VlessServer srv = server;
        final long id = System.currentTimeMillis();
        activeConnectId = id;
        new Thread(() -> connect(srv, id), "connect-thread").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        checkHandler.removeCallbacks(checkRunnable);
        if (bgExecutor != null) bgExecutor.shutdownNow();
        disconnect();
    }

    @Override
    public void onRevoke() { disconnect(); }

    // ── Connect / Disconnect ─────────────────────────────────────────────────

    private void connect(VlessServer server, long connectId) {
        synchronized (connectLock) {
            if (connectId != activeConnectId) return;
            if (isRunning || v2RayManager != null || hevTunnel != null) fullStop();
            if (connectId != activeConnectId) return;
        }
        isStopping = false;
        currentServer = server;

        // Пересоздаём executor если завершён — нужно при переключении сервера
        if (bgExecutor == null || bgExecutor.isShutdown()) {
            bgExecutor = Executors.newSingleThreadExecutor();
            FileLogger.d(TAG, "bgExecutor пересоздан в connect()");
        }

        v2rayThread = new Thread(() -> {
            try {
                vpnInterface = buildTunWithRetries(server);
                if (vpnInterface == null) {
                    FileLogger.e(TAG, "Не удалось создать TUN");
                    mainHandler.post(() -> StatusBus.done("Не удалось создать TUN"));
                    stopSelf();
                    return;
                }
                registerNetworkCallback();
                startV2RayAndHev(server);
            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка в v2ray потоке", e);
                mainHandler.post(() -> { StatusBus.done(e.getMessage()); stopSelf(); });
            }
        }, "v2ray-thread");
        v2rayThread.setDaemon(true);
        v2rayThread.start();
    }

    private ParcelFileDescriptor buildTunWithRetries(VlessServer server) {
        for (int i = 1; i <= 4; i++) {
            try {
                if (VpnService.prepare(this) != null) {
                    FileLogger.e(TAG, "VPN не подготовлен");
                    return null;
                }
                Builder builder = new Builder();
                builder.setSession("VlessVPN");
                builder.setMtu(1500);
                builder.addAddress("10.10.14.1", 24);
                builder.addDnsServer("8.8.8.8");
                builder.addDnsServer("8.8.4.4");
                addRoutesExcluding(builder, server.host);
                try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
                applyAppBlacklist(builder);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);
                ParcelFileDescriptor pfd = builder.establish();
                if (pfd != null) return pfd;
                FileLogger.w(TAG, "establish() null, попытка " + i);
            } catch (Exception e) {
                FileLogger.w(TAG, "buildTun ошибка попытка " + i + ": " + e.getMessage());
            }
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    private void applyAppBlacklist(Builder builder) {
        Set<String> blacklist = new AppBlacklistManager(this).getBlacklist();
        for (String pkg : blacklist) {
            try { builder.addDisallowedApplication(pkg); }
            catch (PackageManager.NameNotFoundException ignored) {}
        }
    }

    private void startV2RayAndHev(VlessServer server) {
        // Сохраняем ID этого подключения — при завершении проверим актуальность
        final long myConnectId = activeConnectId;
        v2RayManager = new V2RayManager(this, new V2RayManager.StatusCallback() {
            @Override public void onStarted(VlessServer s) {
                startHev();
                mainHandler.post(() -> {
                    updateNotification("Подключено", s.host);
                    isRunning      = true;
                    connectedServer = s;
                    currentServer  = s;
                    totalUp = 0; totalDown = 0;
                    prevUp = 0; prevDown = 0;
                    failCount = 0;
                    resetTunBase(VpnTunnelService.this); // фиксируем нулевую точку для UID счётчиков
                    StatusBus.post(VpnTunnelService.this, "Подключено: " + s.host, true);
                    statsHandler.postDelayed(statsPoller, 500);
                    startPeriodicCheck(); // единственное место планирования periodic check
                    ServerTester.setVpnActive(true);
                    // AOD начальный статус
                    sendAodStatus();
                    // Глубокая проверка (если включена в настройках) — после обычной проверки
                    if (new ServerRepository(VpnTunnelService.this).isDeepCheckOnConnect()) {
                        bgExecutor.execute(VpnTunnelService.this::doDeepCheck);
                    }
                    new ServerRepository(VpnTunnelService.this).saveLastWorkingServer(s);
                    sendVpnBroadcast(true, s, null);
                    notifyConnectionChanged(true);
                });
            }

            @Override public void onStopped() {
                mainHandler.post(() -> {
                    ServerTester.setVpnActive(false);
                    StatusBus.done(VpnTunnelService.this, "Отключено");
                    sendVpnBroadcast(false, null, null);
                    notifyConnectionChanged(false);
                    AodOverlayService.sendStatus(VpnTunnelService.this, false, null, null, null);
                    android.util.Log.i("AodOverlay", "VPN отключён — overlay скрыт");
                });
            }

            @Override public void onError(String error) {
                mainHandler.post(() -> {
                    ServerTester.setVpnActive(false);
                    StatusBus.done(VpnTunnelService.this, error);
                    sendVpnBroadcast(false, null, error);
                    notifyConnectionChanged(false);
                    stopSelf();
                });
            }

            @Override public void onStatsUpdate(long up, long down) {
                totalUp = up; totalDown = down;
            }
        });

        v2RayManager.start(server);

        // v2ray завершился — вызываем stopSelf только если это актуальное подключение
        // При переключении сервера activeConnectId уже другой → старый поток не трогает сервис
        if (myConnectId == activeConnectId && !isRunning) {
            stopHev();
            mainHandler.post(this::stopSelf);
        }
    }

    private void sendVpnBroadcast(boolean connected, VlessServer server, String error) {
        try {
            Intent b = new Intent("com.vlessvpn.VPN_STATUS_CHANGED");
            b.putExtra("connected", connected);
            if (server != null) b.putExtra("server", new Gson().toJson(server));
            if (error  != null) b.putExtra("error",  error);
            sendBroadcast(b);
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка broadcast: " + e.getMessage());
        }
    }

    // ── Периодическая проверка соединения ────────────────────────────────────

    // Счётчик последовательных провалов проверки
    private int failCount = 0;

    /**
     * Глубокая проверка — HTTP запрос к google.com через туннель.
     * При первых байтах ответа считаем успехом — прерываем чтение.
     * Результат показываем в tv_last_status (через StatusBus с флагом deep).
     */
    /**
     * Глубокая проверка — запрос к ipinfo.io через туннель.
     * Возвращает внешний IP, страну и город — показывает что трафик идёт
     * именно через VPN сервер, а не напрямую.
     * Ответ ~200 байт JSON — очень лёгкий запрос.
     */

    /** Отправляет статус в AOD overlay — всегда в фоновом потоке */
    private void sendAodStatus() {
        sendAodStatusWithIp(null);
        // Дефолтный статус
        com.vlessvpn.app.service.AodOverlayService.sendStatusMsg(
                this, "Подключено");
    }

    private void sendAodStatusWithIp(String ip) {
        final String ipFinal = ip;
        bgExecutor.execute(() -> {
            com.vlessvpn.app.storage.ServerRepository repo =
                    new com.vlessvpn.app.storage.ServerRepository(VpnTunnelService.this);
            java.util.List<com.vlessvpn.app.model.VlessServer> all =
                    repo.getAllServersSync();
            int total   = all.size();
            int working = 0;
            for (com.vlessvpn.app.model.VlessServer s : all)
                if (s.trafficOk) working++;
            String stat = working + "/" + total;
            AodOverlayService.sendStatus(VpnTunnelService.this,
                    true,
                    currentServer != null ? currentServer.host : null,
                    ipFinal,
                    stat);
        });
    }

    private void doDeepCheck() {
        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this,
                "🔬 Проверка IP...", true));
        try {
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                    new java.net.InetSocketAddress("127.0.0.1", 10808));
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("http://ip-api.com/json?fields=query,city,countryCode,isp")
                    .openConnection(proxy);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "VlessVPN/1.0");
            conn.setRequestProperty("Accept", "application/json");

            long t0 = System.currentTimeMillis();
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                String r = "🔬 IP: ✗ HTTP " + code;
                FileLogger.w(TAG, r);
                mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, r, true));
                return;
            }

            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();
            long ms = System.currentTimeMillis() - t0;

            // Парсим JSON вручную (без зависимостей)
            // {"ip":"1.2.3.4","city":"Moscow","region":"...","country":"RU",...}
            String json = sb.toString();
            String ip      = extractJson(json, "query");
            String city    = extractJson(json, "city");
            String country = extractJson(json, "countryCode");
            String org     = extractJson(json, "isp");

            String location = "";
            if (city != null && country != null)    location = city + ", " + country;
            else if (country != null)               location = country;

            String result;
            if (ip != null) {
                result = "🔬 ✓ " + ip + " " + location + " (" + ms + "ms)";
            } else {
                result = "🔬 IP: ✓ ответ получен (" + ms + "ms)";
            }
            FileLogger.i(TAG, result);
            if (org != null) FileLogger.d(TAG, "org: " + org);
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, result, true));

        } catch (java.net.SocketTimeoutException e) {
            String r = "🔬 IP: ✗ таймаут";
            FileLogger.w(TAG, r);
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, r, true));
        } catch (Exception e) {
            String r = "🔬 IP: ✗ " + e.getMessage();
            FileLogger.w(TAG, r);
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, r, true));
        }
    }

    /** Простой парсер JSON-значения по ключу без библиотек */
    private static String extractJson(String json, String key) {
        try {
            // Ищем: "key":"value"
            String q = String.valueOf('"');
            String search = q + key + q + ":" + q;
            int start = json.indexOf(search);
            if (start < 0) return null;
            start += search.length();
            int end = json.indexOf(q, start);
            if (end < 0) return null;
            return json.substring(start, end);
        } catch (Exception e) { return null; }
    }


    private void doSpeedTest() {
        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "⏱ Тест скорости...", true));
        try {
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                    new java.net.InetSocketAddress("127.0.0.1", 10808));
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("http://speed.cloudflare.com/__down?bytes=262144")
                    .openConnection(proxy);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", "VlessVPN/1.0");
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                String r = "⏱ Тест: ✗ HTTP " + code;
                FileLogger.w(TAG, r);
                mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, r, true));
                return;
            }
            java.io.InputStream is = conn.getInputStream();
            byte[] buf = new byte[8192];
            long downloaded = 0;
            long t0 = System.currentTimeMillis();
            int n;
            while ((n = is.read(buf)) != -1) downloaded += n;
            long ms = System.currentTimeMillis() - t0;
            is.close();
            conn.disconnect();
            if (ms < 100) ms = 100;
            double speedMBs = downloaded / 1024.0 / 1024.0 / (ms / 1000.0);
            String speedStr = speedMBs >= 1.0
                    ? String.format("%.2f MB/s", speedMBs)
                    : String.format("%.0f KB/s", speedMBs * 1024);
            String result = "⏱ ✓ " + speedStr + " (" + downloaded/1024 + " KB / " + ms + " ms)";
            FileLogger.i(TAG, result);
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, result, true));
        } catch (java.net.SocketTimeoutException e) {
            String r = "⏱ Тест: ✗ таймаут";
            FileLogger.w(TAG, r);
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, r, true));
        } catch (Exception e) {
            String r = "⏱ Тест: ✗ " + e.getMessage();
            FileLogger.w(TAG, r);
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, r, true));
        }
    }

    private void doConnectivityCheck() {
        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Проверка соединения...", true));
        if (!ServerTester.trafficTest()) {
            failCount++;
            FileLogger.w(TAG, "=== Нет трафика! (провал " + failCount + "/2)");
            if (failCount >= 2) {
                // Два провала подряд — сервер реально не работает, переключаем
                failCount = 0;
                new ServerRepository(this).clearLastWorkingServer();
                switchToNextServer();
            } else {
                // Первый провал — повторяем немедленно (через 2 сек на освобождение соединений)
                mainHandler.post(() -> StatusBus.post(VpnTunnelService.this,
                        "Нет связи, повторная проверка...", true));
                checkHandler.postDelayed(checkRunnable, 2_000L);
            }
        } else {
            failCount = 0;
            if (currentServer != null) {
                FileLogger.i(TAG, "=== Трафик OK!");
                new ServerRepository(this).saveLastWorkingServer(currentServer);
                mainHandler.post(() -> StatusBus.post(VpnTunnelService.this,
                        "Подключено: " + currentServer.host, true));
            }
        }
    }

    /**
     * Переключение на следующий рабочий сервер.
     * Перебирает список по кругу, начиная со следующего после текущего.
     */
    private void switchToNextServer() {
        ServerRepository repo = new ServerRepository(this);

        // Помечаем текущий сервер как нерабочий — убираем с экрана
        if (currentServer != null) {
            currentServer.trafficOk = false;
            currentServer.pingMs    = -1;
            repo.updateServerSync(currentServer);
            FileLogger.i(TAG, "Сервер помечен нерабочим: " + currentServer.host);
        }

        // Перебираем ВСЕ рабочие серверы после обновления
        List<VlessServer> servers = repo.getAllWorkingServersSync();
        if (servers.isEmpty()) {
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this,
                    "Нет рабочих серверов!", true));
            return;
        }

        VlessServer next = servers.get(0); // первый = самый быстрый из оставшихся
        FileLogger.i(TAG, "Переключаемся на: " + next.host);
        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this,
                "Переключение на " + next.remark, true));

        long newId = System.currentTimeMillis();
        activeConnectId = newId;
        new Thread(() -> connect(next, newId), "reconnect-thread").start();
    }

    // ── Tunnel control ───────────────────────────────────────────────────────

    private void startHev() {
        if (vpnInterface == null) return;
        try {
            // Небольшая пауза чтобы старые Go goroutines освободили ресурсы
            Thread.sleep(200);
            hevTunnel = new HevTunnel(this);
            hevTunnel.start(vpnInterface);
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка запуска hev", e);
        }
    }

    private void stopHev() {
        HevTunnel hev = hevTunnel;
        hevTunnel = null;
        if (hev != null) try { hev.stop(); } catch (Exception ignored) {}
    }

    private void fullStop() {
        // ════════════════════════════════════════════════════════════════
        // ← Защита от повторного вызова
        // ════════════════════════════════════════════════════════════════
        if (isStopping) {
            FileLogger.d(TAG, "fullStop() already in progress — skipping");
            return;
        }
        isStopping = true;

        FileLogger.i(TAG, "fullStop()");
        isRunning = false;
        connectedServer = null;
        totalUp   = 0;
        totalDown = 0;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            statsHandler.removeCallbacks(statsPoller);
            if (checkHandler != null) checkHandler.removeCallbacks(checkRunnable);
        } else {
            mainHandler.post(() -> {
                statsHandler.removeCallbacks(statsPoller);
                if (checkHandler != null) checkHandler.removeCallbacks(checkRunnable);
            });
        }

        unregisterNetworkCallback();
        stopHev();

        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        V2RayManager mgr = v2RayManager;
        v2RayManager = null;
        if (mgr != null) mgr.stop();

        Thread t = v2rayThread;
        v2rayThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException ignored) {}
        }

        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception ignored) {}
            vpnInterface = null;
        }

        // ════════════════════════════════════════════════════════════════
        // ← Сбросить флаг
        // ════════════════════════════════════════════════════════════════
        isStopping = false;
    }

    private void disconnect() {
        checkHandler.removeCallbacks(checkRunnable);
        fullStop();
        // Немедленно уведомляем UI — не ждём onStopped() от Go runtime
        mainHandler.post(() -> {
            ServerTester.setVpnActive(false);
            StatusBus.done(VpnTunnelService.this, "Отключено");
            sendVpnBroadcast(false, null, null);
            notifyConnectionChanged(false);
        });
        stopForeground(true);
        stopSelf();
    }

    // ── TUN builder ──────────────────────────────────────────────────────────

    private void addRoutesExcluding(Builder builder, String serverHost) {
        try {
            byte[] ip = InetAddress.getByName(serverHost).getAddress();
            for (String[] r : subtractRoute(0, ip)) {
                builder.addRoute(r[0], Integer.parseInt(r[1]));
            }
        } catch (Exception e) {
            builder.addRoute("0.0.0.0", 0);
        }
    }

    private List<String[]> subtractRoute(int prefix, byte[] excludeIp) {
        List<String[]> result = new ArrayList<>();
        long excl = bytesToLong(excludeIp);
        for (int p = prefix; p < 32; p++) {
            long blockSize  = 1L << (32 - p - 1);
            long blockStart = (excl >> (32 - p - 1)) * blockSize;
            long otherStart = blockStart ^ blockSize;
            result.add(new String[]{longToIp(otherStart), String.valueOf(p + 1)});
        }
        return result;
    }

    private long bytesToLong(byte[] b) {
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16)
             | ((b[2] & 0xFFL) <<  8) |  (b[3] & 0xFFL);
    }

    private String longToIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "."
             + ((ip >>  8) & 0xFF) + "." +  (ip & 0xFF);
    }

    // ── NetworkCallback ───────────────────────────────────────────────────────

    private void registerNetworkCallback() {
        if (connectivityManager == null) return;
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    setUnderlyingNetworks(new Network[]{n});
                }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                    setUnderlyingNetworks(new Network[]{n});
                }
                @Override public void onLost(Network n) {
                    setUnderlyingNetworks(null);
                }
            };
            connectivityManager.requestNetwork(req, networkCallback);
        } catch (Exception e) {
            FileLogger.e(TAG, "registerNetworkCallback error", e);
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                //FileLogger.d(TAG, "NetworkCallback unregistered");
            } catch (IllegalArgumentException e) {
                // ════════════════════════════════════════════════════════════════
                // ← Это нормально — callback уже был отREGISTERED
                // ════════════════════════════════════════════════════════════════
                FileLogger.d(TAG, "NetworkCallback already unregistered (ignored)");
            } catch (Exception e) {
                FileLogger.w(TAG, "unregisterNetworkCallback error: " + e.getMessage());
            } finally {
                networkCallback = null;  // ← Всегда сбрасываем
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    /** Базовые значения трафика на момент старта VPN */
    private static long tunBaseUp   = 0;
    private static long tunBaseDown = 0;

    /**
     * Фиксирует базовую точку при подключении VPN.
     */
    private static void resetTunBase(Context ctx) {
        long[] raw = readTunRaw(ctx);
        tunBaseUp   = raw[0];
        tunBaseDown = raw[1];
        FileLogger.d(TAG, "resetTunBase: baseUp=" + tunBaseUp + " baseDown=" + tunBaseDown);
    }

    /**
     * Читает абсолютный трафик нашего UID.
     * На Samsung Android 16 /proc/net/dev и /proc/self/net/dev закрыты SELinux.
     * Используем TrafficStats.getUidTxBytes — трафик нашего UID.
     * Это немного шире туннельного (включает служебный трафик приложения),
     * но при активном VPN большая часть — туннель.
     */
    private static long[] readTunRaw(Context ctx) {
        // Метод 1: NetworkStatsManager — точный трафик по интерфейсу (требует USAGE_STATS)
        // Метод 2: TrafficStats.getUidTxBytes — трафик нашего UID (без разрешений)
        try {
            int uid = ctx.getApplicationInfo().uid;
            long tx = android.net.TrafficStats.getUidTxBytes(uid);
            long rx = android.net.TrafficStats.getUidRxBytes(uid);
            if (tx != android.net.TrafficStats.UNSUPPORTED
                    && rx != android.net.TrafficStats.UNSUPPORTED) {
                return new long[]{tx, rx};
            }
        } catch (Exception e) {
            FileLogger.w(TAG, "readTunRaw error: " + e.getMessage());
        }
        return new long[]{0, 0};
    }

    /**
     * Трафик с момента подключения VPN (дельта от базы).
     */
    private long[] readTunStats() {
        long[] raw = readTunRaw(this);
        return new long[]{
            Math.max(0, raw[0] - tunBaseUp),
            Math.max(0, raw[1] - tunBaseDown)
        };
    }

    private static String fmtBytes(long bytes) {
        if (bytes < 1024)              return bytes + " B";
        if (bytes < 1024 * 1024)       return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** Форматирует скорость в байт/с → KB/s, MB/s */
    private static String fmtSpeed(long bytesPerSec) {
        // Всегда KB/s до 10 MB/s — единица не меняется, разряды фиксированы
        // Формат: "XXXX.X KB/s" — 4 цифры до точки + 1 после = поле 6 символов
        if (bytesPerSec < 10 * 1024 * 1024) {
            return String.format("%6.1f KB/s", bytesPerSec / 1024.0);
        }
        return String.format("%6.1f MB/s", bytesPerSec / (1024.0 * 1024));
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "VPN",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("VPN статус");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disconnPi = PendingIntent.getService(this, 0,
                new Intent(this, VpnTunnelService.class).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .addAction(0, "Отключить", disconnPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text));
    }
}
