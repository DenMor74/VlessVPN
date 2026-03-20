package com.vlessvpn.app.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * UpdateChecker — проверка доступности новой версии приложения.
 * Парсит output-metadata.json формат
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    // ════════════════════════════════════════════════════════════════
    // ← Ссылка на ваш output-metadata.json
    // ════════════════════════════════════════════════════════════════
    private static final String VERSION_URL = "http://www.orel.ru/~moroz/Denmor_vpn/output-metadata.json";

    // ════════════════════════════════════════════════════════════════
    // ← Базовый URL для скачивания APK (папка где лежат APK файлы)
    // ════════════════════════════════════════════════════════════════
    private static final String APK_BASE_URL = "http://www.orel.ru/~moroz/Denmor_vpn/";

    private final Context context;
    private final OnUpdateCheckListener listener;

    public interface OnUpdateCheckListener {
        void onUpdateAvailable(String versionName, int versionCode, String downloadUrl, String changelog);
        void onNoUpdate();
        void onError(String error);
    }

    public UpdateChecker(Context context, OnUpdateCheckListener listener) {
        this.context = context;
        this.listener = listener;
    }

    /**
     * Проверить наличие обновления (в фоне)
     */
    public void checkForUpdate() {
        new Thread(this::doCheck).start();
    }

    private void doCheck() {
        try {
            // Получаем текущую версию
            int currentVersionCode = getCurrentVersionCode();
            String currentVersionName = getCurrentVersionName();

            FileLogger.i(TAG, "Текущая версия: " + currentVersionName + " (" + currentVersionCode + ")");

            // Скачиваем output-metadata.json
            String jsonResponse = downloadVersionInfo();
            if (jsonResponse == null) {
                listener.onError("Не удалось получить информацию о версии");
                return;
            }

            FileLogger.i(TAG, "Получен JSON: " + jsonResponse.substring(0, Math.min(200, jsonResponse.length())));

            // ════════════════════════════════════════════════════════════════
            // ← Парсим ВАШ формат (output-metadata.json)
            // ════════════════════════════════════════════════════════════════
            JSONObject json = new JSONObject(jsonResponse);

            // Проверяем что это правильный файл
            int metadataVersion = json.optInt("version", 0);
            if (metadataVersion != 3) {
                FileLogger.w(TAG, "Неверсия версия metadata: " + metadataVersion);
            }

            // Получаем массив elements
            JSONArray elements = json.optJSONArray("elements");
            if (elements == null || elements.length() == 0) {
                listener.onError("Нет элементов в metadata");
                return;
            }

            // Берём первый элемент (основной APK)
            JSONObject firstElement = elements.optJSONObject(0);
            if (firstElement == null) {
                listener.onError("Не удалось получить элемент");
                return;
            }

            // ════════════════════════════════════════════════════════════════
            // ← Извлекаем данные из elements[0]
            // ════════════════════════════════════════════════════════════════
            int latestVersionCode = firstElement.optInt("versionCode", 0);
            String latestVersionName = firstElement.optString("versionName", "");
            String outputFile = firstElement.optString("outputFile", "");

            // Формируем полную ссылку на APK
            String downloadUrl = APK_BASE_URL + outputFile;

            FileLogger.i(TAG, "Последняя версия: " + latestVersionName + " (" + latestVersionCode + ")");
            FileLogger.i(TAG, "Файл: " + outputFile);
            FileLogger.i(TAG, "Ссылка: " + downloadUrl);

            // Сравниваем версии
            if (latestVersionCode > currentVersionCode) {
                FileLogger.i(TAG, "Доступно обновление!");
                listener.onUpdateAvailable(latestVersionName, latestVersionCode, downloadUrl, "");
            } else {
                FileLogger.i(TAG, "Обновлений нет");
                listener.onNoUpdate();
            }

        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка проверки обновлений: " + e.getMessage());
            e.printStackTrace();
            listener.onError("Ошибка: " + e.getMessage());
        }
    }

    /**
     * Получить текущий versionCode
     */
    private int getCurrentVersionCode() {
        try {
            PackageInfo info = getPackageInfo();
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Получить текущую versionName
     */
    private String getCurrentVersionName() {
        try {
            PackageInfo info = getPackageInfo();
            return info.versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /**
     * Получить PackageInfo (совместимо со всеми API)
     */
    private PackageInfo getPackageInfo() throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    PackageManager.PackageInfoFlags.of(0)
            );
        } else {
            return context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    0
            );
        }
    }

    /**
     * Скачать информацию о версии с сервера
     */
    private String downloadVersionInfo() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(VERSION_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "VlessVPN/1.0");

            int responseCode = conn.getResponseCode();
            FileLogger.i(TAG, "HTTP ответ: " + responseCode);

            if (responseCode != HttpURLConnection.HTTP_OK) {
                FileLogger.e(TAG, "HTTP ошибка: " + responseCode);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8")
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            return sb.toString();

        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка загрузки version.json: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Проверить: нужно ли проверять обновление (не чаще чем раз в 24 часа)
     */
    public static boolean shouldCheckUpdate(Context ctx) {
        android.content.SharedPreferences prefs =
                android.preference.PreferenceManager.getDefaultSharedPreferences(ctx);
        long lastCheck = prefs.getLong("last_update_check", 0);
        long now = System.currentTimeMillis();
        long hours24 = 24 * 60 * 60 * 1000L;

        return (now - lastCheck) > hours24;
    }

    /**
     * Сохранить время последней проверки
     */
    public static void markUpdateChecked(Context ctx) {
        android.content.SharedPreferences prefs =
                android.preference.PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply();
    }
}