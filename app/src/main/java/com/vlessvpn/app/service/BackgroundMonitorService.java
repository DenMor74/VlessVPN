package com.vlessvpn.app.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.gson.Gson;
import com.vlessvpn.app.core.V2RayConfigBuilder;
import com.vlessvpn.app.core.V2RayManager;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.network.ConfigDownloader;
import com.vlessvpn.app.network.ServerTester;
import com.vlessvpn.app.network.WifiMonitor;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BackgroundMonitorService extends Service {

    private static final String TAG            = "BackgroundMonitor";
    private static final String WORK_DOWNLOAD  = "server_download";
    private static final String WORK_SCAN      = "server_scan";

    // ── Планировщики ────────────────────────────────────────────────────────

    public static void scheduleDownload(Context ctx, int intervalHours) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_DOWNLOAD,
                ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(DownloadWorker.class, intervalHours, TimeUnit.HOURS)
                        .addTag(WORK_DOWNLOAD).build());
    }

    public static void scheduleScan(Context ctx, int intervalMinutes) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_SCAN,
                ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(ScanWorker.class, intervalMinutes, TimeUnit.MINUTES)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .addTag(WORK_SCAN).build());
    }

    /** Запустить скачивание немедленно (кнопка "Скачать") */
    public static void runDownloadNow(Context ctx) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_DOWNLOAD + "_manual", ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(DownloadWorker.class).build());
    }

    /** Запустить сканирование немедленно (кнопка "Проверить текущий лист") */
    public static void runScanNow(Context ctx) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_SCAN + "_manual", ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(ScanWorker.class).build());
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

    // ════════════════════════════════════════════════════════════════════════
    // DownloadWorker — скачивает списки, затем ВСЕГДА запускает сканирование
    // ════════════════════════════════════════════════════════════════════════

    public static class DownloadWorker extends Worker {
        private static final String W = "DownloadWorker";

        public DownloadWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            Context ctx = getApplicationContext();
            ServerRepository repo = new ServerRepository(ctx);

            // Ночной режим
            if (repo.isNightTime()) {
                FileLogger.i(W, "Ночное время — пропуск скачивания");
                StatusBus.post(ctx, "🌙 Ночной режим — скачивание отложено", false);
                return Result.success();
            }

            // ИСПРАВЛЕНО: Проверяем не рано ли обновляться (для периодического запуска)
            // Для ручного запуска (через runDownloadNow) время сброшено заранее в forceRefreshServers
            if (!repo.isUpdateNeeded()) {
                FileLogger.i(W, "Обновление ещё не нужно — пропускаем скачивание");
                // Но если в базе нет ни одного протестированного сервера — сканируем
                if (repo.getWorkingCount() == 0) {
                    FileLogger.i(W, "Нет рабочих серверов — запускаем сканирование");
                    runScanNow(ctx);
                }
                return Result.success();
            }

            if (!hasRealInternet()) {
                StatusBus.post(ctx, "⚠️ Нет интернета — скачивание пропущено", false);
                return Result.retry();
            }

            StatusBus.post(ctx, "📥 Скачиваем новые списки...", true);
            String[] urls = repo.getConfigUrls();
            int totalDownloaded = 0;

            for (int i = 0; i < urls.length; i++) {
                String url = urls[i];
                if (url == null || url.trim().isEmpty()) continue;
                StatusBus.post(ctx, "📥 Загрузка " + (i + 1) + "/" + urls.length, true);
                try {
                    List<VlessServer> fresh = new ConfigDownloader().download(ctx, url.trim(), url.trim());
                    if (!fresh.isEmpty()) {
                        if (!VpnTunnelService.isRunning) {
                            // VPN не активен — безопасно удалить старые и вставить новые
                            repo.deleteBySourceUrlSync(url.trim());
                        } else {
                            // VPN активен — НЕ удаляем старые проверенные серверы,
                            // insertAll с REPLACE обновит существующие по id
                            FileLogger.i(W, "VPN активен — сохраняем проверенные серверы");
                        }
                        repo.insertAll(fresh);
                        totalDownloaded += fresh.size();
                        FileLogger.i(W, "Загружено " + fresh.size() + " серверов с " + url);
                    }
                } catch (Exception e) {
                    FileLogger.w(W, "Ошибка загрузки " + url + ": " + e.getMessage());
                }
            }

            repo.markUpdated();
            StatusBus.done(ctx, "✅ Загружено " + totalDownloaded + " серверов");

            // ИСПРАВЛЕНО: Всегда запускаем сканирование после скачивания
            if (totalDownloaded > 0) {
                FileLogger.i(W, "Скачано " + totalDownloaded + " серверов → запускаем сканирование");
                StatusBus.post(ctx, "🔍 Запускаем проверку серверов...", true);
                repo.resetAllTestTimesSync();
                runScanNow(ctx);
            } else {
                FileLogger.w(W, "Ничего не скачано — сканирование пропущено");
                StatusBus.done(ctx, "⚠️ Нет новых серверов");
            }

            return Result.success();
        }

        private boolean hasRealInternet() {
            ConnectivityManager cm = (ConnectivityManager)
                    getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress("8.8.8.8", 53), 2000);
                        return true;
                    } catch (Exception e) { return false; }
                }
            }
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ScanWorker — TCP пинг + VLESS measureDelay для всех серверов
    // ════════════════════════════════════════════════════════════════════════

    public static class ScanWorker extends Worker {
        private static final String W = "ScanWorker";

        public ScanWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            Context ctx = getApplicationContext();
            ServerRepository repo = new ServerRepository(ctx);

            if (repo.isNightTime()) {
                FileLogger.i(W, "Ночное время — пропуск сканирования");
                StatusBus.post(ctx, "🌙 Ночной режим — сканирование отложено", false);
                return Result.success();
            }

            if (VpnTunnelService.isRunning) {
                FileLogger.i(W, "VPN подключён — пропускаем фоновое сканирование");
                return Result.success();
            }

            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "VlessVPN::ScanWorker");
            wakeLock.acquire(10 * 60 * 1000L);

            try {
                return doScan(ctx, repo);
            } finally {
                if (wakeLock.isHeld()) wakeLock.release();
            }
        }

        private Result doScan(Context ctx, ServerRepository repo) {
            List<VlessServer> toTest = repo.getAllServersSync();
            if (toTest.isEmpty()) {
                StatusBus.done(ctx, "⚠️ Нет серверов в базе");
                return Result.retry();
            }

            int total = toTest.size();
            StatusBus.post(ctx, "🔍 Тестируем " + total + " серверов...", true);
            StatusBus.setWorking(ctx, true);

            ExecutorService pool = Executors.newFixedThreadPool(10);
            AtomicInteger portCounter = new AtomicInteger(10900);
            CountDownLatch latch = new CountDownLatch(total);
            AtomicInteger done  = new AtomicInteger(0);
            AtomicInteger ok    = new AtomicInteger(0);

            for (VlessServer server : toTest) {
                if (isStopped()) { latch.countDown(); continue; }
                pool.submit(() -> {
                    try {
                        int curr    = done.incrementAndGet();
                        int percent = (curr * 100) / total;

                        StatusBus.postServer(ctx, server.id, server.host,
                                "pinging", -1, false, curr + "/" + total);

                        // Шаг 1: TCP пинг (быстрая фильтрация недоступных)
                        ServerTester.TestResult tcp = ServerTester.tcpTest(ctx, server);

                        if (!tcp.trafficOk) {
                            server.trafficOk    = false;
                            server.pingMs       = -1;
                            server.tcpPingMs    = (int) tcp.pingMs;
                            server.lastTestedAt = System.currentTimeMillis();
                            repo.updateServerSync(server);
                            StatusBus.postServer(ctx, server.id, server.host,
                                    "fail", -1, false, "✗ TCP");
                        } else {
                            // Шаг 2: VLESS measureDelay (реальная проверка протокола)
                            StatusBus.postServer(ctx, server.id, server.host,
                                    "testing", tcp.pingMs, false,
                                    "TCP " + tcp.pingMs + "ms → VLESS...");

                            int testPort = (portCounter.getAndIncrement() % 100) + 10900;
                            String testCfg = V2RayConfigBuilder.buildForTest(server, testPort);
                            long vlessDelay = V2RayManager.measureDelay(ctx, testCfg);

                            if (vlessDelay > 0) {
                                server.pingMs       = vlessDelay;
                                server.tcpPingMs    = (int) tcp.pingMs;
                                server.trafficOk    = true;
                                server.lastTestedAt = System.currentTimeMillis();
                                repo.updateServerSync(server);
                                ok.incrementAndGet();
                                StatusBus.postServer(ctx, server.id, server.host,
                                        "ok", vlessDelay, true,
                                        "✓ VLESS " + vlessDelay + "ms");
                            } else {
                                server.trafficOk    = false;
                                server.pingMs       = -1;
                                server.tcpPingMs    = (int) tcp.pingMs;
                                server.lastTestedAt = System.currentTimeMillis();
                                repo.updateServerSync(server);
                                StatusBus.postServer(ctx, server.id, server.host,
                                        "fail", tcp.pingMs, false,
                                        "✗ VLESS (TCP " + tcp.pingMs + "ms)");
                            }
                        }

                        StatusBus.updateCounts(ctx, total, ok.get(), curr - ok.get());
                        StatusBus.setProgress(ctx, percent);
                        StatusBus.post(ctx,
                                "🔍 " + curr + "/" + total + " (✓ " + ok.get() + " рабочих)", true);

                    } catch (Exception e) {
                        FileLogger.e(W, "Ошибка теста " + server.host, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try { latch.await(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}
            pool.shutdownNow();

            repo.markScanned();

            int finalOk   = ok.get();
            int finalFail = total - finalOk;
            StatusBus.setWorking(ctx, false);
            StatusBus.done(ctx, "✅ " + finalOk + " рабочих из " + total
                    + " (✗ " + finalFail + ")");

            // Авто-подключение после сканирования (если включено)
            checkAutoConnect(ctx, repo);

            FileLogger.i(W, "=== Завершено: " + finalOk + "/" + total + " ===");
            return Result.success();
        }

        private static void checkAutoConnect(Context ctx, ServerRepository repo) {
            if (!repo.isAutoConnectAfterScan()) return;

            boolean wifiConnected = WifiMonitor.isWifiConnected(ctx);
            boolean vpnRunning    = VpnTunnelService.isRunning;

            if (!wifiConnected && !vpnRunning) {
                VlessServer server = repo.getLastWorkingServer();
                if (server == null) {
                    List<VlessServer> working = repo.getTopServersSync();
                    if (working.isEmpty()) return;
                    server = working.get(0);
                }
                FileLogger.i(W, "Авто-подключение после сканирования: " + server.host);
                Intent i = new Intent(ctx, VpnTunnelService.class);
                i.setAction(VpnTunnelService.ACTION_CONNECT);
                i.putExtra(VpnTunnelService.EXTRA_SERVER, new Gson().toJson(server));
                i.putExtra(VpnTunnelService.EXTRA_AUTO_CONNECT, true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(i);
                else
                    ctx.startService(i);
            }

            if (wifiConnected && vpnRunning) {
                Intent i = new Intent(ctx, VpnTunnelService.class);
                i.setAction(VpnTunnelService.ACTION_DISCONNECT);
                ctx.startService(i);
            }
        }
    }
}
