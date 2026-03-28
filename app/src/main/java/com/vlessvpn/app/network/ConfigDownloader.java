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
 */
public class ConfigDownloader {

    private static final String TAG = "ConfigDownloader";
    private static final int TIMEOUT_MS = 8_000;

    // ════════════════════════════════════════════════════════════════
    // ← ОСНОВНЫЕ СПИСКИ (существующие)
    // ════════════════════════════════════════════════════════════════
    public static final String[] MAIN_CONFIG_URLS = {
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile-2.txt",
    };

    // ════════════════════════════════════════════════════════════════
    // ← РЕЗЕРВНЫЙ БЕЛЫЙ СПИСОК (для критических ситуаций)
    // ════════════════════════════════════════════════════════════════
    public static final String[] BACKUP_WHITELIST_URLS = {
            "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt&lang=de-de",
            "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile-2.txt&lang=de-de"
    };

    // ════════════════════════════════════════════════════════════════
    // ← Интерфейс для callbacks (для UI прогресса)
    // ════════════════════════════════════════════════════════════════
    public interface DownloadCallback {
        void onSuccess(int serverCount);
        void onError(String error);
        void onProgress(int progress, int total);
    }

    /**
     * Скачать основные списки (существующий метод)
     */
    //public static void downloadMainConfigs(Context context, DownloadCallback callback) {
    //    downloadAllAsync(context, MAIN_CONFIG_URLS, callback);
    //}

    // ════════════════════════════════════════════════════════════════
    // ← НОВЫЙ МЕТОД: Скачать резервный белый список
    // ════════════════════════════════════════════════════════════════
/*    public static void downloadBackupWhitelist(Context context, DownloadCallback callback) {
        FileLogger.i(TAG, "═══════════════════════════════════════");
        FileLogger.i(TAG, "Скачивание резервного белого списка...");
        FileLogger.i(TAG, "═══════════════════════════════════════");

        downloadAllAsync(context, BACKUP_WHITELIST_URLS, callback);
    }*/

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
}