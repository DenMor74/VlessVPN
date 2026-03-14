package com.vlessvpn.app.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CrashHandler — перехватывает краши и показывает их на экране.
 *
 * При краше:
 * 1. Сохраняет стектрейс в файл
 * 2. Запускает CrashActivity — экран с текстом ошибки
 * 3. На экране: кнопка "Скопировать" → вставить в Telegram/email
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String CRASH_FILE = "crash_log.txt";

    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public static void install(Context context) {
        CrashHandler handler = new CrashHandler(
            context.getApplicationContext(),
            Thread.getDefaultUncaughtExceptionHandler()
        );
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Log.i(TAG, "CrashHandler установлен");
    }

    private CrashHandler(Context context, Thread.UncaughtExceptionHandler defaultHandler) {
        this.context = context;
        this.defaultHandler = defaultHandler;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            String report = buildReport(thread, throwable);

            // Сохраняем в файл
            File file = new File(context.getFilesDir(), CRASH_FILE);
            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write(report);
            }
            Log.e(TAG, "Краш сохранён: " + file.getAbsolutePath());

            // Запускаем CrashActivity — показываем текст на экране
            Intent intent = new Intent(context, CrashActivity.class);
            intent.putExtra("crash_text", report);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);

            Thread.sleep(500);
        } catch (Exception e) {
            Log.e(TAG, "CrashHandler itself crashed", e);
        }

        // Вызываем стандартный обработчик (завершает процесс)
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        }
    }

    private String buildReport(Thread thread, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        pw.println("═══════════════════════════════════");
        pw.println("  CRASH REPORT — VlessVPN");
        pw.println("═══════════════════════════════════");
        pw.println("Время:      " + sdf.format(new Date()));
        pw.println("Устройство: " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("Android:    " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("Поток:      " + thread.getName());
        pw.println("───────────────────────────────────");
        pw.println("ИСКЛЮЧЕНИЕ:");
        pw.println();
        t.printStackTrace(pw);

        // Цепочка причин
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < 5) {
            pw.println();
            pw.println("ПРИЧИНА (cause " + (depth + 1) + "):");
            cause.printStackTrace(pw);
            cause = cause.getCause();
            depth++;
        }

        // Последние строки FileLogger если есть
        try {
            File logFile = new File(context.getFilesDir(), "app_log.txt");
            if (logFile.exists()) {
                pw.println();
                pw.println("───────────────────────────────────");
                pw.println("ПОСЛЕДНИЕ СТРОКИ app_log.txt:");
                String[] lines = new java.io.FileInputStream(logFile)
                    .toString().split("\n");
                // Читаем последние 30 строк через RandomAccessFile
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r");
                long len = raf.length();
                long pos = Math.max(0, len - 3000); // последние ~3000 байт
                raf.seek(pos);
                byte[] buf = new byte[(int)(len - pos)];
                raf.readFully(buf);
                raf.close();
                pw.println(new String(buf, "UTF-8"));
            }
        } catch (Exception ignored) {}

        pw.println("═══════════════════════════════════");
        return sw.toString();
    }
}
