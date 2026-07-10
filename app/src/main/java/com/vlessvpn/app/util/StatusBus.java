package com.vlessvpn.app.util;

import android.content.Context;
import android.content.Intent;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * StatusBus — шина событий Worker → UI.
 *
 * ВАЖНО: WorkManager работает в отдельном процессе!
 * Поэтому используем Broadcast для кросс-процессной передачи.
 */
public class StatusBus {

    // ════════════════════════════════════════════════════
    // Action для Broadcast
    // ════════════════════════════════════════════════════

    public static final String ACTION_STATUS_CHANGED = "com.vlessvpn.STATUS_CHANGED";
    public static final String ACTION_SERVER_EVENT   = "com.vlessvpn.SERVER_EVENT";
    public static final String ACTION_SERVICE_STATUS = "com.vlessvpn.SERVICE_STATUS";
    public static final String ACTION_PING_RESULT    = "com.vlessvpn.PING_RESULT";

    public static final String EXTRA_MESSAGE      = "message";
    public static final String EXTRA_IS_RUNNING   = "is_running";
    public static final String EXTRA_PROGRESS     = "progress";
    public static final String EXTRA_TOTAL        = "total";
    public static final String EXTRA_OK           = "ok";
    public static final String EXTRA_FAIL         = "fail";
    public static final String EXTRA_TG_OK        = "tg_ok";
    public static final String EXTRA_YT_OK        = "yt_ok";

    public static final String EXTRA_SERVER_ID    = "server_id";
    public static final String EXTRA_HOST         = "host";
    public static final String EXTRA_STATUS       = "status";
    public static final String EXTRA_PING         = "ping";
    public static final String EXTRA_DETAIL       = "detail";

    // ════════════════════════════════════════════════════
    // Класс события статуса
    // ════════════════════════════════════════════════════

    public static class StatusEvent {
        public String message = "";
        public boolean isRunning = false;
        public int progress = 0;
        public int total = 0;
        public int ok = 0;
        public int fail = 0;

        public StatusEvent() {}

        public StatusEvent(String message, boolean isRunning) {
            this.message = message;
            this.isRunning = isRunning;
        }
    }

    // ════════════════════════════════════════════════════
    // Класс события сервера
    // ════════════════════════════════════════════════════

    public static class ServerEvent {
        public final String serverId;
        public final String host;
        public final String status;
        public final long pingMs;
        public final boolean trafficOk;
        public final String detail;

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

    // ════════════════════════════════════════════════════
    // Класс события сервиса
    // ════════════════════════════════════════════════════

    public static class ServiceEvent {
        public boolean tgOk = false;
        public boolean ytOk = false;

        public ServiceEvent(boolean tgOk, boolean ytOk) {
            this.tgOk = tgOk;
            this.ytOk = ytOk;
        }
    }

    // ════════════════════════════════════════════════════
    // LiveData для наблюдения
    // ════════════════════════════════════════════════════

    private static final MutableLiveData<StatusEvent>  globalStatus = new MutableLiveData<>();
    private static final MutableLiveData<ServerEvent>  serverStatus = new MutableLiveData<>();
    private static final MutableLiveData<ServiceEvent> serviceStatus = new MutableLiveData<>(new ServiceEvent(false, false));

    // ════════════════════════════════════════════════════
    // Методы с Context (для WorkManager — кросс-процесс)
    // ════════════════════════════════════════════════════

    public static void post(Context ctx, String message, boolean isRunning) {
        globalStatus.postValue(new StatusEvent(message, isRunning));

        // Дублируем в AOD overlay (только если не трафик и не сканирование серверов)
        if (ctx != null && isRunning
                && !message.contains("↑") && !message.contains("↓")
                && !message.contains("/") // прогресс сканирования типа "5/30"
                && com.vlessvpn.app.service.AodOverlayService.isRunning) {
            com.vlessvpn.app.service.AodOverlayService.sendStatusMsg(ctx, message);
        }

        if (ctx != null) {
            Intent intent = new Intent(ACTION_STATUS_CHANGED);
            intent.putExtra(EXTRA_MESSAGE, message);
            intent.putExtra(EXTRA_IS_RUNNING, isRunning);
            ctx.sendBroadcast(intent);
        }

        //FileLogger.i("StatusBus", message);
    }

    public static void post(Context ctx, String message) {
        post(ctx, message, true);
    }

    public static void done(Context ctx, String message) {
        post(ctx, message, false);
    }

    public static void postServer(Context ctx, String serverId, String host, String status,
                                  long pingMs, boolean trafficOk, String detail) {
        serverStatus.postValue(new ServerEvent(serverId, host, status, pingMs, trafficOk, detail));

        if (ctx != null) {
            Intent intent = new Intent(ACTION_SERVER_EVENT);
            intent.putExtra(EXTRA_SERVER_ID, serverId);
            intent.putExtra(EXTRA_HOST, host);
            intent.putExtra(EXTRA_STATUS, status);
            intent.putExtra(EXTRA_PING, pingMs);
            intent.putExtra(EXTRA_DETAIL, detail);
            ctx.sendBroadcast(intent);
        }

       // FileLogger.d("StatusBus", host + " → " + status);
    }

