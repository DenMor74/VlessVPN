package com.vlessvpn.app.ui;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.service.BackgroundMonitorService;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.util.StatusBus;

import java.util.List;
import java.util.concurrent.Executors;

public class MainViewModel extends AndroidViewModel {

    private final ServerRepository repository;
    private final LiveData<List<VlessServer>> allServers;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ════════════════════════════════════════════════════════════════
    // Статус VPN подключения
    // ════════════════════════════════════════════════════════════════

    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);
    private final MutableLiveData<VlessServer> connectedServer = new MutableLiveData<>(null);

    // ════════════════════════════════════════════════════════════════
    // Прогресс сканирования
    // ════════════════════════════════════════════════════════════════

    private final MutableLiveData<Boolean> isWorking = new MutableLiveData<>(false);
    private final MutableLiveData<String> progressTitle = new MutableLiveData<>("");
    private final MutableLiveData<String> progressDetail = new MutableLiveData<>("");
    private final MutableLiveData<Integer> progressPercent = new MutableLiveData<>(0);
    private final MutableLiveData<int[]> progressCounts = new MutableLiveData<>(new int[]{0, 0, 0});
    private final MutableLiveData<String> lastStatusMessage = new MutableLiveData<>("");
    private final MutableLiveData<ServerEvent> serverEvents = new MutableLiveData<>(null);

    // ════════════════════════════════════════════════════════════════
    // Счётчик рабочих серверов
    // ════════════════════════════════════════════════════════════════

    private final MutableLiveData<Integer> workingCount = new MutableLiveData<>(0);

    // ════════════════════════════════════════════════════════════════
    // Конструктор
    // ════════════════════════════════════════════════════════════════

    public MainViewModel(@NonNull Application application) {
        super(application);

        repository = new ServerRepository(application);
        allServers = repository.getTopServersLiveData();

        // Слушаем изменения статуса VPN
        VpnTunnelService.registerConnectionListener(application, this::setConnected);

        // Слушаем StatusBus для прогресса сканирования
        StatusBus.get().observeForever(event -> {
            if (event == null) return;
            setIsWorking(event.isRunning);
            setProgressTitle(event.message);
            setLastStatusMessage(event.message);
        });

        // ════════════════════════════════════════════════════════════════
        // ← ИСПРАВЛЕНО: Обновляем статус при создании ViewModel
        // ════════════════════════════════════════════════════════════════
        setConnected(VpnTunnelService.isRunning);
    }

    // ════════════════════════════════════════════════════════════════
    // Серверы
    // ════════════════════════════════════════════════════════════════

    public LiveData<List<VlessServer>> getAllServers() {
        return allServers;
    }

    public LiveData<List<VlessServer>> getTopServers() {
        return repository.getTopServersLiveData();
    }

    // ════════════════════════════════════════════════════════════════
    // Статус подключения (VPN)
    // ════════════════════════════════════════════════════════════════

    public LiveData<Boolean> getIsConnected() {
        return isConnected;
    }

    public LiveData<VlessServer> getConnectedServer() {
        return connectedServer;
    }

    // ════════════════════════════════════════════════════════════════
    // ← ИСПРАВЛЕНО: УБРАНА БЕСКОНЕЧНАЯ РЕКУРСИЯ!
    // ════════════════════════════════════════════════════════════════

    private void setConnected(boolean connected) {
        // Обновляем статус
        isConnected.postValue(connected);

        // Обновляем сервер ТОЛЬКО если подключились
        if (connected) {
            VlessServer server = VpnTunnelService.getCurrentServer();
            connectedServer.postValue(server);
        } else {
            connectedServer.postValue(null);
        }

        // ════════════════════════════════════════════════════════════════
        // ← УДАЛЕНО: refreshVpnStatus() вызывал setConnected() → бесконечный цикл!
        // ════════════════════════════════════════════════════════════════
    }

    public void refreshVpnStatus() {
        // ════════════════════════════════════════════════════════════════
        // ← ИСПРАВЛЕНО: Просто обновляем статус, без рекурсии
        // ════════════════════════════════════════════════════════════════
        boolean connected = VpnTunnelService.isRunning;
        isConnected.postValue(connected);

        if (connected) {
            VlessServer server = VpnTunnelService.getCurrentServer();
            connectedServer.postValue(server);
        } else {
            connectedServer.postValue(null);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Прогресс сканирования
    // ════════════════════════════════════════════════════════════════

    public LiveData<Boolean> getIsWorking() {
        return isWorking;
    }

    public LiveData<String> getProgressTitle() {
        return progressTitle;
    }

    public LiveData<String> getProgressDetail() {
        return progressDetail;
    }

    public LiveData<Integer> getProgressPercent() {
        return progressPercent;
    }

    public LiveData<int[]> getProgressCounts() {
        return progressCounts;
    }

    public LiveData<String> getLastStatusMessage() {
        return lastStatusMessage;
    }

    public LiveData<ServerEvent> getServerEvents() {
        return serverEvents;
    }

    // ════════════════════════════════════════════════════════════════
    // Сеттеры для прогресса
    // ════════════════════════════════════════════════════════════════

    public void setIsWorking(boolean working) {
        isWorking.postValue(working);
    }

    public void setProgressTitle(String title) {
        progressTitle.postValue(title);
    }

    public void setProgressDetail(String detail) {
        progressDetail.postValue(detail);
    }

    public void setProgressPercent(int percent) {
        progressPercent.postValue(percent);
    }

    public void setProgressCounts(int total, int ok, int fail) {
        progressCounts.postValue(new int[]{total, ok, fail});
    }

    public void setLastStatusMessage(String message) {
        lastStatusMessage.postValue(message);
    }

    public void postServerEvent(String serverId, String host, String status, int ping, boolean ok, String detail) {
        serverEvents.postValue(new ServerEvent(serverId, host, status, ping, ok, detail));
    }

    // ════════════════════════════════════════════════════════════════
    // Счётчик рабочих серверов
    // ════════════════════════════════════════════════════════════════

    public LiveData<Integer> getWorkingCount() {
        return workingCount;
    }

    public void updateWorkingCount(int count) {
        workingCount.postValue(count);
    }

    // ════════════════════════════════════════════════════════════════
    // Ручное обновление
    // ════════════════════════════════════════════════════════════════

    public void forceRefreshServers() {
        Executors.newSingleThreadExecutor().execute(() -> {
            repository.resetUpdateTime();
            repository.resetAllTestTimesSync();

            mainHandler.post(() -> {
                BackgroundMonitorService.runDownloadNow(getApplication());
                BackgroundMonitorService.runScanNow(getApplication());
            });
        });
    }

    // ════════════════════════════════════════════════════════════════
    // Только скачать новые списки
    // ════════════════════════════════════════════════════════════════

    public void downloadNewLists() {
        Executors.newSingleThreadExecutor().execute(() -> {
            repository.resetUpdateTime();
            mainHandler.post(() ->
                    BackgroundMonitorService.runDownloadNow(getApplication())
            );
        });
    }

    // ════════════════════════════════════════════════════════════════
    // Только сканировать текущий список
    // ════════════════════════════════════════════════════════════════

    public void scanCurrentList() {
        if (VpnTunnelService.isRunning) {
            mainHandler.post(() ->
                    android.widget.Toast.makeText(
                            getApplication(),
                            "⚠️ Отключите VPN для сканирования",
                            android.widget.Toast.LENGTH_SHORT
                    ).show()
            );
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            repository.resetAllTestTimesSync();
            mainHandler.post(() ->
                    BackgroundMonitorService.runScanNow(getApplication())
            );
        });
    }

    // ════════════════════════════════════════════════════════════════
    // Внутренний класс для событий серверов
    // ════════════════════════════════════════════════════════════════

    public static class ServerEvent {
        public final String serverId;
        public final String host;
        public final String status;
        public final int ping;
        public final boolean ok;
        public final String detail;

        public ServerEvent(String serverId, String host, String status, int ping, boolean ok, String detail) {
            this.serverId = serverId;
            this.host = host;
            this.status = status;
            this.ping = ping;
            this.ok = ok;
            this.detail = detail;
        }
    }
}