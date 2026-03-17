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
 * ServerTester — два теста:
 *
 * 1. tcpTest()     — TCP ping через LTE (Os.socket + protect + Os.connect)
 * 2. trafficTest() — HTTP GET https://www.google.com через VPN-туннель:
 *                    соединяемся через SOCKS5 10808 → v2ray → сервер.
 *                    Если пришли первые байты — трафик работает.
 */
public class ServerTester {

    private static final String TAG       = "ServerTester";
    private static final int TIMEOUT_MS   = 4_000;
    private static final int REPEAT       = 2;
    // URL для проверки трафика через VPN
    private static final String CHECK_URL = "http://www.gstatic.com/generate_204";

    private static final AtomicBoolean vpnActive = new AtomicBoolean(false);

    public static void setVpnActive(boolean active) { vpnActive.set(active); }
    public static boolean isVpnActive()             { return vpnActive.get(); }

    // ─────────────────────────────────────────────────────────────────────────

    public static class TestResult {
        public long    pingMs        = -1;
        public boolean trafficOk    = false;
        public String  errorMessage = "";
        public long bestPing() { return pingMs >= 0 ? pingMs : Long.MAX_VALUE; }
    }

    // ── 1. TCP пинг через физическую сеть (LTE) ──────────────────────────────

    public static TestResult tcpTest(Context ctx, VlessServer server) {
        TestResult r = new TestResult();
        long best = -1;
        for (int i = 0; i < REPEAT; i++) {
            long ms = socketConnectTime(server.host, server.port);
            if (ms >= 0 && (best < 0 || ms < best)) best = ms;
        }
        if (best >= 0) {
            r.pingMs    = best;
            r.trafficOk = true;
            // FileLogger.d(TAG, "TCP OK " + server.host + ":" + server.port + " " + best + "ms");
        } else {
            r.errorMessage = "недоступен";
           // FileLogger.d(TAG, "TCP FAIL " + server.host + ":" + server.port);
        }
        return r;
    }

    public static TestResult tcpTest(Network ignored, VlessServer server) {
        return tcpTest((Context) null, server);
    }

    // ── 2. Проверка реального трафика через VPN ───────────────────────────────

    /**
     * HTTP GET через локальный SOCKS5 прокси (v2ray на 10808).
     * Если пришёл HTTP ответ (любой) — трафик через VPN работает.
     *
     * Используется java.net.Proxy чтобы завернуть запрос через SOCKS5.
     * Это НЕ системный трафик — поэтому не попадает в TUN.
     *
     * @return true если трафик прошёл, false иначе
     */
    public static boolean trafficTest() {
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
            // gstatic.com/generate_204 возвращает 204 если есть интернет
            // Любой ответ (200, 204, 301...) означает что трафик прошёл
            boolean ok = (code >= 200 && code < 400);
           // FileLogger.i(TAG, "trafficTest → HTTP " + code + " ok=" + ok);
            conn.disconnect();
            return ok;
        } catch (Exception e) {
           // FileLogger.d(TAG, "trafficTest FAIL: " + e.getMessage());
            return false;
        }
    }

    // ── TCP connect через Os.socket() ─────────────────────────────────────────

    private static long socketConnectTime(String host, int port) {
        FileDescriptor fd = null;
        try {
            fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0);

            // protect: исключаем сокет из VPN туннеля → трафик идёт через LTE
            VpnTunnelService.protectSocket(fd instanceof FileDescriptor ? 
                getIntFd(fd) : -1);

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
           // FileLogger.d(TAG, "Os.ERR " + host + ":" + port + " errno=" + e.errno);
            return -1;
        } catch (Exception e) {
           // FileLogger.d(TAG, "ERR " + host + ":" + port + " — " + e.getMessage());
            return -1;
        } finally {
            if (fd != null) try { Os.close(fd); } catch (Exception ignored) {}
        }
    }

    /** Получить int fd из FileDescriptor через reflection */
    private static int getIntFd(FileDescriptor fd) {
        try {
            java.lang.reflect.Field f = FileDescriptor.class.getDeclaredField("descriptor");
            f.setAccessible(true);
            return (int) f.get(fd);
        } catch (Exception e) {
            return -1;
        }
    }

    // ── Сеть ─────────────────────────────────────────────────────────────────

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
}
