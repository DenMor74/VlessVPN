package com.vlessvpn.app.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.preference.PreferenceManager;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.google.gson.Gson;
import com.vlessvpn.app.model.ConfigUrlItem;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.network.ServerTester;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.util.FileLogger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
public class ServerRepository {

    private final ServerDao dao;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final String TAG = "ServerRepository";

    // Ключи SharedPreferences
    public static final String PREF_CONFIG_URLS         = "config_urls";
    public static final String PREF_UPDATE_INTERVAL     = "update_interval_hours";
    public static final String PREF_TOP_COUNT           = "top_server_count";
    public static final String PREF_SCAN_ON_START       = "scan_on_start";
    public static final String PREF_SCAN_INTERVAL       = "scan_interval_minutes";
    public static final String PREF_AUTO_CONNECT_WIFI   = "auto_connect_on_wifi_disconnect";
    public static final String PREF_AUTO_CONNECT_SCAN   = "auto_connect_after_scan";
    public static final String PREF_LAST_WORKING_SERVER = "last_working_server_json";
    public static final String PREF_DISABLE_NIGHT_CHECK = "disable_night_check";
    public static final String PREF_NIGHT_START_HOUR    = "night_start_hour";
    public static final String PREF_NIGHT_END_HOUR      = "night_end_hour";
    public static final String PREF_FORCE_MOBILE_TESTS  = "force_mobile_for_tests";
    public static final String PREF_DEEP_CHECK_ON_CONNECT = "deep_check_on_connect";
    public static final String PREF_SOCKS_PORT = "socks_port";
    public static final int DEFAULT_SOCKS_PORT = 10808;

    // Единая константа для времени обновления (устранён дубль PREF_LAST_UPDATE vs PREF_LAST_UPDATE_TIMESTAMP)
    private static final String PREF_LAST_UPDATE_TS     = "last_update_timestamp";
    private static final String PREF_LAST_SCAN_TS       = "last_scan_timestamp";
    public  static final String PREF_REMOTE_LOG_ENABLED = "remote_log_enabled";
    public  static final String PREF_REMOTE_YANDEX_LOG_ENABLED = "remote_yandex_log_enabled";
    public  static final String PREF_REMOTE_LOG_URL     = "remote_log_url";

    public static final int DEFAULT_TOP_COUNT    = 30;
    public static final int DEFAULT_SCAN_INTERVAL = 30; // минут

    // Добавьте новые ключи SharedPreferences
    public static final String PREF_CONFIG_URLS_JSON = "config_urls_json";
    public static final String PREF_CONFIG_URLS_ENABLED = "config_urls_enabled";

    public static final String DEFAULT_CONFIG_URL =
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt\r\n" +
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile-2.txt\r\n" +
        "https://raw.githubusercontent.com/kort0881/vpn-checker-backend/main/checked/RU_Best/ru_white_part1.txt\r\n" +
        "https://github.com/AvenCores/goida-vpn-configs/raw/refs/heads/main/githubmirror/26.txt\r\n" +
        "https://gbr.mydan.online/configs\r\n" +
        "https://raw.githubusercontent.com/sevcator/5ubscrpt10n/main/protocols/vl.txt?filter=.ru";

    //public static final String DEFAULT_CONFIG_URL =
    // "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt&lang=de-de\r\n" +
    //  "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile-2.txt&lang=de-de";

    public ServerRepository(Context context) {
        dao   = AppDatabase.getInstance(context).serverDao();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }


    // Метод для получения списка URL с состоянием
    public List<ConfigUrlItem> getConfigUrlItems() {
        List<ConfigUrlItem> items = new ArrayList<>();

        // Пробуем загрузить из нового формата (JSON)
        String json = prefs.getString(PREF_CONFIG_URLS_JSON, null);
        if (json != null && !json.isEmpty()) {
            try {
                Type listType = new com.google.gson.reflect.TypeToken<List<ConfigUrlItem>>(){}.getType();
                items = new Gson().fromJson(json, listType);
            } catch (Exception e) {
                // Fallback к старому формату
            }
        }

        // Если пусто или ошибка - конвертируем старый формат
        if (items.isEmpty()) {
            String[] urls = prefs.getString(PREF_CONFIG_URLS, DEFAULT_CONFIG_URL).split("\n");
            for (String url : urls) {
                url = url.trim();
                if (!url.isEmpty()) {
                    items.add(new ConfigUrlItem(url, true));
                }
            }
            // Сохраняем в новом формате
            saveConfigUrlItems(items);
        }

        return items;
    }

