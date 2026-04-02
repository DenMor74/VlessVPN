package com.vlessvpn.app.network;

import android.content.Context;
import android.util.Log;

import com.vlessvpn.app.model.ConfigUrlItem;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;


/**
 * ConfigDownloader — загружает и парсит списки VLESS серверов.
 * Версия 2.2: поддержка ConfigUrlItem + фильтрация по SNI + отладка
 */
public class ConfigDownloader {

    private static final String TAG = "ConfigDownloader";
    private static final int TIMEOUT_MS = 8_000;

    // Флаг для отмены загрузки
    private AtomicBoolean cancelled = new AtomicBoolean(false);

    // Регулярка для поиска sni=...
    private static final Pattern SNI_PATTERN = Pattern.compile("sni=([^&#]+)", Pattern.CASE_INSENSITIVE);


    // ════════════════════════════════════════════════════════════════
    // ← Интерфейс для callbacks (для UI прогресса)
    // ════════════════════════════════════════════════════════════════
    public interface DownloadCallback {
        void onSuccess(int serverCount);
        void onError(String error);
        void onProgress(int progress, int total, String currentUrl);
    }


    // ════════════════════════════════════════════════════════════════
    // ← ПУБЛИЧНЫЙ МЕТОД для запуска загрузки
    // ════════════════════════════════════════════════════════════════

    /**
     * Асинхронная загрузка всех АКТИВНЫХ URL с прогрессом
     */
    public void downloadAllAsync(final Context context,
                                 final List<ConfigUrlItem> urlItems,
                                 final DownloadCallback callback) {

        cancelled.set(false);

        new Thread(() -> {
            List<VlessServer> allServers = new ArrayList<>();

            // ═══════════════════════════════════════════════════════════
            // ← Фильтруем только активные URL (с галочкой)
            // ═══════════════════════════════════════════════════════════
            List<ConfigUrlItem> activeItems = new ArrayList<>();
            int disabledCount = 0;

            FileLogger.i(TAG, "════════════════════════════════════════");
            FileLogger.i(TAG, "НАЧАЛО ЗАГРУЗКИ — всего URL: " + (urlItems != null ? urlItems.size() : 0));

            if (urlItems != null) {
                for (ConfigUrlItem item : urlItems) {
                    boolean isEnabled = item.isEnabled();
                    String url = item.getUrl();
                    boolean isValidUrl = url != null && !url.trim().isEmpty();

                    if (isEnabled && isValidUrl) {
                        activeItems.add(item);
                        FileLogger.i(TAG, "  [✓] АКТИВЕН: " + url);
                    } else {
                        disabledCount++;
                        String reason = !isEnabled ? "ВЫКЛЮЧЕН" : "ПУСТОЙ URL";
                        FileLogger.i(TAG, "  [✗] ПРОПУЩЕН (" + reason + "): " + (url != null ? url : "null"));
                    }
                }
            }

            int totalUrls = activeItems.size();
            FileLogger.i(TAG, "ИТОГО: активных=" + totalUrls + ", отключено=" + disabledCount);
            FileLogger.i(TAG, "════════════════════════════════════════");

            if (totalUrls == 0) {
                if (callback != null) {
                    callback.onError("Нет активных URL для загрузки");
                }
                return;
            }

            for (int i = 0; i < activeItems.size(); i++) {
                if (cancelled.get()) {
                    if (callback != null) {
                        callback.onError("Загрузка отменена");
                    }
                    return;
                }

                ConfigUrlItem item = activeItems.get(i);
                String url = item.getUrl().trim();

                // ← ПОВТОРНАЯ ПРОВЕРКА перед загрузкой
                if (!item.isEnabled()) {
                    FileLogger.w(TAG, "ПРОПУСК: URL был отключён во время загрузки: " + url);
                    continue;
                }

                if (callback != null) {
                    callback.onProgress(i + 1, totalUrls, url);
                }

                try {
                    // Проверяем есть ли фильтр в URL (параметр ?filter=)
                    String filterSNI = extractFilterParam(url);
                    String cleanUrl = filterSNI != null ? url.substring(0, url.indexOf("?")) : url;

                    FileLogger.i(TAG, "Загрузка [" + (i + 1) + "/" + totalUrls + "]: " + cleanUrl +
                            (filterSNI != null ? " (фильтр: " + filterSNI + ")" : ""));

                    List<VlessServer> servers = download(context, cleanUrl, url, filterSNI);
                    if (servers != null && !servers.isEmpty()) {
                        allServers.addAll(servers);
                        FileLogger.i(TAG, "  → Получено " + servers.size() + " серверов");
                    }
                } catch (Exception e) {
                    FileLogger.w(TAG, "Не удалось загрузить " + url + ": " + e.getMessage());
                }
            }

            if (cancelled.get()) return;

            if (allServers.isEmpty()) {
                if (callback != null) {
                    callback.onError("Не удалось загрузить ни одного сервера");
                }
                return;
            }

            // Сохраняем серверы в БД
            ServerRepository repo = new ServerRepository(context);
            for (VlessServer server : allServers) {
                repo.updateServerSync(server);
            }

            FileLogger.i(TAG, "════════════════════════════════════════");
            FileLogger.i(TAG, "ЗАГРУЗКА ЗАВЕРШЕНА — сохранено: " + allServers.size() + " серверов");
            FileLogger.i(TAG, "════════════════════════════════════════");

            if (callback != null && !cancelled.get()) {
                callback.onSuccess(allServers.size());
            }

        }).start();
    }

