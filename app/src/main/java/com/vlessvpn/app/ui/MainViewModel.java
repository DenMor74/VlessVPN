package com.vlessvpn.app.ui;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.service.BackgroundMonitorService;
import com.vlessvpn.app.service.VpnTunnelService;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.StatusBus;

import java.util.List;
import java.util.concurrent.Executors;

public class MainViewModel extends AndroidViewModel {

    private final ServerRepository        repository;
    private final LiveData<List<VlessServer>> allServers;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // VPN статус
    private final MutableLiveData<Boolean>     isConnected       = new MutableLiveData<>(false);
    private final MutableLiveData<VlessServer> connectedServer   = new MutableLiveData<>(null);

    // Статус сканирования и трафик
    private final MutableLiveData<String>      lastStatusMessage = new MutableLiveData<>("");
    // IP результат — отдельная LiveData, не перезаписывается другими сообщениями
    private final MutableLiveData<String>      lastIpResult = new MutableLiveData<>("");

    // Статус сервисов (TG/YT)
    public static class ServiceStatus {
        public boolean tgOk;
        public boolean ytOk;
        public ServiceStatus(boolean tg, boolean yt) { this.tgOk = tg; this.ytOk = yt; }
    }
    private final MutableLiveData<ServiceStatus> serviceStatus = new MutableLiveData<>(new ServiceStatus(false, false));

    public MainViewModel(@NonNull Application application) {
        super(application);
        repository = new ServerRepository(application);
        allServers = repository.getTopServersLiveData();

        // Слушаем изменения VPN состояния
        VpnTunnelService.registerConnectionListener(application, this::setConnected);

        // Слушаем StatusBus — IP результаты в отдельную LiveData
        StatusBus.get().observeForever(event -> {
            if (event != null && event.message != null && !event.message.isEmpty()) {
                if (event.message.startsWith("🔬")) {
                    lastIpResult.postValue(event.message);
                } else {
                    lastStatusMessage.postValue(event.message);
                }
            }
        });

        // Инициализируем текущий статус
        setConnected(VpnTunnelService.isRunning);

        // Слушаем статус сервисов (YT/TG) из StatusBus для сохранения при сворачивании
        StatusBus.getServiceEvents().observeForever(serviceEvent -> {
            if (serviceEvent != null) {
                serviceStatus.postValue(new ServiceStatus(serviceEvent.tgOk, serviceEvent.ytOk));
            }
        });
    }

    // ── Серверы ───────────────────────────────────────────────────────────────

    public LiveData<List<VlessServer>> getAllServers() { return allServers; }

    // ── VPN статус ────────────────────────────────────────────────────────────

    public LiveData<Boolean>     getIsConnected()      { return isConnected; }
    public LiveData<String>      getLastStatusMessage() { return lastStatusMessage; }
    public LiveData<String>      getLastIpResult()      { return lastIpResult; }
    public void clearIpResult() { lastIpResult.postValue(""); }
    public LiveData<VlessServer> getConnectedServer() { return connectedServer; }

    public LiveData<ServiceStatus> getServiceStatus() { return serviceStatus; }
    public void setServiceStatus(boolean tg, boolean yt) { serviceStatus.postValue(new ServiceStatus(tg, yt)); }

    private void setConnected(boolean connected) {
        isConnected.postValue(connected);
        connectedServer.postValue(connected ? VpnTunnelService.getCurrentServer() : null);
    }

    public void refreshVpnStatus() {
        boolean connected = VpnTunnelService.isRunning;
        isConnected.postValue(connected);
        connectedServer.postValue(connected ? VpnTunnelService.getCurrentServer() : null);
    }

    public void toggleFavorite(VlessServer server) {
        repository.toggleFavorite(server);
    }

    // ── Действия ─────────────────────────────────────────────────────────────

    /**
     * Скачать новые списки + сканировать.
     * ← ИСПРАВЛЕНО: раньше здесь вызывались runDownloadNow() и runScanNow() почти
     * одновременно — сканирование стартовало на ещё СТАРОМ списке серверов прямо
     * в тот момент, когда DownloadWorker уже очищал и заново заполнял ту же таблицу
     * в БД (гонка данных на каждое нажатие "Обновить"). Теперь используется
     * runDownloadThenScanNow(), которая через WorkManager .then(...) гарантирует,
     * что сканирование начнётся только после того, как скачивание реально завершится.
     */
    public void forceRefreshServers() {
        Executors.newSingleThreadExecutor().execute(() -> {
            repository.resetUpdateTime();
            repository.resetAllTestTimesSync();
            mainHandler.post(() -> BackgroundMonitorService.runDownloadThenScanNow(getApplication()));
        });
    }
}