    // Метод для сохранения списка URL с состоянием
    public void saveConfigUrlItems(List<ConfigUrlItem> items) {
        String json = new Gson().toJson(items);
        prefs.edit().putString(PREF_CONFIG_URLS_JSON, json).apply();
    }

    // Получить только активные URL (для скачивания)
    public String[] getActiveConfigUrls() {
        List<ConfigUrlItem> items = getConfigUrlItems();
        List<String> active = new ArrayList<>();
        for (ConfigUrlItem item : items) {
            if (item.isEnabled()) {
                active.add(item.getUrl());
            }
        }
        return active.toArray(new String[0]);
    }

    /**
     * Удалить ВСЕ серверы из базы (перед полной загрузкой)
     */

    public void deleteAllServersSync() {
        dao.deleteAllServers();  // ← Используем DAO вместо прямого SQLite
        FileLogger.i(TAG, "База данных очищена — удалено всех серверов");
    }


    // ---------------- YC LOG GROUP ----------------

    public String getRemoteLogGroupId() {
        return "e23jflosv9phmehl5fcs";   // <-- вставь свой logGroupId
    }

    // ---------------- SERVICE ACCOUNT ----------------

    public String getYcServiceAccountId() {
        return "ajepbp52cgkrmcg6tfh4";     // <-- service_account_id из JSON
    }

    // ---------------- KEY ID ----------------

    public String getYcKeyId() {
        return "ajefuqpvvdrm39bu6euj";      // <-- key_id из JSON
    }

    // ---------------- PRIVATE KEY ----------------

    public String getYcPrivateKey() {
        return "-----BEGIN PRIVATE KEY-----\n" +
                "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDmHMVqa3fw5mlG\n" +
                "duEdG8ruSRvIgVvPHB5CLDGBlOU3E7bHLqahZwZtICDX9wHM4QjEEGrLyi75mmq4\n" +
                "hy3czk+gyJFGk6yo1+L5BscvuiYu5hmTVJeo8qLBY3NECynokrrRlwjl4VdM/S7M\n" +
                "N/7yyea+oUDWTfYEFyQMX40Fyp5qiRR8kFbSBfa+7DrC0pJC8joTTxwIXnpE3qBp\n" +
                "ddcW+3s45QYEmtH87fNSTd4CbWp3c3Pfe+4uICSme8a3mkUQ+L5CftvUI4NWAGdV\n" +
                "VXEzEZj5HquLuUD66eEXXxs3vw1zA7SlycC+FC+FIOdP7KcPxEi3iHLOBky6QXL+\n" +
                "wQ9hjaCjAgMBAAECggEAbY83QyLl2VGqv/zr03MfHHK8gqtsbeCCW5k0/PBKbf25\n" +
                "4X3JokEuIxjP6mNVfRmLleYHIv4hfX/S3gamhGHKMdAsswCujTk0fMKIZaXodh3i\n" +
                "AW6eQrc7XH4gLD5wdqYdwpp5hxHSAfrtpBfpD+mnLg4Sk7ZMssfdxvJbb214HVo9\n" +
                "wuqFY1cnrzZ9jTfR6FMYxJ402hd8jUkEFqQCEm9Ec66fKgi4SV3i/bb2/QeCWZiJ\n" +
                "2dhK2vznFvnRYL25yGdFcrKNjj2bZdx4PJPJ8eulFB+JZ45HLrtgs48do9+JnAYj\n" +
                "eEUJCtwoE2YusiwR608tqawRNZlGjr7oW/0V/5F00QKBgQDx6pKEz8CP57w3PbUQ\n" +
                "IOgb3mj9LDFJYTcnE8ZwJSZPvj8fqMJ8SyCRtjoxXiHlScyP+67bRu8dkze9c+Ea\n" +
                "93cGRRwDOGOJ+vVsh2TSQVLvEIhQ7SLgt5v6SPxYMgrX7PKSjjXo9B/Ac6sqS86R\n" +
                "YRb2P0BXb1wMMD/cgkHEg+1lWQKBgQDzgkchv3u7DAFypnAwzVqzQ/Ik7ce/Szq0\n" +
                "uo6FgI+QrhaxwV/s29kcTXzjpuRGEdcSE779THv5QH8+dxCxN0FVD+c+5MJMgLXI\n" +
                "+I2U/q2dducneQ4e2YE3VyuHT6ojbbO9N2XjSs9/moT9S075PjP0oT1tA49hsVFM\n" +
                "4tOI4poqWwKBgQCAKWW2NtotYvezzF1ATi6plQrKFb+GwJoXecKHZycE2CVZAG8I\n" +
                "qkR27bOms9gBQTe+j/fy84F6iaPeGqYHQ1MrXzGYAye40dtzw8cGHNVzEa8mMHtp\n" +
                "0dwwnLoTf29/NWjNe8nTwIGR07W6kq69FlKz4o6Tw8tgKa+rgtaU5c+/AQKBgALv\n" +
                "cRgRDNbGYEYXh4avEwbSLNsRGrVNnNmM3ibx08k0sAVYhWV/iPB0Zqr/2gSWNnd7\n" +
                "UXQQNfZdNqt0F/lq5xi1Zl41t7ngW1Ce3mYLY+BgDI1HQkpQ6OPX4yhwZ2ah7ea8\n" +
                "AjhpMHMjU7MR81PB0jKCtxDXWCUfVBGPMmmWAbG9AoGASIef9QmYtQlSE7+zBZ67\n" +
                "UKqIYzzy2xf1tbbWsHBhDx1mDgMpkCCXEskVdt3FAww34EscVSQKwvJWsE0yNLdP\n" +
                "RdtgLaZju5XpWkJFpZtoeEEylYNjpO/kL8tEG3AUXaIBWUn8R3TB7y8xKMGbAnqr\n" +
                "9CZcb5seVW6d/2zWZa+yyfM=" +
                "-----END PRIVATE KEY-----";
    }