    public static void setWorking(Context ctx, boolean working) {
        StatusEvent event = globalStatus.getValue();
        if (event == null) event = new StatusEvent();
        event.isRunning = working;
        globalStatus.postValue(event);

        if (ctx != null) {
            Intent intent = new Intent(ACTION_STATUS_CHANGED);
            intent.putExtra(EXTRA_IS_RUNNING, working);
            ctx.sendBroadcast(intent);
        }
    }

    public static void setProgress(Context ctx, int percent) {
        StatusEvent event = globalStatus.getValue();
        if (event == null) event = new StatusEvent();
        event.progress = percent;
        globalStatus.postValue(event);

        if (ctx != null) {
            Intent intent = new Intent(ACTION_STATUS_CHANGED);
            intent.putExtra(EXTRA_PROGRESS, percent);
            ctx.sendBroadcast(intent);
        }
    }

    public static synchronized void postWithProgress(Context ctx, String msg, boolean isRunning, int percent) {
        // ОБЯЗАТЕЛЬНО создаем НОВЫЙ объект, чтобы потоки не перезаписывали данные друг друга
        StatusEvent newEvent = new StatusEvent();
        newEvent.message = msg;
        newEvent.isRunning = isRunning; // ← ИСПРАВЛЕНО: используем правильное имя поля
        newEvent.progress = percent;

        globalStatus.postValue(newEvent);

        if (ctx != null) {
            Intent intent = new Intent(ACTION_STATUS_CHANGED);
            // ← ИСПРАВЛЕНО: используем ваши константы для правильной передачи в UI
            intent.putExtra(EXTRA_PROGRESS, percent);
            intent.putExtra(EXTRA_MESSAGE, msg);
            intent.putExtra(EXTRA_IS_RUNNING, isRunning);
            ctx.sendBroadcast(intent);
        }
    }

    public static void updateCounts(Context ctx, int total, int ok, int fail) {
        StatusEvent event = globalStatus.getValue();
        if (event == null) event = new StatusEvent();
        event.total = total;
        event.ok = ok;
        event.fail = fail;
        globalStatus.postValue(event);

        if (ctx != null) {
            Intent intent = new Intent(ACTION_STATUS_CHANGED);
            intent.putExtra(EXTRA_TOTAL, total);
            intent.putExtra(EXTRA_OK, ok);
            intent.putExtra(EXTRA_FAIL, fail);
            ctx.sendBroadcast(intent);
        }
    }

    public static void postServiceStatus(Context ctx, boolean tgOk, boolean ytOk) {
        serviceStatus.postValue(new ServiceEvent(tgOk, ytOk));

        if (ctx != null) {
            Intent intent = new Intent(ACTION_SERVICE_STATUS);
            intent.putExtra(EXTRA_TG_OK, tgOk);
            intent.putExtra(EXTRA_YT_OK, ytOk);
            ctx.sendBroadcast(intent);
        }
    }

    public static void postPingResult(Context ctx, String service, boolean ok) {
        ServiceEvent current = serviceStatus.getValue();
        if (current == null) current = new ServiceEvent(false, false);

        boolean tg = current.tgOk;
        boolean yt = current.ytOk;

        if ("tg".equals(service)) tg = ok;
        else if ("yt".equals(service)) yt = ok;

        serviceStatus.postValue(new ServiceEvent(tg, yt));

        if (ctx != null) {
            Intent intent = new Intent(ACTION_PING_RESULT);
            intent.putExtra("service", service);
            intent.putExtra("ok", ok);
            ctx.sendBroadcast(intent);
        }
    }

    // ════════════════════════════════════════════════════
    // Методы без Context (для совместимости)
    // ════════════════════════════════════════════════════

    public static void post(String message, boolean isRunning) {
        globalStatus.postValue(new StatusEvent(message, isRunning));
    }

    public static void post(String message) {
        post(message, true);
    }

    public static void done(String message) {
        post(message, false);
    }

    public static void postServer(String serverId, String host, String status,
                                  long pingMs, boolean trafficOk, String detail) {
        serverStatus.postValue(new ServerEvent(serverId, host, status, pingMs, trafficOk, detail));
    }

    public static void setWorking(boolean working) {
        StatusEvent event = globalStatus.getValue();
        if (event == null) event = new StatusEvent();
        event.isRunning = working;
        globalStatus.postValue(event);
    }

    public static void setProgress(int percent) {
        StatusEvent event = globalStatus.getValue();
        if (event == null) event = new StatusEvent();
        event.progress = percent;
        globalStatus.postValue(event);
    }

    public static void updateCounts(int total, int ok, int fail) {
        StatusEvent event = globalStatus.getValue();
        if (event == null) event = new StatusEvent();
        event.total = total;
        event.ok = ok;
        event.fail = fail;
        globalStatus.postValue(event);
    }

    // ════════════════════════════════════════════════════
    // Геттеры LiveData
    // ════════════════════════════════════════════════════

    public static LiveData<StatusEvent> get() {
        return globalStatus;
    }

    public static LiveData<ServerEvent> getServerEvents() {
        return serverStatus;
    }

    public static LiveData<ServiceEvent> getServiceEvents() {
        return serviceStatus;
    }
}
