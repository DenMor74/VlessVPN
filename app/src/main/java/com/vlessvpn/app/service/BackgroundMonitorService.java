package com.vlessvpn.app.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.vlessvpn.app.core.V2RayConfigBuilder;
import com.vlessvpn.app.core.V2RayManager;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.network.ConfigDownloader;
import com.vlessvpn.app.network.ServerTester;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BackgroundMonitorService extends Service {

    private static final String TAG = "BackgroundMonitor";
    private static final String WORK_TAG_DOWNLOAD = "server_download";
    private static final String WORK_TAG_SCAN = "server_scan";

    // ════════════════════════════════════════════════════════════════
    // ПЛАНИРОВЩИК: Скачивание новых списков
    // ════════════════════════════════════════════════════════════════

    public static void scheduleDownload(Context ctx, int intervalHours) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                DownloadWorker.class, intervalHours, TimeUnit.HOURS)
                .addTag(WORK_TAG_DOWNLOAD).build();
        WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK_TAG_DOWNLOAD,
                        ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    public static void runDownloadNow(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DownloadWorker.class)
                .addTag(WORK_TAG_DOWNLOAD + "_manual").build();
        WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_TAG_DOWNLOAD + "_manual",
                        ExistingWorkPolicy.REPLACE, req);
    }

    // ════════════════════════════════════════════════════════════════
    // ПЛАНИРОВЩИК: Сканирование текущего списка (без скачивания)
    // ════════════════════════════════════════════════════════════════

    public static void scheduleScan(Context ctx, int intervalMinutes) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                ScanWorker.class, intervalMinutes, TimeUnit.MINUTES)
                .addTag(WORK_TAG_SCAN).build();
        WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK_TAG_SCAN,
                        ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    public static void runScanNow(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ScanWorker.class)
                .addTag(WORK_TAG_SCAN + "_manual").build();
        WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_TAG_SCAN + "_manual",
                        ExistingWorkPolicy.REPLACE, req);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ServerRepository repo = new ServerRepository(this);

        scheduleDownload(this, repo.getUpdateIntervalHours());
        scheduleScan(this, repo.getScanIntervalMinutes());

        stopSelf();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ════════════════════════════════════════════════════════════════
    // DownloadWorker: ТОЛЬКО скачивание списков
    // ════════════════════════════════════════════════════════════════

    public static class DownloadWorker extends Worker {
        private static final String W = "DownloadWorker";

        public DownloadWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            Context ctx = getApplicationContext();
            ServerRepository repo = new ServerRepository(ctx);

            StatusBus.post("📥 Скачиваем новые списки...", true);
            FileLogger.i(W, "=== DownloadWorker START ===");

            String[] urls = repo.getConfigUrls();
            int totalDownloaded = 0;

            if (hasRealInternet()) {
                int urlIndex = 0;
                for (String url : urls) {
                    if (url == null || url.trim().isEmpty()) continue;

                    urlIndex++;
                    StatusBus.post("📥 Загрузка " + urlIndex + "/" + urls.length, true);

                    try {
                        ConfigDownloader dl = new ConfigDownloader();
                        List<VlessServer> fresh = dl.download(ctx, url.trim(), url.trim());

                        if (!fresh.isEmpty()) {
                            repo.deleteBySourceUrlSync(url.trim());
                            repo.insertAll(fresh);
                            totalDownloaded += fresh.size();
                            FileLogger.i(W, "Загружено " + fresh.size() + " серверов с " + url);
                        }
                    } catch (Exception e) {
                        FileLogger.w(W, "Ошибка загрузки " + url + ": " + e.getMessage());
                    }
                }
                repo.markUpdated();
                StatusBus.done("✅ Загружено " + totalDownloaded + " серверов");
            } else {
                FileLogger.i(W, "Нет интернета — пропускаем скачивание");
                StatusBus.post("⚠️ Нет интернета — скачивание пропущено", true);
                return Result.retry();
            }

            FileLogger.i(W, "=== DownloadWorker DONE: " + totalDownloaded + " серверов ===");
            return Result.success();
        }

        private boolean hasRealInternet() {
            Context ctx = getApplicationContext();
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    try (java.net.Socket s = new java.net.Socket()) {
                        s.connect(new InetSocketAddress("8.8.8.8", 53), 2000);
                        return true;
                    } catch (Exception e) { return false; }
                }
            }
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ScanWorker: ТОЛЬКО сканирование текущего списка (без скачивания)
    // ════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════
