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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerTester {

    private static final String TAG      = "ServerTester";
    private static final int TIMEOUT_MS  = 12_000;   // немного увеличил
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

    public static TestResult tcpTest(Context ctx, VlessServer server, Network bindNet) {
        TestResult r = new TestResult();
        long best = -1;

        if (bindNet != null) {
            for (int i = 0; i < REPEAT; i++) {
                long ms = socketConnect(ctx, server.host, server.port, bindNet);
                if (ms >= 0 && (best < 0 || ms < best)) {
                    best = ms;
                    r.networkType = getNetworkTypeName(bindNet, ctx);
                }
            }
        } else {
            Network cellular = getCellularNetwork(ctx);
            if (cellular != null) {
                for (int i = 0; i < REPEAT; i++) {
                    long ms = socketConnect(ctx, server.host, server.port, cellular);
                    if (ms >= 0 && (best < 0 || ms < best)) {
                        best = ms;
                        r.networkType = "LTE";
                    }
                }
            }
            if (best < 0) {
                Network wifi = getWifiNetwork(ctx);
                for (int i = 0; i < REPEAT; i++) {
                    long ms = socketConnect(ctx, server.host, server.port, wifi);
                    if (ms >= 0 && (best < 0 || ms < best)) {
                        best = ms;
                        r.networkType = "WiFi";
                    }
                }
            }
        }

        if (best >= 0) {
            r.pingMs    = best;
            r.trafficOk = true;
        } else {
            r.errorMessage = "недоступен";
            r.networkType  = bindNet != null ? getNetworkTypeName(bindNet, ctx) : "unknown";
        }
        return r;
    }

    public static TestResult tcpTest(Context ctx, VlessServer server) {
        return tcpTest(ctx, server, null);
    }

    private static String getNetworkTypeName(Network net, Context ctx) {
        if (net == null || ctx == null) return "unknown";
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "unknown";
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return "unknown";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "LTE";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
        return "Other";
    }

    // ── 2. Проверка трафика через VPN (без изменений) ──────────────────────
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
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new URL(urlStr).openConnection(proxy);
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    if (code >= 200 && code < 400) success.set(true);
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

    // ── НОВЫЙ: безопасная привязка сокета ─────────────────────────────────
    private static boolean safeBindSocket(Socket socket, Network network) {
        if (network == null || socket == null) return false;

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                network.bindSocket(socket);
                return true;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("EPERM") || msg.contains("Operation not permitted")) {
                    if (attempt == 0) {
                        FileLogger.w(TAG, "bindSocket EPERM → retry 400мс (смена сети)");
                        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    FileLogger.w(TAG, "bindSocket EPERM → игнорируем, продолжаем тест");
                    return false;
                } else {
                    FileLogger.e(TAG, "bindSocket неожиданная ошибка", e);
                    return false;
                }
            }
        }
        return false;
    }

    // ── Обновлённый socketConnect ─────────────────────────────────────────
    private static long socketConnect(Context ctx, String host, int port, Network bindNet) {
        Socket socket = new Socket();
        try {
            // protect от утечек через VPN
            if (VpnTunnelService.getInstance() != null) {
                VpnTunnelService.getInstance().protect(socket);
            }

            if (bindNet != null) {
                safeBindSocket(socket, bindNet);   // ← теперь безопасно
            }

            long start = System.currentTimeMillis();
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return System.currentTimeMillis() - start;

        } catch (Exception e) {
            return -1;
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ── Остальные методы без изменений ────────────────────────────────────
    public static Network getCellularNetwork(Context ctx) { /* ... твой код ... */
        // (оставляю как было)
        if (ctx == null) return null;
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return net;
        }
        return null;
    }

    public static Network getWifiNetwork(Context ctx) { /* ... твой код ... */
        if (ctx == null) return null;
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return net;
        }
        return null;
    }
}