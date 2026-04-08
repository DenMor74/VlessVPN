package com.vlessvpn.app.service;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

import com.google.gson.Gson;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

public class VpnController {

    public enum VpnState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        ERROR
    }

    private static final String TAG = "VpnController";
    private static volatile VpnController instance;
    private final Context appContext;
    private final ServerRepository repository;

    private final MutableLiveData<VpnState> vpnState = new MutableLiveData<>(VpnState.DISCONNECTED);

    private volatile boolean userManuallyDisconnected = false;
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private VlessServer currentServer = null;

    private VpnController(Context context) {
        appContext = context.getApplicationContext();
        repository = new ServerRepository(appContext);
    }

    public static synchronized VpnController getInstance(Context context) {
        if (instance == null) {
            instance = new VpnController(context);
        }
        return instance;
    }

    public LiveData<VpnState> getState() {
        return vpnState;
    }

    public void updateState(VpnState state) {
        FileLogger.i(TAG, "VPN State changed to: " + state.name());
        vpnState.postValue(state);

        if (state == VpnState.DISCONNECTED || state == VpnState.ERROR) {
            isConnecting.set(false);
            if (state == VpnState.DISCONNECTED) {
                currentServer = null;
            }
        } else if (state == VpnState.CONNECTED) {
            isConnecting.set(false);
        }
    }

    public void setCurrentServer(VlessServer server) {
        this.currentServer = server;
    }

    public void disconnect(boolean isUserAction) {
        VpnState currentState = vpnState.getValue();
        if (currentState == VpnState.DISCONNECTED || currentState == VpnState.DISCONNECTING) {
            FileLogger.i(TAG, "disconnect: уже отключено или в процессе отключения.");
            return;
        }

        FileLogger.i(TAG, "DISCONNECT (userAction=" + isUserAction + ")");
        updateState(VpnState.DISCONNECTING);

        if (isUserAction) {
            userManuallyDisconnected = true;
            FileLogger.i(TAG, "VPN отключен вручную.");
        }

        stopAllPendingOperations();

        Intent intent = new Intent(appContext, VpnTunnelService.class);
        intent.setAction(VpnTunnelService.ACTION_DISCONNECT);
        if (!isUserAction) {
            intent.putExtra("system_cleanup", true);
        }

        // ИСПРАВЛЕНИЕ: Гарантированная доставка интента даже если Android пытается его убить
        try {
            ContextCompat.startForegroundService(appContext, intent);
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка вызова ACTION_DISCONNECT: " + e.getMessage());
        }
    }

    public void connect(@NonNull VlessServer server, boolean isAutoMode) {
        VpnState currentState = vpnState.getValue();
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            FileLogger.i(TAG, "connect: уже подключено или в процессе подключения.");
            return;
        }

        FileLogger.i(TAG, "CONNECT " + (isAutoMode ? "(AUTO)" : "(MANUAL)") + ": " + server.host);

        updateState(VpnState.CONNECTING);
        setCurrentServer(server);

        if (!isAutoMode) {
            userManuallyDisconnected = false;
        }

        stopAllPendingOperations();

        Intent intent = new Intent(appContext, VpnTunnelService.class);
        intent.setAction(VpnTunnelService.ACTION_CONNECT);
        intent.putExtra(VpnTunnelService.EXTRA_SERVER, new Gson().toJson(server));
        intent.putExtra(VpnTunnelService.EXTRA_AUTO_CONNECT, isAutoMode);

        try {
            ContextCompat.startForegroundService(appContext, intent);
        } catch (Exception e) {
            FileLogger.e(TAG, "Ошибка вызова ACTION_CONNECT: " + e.getMessage());
            updateState(VpnState.ERROR);
        }
    }

    public void reconnect(@NonNull VlessServer newServer, boolean isAutoMode) {
        FileLogger.i(TAG, "RECONNECT к: " + newServer.host);
        disconnect(false);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            connect(newServer, isAutoMode);
        }, 1000);
    }

    public boolean isUserManuallyDisconnected() {
        return userManuallyDisconnected;
    }

    public void resetManualDisconnectFlag() {
        if (userManuallyDisconnected) {
            userManuallyDisconnected = false;
        }
    }

    public void stopAllPendingOperations() {
        AutoConnectManager.cancelAutoConnect();
    }

    public boolean isRunning() {
        return vpnState.getValue() == VpnState.CONNECTED;
    }

    public VlessServer getCurrentServer() {
        return currentServer;
    }

    public void handleConnectButton(VlessServer server) {
        if (isRunning() || vpnState.getValue() == VpnState.CONNECTING) {
            disconnect(true);
        } else {
            connect(server, false);
        }
    }

    public void handleDisconnectButton() {
        disconnect(true);
    }

    public void startAutoConnect() {
        if (isConnecting.getAndSet(true)) {
            FileLogger.w(TAG, "startAutoConnect: уже идёт подключение, пропускаем");
            return;
        }
        if (isRunning() || vpnState.getValue() == VpnState.CONNECTING) {
            FileLogger.w(TAG, "startAutoConnect: VPN уже работает или подключается");
            isConnecting.set(false);
            return;
        }

        new Thread(() -> {
            try {
                final VlessServer lastWorking = repository.getLastWorkingServer();
                if (lastWorking != null) {
                    FileLogger.i(TAG, "startAutoConnect: " + lastWorking.host);
                    new Handler(Looper.getMainLooper()).post(() -> connect(lastWorking, true));
                    return;
                }

                FileLogger.i(TAG, "startAutoConnect: нет lastWorkingServer — пробуем топ серверов");
                List<VlessServer> top = repository.getTopServersSync();
                if (top != null && !top.isEmpty()) {
                    final VlessServer topServer = top.get(0);
                    FileLogger.i(TAG, "startAutoConnect: fallback — выбран " + topServer.host + " из топ-" + top.size());
                    new Handler(Looper.getMainLooper()).post(() -> connect(topServer, true));
                    return;
                }

                FileLogger.w(TAG, "Нет рабочих серверов для авто-подключения");
                isConnecting.set(false);
                updateState(VpnState.DISCONNECTED);
            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка в startAutoConnect: " + e.getMessage());
                isConnecting.set(false);
                updateState(VpnState.ERROR);
            }
        }).start();
    }

    public void onConnectFinished() {
        isConnecting.set(false);
    }
}