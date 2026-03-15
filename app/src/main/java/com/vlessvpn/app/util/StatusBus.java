package com.vlessvpn.app.util;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * StatusBus — шина событий Worker → UI.
 *
 * Два вида событий:
 * 1. StatusEvent — общий статус (заголовок, прогресс)
 * 2. ServerEvent — статус конкретного сервера (для обновления строки в списке)
 */
public class StatusBus {

    // ── Общий статус ─────────────────────────────────
    public static class StatusEvent {
        public final String message;
        public final boolean isRunning;
        public StatusEvent(String message, boolean isRunning) {
            this.message = message;
            this.isRunning = isRunning;
        }
    }

    // ── Статус конкретного сервера ────────────────────
    public static class ServerEvent {
        public final String serverId;   // id сервера (uuid@host:port)
        public final String host;       // для отображения
        public final String status;     // "pinging" | "testing" | "ok" | "fail"
        public final long pingMs;       // -1 если нет
        public final String detail;     // детальный текст
        public final boolean trafficOk;

        public ServerEvent(String serverId, String host, String status,
                           long pingMs, boolean trafficOk, String detail) {
            this.serverId  = serverId;
            this.host      = host;
            this.status    = status;
            this.pingMs    = pingMs;
            this.trafficOk = trafficOk;
            this.detail    = detail;
        }
    }

    private static final MutableLiveData<StatusEvent>  globalStatus = new MutableLiveData<>();
    private static final MutableLiveData<ServerEvent>  serverStatus = new MutableLiveData<>();

    public static void post(String message, boolean isRunning) {
        globalStatus.postValue(new StatusEvent(message, isRunning));
        //FileLogger.i("StatusBus", message);
    }
    public static void post(String message)  { post(message, true);  }
    public static void done(String message)  { post(message, false); }

    public static void postServer(String serverId, String host, String status,
                                  long pingMs, boolean trafficOk, String detail) {
        serverStatus.postValue(new ServerEvent(serverId, host, status, pingMs, trafficOk, detail));
        FileLogger.d("StatusBus", host + " → " + status
            + (pingMs > 0 ? " " + pingMs + "ms" : "")
            + (detail != null ? " " + detail : ""));
    }

    public static LiveData<StatusEvent> get()            { return globalStatus; }
    public static LiveData<ServerEvent> getServerEvents(){ return serverStatus; }
}
