package com.vlessvpn.app.core;

import com.vlessvpn.app.model.VlessServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * V2RayConfigBuilder — генерирует JSON конфигурацию для v2ray-core.
 *
 * V2Ray core принимает JSON файл с описанием:
 * - inbounds: откуда принимать трафик (локальный SOCKS5 прокси)
 * - outbounds: куда отправлять трафик (наш VLESS сервер)
 * - routing: правила маршрутизации
 *
 * Эта конфигурация создаётся динамически для каждого сервера.
 */
public class V2RayConfigBuilder {

    /**
     * Генерирует полный JSON конфиг v2ray для подключения к серверу.
     *
     * @param server  Параметры VLESS сервера
     * @param socksPort  Порт локального SOCKS5 прокси (обычно 10808)
     * @param tunFd   File Descriptor TUN интерфейса (от VpnService), -1 если не нужен
     * @return JSON строка конфигурации
     */
    public static String build(VlessServer server, int socksPort, int tunFd) {
        try {
            JSONObject config = new JSONObject();

            // ===== LOG =====
            // Уровень логирования: "none", "error", "warning", "info", "debug"
            JSONObject log = new JSONObject();
            config.put("log", log);

            // ===== INBOUNDS (входящие подключения) =====
            // Локальный SOCKS5 прокси — через него VpnService направляет весь трафик
            JSONArray inbounds = new JSONArray();

            JSONObject socksInbound = new JSONObject();
            socksInbound.put("tag", "socks-in");
            socksInbound.put("port", socksPort);
            socksInbound.put("listen", "127.0.0.1"); // только локально!
            socksInbound.put("protocol", "socks");

            JSONObject socksSettings = new JSONObject();
            socksSettings.put("auth", "noauth"); // без пароля
            socksSettings.put("udp", true);       // UDP тоже через прокси
            socksInbound.put("settings", socksSettings);

            // Sniffing — определяем домен из трафика для правильной маршрутизации
            JSONObject sniffing = new JSONObject();
            sniffing.put("enabled", true);
            JSONArray destOverride = new JSONArray();
            destOverride.put("http");
            destOverride.put("tls");
            sniffing.put("destOverride", destOverride);
            socksInbound.put("sniffing", sniffing);

            inbounds.put(socksInbound);

            // HTTP прокси (дополнительно, порт socksPort+1)
            JSONObject httpInbound = new JSONObject();
            httpInbound.put("tag", "http-in");
            httpInbound.put("port", socksPort + 1);
            httpInbound.put("listen", "127.0.0.1");
            httpInbound.put("protocol", "http");
            inbounds.put(httpInbound);

            config.put("inbounds", inbounds);

            // ===== OUTBOUNDS (исходящие подключения) =====
            JSONArray outbounds = new JSONArray();

            // --- VLESS outbound (основной — через VPN сервер) ---
            JSONObject vlessOut = new JSONObject();
            vlessOut.put("tag", "proxy");
            vlessOut.put("protocol", "vless");

            // Настройки VLESS (UUID, адрес, порт)
            JSONObject outSettings = new JSONObject();
            JSONArray vnext = new JSONArray();
            JSONObject serverConf = new JSONObject();
            serverConf.put("address", server.host);
            serverConf.put("port", server.port);

            JSONArray users = new JSONArray();
            JSONObject user = new JSONObject();
            user.put("id", server.uuid);
            user.put("encryption", "none"); // VLESS не шифрует сам (шифрование на уровне TLS/Reality)
            if (!server.flow.isEmpty()) {
                user.put("flow", server.flow); // xtls-rprx-vision для Reality
            }
            users.put(user);
            serverConf.put("users", users);
            vnext.put(serverConf);
            outSettings.put("vnext", vnext);
            vlessOut.put("settings", outSettings);

            // Stream Settings — как устанавливаем соединение
            vlessOut.put("streamSettings", buildStreamSettings(server));

            outbounds.put(vlessOut);

            // --- Freedom outbound (прямое соединение без VPN) ---
            JSONObject freedom = new JSONObject();
            freedom.put("tag", "direct");
            freedom.put("protocol", "freedom");
            JSONObject freedomSettings = new JSONObject();
            freedomSettings.put("domainStrategy", "UseIPv4");
            freedom.put("settings", freedomSettings);
            outbounds.put(freedom);

            // --- Blackhole (блокировка) ---
            JSONObject blackhole = new JSONObject();
            blackhole.put("tag", "block");
            blackhole.put("protocol", "blackhole");
            outbounds.put(blackhole);

            config.put("outbounds", outbounds);

            // ===== ROUTING =====
            JSONObject routing = buildRouting();
            config.put("routing", routing);

            // ===== POLICY (тайм-ауты) =====
            JSONObject policy = new JSONObject();
            JSONObject levels = new JSONObject();
            JSONObject level0 = new JSONObject();
            level0.put("handshake", 4);      // секунд на хендшейк
            level0.put("connIdle", 300);      // таймаут простоя
            level0.put("uplinkOnly", 1);
            level0.put("downlinkOnly", 1);
            levels.put("0", level0);
            policy.put("levels", levels);
            config.put("policy", policy);

            return config.toString(2); // красивый JSON с отступами

        } catch (JSONException e) {
            throw new RuntimeException("Ошибка генерации конфига v2ray: " + e.getMessage(), e);
        }
    }

