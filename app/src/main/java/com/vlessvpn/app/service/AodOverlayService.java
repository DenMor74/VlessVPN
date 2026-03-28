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
 * - ════════════════════════════════════════════════════════════════
 * - ← НОВОЕ: Проверяем VpnTunnelService.isRunning перед показом
 * - ════════════════════════════════════════════════════════════════
 */
public class AodOverlayService extends AccessibilityService {

    public static final String ACTION_VPN_STATUS  = "com.vlessvpn.app.AOD_VPN_STATUS";
    public static final String EXTRA_CONNECTED    = "connected";
    public static final String EXTRA_SERVER       = "server";
    public static final String EXTRA_IP           = "ip";
    public static final String EXTRA_SERVERS_STAT = "servers_stat";
    public static final String EXTRA_STATUS_MSG   = "status_msg";

    public static boolean isRunning = false;

    // ═══ НАСТРОЙКИ ОТОБРАЖЕНИЯ ═══════════════════════════════════════════════
    private static final int  POSITION_Y_DP   = -80;
    private static final int  TEXT_VPN_SP      = 15;
    private static final int  TEXT_IP_SP        = 12;
    private static final int  TEXT_SERVERS_SP   = 11;
    private static final int  TEXT_STATUS_SP    = 11;
    private static final int  COLOR_VPN         = 0xFFFFFFFF;
    private static final int  COLOR_IP          = 0xFF88EE88;
    private static final int  COLOR_SERVERS     = 0xFFAAAAAA;
    private static final int  COLOR_STATUS      = 0xFF6699BB;
    private static final int  COLOR_BG          = 0xFF000000;
    private static final int  COLOR_BORDER      = 0x55FFFFFF;
    private static final int  PADDING_DP        = 20;
    private static final int  MAX_WIDTH_DP      = 380;
    // ═════════════════════════════════════════════════════════════════════════

    private WindowManager wm;
    private LinearLayout  overlay;
    private TextView      tvVpn;
    private TextView      tvIp;
    private TextView      tvServers;
    private TextView      tvStatus;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean inAod      = false;
    private boolean vpnActive  = false;
    private boolean overlayAdded = false;

    private String lastServer = null;
    private String lastIp     = null;
    private String lastStat   = null;
    private String lastStatus = null;
    private String shownServer = "", shownIp = "", shownStat = "", shownStatus = "";

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

    // ════════════════════════════════════════════════════════════════
    // ← ИСПРАВЛЕНО: vpnReceiver теперь проверяет VpnTunnelService.isRunning
    // ════════════════════════════════════════════════════════════════
    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            boolean connected = intent.getBooleanExtra(EXTRA_CONNECTED, false);

            // ════════════════════════════════════════════════════════════════
            // ← НОВОЕ: Двойная проверка — broadcast + реальное состояние VPN
            // ════════════════════════════════════════════════════════════════
            boolean vpnReallyRunning = com.vlessvpn.app.service.VpnTunnelService.isRunning;

            // Если broadcast говорит "connected=true" но VPN не запущен — игнорируем
            if (connected && !vpnReallyRunning) {
                android.util.Log.d("AodOverlay", "Игнорируем broadcast (VPN не запущен)");
                return;
            }

            vpnActive = connected && vpnReallyRunning;

            String statusMsg = intent.getStringExtra(EXTRA_STATUS_MSG);
            String server    = intent.getStringExtra(EXTRA_SERVER);
            String ip        = intent.getStringExtra(EXTRA_IP);
            String stat      = intent.getStringExtra(EXTRA_SERVERS_STAT);

            // Если сменился сервер — сбрасываем IP и статус
            if (server != null && !server.equals(lastServer)) {
                lastIp     = null;
                lastStatus = null;
                shownIp    = "X"; // форсируем перерисовку
                shownStatus= "X";
            }
            if (server    != null) lastServer = server;
            if (ip        != null) lastIp = ip.isEmpty() ? null : ip;
            if (stat      != null) lastStat   = stat;
            if (statusMsg != null) {
                lastStatus = statusMsg;
                if (statusMsg.startsWith("🔬")) {
                    lastIp = statusMsg.replace("🔬 ", "").replace("🔬", "");
                }
            }

