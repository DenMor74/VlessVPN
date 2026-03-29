package com.vlessvpn.app.service;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * RemoteLogger — отправляет статус VPN на удалённый сервер каждые 10 сек.
 *
 * JSON формат:
 * {
 *   "device":      "Samsung SM-S921B",
 *   "total":       30,
 *   "working":     8,
 *   "connected":   true,
 *   "server":      "109.120.188.225",
 *   "last_update": "2026-03-29 12:34:56"
 * }
 *
 * Включается через настройки: switch_remote_log + et_remote_log_url
 * Работает только пока VPN активен.
 */
public class RemoteLogger {

    private static final String TAG       = "RemoteLogger";
    private static final int    INTERVAL  = 10; // секунд

    private static RemoteLogger instance;

    private final Context  ctx;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?>        task;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RemoteLogger(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static RemoteLogger getInstance(Context ctx) {
        if (instance == null) instance = new RemoteLogger(ctx);
        return instance;
    }

    /** Запустить отправку (вызывать при подключении VPN) */
    public void start() {
        ServerRepository repo = new ServerRepository(ctx);
        if (!repo.isRemoteLogEnabled()) return;
        String url = repo.getRemoteLogUrl();
        if (url == null || url.isEmpty()) return;

        stop(); // на случай если уже запущен
        scheduler = Executors.newSingleThreadScheduledExecutor();
        task = scheduler.scheduleAtFixedRate(
                () -> sendStatus(url),
                2, INTERVAL, TimeUnit.SECONDS
        );
        FileLogger.i(TAG, "RemoteLogger запущен → " + url);
    }

    /** Остановить отправку (вызывать при отключении VPN) */
    public void stop() {
        if (task != null) { task.cancel(false); task = null; }
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
    }

    private void sendStatus(String endpoint) {
        try {
            ServerRepository repo = new ServerRepository(ctx);

            // Собираем данные
            List<com.vlessvpn.app.model.VlessServer> all = repo.getAllServersSync();
            int total = all.size(), working = 0;
            for (com.vlessvpn.app.model.VlessServer s : all) if (s.trafficOk) working++;

            com.vlessvpn.app.model.VlessServer cur = VpnTunnelService.getCurrentServer();
            String lastUpdate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(repo.getLastUpdateTimestamp()));

            JSONObject json = new JSONObject();
            json.put("device",      Build.MANUFACTURER + " " + Build.MODEL);
            json.put("total",       total);
            json.put("working",     working);
            json.put("connected",   VpnTunnelService.isRunning);
            json.put("server",      cur != null ? cur.host : "");
            json.put("last_update", lastUpdate);
            json.put("ts",          System.currentTimeMillis());

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            conn.disconnect();
            FileLogger.d(TAG, "Отправлено → HTTP " + code);

        } catch (Exception e) {
            FileLogger.w(TAG, "Ошибка отправки: " + e.getMessage());
        }
    }
}
