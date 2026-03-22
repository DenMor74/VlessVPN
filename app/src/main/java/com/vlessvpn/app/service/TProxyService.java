package com.vlessvpn.app.service;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.vlessvpn.app.util.FileLogger;

import java.io.File;
import java.io.FileWriter;

/**
 * TProxyService — обёртка для запуска hev-socks5-tunnel.
 *
 * ВАЖНО: libhev-socks5-tunnel.so при загрузке ищет класс
 * "com.v2ray.ang.service.TProxyService" (оригинальный пакет v2rayNG).
 * Поэтому native методы объявлены в stub-классе com.v2ray.ang.service.TProxyService,
 * а этот класс только управляет конфигом и вызывает stub.
 *
 * Цепочка:
 *   Телефон → TUN fd → hev-socks5-tunnel → SOCKS5:10808 → v2ray → VLESS сервер
 */
public class TProxyService {

    private static final String TAG = "TProxyService";

    private final Context context;
    private final ParcelFileDescriptor vpnInterface;
    private final int socksPort;
    private volatile boolean running = false;

    // ── Singleton для статического stop() ──────────────────────────────────
    private static volatile TProxyService currentInstance = null;

    /** true если libhev-socks5-tunnel.so успешно загружена */
    public static boolean isAvailable() {
        return com.v2ray.ang.service.TProxyService.isLoaded();
    }

    /** Останавливает текущий запущенный экземпляр */
    public static void stop() {
        TProxyService inst = currentInstance;
        if (inst != null) {
            inst.stopInstance();
            currentInstance = null;
        }
    }

    // ── Instance ────────────────────────────────────────────────────────────
    public TProxyService(Context context, ParcelFileDescriptor vpnInterface, int socksPort) {
        this.context      = context;
        this.vpnInterface = vpnInterface;
        this.socksPort    = socksPort;
    }

    /** Запускает hev-socks5-tunnel. Вызывать после запуска v2ray. */
    public void start() {
        if (!isAvailable()) {
            FileLogger.w(TAG, "start() пропущен — libhev-socks5-tunnel.so недоступна");
            return;
        }
        try {
            String configPath = writeConfig();
            int fd = vpnInterface.getFd();
            FileLogger.i(TAG, "TProxyStartService fd=" + fd + " socks=127.0.0.1:" + socksPort);
            com.v2ray.ang.service.TProxyService.TProxyStartService(configPath, fd);
            running = true;
            currentInstance = this;
            FileLogger.i(TAG, "hev-socks5-tunnel запущен ✓");
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка запуска hev-socks5-tunnel", e);
        }
    }

    private void stopInstance() {
        if (!running) return;
        try {
            com.v2ray.ang.service.TProxyService.TProxyStopService();
            running = false;
            FileLogger.i(TAG, "hev-socks5-tunnel остановлен");
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка остановки", e);
        }
    }

    public boolean isRunning() { return running; }

    /** Статистика: [0]=upload bytes, [1]=download bytes */
    public long[] getStats() {
        if (!running) return new long[]{0, 0};
        try { return com.v2ray.ang.service.TProxyService.TProxyGetStats(); }
        catch (Exception e) { return new long[]{0, 0}; }
    }

    /**
     * YAML конфиг для hev-socks5-tunnel.
     * tunnel.ipv4 ДОЛЖЕН совпадать с addAddress() в VpnTunnelService Builder (10.10.14.1).
     */
    private String writeConfig() throws Exception {
        String cfg =
            "tunnel:\n" +
            "  mtu: 1500\n" +
            "  ipv4: 10.10.14.1\n" +
            "socks5:\n" +
            "  port: " + socksPort + "\n" +
            "  address: 127.0.0.1\n" +
            "  udp: 'udp'\n" +
            "misc:\n" +
            "  tcp-read-write-timeout: 300000\n" +
            "  udp-read-write-timeout: 60000\n" +
            "  log-level: error\n";

        File f = new File(context.getFilesDir(), "hev-socks5-tunnel.yaml");
        FileWriter w = new FileWriter(f);
        w.write(cfg);
        w.close();
        return f.getAbsolutePath();
    }
}
