package com.vlessvpn.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.vlessvpn.app.storage.ServerRepository;

/**
 * BootReceiver — слушает событие перезагрузки телефона.
 *
 * После перезагрузки WorkManager теряет свои задачи (в некоторых случаях).
 * Этот Receiver перепланирует фоновую проверку серверов.
 *
 * Для работы нужны разрешения в AndroidManifest:
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            Log.i(TAG, "Устройство загружено, перепланируем мониторинг серверов");

            // Восстанавливаем расписание WorkManager
            ServerRepository repo = new ServerRepository(context);
            BackgroundMonitorService.schedule(context, repo.getUpdateIntervalHours());

            Log.i(TAG, "Мониторинг серверов перепланирован");
        }
    }
}
