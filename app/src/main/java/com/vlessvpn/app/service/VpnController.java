package com.vlessvpn.app.service;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import com.google.gson.Gson;
import com.vlessvpn.app.model.VlessServer;
import com.vlessvpn.app.storage.ServerRepository;
import com.vlessvpn.app.util.FileLogger;

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
        FileLogger.i(TAG, "VpnController → DISCONNECT (userAction=" + isUserAction + ")");

        if (isUserAction) {
            userManuallyDisconnected = true;   // ← КЛЮЧЕВОЕ ИЗМЕНЕНИЕ
            FileLogger.i(TAG, "Пользователь вручную отключил VPN — авто-подключение заблокировано до смены сети");
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
        FileLogger.i(TAG, "VpnController → CONNECT " +
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
            FileLogger.i(TAG, "Сброс флага ручного отключения — разрешено авто-подключение");
        }
    }

    /** Полная остановка ВСЕГО (используется перед любым новым действием) */
    public void stopAllPendingOperations() {
        FileLogger.i(TAG, "VpnController → stopAllPendingOperations");
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

    // Вызывается из WifiMonitor и AutoConnectManager
    public void startAutoConnect() {
        VlessServer best = repository.getLastWorkingServer();
        if (best != null) {
            FileLogger.i(TAG, "VpnController → startAutoConnect: " + best.host);
            connect(best, true);
        } else {
            FileLogger.w(TAG, "Нет рабочих серверов для авто-подключения");
        }
    }
}