package com.vlessvpn.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class VpnTunnelService extends VpnService {

    private static final String TAG = "VpnTunnelService";
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_CONNECT    = "com.vlessvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vlessvpn.DISCONNECT";
    public static final String EXTRA_SERVER      = "server";
    public static final String EXTRA_AUTO_CONNECT = "auto_connect";

    // ════════════════════════════════════════════════════════════════
    // Слушатели подключения (для MainViewModel)
    // ════════════════════════════════════════════════════════════════

    private static final CopyOnWriteArrayList<Consumer<Boolean>> connectionListeners = new CopyOnWriteArrayList<>();

    private ExecutorService backgroundExecutor;
    private Handler mainHandler;

    private static volatile VpnTunnelService instance;
    public static volatile boolean isRunning = false;
    public static volatile boolean isSwitchingServer = false;
    public static volatile VlessServer connectedServer = null;

    public static volatile long totalUp   = 0;
    public static volatile long totalDown = 0;

    private final Handler statsHandler = new Handler(Looper.getMainLooper());

    private final Runnable statsPoller = new Runnable() {
        @Override public void run() {
            V2RayManager mgr = v2RayManager;
            if (mgr != null && isRunning) {
                long[] s = mgr.getStats();
                totalUp   = s[0];
                totalDown = s[1];
                VlessServer srv = connectedServer;
                if (srv != null) {
                    updateNotification("↑ " + fmtBytes(totalUp) + "  ↓ " + fmtBytes(totalDown), srv.host);
                }
                String trafficMsg = "TRAFFIC:" + fmtBytes(totalUp) + "|" + fmtBytes(totalDown);
                StatusBus.post(VpnTunnelService.this, trafficMsg, true);

                Intent broadcast = new Intent("com.vlessvpn.TRAFFIC_UPDATE");
                broadcast.putExtra("totalUp", totalUp);
                broadcast.putExtra("totalDown", totalDown);
                sendBroadcast(broadcast);
                statsHandler.postDelayed(this, 1_000);
            }
        }
    };

    private ParcelFileDescriptor vpnInterface;
    private V2RayManager v2RayManager;
    private HevTunnel    hevTunnel;
    private Thread       v2rayThread;
    private VlessServer  currentServer;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Object connectLock = new Object();
    private volatile long activeConnectId = 0;

    private Handler checkHandler;

    // ════════════════════════════════════════════════════════════════
    // Методы для MainViewModel
    // ════════════════════════════════════════════════════════════════

    public static void registerConnectionListener(android.content.Context ctx, Consumer<Boolean> listener) {
        connectionListeners.add(listener);
        listener.accept(isRunning);
    }

    public static VlessServer getCurrentServer() {
        return connectedServer;
    }

    private static void notifyConnectionChanged(boolean connected) {
        for (Consumer<Boolean> listener : connectionListeners) {
            try {
                listener.accept(connected);
            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка уведомления слушателя: " + e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════

    public static int getTunFd() {
        return 0;
    }

    public static boolean protectSocket(int fd) {
        VpnTunnelService svc = instance;
        if (svc == null) return false;
        return svc.protect(fd);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        //FileLogger.i(TAG, "=== onCreate ===");
        createNotificationChannel();
        V2RayManager.initEnvOnce(this);

        backgroundExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        checkHandler = new Handler(Looper.getMainLooper());
    }

// ════════════════════════════════════════════════════════════════
// В onStartCommand() — добавить обработку флага
// ════════════════════════════════════════════════════════════════

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
       // FileLogger.i(TAG, "onStartCommand: " + action);

        if (ACTION_DISCONNECT.equals(action)) {
            disconnect();
            return START_NOT_STICKY;
        }

        VlessServer server = null;
        boolean isAutoConnect = false;

        if (intent != null) {
            String json = intent.getStringExtra(EXTRA_SERVER);
            isAutoConnect = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false);  // ← Флаг

            if (json != null) {
                try {
                    server = new Gson().fromJson(json, VlessServer.class);
                } catch (Exception e) {
                    FileLogger.e(TAG, "Ошибка десериализации: " + e.getMessage());
                }
            }
        }

        // ════════════════════════════════════════════════════════════════
        // ← Если авто-подключение — не показывать уведомление
        // ════════════════════════════════════════════════════════════════
        if (isAutoConnect) {
            //FileLogger.i(TAG, "Авто-подключение (без уведомления)");
        }

        if (server == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification("Подключение...", server.host));

        final VlessServer serverFinal = server;
        final long connectId = System.currentTimeMillis();
        activeConnectId = connectId;
        new Thread(() -> connect(serverFinal, connectId), "connect-thread").start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        FileLogger.i(TAG, "onDestroy");

        if (checkHandler != null) checkHandler.removeCallbacks(checkRunnable);
        if (backgroundExecutor != null) backgroundExecutor.shutdownNow();

        disconnect();
    }

    @Override
    public void onRevoke() {
        disconnect();
    }

    // ── CONNECT / DISCONNECT ─────────────────────────────────────────────────

    private void connect(VlessServer server, long connectId) {
        //FileLogger.i(TAG, "connect() id=" + connectId + " server=" + server.host);

        synchronized (connectLock) {
            if (connectId != activeConnectId) return;
            if (isRunning || v2RayManager != null || hevTunnel != null) {
                fullStop();
            }
            if (connectId != activeConnectId) return;
        }

        isSwitchingServer = false;
        currentServer = server;

        v2rayThread = new Thread(() -> {
            try {
                vpnInterface = buildTunWithRetries(server);

                if (vpnInterface == null) {
                    FileLogger.e(TAG, "Не удалось создать TUN (background)");
                    mainHandler.post(() -> StatusBus.done("Не удалось создать TUN"));
                    stopSelf();
                    return;
                }

                //FileLogger.i(TAG, "TUN создан fd=" + vpnInterface.getFd());
                registerNetworkCallback();
                startV2RayAndHev(server);

            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка в v2ray потоке", e);
                mainHandler.post(() -> {
                    StatusBus.done(e.getMessage());
                    stopSelf();
                });
            }
        }, "v2ray-thread");

        v2rayThread.setDaemon(true);
        v2rayThread.start();
    }

    private ParcelFileDescriptor buildTunWithRetries(VlessServer server) {
        for (int i = 1; i <= 4; i++) {
            try {
                if (VpnService.prepare(this) != null) {
                    FileLogger.e(TAG, "VPN не подготовлен (нет разрешения от системы)");
                    return null;
                }

                Builder builder = new Builder();
                builder.setSession("VlessVPN");
                builder.setMtu(1500);
                builder.addAddress("10.10.14.1", 24);
                builder.addDnsServer("8.8.8.8");
                builder.addDnsServer("8.8.4.4");

                addRoutesExcluding(builder, server.host);

                try { builder.addDisallowedApplication(getPackageName()); }
                catch (Exception ignored) {}

                applyAppBlacklist(builder); // ← НОВОЕ: Применить чёрный список приложений

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false);
                }

                ParcelFileDescriptor pfd = builder.establish();
                if (pfd != null) {
                    return pfd;
                }

                FileLogger.w(TAG, "establish() вернул null. Попытка " + i + " из 4...");
            } catch (Exception e) {
                FileLogger.w(TAG, "Ошибка buildTun: " + e.getMessage() + ". Попытка " + i + " из 4...");
            }

            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    private void applyAppBlacklist(Builder builder) {
        AppBlacklistManager blm = new AppBlacklistManager(this);
        Set<String> blacklist = blm.getBlacklist();

        if (blacklist.isEmpty()) {
            FileLogger.d(TAG, "Чёрный список пуст — все приложения через VPN");
            return;
        }

        int added = 0;
        int failed = 0;

        for (String packageName : blacklist) {
            try {
                builder.addDisallowedApplication(packageName);
                added++;
                //FileLogger.d(TAG, "Добавлено в исключения: " + packageName);
            } catch (PackageManager.NameNotFoundException e) {
                failed++;
                FileLogger.w(TAG, "Приложение не найдено: " + packageName);
            } catch (Exception e) {
                failed++;
                FileLogger.w(TAG, "Ошибка добавления: " + packageName + " — " + e.getMessage());
            }
        }

        //FileLogger.i(TAG, "Чёрный список применён: " + added + " приложений (ошибок: " + failed + ")");
    }

    private void startV2RayAndHev(VlessServer server) throws Exception {
        v2RayManager = new V2RayManager(this, new V2RayManager.StatusCallback() {
            @Override public void onStarted(VlessServer s) {
                startHev();
                mainHandler.post(() -> {
                    updateNotification("Подключено", s.host);
                    isRunning = true;
                    connectedServer = s;
                    currentServer = s;
                    totalUp = 0; totalDown = 0;
                    StatusBus.post("Подключено: " + s.host, true);
                    statsHandler.postDelayed(statsPoller, 500);

                    // ════════════════════════════════════════════════════════════════
                    // ← НОВОЕ: Устанавливаем флаг что VPN активен
                    // ════════════════════════════════════════════════════════════════
                    ServerTester.setVpnActive(true);
                    // ════════════════════════════════════════════════════════════════

                    ServerRepository repo = new ServerRepository(VpnTunnelService.this);
                    repo.saveLastWorkingServer(s);
                   // FileLogger.i(TAG, "Сохранён последний рабочий сервер: " + s.host);

                   // FileLogger.i(TAG, "═══════════════════════════════════════");
                   //FileLogger.i(TAG, "ОТПРАВЛЯЕМ VPN_STATUS_CHANGED broadcast");
                    try {
                        Intent broadcast = new Intent("com.vlessvpn.VPN_STATUS_CHANGED");
                        broadcast.putExtra("connected", true);
                        broadcast.putExtra("server", new Gson().toJson(s));
                        sendBroadcast(broadcast);
                        //FileLogger.i(TAG, "Broadcast отправлен успешно");
                    } catch (Exception e) {
                        FileLogger.e(TAG, "Ошибка отправки broadcast: " + e.getMessage());
                    }
                   // FileLogger.i(TAG, "═══════════════════════════════════════");

                    notifyConnectionChanged(true);
                    startPeriodicCheck();
                });
            }

            @Override public void onStopped() {
                mainHandler.post(() -> {
                    // ════════════════════════════════════════════════════════════════
                    // ← НОВОЕ: Сбрасываем флаг что VPN не активен
                    // ════════════════════════════════════════════════════════════════
                    ServerTester.setVpnActive(false);
                    // ════════════════════════════════════════════════════════════════

                    StatusBus.done("Отключено");

                    //FileLogger.i(TAG, "ОТПРАВЛЯЕМ VPN_STATUS_CHANGED broadcast (отключено)");
                    try {
                        Intent broadcast = new Intent("com.vlessvpn.VPN_STATUS_CHANGED");
                        broadcast.putExtra("connected", false);
                        sendBroadcast(broadcast);
                    } catch (Exception e) {
                        FileLogger.e(TAG, "Ошибка отправки broadcast: " + e.getMessage());
                    }

                    notifyConnectionChanged(false);
                });
            }

            @Override public void onError(String error) {
                mainHandler.post(() -> {
                    // ════════════════════════════════════════════════════════════════
                    // ← НОВОЕ: Сбрасываем флаг что VPN не активен
                    // ════════════════════════════════════════════════════════════════
                    ServerTester.setVpnActive(false);
                    // ════════════════════════════════════════════════════════════════

                    StatusBus.done(error);

                    //FileLogger.i(TAG, "ОТПРАВЛЯЕМ VPN_STATUS_CHANGED broadcast (ошибка)");
                    try {
                        Intent broadcast = new Intent("com.vlessvpn.VPN_STATUS_CHANGED");
                        broadcast.putExtra("connected", false);
                        broadcast.putExtra("error", error);
                        sendBroadcast(broadcast);
                    } catch (Exception e) {
                        FileLogger.e(TAG, "Ошибка отправки broadcast: " + e.getMessage());
                    }

                    notifyConnectionChanged(false);

                    if (!isSwitchingServer) {
                        stopSelf();
                    }
                });
            }

            @Override public void onStatsUpdate(long up, long down) {
                totalUp = up; totalDown = down;
            }
        });

        v2RayManager.start(server);

        if (!isRunning && !isSwitchingServer) {
            stopHev();
            mainHandler.post(this::stopSelf);
        }
    }

    // ── PERIODIC CHECK & AUTO-SWITCH ─────────────────────────────────────────

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning || backgroundExecutor == null || backgroundExecutor.isShutdown()) {
                return;
            }

            backgroundExecutor.execute(VpnTunnelService.this::doConnectivityCheck);

            if (checkHandler != null) {
                checkHandler.postDelayed(this, 1 * 60 * 1000L);
            }
        }
    };

    // ════════════════════════════════════════════════════════════════
    // ← НОВОЕ: Проверка соединения с trafficTest()
    // ════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════
