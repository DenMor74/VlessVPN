package com.vlessvpn.app.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * VlessServer — модель одного VLESS сервера.
 *
 * @Entity — говорит Room, что это таблица в базе данных.
 * Каждое поле класса = колонка в таблице.
 *
 * Пример VLESS URI который мы парсим:
 * vless://UUID@host:port?security=reality&sni=example.com&pbk=KEY#Название
 */
@Entity(tableName = "servers")
public class VlessServer {

    // ========== ПОЛЯ БАЗЫ ДАННЫХ ==========

    @PrimaryKey
    @NonNull
    public String id = "";          // UUID сервера — уникальный ключ

    public String host = "";        // IP-адрес или домен сервера
    public int port = 443;          // Порт (обычно 443)
    public String uuid = "";        // UUID для аутентификации

    // Параметры соединения
    public String security = "none";   // "reality", "tls", "none"
    public String networkType = "tcp"; // "tcp", "xhttp", "ws", "grpc"
    public String sni = "";            // Server Name Indication (для TLS/Reality)
    public String pbk = "";            // Public Key (только для Reality)
    public String fp = "chrome";       // Fingerprint браузера (маскировка)
    public String flow = "";           // Дополнительный поток: "xtls-rprx-vision"
    public String path = "/";          // HTTP path (для xhttp/ws)
    public String sid = "";            // Short ID (для Reality)
    public String host2 = "";          // HTTP host header
    public String remark = "";         // Отображаемое название сервера

    public String rawUri = "";         // Оригинальная строка vless://...

    // ========== РЕЗУЛЬТАТЫ ТЕСТИРОВАНИЯ ==========

    public long pingMs = -1;           // Пинг в миллисекундах (-1 = не тестирован)
    public boolean trafficOk = false;  // Прошёл ли тест трафика
    public long lastTestedAt = 0;      // Когда последний раз тестировали (Unix timestamp)
    public String sourceUrl = "";      // С какого URL был скачан этот сервер

    // ========== СТАТИЧЕСКИЙ ПАРСЕР ==========

    /**
     * Парсит строку VLESS URI в объект VlessServer.
     *
     * @param uri Строка вида vless://UUID@host:port?params#remark
     * @return Объект VlessServer или null если строка невалидна
     */
    public static VlessServer parse(String uri) {
        // Проверяем что строка начинается с vless://
        if (uri == null || !uri.startsWith("vless://")) {
            return null;
        }

        VlessServer s = new VlessServer();
        s.rawUri = uri.trim();

        try {
            // Убираем схему "vless://"
            String body = uri.substring("vless://".length());

            // ---- Шаг 1: Извлекаем REMARK (текст после символа #) ----
            int hashIdx = body.lastIndexOf('#');
            if (hashIdx > 0) {
                // Декодируем URL-encoded текст: %F0%9F%87%AB → 🇫🇮
                s.remark = URLDecoder.decode(
                    body.substring(hashIdx + 1),
                    StandardCharsets.UTF_8.name()
                );
                body = body.substring(0, hashIdx);
            }

            // ---- Шаг 2: Извлекаем UUID (текст до символа @) ----
            int atIdx = body.indexOf('@');
            if (atIdx < 0) return null; // невалидный URI
            s.uuid = body.substring(0, atIdx);
            // id = uuid+host+port — уникален даже если у серверов одинаковый UUID
            // (UUID одного провайдера на разных серверах)
            body = body.substring(atIdx + 1);

            // ---- Шаг 3: Извлекаем HOST и PORT (до символа ?) ----
            int qIdx = body.indexOf('?');
            String hostPort = (qIdx > 0) ? body.substring(0, qIdx) : body;

            // Обрабатываем IPv6 адреса вида [::1]:443
            if (hostPort.startsWith("[")) {
                int bracketEnd = hostPort.indexOf(']');
                s.host = hostPort.substring(1, bracketEnd);
                s.port = Integer.parseInt(hostPort.substring(bracketEnd + 2));
            } else {
                int colonIdx = hostPort.lastIndexOf(':');
                s.host = hostPort.substring(0, colonIdx);
                s.port = Integer.parseInt(hostPort.substring(colonIdx + 1));
            }

            // ---- Шаг 4: Парсим QUERY PARAMS ----
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
            }

            // Уникальный ключ = uuid + host + port
            s.id = s.uuid + "@" + s.host + ":" + s.port;

            // Фильтруем неподдерживаемые транспорты
            // xhttp — новый протокол, отсутствует в libv2ray v5.45.x
            if ("xhttp".equals(s.networkType)) {
                return null; // пропускаем
            }

            // Если remark пустой — используем host:port
            if (s.remark.isEmpty()) {
                s.remark = s.host + ":" + s.port;
            }

        } catch (Exception e) {
            // Если что-то пошло не так при парсинге — возвращаем null
            return null;
        }

        return s;
    }

    /**
     * Разбирает строку query params в Map.
     * Пример: "security=reality&sni=example.com" → {"security":"reality", "sni":"example.com"}
     */
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

    /**
     * Проверяет, нужно ли тестировать этот сервер.
     * Тестируем если: никогда не тестировали ИЛИ прошло больше 1 часа.
     */
    public boolean needsTesting() {
        long oneHour = 60 * 60 * 1000L; // 1 час в миллисекундах
        return lastTestedAt == 0 || (System.currentTimeMillis() - lastTestedAt) > oneHour;
    }

    /**
     * Текстовое представление пинга для UI.
     */
    public String getPingText() {
        if (pingMs < 0) return "—";
        if (pingMs < 100) return pingMs + "ms ⚡";
        if (pingMs < 300) return pingMs + "ms ✓";
        return pingMs + "ms ⚠";
    }

    @Override
    public String toString() {
        return "VlessServer{host=" + host + ", port=" + port + ", ping=" + pingMs + "ms}";
    }
}
