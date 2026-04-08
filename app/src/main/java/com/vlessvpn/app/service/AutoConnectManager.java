package com.vlessvpn.app.service;

import android.content.Context;
import com.vlessvpn.app.util.FileLogger;

/**
 * ВНИМАНИЕ: Логика перебора серверов теперь встроена прямо в VpnTunnelService
 * (метод switchToNextServer) и VpnController (метод startAutoConnect).
 *
 * Этот класс переделан в безопасную заглушку, чтобы исключить "Состояние гонки"
 * (Race Condition), когда и AutoConnectManager, и VpnTunnelService пытались
 * одновременно переключать серверы, ломая процесс VPN.
 */
public class AutoConnectManager {

    private static final String TAG = "AutoConnectManager";

    public static void startAutoConnect(Context context) {
        FileLogger.i(TAG, "Делегируем запуск авто-подключения в VpnController");
        VpnController.getInstance(context).startAutoConnect();
    }

    public static void cancelAutoConnect() {
        // Теперь отмена - это прерогатива VpnController.
        // Если вызвана команда STOP, контроллер просто убивает VpnTunnelService.
    }

    public static void reportVerificationResult(boolean success) {
        // Заглушка. VpnTunnelService теперь самостоятельно обрабатывает
        // успешные и неуспешные проверки внутри метода verifyTunnelConnection().
    }

    public static boolean isFailoverInProgress() {
        // Заглушка для обратной совместимости.
        // Реальный статус теперь хранится только в VpnController (VpnState.CONNECTING)
        return false;
    }
}