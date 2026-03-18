package com.vlessvpn.app.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.preference.PreferenceManager;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.google.gson.Gson;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.util.FileLogger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.vlessvpn.app.service.VpnTunnelService;
public class ServerRepository {

    private final ServerDao dao;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public static final String PREF_CONFIG_URLS      = "config_urls";
    public static final String PREF_UPDATE_INTERVAL  = "update_interval_hours";
    public static final String PREF_LAST_UPDATE      = "last_update_timestamp";
    public static final String PREF_TOP_COUNT        = "top_server_count";
    public static final String PREF_SCAN_ON_START    = "scan_on_start";
    public static final int    DEFAULT_TOP_COUNT     = 10;

    public static final String PREF_FORCE_MOBILE_TESTS = "force_mobile_for_tests";

    // ← НОВЫЕ КОНСТАНТЫ ДЛЯ АВТО-ПОДКЛЮЧЕНИЯ
    public static final String PREF_AUTO_CONNECT_WIFI = "auto_connect_on_wifi_disconnect";
    public static final String PREF_LAST_WORKING_SERVER = "last_working_server_json";

    public static final String DEFAULT_CONFIG_URL =
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile-2.txt  ";

    public static final String PREF_SCAN_INTERVAL = "scan_interval_minutes";  // ← НОВОЕ
    public static final int DEFAULT_SCAN_INTERVAL = 30;  // 30 минут по умолчанию

    public static final String PREF_DISABLE_NIGHT_CHECK = "disable_night_check";
    public static final String PREF_NIGHT_START_HOUR = "night_start_hour";
    public static final String PREF_NIGHT_END_HOUR = "night_end_hour";

    /**
     * Получить интервал сканирования текущего списка (минуты)
     * @return интервал в минутах (10-1440)
     */
    public int getScanIntervalMinutes() {
        return prefs.getInt(PREF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL);
    }



    /**
     * Проверить: нужно ли сканировать текущий список
     */
    public boolean isScanNeeded() {
        long lastScan = prefs.getLong("last_scan_timestamp", 0);
        long intervalMs = getScanIntervalMinutes() * 60 * 1000L;
        return (System.currentTimeMillis() - lastScan) > intervalMs;
    }

    /**
     * Отметить время сканирования
     */
    public void markScanned() {
        prefs.edit().putLong("last_scan_timestamp", System.currentTimeMillis()).apply();
    }

