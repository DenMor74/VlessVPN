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
import com.vlessvpn.app.core.V2RayConfigBuilder;
import com.vlessvpn.app.core.V2RayManager;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.ui.MainActivity;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * VpnTunnelService — основной VPN сервис.
 *
 * СХЕМА ТРАФИКА (hev режим, точно как v2rayNG):
 * ══════════════════════════════════════════════
 *
 *   Приложение → TUN (tun0, 10.10.14.1/24)
 *       → hev-socks5-tunnel читает IP пакеты из TUN fd
 *       → конвертирует в SOCKS5
 *       → отправляет на 127.0.0.1:10808
 *       → v2ray SOCKS5 inbound
 *       → VLESS outbound
 *       → сервер
 *
 * startup() в V2RayManager возвращает 0 (fd не нужен v2ray в hev режиме).
 * hev-socks5-tunnel получает fd через TProxyStartService(configPath, fd).
 *
 * ПОРЯДОК ЗАПУСКА:
 *   1. Создать TUN (VpnService.Builder)
 *   2. Запустить v2ray (startLoop с socks inbound 10808)
 *   3. Дождаться "V2Ray started"
 *   4. Запустить hev-socks5-tunnel (TProxyStartService)
 *
 * ПОРЯДОК ОСТАНОВКИ:
 *   1. Остановить hev (TProxyStopService)
 *   2. Остановить v2ray (stopLoop)
 *   3. Закрыть TUN
 */
public class VpnTunnelService extends VpnService {

    private static final String TAG = "VpnTunnelService";
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_CONNECT    = "com.vlessvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vlessvpn.DISCONNECT";
    public static final String EXTRA_SERVER      = "server";

    // Статический доступ для V2RayManager.startup() — в hev режиме НЕ используется
    // (оставляем для совместимости, startup() вернёт 0)
    private static volatile VpnTunnelService instance;
    /** Статическое поле — быстрая проверка из UI без bind */
    public static volatile boolean isRunning = false;
    /** Текущий подключённый сервер — для MainViewModel */
    public static volatile VlessServer connectedServer = null;

    /** Статистика трафика (байты суммарно с момента подключения) */
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
                // Обновляем уведомление с трафиком
                VlessServer srv = connectedServer;
                if (srv != null) {
                    updateNotification("↑ " + fmtBytes(totalUp) + "  ↓ " + fmtBytes(totalDown),
                        srv.host);
                }
                // Рассылаем статус в UI
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

    // Защита от одновременных connect() — только последний запрос выигрывает
    private final Object connectLock = new Object();
    private volatile long activeConnectId = 0;

    // ── статические методы для V2RayManager ──────────────────────────────────

    /** startup() возвращает 0 в hev режиме — v2ray не читает TUN напрямую */
    public static int getTunFd() {
        return 0; // hev режим: fd не нужен v2ray
    }

