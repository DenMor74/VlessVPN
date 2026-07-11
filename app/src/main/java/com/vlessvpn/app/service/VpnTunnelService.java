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

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class VpnTunnelService extends VpnService {


    static final String TUN_ADDRESS = "10.10.14.1";
    static final int    TUN_PREFIX  = 24;            // ← /30, не /24
    //static final String TUN_DNS     = "10.10.14.2";  // ← роутер как DNS
    static final String TUN_DNS     = "8.8.8.8";

    private static final String TAG = "VpnTunnelService";
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_CONNECT = "com.vlessvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vlessvpn.DISCONNECT";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_AUTO_CONNECT = "auto_connect";

    private volatile boolean isStopping = false;
    private volatile boolean isAutoConnectMode = false;
    private final AtomicBoolean isSwitching = new AtomicBoolean(false);

    private static final CopyOnWriteArrayList<Consumer<Boolean>> connectionListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService coreExecutor = Executors.newCachedThreadPool();
    public static void registerConnectionListener(Context ctx, Consumer<Boolean> listener) {
        connectionListeners.add(listener);
        listener.accept(isRunning);
    }

    private static void notifyConnectionChanged(boolean connected) {
        for (Consumer<Boolean> l : connectionListeners) {
            try { l.accept(connected); } catch (Exception ignored) {}
        }
    }

    private static volatile VpnTunnelService instance;
    public static volatile boolean isRunning = false;
    public static volatile boolean haveInternet = false;
    public static volatile boolean whiteInternet = false; // проверка что интернет по белым спискам
    public static volatile boolean isIpDetermined = false;
    public static volatile boolean isTunnelVerified = false;

    public static volatile VlessServer connectedServer = null;
    public static volatile long totalUp = 0;
    public static volatile long totalDown = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler checkHandler = new Handler(Looper.getMainLooper());

    private ScheduledExecutorService statsExecutor;
    private ScheduledFuture<?> statsFuture;
    private ExecutorService bgExecutor;

    private long prevUp = 0;
    private long prevDown = 0;

    private int failCount = 0;
    private int deepCheckRetryCount = 0;
    private static final int DEEP_CHECK_MAX_RETRIES = 5;

    private final Runnable statsPoller = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            long[] tun = readTunStats();
            totalUp = tun[0];
            totalDown = tun[1];

            long speedUp = Math.max(0, totalUp - prevUp);
            long speedDown = Math.max(0, totalDown - prevDown);
            prevUp = totalUp;
            prevDown = totalDown;

            String speedStr = "↑ " + fmtSpeed(speedUp) + "  ↓ " + fmtSpeed(speedDown);
            if (connectedServer != null) updateNotification(speedStr, connectedServer.host);
            mainHandler.post(() -> StatusBus.post(speedStr, true));
        }
    };

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            safeExecute(() -> {
                verifyTunnelConnection(false, currentServer);
                if (isRunning) checkHandler.postDelayed(this, 60_000L);
            });
        }
    };

    private ParcelFileDescriptor vpnInterface;
    private V2RayManager v2RayManager;
    private HevTunnel hevTunnel;
    private Thread v2rayThread;
    private VlessServer currentServer;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Object connectLock = new Object();
    private volatile long activeConnectId = 0;

    private NotificationCompat.Builder notifBuilder;

    public static VpnTunnelService getInstance() { return instance; }
    public static VlessServer getCurrentServer() { return connectedServer; }

    public static int getTunFd() {
        VpnTunnelService svc = instance;
        if (svc != null && svc.vpnInterface != null) {
            return svc.vpnInterface.getFd();
        }
        FileLogger.w(TAG, "getTunFd() — vpnInterface == null");
        return -1;
    }

    public void runSpeedTest() {
        if (bgExecutor != null && !bgExecutor.isShutdown()) bgExecutor.execute(this::doSpeedTest);
    }

    public void runDeepCheck() {
        if (!isTunnelVerified) return;
        isIpDetermined = false;
        if (bgExecutor != null && !bgExecutor.isShutdown()) bgExecutor.execute(this::doDeepCheck);
    }

    public static boolean protectSocket(int fd) {
        VpnTunnelService svc = instance;
        return svc != null && svc.protect(fd);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        createNotificationChannel();
        V2RayManager.initEnvOnce(this);
        bgExecutor = Executors.newSingleThreadExecutor();
        statsExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, getCachedNotification("Подключение...", "Подождите..."));

        String action = intent != null ? intent.getAction() : null;

        if (intent != null) {
            isAutoConnectMode = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false);
        }

        if (ACTION_DISCONNECT.equals(action)) {
            boolean isSystemCleanup = intent != null && intent.getBooleanExtra("system_cleanup", false);
            if (!isSystemCleanup) {
                AutoConnectManager.cancelAutoConnect();
            }
            new Thread(this::disconnect, "disconnect-thread").start();
            return START_NOT_STICKY;
        }

        VlessServer server = null;
        if (intent != null) {
            String json = intent.getStringExtra(EXTRA_SERVER);
            if (json != null) {
                try {
                    server = new Gson().fromJson(json, VlessServer.class);
                } catch (Exception e) {
                    FileLogger.e(TAG, "Ошибка парсинга сервера", e);
                }
            }
        }

        if (server == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        updateNotification("Подключение...", server.host);
        mainHandler.post(() -> StatusBus.post(this, "Подключение...", true));

        if (bgExecutor == null || bgExecutor.isShutdown()) {
            bgExecutor = Executors.newSingleThreadExecutor();
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
        if (statsExecutor != null) statsExecutor.shutdownNow();
        disconnect();
    }

    @Override
    public void onRevoke() {
        disconnect();
    }

    private void connect(VlessServer server, long connectId) {
        synchronized (connectLock) {
            if (connectId != activeConnectId) return;
            if (isRunning || v2RayManager != null || hevTunnel != null) fullStop();
            if (connectId != activeConnectId) return;
        }

        isStopping = false;
        currentServer = server;
        isIpDetermined = false; // Сбрасываем флаг определения IP
        isTunnelVerified = false;
        mainHandler.post(() -> {
            StatusBus.post(this, "🔬 Определение IP...", true);
            StatusBus.postServiceStatus(this, false, false);
            AodOverlayService.sendServiceStatus(this, false, false);
        });

        v2rayThread = new Thread(() -> {
            try {
                mainHandler.post(() -> StatusBus.post(this, "Ping сервера: "+ server.host + " ...", true));
                ServerTester.TestResult ping = ServerTester.tcpTest(this, server);
                if (ping.pingMs < 0) {
                    mainHandler.post(() -> StatusBus.post(this, "Ping сервера: "+ server.host + " - недоступен", true));
                    FileLogger.w(TAG, "TCP недоступен: " + server.host + " → пропускаем");
                    // Сразу переключаемся без запуска xray
                    safeExecute(() -> switchToNextServer());
                    return;
                }
                FileLogger.i(TAG, "PING: " + ping.pingMs);
                mainHandler.post(() -> StatusBus.post(this, "PING: " + ping.pingMs, true));
                vpnInterface = buildTunWithRetries(server);
                if (vpnInterface == null) {
                    mainHandler.post(() -> {
                        StatusBus.done("Не удалось создать TUN");
                        disconnect();  // ← ИСПРАВЛЕНО: disconnect() вместо stopSelf()
                    });
                    return;
                }
                registerNetworkCallback();
                startV2RayAndHev(server);
            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка подключения", e);
                mainHandler.post(() -> {
                    StatusBus.done(e.getMessage());
                    disconnect();  // ← ИСПРАВЛЕНО: disconnect() вместо stopSelf()
                });
            }
        }, "v2ray-thread");
        v2rayThread.setDaemon(true);
        v2rayThread.start();
    }

    private ParcelFileDescriptor buildTunWithRetries(VlessServer server) {
        for (int i = 1; i <= 4; i++) {
            try {
                if (VpnService.prepare(this) != null) return null;

                Builder builder = new Builder();
                builder.setSession("VlessVPN");
                builder.setMtu(1380);
                builder.addAddress(TUN_ADDRESS, TUN_PREFIX);
                builder.addDnsServer(TUN_DNS);

                // ← КРИТИЧНО: всегда добавляем дефолтный маршрут 0.0.0.0/0
                builder.addRoute("0.0.0.0", 0);

                // Исключаем только сам VPN-сервер, чтобы не было петли
                try {
                    byte[] serverIp = InetAddress.getByName(server.host).getAddress();
                    // Добавляем все маршруты, кроме самого сервера
                    for (String[] r : subtractRoute(0, serverIp)) {
                        builder.addRoute(r[0], Integer.parseInt(r[1]));
                    }
                } catch (Exception e) {
                    FileLogger.w(TAG, "Не удалось исключить сервер IP, используем полный 0.0.0.0/0");
                }

                try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
                applyAppBlacklist(builder);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false);
                }

                ParcelFileDescriptor pfd = builder.establish();
                if (pfd != null) {
                    FileLogger.i(TAG, "TUN установлен успешно (подсеть /" + TUN_PREFIX + ")");
                    return pfd;
                }
            } catch (Exception e) {
                FileLogger.w(TAG, "Попытка " + i + " создания TUN провалилась: " + e.getMessage());
            }
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
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

    // ════════════════════════════════════════════════════════════════
    // ← ИСПРАВЛЕНО: onError теперь вызывает disconnect()
    // ════════════════════════════════════════════════════════════════
    private void startV2RayAndHev(VlessServer server) {
        final long myConnectId = activeConnectId;

        v2RayManager = new V2RayManager(this, new V2RayManager.StatusCallback() {
            @Override public void onStarted(VlessServer s) {
                startHev();
                synchronized (VpnTunnelService.this) {
                    if (bgExecutor == null || bgExecutor.isShutdown()) {
                        bgExecutor = Executors.newSingleThreadExecutor();
                        FileLogger.w(TAG, "bgExecutor пересоздан в onStarted");
                    }
                    bgExecutor.execute(() -> resetTunBase(VpnTunnelService.this));
                }

                mainHandler.post(() -> {
                    updateNotification("Подключено", s.host);
                    isRunning = true;
                    connectedServer = s;
                    currentServer = s;
                    totalUp = totalDown = prevUp = prevDown = 0;
                    failCount = deepCheckRetryCount = 0;
                    isIpDetermined = false;
                    isTunnelVerified = false;

                    ServerTester.setVpnActive(true);
                    RemoteLogger.getInstance(VpnTunnelService.this).start();
                    startFastVerification(s);

                    checkHandler.removeCallbacks(checkRunnable);
                    checkHandler.postDelayed(checkRunnable, 60_000L);

                    sendVpnBroadcast(true, s, null);
                    notifyConnectionChanged(true);
                });

                if (statsFuture != null) statsFuture.cancel(false);
                if (statsExecutor != null && !statsExecutor.isShutdown()) {
                    statsFuture = statsExecutor.scheduleAtFixedRate(statsPoller, 500, 1000, TimeUnit.MILLISECONDS);
                }
            }

            @Override public void onStopped() {
                mainHandler.post(() -> {
                    ServerTester.setVpnActive(false);
                    RemoteLogger.getInstance(VpnTunnelService.this).stop();
                    StatusBus.done(VpnTunnelService.this, "Отключено");
                    sendVpnBroadcast(false, null, null);
                    notifyConnectionChanged(false);
                    AodOverlayService.sendStatus(VpnTunnelService.this, false, null, null, null);
                });
            }

            // ════════════════════════════════════════════════════════════════
            // ← ИСПРАВЛЕНО: onError вызывает disconnect() для полной очистки
            // ════════════════════════════════════════════════════════════════
            @Override public void onError(String error) {
                FileLogger.e(TAG, "V2Ray ошибка: " + error);
                mainHandler.post(() -> {
                    ServerTester.setVpnActive(false);
                    StatusBus.done(VpnTunnelService.this, error);
                    sendVpnBroadcast(false, null, error);
                    notifyConnectionChanged(false);
                    AodOverlayService.sendStatus(VpnTunnelService.this, false, null, null, null);
                    disconnect();  // ← ИСПРАВЛЕНО: disconnect() вместо stopSelf()
                });
            }

            @Override public void onStatsUpdate(long up, long down) {
                totalUp = up; totalDown = down;
            }
        });

        v2RayManager.start(server);

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
            if (error != null) b.putExtra("error", error);
            sendBroadcast(b);
        } catch (Exception ignored) {}
    }

    /** Вызывается когда тоннель подтверждён — общая логика успеха */
    private void onTunnelVerified(VlessServer server) {
        FileLogger.i(TAG, "Старт проверка: OK");
        isTunnelVerified = true;
        failCount = 0;
        new ServerRepository(this).saveLastWorkingServer(server);
        mainHandler.post(() -> StatusBus.post(this, "✅ Подключено: " + server.remark, true));
        AutoConnectManager.reportVerificationResult(true);
        if (new ServerRepository(this).isDeepCheckOnConnect()) {
            mainHandler.postDelayed(this::doDeepCheck, 1500);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ОБЪЕДИНЕННАЯ И УПРОЩЕННАЯ ЛОГИКА ПРОВЕРОК СЕТИ И ТУННЕЛЯ
    // ════════════════════════════════════════════════════════════════════════

    private void startFastVerification(VlessServer server) {
        FileLogger.i(TAG, "=== startFastVerification === Сервер: " + server.host);

        safeExecute(() -> {
            ServerRepository r = new ServerRepository(VpnTunnelService.this);
            int total = r.getCount();
            int workingCount = r.getWorkingCount();

            AodOverlayService.sendStatus(VpnTunnelService.this, true,
                    server.host, "Проверка туннеля...", workingCount + "/" + total);
        });

        mainHandler.post(() -> StatusBus.post(this, "Проверка подключения...", true));
        // Дать туннелю время подняться
        safeExecute(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            verifyTunnelConnection(true, server);
        });
    }

    /**
     * Единый метод для первоначальной и фоновой проверок
     */
    private void verifyTunnelConnection(boolean isInitial, VlessServer server) {
        haveInternet = checkPhysicalInternetBypassingVpn();
        whiteInternet = haveInternet && checkWhiteInternetBypassingVpn();

        FileLogger.i(TAG, "Интернет: " + (haveInternet ? "✅" : "❌") +
                "; Белый: " + (whiteInternet ? "✅" : "❌"));

        if (!haveInternet) {
            if (isInitial) {
                mainHandler.post(() -> StatusBus.post(this, "Ожидание сети...", true));
                AutoConnectManager.reportVerificationResult(false);
            } else {
                failCount = 0; // Ждем появления интернета, VPN не виноват
            }
            return;
        }

        if (checkTunnelProxyFastSync(4)) {
            handleVerificationSuccess(isInitial, server, 1);
            return;
        }

        FileLogger.w(TAG, "1 проверка: FAIL...");

        if (checkTunnelProxyFastSync(6)) {
            handleVerificationSuccess(isInitial, server, 2);
        } else {
            failCount++;
            FileLogger.e(TAG, "Обе проверки: FAIL" + (isInitial ? "!" : "") + " (failCount = " + failCount + ")");
            new ServerRepository(this).clearLastWorkingServer();
            if (isInitial) AutoConnectManager.reportVerificationResult(false);

            FileLogger.i(TAG, "Переключаемся...");
            switchToNextServer();
        }
    }

    private void handleVerificationSuccess(boolean isInitial, VlessServer server, int attempt) {
        FileLogger.i(TAG, attempt + " проверка: OK");
        failCount = 0;

        if (server != null) {
            new ServerRepository(this).saveLastWorkingServer(server);
        }

        if (isInitial && server != null) {
            isTunnelVerified = true;
            mainHandler.post(() -> StatusBus.post(this, "✅ Подключено: " + server.remark, true));
            AutoConnectManager.reportVerificationResult(true);
            if (new ServerRepository(this).isDeepCheckOnConnect()) {
                mainHandler.postDelayed(this::doDeepCheckInternal, 1500);
            }
        }
    }
    /**
     * Универсальный метод проверки внешних URL в обход VPN
     */
    private int checkUrlBypassingVpn(String urlStr, int timeout) {
        if (connectivityManager == null) return -1;
        HttpURLConnection conn = null;
        try {
            Network targetNetwork = null;

            // Сначала пробуем получить текущую "активную" сеть по умолчанию
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNet = connectivityManager.getActiveNetwork();
                if (activeNet != null) {
                    NetworkCapabilities activeCaps = connectivityManager.getNetworkCapabilities(activeNet);
                    if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        targetNetwork = activeNet;
                    }
                }
            }

            // Если активная сеть — VPN или null, ищем любую физическую сеть с интернетом
            if (targetNetwork == null) {
                for (Network net : connectivityManager.getAllNetworks()) {
                    NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(net);
                    if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        targetNetwork = net;
                        // WiFi в приоритете
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            break;
                        }
                    }
                }
            }

            if (targetNetwork == null) {
                FileLogger.w(TAG, "Bypass check: нет подходящей физической сети");
                return -1;
            }

            conn = (HttpURLConnection) targetNetwork.openConnection(new URL(urlStr));
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            // FileLogger.d(TAG, "Bypass check " + urlStr + " -> " + code);
            return code;
        } catch (Exception e) {
            // FileLogger.w(TAG, "Bypass check " + urlStr + " failed: " + e.getMessage());
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean checkPhysicalInternetBypassingVpn() {
        int code = checkUrlBypassingVpn("https://yastatic.net/favicon.ico", 5000);
        return code >= 200 && code < 400;
    }

    private boolean checkWhiteInternetBypassingVpn() {
        int code = checkUrlBypassingVpn("https://cloudpub.ru/", 5000);
        FileLogger.i(TAG, "cloudpub.ru - " + code);
        return !(code >= 200 && code < 400); // Инверсия: если Google доступен, значит "не белый (не РФ)" интернет
    }


    // ════════════════════════════════════════════════════════════════
    // ← Switch Server
    // ════════════════════════════════════════════════════════════════

    private void switchToNextServer() {
        if (VpnController.getInstance(this).isUserManuallyDisconnected()) {
            FileLogger.i(TAG, "switchToNextServer: пропуск — пользователь отключил вручную");
            disconnect();
            return;
        }
        if (!isSwitching.compareAndSet(false, true)) return;

        try {
            ServerRepository repo = new ServerRepository(this);
            if (currentServer != null) {
                currentServer.trafficOk = false;
                currentServer.pingMs = -1;
                repo.updateServerSync(currentServer);
            }

            List<VlessServer> servers = repo.getAllWorkingServersSync();
            if (servers.isEmpty()) {
                mainHandler.post(() -> StatusBus.post(this, "Рабочие серверы закончились! Скачиваем новые...", true));
                FileLogger.w(TAG, "switchToNextServer: список рабочих серверов пуст. Запуск экстренного обновления.");
                BackgroundMonitorService.runWhitelistDownloadThenScanNow(this);
                disconnect();
                return;
            }

            VlessServer next = servers.get(0);
            mainHandler.post(() -> StatusBus.post(this, "Переключение на " + next.remark, true));

            long newId = System.currentTimeMillis();
            activeConnectId = newId;
            new Thread(() -> connect(next, newId), "reconnect-thread").start();

        } finally {
            isSwitching.set(false);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ← Tunnel Control
    // ════════════════════════════════════════════════════════════════

    private void startHev() {
        if (vpnInterface == null) return;
        try {
            Thread.sleep(200);
            hevTunnel = new HevTunnel(this);
            hevTunnel.start(vpnInterface);
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка запуска HevTunnel", e);
        }
    }

    private void stopHev() {
        HevTunnel h = hevTunnel;
        hevTunnel = null;
        if (h != null) try { h.stop(); } catch (Exception ignored) {}
    }

    // ════════════════════════════════════════════════════════════════
    // ← ИСПРАВЛЕНО: fullStop() теперь закрывает VPN интерфейс
    // ════════════════════════════════════════════════════════════════

    private void fullStop() {
        if (isStopping) return;
        isStopping = true;

        isRunning = false;
        connectedServer = null;

        if (statsFuture != null) {
            statsFuture.cancel(false);
            statsFuture = null;
        }

        checkHandler.removeCallbacks(checkRunnable);
        unregisterNetworkCallback();
        stopHev();

        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        V2RayManager mgr = v2RayManager;
        v2RayManager = null;
        if (mgr != null) {
            mgr.stop();
            FileLogger.i(TAG, "V2Ray остановлен");
        }

        Thread t = v2rayThread;
        v2rayThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException ignored) {}
        }

        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: Явно закрываем VPN интерфейс
        // ════════════════════════════════════════════════════════════════
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
                //FileLogger.i(TAG, "VPN интерфейс закрыт");
            } catch (Exception e) {
                FileLogger.w(TAG, "Ошибка закрытия VPN интерфейса: " + e.getMessage());
            }
            vpnInterface = null;
        }

        isStopping = false;
       // FileLogger.i(TAG, "fullStop() завершено");
    }

    // ════════════════════════════════════════════════════════════════
    // ← ИСПРАВЛЕНО: disconnect() всегда вызывает fullStop()
    // ════════════════════════════════════════════════════════════════

    private void disconnect() {
        FileLogger.i(TAG, "═══════════════════════════════════════");
        FileLogger.i(TAG, "Отключение VPN (полная очистка)");
        FileLogger.i(TAG, "═══════════════════════════════════════");

        checkHandler.removeCallbacks(checkRunnable);
        fullStop();

        mainHandler.post(() -> {
            ServerTester.setVpnActive(false);
            StatusBus.done(this, "Отключено");
            sendVpnBroadcast(false, null, null);
            notifyConnectionChanged(false);
            AodOverlayService.sendStatus(this, false, null, null, null);
        });

        stopForeground(true);
        stopSelf();

        FileLogger.i(TAG, "VPN полностью отключён");
    }

    // ════════════════════════════════════════════════════════════════
    // ← Deep Check (метод уже был в вашем коде)
    // ════════════════════════════════════════════════════════════════

    public void doDeepCheck() {
        if (bgExecutor == null || bgExecutor.isShutdown()) {
            bgExecutor = Executors.newSingleThreadExecutor();
        }
        bgExecutor.execute(this::doDeepCheckInternal);
    }

