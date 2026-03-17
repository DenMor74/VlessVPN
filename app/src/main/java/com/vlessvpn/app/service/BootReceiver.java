package com.vlessvpn.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

/**
 * BootReceiver — запускает фоновые сервисы после загрузки устройства.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            FileLogger.i(TAG, "Загрузка завершена — запускаем планировщики");

            ServerRepository repo = new ServerRepository(context);

            // ════════════════════════════════════════════════════════════════
            // ← ИСПРАВЛЕНО: Вызываем НОВЫЕ методы (раздельные)
            // ════════════════════════════════════════════════════════════════
            BackgroundMonitorService.scheduleDownload(context, repo.getUpdateIntervalHours());
            BackgroundMonitorService.scheduleScan(context, repo.getScanIntervalMinutes());

            // Запускаем WifiMonitor
            com.vlessvpn.app.network.WifiMonitor.startMonitoring(context);

            FileLogger.i(TAG, "Планировщики запущены (Download + Scan)");
        }
    }
}