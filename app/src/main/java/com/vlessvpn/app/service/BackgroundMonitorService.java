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
import java.util.Collections;
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
                        // Сохраняем текущий подключённый сервер чтобы не потерять его
                        VlessServer connectedServer = VpnTunnelService.connectedServer;

                        // Удаляем старые и вставляем новые (бесшовная замена)
                        repo.deleteBySourceUrlSync(url.trim());
                        repo.insertAll(fresh);

                        // Если VPN активен и текущего сервера нет в новом списке —
                        // восстанавливаем его (помечаем рабочим чтобы не пропал с экрана)
                        if (connectedServer != null && VpnTunnelService.isRunning) {
                            boolean foundInFresh = false;
                            for (VlessServer s : fresh)
                                if (s.id.equals(connectedServer.id)) { foundInFresh = true; break; }
                            if (!foundInFresh) {
                                connectedServer.sourceUrl = url.trim();
                                repo.insertAll(java.util.Collections.singletonList(connectedServer));
                                FileLogger.i(W, "Текущий сервер сохранён: " + connectedServer.host);
                            }
                        }

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

    // ════════════════════════════════════════════════════════════════════════
    // ScanWorker — КОНВЕЙЕР (Fast TCP Ping + Multiplex VLESS HTTP Test)
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

            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VlessVPN::ScanWorker");
            wakeLock.acquire(5 * 60 * 1000L); // 5 минут с запасом

            try {
                return doPipelineScan(ctx, repo);
            } finally {
                if (wakeLock.isHeld()) wakeLock.release();
            }
        }

        private Result doPipelineScan(Context ctx, ServerRepository repo) {
            List<VlessServer> allServers = repo.getAllServersSync();
            if (allServers.isEmpty()) {
                StatusBus.done(ctx, "⚠️ Нет серверов в базе");
                return Result.retry();
            }

            int total = allServers.size();
            StatusBus.post(ctx, "🔍 Тестируем " + total + " серверов...", true);
            StatusBus.setWorking(ctx, true);

            AtomicInteger done = new AtomicInteger(0);
            AtomicInteger ok = new AtomicInteger(0);

            // Получаем мобильную сеть для "честного" теста через DPI провайдера
            Network cellularNet = ServerTester.getCellularNetwork(ctx);
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);

            if (cellularNet != null) {
                cm.bindProcessToNetwork(cellularNet); // Привязываем ВЕСЬ процесс воркера к LTE
            } else {
                FileLogger.w(W, "LTE не найден, тест пойдет через активную сеть (WiFi)");
            }

            try {
                // ====================================================================
                // ЭТАП 1: Быстрый параллельный TCP Ping (отсев мертвецов)
                // ====================================================================
                List<VlessServer> survivedPing = new java.util.ArrayList<>();
                ExecutorService pingPool = Executors.newFixedThreadPool(30); // Широкий пул для скорости
                CountDownLatch pingLatch = new CountDownLatch(total);

                for (VlessServer server : allServers) {
                    pingPool.submit(() -> {
                        try {
                            ServerTester.TestResult tcp = ServerTester.tcpTest(ctx, server);
                            int curr = done.incrementAndGet();

                            if (tcp.trafficOk) {
                                server.tcpPingMs = (int) tcp.pingMs;
                                synchronized (survivedPing) { survivedPing.add(server); }
                                StatusBus.postServer(ctx, server.id, server.host, "pinging", tcp.pingMs, false, "TCP OK");
                            } else {
                                server.trafficOk = false;
                                server.pingMs = -1;
                                server.tcpPingMs = (int) tcp.pingMs;
                                server.lastTestedAt = System.currentTimeMillis();
                                repo.updateServerSync(server);
                                StatusBus.postServer(ctx, server.id, server.host, "fail", -1, false, "✗ TCP");
                            }
                            StatusBus.setProgress(ctx, (curr * 50) / total); // Первая половина прогресс-бара
                        } finally {
                            pingLatch.countDown();
                        }
                    });
                }

                pingLatch.await(30, TimeUnit.SECONDS); // Жесткий таймаут на пинг
                pingPool.shutdownNow();

                FileLogger.i(W, "Прошли TCP Ping: " + survivedPing.size() + " из " + total);

                // ====================================================================
                // ЭТАП 2: Глубокий тест выживших (Мультиплексирование V2Ray)
                // ====================================================================
                if (!survivedPing.isEmpty()) {
                    int basePort = 10800;

                    // 1. Генерируем мега-конфиг
                    String multiplexConfig = V2RayConfigBuilder.buildMultiplexTestConfig(survivedPing, basePort);

                    // 2. Тихо запускаем одно ядро V2Ray без TUN интерфейса
                    boolean coreStarted = V2RayManager.startSilentMultiplexInstance(ctx, multiplexConfig);

                    if (coreStarted) {
                        ExecutorService httpPool = Executors.newFixedThreadPool(20);
                        CountDownLatch httpLatch = new CountDownLatch(survivedPing.size());

                        // 3. Параллельно стучимся в открытые локальные порты
                        for (int i = 0; i < survivedPing.size(); i++) {
                            final VlessServer server = survivedPing.get(i);
                            final int localProxyPort = basePort + i;
                            final int currentIndex = i;

                            httpPool.submit(() -> {
                                try {
                                    long startTime = System.currentTimeMillis();
                                    boolean success = testHttpThroughProxy(localProxyPort);
                                    long vlessDelay = System.currentTimeMillis() - startTime;

                                    server.lastTestedAt = System.currentTimeMillis();
                                    if (success) {
                                        server.pingMs = vlessDelay;
                                        server.trafficOk = true;
                                        ok.incrementAndGet();
                                        StatusBus.postServer(ctx, server.id, server.host, "ok", vlessDelay, true, "✓ VLESS " + vlessDelay + "ms");
                                    } else {
                                        server.trafficOk = false;
                                        server.pingMs = -1;
                                        StatusBus.postServer(ctx, server.id, server.host, "fail", server.tcpPingMs, false, "✗ DPI Блок");
                                    }
                                    repo.updateServerSync(server);

                                    int currProgress = 50 + ((currentIndex + 1) * 50) / survivedPing.size();
                                    StatusBus.setProgress(ctx, currProgress);
                                    StatusBus.post(ctx, "🔍 Тест трафика (✓ " + ok.get() + " рабочих)", true);

                                } finally {
                                    httpLatch.countDown();
                                }
                            });
                        }

                        httpLatch.await(60, TimeUnit.SECONDS);
                        httpPool.shutdownNow();

                        // 4. Убиваем ядро
                        V2RayManager.stopSilentMultiplexInstance();
                    } else {
                        FileLogger.e(W, "Не удалось запустить Мультиплекс V2Ray. Серверы помечены как нерабочие.");
                    }
                }

            } catch (Exception e) {
                FileLogger.e(W, "Критическая ошибка конвейера: " + e.getMessage());
            } finally {
                // Возвращаем сеть по умолчанию
                cm.bindProcessToNetwork(null);
            }

            repo.markScanned();

            int finalOk = ok.get();
            int finalFail = total - finalOk;
            StatusBus.setWorking(ctx, false);
            StatusBus.done(ctx, "✅ " + finalOk + " рабочих из " + total + " (✗ " + finalFail + ")");

            checkAutoConnect(ctx, repo);
            FileLogger.i(W, "=== Завершено: " + finalOk + "/" + total + " ===");
            return Result.success();
        }

        /**
         * Делает HTTP GET запрос к легковесному эндпоинту через локальный HTTP прокси.
         */
        private boolean testHttpThroughProxy(int proxyPort) {
            java.net.HttpURLConnection conn = null;
            try {
                java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress("127.0.0.1", proxyPort));
                // Используем gstatic (Google) - генерирует код 204 без тела. Самый быстрый способ.
                java.net.URL url = new java.net.URL("https://google.com");
                conn = (java.net.HttpURLConnection) url.openConnection(proxy);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestMethod("HEAD");
                conn.setUseCaches(false);

                int code = conn.getResponseCode();
                return code >= 200 && code < 400; // Успешно прошел!

            } catch (Exception e) {
                return false;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private static void checkAutoConnect(Context ctx, ServerRepository repo) {
            // ... оставляете ваш существующий код метода checkAutoConnect без изменений ...
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
