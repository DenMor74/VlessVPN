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

import com.vlessvpn.app.core.V2RayConfigBuilder;
import com.vlessvpn.app.core.V2RayManager;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.network.ConfigDownloader;
import com.vlessvpn.app.network.ServerTester;
import com.vlessvpn.app.network.WifiMonitor;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;
import com.google.gson.Gson;
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
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .setRequiresStorageNotLow(false)
                .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                ScanWorker.class, intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(WORK_TAG_SCAN)
                .build();

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

// ════════════════════════════════════════════════════════════════
// В BackgroundMonitorService.java → DownloadWorker.doWork()
// ════════════════════════════════════════════════════════════════

    public static class DownloadWorker extends Worker {
        private static final String W = "DownloadWorker";

        public DownloadWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            Context ctx = getApplicationContext();
            ServerRepository repo = new ServerRepository(ctx);
            // ════════════════════════════════════════════════════════════════
            // ← НОВОЕ: Проверка ночного времени
            // ════════════════════════════════════════════════════════════════
            if (repo.isNightTime()) {
                FileLogger.i(W, "Ночное время — пропускаем скачивание");
                StatusBus.post(ctx, "🌙 Ночной режим — скачивание отложено", false);
                return Result.success();  // Не retry, просто пропускаем
            }
            // ════════════════════════════════════════════════════════════════

            StatusBus.post(ctx, "📥 Скачиваем новые списки...", true);
            FileLogger.i(W, "=== Скачиваем новые списки");

            String[] urls = repo.getConfigUrls();
            int totalDownloaded = 0;
            boolean hasNewServers = false;  // ← НОВОЕ: Флаг новых серверов

            if (hasRealInternet()) {
                int urlIndex = 0;
                for (String url : urls) {
                    if (url == null || url.trim().isEmpty()) continue;

                    urlIndex++;
                    StatusBus.post(ctx, "📥 Загрузка " + urlIndex + "/" + urls.length, true);

                    try {
                        // ← Сохраняем количество серверов до загрузки
                        int beforeCount = repo.getAllServersSync().size();

                        ConfigDownloader dl = new ConfigDownloader();
                        List<VlessServer> fresh = dl.download(ctx, url.trim(), url.trim());

                        if (!fresh.isEmpty()) {
                            repo.deleteBySourceUrlSync(url.trim());
                            repo.insertAll(fresh);
                            totalDownloaded += fresh.size();

                            // ← Проверяем появились ли новые серверы
                            int afterCount = repo.getAllServersSync().size();
                            if (afterCount > beforeCount) {
                                hasNewServers = true;
                            }

                            FileLogger.i(W, "Загружено " + fresh.size() + " серверов с " + url);
                        }
                    } catch (Exception e) {
                        FileLogger.w(W, "Ошибка загрузки " + url + ": " + e.getMessage());
                    }
                }
                repo.markUpdated();
                StatusBus.done(ctx, "✅ Загружено " + totalDownloaded + " серверов");

                // ════════════════════════════════════════════════════════════════
                // ← НОВОЕ: Если есть новые серверы — запускаем сканирование
                // ════════════════════════════════════════════════════════════════
                if (hasNewServers && totalDownloaded > 0) {
                    FileLogger.i(W, "Новые серверы загружены — запускаем сканирование");
                    StatusBus.post(ctx, "🔍 Запускаем проверку новых серверов...", true);

                    // Сбрасываем флаги тестов для новых серверов
                    repo.resetAllTestTimesSync();

                    // Запускаем сканирование
                    runScanNow(ctx);
                }

            } else {
                FileLogger.i(W, "Нет интернета — пропускаем скачивание");
                StatusBus.post(ctx, "⚠️ Нет интернета — скачивание пропущено", true);
                return Result.retry();
            }
            repo.saveUpdateTimestamp();
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
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress("8.8.8.8", 53), 2000);
                        return true;
                    } catch (Exception e) { return false; }
                }
            }
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ScanWorker: Сканирование с WakeLock
    // ════════════════════════════════════════════════════════════════

    public static class ScanWorker extends Worker {
        private static final String W = "ScanWorker";

        public ScanWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            Context ctx = getApplicationContext();
            ServerRepository repo = new ServerRepository(ctx);
            // ════════════════════════════════════════════════════════════════
            // ← НОВОЕ: Проверка ночного времени
            // ════════════════════════════════════════════════════════════════
            if (repo.isNightTime()) {
                FileLogger.i(W, "Ночное время — пропускаем сканирование");
                StatusBus.post(ctx, "🌙 Ночной режим — сканирование отложено", false);
                return Result.success();  // Не retry, просто пропускаем
            }
            // ════════════════════════════════════════════════════════════════

            // ════════════════════════════════════════════════════════════════
            // WakeLock чтобы CPU не спал во время сканирования
            // ════════════════════════════════════════════════════════════════
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VlessVPN::ScanWorker");
            wakeLock.acquire(10 * 60 * 1000L); // 10 минут максимум

            try {
                if (VpnTunnelService.isRunning) {
                    FileLogger.i(W, "VPN подключён — пропускаем фоновое сканирование");
                    return Result.success();
                }

                StatusBus.post(ctx, "🔍 Сканируем текущий список...", true);
                FileLogger.i(W, "=== Проверка серверов...");

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

                            StatusBus.postServer(ctx, server.id, server.host, "pinging", -1, false, current + "/" + total);

                            // ════════════════════════════════════════════════════════════════
                            // TCP тест через LTE (даже при WiFi)
                            // ════════════════════════════════════════════════════════════════
                            ServerTester.TestResult tcp = ServerTester.tcpTest(ctx, server);

                            if (!tcp.trafficOk) {
                                server.trafficOk = false;
                                server.lastTestedAt = System.currentTimeMillis();
                                server.tcpPingMs = (int) tcp.pingMs;
                                repo.updateServerSync(server);
                                StatusBus.postServer(ctx, server.id, server.host, "fail", -1, false, "✗ TCP");
                                StatusBus.postServer(
                                        ctx, server.id, server.host, "fail", tcp.pingMs, false,
                                        "✗ TCP " + tcp.pingMs + "ms"  // ← TCP пинг в detail
                                );
                            } else {
                                StatusBus.postServer(
                                        ctx, server.id, server.host, "testing", tcp.pingMs, false,
                                        "TCP " + tcp.pingMs + "ms → VLESS..."  // ← TCP пинг в detail
                                );
                                StatusBus.postServer(ctx, server.id, server.host, "testing", tcp.pingMs, false, "TCP " + tcp.pingMs + "ms → VLESS...");

                                int testPort = (portCounter.getAndIncrement() % 100) + 10900;
                                String testCfg = V2RayConfigBuilder.buildForTest(server, testPort);
                                long vlessDelay = V2RayManager.measureDelay(ctx, testCfg);

                                if (vlessDelay > 0) {
                                    StatusBus.postServer(
                                            ctx, server.id, server.host, "ok", vlessDelay, true,
                                            "✓ VLESS " + vlessDelay + "ms"
                                    );
                                    server.pingMs = vlessDelay;      // ← VLESS задержка
                                    server.tcpPingMs = (int) tcp.pingMs;  // ← TCP ping
                                    server.trafficOk = true;
                                    server.lastTestedAt = System.currentTimeMillis();
                                    repo.updateServerSync(server);
                                    ok.incrementAndGet();
                                    StatusBus.postServer(ctx, server.id, server.host, "ok", vlessDelay, true, "✓ VLESS " + vlessDelay + "ms");
                                } else {
                                    StatusBus.postServer(
                                            ctx, server.id, server.host, "fail", tcp.pingMs, false,
                                            "✗ VLESS (TCP " + tcp.pingMs + "ms)"  // ← TCP пинг в detail
                                    );
                                    server.trafficOk = false;
                                    server.pingMs = -1;              // ← VLESS не прошёл
                                    server.tcpPingMs = (int) tcp.pingMs;  // ← Но TCP ping сохраняем
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

                try { latch.await(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}
                pool.shutdownNow();

                repo.markScanned();

                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                int finalOk = ok.get();
                int finalFail = total - finalOk;
                StatusBus.setWorking(ctx, false);
                StatusBus.done(ctx, "✅ " + finalOk + " рабочих из " + total + " (✗ " + finalFail + ")");

                checkWifiAndManageVpn(ctx, repo);  // ← НОВОЕ: Проверка WiFi и авто-подключение/отключение VPN

                FileLogger.i(W, "=== Завершено: " + finalOk + "/" + total + " ===");
                return Result.success();

            } finally {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        }

        private static void checkWifiAndManageVpn(Context ctx, ServerRepository repo) {
            // Проверяем только после сканирования (не монитор)
            if (!repo.isAutoConnectAfterScan()) {
                FileLogger.d(W, "Авто-подключение после сканирования отключено");
                return;
            }

            boolean wifiConnected = WifiMonitor.isWifiConnected(ctx);
            boolean vpnRunning = VpnTunnelService.isRunning;

            FileLogger.i(W, "После сканирования — WiFi: " + (wifiConnected ? "ПОДКЛЮЧЕН" : "ОТКЛЮЧЕН") +
                    ", VPN: " + (vpnRunning ? "ПОДКЛЮЧЕН" : "ОТКЛЮЧЕН"));

            if (!wifiConnected && !vpnRunning) {
                FileLogger.i(W, "WiFi отсутствует → подключаем VPN");
                autoConnectVpn(ctx, repo);
            }

            if (wifiConnected && vpnRunning) {
                FileLogger.i(W, "WiFi появился → отключаем VPN");
                autoDisconnectVpn(ctx);
            }
        }

        // ════════════════════════════════════════════════════════════════
        // ← НОВЫЙ МЕТОД: Авто-подключение к последнему рабочему серверу
        // ════════════════════════════════════════════════════════════════

        private static void autoConnectVpn(Context ctx, ServerRepository repo) {
            // Получаем последний рабочий сервер
            VlessServer server = repo.getLastWorkingServer();

            if (server == null) {
                // Если нет сохранённого — берём первый из рабочих
                List<VlessServer> working = repo.getTopServersSync();
                if (working.isEmpty()) {
                    FileLogger.w(W, "Нет рабочих серверов для авто-подключения");
                    return;
                }
                server = working.get(0);
            }

            FileLogger.i(W, "Авто-подключение к: " + server.host + ":" + server.port);

            // Запускаем VPN сервис
            Intent intent = new Intent(ctx, VpnTunnelService.class);
            intent.setAction(VpnTunnelService.ACTION_CONNECT);
            intent.putExtra(VpnTunnelService.EXTRA_SERVER, new Gson().toJson(server));
            intent.putExtra(VpnTunnelService.EXTRA_AUTO_CONNECT, true);  // ← Флаг авто-подключения

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }

            FileLogger.i(W, "VPN запущен (авто-подключение)");
        }

        // ════════════════════════════════════════════════════════════════
        // ← НОВЫЙ МЕТОД: Авто-отключение VPN
        // ════════════════════════════════════════════════════════════════

        private static void autoDisconnectVpn(Context ctx) {
            Intent intent = new Intent(ctx, VpnTunnelService.class);
            intent.setAction(VpnTunnelService.ACTION_DISCONNECT);
            ctx.startService(intent);

            FileLogger.i(W, "VPN остановлен (авто-отключение)");
        }
    }
}