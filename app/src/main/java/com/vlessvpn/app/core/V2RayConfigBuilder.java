package com.vlessvpn.app.core;

import com.vlessvpn.app.model.VlessServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * V2RayConfigBuilder — строит JSON конфиг для libv2ray.
 *
 * КЛЮЧЕВОЕ ОТКРЫТИЕ из логов:
 *   protocol="tun" → ошибка "Listen on AnyIP but no Port(s) set"
 *   Libv2ray v5.47 использует V4 конфиг-парсер который НЕ знает "tun" inbound.
 *
 * ПРАВИЛЬНАЯ СХЕМА:
 *   - TUN fd передаётся через startup() → libv2ray сам читает пакеты
 *   - В конфиге НЕТ tun inbound вообще
 *   - Только socks inbound для measureDelay
 *   - Routing: всё через proxy (v2ray получает пакеты от ОС через fd напрямую)
 *
 * Аналог: v2rayNG v2ray_config_with_tun.json содержит tun inbound с xray-специфичными
 * полями, но в нашей сборке libv2ray это не поддерживается — fd передаётся через startup().
 */
public class V2RayConfigBuilder {

    /**
     * Строит конфиг для основного VPN подключения.
     * TUN fd передаётся через startup() — не нужен inbound в конфиге.
     */
    public static String build(VlessServer server, int socksPort) {
        try {
            JSONObject config = new JSONObject();

            // ── LOG ──────────────────────────────────────────────────────────
            JSONObject log = new JSONObject();
            log.put("loglevel", "error");  // debug для диагностики
            config.put("log", log);

            // ── INBOUNDS ─────────────────────────────────────────────────────
            // ВАЖНО: НЕТ "tun" inbound — libv2ray не поддерживает его в v4 формате.
            // TUN fd libv2ray получает через startup() и сам читает IP пакеты.
            // Оставляем только socks для measureDelay и диагностики.
            JSONArray inbounds = new JSONArray();

            // SOCKS5 — для measureDelay и диагностики через curl/adb
            JSONObject socksInbound = new JSONObject();
            socksInbound.put("tag", "socks");
            socksInbound.put("port", socksPort);
            socksInbound.put("listen", "127.0.0.1");
            socksInbound.put("protocol", "socks");
            JSONObject socksSettings = new JSONObject();
            socksSettings.put("auth", "noauth");
            socksSettings.put("udp", true);
            socksInbound.put("settings", socksSettings);
            // sniffing ОТКЛЮЧЁН — ломает Reality TLS handshake
            JSONObject sniffOff = new JSONObject();
            sniffOff.put("enabled", false);
            socksInbound.put("sniffing", sniffOff);
            inbounds.put(socksInbound);

            config.put("inbounds", inbounds);

            // ── OUTBOUNDS ────────────────────────────────────────────────────
            JSONArray outbounds = new JSONArray();
            outbounds.put(buildVlessOutbound(server));

            JSONObject freedom = new JSONObject();
            freedom.put("tag", "direct");
            freedom.put("protocol", "freedom");
            freedom.put("settings", new JSONObject());
            outbounds.put(freedom);

            JSONObject blackhole = new JSONObject();
            blackhole.put("tag", "block");
            blackhole.put("protocol", "blackhole");
            blackhole.put("settings", new JSONObject());
            outbounds.put(blackhole);

            config.put("outbounds", outbounds);

            // ── ROUTING ──────────────────────────────────────────────────────
            // Libv2ray читает TUN fd через startup() и маршрутизирует пакеты
            // через proxy outbound — это НЕ требует routing rules для TUN.
            // Правила нужны только для socks inbound.
            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "AsIs");
            JSONArray rules = new JSONArray();

            // socks → proxy (для measureDelay)
            JSONObject socksRule = new JSONObject();
            socksRule.put("type", "field");
            socksRule.put("inboundTag", new JSONArray().put("socks"));
            socksRule.put("outboundTag", "proxy");
            rules.put(socksRule);

            routing.put("rules", rules);
            config.put("routing", routing);

            // ── POLICY ───────────────────────────────────────────────────────
            config.put("policy", buildPolicy());

            // ── STATS ────────────────────────────────────────────────────────
            // Нужен для queryStats("proxy", "uplink")
            config.put("stats", new JSONObject());

            return config.toString(2);

        } catch (JSONException e) {
            throw new RuntimeException("Ошибка генерации конфига: " + e.getMessage(), e);
        }
    }

    /**
     * Конфиг для measureDelay — без TUN, без socks даже.
     * Только outbound и минимальный inbound.
     */
    public static String buildForTest(VlessServer server, int socksPort) {
        try {
            JSONObject config = new JSONObject();

            JSONObject log = new JSONObject();
            log.put("loglevel", "error");
            config.put("log", log);

            JSONArray inbounds = new JSONArray();
            JSONObject socksInbound = new JSONObject();
            socksInbound.put("tag", "socks");
            socksInbound.put("port", socksPort);
            socksInbound.put("listen", "127.0.0.1");
            socksInbound.put("protocol", "socks");
            JSONObject socksSettings = new JSONObject();
            socksSettings.put("auth", "noauth");
            socksSettings.put("udp", false);
            socksInbound.put("settings", socksSettings);
            JSONObject sniffOff = new JSONObject();
            sniffOff.put("enabled", false);
            socksInbound.put("sniffing", sniffOff);
            inbounds.put(socksInbound);
            config.put("inbounds", inbounds);

            JSONArray outbounds = new JSONArray();
            outbounds.put(buildVlessOutbound(server));
            JSONObject freedom = new JSONObject();
            freedom.put("tag", "direct");
            freedom.put("protocol", "freedom");
            freedom.put("settings", new JSONObject());
            outbounds.put(freedom);
            config.put("outbounds", outbounds);

            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "AsIs");
            config.put("routing", routing);

            return config.toString(2);

        } catch (JSONException e) {
            throw new RuntimeException("Ошибка генерации тестового конфига: " + e.getMessage(), e);
        }
    }

    // ── VLESS outbound ───────────────────────────────────────────────────────

    private static JSONObject buildVlessOutbound(VlessServer server) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("tag", "proxy");
        out.put("protocol", "vless");

        JSONObject settings = new JSONObject();
        JSONArray vnext = new JSONArray();
        JSONObject serverObj = new JSONObject();
        serverObj.put("address", server.host);
        serverObj.put("port", server.port);

        JSONArray users = new JSONArray();
        JSONObject user = new JSONObject();
        user.put("id", server.uuid);
        user.put("encryption", "none");
        if (server.flow != null && !server.flow.isEmpty()) {
            user.put("flow", server.flow);
        }
        users.put(user);
        serverObj.put("users", users);
        vnext.put(serverObj);
        settings.put("vnext", vnext);
        out.put("settings", settings);

        out.put("streamSettings", buildStreamSettings(server));

        return out;
    }

    // ── streamSettings ───────────────────────────────────────────────────────

    private static JSONObject buildStreamSettings(VlessServer server) throws JSONException {
        JSONObject stream = new JSONObject();

        String network = (server.networkType == null || server.networkType.isEmpty())
            ? "tcp" : server.networkType;
        stream.put("network", network);

        String sec = server.security == null ? "" : server.security;
        switch (sec) {
            case "reality": {
                stream.put("security", "reality");
                JSONObject rs = new JSONObject();
                rs.put("serverName",  server.sni != null ? server.sni : "");
                // fingerprint: "qq" → заменяем на "chrome" если невалидный
                String fp = server.fp;
                if (fp == null || fp.isEmpty()) fp = "qq";
                rs.put("fingerprint", fp);
                rs.put("publicKey",   server.pbk != null ? server.pbk : "");
                rs.put("shortId",     server.sid != null ? server.sid : "");
                rs.put("spiderX",     "/");
                stream.put("realitySettings", rs);
                break;
            }
            case "tls": {
                stream.put("security", "tls");
                JSONObject ts = new JSONObject();
                ts.put("serverName",  server.sni != null ? server.sni : "");
                String fp = server.fp;
                if (fp == null || fp.isEmpty()) fp = "qq";
                ts.put("fingerprint", fp);
                ts.put("allowInsecure", false);
                stream.put("tlsSettings", ts);
                break;
            }
            default:
                // none или пусто — не добавляем security поле
                break;
        }

        switch (network) {
            case "ws": {
                JSONObject ws = new JSONObject();
                ws.put("path", server.path != null && !server.path.isEmpty() ? server.path : "/");
                if (server.host2 != null && !server.host2.isEmpty()) {
                    JSONObject headers = new JSONObject();
                    headers.put("Host", server.host2);
                    ws.put("headers", headers);
                }
                stream.put("wsSettings", ws);
                break;
            }
            case "grpc": {
                JSONObject grpc = new JSONObject();
                grpc.put("serviceName", server.path != null ? server.path : "");
                stream.put("grpcSettings", grpc);
                break;
            }
            case "h2":
            case "http": {
                stream.put("network", "h2");
                JSONObject h2 = new JSONObject();
                h2.put("path", server.path != null && !server.path.isEmpty() ? server.path : "/");
                if (server.host2 != null && !server.host2.isEmpty()) {
                    JSONArray hosts = new JSONArray();
                    hosts.put(server.host2);
                    h2.put("host", hosts);
                }
                stream.put("httpSettings", h2);
                break;
            }
            default: {
                // TCP — для Reality/TLS
                JSONObject tcp = new JSONObject();
                JSONObject header = new JSONObject();
                header.put("type", "none");
                tcp.put("header", header);
                stream.put("tcpSettings", tcp);
                break;
            }
        }

        return stream;
    }

    // ── Policy ───────────────────────────────────────────────────────────────

    private static JSONObject buildPolicy() throws JSONException {
        JSONObject policy = new JSONObject();
        JSONObject levels = new JSONObject();
        JSONObject level0 = new JSONObject();
        level0.put("handshake", 4);
        level0.put("connIdle", 300);
        level0.put("uplinkOnly", 1);
        level0.put("downlinkOnly", 1);
        levels.put("0", level0);
        policy.put("levels", levels);
        JSONObject system = new JSONObject();
        system.put("statsOutboundUplink", true);
        system.put("statsOutboundDownlink", true);
        policy.put("system", system);
        return policy;
    }
}
