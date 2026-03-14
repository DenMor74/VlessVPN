package com.v2ray.ang.service;

import android.os.ParcelFileDescriptor;
import android.util.Log;

/**
 * STUB-класс с точным именем пакета com.v2ray.ang.service.TProxyService.
 *
 * libhev-socks5-tunnel.so скомпилирована для v2rayNG и при JNI_OnLoad
 * ищет native методы в классе "com.v2ray.ang.service.TProxyService".
 * Если класс не найден — JNI RegisterNatives падает с SIGABRT.
 *
 * Этот класс должен существовать в APK под именно этим именем.
 * Реальная логика запуска/остановки находится в
 * com.vlessvpn.app.service.TProxyService — она просто вызывает
 * статические методы этого stub-класса.
 */
public class TProxyService {

    private static final String TAG = "TProxyService_stub";

    // ── JNI native методы — реализованы в libhev-socks5-tunnel.so ──────────
    public static native void TProxyStartService(String configPath, int fd);
    public static native void TProxyStopService();
    public static native long[] TProxyGetStats();

    // ── Загрузка библиотеки ─────────────────────────────────────────────────
    private static final boolean LOADED;
    static {
        boolean ok = false;
        try {
            System.loadLibrary("hev-socks5-tunnel");
            ok = true;
            Log.i(TAG, "libhev-socks5-tunnel.so загружена успешно");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libhev-socks5-tunnel.so не найдена: " + e.getMessage());
        }
        LOADED = ok;
    }

    public static boolean isLoaded() { return LOADED; }
}