// В VpnTunnelService.java — исправить doConnectivityCheck() и switchToNextServer()
// ════════════════════════════════════════════════════════════════

    private void doConnectivityCheck() {
        //FileLogger.i(TAG, "=== 1-min Тест соединения:");
        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Проверка соединения...", true));

        // ════════════════════════════════════════════════════════════════
        // ← Проверка реального трафика через VPN
        // ════════════════════════════════════════════════════════════════
        boolean trafficOk = ServerTester.trafficTest();

        if (!trafficOk) {
            FileLogger.w(TAG, "=== Нет трафика");
            ServerRepository repo = new ServerRepository(VpnTunnelService.this);
            repo.clearLastWorkingServer();

            // ════════════════════════════════════════════════════════════════
            // ← НОВОЕ: Переключаем и сразу проверяем новый сервер
            // ════════════════════════════════════════════════════════════════
            switchToNextServerWithCheck();
            return;
        }

        FileLogger.d(TAG, "=== Трафик OK!.");

        if (currentServer != null) {
            ServerRepository repo = new ServerRepository(VpnTunnelService.this);
            repo.saveLastWorkingServer(currentServer);
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Подключено: " + currentServer.host, true));
        }
    }

// ════════════════════════════════════════════════════════════════
// ← НОВОЕ: Переключение с немедленной проверкой трафика
// ════════════════════════════════════════════════════════════════

    private void switchToNextServerWithCheck() {
        ServerRepository repo = new ServerRepository(this);
        List<VlessServer> readyList = repo.getTopServersSync();

        if (readyList.isEmpty() || readyList.size() == 1) {
            FileLogger.w(TAG, "Нет запасных серверов для переключения");
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Нет рабочих серверов!", true));
            return;
        }

        // Находим текущий сервер в списке
        int currentIndex = -1;
        if (currentServer != null) {
            for (int i = 0; i < readyList.size(); i++) {
                if (readyList.get(i).id.equals(currentServer.id)) {
                    currentIndex = i;
                    break;
                }
            }
        }

        // ════════════════════════════════════════════════════════════════
        // ← Перебираем серверы пока не найдём рабочий (без ограничения)
        // ════════════════════════════════════════════════════════════════
        int nextIndex = (currentIndex + 1) % readyList.size();
        int checkedCount = 0;

        VlessServer selectedServer = null;

        while (checkedCount < readyList.size()) {
            VlessServer candidate = readyList.get(nextIndex);
            checkedCount++;

            FileLogger.i(TAG, "Проверка " + checkedCount + "/" + readyList.size() + ": " + candidate.host);

            // ════════════════════════════════════════════════════════════════
            // ← Быстрая проверка трафика на этом сервере
            // ════════════════════════════════════════════════════════════════
            if (quickTrafficCheck(candidate)) {
                selectedServer = candidate;
                FileLogger.i(TAG, "✓ " + candidate.host + " OK!");
                break;
            } else {
                FileLogger.w(TAG, "✗ " + candidate.host + " FAIL!");
                nextIndex = (nextIndex + 1) % readyList.size();
            }
        }

        if (selectedServer == null) {
            // Ни один сервер не прошёл проверку — берём первый из списка
            selectedServer = readyList.get(0);
            FileLogger.w(TAG, "Ни один сервер не прошёл проверку — используем " + selectedServer.host);
        }

        final VlessServer finalServer = selectedServer;
        //FileLogger.i(TAG, "Переключаемся на: " + finalServer.host + ":" + finalServer.port);
        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Переключение на " + finalServer.remark, true));

        isSwitchingServer = true;

        long newConnectId = System.currentTimeMillis();
        activeConnectId = newConnectId;

        new Thread(() -> connect(finalServer, newConnectId), "reconnect-thread").start();
    }