    public ServerRepository(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        this.dao  = db.serverDao();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    // ── Чтение ──────────────────────────────────────────────

    /** Топ-10 рабочих серверов для UI (LiveData → автообновление RecyclerView) */
    public LiveData<List<VlessServer>> getTop10Servers() {
        return dao.getTop10WorkingServers();
    }

    /** LiveData топ-N серверов — лимит берётся из настроек */
    public LiveData<List<VlessServer>> getTopServersLiveData() {
        int limit = getTopCount();
        return Transformations.map(dao.getAllWorkingServers(),
                list -> list != null && list.size() > limit ? list.subList(0, limit) : list);
    }

    /** Синхронный топ-10 — для Worker */
    public List<VlessServer> getTopServers() {
        return dao.getTop10WorkingServersSync();
    }

    /** Синхронный топ-N — для Worker с настраиваемым лимитом */
    public List<VlessServer> getTopNServers() {
        return dao.getTopNWorkingServersSync(getTopCount());
    }

    /** ВСЕ серверы из кэша — для тестирования в Worker */
    public List<VlessServer> getAllServersSync() {
        return dao.getAllServersSync();
    }

    // ── Запись ──────────────────────────────────────────────

    public void insertAll(List<VlessServer> servers) {
        dao.insertAll(servers);
    }

    public void updateServer(VlessServer server) {
        dao.update(server);
    }

    public void deleteServer(VlessServer server) {
        dao.deleteById(server.id);
    }

    public void deleteBySourceUrl(String url) {
        dao.deleteBySourceUrl(url);
    }

    // ── Настройки ───────────────────────────────────────────

    public String[] getConfigUrls() {
        String urls = prefs.getString(PREF_CONFIG_URLS, DEFAULT_CONFIG_URL);
        return urls.split("\n");
    }

    public void saveConfigUrls(String urls) {
        prefs.edit().putString(PREF_CONFIG_URLS, urls).apply();
    }

    public int getUpdateIntervalHours() {
        return prefs.getInt(PREF_UPDATE_INTERVAL, 5);
    }

    public void saveUpdateInterval(int hours) {
        prefs.edit().putInt(PREF_UPDATE_INTERVAL, hours).apply();
    }

    public boolean isUpdateNeeded() {
        long lastUpdate = prefs.getLong(PREF_LAST_UPDATE, 0);
        long intervalMs = getUpdateIntervalHours() * 60 * 60 * 1000L;
        return (System.currentTimeMillis() - lastUpdate) > intervalMs;
    }

    public void markUpdated() {
        prefs.edit().putLong(PREF_LAST_UPDATE, System.currentTimeMillis()).apply();
    }

    public long getLastUpdateTimestamp() {
        return prefs.getLong(PREF_LAST_UPDATE, 0);
    }

    public void resetUpdateTime() {
        prefs.edit().putLong(PREF_LAST_UPDATE, 0).apply();
    }

    public void resetAllTestTimes() {
        executor.execute(dao::resetAllTestTimes);
    }

    public void resetAllTestTimesSync() {
        dao.resetAllTestTimes();
    }

    public int getWorkingCount() {
        return dao.getWorkingCount();
    }

    // ── Количество серверов в топе ──────────────────────────────────────────

    public int getTopCount() {
        return prefs.getInt(PREF_TOP_COUNT, DEFAULT_TOP_COUNT);
    }

    public void saveTopCount(int count) {
        prefs.edit().putInt(PREF_TOP_COUNT, Math.max(1, Math.min(50, count))).apply();
    }

    // ── Поведение при запуске ───────────────────────────────────────────────

    public boolean isScanOnStart() {
        return prefs.getBoolean(PREF_SCAN_ON_START, true);
    }

    public void saveScanOnStart(boolean scan) {
        prefs.edit().putBoolean(PREF_SCAN_ON_START, scan).apply();
    }

    public List<VlessServer> getTopServersSync() {
        int limit = prefs.getInt(PREF_TOP_COUNT, 15); // 10-30 по твоим настройкам

        List<VlessServer> all = dao.getAllServersSync();

        // Оставляем только протестированные и рабочие серверы
        List<VlessServer> ready = new ArrayList<>();
        for (VlessServer s : all) {
            if (s.pingMs >= 0 && s.trafficOk) {   // pingMs >=0 + трафик прошёл
                ready.add(s);
            }
        }

        // Если ничего не протестировано — берём все (чтобы не остаться без списка)
        if (ready.isEmpty()) {
            ready = all;
        }

        // Сортируем по пингу ASC → самые быстрые первые
        ready.sort((a, b) -> Long.compare(a.pingMs, b.pingMs));

        // Возвращаем топ N
        int size = Math.min(limit, ready.size());
        return ready.subList(0, size);
    }

    public boolean isForceMobileTests() {
        return prefs.getBoolean(PREF_FORCE_MOBILE_TESTS, true); // по умолчанию ВКЛ
    }

    public void saveForceMobileTests(boolean enabled) {
        prefs.edit().putBoolean(PREF_FORCE_MOBILE_TESTS, enabled).apply();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // НОВЫЕ МЕТОДЫ ДЛЯ АВТО-ПОДКЛЮЧЕНИЯ ПРИ ПОТЕРЕ WiFi
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Проверить: включён ли авто-режим при потере WiFi
     * @return true если авто-подключение включено
     */
    public boolean isAutoConnectOnWifiDisconnect() {
        return prefs.getBoolean(PREF_AUTO_CONNECT_WIFI, false);
    }

    /**
     * Сохранить настройку авто-подключения при потере WiFi
     * @param enabled true = включить, false = выключить
     */
    public void saveAutoConnectOnWifiDisconnect(boolean enabled) {
        prefs.edit().putBoolean(PREF_AUTO_CONNECT_WIFI, enabled).apply();
        FileLogger.i("ServerRepository", "Авто-подключение сохранено: " + (enabled ? "ВКЛ" : "ВЫКЛ"));
    }

    /**
     * Получить последний рабочий сервер (JSON)
     * @return JSON строка сервера или null
     */
    public String getLastWorkingServerJson() {
        return prefs.getString(PREF_LAST_WORKING_SERVER, null);
    }

    /**
     * Сохранить сервер как последний рабочий
     * @param server сервер для сохранения
     */
    public void saveLastWorkingServer(VlessServer server) {
        if (server != null) {
            String json = new Gson().toJson(server);
            prefs.edit().putString(PREF_LAST_WORKING_SERVER, json).apply();
            //FileLogger.i("ServerRepository", "Сохранён последний рабочий сервер: " + server.host);
        }
    }

    /**
     * Очистить последний рабочий сервер
     */
    public void clearLastWorkingServer() {
        prefs.edit().remove(PREF_LAST_WORKING_SERVER).apply();
        //FileLogger.d("ServerRepository", "Очищен последний рабочий сервер");
    }

    /**
     * Получить последний рабочий сервер как объект
     * @return VlessServer или null
     */
    public VlessServer getLastWorkingServer() {
        String json = getLastWorkingServerJson();
        if (json != null) {
            try {
                return new Gson().fromJson(json, VlessServer.class);
            } catch (Exception e) {
                FileLogger.e("ServerRepository", "Ошибка парсинга последнего сервера: " + e.getMessage());
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // УТИЛИТЫ ДЛЯ СОЕДИНЕНИЙ
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Открывает соединение либо через мобильную сеть (если включено и WiFi подключён),
     * либо обычным способом.
     */
    public static HttpURLConnection openConnectionForTest(Context context, URL url) throws IOException {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean forceMobile = context.getSharedPreferences("vless_prefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_FORCE_MOBILE_TESTS, true);

        if (!forceMobile) {
            return (HttpURLConnection) url.openConnection();
        }

        // WiFi подключён?
        boolean wifiConnected = false;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifiConnected = true;
                break;
            }
        }

        if (!wifiConnected) {
            return (HttpURLConnection) url.openConnection();
        }

        // Ищем мобильную сеть
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return (HttpURLConnection) net.openConnection(url);
            }
        }

        // fallback
        return (HttpURLConnection) url.openConnection();
    }

// ════════════════════════════════════════════════════════════════
// В ServerRepository.java → openConnectionForSubscription()
// ════════════════════════════════════════════════════════════════

    /**
     * Скачивание подписок: приоритет WiFi → VPN-туннель (SOCKS5) → LTE
     */
    public static HttpURLConnection openConnectionForSubscription(Context context, URL url) throws IOException {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: Если VPN активен → используем SOCKS5 прокси
        // ════════════════════════════════════════════════════════════════
        if (VpnTunnelService.isRunning) {
            FileLogger.d("ServerRepository", "VPN активен → скачиваем через SOCKS5 прокси");

            // Создаём прокси для подключения через v2ray (порт 10808)
            java.net.Proxy proxy = new java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    new java.net.InetSocketAddress("127.0.0.1", 10808)
            );

            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(15000);  // ← Увеличенный таймаут для VPN
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 VlessVPN/1.0");

            return conn;
        }

        // 1. Ищем WiFi с реальным интернетом
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {

                FileLogger.d("ServerRepository", "Скачиваем подписку через WiFi");
                return (HttpURLConnection) net.openConnection(url);
            }
        }

        // 2. Нет рабочего WiFi → обычное соединение (через LTE)
        FileLogger.d("ServerRepository", "WiFi недоступен → скачиваем через LTE");
        return (HttpURLConnection) url.openConnection();
    }

