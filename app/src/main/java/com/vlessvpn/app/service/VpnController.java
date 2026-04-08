package com.vlessvpn.app.service;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.gson.Gson;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

import java.util.List;

/**
 * VpnController — ЕДИНАЯ ТОЧКА УПРАВЛЕНИЯ подключением.
 * Всё подключение/отключение теперь только через этот класс.
 */
public class VpnController {

    private static final String TAG = "VpnController";
    private static volatile VpnController instance;
    private final Context appContext;
    private final ServerRepository repository;
    private volatile boolean userManuallyDisconnected = false;
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);

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

    // ================================================
    // ЕДИНЫЕ МЕТОДЫ ПОДКЛЮЧЕНИЯ / ОТКЛЮЧЕНИЯ
    // ================================================


    public void disconnect(boolean isUserAction) {
        FileLogger.i(TAG, "DISCONNECT (userAction=" + isUserAction + ")");

        if (isUserAction) {
            userManuallyDisconnected = true;   // ← КЛЮЧЕВОЕ ИЗМЕНЕНИЕ
            FileLogger.i(TAG, "VPN отключен вручную.");
        }

        stopAllPendingOperations();

        Intent intent = new Intent(appContext, VpnTunnelService.class);
        intent.setAction(VpnTunnelService.ACTION_DISCONNECT);
        if (!isUserAction) {
            intent.putExtra("system_cleanup", true);
        }
        appContext.startService(intent);
    }

    public void connect(@NonNull VlessServer server, boolean isAutoMode) {
        FileLogger.i(TAG, "CONNECT " +
                (isAutoMode ? "(AUTO)" : "(MANUAL)") + ": " + server.host);

        // Если пользователь вручную отключил — снимаем блокировку только при ручном подключении
        if (!isAutoMode) {
            userManuallyDisconnected = false;
        }

        stopAllPendingOperations();

        Intent intent = new Intent(appContext, VpnTunnelService.class);
        intent.setAction(VpnTunnelService.ACTION_CONNECT);
        intent.putExtra(VpnTunnelService.EXTRA_SERVER, new Gson().toJson(server));
        intent.putExtra(VpnTunnelService.EXTRA_AUTO_CONNECT, isAutoMode);
        appContext.startService(intent);
    }

    // Новый метод — будет использоваться в WifiMonitor
    public boolean isUserManuallyDisconnected() {
        return userManuallyDisconnected;
    }

    // Сброс флага при смене сети (когда появляется Wi-Fi или меняется тип соединения)
    public void resetManualDisconnectFlag() {
        if (userManuallyDisconnected) {
            userManuallyDisconnected = false;
            //FileLogger.i(TAG, "Сброс флага ручного отключения — разрешено авто-подключение");
        }
    }

    /** Полная остановка ВСЕГО (используется перед любым новым действием) */
    public void stopAllPendingOperations() {
        //FileLogger.i(TAG, "stopAllPendingOperations");
        AutoConnectManager.cancelAutoConnect();
        // Если в будущем добавишь WorkManager-таски — отменяй здесь
    }

    public boolean isRunning() {
        return VpnTunnelService.isRunning;
    }

    public VlessServer getCurrentServer() {
        return VpnTunnelService.getCurrentServer();
    }

    // ================================================
    // Удобные методы
    // ================================================

    public void handleConnectButton(VlessServer server) {
        if (isRunning()) {
            disconnect(true); // отключить
        } else {
            connect(server, false); // manual
        }
    }

    public void handleDisconnectButton() {
        disconnect(true);
    }


    public void startAutoConnect() {
        // ← ГЛАВНЫЙ GUARD
        if (isConnecting.getAndSet(true)) {
            FileLogger.w(TAG, "startAutoConnect: уже идёт подключение, пропускаем");
            return;
        }
        if (VpnTunnelService.isRunning) {
            FileLogger.w(TAG, "startAutoConnect: VPN уже работает");
            isConnecting.set(false);
            return;
        }

        new Thread(() -> {
            try {
                VlessServer best = repository.getLastWorkingServer();
                if (best != null) {
                    FileLogger.i(TAG, "startAutoConnect: " + best.host);
                    connect(best, true);
                    return;
                }
                FileLogger.i(TAG, "startAutoConnect: нет lastWorkingServer — пробуем топ серверов");
                List<VlessServer> top = repository.getTopServersSync();
                if (top != null && !top.isEmpty()) {
                    best = top.get(0);
                    FileLogger.i(TAG, "startAutoConnect: fallback — выбран " + best.host + " из топ-" + top.size());
                    connect(best, true);
                    return;
                }
                FileLogger.w(TAG, "Нет рабочих серверов для авто-подключения");
                isConnecting.set(false); // сбросить если серверов нет
            } catch (Exception e) {
                FileLogger.e(TAG, "Ошибка в startAutoConnect: " + e.getMessage());
                isConnecting.set(false); // сбросить при ошибке
            }
        }).start();
    }

    // Добавить метод сброса — вызывать при успехе и провале:
    public void onConnectFinished() {
        isConnecting.set(false);
    }

}