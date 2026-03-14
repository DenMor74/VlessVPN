package com.vlessvpn.app.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.StatusBus;

import java.util.List;

public class MainViewModel extends AndroidViewModel {

    private final ServerRepository repository;

    // Список топ серверов из БД
    public final LiveData<List<VlessServer>> topServers;

    // VPN статус
    private final MutableLiveData<Boolean> connectionStatus = new MutableLiveData<>(false);
    public final LiveData<Boolean> isConnected = connectionStatus;

    private final MutableLiveData<VlessServer> currentServer = new MutableLiveData<>(null);
    public final LiveData<VlessServer> connectedServer = currentServer;

    // ── Прогресс тестирования ───────────────────────────────

    /** Заголовок текущего этапа: "📥 Скачиваем...", "🔍 Тестируем 5/20" и т.д. */
    private final MutableLiveData<String> progressTitle = new MutableLiveData<>("");
    public final LiveData<String> getProgressTitle() { return progressTitle; }

    /** Детальная строка: имя сервера, ошибка и т.д. */
    private final MutableLiveData<String> progressDetail = new MutableLiveData<>("");
    public final LiveData<String> getProgressDetail() { return progressDetail; }

    /** true = процесс идёт (показываем спиннер и панель) */
    private final MutableLiveData<Boolean> isWorking = new MutableLiveData<>(false);
    public final LiveData<Boolean> getIsWorking() { return isWorking; }

    /** Прогресс 0..100 для горизонтальной полоски */
    private final MutableLiveData<Integer> progressPercent = new MutableLiveData<>(0);
    public final LiveData<Integer> getProgressPercent() { return progressPercent; }

    /** Статистика: [total, ok, fail] */
    private final MutableLiveData<int[]> progressCounts = new MutableLiveData<>(new int[]{0,0,0});
    public final LiveData<int[]> getProgressCounts() { return progressCounts; }

    /** Последнее завершённое сообщение (показывается когда панель скрыта) */
    private final MutableLiveData<String> lastStatusMessage = new MutableLiveData<>("");
    public final LiveData<String> getLastStatusMessage() { return lastStatusMessage; }

    /** События по конкретным серверам — для обновления строк в списке */
    public LiveData<StatusBus.ServerEvent> getServerEvents() { return StatusBus.getServerEvents(); }

    public MainViewModel(@NonNull Application application) {
        super(application);
        repository = new ServerRepository(application);
        topServers = repository.getTopServersLiveData();

        // Сканирование при запуске (если включено в настройках)
        if (repository.isScanOnStart()) {
            forceRefreshServers();
        }

        // Подписываемся на StatusBus — получаем обновления из Worker
        StatusBus.get().observeForever(event -> {
            if (event == null) return;
            parseAndUpdate(event);
        });
    }

    /**
     * Разбирает сообщение из StatusBus и обновляет все LiveData.
     * Формат сообщений от TestWorker:
     *   "📥 Скачиваем списки с 2 источников..."
     *   "📋 Загружено 150 серверов с\nhttp://..."
     *   "✅ 5/20  ✓3 рабочих\nhost.com — 120ms"
     *   "⏳ 5/20  ✓3 рабочих\nhost.com — недоступен"
     *   "📊 Тест завершён: ✓ 8 рабочих / ✗ 12 недоступных"
     *   "✅ Готово! В базе 8 рабочих серверов"
     */
    private void parseAndUpdate(StatusBus.StatusEvent event) {
        String msg = event.message;
        boolean running = event.isRunning;

        isWorking.postValue(running);

        if (!running) {
            // Процесс завершён
            refreshInProgress = false;
            lastStatusMessage.postValue(msg);
            progressTitle.postValue(msg);
            progressDetail.postValue("");
            progressPercent.postValue(100);
            return;
        }

        // Разбираем прогресс вида "✅ 5/20  ✓3 рабочих\nhost..."
        int tested = 0, total = 0, ok = 0, fail = 0;
        if (msg.contains("/")) {
            try {
                // Ищем паттерн "N/M"
                String[] parts = msg.split("[\\s\\n]");
                for (String p : parts) {
                    if (p.matches("\\d+/\\d+")) {
                        String[] nm = p.split("/");
                        tested = Integer.parseInt(nm[0]);
                        total  = Integer.parseInt(nm[1]);
                    }
                    // "✓3" или "✓ 3"
                    if (p.startsWith("✓") && p.length() > 1) {
                        try { ok = Integer.parseInt(p.substring(1)); } catch (Exception ignored) {}
                    }
                }
                fail = tested - ok;
                if (total > 0) {
                    progressPercent.postValue(tested * 100 / total);
                    progressCounts.postValue(new int[]{total, ok, fail});
                }
            } catch (Exception ignored) {}
        }

        // Разделяем заголовок и детали по \n
        if (msg.contains("\n")) {
            String[] lines = msg.split("\n", 2);
            progressTitle.postValue(lines[0]);
            progressDetail.postValue(lines[1]);
        } else {
            progressTitle.postValue(msg);
            progressDetail.postValue("");
        }
    }

    public void refreshVpnStatus() {
        // postValue работает из любого потока (в т.ч. из BroadcastReceiver)
        connectionStatus.postValue(VpnTunnelService.isRunning);
        currentServer.postValue(VpnTunnelService.connectedServer);
    }

    // Защита от двойного запуска
    private boolean refreshInProgress = false;

    public void forceRefreshServers() {
        if (refreshInProgress) return;
        refreshInProgress = true;

        // Сбрасываем время → isUpdateNeeded() вернёт true → серверы скачаются заново
        repository.resetUpdateTime();

        isWorking.setValue(true);
        progressTitle.setValue("🔄 Запуск проверки...");
        progressDetail.setValue("");
        progressPercent.setValue(0);
        progressCounts.setValue(new int[]{0, 0, 0});
        com.vlessvpn.app.service.BackgroundMonitorService.runImmediately(getApplication());
    }

    public ServerRepository getRepository() { return repository; }

    /** Количество серверов в топе из настроек */
    public int getTopCount() { return repository.getTopCount(); }
}
