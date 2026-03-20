package com.vlessvpn.app.service;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import com.vlessvpn.app.util.FileLogger;

import java.io.File;

/**
 * UpdateDownloadReceiver — отслеживает завершение загрузки и запускает установку.
 * ВАЖНО: Должен быть ОТДЕЛЬНЫМ классом (не inner class!)
 */
public class UpdateDownloadReceiver extends BroadcastReceiver {

    private static final String TAG = "UpdateDownloadReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        FileLogger.i(TAG, "═══════════════════════════════════════");
        FileLogger.i(TAG, "onReceive вызван!");
        FileLogger.i(TAG, "Action: " + (intent != null ? intent.getAction() : "null"));
        FileLogger.i(TAG, "═══════════════════════════════════════");

        if (intent == null) return;

        String action = intent.getAction();

        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

            FileLogger.i(TAG, "DOWNLOAD_COMPLETE, ID: " + downloadId);

            if (downloadId != -1) {
                installApk(context, downloadId);
            }
        } else {
            FileLogger.w(TAG, "Неизвестное действие: " + action);
        }
    }

    private void installApk(Context context, long downloadId) {
        try {
            FileLogger.i(TAG, "Начало установки, downloadId: " + downloadId);

            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                FileLogger.e(TAG, "DownloadManager = null");
                return;
            }

            // Получить информацию о загрузке
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
            Cursor cursor = dm.query(query);

            if (cursor == null) {
                FileLogger.e(TAG, "Cursor = null");
                return;
            }

            if (!cursor.moveToFirst()) {
                FileLogger.e(TAG, "Cursor пуст");
                cursor.close();
                return;
            }

            // ════════════════════════════════════════════════════════════════
            // ← Получить статус загрузки
            // ════════════════════════════════════════════════════════════════
            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int status = cursor.getInt(statusIndex);

            FileLogger.i(TAG, "Статус загрузки: " + status);

            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                FileLogger.e(TAG, "Загрузка не успешна! Status: " + status);
                cursor.close();
                return;
            }

            // Получить URI
            int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            String uriString = cursor.getString(uriIndex);

            FileLogger.i(TAG, "APK URI: " + uriString);

            cursor.close();

            if (uriString == null) {
                FileLogger.e(TAG, "URI = null");
                return;
            }

            // ════════════════════════════════════════════════════════════════
            // ← Создать Intent для установки
            // ════════════════════════════════════════════════════════════════
            Intent installIntent = new Intent(Intent.ACTION_VIEW);

            Uri apkUri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+ — использовать FileProvider
                FileLogger.i(TAG, "Android 7+ — используем FileProvider");

                // Попытка получить путь к файлу
                String filePath = getFilePathFromUri(context, uriString);
                FileLogger.i(TAG, "Путь к файлу: " + filePath);

                if (filePath != null) {
                    File apkFile = new File(filePath);
                    if (apkFile.exists()) {
                        FileLogger.i(TAG, "Файл существует, размер: " + apkFile.length());

                        apkUri = FileProvider.getUriForFile(
                                context,
                                context.getPackageName() + ".provider",
                                apkFile
                        );
                        FileLogger.i(TAG, "FileProvider URI: " + apkUri);
                    } else {
                        FileLogger.e(TAG, "Файл не существует!");
                        apkUri = Uri.parse(uriString);
                    }
                } else {
                    FileLogger.w(TAG, "Не удалось получить путь, используем URI");
                    apkUri = Uri.parse(uriString);
                }
            } else {
                // Android 6 и ниже
                FileLogger.i(TAG, "Android 6 или ниже — прямой URI");
                apkUri = Uri.parse(uriString);
            }

            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            FileLogger.i(TAG, "Запуск установки...");

            // Проверить есть ли Activity для обработки
            if (installIntent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(installIntent);
                FileLogger.i(TAG, "Установка запущена успешно!");
            } else {
                FileLogger.e(TAG, "Нет Activity для обработки Intent!");
            }

        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка установки: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String getFilePathFromUri(Context context, String uriString) {
        try {
            FileLogger.d(TAG, "getFilePathFromUri: " + uriString);

            // content://downloads/public_downloads/123
            if (uriString.startsWith("content://downloads/public_downloads/")) {
                String id = uriString.substring("content://downloads/public_downloads/".length());
                FileLogger.d(TAG, "Download ID: " + id);

                try {
                    Uri uri = android.content.ContentUris.withAppendedId(
                            android.net.Uri.parse("content://downloads/public_downloads"),
                            Long.parseLong(id)
                    );

                    String[] projection = { android.provider.MediaStore.Files.FileColumns.DATA };
                    Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);

                    if (cursor != null) {
                        if (cursor.moveToFirst()) {
                            String path = cursor.getString(0);
                            cursor.close();
                            FileLogger.d(TAG, "Путь из курсора: " + path);
                            return path;
                        }
                        cursor.close();
                    }
                } catch (NumberFormatException e) {
                    FileLogger.w(TAG, "Неверный формат ID: " + e.getMessage());
                }
            }

            // file:///storage/emulated/0/Download/...
            if (uriString.startsWith("file://")) {
                String path = uriString.substring(7);
                FileLogger.d(TAG, "Путь из file://: " + path);
                return path;
            }

            // content://com.vlessvpn.app.provider/...
            if (uriString.startsWith("content://")) {
                FileLogger.d(TAG, "Content URI — пробуем получить путь");

                String[] projection = { android.provider.MediaStore.Files.FileColumns.DATA };
                Cursor cursor = context.getContentResolver().query(
                        Uri.parse(uriString), projection, null, null, null
                );

                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        String path = cursor.getString(0);
                        cursor.close();
                        FileLogger.d(TAG, "Путь из content URI: " + path);
                        return path;
                    }
                    cursor.close();
                }
            }

        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка getFilePathFromUri: " + e.getMessage());
        }

        return null;
    }
}