    /**
     * Извлекает параметр фильтра из URL (например ?filter=.ru)
     */
    private String extractFilterParam(String url) {
        if (url != null && url.contains("?filter=")) {
            String[] parts = url.split("\\?filter=");
            if (parts.length > 1) {
                return parts[1].split("&")[0].trim();
            }
        }
        return null;
    }

    /**
     * Устаревший метод для совместимости (использует String[])
     */
    @Deprecated
    public void downloadAllAsync(final Context context,
                                 final String[] urls,
                                 final DownloadCallback callback) {
        List<ConfigUrlItem> items = new ArrayList<>();
        for (String url : urls) {
            if (url != null && !url.trim().isEmpty()) {
                items.add(new ConfigUrlItem(url.trim(), true));
            }
        }
        downloadAllAsync(context, items, callback);
    }


    /**
     * Отмена текущей загрузки
     */
    public void cancel() {
        cancelled.set(true);
        FileLogger.i(TAG, "Загрузка отменена пользователем");
    }


    /**
     * Скачивает список серверов с указанного URL с опциональной фильтрацией по SNI.
     */
    public List<VlessServer> download(Context context, String url, String sourceUrl, String sniFilter) {
        List<VlessServer> servers = new ArrayList<>();
        HttpURLConnection connection = null;
        BufferedReader reader = null;

        try {
            FileLogger.i(TAG, "Загружаем список серверов: " + url +
                    (sniFilter != null ? " (фильтр SNI: " + sniFilter + ")" : ""));

            URL targetUrl = new URL(url);

            // Проверка на null
            connection = ServerRepository.openConnectionForSubscription(context, targetUrl);
            if (connection == null) {
                connection = (HttpURLConnection) targetUrl.openConnection();
            }

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 VlessVPN/1.0");
            connection.setRequestProperty("Accept", "*/*");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP ошибка: " + responseCode + " для URL: " + url);
                return servers;
            }

            // Проверка InputStream
            InputStream inputStream = connection.getInputStream();
            if (inputStream == null) {
                Log.e(TAG, "Пустой InputStream для URL: " + url);
                return servers;
            }

            reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

            String line;
            int lineNum = 0;
            int parsedCount = 0;
            int filteredCount = 0;
            int errorCount = 0;

            while ((line = reader.readLine()) != null) {
                if (cancelled.get()) break;

                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("vless://")) {
                    // Применяем фильтр SNI если указан
                    if (sniFilter != null && !sniFilter.isEmpty()) {
                        if (!hasMatchingSNI(line, sniFilter)) {
                            filteredCount++;
                            continue;
                        }
                    }

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

            FileLogger.i(TAG, "Загружено " + parsedCount + " серверов, отфильтровано: " + filteredCount +
                    ", ошибок парсинга: " + errorCount);

        } catch (IOException e) {
            FileLogger.e(TAG, "Ошибка загрузки " + url + ": " + e.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
        return servers;
    }

    /**
     * Проверяет наличие SNI с указанным суффиксом в строке vless://
     */
    private boolean hasMatchingSNI(String vlessLine, String sniSuffix) {
        if (vlessLine == null || sniSuffix == null) return false;

        Matcher matcher = SNI_PATTERN.matcher(vlessLine);
        while (matcher.find()) {
            String sniValue = matcher.group(1).toLowerCase();
            if (sniValue.contains(sniSuffix.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Перегрузка для обратной совместимости (без фильтра)
     */
    public List<VlessServer> download(Context context, String url, String sourceUrl) {
        return download(context, url, sourceUrl, null);
    }


    /**
     * Загружает серверы с нескольких URL (синхронная версия).
     */
    @Deprecated
    public List<VlessServer> downloadAll(Context context, String[] urls) {
        return downloadAll(context, urls, null);
    }

    /**
     * Синхронная загрузка с фильтром SNI
     */
    @Deprecated
    public List<VlessServer> downloadAll(Context context, String[] urls, String sniFilter) {
        List<VlessServer> allServers = new ArrayList<>();

        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) continue;
            List<VlessServer> servers = download(context, url.trim(), url.trim(), sniFilter);
            allServers.addAll(servers);
            Log.i(TAG, "С " + url + " получено " + servers.size() + " серверов");
        }
        FileLogger.i(TAG, "Итого загружено серверов: " + allServers.size());
        return allServers;
    }


    // ════════════════════════════════════════════════════════════════
    // ← Специальный метод для sevcator с .ru фильтром (если нужно)
    // ════════════════════════════════════════════════════════════════

    /**
     * Скачивает sevcator vl.txt с фильтром только sni=*.ru
     */
    public List<VlessServer> downloadSevcatorRuFiltered(Context context, String sourceUrlForDB) {
        return download(context,
                "https://raw.githubusercontent.com/sevcator/5ubscrpt10n/main/protocols/vl.txt",
                sourceUrlForDB,
                ".ru");
    }
}