// ════════════════════════════════════════════════════════════════
// В doDeepCheckInternal() — добавить больше сервисов
// ════════════════════════════════════════════════════════════════
    private void doDeepCheckInternal() {
        if (!isRunning) return;
        if (!isTunnelVerified) return;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            bgExecutor.execute(this::doDeepCheckInternal);
            return;
        }

        final long checkConnectId = activeConnectId;

        mainHandler.post(() -> {
            StatusBus.post(this, "🔍 Проверка доступности сервисов...", true);
            StatusBus.postServiceStatus(this, false, false); // Сбрасываем иконки в серый
            AodOverlayService.sendServiceStatus(this, false, false);
        });

        // Атомарные флаги для статуса сервисов
        AtomicBoolean tgOk = new AtomicBoolean(false);
        AtomicBoolean ytOk = new AtomicBoolean(false);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);

        // 1. ПАРАЛЛЕЛЬНАЯ ПРОВЕРКА TELEGRAM
        safeExecute(() -> {
            try {
                if (activeConnectId != checkConnectId) return;
                boolean ok = checkUrlStatusThroughProxy("https://t.me/favicon.ico", 4000);
                if (activeConnectId != checkConnectId) return;
                tgOk.set(ok);
                StatusBus.postPingResult(getApplicationContext(), "tg", ok);
                FileLogger.i(TAG, "=== Telegram: " + ok);
            } finally {
                latch.countDown();
            }
        });

        // 2. ПАРАЛЛЕЛЬНАЯ ПРОВЕРКА YOUTUBE
        safeExecute(() -> {
            try {
                if (activeConnectId != checkConnectId) return;
                boolean ok = checkUrlStatusThroughProxy("https://www.youtube.com/favicon.ico", 4000);
                if (activeConnectId != checkConnectId) return;
                ytOk.set(ok);
                StatusBus.postPingResult(getApplicationContext(), "yt", ok);
                FileLogger.i(TAG, "=== Youtube:: " + ok);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        if (activeConnectId != checkConnectId) return;

        // Обновляем UI один раз после обеих проверок (или таймаута)
        AodOverlayService.sendServiceStatus(this, tgOk.get(), ytOk.get());
        updateConnectedMessage(tgOk.get(), ytOk.get());

        // 3. ПАРАЛЛЕЛЬНОЕ ОПРЕДЕЛЕНИЕ IP (запускаем сразу все сервисы)
        AodOverlayService.sendStatus(this, true,
                currentServer != null ? currentServer.host : null,
                "Определяем IP...", null);

        String[][] services = {
                {"http://ip-api.com/json?fields=query,city,countryCode,isp", "ip-api.com (HTTP)"},
                {"https://ipapi.co/json/", "ipapi.co"},
                {"https://ipinfo.io/json", "ipinfo.io"},
                {"http://ipwhois.app/json/", "ipwhois.app"}
        };

        for (String[] service : services) {
            safeExecute(() -> {
                if (isIpDetermined || activeConnectId != checkConnectId) return;
                if (tryGetIpFromService(service[0], service[1], checkConnectId)) {
                    FileLogger.i(TAG, "IP determined by " + service[1]);
                }
            });
        }
    }

/**
 * Вспомогательный метод для обновления сообщения о подключении с учетом статуса сервисов
 */
private void updateConnectedMessage(boolean tg, boolean yt) {
    String remark = (connectedServer != null) ? (connectedServer.remark.isEmpty() ? connectedServer.host : connectedServer.remark) : "";
    String msg = "✅ Подключено: " + remark;
    if (!tg || !yt) {
        msg += " (";
        if (!tg) msg += "no TG";
        if (!tg && !yt) msg += ", ";
        if (!yt) msg += "no YT";
        msg += ")";
    }
    final String finalMsg = msg;
    mainHandler.post(() -> StatusBus.post(this, finalMsg, true));
}

    private boolean checkUrlStatusThroughProxy(String urlStr, int timeout) {
        HttpURLConnection conn = null;
        int socksPort = 10808; // fallback
        try {
            socksPort = new ServerRepository(this).getLocalSocksPort();
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS, new java.net.InetSocketAddress("127.0.0.1", socksPort));
            conn = (HttpURLConnection) new URL(urlStr).openConnection(proxy);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestMethod("HEAD"); // Быстрее чем GET
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (VlessVPN; Android)");
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            FileLogger.w(TAG, "Ошибка проверки " + urlStr + " через SOCKS:" + socksPort + " -> " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

/*    private void doDeepCheckInternal() {
        if (!isRunning) {
            FileLogger.w(TAG, "doDeepCheckInternal: VPN уже отключён");
            return;
        }
        if (!isTunnelVerified) {
            FileLogger.d(TAG, "IP Check отклонён: туннель еще не прошел проверку связи");
            return;
        }
        if (isIpDetermined) {
            FileLogger.d(TAG, "IP уже найден!");
            return;
        }

        // Защита от запуска на главном потоке
        if (Looper.myLooper() == Looper.getMainLooper()) {
            FileLogger.e(TAG, "Deep Check запущен на UI-потоке! Перезапускаем в фоне.");
            bgExecutor.execute(this::doDeepCheckInternal);
            return;
        }

        AodOverlayService.sendStatus(this, true, currentServer != null ? currentServer.host : null, "Определяем IP...", null);
        mainHandler.post(() -> StatusBus.post(this, "🔍 Определяем внешний IP...", true));

        // ════════════════════════════════════════════════════════════════
        // Цепочка сервисов (все на HTTPS + более стабильный порядок)
        // ════════════════════════════════════════════════════════════════
        String[][] services = {
                // { URL, название сервиса }
                //{"https://ip-api.com/json?fields=query,city,countryCode,isp",          "ip-api.com (HTTPS)"},
               // {"https://ip-api.com/json?fields=query,city,countryCode,isp&lang=ru", "ip-api.com (RU)"},
                {"https://ipapi.co/json/",                                             "ipapi.co"},
                {"https://ipinfo.io/json",                                             "ipinfo.io"},
               // {"https://freeipapi.com/json",                                         "freeipapi.com"},
               // {"https://ipwhois.app/json/",                                          "ipwhois.app"},
        };

        boolean success = false;
        for (String[] service : services) {
            if (success) break;

            FileLogger.d(TAG, "Deep Check → пробуем " + service[1]);
            success = tryGetIpFromService(service[0], service[1]);

           // if (!success) {
           //     FileLogger.w(TAG, "Deep Check: " + service[1] + " не ответил");
           // }
        }

        if (!success) {
            deepCheckRetryCount++;
            String retryMsg = (deepCheckRetryCount <= DEEP_CHECK_MAX_RETRIES)
                    ? "✗ Все сервисы не ответили, повтор " + deepCheckRetryCount + "/" + DEEP_CHECK_MAX_RETRIES
                    : "✗ Не удалось определить IP";

            FileLogger.w(TAG, "Deep Check: " + retryMsg);
            AodOverlayService.sendStatus(this, true, currentServer != null ? currentServer.host : null, retryMsg, null);
            mainHandler.post(() -> StatusBus.post(this, "🔬 IP: " + retryMsg, true));

            // Авто-повтор только если ещё есть попытки
            if (isRunning && deepCheckRetryCount <= DEEP_CHECK_MAX_RETRIES) {
                checkHandler.postDelayed(() -> {
                    if (isRunning && bgExecutor != null && !bgExecutor.isShutdown()) {
                        bgExecutor.execute(this::doDeepCheckInternal);
                    }
                }, 1500); // 1.5 секунды пауза
            }
        }
    }*/

// ════════════════════════════════════════════════════════════════
// В VpnTunnelService.java — заменить tryGetIpFromService()
// ════════════════════════════════════════════════════════════════

    /**
     * Пытается получить IP через указанный сервис
     * @return true если успешно получил и обработал данные
     */
    private boolean tryGetIpFromService(String urlStr, String serviceName, long checkConnectId) {
        HttpURLConnection conn = null;
        try {
            if (activeConnectId != checkConnectId) return false;

            int socksPort = new ServerRepository(this).getLocalSocksPort();
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
            URL url = new URL(urlStr);

            FileLogger.i(TAG, "IP → Запрос к " + serviceName + ": " + url);

            conn = (HttpURLConnection) url.openConnection(proxy);

            // === КРИТИЧНЫЕ ПАРАМЕТРЫ ===
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);      // 5 секунд
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);

            // Заголовки, которые сильно помогают ip-api.com и остальным
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Connection", "close");   // ← важно! отключает keep-alive

            long startTime = System.currentTimeMillis();
            int responseCode = conn.getResponseCode();

            if (activeConnectId != checkConnectId) return false;

            long duration = System.currentTimeMillis() - startTime;
            FileLogger.i(TAG, "IP → " + serviceName + " ответил: HTTP " + responseCode + " (" + duration + "ms)");

            if (responseCode != 200) {
               // FileLogger.w(TAG, serviceName + " вернул код " + responseCode);
                return false;
            }

            // Читаем ответ
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            String json = sb.toString();
            FileLogger.d(TAG, serviceName + " JSON: " + json.substring(0, Math.min(200, json.length())));

            // ════════════════════════════════════════════════════════════════
            // ← Поддержка разных форматов JSON
            // ════════════════════════════════════════════════════════════════
            String ip = extractJson(json, "query");      // ip-api.com
            if (ip == null || ip.isEmpty()) {
                ip = extractJson(json, "ip");            // freeipapi.com, ipapi.co
            }
            if (ip == null || ip.isEmpty()) {
                ip = extractJson(json, "ipAddress");     // ipinfo.io
            }

            if (activeConnectId != checkConnectId) return false;

            String city = extractJson(json, "city");
            String country = extractJson(json, "countryCode");
            if (country == null) {
                country = extractJson(json, "country");
            }

            String location = "";
            if (city != null && country != null) location = city + ", " + country;
            else if (country != null) location = country;
            else if (city != null) location = city;

            String result = (ip != null && !ip.isEmpty())
                    ? "🔬 ✓ " + ip + " " + location + " (" + duration + "ms)"
                    : "🔬 IP: ✓ ответ получен (" + duration + "ms)";

            FileLogger.i(TAG, "IP найден " + serviceName + ": " + ip);
            isIpDetermined = true;
            deepCheckRetryCount = 0;

            final String finalResult = result;
            final String ipForAod = (ip != null) ? ip + " " + location : "IP получен";

            mainHandler.post(() -> StatusBus.post(this, finalResult, true));
            bgExecutor.execute(() -> {
                ServerRepository r2 = new ServerRepository(VpnTunnelService.this);
                java.util.List<com.vlessvpn.app.model.VlessServer> all2 = r2.getAllServersSync();
                int total2 = all2.size(), working2 = 0;
                for (com.vlessvpn.app.model.VlessServer sv2 : all2) if (sv2.trafficOk) working2++;
                AodOverlayService.sendStatus(VpnTunnelService.this, true,
                        currentServer != null ? currentServer.host : null,
                        ipForAod, working2 + "/" + total2);
            });

            return true;

        } catch (java.net.SocketTimeoutException e) {
            FileLogger.w(TAG, "Deep Check таймаут " + serviceName + ": " + e.getMessage());
            return false;

        } catch (java.io.IOException e) {
            // Специально ловим именно эту ошибку и продолжаем дальше
            if (e instanceof java.io.EOFException ||
                    e.getMessage() != null && e.getMessage().contains("unexpected end of stream")) {
                FileLogger.w(TAG, serviceName + " → " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } else {
                FileLogger.e(TAG, serviceName + " ошибка: " + e.getMessage());
            }
        } catch (Exception e) {
            FileLogger.e(TAG, "Deep Check ошибка при работе с " + serviceName, e);
            return false;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private void handleDeepCheckRetry(String baseMsg) {
        deepCheckRetryCount++;
        String retryMsg = (deepCheckRetryCount <= DEEP_CHECK_MAX_RETRIES)
                ? baseMsg + ", повтор " + deepCheckRetryCount + "/" + DEEP_CHECK_MAX_RETRIES
                : baseMsg + " (IP не определён)";

        FileLogger.w(TAG, "Deep Check: " + retryMsg);

        AodOverlayService.sendStatus(this, true,
                currentServer != null ? currentServer.host : null, retryMsg, null);

        mainHandler.post(() -> StatusBus.post(this, "🔬 IP: " + retryMsg, true));

        if (isRunning && deepCheckRetryCount <= DEEP_CHECK_MAX_RETRIES) {
            checkHandler.postDelayed(() -> {
                if (isRunning && bgExecutor != null && !bgExecutor.isShutdown()) {
                    bgExecutor.execute(this::doDeepCheckInternal);
                }
            }, 28000);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ← Speed Test
    // ════════════════════════════════════════════════════════════════

    private void doSpeedTest() {
        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "⏱ Тест скорости...", true));
        java.net.HttpURLConnection conn = null;
        try {
            int socksPort = new ServerRepository(VpnTunnelService.this).getLocalSocksPort();
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS, new java.net.InetSocketAddress("127.0.0.1", socksPort));

            conn = (java.net.HttpURLConnection) new java.net.URL("http://speed.cloudflare.com/__down?bytes=262144").openConnection(proxy);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", "VlessVPN/1.0");
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200) {
                mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "⏱ Тест: ✗ HTTP " + code, true));
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

            if (ms < 100) ms = 100;
            double speedMBs = downloaded / 1024.0 / 1024.0 / (ms / 1000.0);
            String speedStr = speedMBs >= 1.0 ? String.format("%.2f MB/s", speedMBs) : String.format("%.0f KB/s", speedMBs * 1024);
            String result = "⏱ ✓ " + speedStr + " (" + downloaded/1024 + " KB / " + ms + " ms)";
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, result, true));
        } catch (Exception e) {
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "⏱ Тест: ✗ Ошибка", true));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ← Routing
    // ════════════════════════════════════════════════════════════════

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
            long blockSize = 1L << (32 - p - 1);
            long blockStart = (excl >> (32 - p - 1)) * blockSize;
            long otherStart = blockStart ^ blockSize;
            result.add(new String[]{longToIp(otherStart), String.valueOf(p + 1)});
        }
        return result;
    }

    private long bytesToLong(byte[] b) {
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
    }

    private String longToIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    /**
     * Надёжный парсер JSON-строки без внешних библиотек.
     * Ищет "key": "value" с любым количеством пробелов.
     */
    private static String extractJson(String json, String key) {
        if (json == null || json.isEmpty() || key == null) return null;

        try {
            // Основной паттерн: "key" : "value" (учитывает пробелы)
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "\"" + key + "\"\\s*:\\s*\"([^\"\\\\]+)\""
            );
            java.util.regex.Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }

            // Запасной паттерн (на случай чисел или значений без кавычек — хотя у тебя все строки)
            pattern = java.util.regex.Pattern.compile(
                    "\"" + key + "\"\\s*:\\s*([^,}\\]]+)"
            );
            matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка парсинга JSON ключа: " + key, e);
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // ← Network Callback
    // ════════════════════════════════════════════════════════════════

    private void registerNetworkCallback() {
        if (connectivityManager == null) return;
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { setUnderlyingNetworks(new Network[]{n}); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { setUnderlyingNetworks(new Network[]{n}); }
                @Override public void onLost(Network n) { setUnderlyingNetworks(null); }
            };
            connectivityManager.requestNetwork(req, networkCallback);
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка регистрации network callback", e);
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
            networkCallback = null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ← Tunnel Check
    // ════════════════════════════════════════════════════════════════

    public static boolean checkTunnelProxyFastSync(int timeout) {
        if (instance == null) return false;
        String[] testUrls = {"https://google.com", "https://github.com", "https://www.wikipedia.org/"};
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicInteger failsCount = new AtomicInteger(0);

        for (String url : testUrls) {
            instance.safeExecute(() -> {
                if (success.get()) return;
                if (checkSingleUrlProxyStatic(instance, url)) {
                    success.set(true);
                    latch.countDown();
                } else {
                    if (failsCount.incrementAndGet() == testUrls.length) {
                        latch.countDown();
                    }
                }
            });
        }

        try { latch.await(timeout, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return success.get();
    }

/*    *//**
     * Проверяет именно TUN-интерфейс — так же как браузер.
     * Никакого явного прокси — трафик идёт через VPN tun автоматически.
     *//*
    public static boolean checkTunnelProxyFastSync() {
        String[] testUrls = {
                "https://google.com",
                "https://github.com",
                "https://www.wikipedia.org/"
        };
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(testUrls.length);

        for (String url : testUrls) {
            pool.execute(() -> {
                if (checkSingleUrlViaTun(url)) {
                    success.set(true);
                    latch.countDown();
                }
            });
        }
        try { latch.await(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        pool.shutdownNow();
        return success.get();
    }*/

    private static boolean checkSingleUrlProxyStatic(Context context, String urlStr) {
        HttpURLConnection conn = null;
        try {
            int socksPort = new ServerRepository(context).getLocalSocksPort();
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
            conn = (HttpURLConnection) new URL(urlStr).openConnection(proxy);
            conn.setConnectTimeout(8000);  // ← было 10000
            conn.setReadTimeout(8000);     // ← было 10000
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }


    // ════════════════════════════════════════════════════════════════
    // ← Notification
    // ════════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("VPN статус");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification getCachedNotification(String title, String text) {
        if (notifBuilder == null) {
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            PendingIntent disconnPi = PendingIntent.getService(this, 0,
                    new Intent(this, VpnTunnelService.class).setAction(ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            notifBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_vpn_notify)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(pi)
                    .addAction(0, "Отключить", disconnPi);
        }
        notifBuilder.setContentTitle(title);
        notifBuilder.setContentText(text);
        return notifBuilder.build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, getCachedNotification(title, text));
    }

    // ════════════════════════════════════════════════════════════════
    // ← Stats
    // ════════════════════════════════════════════════════════════════

    private static long tunBaseUp = 0;
    private static long tunBaseDown = 0;

    private static void resetTunBase(Context ctx) {
        long[] raw = readTunRaw(ctx);
        tunBaseUp = raw[0];
        tunBaseDown = raw[1];
    }

    private static long[] readTunRaw(Context ctx) {
        try {
            int uid = ctx.getApplicationInfo().uid;
            long tx = android.net.TrafficStats.getUidTxBytes(uid);
            long rx = android.net.TrafficStats.getUidRxBytes(uid);
            if (tx != android.net.TrafficStats.UNSUPPORTED && rx != android.net.TrafficStats.UNSUPPORTED) {
                return new long[]{tx, rx};
            }
        } catch (Exception ignored) {}
        return new long[]{0, 0};
    }

    private long[] readTunStats() {
        long[] raw = readTunRaw(this);
        return new long[]{Math.max(0, raw[0] - tunBaseUp), Math.max(0, raw[1] - tunBaseDown)};
    }

    private static String fmtSpeed(long bytesPerSec) {
        if (bytesPerSec < 10 * 1024 * 1024)
            return String.format("%6.1f KB/s", bytesPerSec / 1024.0);
        return String.format("%6.1f MB/s", bytesPerSec / (1024.0 * 1024));
    }



    private void safeExecute(Runnable task) {
        try {
            if (!coreExecutor.isShutdown() && !coreExecutor.isTerminated()) {
                coreExecutor.execute(task);
            }
        } catch (Exception e) {
            FileLogger.w(TAG, "Задача отклонена: пул потоков закрывается");
        }
    }
}