package com.vlessvpn.app.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.vlessvpn.app.model.VlessServer;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    public static final String DEFAULT_CONFIG_URL =
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile-2.txt";

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
}
