package com.vlessvpn.app.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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

    // ════════════════════════════════════════════════════════════════
    // ← ИЗМЕНЕНО: 100 KB вместо 2 MB
    // ════════════════════════════════════════════════════════════════
    private static final long MAX_SIZE_BYTES = 50 * 1024; // 50 KB
    private static final long MIN_SIZE_BYTES = 10 * 1024;  // 10 KB (остаётся после очистки)

    private static File logFile = null;
    private static final SimpleDateFormat sdf =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    /**
     * Инициализация. Вызывать в VpnApplication.onCreate() первой строкой.
     * Использует context.getFilesDir() — всегда доступно, разрешения не нужны.
     */
    public static void init(Context context) {
        try {
            File dir = context.getFilesDir();
            if (!dir.exists()) dir.mkdirs();

            logFile = new File(dir, FILE_NAME);

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

    public static String getRecentLogs(int hours) {
        if (logFile == null || !logFile.exists()) return "Лог пуст";

        StringBuilder sb = new StringBuilder();
        long cutoff = System.currentTimeMillis() - (hours * 3600L * 1000L);

        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() < 20) {
                    sb.append(line).append("\n");
                    continue;
                }
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            return "Ошибка чтения лога";
        }
        return sb.toString();
    }

    /**
     * Полностью очищает файл лога (удаляет все записи)
     */
    public static void clearLog() {
        if (logFile != null && logFile.exists()) {
            if (logFile.delete()) {
                try {
                    logFile.createNewFile();
                } catch (IOException ignored) {}
                Log.i(TAG, "=== ЛОГ ПОЛНОСТЬЮ ОЧИЩЕН ===");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ← НОВЫЙ МЕТОД: Проверка и ротация лог файла
    // ════════════════════════════════════════════════════════════════
    /**
     * Проверяет размер лог файла и очищает если превышает 100 KB.
     * Вызывать во время периодической проверки (каждую минуту).
     */
    public static void checkAndRotateLog() {
        if (logFile == null || !logFile.exists()) {
            return;
        }

        long fileSize = logFile.length();

        // ════════════════════════════════════════════════════════════════
        // ← Если размер превышает 100 KB — очищаем
        // ════════════════════════════════════════════════════════════════
        if (fileSize > MAX_SIZE_BYTES) {
            i(TAG, "═════════════════════");
            i(TAG, "Очистка лог файла");
            i(TAG, "Было: " + fmtBytes(fileSize));

            rotateLogFile(MIN_SIZE_BYTES);

            i(TAG, "Стало: " + fmtBytes(MIN_SIZE_BYTES));
            i(TAG, "═════════════════════");
        }
    }

    /**
     * Ротация лог файла — оставляет последние N байт.
     */
    private static void rotateLogFile(long targetSize) {
        try {
            // Читаем весь файл
            BufferedReader reader = new BufferedReader(new FileReader(logFile));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            reader.close();

            // ════════════════════════════════════════════════════════════════
            // ← Идём с конца и собираем строки пока не наберём targetSize
            // ════════════════════════════════════════════════════════════════
            List<String> lastLines = new ArrayList<>();
            long currentSize = 0;

            for (int i = lines.size() - 1; i >= 0; i--) {
                String l = lines.get(i);
                currentSize += l.length() + 1; // +1 для \n

                if (currentSize > targetSize && lastLines.size() > 50) {
                    break; // Останавливаемся когда набрали достаточно
                }

                lastLines.add(0, l); // Добавляем в начало чтобы сохранить порядок
            }

            // ════════════════════════════════════════════════════════════════
            // ← Записываем обратно
            // ════════════════════════════════════════════════════════════════
            FileWriter writer = new FileWriter(logFile);
            for (String l : lastLines) {
                writer.write(l);
                writer.write("\n");
            }
            writer.close();

            i(TAG, "Очищено строк: " + (lines.size() - lastLines.size()) +
                    " → осталось: " + lastLines.size());

        } catch (Exception e) {
            e(TAG, "Ошибка rotateLogFile: " + e.getMessage());
            // ════════════════════════════════════════════════════════════════
            // ← Fallback: просто удаляем файл если ошибка
            // ════════════════════════════════════════════════════════════════
            logFile.delete();
            try {
                logFile.createNewFile();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Форматирование размера файла
     */
    private static String fmtBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }

    /**
     * Получить текущий размер лог файла (для отладки)
     */
    public static long getLogFileSize() {
        if (logFile == null || !logFile.exists()) return 0;
        return logFile.length();
    }
}