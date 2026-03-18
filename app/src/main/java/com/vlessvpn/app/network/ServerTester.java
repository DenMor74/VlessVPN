package com.vlessvpn.app.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.system.Os;
import android.system.OsConstants;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.util.FileLogger;

import java.io.FileDescriptor;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ServerTester — тестирование серверов через LTE (даже при WiFi).
 */
public class ServerTester {

    private static final String TAG       = "ServerTester";
    private static final int TIMEOUT_MS   = 6_000;
    private static final int REPEAT       = 2;

    private static final String CHECK_URL = "http://www.gstatic.com/generate_204";

    private static final AtomicBoolean vpnActive = new AtomicBoolean(false);

    public static void setVpnActive(boolean active) { vpnActive.set(active); }
    public static boolean isVpnActive()             { return vpnActive.get(); }

    // ─────────────────────────────────────────────────────────────────────────

    public static class TestResult {
        public long    pingMs        = -1;
        public boolean trafficOk    = false;
        public String  errorMessage = "";
        public String  networkType  = "";

        public long bestPing() { return pingMs >= 0 ? pingMs : Long.MAX_VALUE; }
    }

    // ════════════════════════════════════════════════════════════════
    // 1. TCP пинг через LTE (даже если WiFi подключён)
    // ════════════════════════════════════════════════════════════════

    public static TestResult tcpTest(Context ctx, VlessServer server) {
        TestResult r = new TestResult();

        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: Сначала проверяем есть ли LTE сеть
        // ════════════════════════════════════════════════════════════════
        Network cellular = getCellularNetwork(ctx);
        boolean hasCellular = (cellular != null);

        // FileLogger.d(TAG, "tcpTest: hasCellular=" + hasCellular + " server=" + server.host);

        long best = -1;

        // ════════════════════════════════════════════════════════════════
        // ← Пытаемся через LTE если сеть есть
        // ════════════════════════════════════════════════════════════════
        if (hasCellular) {
            for (int i = 0; i < REPEAT; i++) {
                long ms = socketConnectTimeWithCellular(ctx, server.host, server.port);
                if (ms >= 0 && (best < 0 || ms < best)) {
                    best = ms;
                    r.networkType = "LTE";
                }
            }
        }

        // ════════════════════════════════════════════════════════════════
        // ← Fallback на WiFi ТОЛЬКО если LTE сети нет (не если сервер упал!)
        // ════════════════════════════════════════════════════════════════
        if (best < 0 && !hasCellular) {
            FileLogger.w(TAG, "LTE сети нет → пробуем через WiFi");
            for (int i = 0; i < REPEAT; i++) {
                long ms = socketConnectTimeWithWifi(ctx, server.host, server.port);
                if (ms >= 0 && (best < 0 || ms < best)) {
                    best = ms;
                    r.networkType = "WiFi";
                }
            }
        }

        if (best >= 0) {
            r.pingMs    = best;
            r.trafficOk = true;
            //FileLogger.d(TAG, "TCP OK " + server.host + ":" + server.port + " " + best + "ms (" + r.networkType + ")");
        } else {
            r.errorMessage = "недоступен";
            r.networkType = hasCellular ? "LTE" : "WiFi";  // ← Показываем через что тестировали
            //FileLogger.d(TAG, "TCP FAIL " + server.host + ":" + server.port + " (" + r.networkType + ")");
        }
        return r;
    }

    // ════════════════════════════════════════════════════════════════
    // 2. TCP пинг через WiFi (если LTE недоступен)
    // ════════════════════════════════════════════════════════════════

    public static TestResult tcpTestWithWifi(Context ctx, VlessServer server) {
        TestResult r = new TestResult();
        long best = -1;

        for (int i = 0; i < REPEAT; i++) {
            long ms = socketConnectTimeWithWifi(ctx, server.host, server.port);
            if (ms >= 0 && (best < 0 || ms < best)) best = ms;
        }

        if (best >= 0) {
            r.pingMs    = best;
            r.trafficOk = true;
            r.networkType = "WiFi";
        } else {
            r.errorMessage = "недоступен";
            r.networkType = "NONE";
        }
        return r;
    }

