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
    public static final int DEFAULT_SCAN_INTERVAL = 180; // минут

    // ← НОВОЕ: лимит числа серверов, которые реально пингуются/проверяются за один
    // проход сканирования. Списки из публичных подписок (по умолчанию их 6 в
    // приложении) суммарно легко превышают несколько тысяч записей — тестирование
    // всех разом создаёт сотни параллельных сокетов/потоков и было основной причиной
    // падений на больших списках. Отбор кандидатов идёт через
    // ServerDao.getServersForTestingSync(): сначала избранные (isFavorite DESC),
    // затем давно не тестировавшиеся (lastTestedAt ASC) — так за несколько
    // последовательных сканирований список постепенно проверяется весь целиком,
    // а не залипает на одной случайной тысяче.
    public static final String PREF_MAX_SERVERS_PER_SCAN = "max_servers_per_scan";
    public static final int DEFAULT_MAX_SERVERS_PER_SCAN = 500;

    // Добавьте новые ключи SharedPreferences
    public static final String PREF_CONFIG_URLS_JSON = "config_urls_json";
    public static final String PREF_CONFIG_URLS_ENABLED = "config_urls_enabled";

    public static final String DEFAULT_CONFIG_URL =
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt\r\n" +
        "https://raw.githubusercontent.com/kort0881/vpn-checker-backend/main/checked/RU_Best/ru_white_part1.txt\r\n" +
        "https://github.com/AvenCores/goida-vpn-configs/raw/refs/heads/main/githubmirror/26.txt\r\n" +
        "https://gbr.mydan.online/configs\r\n" +
        "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6.txt\r\n" +
        "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta7.txt";

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

    public void deleteNonFavoritesSync() {
        dao.deleteNonFavorites();
        FileLogger.i(TAG, "База данных очищена — удалены все, кроме избранных");
    }


    // ---------------- YC LOG GROUP ----------------
    //
    // ← КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ БЕЗОПАСНОСТИ: раньше здесь прямо в исходном коде,
    // закоммиченном в публичный GitHub-репозиторий, лежал полный приватный RSA-ключ
    // сервисного аккаунта Yandex Cloud вместе с serviceAccountId/keyId/logGroupId.
    // Любой человек, склонировавший репозиторий, мог аутентифицироваться от имени
    // этого сервисного аккаунта. Раз ключ уже был опубликован — его необходимо
    // немедленно ОТОЗВАТЬ/ПЕРЕВЫПУСТИТЬ в консоли Yandex Cloud (IAM → Сервисные
    // аккаунты → Авторизованные ключи), простого удаления из кода недостаточно,
    // т.к. старые коммиты с ключом остаются в истории git навсегда.
    //
    // Теперь значения читаются из BuildConfig, которые Gradle подставляет из
    // local.properties (файл НЕ коммитится, уже в .gitignore). Смотри
    // local.properties.example — впиши туда СВОЙ НОВЫЙ (перевыпущенный) ключ.
    // Если значения не заданы — функция удалённого логирования в Yandex Cloud
    // просто не будет работать (fallback на пустую строку), приложение при этом
    // не падает.

    public String getRemoteLogGroupId() {
        return com.vlessvpn.app.BuildConfig.YC_LOG_GROUP_ID;
    }

    // ---------------- SERVICE ACCOUNT ----------------

    public String getYcServiceAccountId() {
        return com.vlessvpn.app.BuildConfig.YC_SERVICE_ACCOUNT_ID;
    }

    // ---------------- KEY ID ----------------

    public String getYcKeyId() {
        return com.vlessvpn.app.BuildConfig.YC_KEY_ID;
    }

    // ---------------- PRIVATE KEY ----------------

    public String getYcPrivateKey() {
        return com.vlessvpn.app.BuildConfig.YC_PRIVATE_KEY.replace("\\n", "\n");
    }

    // ── Чтение серверов ────────────────────────────────────────────────────

    public LiveData<List<VlessServer>> getTopServersLiveData() {
        int limit = getTopCount();
        return Transformations.map(dao.getAllWorkingServers(),
                list -> list != null && list.size() > limit ? list.subList(0, limit) : list);
    }

    public List<VlessServer> getAllServersSync() { return dao.getAllServersSync(); }

    /**
     * ← НОВОЕ: список-кандидат для сканирования, ограниченный getMaxServersPerScan().
     * Приоритет отбора (см. ServerDao.getServersForTestingSync): избранные, затем
     * давно не тестировавшиеся. Если реальных серверов меньше лимита — вернутся
     * все, поведение не отличается от getAllServersSync().
     */
    public List<VlessServer> getServersForTestingSync(int limit) {
        return dao.getServersForTestingSync(limit);
    }

    /** Топ-N рабочих серверов для AutoConnect и switchToNextServer */
    /** Все рабочие серверы (без лимита топ-N) — для перебора при переключении */
    public List<VlessServer> getAllWorkingServersSync() {
        return dao.getAllWorkingServersSync();
    }

    public List<VlessServer> getTopServersSync() {
        int limit = getTopCount();
        return dao.getTopNWorkingServersSync(limit);
    }

    // ── Запись серверов ────────────────────────────────────────────────────

    public void insertAll(List<VlessServer> servers) { dao.insertAll(servers); }

    public void updateServer(VlessServer server)  { dao.update(server); }

    public void updateTestResultsSync(VlessServer s) {
        dao.updateTestResults(s.id, s.pingMs, s.trafficOk, s.lastTestedAt, s.tcpPingMs);
    }

    public void updateServerSync(VlessServer s)   {
        VlessServer existing = dao.getServerById(s.id);
        if (existing != null) {
            s.isFavorite = existing.isFavorite;
            dao.update(s);
        } else {
            dao.insert(s);
        }
    }

    public void toggleFavorite(VlessServer server) {
        executor.execute(() -> {
            boolean newVal = !server.isFavorite;
            server.isFavorite = newVal;
            dao.updateFavorite(server.id, newVal);
        });
    }

    public void deleteBySourceUrl(String url)     { dao.deleteBySourceUrl(url); }
    public void deleteBySourceUrlSync(String url) { dao.deleteBySourceUrl(url); }

    public void resetAllTestTimes()     { executor.execute(dao::resetAllTestTimes); }
    public void resetAllTestTimesSync() { dao.resetAllTestTimes(); }

    public int getCount() { return dao.getCount(); }

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

    /** Сколько серверов максимум тестируется за один проход сканирования (см. doPipelineScan). */
    public int getMaxServersPerScan() {
        return prefs.getInt(PREF_MAX_SERVERS_PER_SCAN, DEFAULT_MAX_SERVERS_PER_SCAN);
    }

    public void saveMaxServersPerScan(int count) {
        prefs.edit().putInt(PREF_MAX_SERVERS_PER_SCAN, Math.max(50, count)).apply();
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
}