            if (!vpnActive) {
                // ════════════════════════════════════════════════════════════════
                // ← VPN отключён — скрываем overlay НЕМЕДЛЕННО (без showDisconnected)
                // ════════════════════════════════════════════════════════════════
                android.util.Log.i("AodOverlay", "VPN отключён — скрываем overlay");
                lastServer = null;
                lastIp     = null;
                lastStatus = null;
                lastStat   = null;
                shownServer = "";
                shownIp     = "";
                shownStat   = "";
                shownStatus = "";
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
                        inAod = true;
                        wasInAod = true;
                        // ════════════════════════════════════════════════════════════════
                        // ← Синхронизируем vpnActive с реальным состоянием VPN
                        // ════════════════════════════════════════════════════════════════
                        vpnActive = com.vlessvpn.app.service.VpnTunnelService.isRunning;
                        android.util.Log.i("AodOverlay", "Вошли в AOD state=" + state
                                + " vpnActive=" + vpnActive);
                        if (vpnActive) addOverlayFull();
                    } else if (!nowInAod && wasInAod) {
                        inAod = false;
                        wasInAod = false;
                        android.util.Log.i("AodOverlay", "Вышли из AOD state=" + state);
                        removeOverlayNow();
                    }
                }
            }, handler);
        }

        IntentFilter vpnFilter = new IntentFilter(ACTION_VPN_STATUS);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            registerReceiver(vpnReceiver, vpnFilter, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(vpnReceiver, vpnFilter);

        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED);
        else
            registerReceiver(screenReceiver, screenFilter);

        handler.postDelayed(burnRunnable, 60_000L);
    }

    private void updateIfChanged() {
        String s  = lastServer != null ? lastServer : "";
        String i  = lastIp     != null ? lastIp     : "";
        String st = lastStat   != null ? lastStat   : "";
        String sm = lastStatus != null ? lastStatus : "";

        boolean changed = !s.equals(shownServer) || !i.equals(shownIp)
                || !st.equals(shownStat) || !sm.equals(shownStatus);

        if (!changed && overlayAdded) return;

        shownServer = s;
        shownIp     = i;
        shownStat   = st;
        shownStatus = sm;

        addOverlayFull();
    }

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
                android.util.Log.i("AodOverlay", "Overlay добавлен vpnActive=" + vpnActive);
            } catch (Exception e) {
                android.util.Log.e("AodOverlay", "addView: " + e.getMessage());
            }
        });
    }

    private void repositionOverlay() {
        handler.post(() -> {
            if (wm == null || !overlayAdded) return;
            try {
                wm.updateViewLayout(overlay, makeParams());
            } catch (Exception ignored) {
                addOverlayFull();
            }
        });
    }

    private void applyTexts() {
        String server = lastServer != null ? lastServer : "";
        String ip     = lastIp     != null ? lastIp     : "";
        String stat   = lastStat   != null ? lastStat   : "";
        String status = lastStatus != null ? lastStatus : "";

        if (!vpnActive) {
            if (tvVpn    != null) tvVpn.setText("🔴 VPN");
            if (tvIp     != null) tvIp.setVisibility(View.GONE);
            if (tvServers != null) {
                tvServers.setText(!stat.isEmpty() ? "Серверов: " + stat : "");
                tvServers.setVisibility(!stat.isEmpty() ? View.VISIBLE : View.GONE);
            }
            if (tvStatus != null) {
                tvStatus.setText(!status.isEmpty() ? status : "Отключено");
                tvStatus.setVisibility(View.VISIBLE);
            }
            return;
        }

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
        p.y = dp(POSITION_Y_DP) + burnDy[burnStep];
        return p;
    }

    private final Runnable hideRunnable = this::removeOverlayNow;

    // ════════════════════════════════════════════════════════════════
    // ← УДАЛИТЬ showDisconnected() — больше не используется
    // ════════════════════════════════════════════════════════════════
    // ❌ Удалить весь метод showDisconnected()

    private void removeOverlayNow() {
        handler.post(() -> {
            try { if (overlay != null) wm.removeView(overlay); } catch (Exception ignored) {}
            overlayAdded = false;
        });
    }

    private void buildViews() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(PADDING_DP), dp(PADDING_DP-4), dp(PADDING_DP), dp(PADDING_DP-4));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(COLOR_BG);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), COLOR_BORDER);
        overlay.setBackground(bg);

        tvVpn     = makeText(TEXT_VPN_SP,     true,  COLOR_VPN);
        tvIp      = makeText(TEXT_IP_SP,      false, COLOR_IP);
        tvServers = makeText(TEXT_SERVERS_SP, false, COLOR_SERVERS);
        tvStatus  = makeText(TEXT_STATUS_SP,  false, COLOR_STATUS);

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

    // ════════════════════════════════════════════════════════════════
    // ← ИСПРАВЛЕНО: sendStatusMsg больше НЕ хардкодит connected=true
    // ════════════════════════════════════════════════════════════════
    public static void sendStatusMsg(Context ctx, String msg) {
        Intent i = new Intent(ACTION_VPN_STATUS);
        // ════════════════════════════════════════════════════════════════
        // ← НОВОЕ: Проверяем реальное состояние VPN
        // ════════════════════════════════════════════════════════════════
        boolean vpnRunning = com.vlessvpn.app.service.VpnTunnelService.isRunning;
        i.putExtra(EXTRA_CONNECTED, vpnRunning);  // ← Не true, а реальное состояние!
        i.putExtra(EXTRA_STATUS_MSG, msg);
        i.setPackage(ctx.getPackageName());
        ctx.sendBroadcast(i);

        android.util.Log.d("AodOverlay", "sendStatusMsg: vpnRunning=" + vpnRunning + " msg=" + msg);
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