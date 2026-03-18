package com.vlessvpn.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;

import com.vlessvpn.app.model.AppInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AppBlacklistManager — управление чёрным списком приложений.
 */
public class AppBlacklistManager {

    private static final String PREF_BLACKLIST = "app_blacklist";
    private static final String TAG = "AppBlacklistManager";

    private final SharedPreferences prefs;
    private final PackageManager pm;

    public AppBlacklistManager(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        this.pm = context.getPackageManager();
    }

    /**
     * Получить все установленные приложения (пользовательские + обновлённые системные).
     */
    public List<AppInfo> getAllInstalledApps(Context context) {
        List<AppInfo> apps = new ArrayList<>();
        Set<String> blacklist = getBlacklist();

        // ════════════════════════════════════════════════════════════════
        // Получаем ВСЕ приложения
        // ════════════════════════════════════════════════════════════════
        List<ApplicationInfo> installedApps = pm.getInstalledApplications(
                PackageManager.GET_META_DATA
        );

        for (ApplicationInfo appInfo : installedApps) {
            String packageName = appInfo.packageName;

            // ════════════════════════════════════════════════════════════════
            // ← УМНАЯ ФИЛЬТРАЦИЯ:
            // 1. Пропускаем само приложение VlessVPN
            // 2. Пропускаем чистые системные приложения (которые нельзя удалить)
            // 3. Показываем пользовательские и обновлённые системные
            // ════════════════════════════════════════════════════════════════

            // Пропускаем VlessVPN
            if (packageName.equals(context.getPackageName())) {
                continue;
            }

            // Пропускаем системные приложения которые НЕ были обновлены
            // (те которые нельзя удалить — они не нужны в чёрном списке)
            boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystem = (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

            // Показываем если:
            // 1. НЕ системное ИЛИ
            // 2. Системное но обновлённое (пользователь может удалить)
            if (isSystem && !isUpdatedSystem) {
                continue;  // Пропускаем чистые системные
            }

            // Пропускаем приложения без имени (скрытые/служебные)
            String appName = appInfo.loadLabel(pm).toString();
            if (appName == null || appName.trim().isEmpty()) {
                continue;
            }

            Drawable icon = appInfo.loadIcon(pm);
            boolean isSelected = blacklist.contains(packageName);

            apps.add(new AppInfo(packageName, appName, icon, isSelected));
        }

// ════════════════════════════════════════════════════════════════
// В методе getAllInstalledApps() — изменить сортировку
// ════════════════════════════════════════════════════════════════

// Сортировка:
// 1. Сначала выбранные (в чёрном списке)
// 2. Потом пользовательские по алфавиту
// 3. В конце системные по алфавиту
        apps.sort((a, b) -> {
            // ← Сначала выбранные приложения
            if (a.isSelected != b.isSelected) {
                return a.isSelected ? -1 : 1;  // Выбранные первыми
            }

            // ← Потом пользовательские перед системными
            boolean aIsSystem = isSystemApp(a.packageName, pm);
            boolean bIsSystem = isSystemApp(b.packageName, pm);

            if (aIsSystem != bIsSystem) {
                return aIsSystem ? 1 : -1;  // Пользовательские первыми
            }

            // ← Внутри групп сортировка по имени
            return a.appName.compareToIgnoreCase(b.appName);
        });

        FileLogger.i(TAG, "Найдено приложений: " + apps.size() + " (чёрный список: " + blacklist.size() + ")");

        return apps;
    }

    /**
     * Проверить является ли приложение системным по package name.
     */
    private boolean isSystemApp(String packageName, PackageManager pm) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Получить чёрный список (набор package names).
     */
    public Set<String> getBlacklist() {
        return prefs.getStringSet(PREF_BLACKLIST, new HashSet<>());
    }

    /**
     * Сохранить чёрный список.
     */
    public void saveBlacklist(Set<String> blacklist) {
        prefs.edit().putStringSet(PREF_BLACKLIST, blacklist).apply();
        FileLogger.i(TAG, "Чёрный список сохранён: " + blacklist.size() + " приложений");
    }

    /**
     * Добавить приложение в чёрный список.
     */
    public void addToBlacklist(String packageName) {
        Set<String> blacklist = getBlacklist();
        blacklist.add(packageName);
        saveBlacklist(blacklist);
    }

    /**
     * Удалить приложение из чёрного списка.
     */
    public void removeFromBlacklist(String packageName) {
        Set<String> blacklist = getBlacklist();
        blacklist.remove(packageName);
        saveBlacklist(blacklist);
    }

    /**
     * Проверить, находится ли приложение в чёрном списке.
     */
    public boolean isBlacklisted(String packageName) {
        return getBlacklist().contains(packageName);
    }

    /**
     * Получить количество приложений в чёрном списке.
     */
    public int getBlacklistCount() {
        return getBlacklist().size();
    }

    /**
     * Очистить чёрный список.
     */
    public void clearBlacklist() {
        saveBlacklist(new HashSet<>());
    }
}