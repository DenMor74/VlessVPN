package com.vlessvpn.app.storage;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.vlessvpn.app.model.VlessServer;

@Database(
        entities = {VlessServer.class},
        version = 2,  // ← Увеличили с 1 до 2
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract ServerDao serverDao();

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "vless_database"
                            )
                            // ════════════════════════════════════════════════════════════════
                            // ← Если нужно сохранить данные — добавьте миграцию
                            // ════════════════════════════════════════════════════════════════
                            // .addMigrations(MIGRATION_1_2)

                            // ════════════════════════════════════════════════════════════════
                            // ← Если можно потерять данные — просто сбросьте БД
                            // ════════════════════════════════════════════════════════════════
                            .fallbackToDestructiveMigration()

                            .build();
                }
            }
        }
        return instance;
    }
}