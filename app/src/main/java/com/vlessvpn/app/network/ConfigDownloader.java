package com.vlessvpn.app.network;

import android.content.Context;
import android.util.Log;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

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
 * ...
 */
public class ConfigDownloader {

    private static final String TAG = "ConfigDownloader";
    private static final int TIMEOUT_MS = 8_000; // 8 секунд

    /**
     * Скачивает список серверов с указанного URL.
     *
     * @param context   нужен для выбора сети (WiFi приоритет или туннель)
     * @param url       URL файла со списком
     * @param sourceUrl для сохранения в БД
     */
    public List<VlessServer> download(Context context, String url, String sourceUrl) {
        List<VlessServer> servers = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            FileLogger.i(TAG, "Загружаем список серверов: " + url);

            URL targetUrl = new URL(url);
            // ← ГЛАВНОЕ ИСПРАВЛЕНИЕ: умное подключение (WiFi или через туннель)
            connection = ServerRepository.openConnectionForSubscription(context, targetUrl);

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 VlessVPN/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP ошибка: " + responseCode + " для URL: " + url);
                return servers;
            }

            InputStream inputStream = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            int lineNum = 0;
            int parsedCount = 0;
            int errorCount = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("vless://")) {
                    VlessServer server = VlessServer.parse(line);
                    if (server != null) {
                        server.sourceUrl = sourceUrl;
                        servers.add(server);
                        parsedCount++;
                    } else {
                        FileLogger.w(TAG, "Не удалось распарсить строку " + lineNum + ": " + line.substring(0, Math.min(50, line.length())));
                        errorCount++;
                    }
                }
            }
            reader.close();
            FileLogger.i(TAG, "Загружено " + parsedCount + " серверов, ошибок парсинга: " + errorCount);
        } catch (IOException e) {
            FileLogger.e(TAG, "Ошибка загрузки " + url + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return servers;
    }

    /**
     * Загружает серверы с нескольких URL.
     *
     * @param context нужен для выбора сети
     * @param urls    массив ссылок
     */
    public List<VlessServer> downloadAll(Context context, String[] urls) {
        List<VlessServer> allServers = new ArrayList<>();
        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) continue;
            List<VlessServer> servers = download(context, url.trim(), url.trim());
            allServers.addAll(servers);
            Log.i(TAG, "С " + url + " получено " + servers.size() + " серверов");
        }
        FileLogger.i(TAG, "Итого загружено серверов: " + allServers.size());
        return allServers;
    }
}