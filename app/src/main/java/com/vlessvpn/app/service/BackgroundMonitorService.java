package com.vlessvpn.app.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.util.Log;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.network.ConfigDownloader;
import com.vlessvpn.app.network.ServerTester;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BackgroundMonitorService — загрузка и тестирование серверов.
 *
 * Логика:
 * 1. Пробуем скачать списки если есть реальный интернет (TCP 8.8.8.8:53 за 2 сек)
 * 2. Если скачать не удалось — используем кеш из БД
 * 3. Тестируем серверы параллельно (10 потоков), bindSocket к CELLULAR/WiFi
 * 4. Сохраняем топ-10 в БД
 */
public class BackgroundMonitorService extends Service {

    private static final String TAG      = "BackgroundMonitor";
    private static final String WORK_TAG = "server_test";

    public static void schedule(Context ctx, int intervalHours) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
            TestWorker.class, intervalHours, TimeUnit.HOURS)
            .addTag(WORK_TAG).build();
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK_TAG, ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    public static void runImmediately(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(TestWorker.class)
            .addTag(WORK_TAG + "_once").build();
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(WORK_TAG + "_once", ExistingWorkPolicy.REPLACE, req);
    }

    // ════════════════════════════════════════════════════════════════
    //  TestWorker
    // ════════════════════════════════════════════════════════════════

    public static class TestWorker extends Worker {

        private static final String W = "TestWorker";

        public TestWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            Context ctx  = getApplicationContext();
            ServerRepository repo = new ServerRepository(ctx);
            ConfigDownloader dl   = new ConfigDownloader();

            StatusBus.post("🔄 Запуск проверки серверов...");
            FileLogger.i(W, "=== doWork START ===");

            // ── Шаг 1: Скачиваем если есть интернет ────────────────────────
            String[] urls = repo.getConfigUrls();
            int cachedCount = repo.getAllServersSync().size();

            if (hasRealInternet()) {
                StatusBus.post("📥 Обновляем списки (" + urls.length + " источника)...");
                for (String url : urls) {
                    if (url == null || url.trim().isEmpty()) continue;
                    try {
                        List<VlessServer> fresh = dl.download(url.trim(), url.trim());
                        if (!fresh.isEmpty()) {
                            repo.deleteBySourceUrl(url.trim());
                            repo.insertAll(fresh);
                            repo.markUpdated();  // ← фиксируем время обновления
                            FileLogger.i(W, "Загружено " + fresh.size() + " серверов с " + url);
                            StatusBus.post("📋 Загружено " + fresh.size() + " серверов");
                        }
                    } catch (Exception e) {
                        FileLogger.w(W, "Ошибка загрузки " + url + ": " + e.getMessage());
                        StatusBus.post("⚠️ Ошибка загрузки — используем кеш (" + cachedCount + ")");
                    }
                }
            } else {
                FileLogger.i(W, "Нет интернета — используем кеш (" + cachedCount + " серверов)");
                StatusBus.post("📋 Нет связи — тестируем кеш (" + cachedCount + " серверов)");
            }

            // ── Шаг 2: Берём список для тестирования ───────────────────────
            List<VlessServer> toTest = repo.getAllServersSync();
            if (toTest.isEmpty()) {
                StatusBus.done("⚠️ Нет серверов. Подключитесь к интернету и обновите список.");
                return Result.retry();
            }

            // Сбрасываем флаги перед новым тестом
            repo.resetAllTestTimesSync();

            FileLogger.i(W, "Тестируем " + toTest.size() + " серверов...");
            StatusBus.post("🔍 Тестируем " + toTest.size() + " серверов...");

            // ── Шаг 3: Параллельное тестирование 10 потоков ────────────────
            final int THREADS = 10;
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            // Порты для measureDelay — каждый поток свой порт чтобы не конфликтовали
            final java.util.concurrent.atomic.AtomicInteger portCounter = new java.util.concurrent.atomic.AtomicInteger(10900);
            CountDownLatch latch = new CountDownLatch(toTest.size());
            AtomicInteger done   = new AtomicInteger(0);
            AtomicInteger ok     = new AtomicInteger(0);
            int total            = toTest.size();

            for (VlessServer server : toTest) {
                if (isStopped()) { latch.countDown(); continue; }
                pool.submit(() -> {
                    try {
                        // ── Шаг 1: TCP пинг (быстро — фильтр недоступных) ──
                        StatusBus.postServer(server.id, server.host, "pinging", -1, false, "TCP...");
                        ServerTester.TestResult tcp = ServerTester.tcpTest(ctx, server);
                        int d = done.incrementAndGet();

                        if (!tcp.trafficOk) {
                            // TCP недоступен — сервер точно не работает
                            server.trafficOk    = false;
                            server.lastTestedAt = System.currentTimeMillis();
                            repo.updateServer(server);
                            StatusBus.postServer(server.id, server.host, "fail", -1, false, "✗ TCP");
                            StatusBus.post("⏳ " + d + "/" + total
                                + "  ✓" + ok.get() + " рабочих\n" + server.host + " — недоступен");
                        } else {
                            // ── Шаг 2: measureDelay — реальный VLESS/Reality тест ──
                            StatusBus.postServer(server.id, server.host, "testing", tcp.pingMs, false,
                                "TCP " + tcp.pingMs + "ms → VLESS...");
                            int testPort = (portCounter.getAndIncrement() % 100) + 10900;
                            String testCfg = com.vlessvpn.app.core.V2RayConfigBuilder.buildForTest(server, testPort);
                            long vlessDelay = com.vlessvpn.app.core.V2RayManager.measureDelay(ctx, testCfg);
                            FileLogger.d(W, server.host + " TCP=" + tcp.pingMs + "ms VLESS=" + vlessDelay + "ms");

                            if (vlessDelay > 0) {
                                server.pingMs       = vlessDelay;
                                server.trafficOk    = true;
                                server.lastTestedAt = System.currentTimeMillis();
                                repo.updateServer(server);
                                int o = ok.incrementAndGet();
                                StatusBus.postServer(server.id, server.host, "ok", vlessDelay, true,
                                    "✓ VLESS " + vlessDelay + "ms");
                                StatusBus.post("⏳ " + d + "/" + total
                                    + "  ✓" + o + " рабочих\n" + server.host + " — VLESS " + vlessDelay + "ms");
                            } else {
                                // TCP OK но VLESS не отвечает — порт есть, протокол нет
                                server.trafficOk    = false;
                                server.pingMs       = tcp.pingMs; // сохраняем TCP пинг для инфо
                                server.lastTestedAt = System.currentTimeMillis();
                                repo.updateServer(server);
                                StatusBus.postServer(server.id, server.host, "fail", tcp.pingMs, false,
                                    "✗ VLESS (TCP " + tcp.pingMs + "ms)");
                                StatusBus.post("⏳ " + d + "/" + total
                                    + "  ✓" + ok.get() + " рабочих\n" + server.host
                                    + " — TCP OK но VLESS нет");
                            }
                        }
                    } catch (Exception e) {
                        FileLogger.e(W, "Ошибка теста " + server.host, e);
                        done.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try { latch.await(5, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}
            pool.shutdownNow();

            int okCount = ok.get();
            FileLogger.i(W, "=== doWork DONE: " + okCount + "/" + total + " рабочих ===");
            StatusBus.done("✅ Готово: " + okCount + " рабочих из " + total);
            return Result.success();
        }

        /**
         * Проверяет реальный интернет через TCP connect к 8.8.8.8:53 за 2 сек.
         * NET_CAPABILITY_VALIDATED может быть установлен даже без доступа к серверу.
         */
        private boolean hasRealInternet() {
            // Сначала быстрая проверка флагов системы
            Context ctx = getApplicationContext();
            ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            boolean hasValidated = false;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    hasValidated = true;
                    break;
                }
            }
            if (!hasValidated) {
                FileLogger.i("TestWorker", "hasRealInternet: нет VALIDATED сети");
                return false;
            }

            // TCP connect к 8.8.8.8:53 — реальная проверка
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("8.8.8.8", 53), 2000);
                FileLogger.i("TestWorker", "hasRealInternet: TCP 8.8.8.8:53 OK");
                return true;
            } catch (Exception e) {
                FileLogger.w("TestWorker", "hasRealInternet: TCP 8.8.8.8:53 FAIL: " + e.getMessage());
                return false;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Service
    // ════════════════════════════════════════════════════════════════

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ServerRepository repo = new ServerRepository(this);
        schedule(this, repo.getUpdateIntervalHours());
        runImmediately(this);
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
