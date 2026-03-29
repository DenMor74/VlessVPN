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
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.util.FileLogger;

import java.io.IOException;
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

    // Единая константа для времени обновления (устранён дубль PREF_LAST_UPDATE vs PREF_LAST_UPDATE_TIMESTAMP)
    private static final String PREF_LAST_UPDATE_TS     = "last_update_timestamp";
    private static final String PREF_LAST_SCAN_TS       = "last_scan_timestamp";
    public  static final String PREF_REMOTE_LOG_ENABLED = "remote_log_enabled";
    public  static final String PREF_REMOTE_LOG_URL     = "remote_log_url";

    public static final int DEFAULT_TOP_COUNT    = 10;
    public static final int DEFAULT_SCAN_INTERVAL = 30; // минут

    public static final String DEFAULT_CONFIG_URL =
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile-2.txt\r\n" +
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt";

    //public static final String DEFAULT_CONFIG_URL =
    // "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt&lang=de-de\r\n" +
    //  "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile-2.txt&lang=de-de";

    public ServerRepository(Context context) {
        dao   = AppDatabase.getInstance(context).serverDao();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
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
        prefs.edit().putInt(PREF_TOP_COUNT, Math.max(1, Math.min(50, count))).apply();
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
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                    new java.net.InetSocketAddress("127.0.0.1", 10808));
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
}