    /**
     * Строит streamSettings — параметры транспортного уровня.
     * Зависит от типа соединения: Reality, TLS, или без шифрования.
     */
    private static JSONObject buildStreamSettings(VlessServer server) throws JSONException {
        JSONObject stream = new JSONObject();

        // Тип сети: tcp, xhttp, ws, grpc
        stream.put("network", server.networkType);

        // ---- БЕЗОПАСНОСТЬ ----
        switch (server.security) {

            case "reality":
                // Reality — современная маскировка под обычный HTTPS сайт
                // Сервер притворяется настоящим сайтом (sni), но мы знаем секретный ключ (pbk)
                stream.put("security", "reality");
                JSONObject realitySettings = new JSONObject();
                realitySettings.put("serverName", server.sni);    // притворяемся этим сайтом
                realitySettings.put("fingerprint", server.fp);     // отпечаток браузера
                realitySettings.put("publicKey", server.pbk);      // публичный ключ сервера
                realitySettings.put("shortId", server.sid);        // дополнительный идентификатор
                realitySettings.put("spiderX", "/");
                stream.put("realitySettings", realitySettings);
                break;

            case "tls":
                // Обычный TLS (как HTTPS)
                stream.put("security", "tls");
                JSONObject tlsSettings = new JSONObject();
                tlsSettings.put("serverName", server.sni);
                tlsSettings.put("fingerprint", server.fp);
                tlsSettings.put("allowInsecure", false);
                stream.put("tlsSettings", tlsSettings);
                break;

            default:
                // Без шифрования (не рекомендуется, но бывает)
                stream.put("security", "none");
        }

        // ---- ТИП ТРАНСПОРТА ----
        switch (server.networkType) {

            case "xhttp":
                // XHTTP — новый транспорт, выглядит как обычный HTTP запрос
                JSONObject xhttpSettings = new JSONObject();
                xhttpSettings.put("path", server.path);
                if (!server.host2.isEmpty()) {
                    xhttpSettings.put("host", server.host2);
                }
                xhttpSettings.put("mode", "auto");
                stream.put("xhttpSettings", xhttpSettings);
                break;

            case "ws":
                // WebSocket транспорт
                JSONObject wsSettings = new JSONObject();
                wsSettings.put("path", server.path);
                if (!server.host2.isEmpty()) {
                    JSONObject headers = new JSONObject();
                    headers.put("Host", server.host2);
                    wsSettings.put("headers", headers);
                }
                stream.put("wsSettings", wsSettings);
                break;

            case "grpc":
                // gRPC транспорт
                JSONObject grpcSettings = new JSONObject();
                grpcSettings.put("serviceName", server.path);
                stream.put("grpcSettings", grpcSettings);
                break;

            default:
                // TCP (стандарт)
                JSONObject tcpSettings = new JSONObject();
                JSONObject header = new JSONObject();
                header.put("type", "none");
                tcpSettings.put("header", header);
                stream.put("tcpSettings", tcpSettings);
        }

        return stream;
    }

