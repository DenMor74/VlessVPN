package com.vlessvpn.app.network;

import android.util.Log;

import com.vlessvpn.app.model.VlessServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * ConfigDownloader — загружает и парсит списки VLESS серверов.
 *
 * Формат файла:
 * # Это комментарий (игнорируем)
 * # profile-title: Название
 * vless://UUID@host:port?params#Название
 * vless://UUID@host:port?params#Название
 * ...
 */
public class ConfigDownloader {

    private static final String TAG = "ConfigDownloader";
    private static final int TIMEOUT_MS = 8_000; // 8 секунд — если нет ответа, используем кэш

    /**
     * Скачивает список серверов с указанного URL.
     *
     * @param url  URL файла со списком VLESS серверов
     * @param sourceUrl  URL источника (для сохранения в БД)
     * @return Список распарсенных серверов (пустой список если ошибка)
     */
    public List<VlessServer> download(String url, String sourceUrl) {
        List<VlessServer> servers = new ArrayList<>();

        HttpURLConnection connection = null;
        try {
            Log.i(TAG, "Загружаем список серверов: " + url);

            // Устанавливаем соединение
            URL targetUrl = new URL(url);
            connection = (HttpURLConnection) targetUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            // User-Agent чтобы сервер не блокировал нас
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 VlessVPN/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP ошибка: " + responseCode + " для URL: " + url);
                return servers;
            }

            // Читаем тело ответа построчно
            InputStream inputStream = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            String line;
            int lineNum = 0;
            int parsedCount = 0;
            int errorCount = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();

                // Пропускаем пустые строки и комментарии (начинаются с #)
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Парсим только VLESS строки (другие протоколы игнорируем)
                if (line.startsWith("vless://")) {
                    VlessServer server = VlessServer.parse(line);
                    if (server != null) {
                        server.sourceUrl = sourceUrl; // запоминаем откуда скачали
                        servers.add(server);
                        parsedCount++;
                    } else {
                        Log.w(TAG, "Не удалось распарсить строку " + lineNum + ": " + line.substring(0, Math.min(50, line.length())));
                        errorCount++;
                    }
                }
                // vmess://, trojan://, ss:// — другие протоколы, пропускаем
            }

            reader.close();
            Log.i(TAG, "Загружено " + parsedCount + " серверов, ошибок парсинга: " + errorCount);

        } catch (IOException e) {
            Log.e(TAG, "Ошибка загрузки " + url + ": " + e.getMessage());
        } finally {
            // Всегда закрываем соединение
            if (connection != null) {
                connection.disconnect();
            }
        }

        return servers;
    }

    /**
     * Загружает серверы с нескольких URL (несколько источников).
     *
     * @param urls  Массив URL
     * @return Объединённый список серверов из всех источников
     */
    public List<VlessServer> downloadAll(String[] urls) {
        List<VlessServer> allServers = new ArrayList<>();

        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) continue;

            List<VlessServer> servers = download(url.trim(), url.trim());
            allServers.addAll(servers);
            Log.i(TAG, "С " + url + " получено " + servers.size() + " серверов");
        }

        Log.i(TAG, "Итого загружено серверов: " + allServers.size());
        return allServers;
    }
}
