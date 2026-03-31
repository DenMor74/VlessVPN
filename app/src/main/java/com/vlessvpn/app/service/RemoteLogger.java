package com.vlessvpn.app.service;

import android.content.Context;
import android.os.Build;
import android.util.Base64;

import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class RemoteLogger {

    private static final String TAG = "RemoteLogger";
    private static final int INTERVAL = 10;

    private static final String YC_ENDPOINT = "https://logging.api.cloud.yandex.net/logging/v1/logGroups";
    private static final String remoteLogUrl = "https://hostilely-flawless-salmon.cloudpub.ru/";

    private static RemoteLogger instance;
    private final Context ctx;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;

    // ---------- IAM TOKEN CACHE ----------
    private static String iamToken = null;
    private static long tokenExpire = 0;


    private RemoteLogger(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static RemoteLogger getInstance(Context ctx) {
        if (instance == null) instance = new RemoteLogger(ctx);
        return instance;
    }

    public void start() {
        ServerRepository repo = new ServerRepository(ctx);
        if (!repo.isRemoteLogEnabled()) return;

        stop();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        task = scheduler.scheduleAtFixedRate(this::tick, 2, INTERVAL, TimeUnit.SECONDS);
        FileLogger.i(TAG, "RemoteLogger запущен");
    }

    public void stop() {
        if (task != null) task.cancel(true);
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void tick() {
        try {
            ServerRepository repo = new ServerRepository(ctx);

            if (!repo.isRemoteLogEnabled() || !VpnTunnelService.isRunning) {
                stop();
                return;
            }

            List<com.vlessvpn.app.model.VlessServer> all = repo.getAllServersSync();
            int total = all.size();
            int working = 0;
            for (com.vlessvpn.app.model.VlessServer s : all)
                if (s.trafficOk) working++;

            com.vlessvpn.app.model.VlessServer cur = VpnTunnelService.getCurrentServer();

            String device = Build.MANUFACTURER + " " + Build.MODEL;
            String server = cur != null ? cur.host : "";
            String lastUpd = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(repo.getLastUpdateTimestamp()));
            String url = repo.getRemoteLogUrl();
            if (url.isEmpty()) {
                url = remoteLogUrl;
            }

            sendToCustomUrl(url, device, total, working, server, lastUpd);
           if( repo.isRemoteLogYandexEnabled()){
               sendToYandexCloud(repo, device, total, working, server, lastUpd);
           }

        } catch (Exception e) {
            FileLogger.w(TAG, "tick error: " + e.getMessage());
        }
    }

    // ---------------- ПОЛУЧЕНИЕ IAM TOKEN ----------------
    private String getIamToken(ServerRepository repo) throws Exception {

        long now = System.currentTimeMillis();

        if (iamToken != null && now < tokenExpire)
            return iamToken;

        String serviceAccountId = repo.getYcServiceAccountId();
        String keyId = repo.getYcKeyId();
        String privateKey = repo.getYcPrivateKey();

        String header = "{\"alg\":\"PS256\",\"kid\":\"" + keyId + "\"}";
        String payload = "{\"iss\":\"" + serviceAccountId + "\",\"aud\":\"https://iam.api.cloud.yandex.net/iam/v1/tokens\",\"iat\":" + (now / 1000) + ",\"exp\":" + (now / 1000 + 3600) + "}";

        String encHeader = Base64.encodeToString(header.getBytes(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String encPayload = Base64.encodeToString(payload.getBytes(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

        String toSign = encHeader + "." + encPayload;

        PrivateKey key = getPrivateKey(privateKey);

        Signature sign = Signature.getInstance("SHA256withRSA/PSS");
        sign.initSign(key);
        sign.update(toSign.getBytes());

        String signature = Base64.encodeToString(sign.sign(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

        String jwt = toSign + "." + signature;

        URL url = new URL("https://iam.api.cloud.yandex.net/iam/v1/tokens");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        JSONObject body = new JSONObject();
        body.put("jwt", jwt);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes());
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);

        JSONObject json = new JSONObject(sb.toString());

        iamToken = json.getString("iamToken");
        tokenExpire = now + 55 * 60 * 1000;

        FileLogger.d(TAG, "IAM token обновлён");

        return iamToken;
    }

    private PrivateKey getPrivateKey(String key) throws Exception {
        key = key.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\n", "");

        byte[] decoded = Base64.decode(key, Base64.DEFAULT);

        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    // ---------------- ОТПРАВКА В YC ----------------
    private void sendToYandexCloud(ServerRepository repo,
                                   String device, int total, int working,
                                   String server, String lastUpdate) {

        HttpURLConnection conn = null;

        try {

            String token = getIamToken(repo);

            String groupId = repo.getRemoteLogGroupId();

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String ts = sdf.format(new Date());

            JSONObject payload = new JSONObject();
            payload.put("device", device);
            payload.put("total", total);
            payload.put("working", working);
            payload.put("server", server);
            payload.put("last_update", lastUpdate);

            JSONObject entry = new JSONObject();
            entry.put("timestamp", ts);
            entry.put("level", "INFO");
            entry.put("message", device + " | " + server + " | " + working + "/" + total);
            entry.put("streamName", device);
            entry.put("jsonPayload", payload);

            JSONObject dest = new JSONObject();
            dest.put("logGroupId", groupId);

            JSONObject resource = new JSONObject();
            resource.put("type", "global");
           // resource.put("folderId", "b1grjfud3d7lsb1sh064");   // b1gte7fmn3nssr8c38us   b1grjfud3d7lsb1sh064

            JSONObject body = new JSONObject();
            body.put("destination", dest);
            body.put("resource", resource);
            body.put("entries", new JSONArray().put(entry));

            conn = (HttpURLConnection) new URL(YC_ENDPOINT).openConnection();

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();

            if (code != 200) {
                BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                StringBuilder e = new StringBuilder();
                String l;
                while ((l = err.readLine()) != null) e.append(l);

                FileLogger.w(TAG, "YC Logging ERROR " + code + " -> " + e);
            } else {
                FileLogger.d(TAG, "YC Logging OK");
            }

        } catch (Exception e) {
            FileLogger.w(TAG, "YC ошибка: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Отправка на кастомный HTTP endpoint (как раньше) */
    private void sendToCustomUrl(String endpoint,
                                 String device, int total, int working,
                                 String server, String lastUpdate) {
        HttpURLConnection conn = null;
        try {
            JSONObject json = new JSONObject();
            json.put("device",      device);
            json.put("total",       total);
            json.put("working",     working);
            json.put("connected",   VpnTunnelService.isRunning);
            json.put("server",      server);
            json.put("last_update", lastUpdate);
            json.put("ts",          System.currentTimeMillis());

            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
            FileLogger.d(TAG, "Custom → HTTP " + conn.getResponseCode());

        } catch (Exception e) {
            FileLogger.w(TAG, "Custom ошибка: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }
}