    // ── Чтение серверов ────────────────────────────────────────────────────

    public LiveData<List<VlessServer>> getTopServersLiveData() {
        int limit = getTopCount();
        return Transformations.map(dao.getAllWorkingServers(),
                list -> list != null && list.size() > limit ? list.subList(0, limit) : list);
    }

    public List<VlessServer> getAllServersSync() { return dao.getAllServersSync(); }

    /** Топ-N рабочих серверов для AutoConnect и switchToNextServer */
    /** Все рабочие серверы (без лимита топ-N) — для перебора при переключении */
    public List<VlessServer> getAllWorkingServersSync() {
        List<VlessServer> all = dao.getAllServersSync();
        List<VlessServer> ready = new ArrayList<>();
        for (VlessServer s : all) {
            if (s.pingMs >= 0 && s.trafficOk) ready.add(s);
        }
        if (ready.isEmpty()) ready = all;
        ready.sort((a, b) -> Long.compare(a.pingMs, b.pingMs));
        return ready;
    }

    public List<VlessServer> getTopServersSync() {
        int limit = getTopCount();
        List<VlessServer> all = dao.getAllServersSync();
        List<VlessServer> ready = new ArrayList<>();
        for (VlessServer s : all) {
            if (s.pingMs >= 0 && s.trafficOk) ready.add(s);
        }
        if (ready.isEmpty()) ready = all;
        ready.sort((a, b) -> Long.compare(a.pingMs, b.pingMs));
        return ready.subList(0, Math.min(limit, ready.size()));
    }

    // ── Запись серверов ────────────────────────────────────────────────────

    public void insertAll(List<VlessServer> servers) { dao.insertAll(servers); }

    public void updateServer(VlessServer server)  { dao.update(server); }
    public void updateServerSync(VlessServer s)   { dao.updateServer(s); }

    public void deleteBySourceUrl(String url)     { dao.deleteBySourceUrl(url); }
    public void deleteBySourceUrlSync(String url) { dao.deleteBySourceUrl(url); }

    public void resetAllTestTimes()     { executor.execute(dao::resetAllTestTimes); }
    public void resetAllTestTimesSync() { dao.resetAllTestTimes(); }

    public int getWorkingCount() { return dao.getWorkingCount(); }

    // ── Настройки: URL конфигов ────────────────────────────────────────────

    public String[] getConfigUrls() {
        return prefs.getString(PREF_CONFIG_URLS, DEFAULT_CONFIG_URL).split("\n");
    }

    public void saveConfigUrls(String urls) {
        prefs.edit().putString(PREF_CONFIG_URLS, urls).apply();
    }

    // ── Настройки: интервалы ───────────────────────────────────────────────

    public int getUpdateIntervalHours() { return prefs.getInt(PREF_UPDATE_INTERVAL, 5); }

    public void saveUpdateInterval(int hours) {
        prefs.edit().putInt(PREF_UPDATE_INTERVAL, hours).apply();
    }

    public int getScanIntervalMinutes() {
        return prefs.getInt(PREF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL);
    }

