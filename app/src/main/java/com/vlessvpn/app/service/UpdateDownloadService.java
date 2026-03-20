package com.vlessvpn.app.service;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.vlessvpn.app.R;
import com.vlessvpn.app.ui.MainActivity;
import com.vlessvpn.app.util.FileLogger;

/**
 * UpdateDownloadService — сервис для скачивания APK обновления.
 */
public class UpdateDownloadService extends Service {

    private static final String TAG = "UpdateDownloadService";
    private static final String CHANNEL_ID = "update_channel";
    private static final int NOTIF_ID = 2001;

    public static final String EXTRA_DOWNLOAD_URL = "download_url";
    public static final String EXTRA_VERSION_NAME = "version_name";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String downloadUrl = intent != null ? intent.getStringExtra(EXTRA_DOWNLOAD_URL) : null;
        String versionName = intent != null ? intent.getStringExtra(EXTRA_VERSION_NAME) : null;

        if (downloadUrl == null) {
            FileLogger.e(TAG, "Нет URL для загрузки");
            stopSelf();
            return START_NOT_STICKY;
        }

        FileLogger.i(TAG, "Начало загрузки: " + downloadUrl);

        showNotification("Загрузка обновления...", versionName != null ? versionName : "");

        downloadApk(downloadUrl);

        // Не останавливаем сервис сразу — ждём завершения загрузки
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void downloadApk(String url) {
        try {
            FileLogger.i(TAG, "downloadApk: " + url);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("VlessVPN Update");
            request.setDescription("Загрузка новой версии...");

            // ════════════════════════════════════════════════════════════════
            // ← ВАЖНО: VISIBILITY_VISIBLE_NOTIFY_COMPLETED для broadcast
            // ════════════════════════════════════════════════════════════════
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );

            // ════════════════════════════════════════════════════════════════
            // ← Куда скачивать (внешнее хранилище — Downloads)
            // ════════════════════════════════════════════════════════════════
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "vlessvpn_update.apk"
            );

            // ════════════════════════════════════════════════════════════════
            // ← Разрешить перезапись существующего файла
            // ════════════════════════════════════════════════════════════════
            request.allowScanningByMediaScanner();

            // Разрешить загрузку через мобильную сеть и WiFi
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI |
                            DownloadManager.Request.NETWORK_MOBILE
            );

            // MIME тип
            request.setMimeType("application/vnd.android.package-archive");

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                long downloadId = dm.enqueue(request);
                FileLogger.i(TAG, "═══════════════════════════════════════");
                FileLogger.i(TAG, "Загрузка начата!");
                FileLogger.i(TAG, "Download ID: " + downloadId);
                FileLogger.i(TAG, "URL: " + url);
                FileLogger.i(TAG, "Путь: /sdcard/Download/vlessvpn_update.apk");
                FileLogger.i(TAG, "═══════════════════════════════════════");

                // Сохранить ID для отладки
                android.content.SharedPreferences prefs =
                        android.preference.PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit().putLong("last_download_id", downloadId).apply();

            } else {
                FileLogger.e(TAG, "DownloadManager = null!");
            }

        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка загрузки: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void showNotification(String title, String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIF_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Обновления",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Статус загрузки обновлений");

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}