    // Для совместимости
    public static TestResult tcpTest(Network ignored, VlessServer server) {
        return tcpTest((Context) null, server);
    }

    // ════════════════════════════════════════════════════════════════
    // 3. Проверка реального трафика через VPN
    // ════════════════════════════════════════════════════════════════

    public static boolean trafficTest() {
        if (!vpnActive.get()) {
            FileLogger.w(TAG, "trafficTest: VPN не активен");
            return false;
        }

        try {
            java.net.Proxy proxy = new java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    new java.net.InetSocketAddress("127.0.0.1", 10808)
            );
            URL url = new URL(CHECK_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int code = conn.getResponseCode();
            boolean ok = (code >= 200 && code < 400);
            FileLogger.i(TAG, "trafficTest → HTTP " + code + " ok=" + ok);
            conn.disconnect();
            return ok;
        } catch (Exception e) {
            FileLogger.d(TAG, "trafficTest FAIL: " + e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 4. TCP connect через LTE (protectSocket + bind если есть)
    // ════════════════════════════════════════════════════════════════

    private static long socketConnectTimeWithCellular(Context ctx, String host, int port) {
        FileDescriptor fd = null;
        try {
            fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0);

            // protectSocket() — основной метод (работает всегда)
            VpnTunnelService.protectSocket(fd instanceof FileDescriptor ? getIntFd(fd) : -1);

            // Пытаемся bind к LTE как оптимизация (не критично если не выйдет)
            Network cellular = getCellularNetwork(ctx);
            if (cellular != null) {
                try {
                    cellular.bindSocket(fd);
                } catch (Exception e) {
                    // EPERM это нормально на Android 10+ — protectSocket() уже сработал
                }
            }

            java.net.InetAddress addr = java.net.InetAddress.getByName(host);

            android.system.StructTimeval tv = android.system.StructTimeval.fromMillis(TIMEOUT_MS);
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, tv);
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, tv);

            long start = System.currentTimeMillis();
            Os.connect(fd, addr, port);
            long ms = System.currentTimeMillis() - start;
            Os.close(fd);
            fd = null;
            return ms;

        } catch (android.system.ErrnoException e) {
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (fd != null) try { Os.close(fd); } catch (Exception ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 5. TCP connect через WiFi (fallback)
    // ════════════════════════════════════════════════════════════════

    private static long socketConnectTimeWithWifi(Context ctx, String host, int port) {
        FileDescriptor fd = null;
        try {
            fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0);

            VpnTunnelService.protectSocket(fd instanceof FileDescriptor ? getIntFd(fd) : -1);

            Network wifi = getWifiNetwork(ctx);
            if (wifi != null) {
                try {
                    wifi.bindSocket(fd);
                } catch (Exception e) {
                    // EPERM это нормально
                }
            }

            java.net.InetAddress addr = java.net.InetAddress.getByName(host);

            android.system.StructTimeval tv = android.system.StructTimeval.fromMillis(TIMEOUT_MS);
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, tv);
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, tv);

            long start = System.currentTimeMillis();
            Os.connect(fd, addr, port);
            long ms = System.currentTimeMillis() - start;
            Os.close(fd);
            fd = null;
            return ms;

        } catch (android.system.ErrnoException e) {
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (fd != null) try { Os.close(fd); } catch (Exception ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 6. Получить int fd из FileDescriptor через reflection
    // ════════════════════════════════════════════════════════════════

    private static int getIntFd(FileDescriptor fd) {
        try {
            java.lang.reflect.Field f = FileDescriptor.class.getDeclaredField("descriptor");
            f.setAccessible(true);
            return (int) f.get(fd);
        } catch (Exception e) {
            return -1;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 7. Найти LTE сеть
    // ════════════════════════════════════════════════════════════════

    public static Network getCellularNetwork(Context ctx) {
        if (ctx == null) return null;
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))       continue;
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                return net;
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // 8. Найти WiFi сеть (для fallback)
    // ════════════════════════════════════════════════════════════════

    public static Network getWifiNetwork(Context ctx) {
        if (ctx == null) return null;
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))     continue;
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))   continue;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                return net;
        }
        return null;
    }
}