package com.vlessvpn.app.storage;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.vlessvpn.app.model.VlessServer;

/**
 * AppDatabase — главная база данных приложения.
 *
 * @Database — аннотация Room. Указываем:
 *   - entities: какие таблицы есть в БД
 *   - version: версия схемы (при изменении структуры нужно увеличивать)
 *
 * Singleton паттерн — только один экземпляр БД на всё приложение.
 * Это важно для производительности и корректности данных.
 */
@Database(entities = {VlessServer.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    // Единственный экземпляр БД (volatile — видим из разных потоков)
    private static volatile AppDatabase INSTANCE;

    /**
     * Абстрактный метод — Room сам создаёт реализацию.
     * Через него получаем доступ к операциям с серверами.
     */
    public abstract ServerDao serverDao();

    /**
     * Получить экземпляр базы данных.
     * Если БД ещё не создана — создаём. Иначе возвращаем существующую.
     *
     * synchronized — потокобезопасность (важно для фоновых задач)
     */
    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                // Двойная проверка на случай гонки потоков
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "vless_vpn_database"  // имя файла БД
                        )
                        // fallbackToDestructiveMigration — при изменении версии
                        // просто пересоздаём БД (для простоты, в продакшене лучше писать миграции)
                        .fallbackToDestructiveMigration()
                        .build();
                }
            }
        }
        return INSTANCE;
    }
}
