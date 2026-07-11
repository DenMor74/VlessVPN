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

        // МАКСИМАЛЬНО СТРОГАЯ ПРОВЕРКА ПО ЗАПРОСУ ПОЛЬЗОВАТЕЛЯ:
        // Если Reality ключ или UUID содержат спецсимволы, на которых падает ядро, мы отменяем сборку.
        if ("reality".equalsIgnoreCase(server.security)) {
            String key = server.pbk != null ? server.pbk : "";
            if (key.length() < 43 || key.contains("+") || key.contains("/") || key.contains("=")) {
                FileLogger.e(TAG, "Отмена сборки: Несовместимый pbk (спецсимволы или длина) - " + key);
                return null;
            }
        }
        
        // Проверка UUID для VLESS
        if ("vless".equalsIgnoreCase(server.protocol)) {
            if (server.uuid != null && (server.uuid.contains("+") || server.uuid.contains("/") || server.uuid.contains("=") || server.uuid.contains(" "))) {
                FileLogger.e(TAG, "Отмена сборки: Несовместимый UUID - " + server.uuid);
                return null;
            }
        }

        try {
            JSONObject config = new JSONObject();

            // ── LOG ──────────────────────────────────────────────────────────
            JSONObject log = new JSONObject();
            log.put("loglevel", "warning"); // warning, чтобы не засорять логи
            config.put("log", log);

            // ── FAKEDNS (НОВОЕ) ──────────────────────────────────────────────
            JSONArray fakednsArray = new JSONArray();
            JSONObject fakednsObj = new JSONObject();
            fakednsObj.put("ipPool", "198.18.0.0/15");
            fakednsObj.put("poolSize", 65535);
            fakednsArray.put(fakednsObj);
            config.put("fakedns", fakednsArray);

            // ── DNS (ОБНОВЛЕНО) ──────────────────────────────────────────────
            JSONObject dns = new JSONObject();
            JSONArray dnsServers = new JSONArray();
            dnsServers.put("fakedns"); // Обязательно первым!
            dnsServers.put("tcp://8.8.8.8");
            dnsServers.put("https://1.1.1.1/dns-query"); // DoH работает еще надежнее
            dns.put("servers", dnsServers);
            dns.put("queryStrategy", "UseIPv4");
            config.put("dns", dns);

            // ── INBOUNDS ─────────────────────────────────────────────────────
            JSONArray inbounds = new JSONArray();

            // SOCKS5 — для hev-socks5-tunnel
            JSONObject socksInbound = new JSONObject();
            socksInbound.put("tag", "socks-in");
            socksInbound.put("port", socksPort);       // 10808
            socksInbound.put("listen", "127.0.0.1");
            socksInbound.put("protocol", "socks");

            JSONObject socksSettings = new JSONObject();
            socksSettings.put("auth", "noauth");
            socksSettings.put("udp", true);
            socksInbound.put("settings", socksSettings);

            // Включаем сниффинг FakeDNS
            JSONObject sniffing = new JSONObject();
            sniffing.put("enabled", true);
            JSONArray destOverride = new JSONArray();
            destOverride.put("http");
            destOverride.put("tls");
            destOverride.put("quic");
            destOverride.put("fakedns");
            sniffing.put("destOverride", destOverride);
            sniffing.put("routeOnly", false);
            socksInbound.put("sniffing", sniffing);

            inbounds.put(socksInbound);
            config.put("inbounds", inbounds);

            // ── OUTBOUNDS ────────────────────────────────────────────────────
            JSONArray outbounds = new JSONArray();

            // 1. DNS (Для перехвата 53 порта и обработки FakeDNS)
            JSONObject dnsOut = new JSONObject();
            dnsOut.put("tag", "dns-out");
            dnsOut.put("protocol", "dns");
            outbounds.put(dnsOut);

            // 2. Proxy outbound
            String protocol = (server.protocol != null) ? server.protocol.toLowerCase() : "vless";
            JSONObject vlessOut = new JSONObject();
            vlessOut.put("tag", "proxy");
            vlessOut.put("protocol", protocol);
            JSONObject outSettings = new JSONObject();
            JSONArray vnext = new JSONArray();
            JSONObject serverConf = new JSONObject();
            serverConf.put("address", server.host);
            serverConf.put("port", server.port);
            JSONArray users = new JSONArray();
            JSONObject user = new JSONObject();

            boolean isReality = "reality".equalsIgnoreCase(server.security);

            if (protocol.equals("trojan")) {
                user.put("password", server.uuid);
            } else {
                user.put("id", server.uuid);
                user.put("encryption", "none");
            }

            if (isReality) {
                user.put("flow", "xtls-rprx-vision");
                // ГАРАНТИРУЕМ, что поля "password" нет для VLESS/Reality
                user.remove("password");
            } else if (server.flow != null && !server.flow.isEmpty()) {
                user.put("flow", server.flow);
            }
                users.put(user);
                serverConf.put("users", users);

                if (protocol.equals("trojan")) {
                    // Trojan outbound structure uses "servers" array instead of "vnext"
                    JSONArray serversArr = new JSONArray();
                    serversArr.put(serverConf);
                    outSettings.put("servers", serversArr);
                } else {
                    vnext.put(serverConf);
                    outSettings.put("vnext", vnext);
                }
                vlessOut.put("settings", outSettings);
                vlessOut.put("streamSettings", buildStreamSettings(server));
                outbounds.put(vlessOut);

            // 3. Direct
            JSONObject freedom = new JSONObject();
            freedom.put("tag", "direct");
            freedom.put("protocol", "freedom");
            outbounds.put(freedom);

            // 4. Blackhole
            JSONObject blackhole = new JSONObject();
            blackhole.put("tag", "block");
            blackhole.put("protocol", "blackhole");
            outbounds.put(blackhole);

            config.put("outbounds", outbounds);

            // ── ROUTING ──────────────────────────────────────────────────────
            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "AsIs");
            JSONArray rules = new JSONArray();

            // Правило 1: Порт 53 ловим и отправляем в dns-out (FakeDNS)
            JSONObject dnsRule = new JSONObject();
            dnsRule.put("type", "field");
            // ДОБАВЛЕНА ЭТА СТРОКА, чтобы избежать бесконечной петли:
            JSONArray inTags = new JSONArray();
            inTags.put("socks-in");
            dnsRule.put("inboundTag", inTags);

            dnsRule.put("port", "53");
            dnsRule.put("outboundTag", "dns-out");
            rules.put(dnsRule);

            // Правило 2: Приватные адреса — direct
            JSONObject directPrivate = new JSONObject();
            directPrivate.put("type", "field");
            directPrivate.put("outboundTag", "direct");
            JSONArray privateIps = new JSONArray();
            privateIps.put("172.16.0.0/12");
            privateIps.put("192.168.0.0/16");
            privateIps.put("127.0.0.0/8");
            privateIps.put("fc00::/7");
            privateIps.put("fe80::/10");
            directPrivate.put("ip", privateIps);
            rules.put(directPrivate);

            // Правило 3: Всё остальное — через proxy
            JSONObject proxyAll = new JSONObject();
            proxyAll.put("type", "field");
            proxyAll.put("network", "tcp,udp");
            proxyAll.put("outboundTag", "proxy");
            rules.put(proxyAll);

            routing.put("rules", rules);
            config.put("routing", routing);

            // Используем toString() без аргументов для компактности, 
            // но убеждаемся, что спецсимволы не ломают парсер.
            return config.toString();

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
        if (server.alpn != null && !server.alpn.isEmpty()) {
            for (String a : server.alpn.split(",")) {
                if (!a.trim().isEmpty()) alpn.put(a.trim());
            }
        } else {
            alpn.put("h2");
            alpn.put("http/1.1");
        }

        if (security.equals("reality")) {
            stream.put("security", "reality");
            JSONObject realitySettings = new JSONObject();
            realitySettings.put("serverName", server.sni != null && !server.sni.isEmpty() ? server.sni : server.host);
            realitySettings.put("fingerprint", fp);
            
            // ВАЖНО: Используем ключ как есть после sanitizeServer.
            // Он уже проверен на отсутствие спецсимволов.
            realitySettings.put("publicKey", server.pbk != null ? server.pbk : "");
            
            realitySettings.put("shortId", server.sid != null ? server.sid : "");
            realitySettings.put("spiderX", server.getSpiderX() != null ? server.getSpiderX() : "/");
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
            case "http":
                JSONObject xhttpSettings = new JSONObject();
                xhttpSettings.put("path", server.path != null ? server.path : "/");
                if (server.host2 != null && !server.host2.isEmpty()) xhttpSettings.put("host", server.host2);
                String xmode = (server.mode != null && !server.mode.isEmpty()) ? server.mode : "auto";
                xhttpSettings.put("mode", xmode);
                
                // Исправление XHTTP extra: ядро ожидает объект, а не строку
                if (server.extra != null && !server.extra.isEmpty()) {
                    try {
                        // Пытаемся распарсить строку extra как JSON объект
                        JSONObject extraObj = new JSONObject(server.extra);
                        xhttpSettings.put("extra", extraObj);
                    } catch (Exception e) {
                        // Если это не JSON (например, "null" или просто текст),
                        // лучше не добавлять вообще, чтобы не сломать конфиг
                        FileLogger.w(TAG, "Не удалось распарсить extra как JSON для " + server.host);
                    }
                }

                stream.put("xhttpSettings", xhttpSettings);
                stream.put("network", "xhttp");
                break;

            case "httpupgrade":
                JSONObject hupSettings = new JSONObject();
                hupSettings.put("path", server.path != null ? server.path : "/");
                if (server.host2 != null && !server.host2.isEmpty()) hupSettings.put("host", server.host2);
                stream.put("httpupgradeSettings", hupSettings);
                break;

            case "splithttp":
                JSONObject splitSettings = new JSONObject();
                splitSettings.put("path", server.path != null ? server.path : "/");
                if (server.host2 != null && !server.host2.isEmpty()) splitSettings.put("host", server.host2);
                stream.put("splithttpSettings", splitSettings);
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
                String protocol = (server.protocol != null) ? server.protocol.toLowerCase() : "vless";

                // Все твои оригинальные фильтры (ничего не удалено)
                if (server.sid != null && !server.sid.isEmpty()) {
                    if (!server.sid.matches("^[0-9a-fA-F]{0,16}$")) {
                        FileLogger.w(TAG, "Пропуск сервера " + server.host + " — invalid shortId: " + server.sid);
                        skippedInvalidShortId++;
                        continue;
                    }
                }


                if (server.security != null && server.security.equalsIgnoreCase("reality")) {
                    String key = server.pbk != null ? server.pbk : "";
                    
                    // МАКСИМАЛЬНО СТРОГАЯ ФИЛЬТРАЦИЯ: Отсекаем любые спецсимволы в ключе Reality.
                    if (key.length() < 43 || key.contains("+") || key.contains("/") || key.contains("=")) {
                        FileLogger.w(TAG, "Пропуск [" + server.remark + "] — несовместимый pbk: " + key);
                        skippedNoPublicKey++;
                        continue;
                    }

                    if (server.sni == null || server.sni.isEmpty()) {
                        server.sni = server.host;
                    }
                }

                String net = server.networkType != null ? server.networkType.trim().toLowerCase() : "tcp";
                if (net.equals("raw")) net = "tcp";

                if (!net.equals("tcp") && !net.equals("ws") && !net.equals("grpc") && !net.equals("xhttp") &&
                        !net.equals("kcp") && !net.equals("quic") && !net.equals("h2") &&
                        !net.equals("http") && !net.equals("httpupgrade") && !net.equals("splithttp")) {
                    FileLogger.w(TAG, "Пропуск [" + server.remark + "] — мусор в networkType: " + net + " | URI: " + server.rawUri);
                    skippedOther++;
                    continue;
                }

                if (server.host == null || server.host.trim().isEmpty() ||
                        server.port <= 0 || server.port > 65535 ||
                        server.uuid == null || server.uuid.trim().isEmpty()) {
                    skippedOther++;
                    continue;
                }

                // Дополнительная фильтрация UUID (пароля) от спецсимволов, если это VLESS
                if ("vless".equalsIgnoreCase(protocol)) {
                    if (server.uuid.contains("=") || server.uuid.contains("+") || server.uuid.contains("/") || server.uuid.contains(" ")) {
                        FileLogger.w(TAG, "Пропуск [" + server.remark + "] — несовместимый UUID: " + server.uuid);
                        skippedOther++;
                        continue;
                    }
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
                vlessOut.put("protocol", protocol);

                JSONObject outSettings = new JSONObject();
                JSONArray vnext = new JSONArray();
                JSONObject serverConf = new JSONObject();
                serverConf.put("address", server.host);
                serverConf.put("port", server.port);

                JSONArray users = new JSONArray();
                JSONObject user = new JSONObject();
                if (protocol.equals("trojan")) {
                    user.put("password", server.uuid);
                } else {
                    user.put("id", server.uuid);
                    user.put("encryption", "none");
                }

                // ← Важное улучшение: Vision flow
                // КРИТИЧНО: Для REALITY НЕЛЬЗЯ передавать поле "password" в конфиг VLESS/VMess
                // Xray падает с ошибкой infra/conf: invalid "password"
                if (server.security != null && server.security.equalsIgnoreCase("reality")) {
                    user.put("flow", "xtls-rprx-vision");
                    user.remove("password");
                } else if (server.flow != null && !server.flow.isEmpty()) {
                    user.put("flow", server.flow);
                }

                users.put(user);
                serverConf.put("users", users);
                
                if (protocol.equals("trojan")) {
                    JSONArray serversArr = new JSONArray();
                    serversArr.put(serverConf);
                    outSettings.put("servers", serversArr);
                } else {
                    vnext.put(serverConf);
                    outSettings.put("vnext", vnext);
                }
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
                return buildFallbackConfig(10808);
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
            return buildFallbackConfig(10808);
        }
    }

    private static String buildFallbackConfig(int socksPort) {
        // Твой оригинальный fallback — полностью без изменений
        try {
            JSONObject config = new JSONObject();
            JSONObject log = new JSONObject();
            log.put("loglevel", "warning");
            config.put("log", log);

            JSONArray inbounds = new JSONArray();
            JSONObject inbound = new JSONObject();
            inbound.put("port", socksPort);
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
        if (server == null) return;

        // UUID / Password
        if (server.uuid != null) {
            server.uuid = server.uuid.trim();
            if (server.uuid.contains("#"))
                server.uuid = server.uuid.substring(0, server.uuid.indexOf("#")).trim();
        }

        // Reality Public Key
        if (server.pbk != null) {
            server.pbk = server.pbk.trim();
            if (server.pbk.contains("#"))
                server.pbk = server.pbk.substring(0, server.pbk.indexOf("#")).trim();
            
            // НОРМАЛИЗАЦИЯ: В ссылках часто URL-safe Base64 (-/_).
            // Мы приводим к стандарту (+/), НО ПОТОМ ФИЛЬТРУЕМ ИХ в build/buildMultiplex.
            server.pbk = server.pbk.replace(" ", "+").replace("-", "+").replace("_", "/");
            
            // Удаляем паддинг '=', так как он 100% вызывает ошибку в этом ядре
            if (server.pbk.contains("=")) {
                server.pbk = server.pbk.replace("=", "");
            }
        }

        // Reality Short ID
        if (server.sid != null) {
            server.sid = server.sid.trim();
            if (server.sid.contains("#"))
                server.sid = server.sid.substring(0, server.sid.indexOf("#")).trim();
        }

        // SNI / Server Name
        if (server.sni != null) {
            server.sni = server.sni.trim();
            if (server.sni.contains("#"))
                server.sni = server.sni.substring(0, server.sni.indexOf("#")).trim();
        }

        // Network Type
        if (server.networkType != null) {
            server.networkType = server.networkType.trim().toLowerCase();
            if (server.networkType.contains("#"))
                server.networkType = server.networkType.substring(0, server.networkType.indexOf("#")).trim();
            
            // Маппинг нестандартных типов транспорта
            if (server.networkType.equals("raw")) server.networkType = "tcp";
            if (server.networkType.equals("h2")) server.networkType = "http";
        }

        // Security
        if (server.security != null) {
            server.security = server.security.trim();
            if (server.security.contains("#"))
                server.security = server.security.substring(0, server.security.indexOf("#")).trim();
        }
    }
}