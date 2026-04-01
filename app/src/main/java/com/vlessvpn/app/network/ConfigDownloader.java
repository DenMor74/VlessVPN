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
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.nio.charset.StandardCharsets;


/**
 * ConfigDownloader — загружает и парсит списки VLESS серверов.
 */
public class ConfigDownloader {

    private static final String TAG = "ConfigDownloader";
    private static final int TIMEOUT_MS = 8_000;

    // ════════════════════════════════════════════════════════════════
    // ← Интерфейс для callbacks (для UI прогресса)
    // ════════════════════════════════════════════════════════════════
    public interface DownloadCallback {
        void onSuccess(int serverCount);
        void onError(String error);
        void onProgress(int progress, int total);
    }


    /**
     * Асинхронная загрузка всех URL с прогрессом
     */
    private static void downloadAllAsync(final Context context, final String[] urls, final DownloadCallback callback) {
        new Thread(() -> {
            List<VlessServer> allServers = new ArrayList<>();
            int totalUrls = urls.length;
            ConfigDownloader downloader = new ConfigDownloader();

            for (int i = 0; i < urls.length; i++) {
                String url = urls[i];
                if (url == null || url.trim().isEmpty()) continue;

                if (callback != null) {
                    callback.onProgress(i + 1, totalUrls);
                }

                try {
                    List<VlessServer> servers = downloader.download(context, url.trim(), url.trim());
                    if (servers != null && !servers.isEmpty()) {
                        allServers.addAll(servers);
                        FileLogger.i(TAG, "С " + url + " получено " + servers.size() + " серверов");
                    }
                } catch (Exception e) {
                    FileLogger.w(TAG, "Не удалось загрузить " + url + ": " + e.getMessage());
                }
            }

            if (allServers.isEmpty()) {
                if (callback != null) {
                    callback.onError("Не удалось загрузить ни одного сервера");
                }
                return;
            }

            // ════════════════════════════════════════════════════════════════
            // ← Сохраняем серверы в БД (в фоне, не в onSuccess!)
            // ════════════════════════════════════════════════════════════════
            ServerRepository repo = new ServerRepository(context);
            for (VlessServer server : allServers) {
                repo.updateServerSync(server);
            }

            FileLogger.i(TAG, "Итого загружено и сохранено: " + allServers.size() + " серверов");

            if (callback != null) {
                callback.onSuccess(allServers.size());
            }

        }).start();
    }

    /**
     * Скачивает список серверов с указанного URL.
     */
    public List<VlessServer> download(Context context, String url, String sourceUrl) {
        List<VlessServer> servers = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            FileLogger.i(TAG, "Загружаем список серверов: " + url);

            URL targetUrl = new URL(url);
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
                        FileLogger.w(TAG, "Не удалось распарсить строку " + lineNum + ": " +
                                line.substring(0, Math.min(50, line.length())));
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
     * Загружает серверы с нескольких URL (синхронная версия).
     */
    public List<VlessServer> downloadAll(Context context, String[] urls) {
        List<VlessServer> allServers = new ArrayList<>();
        ConfigDownloader downloader = new ConfigDownloader();

        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) continue;
            List<VlessServer> servers = downloader.download(context, url.trim(), url.trim());
            allServers.addAll(servers);
            Log.i(TAG, "С " + url + " получено " + servers.size() + " серверов");
        }
        FileLogger.i(TAG, "Итого загружено серверов: " + allServers.size());
        return allServers;
    }

    // ===================================================================
    // ← ДОПОЛНЕНИЕ: sevcator vl.txt + фильтр только sni=*.ru
    // ===================================================================

    /**
     * Скачивает https://raw.githubusercontent.com/sevcator/.../vl.txt
     * и оставляет ТОЛЬКО строки, где sni содержит ".ru"
     * Возвращает уже распарсенные VlessServer (готовые к вставке в БД)
     */
    public List<VlessServer> downloadSevcatorRuFiltered(Context context, String sourceUrlForDB) {
        String urlString = "https://raw.githubusercontent.com/sevcator/5ubscrpt10n/main/protocols/vl.txt";
        List<VlessServer> servers = new ArrayList<>();

        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                FileLogger.w(TAG, "Sevcator RU: HTTP " + connection.getResponseCode());
                return servers;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));

            // Регулярка для поиска sni=...
            Pattern pattern = Pattern.compile("sni=([^&#]+)");

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("vless://")) continue;

                // Проверяем наличие sni=*.ru
                Matcher matcher = pattern.matcher(line);
                boolean hasRuSNI = false;
                while (matcher.find()) {
                    String sniValue = matcher.group(1).toLowerCase();
                    if (sniValue.contains(".ru")) {
                        hasRuSNI = true;
                        break;
                    }
                }

                if (hasRuSNI) {
                    VlessServer server = VlessServer.parse(line);
                    if (server != null) {
                        server.sourceUrl = sourceUrlForDB;   // чтобы можно было удалять по этому URL
                        servers.add(server);
                    }
                }
            }

            reader.close();
            connection.disconnect();

            FileLogger.i(TAG, "Sevcator RU: отфильтровано " + servers.size() + " серверов с .ru SNI");

        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка скачивания/фильтра sevcator RU", e);
        }

        return servers;
    }
}