    public void saveScanIntervalMinutes(int minutes) {
        prefs.edit().putInt(PREF_SCAN_INTERVAL, Math.max(10, Math.min(1440, minutes))).apply();
    }

    // ── Настройки: количество серверов ────────────────────────────────────

    public int getTopCount() { return prefs.getInt(PREF_TOP_COUNT, DEFAULT_TOP_COUNT); }

    public void saveTopCount(int count) {
        prefs.edit().putInt(PREF_TOP_COUNT, Math.max(1, Math.min(200, count))).apply();
    }

    // ── Настройки: поведение при запуске ──────────────────────────────────

    public boolean isScanOnStart() { return prefs.getBoolean(PREF_SCAN_ON_START, true); }
    public void saveScanOnStart(boolean v) { prefs.edit().putBoolean(PREF_SCAN_ON_START, v).apply(); }

    // ── Настройки: авто-подключение ───────────────────────────────────────

    public boolean isAutoConnectOnWifiDisconnect() {
        return prefs.getBoolean(PREF_AUTO_CONNECT_WIFI, false);
    }

    public void saveAutoConnectOnWifiDisconnect(boolean v) {
        prefs.edit().putBoolean(PREF_AUTO_CONNECT_WIFI, v).apply();
    }

    public boolean isAutoConnectAfterScan() {
        return prefs.getBoolean(PREF_AUTO_CONNECT_SCAN, false);
    }

    public void saveAutoConnectAfterScan(boolean v) {
        prefs.edit().putBoolean(PREF_AUTO_CONNECT_SCAN, v).apply();
    }

    // ── Последний рабочий сервер ───────────────────────────────────────────

    public void saveLastWorkingServer(VlessServer server) {
        if (server != null)
            prefs.edit().putString(PREF_LAST_WORKING_SERVER, new Gson().toJson(server)).apply();
    }

    public VlessServer getLastWorkingServer() {
        String json = prefs.getString(PREF_LAST_WORKING_SERVER, null);
        if (json == null) return null;
        try { return new Gson().fromJson(json, VlessServer.class); }
        catch (Exception e) { return null; }
    }

    public void clearLastWorkingServer() {
        prefs.edit().remove(PREF_LAST_WORKING_SERVER).apply();
    }

    // ── Временные метки ───────────────────────────────────────────────────

    public long getLastUpdateTimestamp() { return prefs.getLong(PREF_LAST_UPDATE_TS, 0); }

    public void markUpdated() {
        prefs.edit().putLong(PREF_LAST_UPDATE_TS, System.currentTimeMillis()).apply();
    }

    public void resetUpdateTime() {
        prefs.edit().putLong(PREF_LAST_UPDATE_TS, 0).apply();
    }

    public boolean isUpdateNeeded() {
        long interval = getUpdateIntervalHours() * 3_600_000L;
        return (System.currentTimeMillis() - getLastUpdateTimestamp()) > interval;
    }

    public long getLastScanTimestamp() { return prefs.getLong(PREF_LAST_SCAN_TS, 0); }

    public void markScanned() {
        prefs.edit().putLong(PREF_LAST_SCAN_TS, System.currentTimeMillis()).apply();
    }

    public boolean isScanNeeded() {
        long interval = getScanIntervalMinutes() * 60_000L;
        return (System.currentTimeMillis() - getLastScanTimestamp()) > interval;
    }

    // ── Ночной режим ──────────────────────────────────────────────────────

    public boolean isDisableNightCheck() {
        return prefs.getBoolean(PREF_DISABLE_NIGHT_CHECK, false);
    }

    public void saveDisableNightCheck(boolean v) {
        prefs.edit().putBoolean(PREF_DISABLE_NIGHT_CHECK, v).apply();
    }

    public int getNightStartHour() { return prefs.getInt(PREF_NIGHT_START_HOUR, 24); }
    public int getNightEndHour()   { return prefs.getInt(PREF_NIGHT_END_HOUR, 6); }

    public boolean isNightTime() {
        if (!isDisableNightCheck()) return false;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        // Ночь 0:00-6:00
        return hour < getNightEndHour();
    }

    // ── Настройки: принудительные мобильные тесты ─────────────────────────

    public boolean isRemoteLogEnabled() {
        return prefs.getBoolean(PREF_REMOTE_LOG_ENABLED, false);
    }

    public boolean isRemoteLogYandexEnabled() {
        return prefs.getBoolean(PREF_REMOTE_YANDEX_LOG_ENABLED, false);
    }

