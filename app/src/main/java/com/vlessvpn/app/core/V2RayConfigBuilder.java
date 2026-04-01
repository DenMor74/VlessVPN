package com.vlessvpn.app.core;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.util.FileLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * V2RayConfigBuilder — генерирует JSON конфигурацию для v2ray-core.
 */
public class V2RayConfigBuilder {

    private static final String TAG = "V2RayConfigBuilder";

    /**
     * Генерирует полный JSON конфиг v2ray для подключения к серверу.
     */
    public static String build(VlessServer server, int socksPort, int tunFd) {
        try {
            JSONObject config = new JSONObject();

            // ===== LOG =====
            JSONObject log = new JSONObject();
            log.put("loglevel", "warning");
            config.put("log", log);

            // ===== INBOUNDS =====
            JSONArray inbounds = new JSONArray();

            JSONObject socksInbound = new JSONObject();
            socksInbound.put("tag", "socks-in");
            socksInbound.put("port", socksPort);
            socksInbound.put("listen", "127.0.0.1");
            socksInbound.put("protocol", "socks");

            JSONObject socksSettings = new JSONObject();
            socksSettings.put("auth", "noauth");
            socksSettings.put("udp", true);
            socksInbound.put("settings", socksSettings);

            JSONObject sniffing = new JSONObject();
            sniffing.put("enabled", true);
            JSONArray destOverride = new JSONArray();
            destOverride.put("http");
            destOverride.put("tls");
            sniffing.put("destOverride", destOverride);
            socksInbound.put("sniffing", sniffing);

            inbounds.put(socksInbound);
            config.put("inbounds", inbounds);

            // ===== OUTBOUNDS =====
            JSONArray outbounds = new JSONArray();

            // VLESS outbound
            JSONObject vlessOut = new JSONObject();
            vlessOut.put("tag", "proxy");
            vlessOut.put("protocol", "vless");

            JSONObject outSettings = new JSONObject();
            JSONArray vnext = new JSONArray();
            JSONObject serverConf = new JSONObject();
            serverConf.put("address", server.host);
            serverConf.put("port", server.port);

            JSONArray users = new JSONArray();
            JSONObject user = new JSONObject();

            // ════════════════════════════════════════════════════════════════
            // ← ИСПРАВЛЕНО: используем server.uuid (НЕ server.id!)
            // server.id = uuid@host:port (для БД)
            // server.uuid = только UUID (для V2Ray)
            // ════════════════════════════════════════════════════════════════
            user.put("id", server.uuid);  // ← ВАЖНО: uuid, не id!
            user.put("encryption", "none");

            if (server.flow != null && !server.flow.isEmpty()) {
                user.put("flow", server.flow);
            }

            users.put(user);
            serverConf.put("users", users);
            vnext.put(serverConf);
            outSettings.put("vnext", vnext);
            vlessOut.put("settings", outSettings);

            // Stream Settings
            vlessOut.put("streamSettings", buildStreamSettings(server));
            outbounds.put(vlessOut);

            // Freedom outbound
            JSONObject freedom = new JSONObject();
            freedom.put("tag", "direct");
            freedom.put("protocol", "freedom");
            outbounds.put(freedom);

            // Blackhole
            JSONObject blackhole = new JSONObject();
            blackhole.put("tag", "block");
            blackhole.put("protocol", "blackhole");
            outbounds.put(blackhole);

            config.put("outbounds", outbounds);

            // ===== ROUTING =====
            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "AsIs");
            JSONArray rules = new JSONArray();
            JSONObject proxyAll = new JSONObject();
            proxyAll.put("type", "field");
            proxyAll.put("network", "tcp,udp");
            proxyAll.put("outboundTag", "proxy");
            rules.put(proxyAll);
            routing.put("rules", rules);
            config.put("routing", routing);

            return config.toString(2);

        } catch (JSONException e) {
            FileLogger.e(TAG, "Ошибка генерации конфига: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Строит streamSettings — параметры транспортного уровня.
     */
    private static JSONObject buildStreamSettings(VlessServer server) throws JSONException {
        JSONObject stream = new JSONObject();
        stream.put("network", server.networkType != null && !server.networkType.isEmpty() ? server.networkType : "tcp");

        String security = server.security != null ? server.security : "none";

        switch (security) {
            case "reality":
                stream.put("security", "reality");
                JSONObject realitySettings = new JSONObject();
                realitySettings.put("serverName", server.sni != null && !server.sni.isEmpty() ? server.sni : server.host);
                realitySettings.put("fingerprint", server.fp != null && !server.fp.isEmpty() ? server.fp : "chrome");
                realitySettings.put("publicKey", server.pbk != null ? server.pbk : "");
                realitySettings.put("shortId", server.sid != null ? server.sid : "");
                stream.put("realitySettings", realitySettings);
                break;

            case "tls":
                stream.put("security", "tls");
                JSONObject tlsSettings = new JSONObject();
                tlsSettings.put("serverName", server.sni != null && !server.sni.isEmpty() ? server.sni : server.host);
                tlsSettings.put("fingerprint", server.fp != null && !server.fp.isEmpty() ? server.fp : "chrome");
                tlsSettings.put("allowInsecure", false);
                stream.put("tlsSettings", tlsSettings);
                break;

            default:
                stream.put("security", "none");
        }

        switch (server.networkType != null ? server.networkType : "tcp") {
            case "xhttp":
                JSONObject xhttpSettings = new JSONObject();
                xhttpSettings.put("path", server.path != null ? server.path : "/");
                if (server.host2 != null && !server.host2.isEmpty()) {
                    xhttpSettings.put("host", server.host2);
                }
                xhttpSettings.put("mode", "auto");
                stream.put("xhttpSettings", xhttpSettings);
                break;

            case "ws":
                JSONObject wsSettings = new JSONObject();
                wsSettings.put("path", server.path != null ? server.path : "/");
                if (server.host2 != null && !server.host2.isEmpty()) {
                    JSONObject headers = new JSONObject();
                    headers.put("Host", server.host2);
                    wsSettings.put("headers", headers);
                }
                stream.put("wsSettings", wsSettings);
                break;

            case "grpc":
                JSONObject grpcSettings = new JSONObject();
                grpcSettings.put("serviceName", server.path != null ? server.path : "grpc");
                stream.put("grpcSettings", grpcSettings);
                break;

            default:
                JSONObject tcpSettings = new JSONObject();
                JSONObject header = new JSONObject();
                header.put("type", "none");
                tcpSettings.put("header", header);
                stream.put("tcpSettings", tcpSettings);
        }

        return stream;
    }

    /**
     * Конфиг для measureDelay — только SOCKS5 прокси, без TUN.
     */
    public static String buildForTest(VlessServer server, int socksPort) {
        return build(server, socksPort, -1);
    }

    /**
     * Генерирует "Мультиплекс-конфиг" для параллельного тестирования списка серверов.
     */
    public static String buildMultiplexTestConfig(List<VlessServer> servers, int basePort) {
        try {
            JSONObject config = new JSONObject();

            JSONObject log = new JSONObject();
            log.put("loglevel", "warning");
            config.put("log", log);

            JSONArray inbounds = new JSONArray();
            JSONArray outbounds = new JSONArray();
            JSONArray rules = new JSONArray();

            for (int i = 0; i < servers.size(); i++) {
                VlessServer server = servers.get(i);
                int localPort = basePort + i;
                String inTag = "in-" + i;
                String outTag = "proxy-" + i;

                // Пропускаем серверы без обязательных полей для REALITY
                if (server.security != null && server.security.equals("reality")) {
                    if (server.pbk == null || server.pbk.isEmpty()) {
                        FileLogger.w(TAG, "Пропуск сервера " + server.host + " — нет publicKey (pbk)");
                        continue;
                    }
                    if (server.sni == null || server.sni.isEmpty()) {
                        FileLogger.w(TAG, "Пропуск сервера " + server.host + " — нет serverName (sni)");
                        continue;
                    }
                }

                // 1. Inbound (Локальный HTTP прокси)
                JSONObject inbound = new JSONObject();
                inbound.put("tag", inTag);
                inbound.put("port", localPort);
                inbound.put("listen", "127.0.0.1");
                inbound.put("protocol", "http");

                JSONObject inSettings = new JSONObject();
                inSettings.put("timeout", 0);
                inbound.put("settings", inSettings);
                inbounds.put(inbound);

                // 2. Outbound (VLESS сервер)
                JSONObject vlessOut = new JSONObject();
                vlessOut.put("tag", outTag);
                vlessOut.put("protocol", "vless");

                JSONObject outSettings = new JSONObject();
                JSONArray vnext = new JSONArray();
                JSONObject serverConf = new JSONObject();
                serverConf.put("address", server.host);
                serverConf.put("port", server.port);

                JSONArray users = new JSONArray();
                JSONObject user = new JSONObject();

                // ════════════════════════════════════════════════════════════════
                // ← ИСПРАВЛЕНО: используем server.uuid (НЕ server.id!)
                // ════════════════════════════════════════════════════════════════
                user.put("id", server.uuid);  // ← ВАЖНО: uuid, не id!
                user.put("encryption", "none");

                if (server.flow != null && !server.flow.isEmpty()) {
                    user.put("flow", server.flow);
                }

                users.put(user);
                serverConf.put("users", users);
                vnext.put(serverConf);
                outSettings.put("vnext", vnext);
                vlessOut.put("settings", outSettings);

                // Stream Settings
                vlessOut.put("streamSettings", buildStreamSettings(server));
                outbounds.put(vlessOut);

                // 3. Routing rule
                JSONObject rule = new JSONObject();
                rule.put("type", "field");
                JSONArray inTags = new JSONArray();
                inTags.put(inTag);
                rule.put("inboundTag", inTags);
                rule.put("outboundTag", outTag);
                rules.put(rule);
            }

            // Обязательные outbounds
            JSONObject direct = new JSONObject();
            direct.put("tag", "direct");
            direct.put("protocol", "freedom");
            outbounds.put(direct);

            JSONObject block = new JSONObject();
            block.put("tag", "block");
            block.put("protocol", "blackhole");
            outbounds.put(block);

            config.put("inbounds", inbounds);
            config.put("outbounds", outbounds);

            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "AsIs");
            routing.put("rules", rules);
            config.put("routing", routing);

            String configStr = config.toString();
            FileLogger.d(TAG, "Мультиплекс конфиг: " + servers.size() + " серверов → " +
                    outbounds.length() + " outbounds, " + configStr.length() + " байт");
            return configStr;

        } catch (JSONException e) {
            FileLogger.e(TAG, "Ошибка генерации Multiplex-конфига: " + e.getMessage(), e);
            return null;
        }
    }
}