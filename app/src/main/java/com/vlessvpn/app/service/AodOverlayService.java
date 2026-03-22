package com.vlessvpn.app.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * AOD Overlay — статус VPN поверх Always On Display Samsung.
 *
 * Логика:
 * - Показываем только когда displayState = DOZE или DOZE_SUSPEND
 * - reAddOverlay только при входе в AOD или когда данные реально изменились
 * - НЕ перерисовываем при каждом 3↔4 переключении (мигание)
 */
public class AodOverlayService extends AccessibilityService {

    public static final String ACTION_VPN_STATUS  = "com.vlessvpn.app.AOD_VPN_STATUS";
    public static final String EXTRA_CONNECTED    = "connected";
    public static final String EXTRA_SERVER       = "server";
    public static final String EXTRA_IP           = "ip";
    public static final String EXTRA_SERVERS_STAT = "servers_stat";
    public static final String EXTRA_STATUS_MSG   = "status_msg";

    public static boolean isRunning = false;

    private WindowManager wm;
    private LinearLayout  overlay;
    private TextView      tvVpn;     // 🟢 VPN • сервер
    private TextView      tvIp;      // IP город, страна
    private TextView      tvServers; // Серверов: 8/30
    private TextView      tvStatus;  // Статус

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean inAod      = false;  // сейчас в AOD режиме
    private boolean vpnActive  = false;
    private boolean overlayAdded = false;

    // Данные — храним для сравнения чтобы не мигать лишний раз
    private String lastServer = null;
    private String lastIp     = null;
    private String lastStat   = null;
    private String lastStatus = null;
    private String shownServer = "", shownIp = "", shownStat = "", shownStatus = "";

    // Антиожог — раз в 60 сек сдвигаем позицию
    private int burnStep = 0;
    private final int[] burnDx = {0, 6, -6, 4, -4, 8, -8, 3, -3};
    private final int[] burnDy = {0, -4, 4, 6, -6, -2, 2, 5, -5};
    private final Runnable burnRunnable = new Runnable() {
        @Override public void run() {
            burnStep = (burnStep + 1) % burnDx.length;
            if (inAod && vpnActive && overlayAdded) {
                repositionOverlay();
            }
            handler.postDelayed(this, 60_000L);
        }
    };

    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            boolean connected = intent.getBooleanExtra(EXTRA_CONNECTED, false);
            vpnActive = connected;

            String statusMsg = intent.getStringExtra(EXTRA_STATUS_MSG);
            String server    = intent.getStringExtra(EXTRA_SERVER);
            String ip        = intent.getStringExtra(EXTRA_IP);
            String stat      = intent.getStringExtra(EXTRA_SERVERS_STAT);

            if (server    != null) lastServer = server;
            if (ip        != null) lastIp     = ip;
            if (stat      != null) lastStat   = stat;
            if (statusMsg != null) lastStatus = statusMsg;

