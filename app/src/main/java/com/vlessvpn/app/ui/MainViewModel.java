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
    private final MutableLiveData<Boolean>     isConnected    = new MutableLiveData<>(false);
    private final MutableLiveData<VlessServer> connectedServer = new MutableLiveData<>(null);

    public MainViewModel(@NonNull Application application) {
        super(application);
        repository = new ServerRepository(application);
        allServers = repository.getTopServersLiveData();

        // Слушаем изменения VPN состояния
        VpnTunnelService.registerConnectionListener(application, this::setConnected);

        // Слушаем StatusBus (прогресс сканирования показывается через broadcast в MainActivity)
        StatusBus.get().observeForever(event -> {
            if (event != null) {
                // ViewModel больше не пытается разбирать progress-сообщения —
                // это делает statusReceiver в MainActivity напрямую.
            }
        });

        // Инициализируем текущий статус
        setConnected(VpnTunnelService.isRunning);
    }

    // ── Серверы ───────────────────────────────────────────────────────────────

    public LiveData<List<VlessServer>> getAllServers() { return allServers; }

    // ── VPN статус ────────────────────────────────────────────────────────────

    public LiveData<Boolean>     getIsConnected()    { return isConnected; }
    public LiveData<VlessServer> getConnectedServer() { return connectedServer; }

    private void setConnected(boolean connected) {
        isConnected.postValue(connected);
        connectedServer.postValue(connected ? VpnTunnelService.getCurrentServer() : null);
    }

    public void refreshVpnStatus() {
        boolean connected = VpnTunnelService.isRunning;
        isConnected.postValue(connected);
        connectedServer.postValue(connected ? VpnTunnelService.getCurrentServer() : null);
    }

    // ── Действия ─────────────────────────────────────────────────────────────

    /** Скачать новые списки + сканировать */
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
}