// В ScanWorker.doWork() — заменить отправку прогресса
// ════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════
// В ScanWorker.doWork() — полный код с итоговым результатом
// ════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════
// В ScanWorker.doWork() — в конце НЕ скрывать панель прогресса
// ════════════════════════════════════════════════════════════════

    public static class ScanWorker extends Worker {
        private static final String W = "ScanWorker";

        public ScanWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            Context ctx = getApplicationContext();
            ServerRepository repo = new ServerRepository(ctx);

            if (VpnTunnelService.isRunning) {
                FileLogger.i(W, "VPN подключён — пропускаем фоновое сканирование");
                return Result.success();
            }

            StatusBus.post(ctx, "🔍 Сканируем текущий список...", true);
            FileLogger.i(W, "=== ScanWorker START ===");

            List<VlessServer> toTest = repo.getAllServersSync();
            if (toTest.isEmpty()) {
                StatusBus.done(ctx, "⚠️ Нет серверов в базе");
                return Result.retry();
            }

            int total = toTest.size();
            StatusBus.post(ctx, "🔍 Тестируем " + total + " серверов...", true);
            StatusBus.setWorking(ctx, true);

            final int THREADS = 10;
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            final AtomicInteger portCounter = new AtomicInteger(10900);
            CountDownLatch latch = new CountDownLatch(total);
            AtomicInteger done = new AtomicInteger(0);
            AtomicInteger ok = new AtomicInteger(0);

            for (VlessServer server : toTest) {
                if (isStopped()) { latch.countDown(); continue; }
                pool.submit(() -> {
                    try {
                        int current = done.incrementAndGet();
                        int percent = (current * 100) / total;

                        StatusBus.postServer(
                                ctx, server.id, server.host, "pinging", -1, false,
                                current + "/" + total
                        );

                        ServerTester.TestResult tcp = ServerTester.tcpTest(ctx, server);

                        if (!tcp.trafficOk) {
                            server.trafficOk = false;
                            server.lastTestedAt = System.currentTimeMillis();
                            repo.updateServerSync(server);
                            StatusBus.postServer(ctx, server.id, server.host, "fail", -1, false, "✗ TCP");
                        } else {
                            StatusBus.postServer(
                                    ctx, server.id, server.host, "testing", tcp.pingMs, false,
                                    "TCP " + tcp.pingMs + "ms → VLESS..."
                            );

                            int testPort = (portCounter.getAndIncrement() % 100) + 10900;
                            String testCfg = V2RayConfigBuilder.buildForTest(server, testPort);
                            long vlessDelay = V2RayManager.measureDelay(ctx, testCfg);

                            if (vlessDelay > 0) {
                                server.pingMs = vlessDelay;
                                server.trafficOk = true;
                                server.lastTestedAt = System.currentTimeMillis();
                                repo.updateServerSync(server);
                                ok.incrementAndGet();
                                StatusBus.postServer(ctx, server.id, server.host, "ok", vlessDelay, true, "✓ VLESS " + vlessDelay + "ms");
                            } else {
                                server.trafficOk = false;
                                server.pingMs = tcp.pingMs;
                                server.lastTestedAt = System.currentTimeMillis();
                                repo.updateServerSync(server);
                                StatusBus.postServer(ctx, server.id, server.host, "fail", tcp.pingMs, false, "✗ VLESS");
                            }
                        }

                        StatusBus.updateCounts(ctx, total, ok.get(), current - ok.get());
                        StatusBus.setProgress(ctx, percent);
                        StatusBus.post(ctx, "🔍 " + current + "/" + total + " (✓ " + ok.get() + " рабочих)", true);

                    } catch (Exception e) {
                        FileLogger.e(W, "Ошибка теста " + server.host, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try { latch.await(5, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}
            pool.shutdownNow();

            repo.markScanned();

            // ════════════════════════════════════════════════════════════════
            // ← ИТОГ: Остаётся виден постоянно (не скрываем панель)
            // ════════════════════════════════════════════════════════════════
            int finalOk = ok.get();
            int finalFail = total - finalOk;

            // Формируем итоговую строку
            String summary = "✅ " + finalOk + " рабочих из " + total + " (✗ " + finalFail + ")";

            StatusBus.setWorking(ctx, false);  // ← Останавливаем спиннер
            StatusBus.done(ctx, summary);       // ← Но текст остаётся!

            FileLogger.i(W, "=== ScanWorker DONE: " + summary);
            return Result.success();
        }
    }
}