    // ════════════════════════════════════════════════════════════════
// НОВЫЕ МЕТОДЫ для раздельного сканирования (добавить в конец класса)
// ════════════════════════════════════════════════════════════════

    /**
     * Получить интервал сканирования текущего списка (минуты)
     * @return интервал в минутах (10-1440)
     */

    /**
     * Сохранить интервал сканирования текущего списка
     * @param minutes интервал в минутах (10-1440)
     */
    public void saveScanIntervalMinutes(int minutes) {
        int clamped = Math.max(10, Math.min(1440, minutes));  // 10мин - 24ч
        prefs.edit().putInt(PREF_SCAN_INTERVAL, clamped).apply();
    }



    /**
     * Обновить сервер синхронно (для Worker)
     */
    public void updateServerSync(VlessServer server) {
        dao.updateServer(server);
    }


    /**
     * Удалить серверы по URL источника синхронно (для Worker)
     */
    public void deleteBySourceUrlSync(String url) {
        dao.deleteBySourceUrl(url);
    }


    /**
     * Получить время последней проверки списка
     */
    public long getLastScanTimestamp() {
        return prefs.getLong("last_scan_timestamp", 0);
    }

    /**
     * Проверить: отключены ли проверки ночью
     */
    public boolean isDisableNightCheck() {
        return prefs.getBoolean(PREF_DISABLE_NIGHT_CHECK, false);
    }

    /**
     * Сохранить настройку отключения ночных проверок
     */
    public void saveDisableNightCheck(boolean disabled) {
        prefs.edit().putBoolean(PREF_DISABLE_NIGHT_CHECK, disabled).apply();
        FileLogger.i("ServerRepository", "Ночные проверки: " + (disabled ? "ОТКЛ" : "ВКЛ"));
    }

    /**
     * Получить час начала ночного режима (по умолчанию 24)
     */
    public int getNightStartHour() {
        return prefs.getInt(PREF_NIGHT_START_HOUR, 24);
    }

    /**
     * Получить час окончания ночного режима (по умолчанию 6)
     */
    public int getNightEndHour() {
        return prefs.getInt(PREF_NIGHT_END_HOUR, 6);
    }

    /**
     * Проверить: сейчас ночное время?
     * @return true если сейчас ночь (24:00-6:00)
     */
    public boolean isNightTime() {
        if (!isDisableNightCheck()) {
            return false;  // Ночной режим отключен
        }

        int startHour = getNightStartHour();  // 24
        int endHour = getNightEndHour();      // 6

        Calendar cal = Calendar.getInstance();
        int currentHour = cal.get(Calendar.HOUR_OF_DAY);

        // Ночь с 24:00 до 6:00
        if (startHour == 24 && endHour == 6) {
            return currentHour >= 0 && currentHour < endHour;
        }

        return false;
    }
}