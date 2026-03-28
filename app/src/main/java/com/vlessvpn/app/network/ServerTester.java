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
import java.net.HttpURLConnection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.InetSocketAddress;
import java.net.URL;

/**
 * ServerTester — два типа проверок:
 *
 * 1. tcpTest()    — TCP пинг через LTE (приоритет) или WiFi (fallback).
 *                  Используется как первый этап в ScanWorker.
 *
 * 2. trafficTest() — HTTP GET через SOCKS5→v2ray.
 *                  Используется для проверки работы VPN в doConnectivityCheck.
 */
public class ServerTester {

    private static final String TAG      = "ServerTester";
    private static final int TIMEOUT_MS  = 10_000;
    private static final int REPEAT      = 2;
    private static final String[] CHECK_URLS = {
            "http://speed.cloudflare.com/__down?bytes=512",
            "http://ip-api.com/json?fields=query",
            "http://www.msftconnecttest.com/connecttest.txt"
    };

    private static final AtomicBoolean vpnActive = new AtomicBoolean(false);

    public static void setVpnActive(boolean active) { vpnActive.set(active); }
    public static boolean isVpnActive()             { return vpnActive.get(); }

    public static class TestResult {
        public long    pingMs       = -1;
        public boolean trafficOk   = false;
        public String  errorMessage = "";
        public String  networkType  = "";
        public long bestPing() { return pingMs >= 0 ? pingMs : Long.MAX_VALUE; }
    }

    // ── 1. TCP пинг ────────────────────────────────────────────────────────

    public static TestResult tcpTest(Context ctx, VlessServer server) {
        TestResult r = new TestResult();
        Network cellular = getCellularNetwork(ctx);
        long best = -1;

        if (cellular != null) {
            for (int i = 0; i < REPEAT; i++) {
                long ms = socketConnect(ctx, server.host, server.port, cellular);
                if (ms >= 0 && (best < 0 || ms < best)) { best = ms; r.networkType = "LTE"; }
            }
        }

        // Fallback на WiFi только если LTE сети нет совсем
        if (best < 0 && cellular == null) {
            Network wifi = getWifiNetwork(ctx);
            for (int i = 0; i < REPEAT; i++) {
                long ms = socketConnect(ctx, server.host, server.port, wifi);
                if (ms >= 0 && (best < 0 || ms < best)) { best = ms; r.networkType = "WiFi"; }
            }
        }

        if (best >= 0) {
            r.pingMs    = best;
            r.trafficOk = true;
        } else {
            r.errorMessage = "недоступен";
            r.networkType  = cellular != null ? "LTE" : "WiFi";
        }
        return r;
    }

    // ── 2. Проверка трафика через VPN ──────────────────────────────────────

    /**
     * Проверка трафика через VPN — 3 параллельных запроса к разным URL.
     * Возвращает true при первом успешном ответе.
     * Быстрее последовательной проверки при медленном туннеле.
     */
    public static boolean trafficTest() {
        if (!vpnActive.get()) return false;

        java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new InetSocketAddress("127.0.0.1", 10808));

        AtomicBoolean success = new AtomicBoolean(false);
        CountDownLatch latch  = new CountDownLatch(CHECK_URLS.length);
        ExecutorService pool  = Executors.newFixedThreadPool(CHECK_URLS.length);

        for (String urlStr : CHECK_URLS) {
            pool.execute(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection)
                            new URL(urlStr).openConnection(proxy);
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    if (code >= 200 && code < 400) {
                        success.set(true);
                        // Сигналим остальным потокам что можно заканчивать
                        while (latch.getCount() > 0) latch.countDown();
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        try { latch.await(TIMEOUT_MS + 2000L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) {}
        pool.shutdownNow();

        return success.get();
    }

    // ── Сетевые утилиты ────────────────────────────────────────────────────

    private static long socketConnect(Context ctx, String host, int port, Network bindNet) {
        FileDescriptor fd = null;
        try {
            fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0);

            // 🔴 ИСПРАВЛЕНИЕ: Вызываем protect() ТОЛЬКО если VPN реально включен пользователем.
            // Если VPN выключен, вызов VpnTunnelService.protect() будит сервис,
            // создает Foreground-уведомление и зажигает AOD-экран.
            if (isVpnActive()) {
                try {
                    VpnTunnelService.protectSocket(getIntFd(fd));
                } catch (Exception ignored) {}
            }

            // Привязка сокета к LTE/WiFi (это само по себе пускает трафик в обход VPN)
            if (bindNet != null) {
                try { bindNet.bindSocket(fd); } catch (Exception ignored) {}
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
        } catch (Exception e) {
            return -1;
        } finally {
            if (fd != null) try { Os.close(fd); } catch (Exception ignored) {}
        }
    }

    private static int getIntFd(FileDescriptor fd) {
        try {
            java.lang.reflect.Field f = FileDescriptor.class.getDeclaredField("descriptor");
            f.setAccessible(true);
            return (int) f.get(fd);
        } catch (Exception e) { return -1; }
    }

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
                    || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return net;
        }
        return null;
    }

    public static Network getWifiNetwork(Context ctx) {
        if (ctx == null) return null;
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))  continue;
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return net;
        }
        return null;
    }
}