// ════════════════════════════════════════════════════════════════
// ← Быстрая проверка трафика
// ════════════════════════════════════════════════════════════════

    private boolean quickTrafficCheck(VlessServer server) {
        // Проверяем TCP доступность сервера
        boolean tcpOk = false;
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.setSoTimeout(3000);
            socket.connect(new java.net.InetSocketAddress(server.host, server.port), 3000);
            socket.close();
            tcpOk = true;
        } catch (Exception e) {
            tcpOk = false;
        }

        if (!tcpOk) return false;

        // ════════════════════════════════════════════════════════════════
        // ← Если VPN уже подключён — проверяем через SOCKS5
        // ════════════════════════════════════════════════════════════════
        if (isRunning && v2RayManager != null) {
            return ServerTester.trafficTest();
        }

        // Если VPN не подключён — TCP достаточно для быстрой проверки
        return true;
    }

// ════════════════════════════════════════════════════════════════
// ← СТАРОЕ: Простое переключение (без проверки)
// ════════════════════════════════════════════════════════════════

    private void switchToNextServer() {
        ServerRepository repo = new ServerRepository(this);
        List<VlessServer> readyList = repo.getTopServersSync();

        if (readyList.isEmpty() || readyList.size() == 1) {
            FileLogger.w(TAG, "Нет запасных серверов для переключения");
            mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Нет рабочих серверов!", true));
            return;
        }

        VlessServer next = null;
        for (VlessServer s : readyList) {
            if (currentServer != null && !s.id.equals(currentServer.id)) {
                next = s;
                break;
            }
        }
        if (next == null) next = readyList.get(0);

        final VlessServer finalNext = next;
        //FileLogger.i(TAG, "Переключаемся на: " + finalNext.host + ":" + finalNext.port);

        mainHandler.post(() -> StatusBus.post(VpnTunnelService.this, "Переключение на " + finalNext.remark, true));

        isSwitchingServer = true;

        long newConnectId = System.currentTimeMillis();
        activeConnectId = newConnectId;

        new Thread(() -> connect(finalNext, newConnectId), "reconnect-thread").start();
    }

    private void startPeriodicCheck() {
        checkHandler.removeCallbacks(checkRunnable);
        checkHandler.postDelayed(checkRunnable, 1 * 60 * 1000L);
    }

    private boolean performShortConnectivityCheck() {
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 10808));
            URL url = new URL("http://cp.cloudflare.com/generate_204");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);

            conn.setUseCaches(false);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            int code = conn.getResponseCode();
            return (code == 204 || code == 200);
        } catch (Exception e) {
            FileLogger.e(TAG, "Check FAIL: " + e.getMessage());
            return false;
        }
    }

    // ── TUNNEL CONTROL ───────────────────────────────────────────────────────

    private void startHev() {
        if (vpnInterface == null) return;
        try {
            hevTunnel = new HevTunnel(this);
            hevTunnel.start(vpnInterface);
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка запуска hev", e);
        }
    }

    private void stopHev() {
        HevTunnel hev = hevTunnel;
        hevTunnel = null;
        if (hev != null) {
            try { hev.stop(); } catch (Exception ignored) {}
        }
    }

    private void fullStop() {
        FileLogger.i(TAG, "fullStop()");
        isRunning = false;
        connectedServer = null;

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

        try { Thread.sleep(150); } catch (InterruptedException ignored) {}

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
    }

    private void disconnect() {
        FileLogger.i(TAG, "disconnect()");
        isSwitchingServer = false;
        checkHandler.removeCallbacks(checkRunnable);
        fullStop();
        StatusBus.done("Отключено");
        stopForeground(true);
        stopSelf();
    }

    // ── TUN BUILDER & NETWORK CALLBACK ───────────────────────────────────────

    private ParcelFileDescriptor buildTun(VlessServer server) {
        try {
            Builder builder = new Builder();
            builder.setSession("VlessVPN");
            builder.setMtu(1500);
            builder.addAddress("10.10.14.1", 24);
            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("8.8.4.4");

            addRoutesExcluding(builder, server.host);

            try { builder.addDisallowedApplication(getPackageName()); }
            catch (Exception ignored) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);

            return builder.establish();
        } catch (Exception e) { return null; }
    }

    private void addRoutesExcluding(Builder builder, String serverHost) {
        try {
            InetAddress addr = InetAddress.getByName(serverHost);
            byte[] ip = addr.getAddress();
            List<String[]> routes = subtractRoute("0.0.0.0", 0, ip);
            for (String[] r : routes) builder.addRoute(r[0], Integer.parseInt(r[1]));
        } catch (Exception e) {
            builder.addRoute("0.0.0.0", 0);
        }
    }

    private List<String[]> subtractRoute(String baseIp, int prefix, byte[] excludeIp) {
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

    private long ipToLong(String ip) {
        try { return bytesToLong(InetAddress.getByName(ip).getAddress()); }
        catch (Exception e) { return 0; }
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
                @Override
                public void onAvailable(Network network) {
                    Network[] arr = new Network[1];
                    arr[0] = network;
                    setUnderlyingNetworks(arr);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    Network[] arr = new Network[1];
                    arr[0] = network;
                    setUnderlyingNetworks(arr);
                }

                @Override
                public void onLost(Network network) {
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
            } catch (Exception e) {
                FileLogger.w(TAG, "unregisterNetworkCallback: " + e.getMessage());
            }
            networkCallback = null;
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private static String fmtBytes(long bytes) {
        if (bytes < 1024)             return bytes + " B";
        if (bytes < 1024 * 1024)      return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("VPN статус");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

        Intent disconnIntent = new Intent(this, VpnTunnelService.class);
        disconnIntent.setAction(ACTION_DISCONNECT);
        PendingIntent disconnPi = PendingIntent.getService(this, 0, disconnIntent,
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
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(title, text));
        }
    }

    // ════════════════════════════════════════════════════════════════
// В VpnTunnelService.java — добавить статический метод
// ════════════════════════════════════════════════════════════════

    private static long bytesUploaded = 0;
    private static long bytesDownloaded = 0;

    public static String getTrafficStats() {
        // Форматировать трафик
        String up = formatBytes(bytesUploaded);
        String down = formatBytes(bytesDownloaded);
        return "↑ " + up + "  ↓ " + down;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024 * 1024 * 1024)) + " GB";
    }

// ════════════════════════════════════════════════════════════════
// Обновлять счётчики в процессе работы VPN
// ════════════════════════════════════════════════════════════════

    private void updateTrafficStats(long up, long down) {
        bytesUploaded = up;
        bytesDownloaded = down;
    }
}