    public void saveRemoteLogYandexEnabled(boolean v) {
        prefs.edit().putBoolean(PREF_REMOTE_YANDEX_LOG_ENABLED, v).apply();
    }

    public void saveRemoteLogEnabled(boolean v) {
        prefs.edit().putBoolean(PREF_REMOTE_LOG_ENABLED, v).apply();
    }
    public String getRemoteLogUrl() {
        return prefs.getString(PREF_REMOTE_LOG_URL, "");
    }
    public void saveRemoteLogUrl(String url) {
        prefs.edit().putString(PREF_REMOTE_LOG_URL, url.trim()).apply();
    }


    public boolean isDeepCheckOnConnect() {
        return prefs.getBoolean(PREF_DEEP_CHECK_ON_CONNECT, false);
    }

    public void saveDeepCheckOnConnect(boolean v) {
        prefs.edit().putBoolean(PREF_DEEP_CHECK_ON_CONNECT, v).apply();
    }

    public boolean isForceMobileTests() {
        return prefs.getBoolean(PREF_FORCE_MOBILE_TESTS, true);
    }

    public void saveForceMobileTests(boolean v) {
        prefs.edit().putBoolean(PREF_FORCE_MOBILE_TESTS, v).apply();
    }

    // ── Соединения для скачивания подписок ────────────────────────────────

    /**
     * Открывает HTTP соединение для скачивания подписок.
     * Приоритет: VPN(SOCKS5) → WiFi → LTE.
     */
    public static HttpURLConnection openConnectionForSubscription(Context ctx, URL url)
            throws IOException {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (VpnTunnelService.isRunning) {
            int socksPort = new ServerRepository(ctx).getLocalSocksPort();

            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                    new java.net.InetSocketAddress("127.0.0.1", socksPort));

            FileLogger.i(TAG, "Скачивание подписки через SOCKS: " + socksPort);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            return conn;
        }

        if (cm != null) {
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps != null
                        && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return (HttpURLConnection) net.openConnection(url);
                }
            }
        }

        return (HttpURLConnection) url.openConnection();
    }

    public int getLocalSocksPort() {
        try {
            // EditTextPreference в Android сохраняет значения как String!
            String portStr = prefs.getString(PREF_SOCKS_PORT, String.valueOf(DEFAULT_SOCKS_PORT));
            int port = Integer.parseInt(portStr);

            // Защита от ввода системных (до 1024) или невалидных портов
            if (port <= 1024 || port > 65535) return DEFAULT_SOCKS_PORT;
            return port;
        } catch (Exception e) {
            return DEFAULT_SOCKS_PORT;
        }
    }

    public void saveLocalSocksPort(int port) {
        // Обязательно сохраняем как String, иначе при открытии экрана настроек
        // AndroidX выбросит ClassCastException
        prefs.edit().putString(PREF_SOCKS_PORT, String.valueOf(port)).apply();
    }

    public void triggerImmediateScan(Context context, Runnable onComplete) {
        new Thread(() -> {
            try {
                FileLogger.i(TAG, "Внеочередное сканирование серверов...");
                List<VlessServer> all = getAllServersSync();
                if (all.isEmpty()) {
                    FileLogger.w(TAG, "Нет серверов для сканирования");
                    if (onComplete != null) onComplete.run();
                    return;
                }

                // Тестируем параллельно — как ScanWorker
                int threads = Math.min(20, all.size());
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                CountDownLatch latch = new CountDownLatch(all.size());

                for (VlessServer server : all) {
                    pool.submit(() -> {
                        try {
                            // При активном VPN не пытаемся биндить к cellular — передаём null
                            Network bindNet = VpnTunnelService.isRunning ? null :
                                    ServerTester.getCellularNetwork(context);
                            ServerTester.TestResult result = ServerTester.tcpTest(context, server, bindNet);
                            server.pingMs = result.pingMs;
                            server.trafficOk = result.trafficOk;
                            updateServerSync(server);
                        } catch (Exception e) {
                            FileLogger.w(TAG, "Ошибка теста " + server.host + ": " + e.getMessage());
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                // Ждём завершения — максимум 60 секунд
                boolean finished = latch.await(60, TimeUnit.SECONDS);
                pool.shutdownNow();

                FileLogger.i(TAG, "Сканирование завершено (finished=" + finished +
                        ", рабочих: " + getWorkingCount() + ")");
                markScanned();

            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка сканирования: " + e.getMessage());
            } finally {
                if (onComplete != null) onComplete.run();
            }
        }, "emergency-scan-thread").start();
    }

}