            if (!connected) {
                removeOverlayNow();
                return;
            }
            if (inAod) updateIfChanged();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                inAod = false;
                removeOverlayNow();
            }
        }
    };

    @Override
    public void onServiceConnected() {
        isRunning = true;
        android.util.Log.i("AodOverlay", "Сервис запущен");

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes   = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        setServiceInfo(info);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildViews();

        // DisplayListener — отслеживаем вход/выход из AOD
        android.hardware.display.DisplayManager dm =
            (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm != null) {
            dm.registerDisplayListener(new android.hardware.display.DisplayManager.DisplayListener() {
                private boolean wasInAod = false;

                @Override public void onDisplayAdded(int id) {}
                @Override public void onDisplayRemoved(int id) {}
                @Override public void onDisplayChanged(int id) {
                    if (id != android.view.Display.DEFAULT_DISPLAY) return;
                    android.view.Display display = dm.getDisplay(id);
                    if (display == null) return;
                    int state = display.getState();

                    boolean nowInAod = state == android.view.Display.STATE_DOZE
                            || state == android.view.Display.STATE_DOZE_SUSPEND;

                    if (nowInAod && !wasInAod) {
                        // Вошли в AOD — показываем
                        inAod = true;
                        wasInAod = true;
                        android.util.Log.i("AodOverlay", "Вошли в AOD state=" + state);
                        if (vpnActive) addOverlayFull();
                    } else if (!nowInAod && wasInAod) {
                        // Вышли из AOD
                        inAod = false;
                        wasInAod = false;
                        android.util.Log.i("AodOverlay", "Вышли из AOD state=" + state);
                        removeOverlayNow();
                    }
                    // Переходы 3↔4 внутри AOD — игнорируем (не перерисовываем)
                }
            }, handler);
        }

        // VPN broadcast
        IntentFilter vpnFilter = new IntentFilter(ACTION_VPN_STATUS);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            registerReceiver(vpnReceiver, vpnFilter, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(vpnReceiver, vpnFilter);

        // Разблокировка
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED);
        else
            registerReceiver(screenReceiver, screenFilter);

        handler.postDelayed(burnRunnable, 60_000L);
    }

    /** Обновляем только если данные реально изменились */
    private void updateIfChanged() {
        String s  = lastServer != null ? lastServer : "";
        String i  = lastIp     != null ? lastIp     : "";
        String st = lastStat   != null ? lastStat   : "";
        String sm = lastStatus != null ? lastStatus : "";

        boolean changed = !s.equals(shownServer) || !i.equals(shownIp)
                || !st.equals(shownStat) || !sm.equals(shownStatus);

        if (!changed && overlayAdded) return; // ничего нового — не мигаем

        shownServer = s;
        shownIp     = i;
        shownStat   = st;
        shownStatus = sm;

        // В AOD Samsung игнорирует setText — нужен remove+add для перерисовки
        addOverlayFull();
    }

    /** Добавляет overlay с нуля (remove+add) */
    private void addOverlayFull() {
        handler.post(() -> {
            if (wm == null) return;
            applyTexts();
            try { wm.removeView(overlay); } catch (Exception ignored) {}
            overlayAdded = false;

            WindowManager.LayoutParams p = makeParams();
            try {
                wm.addView(overlay, p);
                overlayAdded = true;
                android.util.Log.i("AodOverlay", "Overlay добавлен");
            } catch (Exception e) {
                android.util.Log.e("AodOverlay", "addView: " + e.getMessage());
            }
        });
    }

    /** Только сдвиг позиции (антиожог) без пересоздания */
    private void repositionOverlay() {
        handler.post(() -> {
            if (wm == null || !overlayAdded) return;
            try {
                wm.updateViewLayout(overlay, makeParams());
            } catch (Exception ignored) {
                // Если updateViewLayout не работает в AOD — пересоздаём
                addOverlayFull();
            }
        });
    }

    private void applyTexts() {
        String server = lastServer != null ? lastServer : "";
        String ip     = lastIp     != null ? lastIp     : "";
        String stat   = lastStat   != null ? lastStat   : "";
        String status = lastStatus != null ? lastStatus : "";

        if (tvVpn     != null) tvVpn.setText("🟢 VPN" + (!server.isEmpty() ? "  •  " + server : ""));
        if (tvIp      != null) { tvIp.setText(ip);     tvIp.setVisibility(!ip.isEmpty()     ? View.VISIBLE : View.GONE); }
        if (tvServers != null) { tvServers.setText(!stat.isEmpty() ? "Серверов: " + stat : "");
                                 tvServers.setVisibility(!stat.isEmpty() ? View.VISIBLE : View.GONE); }
        if (tvStatus  != null) { tvStatus.setText(status);
                                 tvStatus.setVisibility(!status.isEmpty() ? View.VISIBLE : View.GONE); }
    }

    private WindowManager.LayoutParams makeParams() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.CENTER;
        p.x = burnDx[burnStep];
        p.y = -dp(120) + burnDy[burnStep];
        return p;
    }

    private void removeOverlayNow() {
        handler.post(() -> {
            try { if (overlay != null) wm.removeView(overlay); } catch (Exception ignored) {}
            overlayAdded = false;
        });
    }

    private void buildViews() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(20), dp(16), dp(20), dp(16));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x55FFFFFF);
        overlay.setBackground(bg);

        tvVpn     = makeText(15, true,  0xFFFFFFFF);
        tvIp      = makeText(12, false, 0xFF88EE88);
        tvServers = makeText(11, false, 0xFFAAAAAA);
        tvStatus  = makeText(11, false, 0xFF6699BB);

        for (TextView tv : new TextView[]{tvVpn, tvIp, tvServers, tvStatus}) {
            tv.setMaxWidth(dp(380));
            overlay.addView(tv);
        }
    }

    public static void sendStatus(Context ctx, boolean connected,
                                  String server, String ip, String serversStat) {
        Intent i = new Intent(ACTION_VPN_STATUS);
        i.putExtra(EXTRA_CONNECTED, connected);
        if (server      != null) i.putExtra(EXTRA_SERVER,       server);
        if (ip          != null) i.putExtra(EXTRA_IP,           ip);
        if (serversStat != null) i.putExtra(EXTRA_SERVERS_STAT, serversStat);
        i.setPackage(ctx.getPackageName());
        ctx.sendBroadcast(i);
    }

    public static void sendStatusMsg(Context ctx, String msg) {
        Intent i = new Intent(ACTION_VPN_STATUS);
        i.putExtra(EXTRA_CONNECTED, true);
        i.putExtra(EXTRA_STATUS_MSG, msg);
        i.setPackage(ctx.getPackageName());
        ctx.sendBroadcast(i);
    }

    private TextView makeText(int sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        isRunning = false;
        handler.removeCallbacks(burnRunnable);
        try { unregisterReceiver(vpnReceiver);  } catch (Exception ignored) {}
        try { unregisterReceiver(screenReceiver);} catch (Exception ignored) {}
        removeOverlayNow();
        super.onDestroy();
    }
}