    /** protect() для исходящих сокетов v2ray — нужен чтобы сокет v2ray не шёл в TUN */
    public static boolean protectSocket(int fd) {
        VpnTunnelService svc = instance;
        if (svc == null) {
            FileLogger.e(TAG, "protectSocket: instance=null");
            return false;
        }
        boolean ok = svc.protect(fd);
        FileLogger.d(TAG, "protectSocket(fd=" + fd + ")=" + ok);
        return ok;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        FileLogger.i(TAG, "=== onCreate ===");
        createNotificationChannel();
        V2RayManager.initEnvOnce(this);
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
                    FileLogger.e(TAG, "Ошибка десериализации сервера: " + e.getMessage());
                }
            }
        }
        if (server == null) {
            FileLogger.e(TAG, "No server in intent");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification("Подключение...", server.host));
        // Запускаем в фоне — fullStop() внутри connect() блокирует поток
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
        disconnect();
    }

    @Override
    public void onRevoke() {
        FileLogger.w(TAG, "onRevoke — разрешение отозвано");
        disconnect();
    }

    // ── connect / disconnect ──────────────────────────────────────────────────

    private void connect(VlessServer server, long connectId) {
        FileLogger.i(TAG, "connect() id=" + connectId + " server=" + server.host);
        synchronized (connectLock) {
            // Если пришёл более новый запрос — этот уже устарел
            if (connectId != activeConnectId) {
                FileLogger.i(TAG, "connect отменён — пришёл новый запрос");
                return;
            }
            // Останавливаем предыдущее соединение синхронно
            if (isRunning || v2RayManager != null || hevTunnel != null) {
                FileLogger.i(TAG, "Переключение сервера → fullStop");
                fullStop();
            }
            // Снова проверяем — вдруг за время fullStop пришёл новый запрос
            if (connectId != activeConnectId) {
                FileLogger.i(TAG, "connect отменён после fullStop");
                return;
            }
            FileLogger.i(TAG, "connect: fullStop завершён, создаём TUN для " + server.host);
        }
        currentServer = server;
        FileLogger.i(TAG, "connectToServer: " + server.host + ":" + server.port);

        // 1. Создаём TUN на main thread (Samsung требует main thread для establish())
        final java.util.concurrent.CountDownLatch tunLatch = new java.util.concurrent.CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            vpnInterface = buildTun(server);
            tunLatch.countDown();
        });
        try { tunLatch.await(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

        if (vpnInterface == null) {
            FileLogger.e(TAG, "Не удалось создать TUN");
            new Handler(Looper.getMainLooper()).post(() -> StatusBus.done("Не удалось создать TUN"));
            stopSelf();
            return;
        }
        FileLogger.i(TAG, "TUN создан fd=" + vpnInterface.getFd());

        // 2. NetworkCallback — setUnderlyingNetworks (критично для трафика)
        registerNetworkCallback();

        // 3. Запускаем v2ray + hev в фоновом потоке
        v2rayThread = new Thread(() -> {
            try {
                startV2RayAndHev(server);
            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка в v2ray потоке", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    StatusBus.done(e.getMessage());
                    stopSelf();
                });
            }
        }, "v2ray-thread");
        v2rayThread.setDaemon(true);
        v2rayThread.start();
    }

    private void startV2RayAndHev(VlessServer server) throws Exception {
        // 3a. Инициализируем v2ray manager
        v2RayManager = new V2RayManager(this, new V2RayManager.StatusCallback() {
            @Override public void onStarted(VlessServer s) {
                FileLogger.i(TAG, "✔ v2ray запущен → запускаем hev");
                // 3b. Запускаем hev ПОСЛЕ старта v2ray
                startHev();
                new Handler(Looper.getMainLooper()).post(() -> {
                    updateNotification("Подключено", s.host);
                    isRunning = true;
                    connectedServer = s;
                    totalUp = 0; totalDown = 0;
                    StatusBus.post("Подключено: " + s.host, true);
                    statsHandler.postDelayed(statsPoller, 3_000);
                });
            }
            @Override public void onStopped() {
                FileLogger.i(TAG, "v2ray остановлен");
                new Handler(Looper.getMainLooper()).post(() -> {
                    StatusBus.done("Отключено");
                });
            }
            @Override public void onError(String error) {
                FileLogger.e(TAG, "v2ray error: " + error);
                new Handler(Looper.getMainLooper()).post(() -> {
                    StatusBus.done(error);
                    stopSelf();
                });
            }
            @Override public void onStatsUpdate(long up, long down) {
                totalUp   = up;
                totalDown = down;
            }
        });

        // start() блокирует поток пока v2ray работает
        v2RayManager.start(server);

        // v2ray завершился — если новое подключение уже запущено (переключение сервера),
        // то не останавливаем сервис — fullStop() уже вызван из connect()
        if (!isRunning) {
            stopHev();
            new Handler(Looper.getMainLooper()).post(this::stopSelf);
        }
    }

    private void startHev() {
        if (vpnInterface == null) {
            FileLogger.e(TAG, "startHev: vpnInterface=null");
            return;
        }
        try {
            hevTunnel = new HevTunnel(this);
            hevTunnel.start(vpnInterface);
            FileLogger.i(TAG, "hev-socks5-tunnel запущен");
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка запуска hev", e);
        }
    }

    private void stopHev() {
        HevTunnel hev = hevTunnel;
        hevTunnel = null;
        if (hev != null) {
            try { hev.stop(); } catch (Exception e) {
                FileLogger.w(TAG, "Ошибка остановки hev: " + e.getMessage());
            }
        }
    }

    /**
     * Полная синхронная остановка hev + v2ray + TUN.
     * НЕ вызывает stopForeground/stopSelf — используется при переключении сервера.
     */
    private void fullStop() {
        FileLogger.i(TAG, "fullStop()");
        isRunning = false;
        connectedServer = null;
        // removeCallbacks — только из main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statsHandler.removeCallbacks(statsPoller);
        } else {
            statsHandler.post(() -> statsHandler.removeCallbacks(statsPoller));
        }

        unregisterNetworkCallback();

        // 1. Останавливаем hev первым — он держит ссылку на fd
        stopHev();

        // 2. Ждём немного чтобы hev точно освободил fd (нативный поток)
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}

        // 3. Останавливаем v2ray
        V2RayManager mgr = v2RayManager;
        v2RayManager = null;
        if (mgr != null) mgr.stop();

        // 4. Прерываем поток v2ray
        Thread t = v2rayThread;
        v2rayThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException ignored) {}
        }

        // 5. Закрываем TUN — только после полной остановки hev и v2ray
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception e) {
                FileLogger.w(TAG, "Ошибка закрытия TUN: " + e.getMessage());
            }
            vpnInterface = null;
        }
    }

    private void disconnect() {
        FileLogger.i(TAG, "disconnect()");
        fullStop();
        StatusBus.done("Отключено");
        stopForeground(true);
    }

    // ── TUN builder ───────────────────────────────────────────────────────────

    private ParcelFileDescriptor buildTun(VlessServer server) {
        try {
            Builder builder = new Builder();
            builder.setSession("VlessVPN");
            builder.setMtu(1500);
            builder.addAddress("10.10.14.1", 24);
            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("8.8.4.4");

            // Split tunnel: весь трафик через VPN, кроме IP сервера
            addRoutesExcluding(builder, server.host);

            // Не пускать трафик самого приложения через TUN (иначе петля)
            try { builder.addDisallowedApplication(getPackageName()); }
            catch (Exception e) { FileLogger.w(TAG, "addDisallowedApplication: " + e.getMessage()); }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false);
            }

            return builder.establish();
        } catch (Exception e) {
            FileLogger.e(TAG, "buildTun error", e);
            return null;
        }
    }

    private void addRoutesExcluding(Builder builder, String serverHost) {
        try {
            InetAddress addr = InetAddress.getByName(serverHost);
            byte[] ip = addr.getAddress();
            FileLogger.i(TAG, "Split tunnel: исключаем " + serverHost);

            // Все маршруты 0.0.0.0/0 минус IP сервера
            List<String[]> routes = subtractRoute("0.0.0.0", 0, ip);
            for (String[] r : routes) {
                builder.addRoute(r[0], Integer.parseInt(r[1]));
            }
        } catch (Exception e) {
            FileLogger.w(TAG, "addRoutesExcluding error, используем 0.0.0.0/0: " + e.getMessage());
            builder.addRoute("0.0.0.0", 0);
        }
    }

    /** Вычитает один /32 адрес из CIDR блока, возвращает список оставшихся маршрутов */
    private List<String[]> subtractRoute(String baseIp, int prefix, byte[] excludeIp) {
        List<String[]> result = new ArrayList<>();
        long base = ipToLong(baseIp);
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
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            return bytesToLong(b);
        } catch (Exception e) { return 0; }
    }

    private long bytesToLong(byte[] b) {
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) |
               ((b[2] & 0xFFL) << 8)  |  (b[3] & 0xFFL);
    }

    private String longToIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." +
               ((ip >> 8) & 0xFF)  + "." + (ip & 0xFF);
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
                @Override
                public void onAvailable(Network network) {
                    FileLogger.i(TAG, "setUnderlyingNetworks: " + network);
                    setUnderlyingNetworks(new Network[]{network});
                }
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    setUnderlyingNetworks(new Network[]{network});
                }
                @Override
                public void onLost(Network network) {
                    FileLogger.w(TAG, "Network lost");
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
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (Exception e) { FileLogger.w(TAG, "unregisterNetworkCallback: " + e.getMessage()); }
            networkCallback = null;
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    /** Форматирование байт: 1.2 MB, 345 KB, etc. */
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
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
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
        nm.notify(NOTIF_ID, buildNotification(title, text));
    }
}
