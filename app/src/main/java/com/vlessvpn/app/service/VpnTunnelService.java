package com.vlessvpn.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
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
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.ui.MainActivity;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VpnTunnelService extends VpnService {

    private static final String TAG = "VpnTunnelService";
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_CONNECT    = "com.vlessvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vlessvpn.DISCONNECT";
    public static final String EXTRA_SERVER      = "server";

    private ExecutorService backgroundExecutor;
    private Handler mainHandler;

    private static volatile VpnTunnelService instance;
    public static volatile boolean isRunning = false;
    public static volatile boolean isSwitchingServer = false; // Флаг безопасного переключения
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
                StatusBus.post("↑ " + fmtBytes(totalUp) + "  ↓ " + fmtBytes(totalDown), true);
                statsHandler.postDelayed(this, 3_000);
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
        FileLogger.i(TAG, "=== onCreate ===");
        createNotificationChannel();
        V2RayManager.initEnvOnce(this);

        backgroundExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        checkHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        FileLogger.i(TAG, "onStartCommand: " + action);

        if (ACTION_DISCONNECT.equals(action)) {
            disconnect();
            return START_NOT_STICKY;
        }

        VlessServer server = null;
        if (intent != null) {
            String json = intent.getStringExtra(EXTRA_SERVER);
            if (json != null) {
                try {
                    server = new Gson().fromJson(json, VlessServer.class);
                } catch (Exception e) {
                    FileLogger.e(TAG, "Ошибка десериализации: " + e.getMessage());
                }
            }
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
        FileLogger.i(TAG, "connect() id=" + connectId + " server=" + server.host);

        synchronized (connectLock) {
            if (connectId != activeConnectId) return;
            if (isRunning || v2RayManager != null || hevTunnel != null) {
                fullStop(); // Безопасная остановка старого ядра
            }
            if (connectId != activeConnectId) return;
        }

        // СБРАСЫВАЕМ ФЛАГ ПЕРЕКЛЮЧЕНИЯ (мы начинаем новое подключение)
        isSwitchingServer = false;
        currentServer = server;

        final java.util.concurrent.CountDownLatch tunLatch = new java.util.concurrent.CountDownLatch(1);
        mainHandler.post(() -> {
            vpnInterface = buildTun(server);
            tunLatch.countDown();
        });

        try { tunLatch.await(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

        if (vpnInterface == null) {
            mainHandler.post(() -> StatusBus.done("Не удалось создать TUN"));
            stopSelf();
            return;
        }

        registerNetworkCallback();

        v2rayThread = new Thread(() -> {
            try {
                startV2RayAndHev(server);
            } catch (Exception e) {
                mainHandler.post(() -> {
                    StatusBus.done(e.getMessage());
                    stopSelf();
                });
            }
        }, "v2ray-thread");
        v2rayThread.setDaemon(true);
        v2rayThread.start();
    }

    private void startV2RayAndHev(VlessServer server) throws Exception {
        v2RayManager = new V2RayManager(this, new V2RayManager.StatusCallback() {
            @Override public void onStarted(VlessServer s) {
                startHev();
                mainHandler.post(() -> {
                    updateNotification("Подключено", s.host);
                    isRunning = true;
                    connectedServer = s;
                    totalUp = 0; totalDown = 0;
                    StatusBus.post("Подключено: " + s.host, true);
                    statsHandler.postDelayed(statsPoller, 3_000);

                    // Запуск проверки связи (каждые 5 минут)
                    startPeriodicCheck();
                });
            }
            @Override public void onStopped() {
                mainHandler.post(() -> StatusBus.done("Отключено"));
            }
            @Override public void onError(String error) {
                mainHandler.post(() -> {
                    StatusBus.done(error);
                    // Убиваем службу только если это не процесс переключения
                    if (!isSwitchingServer) {
                        stopSelf();
                    }
                });
            }
            @Override public void onStatsUpdate(long up, long down) {
                totalUp = up; totalDown = down;
            }
        });

        v2RayManager.start(server); // Поток спит здесь, пока V2Ray работает

        // Когда Xray остановлен, проверяем: если мы переключаемся, службу не убиваем!
        if (!isRunning && !isSwitchingServer) {
            stopHev();
            mainHandler.post(this::stopSelf);
        }
    }

    // ── PERIODIC CHECK & AUTO-SWITCH ─────────────────────────────────────────

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            // Если служба была пересоздана или остановлена, этот "старый" таймер должен тихо умереть
            if (!isRunning || backgroundExecutor == null || backgroundExecutor.isShutdown()) {
                return;
            }

            backgroundExecutor.execute(this::doConnectivityCheck);

            if (checkHandler != null) {
                checkHandler.postDelayed(this, 1 * 60 * 1000L);
            }
        }

        private void doConnectivityCheck() {
            FileLogger.i(TAG, "=== 1-min check: проверяем текущий сервер");
            mainHandler.post(() -> StatusBus.post("Проверка соединения..."));

            boolean ok = performShortConnectivityCheck();

            if (!ok) {
                FileLogger.w(TAG, "Сервер НЕ отвечает — инициируем переключение");
                switchToNextServer(); // Безопасное переключение
            } else {
                FileLogger.d(TAG, "=== Сервер работает стабильно.");
                if (currentServer != null) {
                    mainHandler.post(() -> StatusBus.post("Подключено: " + currentServer.host, true));
                }
            }
        }
    };

    private void startPeriodicCheck() {
        checkHandler.removeCallbacks(checkRunnable);
        checkHandler.postDelayed(checkRunnable, 1 * 60 * 1000L);
    }

    private boolean performShortConnectivityCheck() {
        try {
            // Заворачиваем запрос в SOCKS5 (10808) от Xray!
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 10808));
            URL url = new URL("http://cp.cloudflare.com/generate_204");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);

            conn.setUseCaches(false);
            conn.setConnectTimeout(6000); // 6 секунд на тест
            conn.setReadTimeout(6000);

            int code = conn.getResponseCode();
            return (code == 204 || code == 200);
        } catch (Exception e) {
            FileLogger.e(TAG, "Check FAIL: " + e.getMessage());
            return false;
        }
    }

    private void switchToNextServer() {
        ServerRepository repo = new ServerRepository(this);
        List<VlessServer> readyList = repo.getTopServersSync();

        if (readyList.isEmpty() || readyList.size() == 1) {
            FileLogger.w(TAG, "Нет запасных серверов для переключения");
            mainHandler.post(() -> StatusBus.post("Нет рабочих серверов!"));
            return;
        }

        // Ищем следующий сервер в списке
        VlessServer next = null;
        for (VlessServer s : readyList) {
            if (currentServer != null && !s.id.equals(currentServer.id)) {
                next = s;
                break;
            }
        }
        if (next == null) next = readyList.get(0);

        final VlessServer finalNext = next;
        FileLogger.i(TAG, "Переключаемся на: " + finalNext.host + ":" + finalNext.port);

        mainHandler.post(() -> StatusBus.post("Переключение на " + finalNext.remark));

        // 1. ВКЛЮЧАЕМ ФЛАГ БЛОКИРОВКИ СМЕРТИ СЛУЖБЫ
        isSwitchingServer = true;

        long newConnectId = System.currentTimeMillis();
        activeConnectId = newConnectId;

        // 2. ЗАПУСКАЕМ НОВЫЙ КОННЕКТ (он сам аккуратно вызовет fullStop для старого ядра)
        new Thread(() -> connect(finalNext, newConnectId), "reconnect-thread").start();
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

        // Очищаем поллер статистики и 5-минутный чекер
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
        isSwitchingServer = false; // Сбрасываем флаг, так как это ручное отключение
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
}