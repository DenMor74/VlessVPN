package com.vlessvpn.app.ui;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;
import com.vlessvpn.app.util.StatusBus;

import java.util.List;

public class MainViewModel extends AndroidViewModel {

    // ════════════════════════════════════════════════════════════════════════
    // ПУБЛИЧНЫЕ ПОЛЯ (прямой доступ из MainActivity)
    // ════════════════════════════════════════════════════════════════════════

    public final MutableLiveData<Boolean> isConnected = new MutableLiveData<>();
    public final MutableLiveData<VlessServer> connectedServer = new MutableLiveData<>();
    public final MutableLiveData<List<VlessServer>> topServers = new MutableLiveData<>();

    private final ServerRepository repository;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public MainViewModel(@NonNull Application application) {
        super(application);
        repository = new ServerRepository(application);

        // ════════════════════════════════════════════════════════════════════════
        // Подписка на обновление списка серверов из БД
        // ════════════════════════════════════════════════════════════════════════
        repository.getTopServersLiveData().observeForever(servers -> {
            if (servers != null) {
                FileLogger.d("MainViewModel", "Получено из БД: " + servers.size() + " серверов");
                topServers.postValue(servers);
            }
        });

        // ════════════════════════════════════════════════════════════════════════
        // ← ВАЖНО: Подписка на StatusBus для обновления статуса VPN
        // (это нужно для авто-подключения при отключении WiFi)
        // ════════════════════════════════════════════════════════════════════════
        StatusBus.get().observeForever(event -> {
            if (event != null && event.message != null) {
                // Если сообщение о подключении/отключении — обновляем статус VPN
                if (event.message.contains("Подключено") ||
                        event.message.contains("Отключено")) {
                    FileLogger.d("MainViewModel", "StatusBus: " + event.message + " → refreshVpnStatus()");
                    refreshVpnStatus();
                }
            }
        });

        refreshVpnStatus();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Методы для обновления состояния
    // ════════════════════════════════════════════════════════════════════════

    public void refreshVpnStatus() {
        boolean running = VpnTunnelService.isRunning;
        VlessServer server = VpnTunnelService.connectedServer;

        FileLogger.d("MainViewModel", "refreshVpnStatus: running=" + running +
                ", server=" + (server != null ? server.host : "null"));

        isConnected.postValue(running);
        connectedServer.postValue(server);
    }

    public void forceRefreshServers() {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            repository.resetUpdateTime();
            repository.resetAllTestTimesSync();

            mainHandler.post(() ->
                    com.vlessvpn.app.service.BackgroundMonitorService.runImmediately(getApplication())
            );
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    // Прогресс сканирования
    // ════════════════════════════════════════════════════════════════════════

    private final MutableLiveData<Boolean> isWorking = new MutableLiveData<>(false);
    private final MutableLiveData<String> progressTitle = new MutableLiveData<>("");
    private final MutableLiveData<String> progressDetail = new MutableLiveData<>("");
    private final MutableLiveData<Integer> progressPercent = new MutableLiveData<>(0);
    private final MutableLiveData<int[]> progressCounts = new MutableLiveData<>();
    private final MutableLiveData<String> lastStatusMessage = new MutableLiveData<>("");

    public LiveData<Boolean> getIsWorking() { return isWorking; }
    public LiveData<String> getProgressTitle() { return progressTitle; }
    public LiveData<String> getProgressDetail() { return progressDetail; }
    public LiveData<Integer> getProgressPercent() { return progressPercent; }
    public LiveData<int[]> getProgressCounts() { return progressCounts; }
    public LiveData<String> getLastStatusMessage() { return lastStatusMessage; }

    public void setIsWorking(boolean working) { isWorking.postValue(working); }
    public void setProgressTitle(String title) { progressTitle.postValue(title); }
    public void setProgressDetail(String detail) { progressDetail.postValue(detail); }
    public void setProgressPercent(int percent) { progressPercent.postValue(percent); }
    public void setProgressCounts(int[] counts) { progressCounts.postValue(counts); }
    public void setLastStatusMessage(String msg) { lastStatusMessage.postValue(msg); }

    // ════════════════════════════════════════════════════════════════════════
    // События серверов (из StatusBus)
    // ════════════════════════════════════════════════════════════════════════

    public LiveData<StatusBus.ServerEvent> getServerEvents() {
        return StatusBus.getServerEvents();
    }
}