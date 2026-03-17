package com.vlessvpn.app.storage;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.vlessvpn.app.model.VlessServer;

import java.util.List;

@Dao
public interface ServerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(VlessServer server);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<VlessServer> servers);

    @Update
    void update(VlessServer server);

    @Query("DELETE FROM servers WHERE id = :id")
    void deleteById(String id);

    @Update
    void updateServer(VlessServer server);


    @Query("SELECT * FROM servers")
    List<VlessServer> getAllServers();

    /**
     * Топ-10 рабочих серверов для отображения в UI (LiveData → автообновление)
     */
    @Query("SELECT * FROM servers WHERE trafficOk = 1 ORDER BY pingMs ASC LIMIT 10")
    LiveData<List<VlessServer>> getTop10WorkingServers();

    @Query("SELECT * FROM servers WHERE trafficOk = 1 ORDER BY pingMs ASC LIMIT 10")
    List<VlessServer> getTop10WorkingServersSync();

    /** Топ-N рабочих серверов для Worker (с настраиваемым лимитом) */
    @Query("SELECT * FROM servers WHERE trafficOk = 1 ORDER BY pingMs ASC LIMIT :limit")
    List<VlessServer> getTopNWorkingServersSync(int limit);

    /** Все рабочие серверы (без лимита) — для LiveData с внешней обрезкой */
    @Query("SELECT * FROM servers WHERE trafficOk = 1 ORDER BY pingMs ASC")
    LiveData<List<VlessServer>> getAllWorkingServers();

    /**
     * ВСЕ серверы для тестирования — храним весь кэш, не удаляем!
     * Это позволяет при следующем тесте проверить все серверы заново,
     * даже если предыдущий тест показал их нерабочими.
     */
    @Query("SELECT * FROM servers")
    List<VlessServer> getAllServersSync();

    @Query("SELECT COUNT(*) FROM servers")
    int getCount();

    /**
     * Сбросить флаги теста → все серверы будут протестированы заново.
     * НЕ удаляем серверы — только сбрасываем результаты теста.
     */
    @Query("UPDATE servers SET lastTestedAt = 0, trafficOk = 0, pingMs = -1")
    void resetAllTestTimes();

    /**
     * Удалить все серверы с указанного URL (перед обновлением списка с сервера).
     */
    @Query("DELETE FROM servers WHERE sourceUrl = :url")
    void deleteBySourceUrl(String url);

    /**
     * Количество рабочих серверов (для StatusBus сообщений).
     */
    @Query("SELECT COUNT(*) FROM servers WHERE trafficOk = 1")
    int getWorkingCount();
}
