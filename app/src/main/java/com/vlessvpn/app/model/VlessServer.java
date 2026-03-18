package com.vlessvpn.app.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.vlessvpn.app.util.FileLogger;

/**
 * VlessServer — модель одного сервера (VLESS/VMess/Trojan).
 *
 * @Entity — говорит Room, что это таблица в базе данных.
 */
@Entity(tableName = "servers")
public class VlessServer {

    // ========== ПОЛЯ БАЗЫ ДАННЫХ ==========

    @PrimaryKey
    @NonNull
    public String id = "";

    public String host = "";
    public int port = 443;
    public String uuid = "";

    // Параметры соединения
    public String security = "none";
    public String networkType = "tcp";
    public String sni = "";
    public String pbk = "";
    public String fp = "chrome";
    public String flow = "";
    public String path = "/";
    public String sid = "";
    public String host2 = "";
    public String remark = "";

    // ════════════════════════════════════════════════════════════════
    // ← НОВЫЕ ПОЛЯ для поддержки xhttp и других транспортов
    // ════════════════════════════════════════════════════════════════
    public String mode = "";           // xhttp mode: stream-one, packet-up, auto
    public String extra = "";          // xhttp extra config
    public String spx = "";            // Reality spacer
    public String alpn = "";           // ALPN protocol
    public String protocol = "vless";  // "vless", "vmess", "trojan"
    // ════════════════════════════════════════════════════════════════

    public String rawUri = "";

    // ========== РЕЗУЛЬТАТЫ ТЕСТИРОВАНИЯ ==========

    public long pingMs = -1;
    public boolean trafficOk = false;
    public long lastTestedAt = 0;
    public String sourceUrl = "";

    // ========== СТАТИЧЕСКИЙ ПАРСЕР ==========

    /**
     * Парсит строку VLESS/VMess/Trojan URI в объект VlessServer.
     */
    public static VlessServer parse(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return null;
        }

        uri = uri.trim();

        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: Поддержка VMess
        // ════════════════════════════════════════════════════════════════
        if (uri.startsWith("vmess://")) {
            return parseVmess(uri);
        }

        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: Поддержка Trojan
        // ════════════════════════════════════════════════════════════════
        if (uri.startsWith("trojan://")) {
            return parseTrojan(uri);
        }

        // ════════════════════════════════════════════════════════════════
        // ← Hysteria2 — НЕ поддерживается Xray
        // ════════════════════════════════════════════════════════════════
        if (uri.startsWith("hysteria2://") || uri.startsWith("hysteria://")) {
            FileLogger.w("VlessServer", "Hysteria2 не поддерживается: " + uri.substring(0, Math.min(50, uri.length())));
            return null;
        }

        // VLESS
        if (!uri.startsWith("vless://")) {
            return null;
        }

        VlessServer s = new VlessServer();
        s.protocol = "vless";
        s.rawUri = uri;

        try {
            String body = uri.substring("vless://".length());

            // REMARK
            int hashIdx = body.lastIndexOf('#');
            if (hashIdx > 0) {
                s.remark = URLDecoder.decode(body.substring(hashIdx + 1), StandardCharsets.UTF_8.name());
                body = body.substring(0, hashIdx);
            }

            // UUID
            int atIdx = body.indexOf('@');
            if (atIdx < 0) return null;
            s.uuid = body.substring(0, atIdx);
            body = body.substring(atIdx + 1);

            // HOST:PORT
            int qIdx = body.indexOf('?');
            String hostPort = (qIdx > 0) ? body.substring(0, qIdx) : body;

            if (hostPort.startsWith("[")) {
                int bracketEnd = hostPort.indexOf(']');
                s.host = hostPort.substring(1, bracketEnd);
                s.port = Integer.parseInt(hostPort.substring(bracketEnd + 2));
            } else {
                int colonIdx = hostPort.lastIndexOf(':');
                s.host = hostPort.substring(0, colonIdx);
                s.port = Integer.parseInt(hostPort.substring(colonIdx + 1));
            }

            // QUERY PARAMS
            if (qIdx > 0) {
                Map<String, String> params = parseQueryParams(body.substring(qIdx + 1));

                s.security     = params.getOrDefault("security", "none");
                s.networkType  = params.getOrDefault("type", "tcp");
                s.sni          = params.getOrDefault("sni", s.host);
                s.pbk          = params.getOrDefault("pbk", "");
                s.fp           = params.getOrDefault("fp", "chrome");
                s.flow         = params.getOrDefault("flow", "");
                s.path         = params.getOrDefault("path", "/");
                s.sid          = params.getOrDefault("sid", "");
                s.host2        = params.getOrDefault("host", "");

                // ════════════════════════════════════════════════════════════════
                // ← НОВОЕ: Поддержка xhttp параметров
                // ════════════════════════════════════════════════════════════════
                s.mode         = params.getOrDefault("mode", "");
                s.extra        = params.getOrDefault("extra", "");
                s.spx          = params.getOrDefault("spx", "");
                s.alpn         = params.getOrDefault("alpn", "");
            }

            s.id = s.uuid + "@" + s.host + ":" + s.port;

            // ════════════════════════════════════════════════════════════════
            // ← ИСПРАВЛЕНО: НЕ фильтруем xhttp! Xray v26 поддерживает
            // ════════════════════════════════════════════════════════════════
            // xhttp теперь поддерживается в Xray-core 1.8.0+ (v26.x)
            // Оставляем сервера, но логируем для отладки
            if ("xhttp".equals(s.networkType)) {
                //FileLogger.d("VlessServer", "xhttp сервер добавлен: " + s.host + ":" + s.port);
            }

            if (s.remark.isEmpty()) {
                s.remark = s.host + ":" + s.port;
            }

        } catch (Exception e) {
            FileLogger.e("VlessServer", "Ошибка парсинга VLESS: " + e.getMessage());
            return null;
        }