    /**
     * Правила маршрутизации.
     * Что блокируем, что пускаем напрямую, что через VPN.
     */
    private static JSONObject buildRouting() throws JSONException {
        JSONObject routing = new JSONObject();
        // AssetManager: НЕ используем geosite/geoip — требуют dat-файлы в assets
        routing.put("domainStrategy", "AsIs");

        JSONArray rules = new JSONArray();

        // Весь трафик через прокси (без geosite правил)
        JSONObject proxyAll = new JSONObject();
        proxyAll.put("type", "field");
        proxyAll.put("network", "tcp,udp");
        proxyAll.put("outboundTag", "proxy");
        rules.put(proxyAll);

        routing.put("rules", rules);
        return routing;
    }

    /**
     * Конфиг для measureDelay — только SOCKS5 прокси, без TUN.
     * measureDelay сам делает HTTP запрос через этот прокси и меряет RTT.
     * Используется в ServerTester при проверке серверов.
     */
    public static String buildForTest(VlessServer server, int socksPort) {
        // Переиспользуем build() с socksPort, без TUN (tunFd=-1)
        // measureDelay использует встроенный HTTP клиент Go, не наш SOCKS
        return build(server, socksPort, -1);
    }

    /**
     * Генерирует "Мультиплекс-конфиг" для параллельного тестирования списка серверов.
     * Не содержит TUN-интерфейса. Для каждого сервера открывается свой локальный HTTP-порт.
     *
     * @param servers  Список серверов (выживших после TCP Ping)
     * @param basePort Стартовый порт для локальных прокси (например, 10800).
     *                 Если передано 10 серверов, они займут порты 10800-10809.
     * @return JSON строка конфигурации
     */
    public static String buildMultiplexTestConfig(List<VlessServer> servers, int basePort) {
        try {
            JSONObject config = new JSONObject();

            JSONObject log = new JSONObject();
            log.put("loglevel", "warning"); // Меньше логов для ускорения
            config.put("log", log);

            JSONArray inbounds = new JSONArray();
            JSONArray outbounds = new JSONArray();
            JSONArray rules = new JSONArray();

            for (int i = 0; i < servers.size(); i++) {
                VlessServer server = servers.get(i);
                int localPort = basePort + i;
                String inTag = "in-" + i;
                String outTag = "proxy-" + i;

                // 1. Создаем Inbound (Локальный HTTP прокси для этого сервера)
                // Используем HTTP, так как HttpURLConnection в Java работает с ним быстрее и надежнее SOCKS
                JSONObject inbound = new JSONObject();
                inbound.put("tag", inTag);
                inbound.put("port", localPort);
                inbound.put("listen", "127.0.0.1");
                inbound.put("protocol", "http");

                JSONObject inSettings = new JSONObject();
                inSettings.put("timeout", 0);
                inbound.put("settings", inSettings);

                inbounds.put(inbound);

                // 2. Создаем Outbound (Сам удаленный VLESS сервер)
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
                if (server.flow != null && !server.flow.isEmpty()) {
                    user.put("flow", server.flow);
                }
                users.put(user);
                serverConf.put("users", users);

                vnext.put(serverConf);
                outSettings.put("vnext", vnext);
                vlessOut.put("settings", outSettings);

                // Переиспользуем ваш существующий метод для генерации streamSettings
                vlessOut.put("streamSettings", buildStreamSettings(server));

                outbounds.put(vlessOut);

                // 3. Создаем правило Routing (Привязываем Inbound к Outbound)
                JSONObject rule = new JSONObject();
                rule.put("type", "field");

                JSONArray inTags = new JSONArray();
                inTags.put(inTag);
                rule.put("inboundTag", inTags);
                rule.put("outboundTag", outTag);

                rules.put(rule);
            }

            // Добавляем обязательные outbounds для блокировки и прямого трафика
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

            return config.toString();

        } catch (JSONException e) {
            throw new RuntimeException("Ошибка генерации Multiplex-конфига: " + e.getMessage(), e);
        }
    }

}
