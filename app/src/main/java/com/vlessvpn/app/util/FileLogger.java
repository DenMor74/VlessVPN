package com.vlessvpn.app.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * FileLogger — пишет логи во внутреннюю папку приложения.
 *
 * Путь: /data/data/com.vlessvpn.app/files/app_log.txt
 *
 * Как читать лог с Samsung S24 (Android 14) БЕЗ root:
 *
 * Способ 1 — через ADB (USB отладка):
 *   adb exec-out run-as com.vlessvpn.app cat /data/data/com.vlessvpn.app/files/app_log.txt
 *
 * Способ 2 — кнопка "Поделиться логом" в самом приложении:
 *   Вызови FileLogger.shareLog(activity) — откроется стандартный диалог "Отправить"
 *   Можно отправить себе в Telegram, email и т.д.
 *
 * Способ 3 — Android Studio Device Explorer:
 *   View → Tool Windows → Device Explorer
 *   data/data/com.vlessvpn.app/files/app_log.txt → правая кнопка → Save As
 */
public class FileLogger {

    private static final String TAG = "FileLogger";
    private static final String FILE_NAME = "app_log.txt";
    private static final long MAX_SIZE_BYTES = 2 * 1024 * 1024; // 2 МБ

    private static File logFile = null;
    private static final SimpleDateFormat sdf =
        new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());

    /**
     * Инициализация. Вызывать в VpnApplication.onCreate() первой строкой.
     * Использует context.getFilesDir() — всегда доступно, разрешения не нужны.
     */
    public static void init(Context context) {
        try {
            // getFilesDir() = /data/data/com.vlessvpn.app/files/
            // Доступно всегда, не требует никаких разрешений
            File dir = context.getFilesDir();
            if (!dir.exists()) dir.mkdirs();

            logFile = new File(dir, FILE_NAME);

            // Всегда очищаем лог при запуске приложения
            if (logFile.exists()) {
                logFile.delete();
            }

            write("════════════════════════════════════════");
            write("  VlessVPN запущен: " + sdf.format(new Date()));
            write("  Android: " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            write("  Устройство: " + android.os.Build.MANUFACTURER
                + " " + android.os.Build.MODEL);
            write("  Лог: " + logFile.getAbsolutePath());
            write("════════════════════════════════════════");

            Log.i(TAG, "FileLogger → " + logFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "FileLogger init error: " + e.getMessage());
        }
    }

    public static void i(String tag, String msg)              { log("I", tag, msg, null); }
    public static void d(String tag, String msg)              { log("D", tag, msg, null); }
    public static void w(String tag, String msg)              { log("W", tag, msg, null); }
    public static void e(String tag, String msg)              { log("E", tag, msg, null); }
    public static void e(String tag, String msg, Throwable t) { log("E", tag, msg, t);    }

    public static void raw(String text) { write(text); }

    private static void log(String level, String tag, String msg, Throwable t) {
        switch (level) {
            case "I": Log.i(tag, msg); break;
            case "D": Log.d(tag, msg); break;
            case "W": Log.w(tag, msg); break;
            case "E": if (t != null) Log.e(tag, msg, t); else Log.e(tag, msg); break;
        }
        write(sdf.format(new Date()) + " " + level + "/" + tag + ": " + msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            write("  " + sw.toString().trim().replace("\n", "\n  "));
        }
    }

    private static synchronized void write(String text) {
        if (logFile == null) return;
        try (FileWriter fw = new FileWriter(logFile, true)) {
            fw.write(text + "\n");
        } catch (Exception ignored) {}
    }

    public static String getLogPath() {
        return logFile != null ? logFile.getAbsolutePath() : "недоступен";
    }

    public static File getLogFile() {
        return logFile;
    }

    /**
     * Отправляет лог через стандартный диалог Android (Telegram, Gmail и т.д.)
     * Вызывать из Activity при нажатии кнопки.
     */
    public static void shareLog(android.app.Activity activity) {
        if (logFile == null || !logFile.exists()) {
            android.widget.Toast.makeText(activity,
                "Лог файл не найден", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // FileProvider нужен для Android 7+ чтобы передать файл другому приложению
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".provider",
                logFile
            );
            android.content.Intent intent = new android.content.Intent(
                android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, "VlessVPN Log");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(android.content.Intent.createChooser(
                intent, "Отправить лог"));
        } catch (Exception e) {
            Log.e(TAG, "shareLog error: " + e.getMessage(), e);
            android.widget.Toast.makeText(activity,
                "Ошибка: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
