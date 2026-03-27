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
    private volatile boolean isAutoConnectMode = false;

    private static final CopyOnWriteArrayList<Consumer<Boolean>> connectionListeners = new CopyOnWriteArrayList<>();

    public static void registerConnectionListener(android.content.Context ctx, Consumer<Boolean> listener) {
        connectionListeners.add(listener);
        listener.accept(isRunning);
    }

    private static void notifyConnectionChanged(boolean connected) {
        for (Consumer<Boolean> l : connectionListeners) {
            try { l.accept(connected); } catch (Exception e) {}
        }
    }

    private static volatile VpnTunnelService instance;
    public  static volatile boolean    isRunning       = false;
    public  static volatile VlessServer connectedServer = null;
    public  static volatile long        totalUp         = 0;
    public  static volatile long        totalDown       = 0;

    private final Handler mainHandler  = new Handler(Looper.getMainLooper());
    private final Handler checkHandler  = new Handler(Looper.getMainLooper());

    private ScheduledExecutorService statsExecutor;
    private ScheduledFuture<?> statsFuture;
    private ExecutorService bgExecutor;

    private long prevUp   = 0;
    private long prevDown = 0;

    // Счётчики проверок
    private int failCount = 0;
    private int deepCheckRetryCount = 0;
    private static final int DEEP_CHECK_MAX_RETRIES = 5;

    private final Runnable statsPoller = new Runnable() {
        @Override public void run() {
            if (!isRunning) return;

            long[] tun = readTunStats();
            totalUp   = tun[0];
            totalDown = tun[1];

            long speedUp   = Math.max(0, totalUp   - prevUp);
            long speedDown = Math.max(0, totalDown - prevDown);
            prevUp   = totalUp;
            prevDown = totalDown;

            String speedStr = "↑ " + fmtSpeed(speedUp) + "  ↓ " + fmtSpeed(speedDown);
            VlessServer srv = connectedServer;

            if (srv != null) updateNotification(speedStr, srv.host);
            mainHandler.post(() -> StatusBus.post(speedStr, true));
        }
    };

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            if (bgExecutor == null || bgExecutor.isShutdown()) {
                bgExecutor = Executors.newSingleThreadExecutor();
            }
            bgExecutor.execute(() -> {
                doConnectivityCheck();
                if (isRunning) {
                    checkHandler.postDelayed(checkRunnable, 60_000L);
                }
            });
        }
    };

    private ParcelFileDescriptor vpnInterface;
    private V2RayManager         v2RayManager;
    private HevTunnel            hevTunnel;
    private Thread               v2rayThread;
    private VlessServer          currentServer;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Object connectLock    = new Object();
    private volatile long activeConnectId = 0;

    public static int getTunFd() { return 0; }
    public static VpnTunnelService getInstance() { return instance; }

    public void runSpeedTest() {
        if (bgExecutor != null && !bgExecutor.isShutdown()) bgExecutor.execute(this::doSpeedTest);
    }

    public void runDeepCheck() {
        if (bgExecutor != null && !bgExecutor.isShutdown()) bgExecutor.execute(this::doDeepCheck);
    }

    public static boolean protectSocket(int fd) {
        VpnTunnelService svc = instance;
        return svc != null && svc.protect(fd);
    }

    public static VlessServer getCurrentServer() { return connectedServer; }

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
            new Thread(this::disconnect, "disconnect-thread").start();
            return START_NOT_STICKY;
        }

        VlessServer server = null;
        if (intent != null) {
            String json = intent.getStringExtra(EXTRA_SERVER);
            if (json != null) {
                try { server = new Gson().fromJson(json, VlessServer.class); }
                catch (Exception e) {}
            }
        }

        if (server == null) { stopSelf(); return START_NOT_STICKY; }

        updateNotification("Подключение...", server.host);

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
    public void onRevoke() { disconnect(); }

    private void connect(VlessServer server, long connectId) {
        synchronized (connectLock) {
            if (connectId != activeConnectId) return;
            if (isRunning || v2RayManager != null || hevTunnel != null) fullStop();
            if (connectId != activeConnectId) return;
        }
        isStopping = false;
        currentServer = server;

        if (bgExecutor == null || bgExecutor.isShutdown()) {
            bgExecutor = Executors.newSingleThreadExecutor();
        }

        v2rayThread = new Thread(() -> {
            try {
                vpnInterface = buildTunWithRetries(server);
                if (vpnInterface == null) {
                    mainHandler.post(() -> StatusBus.done("Не удалось создать TUN"));
                    stopSelf();
                    return;
                }
                registerNetworkCallback();
                startV2RayAndHev(server);
            } catch (Exception e) {
                mainHandler.post(() -> { StatusBus.done(e.getMessage()); stopSelf(); });
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
            } catch (Exception ignored) {}
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
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
        final long myConnectId = activeConnectId;
        v2RayManager = new V2RayManager(this, new V2RayManager.StatusCallback() {
            @Override public void onStarted(VlessServer s) {
                startHev();

                bgExecutor.execute(() -> resetTunBase(VpnTunnelService.this));

                mainHandler.post(() -> {
                    updateNotification("Подключено", s.host);
                    isRunning      = true;
                    connectedServer = s;
                    currentServer  = s;
                    totalUp = 0; totalDown = 0;
                    prevUp = 0; prevDown = 0;

                    // Сбрасываем счетчики проверок
                    failCount = 0;
                    deepCheckRetryCount = 0;

                    ServerTester.setVpnActive(true);

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
                    StatusBus.done(VpnTunnelService.this, "Отключено");
                    sendVpnBroadcast(false, null, null);
                    notifyConnectionChanged(false);
                    AodOverlayService.sendStatus(VpnTunnelService.this, false, null, null, null);
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
        } catch (Exception e) {}
    }

    public static boolean checkTunnelProxyFastSync() {
        String[] testUrls = {
                "https://www.wikipedia.org",
                "https://github.com",
                "https://google.com",
        };
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(testUrls.length);

        for (String url : testUrls) {
            pool.execute(() -> {
                if (checkSingleUrlProxyStatic(url)) {
                    success.compareAndSet(false, true);
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        pool.shutdownNow();
        return success.get();
    }

/*    private static boolean checkSingleUrlProxyStatic(String urlStr) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS, new java.net.InetSocketAddress("127.0.0.1", 10808));
            conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection(proxy);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }*/
    private static boolean checkSingleUrlProxyStatic(String urlStr) {
        try {
            Proxy proxy = new Proxy(
                    Proxy.Type.SOCKS,
                    new InetSocketAddress("127.0.0.1", 10808)
            );

            HttpURLConnection conn =
                    (HttpURLConnection) new URL(urlStr).openConnection(proxy);

            conn.setConnectTimeout(3000);      // было 3000
            conn.setReadTimeout(3000);         // было 3000
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("HEAD");     // САМОЕ ВАЖНОЕ ускорение

            int code = conn.getResponseCode();

            return code >= 200 && code < 400;

        } catch (Exception ignored) {
            return false;
        }
    }

    private void startFastVerification(VlessServer server) {
        AodOverlayService.sendStatus(this, true, server.host, "Проверка связи...", null);
        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Проверка обхода блокировок...", true));

        bgExecutor.execute(() -> {
            if (new ServerRepository(this).isDeepCheckOnConnect()) {
                Executors.newSingleThreadExecutor().execute(this::doDeepCheckInternal);
            }

            try {
                if (!checkPhysicalInternetBypassingVpn()) {
                    FileLogger.w(TAG, "Нет интернета мимо VPN!");
                    mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Ожидание сети...", true));
                    failCount = 0;
                    AutoConnectManager.reportVerificationResult(false);
                    return;
                }
                Thread.sleep(2000);
                boolean tunnelOk = checkTunnelProxyFastSync();

                if (tunnelOk) {
                    failCount = 0;
                    FileLogger.i(TAG, "=== Трафик OK (fast)");
                    new ServerRepository(this).saveLastWorkingServer(server);
                    mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Подключено: " + server.remark, true));
                    AutoConnectManager.reportVerificationResult(true);
                } else {
                    failCount++;
                    FileLogger.e(TAG, "=== ТРАФИК FAIL! (fast)");
                    new ServerRepository(this).clearLastWorkingServer();
                    AutoConnectManager.reportVerificationResult(false);

                    if (!isAutoConnectMode) {
                        switchToNextServer();
                    }
                    else{
                        FileLogger.e(TAG, "не переключаем сервер - ?");
                    }
                }

            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка проверки: " + e.getMessage());
                AutoConnectManager.reportVerificationResult(false);
                if (!isAutoConnectMode) switchToNextServer();
            }
        });
    }

    private boolean checkPhysicalInternetBypassingVpn() {
        if (connectivityManager == null) return false;
        java.net.HttpURLConnection conn = null;
        try {
            Network activeNetwork = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activeNetwork = connectivityManager.getActiveNetwork();
            }
            if (activeNetwork == null) {
                for (Network net : connectivityManager.getAllNetworks()) {
                    NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(net);
                    if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        activeNetwork = net;
                        break;
                    }
                }
            }
            if (activeNetwork == null) return false;

            conn = (java.net.HttpURLConnection) activeNetwork.openConnection(new java.net.URL("https://ya.ru"));
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            return (code >= 200 && code < 400);
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void doConnectivityCheck() {
        if (!isRunning) return;

        boolean hasPhysicalInternet = checkPhysicalInternetBypassingVpn();
        if (!hasPhysicalInternet) {
            failCount = 0;
            FileLogger.i(TAG, "=== Интернета НЕТ!");
            return;
        }
        FileLogger.i(TAG, "=== Интернет ЕСТЬ!");
        boolean tunnelOk = checkTunnelProxyFastSync();

        if (tunnelOk) {
            FileLogger.i(TAG, "=== Трафик OK!");
            failCount = 0;
            if (currentServer != null) {
                new ServerRepository(this).saveLastWorkingServer(currentServer);
            }
        } else {
            failCount++;
            FileLogger.e(TAG, "=== Трафик пропал (фоновая проверка)!");
            new ServerRepository(this).clearLastWorkingServer();
            switchToNextServer();
        }
    }

    private void switchToNextServer() {
        ServerRepository repo = new ServerRepository(this);
        if (currentServer != null) {
            currentServer.trafficOk = false;
            currentServer.pingMs    = -1;
            repo.updateServerSync(currentServer);
        }

        List<VlessServer> servers = repo.getAllWorkingServersSync();
        if (servers.isEmpty()) {
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Нет рабочих серверов!", true));
            return;
        }

        VlessServer next = servers.get(0);
        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Переключение на " + next.remark, true));

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
            FileLogger.e(TAG, "Ошибка запуска hev", e);
        }
    }

    private void stopHev() {
        HevTunnel hev = hevTunnel;
        hevTunnel = null;
        if (hev != null) try { hev.stop(); } catch (Exception ignored) {}
    }

    private void fullStop() {
        if (isStopping) return;
        isStopping = true;

        isRunning = false;
        connectedServer = null;
        totalUp   = 0;
        totalDown = 0;

        if (statsFuture != null) {
            statsFuture.cancel(false);
            statsFuture = null;
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            checkHandler.removeCallbacks(checkRunnable);
        } else {
            mainHandler.post(() -> {
                checkHandler.removeCallbacks(checkRunnable);
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
        isStopping = false;
    }

    private void disconnect() {
        checkHandler.removeCallbacks(checkRunnable);
        fullStop();
        mainHandler.post(() -> {
            ServerTester.setVpnActive(false);
            StatusBus.done(VpnTunnelService.this, "Отключено");
            sendVpnBroadcast(false, null, null);
            notifyConnectionChanged(false);
        });
        stopForeground(true);
        stopSelf();
    }

    private void doDeepCheck() {
        doDeepCheckInternal();
    }

    private void doDeepCheckInternal() {
        if (!isRunning) return;

        AodOverlayService.sendStatus(this, true, currentServer != null ? currentServer.host : null, "Определяем IP...", null);
        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Проверка IP...", true));

        java.net.HttpURLConnection conn = null;
        try {
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS, new java.net.InetSocketAddress("127.0.0.1", 10808));
            conn = (java.net.HttpURLConnection) new java.net.URL("http://ip-api.com/json?fields=query,city,countryCode,isp").openConnection(proxy);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "VlessVPN/1.0");
            conn.setRequestProperty("Accept", "application/json");

            long t0 = System.currentTimeMillis();
            int code = conn.getResponseCode();
            if (code != 200) {
                String r = "🔬 IP: ✗ HTTP " + code;
                mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, r, true));
                return;
            }

            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            long ms = System.currentTimeMillis() - t0;

            String json = sb.toString();
            String ip      = extractJson(json, "query");
            String city    = extractJson(json, "city");
            String country = extractJson(json, "countryCode");

            String location = "";
            if (city != null && country != null) location = city + ", " + country;
            else if (country != null)            location = country;

            String result = (ip != null) ? "🔬 ✓ " + ip + " " + location + " (" + ms + "ms)" : "🔬 IP: ✓ ответ получен (" + ms + "ms)";
            deepCheckRetryCount = 0;
            FileLogger.i(TAG, result);

            if (ip != null) {
                final String ipLocation = ip + " " + location;
                com.vlessvpn.app.storage.ServerRepository repo = new com.vlessvpn.app.storage.ServerRepository(VpnTunnelService.this);
                java.util.List<com.vlessvpn.app.model.VlessServer> all = repo.getAllServersSync();
                int total = all.size(), working = 0;
                for (com.vlessvpn.app.model.VlessServer s : all) if (s.trafficOk) working++;
                com.vlessvpn.app.service.AodOverlayService.sendStatus(VpnTunnelService.this, true,
                        currentServer != null ? currentServer.host : null, ipLocation, working + "/" + total);
            }
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, result, true));

        } catch (Exception e) {
            deepCheckRetryCount++;
            String retryMsg = deepCheckRetryCount <= DEEP_CHECK_MAX_RETRIES
                    ? "✗ таймаут, повтор " + deepCheckRetryCount + "/" + DEEP_CHECK_MAX_RETRIES
                    : "✗ IP не определён";
            AodOverlayService.sendStatus(this, true, currentServer != null ? currentServer.host : null, retryMsg, null);
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "🔬 IP: " + retryMsg, true));
            if (isRunning && deepCheckRetryCount <= DEEP_CHECK_MAX_RETRIES) {
                checkHandler.postDelayed(() -> {
                    if (isRunning && bgExecutor != null && !bgExecutor.isShutdown()) bgExecutor.execute(this::doDeepCheckInternal);
                }, 30_000L);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String extractJson(String json, String key) {
        try {
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
        java.net.HttpURLConnection conn = null;
        try {
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS, new java.net.InetSocketAddress("127.0.0.1", 10808));
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

    private void addRoutesExcluding(Builder builder, String serverHost) {
        try {
            byte[] ip = InetAddress.getByName(serverHost).getAddress();
            for (String[] r : subtractRoute(0, ip)) builder.addRoute(r[0], Integer.parseInt(r[1]));
        } catch (Exception e) { builder.addRoute("0.0.0.0", 0); }
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
        } catch (Exception e) {}
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (Exception ignored) {}
            finally { networkCallback = null; }
        }
    }

    private static long tunBaseUp   = 0;
    private static long tunBaseDown = 0;

    private static void resetTunBase(Context ctx) {
        long[] raw = readTunRaw(ctx);
        tunBaseUp   = raw[0];
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
        return new long[]{ Math.max(0, raw[0] - tunBaseUp), Math.max(0, raw[1] - tunBaseDown) };
    }

    private static String fmtSpeed(long bytesPerSec) {
        if (bytesPerSec < 10 * 1024 * 1024) return String.format("%6.1f KB/s", bytesPerSec / 1024.0);
        return String.format("%6.1f MB/s", bytesPerSec / (1024.0 * 1024));
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("VPN статус");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private NotificationCompat.Builder notifBuilder;

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
}