        return s;
    }

    // ════════════════════════════════════════════════════════════════
    // ← НОВОЕ: Парсер VMess
    // ════════════════════════════════════════════════════════════════

    private static VlessServer parseVmess(String uri) {
        try {
            String base64 = uri.substring("vmess://".length());
            String json = new String(java.util.Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);

            org.json.JSONObject obj = new org.json.JSONObject(json);

            VlessServer s = new VlessServer();
            s.protocol = "vmess";
            s.uuid = obj.getString("id");
            s.host = obj.getString("add");
            s.port = obj.getInt("port");
            s.remark = obj.optString("ps", s.host + ":" + s.port);
            s.networkType = obj.optString("net", "tcp");
            s.security = obj.optString("tls", "none");
            s.sni = obj.optString("sni", s.host);
            s.host2 = obj.optString("host", "");
            s.path = obj.optString("path", "/");
            s.fp = obj.optString("fp", "chrome");
            s.alpn = obj.optString("alpn", "");

            s.id = s.uuid + "@" + s.host + ":" + s.port;
            s.rawUri = uri;

            //FileLogger.d("VlessServer", "VMess распарсен: " + s.remark);
            return s;

        } catch (Exception e) {
            FileLogger.e("VlessServer", "Ошибка парсинга VMess: " + e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ← НОВОЕ: Парсер Trojan
    // ════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════
// ← НОВОЕ: Парсер Trojan
// ════════════════════════════════════════════════════════════════

    private static VlessServer parseTrojan(String uri) {
        try {
            String body = uri.substring("trojan://".length());

            VlessServer s = new VlessServer();
            s.protocol = "trojan";

            // REMARK
            int hashIdx = body.lastIndexOf('#');
            if (hashIdx > 0) {
                s.remark = URLDecoder.decode(body.substring(hashIdx + 1), StandardCharsets.UTF_8.name());
                body = body.substring(0, hashIdx);
            }

            // PASSWORD@HOST:PORT
            int atIdx = body.indexOf('@');
            if (atIdx < 0) return null;
            s.uuid = body.substring(0, atIdx);  // В Trojan это password
            body = body.substring(atIdx + 1);

            // HOST:PORT
            int qIdx = body.indexOf('?');
            String hostPort = (qIdx > 0) ? body.substring(0, qIdx) : body;

            int colonIdx = hostPort.lastIndexOf(':');
            s.host = hostPort.substring(0, colonIdx);
            s.port = Integer.parseInt(hostPort.substring(colonIdx + 1));

            // ════════════════════════════════════════════════════════════════
            // ← ИСПРАВЛЕНО: Объявляем params перед if
            // ════════════════════════════════════════════════════════════════
            Map<String, String> params = new HashMap<>();

            if (qIdx > 0) {
                params = parseQueryParams(body.substring(qIdx + 1));
                s.sni = params.getOrDefault("sni", s.host);
                s.alpn = params.getOrDefault("alpn", "");
                s.fp = params.getOrDefault("fp", "chrome");
            }

            s.security = "tls";  // Trojan всегда использует TLS
            s.networkType = params.getOrDefault("type", "tcp");  // ← Теперь работает!

            s.id = s.uuid + "@" + s.host + ":" + s.port;
            s.rawUri = uri;

            if (s.remark.isEmpty()) {
                s.remark = s.host + ":" + s.port;
            }

            //FileLogger.d("VlessServer", "Trojan распарсен: " + s.remark);
            return s;

        } catch (Exception e) {
            FileLogger.e("VlessServer", "Ошибка парсинга Trojan: " + e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name());
                    String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
                    map.put(key, val);
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    public boolean needsTesting() {
        long oneHour = 60 * 60 * 1000L;
        return lastTestedAt == 0 || (System.currentTimeMillis() - lastTestedAt) > oneHour;
    }

    public String getPingText() {
        if (pingMs < 0) return "—";
        if (pingMs < 100) return pingMs + "ms ⚡";
        if (pingMs < 300) return pingMs + "ms ✓";
        return pingMs + "ms ⚠";
    }

    @Override
    public String toString() {
        return "VlessServer{protocol=" + protocol + ", host=" + host + ", port=" + port + ", ping=" + pingMs + "ms}";
    }
}