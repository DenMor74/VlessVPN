package com.vlessvpn.app.core;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.util.FileLogger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * V2RayConfigBuilder — генерирует JSON конфигурацию для Xray-core (AndroidLibXrayLite)
 * Обновлено в апреле 2026 — добавлены современные функции Xray v26+
 */
public class V2RayConfigBuilder {

    private static final String TAG = "V2RayConfigBuilder";

    /**
     * Основной конфиг для подключения (одиночный сервер)
     */
    public static String build(VlessServer server, int socksPort, int tunFd) {
        sanitizeServer(server);
        try {
            JSONObject config = new JSONObject();

            // LOG
            JSONObject log = new JSONObject();
            log.put("loglevel", "warning");
            config.put("log", log);

            // ===== DNS (DoH + защита от утечек) =====
            JSONObject dns = new JSONObject();
            JSONArray dnsServers = new JSONArray();
            //dnsServers.put("https://dns.yandex.ru/dns-query");
            //dnsServers.put("https://1.1.1.1/dns-query");
            dnsServers.put("1.1.1.1");
            dnsServers.put("8.8.8.8");

            dnsServers.put("localhost");
            dns.put("servers", dnsServers);
            dns.put("queryStrategy", "UseIP");
            dns.put("disableCache", false);
            dns.put("disableFallback", true);
            dns.put("tag", "dns-out");
            config.put("dns", dns);

            // INBOUNDS
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

            // OUTBOUNDS
            JSONArray outbounds = new JSONArray();

            // VLESS
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
            user.put("id", server.uuid);
            user.put("encryption", "none");

            // Автоматический Vision flow для Reality
            if (server.security != null && server.security.equalsIgnoreCase("reality")) {
                user.put("flow", "xtls-rprx-vision");
            } else if (server.flow != null && !server.flow.isEmpty()) {
                user.put("flow", server.flow);
            }

            users.put(user);
            serverConf.put("users", users);
            vnext.put(serverConf);
            outSettings.put("vnext", vnext);
            vlessOut.put("settings", outSettings);

            vlessOut.put("streamSettings", buildStreamSettings(server));
            outbounds.put(vlessOut);

            // Freedom + Fragment
            JSONObject freedom = new JSONObject();
            freedom.put("tag", "direct");
            freedom.put("protocol", "freedom");
            JSONObject freedomSettings = new JSONObject();
            JSONObject fragment = new JSONObject();
            fragment.put("packets", "1-3");
            fragment.put("length", "10-20");
            fragment.put("interval", "5-10");
            freedomSettings.put("fragment", fragment);
            freedom.put("settings", freedomSettings);
            outbounds.put(freedom);

            // Blackhole
            JSONObject blackhole = new JSONObject();
            blackhole.put("tag", "block");
            blackhole.put("protocol", "blackhole");
            outbounds.put(blackhole);

            config.put("outbounds", outbounds);

            // ROUTING
            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "IPIfNonMatch");
            JSONArray rules = new JSONArray();

            JSONObject directPrivate = new JSONObject();
            directPrivate.put("type", "field");
            directPrivate.put("outboundTag", "direct");
            JSONArray privateIps = new JSONArray();
            privateIps.put("10.0.0.0/8");
            privateIps.put("172.16.0.0/12");
            privateIps.put("192.168.0.0/16");
            privateIps.put("127.0.0.0/8");
            privateIps.put("fc00::/7");
            privateIps.put("fe80::/10");
            directPrivate.put("ip", privateIps);
            rules.put(directPrivate);

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
     * Улучшенный StreamSettings (2026)
     */
    private static JSONObject buildStreamSettings(VlessServer server) throws JSONException {
        JSONObject stream = new JSONObject();

        String net = (server.networkType != null && !server.networkType.isEmpty())
                ? server.networkType.toLowerCase() : "tcp";
        stream.put("network", net);

        String security = server.security != null ? server.security.toLowerCase() : "none";

        String fp = (server.fp != null && !server.fp.isEmpty()) ? server.fp : "chrome";

        JSONArray alpn = new JSONArray();
        alpn.put("h2");
        alpn.put("http/1.1");

        if (security.equals("reality")) {
            stream.put("security", "reality");
            JSONObject realitySettings = new JSONObject();
            realitySettings.put("serverName", server.sni != null && !server.sni.isEmpty() ? server.sni : server.host);
            realitySettings.put("fingerprint", fp);
            realitySettings.put("publicKey", server.pbk != null ? server.pbk : "");
            realitySettings.put("shortId", server.sid != null ? server.sid : "");
            realitySettings.put("spiderX", server.getSpiderX());
            realitySettings.put("alpn", alpn);
            stream.put("realitySettings", realitySettings);

        } else if (security.equals("tls")) {
            stream.put("security", "tls");
            JSONObject tlsSettings = new JSONObject();
            tlsSettings.put("serverName", server.sni != null && !server.sni.isEmpty() ? server.sni : server.host);
            tlsSettings.put("fingerprint", fp);
            tlsSettings.put("allowInsecure", false);
            tlsSettings.put("alpn", alpn);
            stream.put("tlsSettings", tlsSettings);
        }

        // Sockopt
        JSONObject sockopt = new JSONObject();
        sockopt.put("tcpFastOpen", true);
        sockopt.put("tcpKeepAlive", true);
        stream.put("sockopt", sockopt);

        // Транспорт
        switch (net) {
            case "xhttp":
                JSONObject xhttpSettings = new JSONObject();
                xhttpSettings.put("path", server.path != null ? server.path : "/");
                if (server.host2 != null && !server.host2.isEmpty()) xhttpSettings.put("host", server.host2);
                xhttpSettings.put("mode", "stream-one");
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

            default: // tcp
                JSONObject tcpSettings = new JSONObject();
                JSONObject header = new JSONObject();
                header.put("type", "none");
                tcpSettings.put("header", header);
                stream.put("tcpSettings", tcpSettings);
        }

        return stream;
    }

    public static String buildForTest(VlessServer server, int socksPort) {
        return build(server, socksPort, -1);
    }

    /**
     * МУЛЬТИПЛЕКС КОНФИГ ДЛЯ СКАНИРОВАНИЯ — обновлён под новые функции
     * (фильтры оставлены полностью как у тебя были)
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

            int skippedInvalidShortId = 0;
            int skippedNoPublicKey = 0;
            int skippedNoSni = 0;
            int skippedHttpTransport = 0;
            int skippedOther = 0;
            int validCount = 0;

            for (int i = 0; i < servers.size(); i++) {
                VlessServer server = servers.get(i);
                sanitizeServer(server); // ← ДОБАВЛЕНА ОЧИСТКА: Спасаем серверы от мусора вроде "#🇩🇪 Germany"

                int localPort = basePort + i;
                String inTag = "in-" + i;
                String outTag = "proxy-" + i;

                // Все твои оригинальные фильтры (ничего не удалено)
                if (server.sid != null && !server.sid.isEmpty()) {
                    if (!server.sid.matches("^[0-9a-fA-F]{0,16}$")) {
                        FileLogger.w(TAG, "Пропуск сервера " + server.host + " — invalid shortId: " + server.sid);
                        skippedInvalidShortId++;
                        continue;
                    }
                }

                if (server.security != null && server.security.equals("reality")) {
                    if (server.pbk == null || server.pbk.isEmpty()) {
                        FileLogger.w(TAG, "Пропуск сервера " + server.host + " — нет publicKey (pbk)");
                        skippedNoPublicKey++;
                        continue;
                    }
                    if (server.sni == null || server.sni.isEmpty()) {
                        FileLogger.w(TAG, "Пропуск сервера " + server.host + " — нет serverName (sni)");
                        skippedNoSni++;
                        continue;
                    }
                }

                if ("http".equalsIgnoreCase(server.networkType)) {
                    FileLogger.w(TAG, "Пропуск сервера " + server.host + " — HTTP transport устарел");
                    skippedHttpTransport++;
                    continue;
                }

                String net = server.networkType != null ? server.networkType.trim().toLowerCase() : "tcp";
                if (!net.equals("tcp") && !net.equals("ws") && !net.equals("grpc") && !net.equals("xhttp") &&
                        !net.equals("kcp") && !net.equals("quic") && !net.equals("h2")) {
                    FileLogger.w(TAG, "Пропуск сервера " + server.host + " — мусор в networkType: " + net);
                    skippedOther++;
                    continue;
                }

                if (server.host == null || server.host.trim().isEmpty() ||
                        server.port <= 0 || server.port > 65535 ||
                        server.uuid == null || server.uuid.trim().isEmpty()) {
                    skippedOther++;
                    continue;
                }

                validCount++;

                // Inbound
                JSONObject inbound = new JSONObject();
                inbound.put("tag", inTag);
                inbound.put("port", localPort);
                inbound.put("listen", "127.0.0.1");
                inbound.put("protocol", "http");
                JSONObject inSettings = new JSONObject();
                inSettings.put("timeout", 0);
                inbound.put("settings", inSettings);
                inbounds.put(inbound);

                // Outbound
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
                user.put("id", server.uuid);
                user.put("encryption", "none");

                // ← Важное улучшение: Vision flow
                if (server.security != null && server.security.equalsIgnoreCase("reality")) {
                    user.put("flow", "xtls-rprx-vision");
                } else if (server.flow != null && !server.flow.isEmpty()) {
                    user.put("flow", server.flow);
                }

                users.put(user);
                serverConf.put("users", users);
                vnext.put(serverConf);
                outSettings.put("vnext", vnext);
                vlessOut.put("settings", outSettings);

                vlessOut.put("streamSettings", buildStreamSettings(server));  // ← теперь использует новую версию
                outbounds.put(vlessOut);

                // Routing rule
                JSONObject rule = new JSONObject();
                rule.put("type", "field");
                JSONArray inTags = new JSONArray();
                inTags.put(inTag);
                rule.put("inboundTag", inTags);
                rule.put("outboundTag", outTag);
                rules.put(rule);
            }

            FileLogger.i(TAG, "Фильтр: " + servers.size() + " → " + validCount + " (отклонено: shortId=" + skippedInvalidShortId +
                    ", noPbk=" + skippedNoPublicKey + ", noSni=" + skippedNoSni + ", http=" + skippedHttpTransport + ", other=" + skippedOther + ")");

            if (validCount == 0) {
                FileLogger.w(TAG, "Все серверы отфильтрованы — возвращаем fallback конфиг");
                return buildFallbackConfig();
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
            FileLogger.d(TAG, "ТЕСТ конфиг: " + validCount + " серверов → " + outbounds.length() + " outbounds");
            return configStr;

        } catch (JSONException e) {
            FileLogger.e(TAG, "Ошибка генерации Multiplex-конфига: " + e.getMessage(), e);
            return buildFallbackConfig();
        }
    }

    private static String buildFallbackConfig() {
        // Твой оригинальный fallback — полностью без изменений
        try {
            JSONObject config = new JSONObject();
            JSONObject log = new JSONObject();
            log.put("loglevel", "warning");
            config.put("log", log);

            JSONArray inbounds = new JSONArray();
            JSONObject inbound = new JSONObject();
            inbound.put("port", 10808);
            inbound.put("listen", "127.0.0.1");
            inbound.put("protocol", "socks");
            JSONObject settings = new JSONObject();
            settings.put("auth", "noauth");
            settings.put("udp", true);
            inbound.put("settings", settings);
            inbounds.put(inbound);
            config.put("inbounds", inbounds);

            JSONArray outbounds = new JSONArray();
            JSONObject direct = new JSONObject();
            direct.put("tag", "direct");
            direct.put("protocol", "freedom");
            outbounds.put(direct);
            config.put("outbounds", outbounds);

            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "AsIs");
            JSONArray rules = new JSONArray();
            JSONObject rule = new JSONObject();
            rule.put("type", "field");
            rule.put("outboundTag", "direct");
            rules.put(rule);
            routing.put("rules", rules);
            config.put("routing", routing);

            return config.toString();
        } catch (Exception e) {
            return "{\"log\":{\"loglevel\":\"warning\"},\"inbounds\":[],\"outbounds\":[{\"protocol\":\"freedom\",\"tag\":\"direct\"}]}";
        }
    }

    private static void sanitizeServer(VlessServer server) {
        if (server.pbk != null && server.pbk.contains("#"))
            server.pbk = server.pbk.substring(0, server.pbk.indexOf("#")).trim();

        if (server.sid != null && server.sid.contains("#"))
            server.sid = server.sid.substring(0, server.sid.indexOf("#")).trim();

        if (server.sni != null && server.sni.contains("#"))
            server.sni = server.sni.substring(0, server.sni.indexOf("#")).trim();

        if (server.networkType != null && server.networkType.contains("#"))
            server.networkType = server.networkType.substring(0, server.networkType.indexOf("#")).trim();

        if (server.security != null && server.security.contains("#"))
            server.security = server.security.substring(0, server.security.indexOf("#")).trim();
    }
}