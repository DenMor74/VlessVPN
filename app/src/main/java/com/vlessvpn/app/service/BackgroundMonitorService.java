package com.vlessvpn.app.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class BackgroundMonitorService extends Service {

    private static final String TAG            = "BackgroundMonitor";
    private static final String WORK_DOWNLOAD  = "server_download";
    private static final String WORK_SCAN      = "server_scan";
    private static long lastFinalLogTime = 0;   // защита от двойного финального лога

    // Флаги пропущенных задач
    private static final String PREFS_MISSED = "monitor_missed_prefs";
    private static final String KEY_MISSED_DL = "missed_download";
    private static final String KEY_MISSED_SCAN = "missed_scan";

    private static String[] testUrls = {
            "http://cp.cloudflare.com/generate_204",
            "https://detectportal.firefox.com/success.txt",
            "http://www.msftconnecttest.com/connecttest.txt"
    };

    // ── Планировщики ────────────────────────────────────────────────────────

    public static void scheduleDownload(Context ctx, int intervalHours) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_DOWNLOAD,
                ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(DownloadWorker.class, intervalHours, TimeUnit.HOURS)
                        // НОВОЕ: Требуем интернет. Если интернета нет, система подождет его появления и сразу запустит скачивание
                        .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .addTag(WORK_DOWNLOAD).build());
    }

    public static void scheduleScan(Context ctx, int intervalMinutes) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_SCAN,
                ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(ScanWorker.class, intervalMinutes, TimeUnit.MINUTES)
                        .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .addTag(WORK_SCAN).build());
    }

    public static void runDownloadNow(Context ctx) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_DOWNLOAD + "_manual", ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(DownloadWorker.class).build());
    }

    public static void runScanNow(Context ctx) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_SCAN + "_manual", ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(ScanWorker.class).build());
    }

    // ════════════════════════════════════════════════════════════════════════
    // НОВОЕ: Обработчик пропущенных задач (Вызывать при отключении VPN)
    // ════════════════════════════════════════════════════════════════════════
    public static void scheduleCatchUpTasks(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_MISSED, Context.MODE_PRIVATE);
        boolean missedDl = prefs.getBoolean(KEY_MISSED_DL, false);
        boolean missedScan = prefs.getBoolean(KEY_MISSED_SCAN, false);

        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();

        if (missedDl) {
            FileLogger.i(TAG, "VPN отключен. Запланировано пропущенное СКАЧИВАНИЕ через 5 минут.");
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                    WORK_DOWNLOAD + "_catchup",
                    ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(DownloadWorker.class)
                            .setInitialDelay(5, TimeUnit.MINUTES) // Ждем 5 минут (защита от переподключений)
                            .setConstraints(constraints)
                            .build()
            );
        } else if (missedScan) {
            FileLogger.i(TAG, "VPN отключен. Запланировано пропущенное СКАНИРОВАНИЕ через 5 минут.");
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                    WORK_SCAN + "_catchup",
                    ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(ScanWorker.class)
                            .setInitialDelay(5, TimeUnit.MINUTES)
                            .setConstraints(constraints)
                            .build()
            );
        }
    }

    private static void setMissedFlag(Context ctx, String key, boolean value) {
        ctx.getSharedPreferences(PREFS_MISSED, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ServerRepository repo = new ServerRepository(this);
        scheduleDownload(this, repo.getUpdateIntervalHours());
        scheduleScan(this, repo.getScanIntervalMinutes());
        stopSelf();
        return START_NOT_STICKY;
    }

    public static void runWhitelistDownloadNow(Context ctx) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_DOWNLOAD + "_whitelist",
                ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(WhitelistDownloadWorker.class).build()
        );
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ════════════════════════════════════════════════════════════════════════
    // DownloadWorker
    // ════════════════════════════════════════════════════════════════════════

    public static class DownloadWorker extends Worker {
        private static final String W = "DownloadWorker";

        public DownloadWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            Context ctx = getApplicationContext();
            ServerRepository repo = new ServerRepository(ctx);

            if (VpnTunnelService.isRunning) {
                FileLogger.w(TAG, "Блокировка скачивания листов: VPN активен! Задача отложена до отключения.");
                setMissedFlag(ctx, KEY_MISSED_DL, true); // Запоминаем, что пропустили
                return Result.success();
            }

            // Если мы дошли сюда, VPN выключен. Сбрасываем флаг пропуска.
            setMissedFlag(ctx, KEY_MISSED_DL, false);

            if (repo.isNightTime()) {
                FileLogger.i(W, "Ночное время — пропуск скачивания");
                StatusBus.post(ctx, "🌙 Ночной режим — скачивание отложено", false);
                return Result.success();
            }

            if (!repo.isUpdateNeeded()) {
                FileLogger.i(W, "Обновление ещё не нужно — пропускаем скачивание");
                if (repo.getWorkingCount() == 0) {
                    FileLogger.i(W, "Нет рабочих серверов — запускаем сканирование");
                    runScanNow(ctx);
                }
                return Result.success();
            }

            if (!hasRealInternet()) {
                StatusBus.post(ctx, "⚠️ Нет интернета — скачивание пропущено", false);
                FileLogger.i(W, "⚠️ Нет интернета — скачивание пропущено");
                return Result.retry();
            }

            StatusBus.post(ctx, "📥 Скачиваем новые списки...", true);

            List<com.vlessvpn.app.model.ConfigUrlItem> urlItems = repo.getConfigUrlItems();

            int activeCount = 0;
            for (com.vlessvpn.app.model.ConfigUrlItem item : urlItems) {
                if (item.isEnabled()) activeCount++;
            }
            FileLogger.i(W, "Всего URL: " + urlItems.size() + ", Активных (с галочкой): " + activeCount);

            if (activeCount > 0) {
                FileLogger.i(W, "Очищаем базу данных перед загрузкой...");
                repo.deleteAllServersSync();
                StatusBus.post(ctx, "🗑️ Очистка базы...", true);
            }

            int totalDownloaded = 0;

            for (int i = 0; i < urlItems.size(); i++) {
                com.vlessvpn.app.model.ConfigUrlItem item = urlItems.get(i);

                if (!item.isEnabled()) {
                    FileLogger.i(W, "  [✗] Пропущен (выключен): " + item.getUrl());
                    continue;
                }

                String url = item.getUrl();
                if (url == null || url.trim().isEmpty()) {
                    FileLogger.w(W, "  [✗] Пропущен (пустой URL)");
                    continue;
                }

                url = url.trim();
                FileLogger.i(W, "[✓] Загрузка " + (i + 1) + "/" + urlItems.size() + ": " + url);
                StatusBus.post(ctx, "📥 Загрузка " + (i + 1) + "/" + activeCount, true);

                try {
                    String filterSNI = null;
                    String cleanUrl = url;
                    if (url.contains("?filter=")) {
                        String[] parts = url.split("\\?filter=");
                        if (parts.length > 1) {
                            filterSNI = parts[1].split("&")[0].trim();
                            cleanUrl = parts[0];
                        }
                    }

                    List<VlessServer> fresh = new ConfigDownloader().download(ctx, cleanUrl, url, filterSNI);

                    if (!fresh.isEmpty()) {
                        VlessServer connectedServer = VpnTunnelService.connectedServer;

                        repo.deleteBySourceUrlSync(url);
                        repo.insertAll(fresh);

                        if (connectedServer != null && VpnTunnelService.isRunning) {
                            boolean foundInFresh = false;
                            for (VlessServer s : fresh)
                                if (s.id.equals(connectedServer.id)) { foundInFresh = true; break; }
                            if (!foundInFresh) {
                                connectedServer.sourceUrl = url;
                                repo.insertAll(java.util.Collections.singletonList(connectedServer));
                            }
                        }

                        totalDownloaded += fresh.size();

                        String logMsg = "Загружено " + fresh.size() + " серверов с " + url;
                        if (filterSNI != null) {
                            logMsg = "✅ Добавлено " + fresh.size() + " серверов " + filterSNI + " SNI из " + url;
                        }
                        FileLogger.i(W, logMsg);
                    }
                } catch (Exception e) {
                    FileLogger.w(W, "Ошибка загрузки " + url + ": " + e.getMessage());
                }
            }

            repo.markUpdated();
            StatusBus.done(ctx, "✅ Загружено " + totalDownloaded + " серверов");

            if (totalDownloaded > 0) {
                FileLogger.i(W, "Скачано " + totalDownloaded + " серверов → запускаем сканирование");
                StatusBus.post(ctx, "🔍 Запускаем проверку серверов...", true);
                repo.resetAllTestTimesSync();
                runScanNow(ctx);
            } else {
                FileLogger.w(W, "Ничего не скачано — сканирование пропущено");
                StatusBus.done(ctx, "⚠️ Нет новых серверов");
            }

            int realInDB = repo.getAllServersSync().size();
            FileLogger.i(W, "✅ Серверов после дедупликации: " + realInDB
                    + " (из " + totalDownloaded + " скачанных — дубли: " + (totalDownloaded - realInDB) + ")");

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
    // ScanWorker
    // ════════════════════════════════════════════════════════════════════════

    public static class ScanWorker extends Worker {
        private static final String W = "ScanWorker";

        public ScanWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            Context ctx = getApplicationContext();
            ServerRepository repo = new ServerRepository(ctx);

            if (VpnTunnelService.isRunning) {
                FileLogger.i(W, "VPN работает — пропуск сканирования. Задача отложена до отключения.");
                StatusBus.post(ctx, "VPN работает — пропуск сканирования", false);
                setMissedFlag(ctx, KEY_MISSED_SCAN, true); // Запоминаем пропуск
                return Result.success();
            }

            // VPN выключен, сбрасываем флаг пропуска
            setMissedFlag(ctx, KEY_MISSED_SCAN, false);

            if (repo.isNightTime()) {
                FileLogger.i(W, "Ночное время — пропуск сканирования");
                StatusBus.post(ctx, "🌙 Ночной режим — сканирование отложено", false);
                return Result.success();
            }

            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VlessVPN::ScanWorker");
            wakeLock.acquire(5 * 60 * 1000L); // 5 минут с запасом

            try {
                return doPipelineScan(ctx, repo);
            } finally {
                if (wakeLock.isHeld()) wakeLock.release();
            }
        }

        private void waitForProxyToStart(int testPort, int maxWaitMs) {
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < maxWaitMs) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", testPort), 200);
                    return;
                } catch (Exception e) {
                    try { Thread.sleep(50); } catch (InterruptedException ignore) {}
                }
            }
            FileLogger.w(W, "Таймаут ожидания старта локального прокси на порту " + testPort);
        }

        private Result doPipelineScan(Context ctx, ServerRepository repo) {
            if (VpnTunnelService.isRunning) {
                FileLogger.w(TAG, "Блокировка тестирования листа: VPN уже активен!");
                setMissedFlag(ctx, KEY_MISSED_SCAN, true);
                return Result.retry();
            }
            List<VlessServer> allServers = repo.getAllServersSync();
            if (allServers.isEmpty()) {
                StatusBus.done(ctx, "⚠️ Нет серверов в базе");
                return Result.retry();
            }

            int total = allServers.size();
            StatusBus.post(ctx, "🔍 Подготовка к тесту " + total + " серверов...", true);
            StatusBus.setWorking(ctx, true);

            AtomicInteger tcpDone = new AtomicInteger(0);
            AtomicInteger httpDone = new AtomicInteger(0);
            AtomicInteger ok = new AtomicInteger(0);

            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network cellularNet = null;
            ConnectivityManager.NetworkCallback cellCallback = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    NetworkRequest cellReq = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();

                    CountDownLatch latch = new CountDownLatch(1);
                    Network[] foundNet = new Network[1];

                    cellCallback = new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(@NonNull Network network) {
                            if (foundNet[0] == null) {
                                foundNet[0] = network;
                                latch.countDown();
                            }
                        }
                    };

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        cm.requestNetwork(cellReq, cellCallback, 4000);
                    } else {
                        cm.requestNetwork(cellReq, cellCallback);
                    }

                    if (latch.await(5, TimeUnit.SECONDS)) {
                        cellularNet = foundNet[0];
                    } else {
                        cm.unregisterNetworkCallback(cellCallback);
                        cellCallback = null;
                    }
                } catch (Exception e) {
                    FileLogger.w(W, "Ошибка запроса LTE сети: " + e.getMessage());
                }
            }

            if (cellularNet == null) {
                cellularNet = ServerTester.getCellularNetwork(ctx);
            }

            if (cellularNet != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.bindProcessToNetwork(cellularNet);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ConnectivityManager.setProcessDefaultNetwork(cellularNet);
                }
            }

            try {
                // ====================================================================
                // ЭТАП 1: МАССОВЫЙ ПАРАЛЛЕЛЬНЫЙ ПИНГ
                // ====================================================================
                final List<VlessServer> tcpSurvived = new java.util.ArrayList<>();
                int poolSize = Math.min(150, total);
                ExecutorService pingPool = Executors.newFixedThreadPool(poolSize);
                CountDownLatch pingLatch = new CountDownLatch(total);
                final Network finalCellularNet = cellularNet;

                java.util.concurrent.atomic.AtomicBoolean phase1Active = new java.util.concurrent.atomic.AtomicBoolean(true);

                for (VlessServer server : allServers) {
                    pingPool.submit(() -> {
                        if (!phase1Active.get() || Thread.currentThread().isInterrupted()) {
                            pingLatch.countDown();
                            return;
                        }

                        try {
                            ServerTester.TestResult tcp = ServerTester.tcpTest(ctx, server, finalCellularNet);
                            int currTcp = tcpDone.incrementAndGet();

                            if (tcp.trafficOk) {
                                server.tcpPingMs = (int) tcp.pingMs;
                                server.lastTestedAt = System.currentTimeMillis();
                                repo.updateServerSync(server);

                                synchronized (tcpSurvived) { tcpSurvived.add(server); }
                                if (phase1Active.get()) StatusBus.postServer(ctx, server.id, server.host, "pinging", tcp.pingMs, false, "TCP OK");
                            } else {
                                server.trafficOk = false;
                                server.pingMs = -1;
                                server.tcpPingMs = (int) tcp.pingMs;
                                server.lastTestedAt = System.currentTimeMillis();
                                repo.updateServerSync(server);
                                if (phase1Active.get()) StatusBus.postServer(ctx, server.id, server.host, "fail", -1, false, "✗ TCP");
                            }

                            if (phase1Active.get()) {
                                int progress = (currTcp * 50) / total;
                                String msg = "⚡ Пинг TCP: " + currTcp + "/" + total + " (ответили: " + tcpSurvived.size() + ")";
                                StatusBus.postWithProgress(ctx, msg, true, progress);
                            }
                        } catch (Exception e) {
                        } finally {
                            pingLatch.countDown();
                        }
                    });
                }

                int batches = (int) Math.ceil((double) total / poolSize);
                long pingTimeout = Math.min(240, batches * 20L);

                try {
                    boolean finished = pingLatch.await(pingTimeout, TimeUnit.SECONDS);
                    if (!finished) {
                        FileLogger.w(W, "Фаза 1: таймаут (" + pingTimeout + " сек). Осталось задач: " + pingLatch.getCount());
                    }
                } catch (InterruptedException e) {
                    FileLogger.w(W, "Фаза 1 прервана (задача отменена системой)");
                    return Result.success();
                } finally {
                    phase1Active.set(false);
                    pingPool.shutdownNow();
                }

                // ====================================================================
                // ЭТАП 2: БАТЧИ V2RAY
                // ====================================================================
                if (!tcpSurvived.isEmpty() && !Thread.currentThread().isInterrupted()) {
                    List<VlessServer> survivedPing;
                    synchronized (tcpSurvived) {
                        survivedPing = new java.util.ArrayList<>(tcpSurvived);
                    }

                    Collections.sort(survivedPing, Comparator.comparingInt(s -> s.tcpPingMs));

                    final int finalSurvivedSize = survivedPing.size();
                    final int BATCH_SIZE = 100;
                    java.util.concurrent.atomic.AtomicBoolean phase2Active = new java.util.concurrent.atomic.AtomicBoolean(true);

                    for (int startIndex = 0; startIndex < finalSurvivedSize; startIndex += BATCH_SIZE) {
                        if (Thread.currentThread().isInterrupted() || !phase2Active.get()) break;

                        int endIndex = Math.min(startIndex + BATCH_SIZE, finalSurvivedSize);
                        List<VlessServer> currentBatch = survivedPing.subList(startIndex, endIndex);

                        int basePort = 10900;
                        V2RayManager.setTestNetworkForMultiplex(finalCellularNet);

                        try {
                            String multiplexConfig = V2RayConfigBuilder.buildMultiplexTestConfig(currentBatch, basePort);
                            boolean coreStarted = V2RayManager.startSilentMultiplexInstance(ctx, multiplexConfig);

                            if (coreStarted) {
                                waitForProxyToStart(basePort, 2000);

                                ExecutorService httpPool = Executors.newFixedThreadPool(40);
                                ExecutorService fastUrlPool = Executors.newCachedThreadPool();
                                CountDownLatch httpLatch = new CountDownLatch(currentBatch.size());

                                for (int i = 0; i < currentBatch.size(); i++) {
                                    final VlessServer server = currentBatch.get(i);
                                    final int localProxyPort = basePort + i;

                                    httpPool.submit(() -> {
                                        if (!phase2Active.get() || Thread.currentThread().isInterrupted()) {
                                            httpLatch.countDown();
                                            return;
                                        }

                                        try {
                                            long startTime = System.currentTimeMillis();
                                            boolean success = testHttpThroughProxy(localProxyPort, fastUrlPool);
                                            long vlessDelay = System.currentTimeMillis() - startTime;

                                            server.lastTestedAt = System.currentTimeMillis();

                                            if (success) {
                                                server.pingMs = vlessDelay;
                                                server.trafficOk = true;
                                                ok.incrementAndGet();
                                                if (phase2Active.get()) StatusBus.postServer(ctx, server.id, server.host, "ok", vlessDelay, true, "✓ VLESS " + vlessDelay + "ms");
                                            } else {
                                                server.trafficOk = false;
                                                server.pingMs = -1;
                                                if (phase2Active.get()) StatusBus.postServer(ctx, server.id, server.host, "fail", server.tcpPingMs, false, "✗ DPI Блок");
                                            }
                                            repo.updateServerSync(server);

                                            if (phase2Active.get()) {
                                                int currHttp = httpDone.incrementAndGet();
                                                int currProgress = 50 + ((currHttp * 50) / finalSurvivedSize);
                                                String msg = "🔍 Тест VLESS: " + currHttp + "/" + finalSurvivedSize + " (✓ рабочих: " + ok.get() + ")";
                                                StatusBus.postWithProgress(ctx, msg, true, currProgress);
                                            }
                                        } catch (Exception e) {
                                        } finally {
                                            httpLatch.countDown();
                                        }
                                    });
                                }

                                try {
                                    long httpTimeout = Math.max(180, currentBatch.size());
                                    boolean finished = httpLatch.await(httpTimeout, TimeUnit.SECONDS);
                                    if (!finished) {
                                        FileLogger.w(W, "Фаза 2: таймаут (" + httpTimeout + " сек). Осталось задач: " + httpLatch.getCount());
                                    }
                                } catch (InterruptedException e) {
                                    FileLogger.w(W, "Фаза 2 прервана (задача отменена системой)");
                                    phase2Active.set(false);
                                    Thread.currentThread().interrupt();
                                    break;
                                } finally {
                                    httpPool.shutdownNow();
                                    fastUrlPool.shutdownNow();
                                }
                            }
                        } finally {
                            V2RayManager.clearTestNetwork();
                            V2RayManager.stopSilentMultiplexInstance();
                        }
                    }
                    phase2Active.set(false);
                }

            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                FileLogger.e(W, "Критическая ошибка конвейера: " + errMsg);
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.bindProcessToNetwork(null);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ConnectivityManager.setProcessDefaultNetwork(null);
                }

                if (cellCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        cm.unregisterNetworkCallback(cellCallback);
                    } catch (Exception ignore) {}
                }
            }

            repo.markScanned();

            int realTotal   = repo.getAllServersSync().size();
            int realWorking = repo.getWorkingCount();

            long now = System.currentTimeMillis();
            if (now - lastFinalLogTime > 8000) {
                lastFinalLogTime = now;
                StatusBus.setWorking(ctx, false);
                StatusBus.done(ctx, realWorking + " рабочих из " + realTotal + " (FAIL " + (realTotal - realWorking) + ")");
                FileLogger.i(W, "Проверка завершена: " + String.format("%4d", realWorking) + "/" + String.format("%4d", realTotal));
            }

            if (realWorking == 0) {
                enableFallbackServers(ctx, repo, allServers);
            }

            checkAutoConnect(ctx, repo);

            return Result.success();
        }

        private void enableFallbackServers(Context ctx, ServerRepository repo, List<VlessServer> allServers) {
            List<VlessServer> fallbackCandidates = new java.util.ArrayList<>();

            for (VlessServer server : allServers) {
                if (server.tcpPingMs > 0 && server.tcpPingMs < 5000) {
                    fallbackCandidates.add(server);
                }
                if (fallbackCandidates.size() >= 30) break;
            }

            if (fallbackCandidates.isEmpty()) {
                for (int i = 0; i < Math.min(30, allServers.size()); i++) {
                    fallbackCandidates.add(allServers.get(i));
                }
            }

            int enabled = 0;
            for (VlessServer server : fallbackCandidates) {
                server.trafficOk = true;
                server.pingMs = server.tcpPingMs > 0 ? server.tcpPingMs : 9999;
                server.lastTestedAt = System.currentTimeMillis();
                repo.updateServerSync(server);
                enabled++;
            }

            FileLogger.i(W, "✅ Fallback: включено " + enabled + " серверов из " + allServers.size());
            StatusBus.post(ctx, "⚠️ 0 рабочих — включено " + enabled + " серверов из базы", true);
        }

        private boolean testHttpThroughProxy(int proxyPort, ExecutorService fastUrlPool) {
            java.net.Proxy proxy = new java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    new java.net.InetSocketAddress("127.0.0.1", proxyPort));

            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);
            AtomicInteger finishedCount = new AtomicInteger(0);
            int total = testUrls.length;

            for (String urlStr : testUrls) {
                fastUrlPool.submit(() -> {
                    java.net.HttpURLConnection conn = null;
                    try {
                        conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection(proxy);
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);
                        conn.setRequestMethod("HEAD");
                        conn.setUseCaches(false);
                        conn.setInstanceFollowRedirects(false);
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                        int code = conn.getResponseCode();
                        if (code >= 200 && code < 500) {
                            if (success.compareAndSet(false, true)) {
                                latch.countDown();
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
                        if (finishedCount.incrementAndGet() == total && !success.get()) {
                            latch.countDown();
                        }
                    }
                });
            }

            try {
                latch.await(10500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return success.get();
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


    public static class WhitelistDownloadWorker extends Worker {
        private static final String W = "WhitelistDownloadWorker";

        public WhitelistDownloadWorker(Context ctx, WorkerParameters p) {
            super(ctx, p);
        }

        @Override
        public Result doWork() {
            Context ctx = getApplicationContext();
            ServerRepository repo = new ServerRepository(ctx);

            if (!hasRealInternet()) {
                StatusBus.post(ctx, "⚠️ Нет интернета — скачивание пропущено", false);
                return Result.retry();
            }

            StatusBus.post(ctx, "📥 Скачиваем список через белый сервер...", true);

            String[] configUrls = repo.getConfigUrls();

            int totalDownloaded = 0;
            for (int i = 0; i < configUrls.length; i++) {
                String url = configUrls[i];
                if (url == null || url.trim().isEmpty()) continue;

                String wrappedUrl = wrapWithYandexTranslate(url);

                StatusBus.post(ctx, "📥 Загрузка " + (i + 1) + "/" + configUrls.length, true);

                try {
                    List<VlessServer> fresh = new ConfigDownloader().download(ctx, wrappedUrl, url.trim());
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
            StatusBus.done(ctx, "✅ Загружено " + totalDownloaded + " серверов");

            if (totalDownloaded > 0) {
                FileLogger.i(W, "Скачано " + totalDownloaded + " серверов → запускаем сканирование");
                StatusBus.post(ctx, "🔍 Запускаем проверку серверов...", true);
                repo.resetAllTestTimesSync();
                runScanNow(ctx);
            }

            return Result.success();
        }

        private String wrapWithYandexTranslate(String originalUrl) {
            try {
                String cleanUrl = originalUrl.trim().replaceAll("\\r?\\n", "").replaceAll("\\s+", "");

                String encodedUrl = java.net.URLEncoder.encode(cleanUrl, "UTF-8");
                return "https://translate.yandex.ru/translate?url=" + encodedUrl + "&lang=de-de";
            } catch (Exception e) {
                FileLogger.e(W, "Ошибка кодирования URL: " + e.getMessage());
                return originalUrl;
            }
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
}