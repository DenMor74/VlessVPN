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
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.StatusBus;

import java.util.List;
import java.util.concurrent.Executors;

public class MainViewModel extends AndroidViewModel {

    private final ServerRepository repository;
    private final LiveData<List<VlessServer>> allServers;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Статус сканирования и трафик
    private final MutableLiveData<String> lastStatusMessage = new MutableLiveData<>("");
    // IP результат — отдельная LiveData, не перезаписывается другими сообщениями
    private final MutableLiveData<String> lastIpResult = new MutableLiveData<>("");

    public MainViewModel(@NonNull Application application) {
        super(application);
        repository = new ServerRepository(application);
        allServers = repository.getTopServersLiveData();

        // ⚠️ ВАЖНО: Вся логика статуса подключения (isConnected, connectedServer)
        // теперь находится в VpnController и наблюдается напрямую из MainActivity!

        // Слушаем StatusBus (только для вывода текстов скорости, пинга и IP)
        StatusBus.get().observeForever(event -> {
            if (event != null && event.message != null && !event.message.isEmpty()) {
                if (event.message.startsWith("🔬")) {
                    lastIpResult.postValue(event.message);
                } else {
                    lastStatusMessage.postValue(event.message);
                }
            }
        });
    }

    // ── Серверы ───────────────────────────────────────────────────────────────

    public LiveData<List<VlessServer>> getAllServers() { return allServers; }

    // ── Тексты и статусы ──────────────────────────────────────────────────────

    public LiveData<String> getLastStatusMessage() { return lastStatusMessage; }
    public LiveData<String> getLastIpResult()      { return lastIpResult; }
    public void clearIpResult() { lastIpResult.postValue(""); }

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