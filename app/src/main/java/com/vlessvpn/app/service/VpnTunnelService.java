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

import org.json.JSONObject;

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
    static final int    TUN_PREFIX  = 30;
    static final String TUN_DNS     = "8.8.8.8";

    private static final String TAG = "VpnTunnelService";
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_CONNECT = "com.vlessvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vlessvpn.DISCONNECT";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_AUTO_CONNECT = "auto_connect";

    private volatile boolean isStopping = false;
    private volatile boolean isDisconnecting = false;
    private volatile boolean isAutoConnectMode = false;

    private static final CopyOnWriteArrayList<Consumer<Boolean>> connectionListeners = new CopyOnWriteArrayList<>();

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
    public static volatile boolean whiteInternet = false;
    public static volatile boolean isIpDetermined = false;
    public static volatile boolean isTunnelVerified = false;

    public static volatile VlessServer connectedServer = null;
    public static volatile long totalUp = 0;
    public static volatile long totalDown = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler checkHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService coreExecutor = Executors.newCachedThreadPool();
    private ScheduledExecutorService statsExecutor;
    private ScheduledFuture<?> statsFuture;

    private long prevUp = 0;
    private long prevDown = 0;

    private int failCount = 0;
    private int deepCheckRetryCount = 0;
    private static final int DEEP_CHECK_MAX_RETRIES = 5;

    private void safeExecute(Runnable task) {
        try {
            if (!coreExecutor.isShutdown() && !coreExecutor.isTerminated()) {
                coreExecutor.execute(task);
            }
        } catch (Exception e) {
            FileLogger.w(TAG, "Задача отклонена: пул потоков закрывается");
        }
    }

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
        safeExecute(this::doSpeedTest);
    }

    public void runDeepCheck() {
        if (!isTunnelVerified) return;
        isIpDetermined = false;
        safeExecute(this::doDeepCheckInternal);
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
        coreExecutor.shutdownNow();
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
        isDisconnecting = false;
        currentServer = server;

        v2rayThread = new Thread(() -> {
            try {
                vpnInterface = buildTunWithRetries(server);
                if (vpnInterface == null) {
                    mainHandler.post(() -> {
                        StatusBus.done("Не удалось создать TUN (Нет прав или ошибка)");
                        disconnect();
                    });
                    return;
                }
                registerNetworkCallback();
                startV2RayAndHev(server);
            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка подключения", e);
                mainHandler.post(() -> {
                    StatusBus.done(e.getMessage());
                    disconnect();
                });
            }
        }, "v2ray-thread");
        v2rayThread.setDaemon(true);
        v2rayThread.start();
    }

    private ParcelFileDescriptor buildTunWithRetries(VlessServer server) {
        try {
            if (VpnService.prepare(this) != null) return null;

            Builder builder = new Builder();
            builder.setSession("VlessVPN");
            builder.setMtu(1500);
            builder.addAddress(TUN_ADDRESS, TUN_PREFIX);
            builder.addDnsServer(TUN_DNS);
            addRoutesExcluding(builder, server.host);

            try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
            applyAppBlacklist(builder);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);

            return builder.establish();
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка создания TUN: " + e.getMessage());
            return null;
        }
    }

    private void applyAppBlacklist(Builder builder) {
        Set<String> blacklist = new AppBlacklistManager(this).getBlacklist();
        for (String pkg : blacklist) {
            try { builder.addDisallowedApplication(pkg); }
            catch (PackageManager.NameNotFoundException ignored) {}
        }
    }

    private void startV2RayAndHev(VlessServer server) {
        final long myConnectId = activeConnectId;

        v2RayManager = new V2RayManager(this, new V2RayManager.StatusCallback() {
            @Override public void onStarted(VlessServer s) {
                if (myConnectId != activeConnectId) {
                    FileLogger.w(TAG, "V2Ray запущен, но сессия прервана. Игнор.");
                    return;
                }

                startHev();
                synchronized (VpnTunnelService.this) {
                    safeExecute(() -> resetTunBase(VpnTunnelService.this));
                }

                mainHandler.post(() -> {
                    if (myConnectId != activeConnectId) return;

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

                    mainHandler.postDelayed(() -> {
                        if (myConnectId == activeConnectId && isRunning) {
                            startFastVerification(s);
                        }
                    }, 800);

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
                if (myConnectId != activeConnectId) return;
                mainHandler.post(() -> {
                    ServerTester.setVpnActive(false);
                    RemoteLogger.getInstance(VpnTunnelService.this).stop();
                    StatusBus.done(VpnTunnelService.this, "Отключено");
                    sendVpnBroadcast(false, null, null);
                    notifyConnectionChanged(false);
                    AodOverlayService.sendStatus(VpnTunnelService.this, false, null, null, null);
                });
            }

            @Override public void onError(String error) {
                if (myConnectId != activeConnectId) return;
                FileLogger.e(TAG, "V2Ray ошибка: " + error);
                mainHandler.post(() -> {
                    ServerTester.setVpnActive(false);
                    StatusBus.done(VpnTunnelService.this, error);
                    sendVpnBroadcast(false, null, error);
                    notifyConnectionChanged(false);
                    AodOverlayService.sendStatus(VpnTunnelService.this, false, null, null, null);
                    disconnect();
                });
            }

            @Override public void onStatsUpdate(long up, long down) {
                totalUp = up; totalDown = down;
            }
        });

        v2RayManager.start(server);

        if (myConnectId == activeConnectId && !isRunning && !isStopping) {
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

    // ════════════════════════════════════════════════════════════════════════
    // ОБЪЕДИНЕННАЯ И УПРОЩЕННАЯ ЛОГИКА ПРОВЕРОК СЕТИ И ТУННЕЛЯ
    // ════════════════════════════════════════════════════════════════════════

    private void startFastVerification(VlessServer server) {
        FileLogger.i(TAG, "Проверка: " + server.host);

        safeExecute(() -> {
            ServerRepository r = new ServerRepository(VpnTunnelService.this);
            int total = r.getAllServersSync().size();
            int workingCount = 0;
            for (VlessServer sv : r.getAllServersSync()) if (sv.trafficOk) workingCount++;

            AodOverlayService.sendStatus(VpnTunnelService.this, true,
                    server.host, "Проверка туннеля...", workingCount + "/" + total);
        });

        mainHandler.post(() -> StatusBus.post(this, "Проверка подключения...", true));
        safeExecute(() -> verifyTunnelConnection(true, server));
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

        if (checkTunnelProxyFastSync(2)) {
            handleVerificationSuccess(isInitial, server, 1);
            return;
        }

        FileLogger.w(TAG, "1 проверка: ❌...");

        if (checkTunnelProxyFastSync(4)) {
            handleVerificationSuccess(isInitial, server, 2);
        } else {
            failCount++;
            FileLogger.e(TAG, "Обе проверки: ❌" + (isInitial ? "!" : "") + " (failCount = " + failCount + ")");
            new ServerRepository(this).clearLastWorkingServer();
            if (isInitial) AutoConnectManager.reportVerificationResult(false);

            FileLogger.i(TAG, "Переключаемся...");
            switchToNextServer();
        }
    }

    private void handleVerificationSuccess(boolean isInitial, VlessServer server, int attempt) {
        FileLogger.i(TAG, attempt + " проверка: ✅");
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
            Network active = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                    connectivityManager.getActiveNetwork() : null;

            if (active == null) {
                for (Network net : connectivityManager.getAllNetworks()) {
                    NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(net);
                    if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        active = net;
                        break;
                    }
                }
            }
            if (active == null) return -1;

            conn = (HttpURLConnection) active.openConnection(new URL(urlStr));
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestMethod("HEAD");
            return conn.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean checkPhysicalInternetBypassingVpn() {
        int code = checkUrlBypassingVpn("https://ya.ru", 2000);
        return code >= 200 && code < 400;
    }

    private boolean checkWhiteInternetBypassingVpn() {
        int code = checkUrlBypassingVpn("https://google.com", 3000);
        return !(code >= 200 && code < 400); // Инверсия: если Google доступен, значит "не белый (не РФ)" интернет
    }

    // ════════════════════════════════════════════════════════════════════════

    private void switchToNextServer() {
        if (VpnController.getInstance(this).isUserManuallyDisconnected()) {
            // FileLogger.i(TAG, "switchToNextServer: пропуск — пользователь отключил вручную");
            disconnect();
            return;
        }

        ServerRepository repo = new ServerRepository(this);
        if (currentServer != null) {
            currentServer.trafficOk = false;
            currentServer.pingMs = -1;
            repo.updateServerSync(currentServer);
        }

        List<VlessServer> servers = repo.getAllWorkingServersSync();
        if (servers.isEmpty()) {
            mainHandler.post(() -> StatusBus.post(this, "Нет рабочих серверов!", true));
            return;
        }

        VlessServer next = servers.get(0);
        mainHandler.post(() -> {
            StatusBus.post(this, "Переключение на " + next.remark, true);
            StatusBus.post(this, "⚡RESET_PANELS", true);
        });

        long newId = System.currentTimeMillis();
        activeConnectId = newId;
        new Thread(() -> connect(next, newId), "reconnect-thread").start();
    }

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

    private void fullStop() {
        if (isStopping) return;
        isStopping = true;

        activeConnectId = 0;
        isRunning = false;
        connectedServer = null;

        if (statsFuture != null) {
            statsFuture.cancel(false);
            statsFuture = null;
        }

        checkHandler.removeCallbacks(checkRunnable);
        unregisterNetworkCallback();
        stopHev();

        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        V2RayManager mgr = v2RayManager;
        v2RayManager = null;
        if (mgr != null) {
            mgr.stop();
            //FileLogger.i(TAG, "V2Ray остановлен");
        }

        Thread t = v2rayThread;
        v2rayThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException ignored) {}
        }

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
        //FileLogger.i(TAG, "fullStop() завершено");
    }

    private void disconnect() {
        if (isDisconnecting) return;
        isDisconnecting = true;
        FileLogger.i(TAG, "══════════════Отключение_VPN══════════════");

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

       // FileLogger.i(TAG, "VPN полностью отключён");
        BackgroundMonitorService.scheduleCatchUpTasks(this);
    }

    private void doDeepCheckInternal() {
        if (!isRunning) return;
        if (!isTunnelVerified) return;
        if (isIpDetermined) return;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            safeExecute(this::doDeepCheckInternal);
            return;
        }

        AodOverlayService.sendStatus(this, true,
                currentServer != null ? currentServer.host : null,
                "Определяем IP...", null);

        mainHandler.post(() -> StatusBus.post(this, "🔍 Определяем внешний IP...", true));

        String[][] services = {
                {"http://ip-api.com/json?fields=query,city,countryCode,isp", "ip-api.com (HTTP)"},
                {"https://ipapi.co/json/", "ipapi.co"},
                {"https://ipinfo.io/json", "ipinfo.io"},
                {"http://ipwhois.app/json/", "ipwhois.app"}
        };

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        for (String[] service : services) {
            safeExecute(() -> {
                if (success.get()) return;
                if (tryGetIpFromService(service[0], service[1])) {
                    if (success.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                }
            });
        }

        try {
            boolean gotIp = latch.await(10, TimeUnit.SECONDS);
            if (!gotIp && !success.get()) {
                handleDeepCheckRetry("✗ Все сервисы не ответили");
            }
        } catch (InterruptedException ignored) {}
    }

    private boolean tryGetIpFromService(String urlStr, String serviceName) {
        HttpURLConnection conn = null;
        try {
            int socksPort = new ServerRepository(this).getLocalSocksPort();
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
            URL url = new URL(urlStr);

            conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept", "application/json");
            conn.setInstanceFollowRedirects(true);

            long startTime = System.currentTimeMillis();
            int responseCode = conn.getResponseCode();
            long duration = System.currentTimeMillis() - startTime;

            if (responseCode != 200) return false;

            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            // ИСПОЛЬЗУЕМ ВСТРОЕННЫЙ БЕЗОПАСНЫЙ JSON ПАРСЕР ANDROID
            String ip = null, city = null, country = null;
            try {
                JSONObject obj = new JSONObject(sb.toString());
                ip = obj.optString("query", null);
                if (ip == null || ip.isEmpty()) ip = obj.optString("ip", null);
                if (ip == null || ip.isEmpty()) ip = obj.optString("ipAddress", null);

                city = obj.optString("city", null);
                country = obj.optString("countryCode", null);
                if (country == null || country.isEmpty()) country = obj.optString("country", null);
            } catch (Exception ignored) {}

            if (ip == null || ip.trim().isEmpty() || ip.equalsIgnoreCase("null")) {
                FileLogger.w(TAG, serviceName + " вернул невалидный IP: " + ip);
                return false;
            }

            String location = "";
            if (city != null && country != null) location = city + ", " + country;
            else if (country != null) location = country;
            else if (city != null) location = city;

            String result = "🔬 ✓ " + ip + " " + location + " (" + duration + "ms)";

            FileLogger.i(TAG, "IP найден " + serviceName + ": " + ip);
            isIpDetermined = true;
            deepCheckRetryCount = 0;

            final String ipForAod = ip + " " + location;

            mainHandler.post(() -> StatusBus.post(this, result, true));
            safeExecute(() -> {
                ServerRepository r2 = new ServerRepository(VpnTunnelService.this);
                java.util.List<VlessServer> all2 = r2.getAllServersSync();
                int total2 = all2.size(), working2 = 0;
                for (VlessServer sv2 : all2) if (sv2.trafficOk) working2++;
                AodOverlayService.sendStatus(VpnTunnelService.this, true,
                        currentServer != null ? currentServer.host : null,
                        ipForAod, working2 + "/" + total2);
            });

            return true;

        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
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
                if (isRunning) safeExecute(this::doDeepCheckInternal);
            }, 28000);
        }
    }

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

            long downloaded = 0;
            long t0 = System.currentTimeMillis();

            try (java.io.InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    downloaded += n;
                }
            }

            long ms = System.currentTimeMillis() - t0;
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

    private static boolean checkSingleUrlProxyStatic(Context context, String urlStr) {
        HttpURLConnection conn = null;
        try {
            int socksPort = new ServerRepository(context).getLocalSocksPort();
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
            conn = (HttpURLConnection) new URL(urlStr).openConnection(proxy);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
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

    public static int getLocalSocksPort(Context context) {
        if (context == null) return 10808;
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        try {
            String portStr = prefs.getString("socks_port", "10808");
            int port = Integer.parseInt(portStr);
            if (port <= 1024 || port > 65535) return 10808;
            return port;
        } catch (Exception e) {
            return 10808;
        }
    }
}