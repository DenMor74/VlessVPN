package com.vlessvpn.app.service;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.vlessvpn.app.util.FileLogger;

import java.io.File;
import java.io.FileWriter;

/**
 * HevTunnel — обёртка над libhev-socks5-tunnel.so.
 *
 * ВАЖНО: libhev-socks5-tunnel.so скомпилирована для v2rayNG и при JNI_OnLoad
 * ищет нативные методы в классе "com.v2ray.ang.service.TProxyService".
 * Поэтому мы делегируем вызовы туда через stub-класс.
 *
 * Схема трафика:
 *   TUN fd → hev-socks5-tunnel → SOCKS5 127.0.0.1:10808 → v2ray → VLESS сервер
 */
public class HevTunnel {

    private static final String TAG = "HevTunnel";
    private static final int SOCKS_PORT = 10808;

    private final Context context;
    private volatile boolean running = false;

    public HevTunnel(Context context) {
        this.context = context;
    }

    /**
     * Запуск hev-socks5-tunnel.
     * Вызывать ПОСЛЕ того как v2ray запущен и слушает SOCKS5 на 10808.
     */
    public void start(ParcelFileDescriptor vpnInterface) {
        if (running) {
            FileLogger.w(TAG, "Уже запущен");
            return;
        }
        try {
            String configPath = writeConfig();
            int fd = vpnInterface.getFd();
            //FileLogger.i(TAG, "TProxyStartService configPath=" + configPath + " fd=" + fd);
            // Вызываем через stub — JNI ищет методы в com.v2ray.ang.service.TProxyService
            com.v2ray.ang.service.TProxyService.TProxyStartService(configPath, fd);
            running = true;
            FileLogger.i(TAG, "hev-socks5-tunnel запущен ✔");
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка запуска hev-socks5-tunnel", e);
        }
    }

    public void stop() {
        if (!running) return;
        try {
            com.v2ray.ang.service.TProxyService.TProxyStopService();
            running = false;
            FileLogger.i(TAG, "hev-socks5-tunnel остановлен");
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка остановки hev", e);
        }
    }

    public boolean isRunning() { return running; }

    public long[] getStats() {
        if (!running) return null;
        try {
            return com.v2ray.ang.service.TProxyService.TProxyGetStats();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * YAML конфиг для hev-socks5-tunnel.
     * Формат как в v2rayNG TProxyService.buildConfig().
     */
    private String writeConfig() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("tunnel:\n");
        sb.append("  mtu: 1500\n");
        sb.append("  ipv4: 10.1.10.1\n");      // локальный IP туннеля (не tun0)
        sb.append("socks5:\n");
        sb.append("  port: ").append(SOCKS_PORT).append("\n");
        sb.append("  address: 127.0.0.1\n");
        sb.append("  udp: 'udp'\n");
        sb.append("misc:\n");
        sb.append("  task-stack-size: 81920\n");
        sb.append("  connect-timeout: 5000\n");
        sb.append("  read-write-timeout: 60000\n");
        sb.append("  log-level: warn\n");

        File configFile = new File(context.getFilesDir(), "hev-socks5-tunnel.yaml");
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(sb.toString());
        }

        //FileLogger.d(TAG, "hev config:\n" + sb);
        return configFile.getAbsolutePath();
    }
}
