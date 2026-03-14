package com.vlessvpn.app;

import android.app.Application;
import com.vlessvpn.app.util.CrashHandler;
import com.vlessvpn.app.core.V2RayManager;
import com.vlessvpn.app.util.FileLogger;

public class VpnApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Сначала перехватчик крашей — чтобы поймать даже ошибки инициализации
        CrashHandler.install(this);

        // 2. Файловый логгер
        FileLogger.init(this);
        FileLogger.i("App", "=== VpnApplication.onCreate() ===");
        // Инициализируем libv2ray один раз при старте приложения
        V2RayManager.initEnvOnce(this);
        FileLogger.i("App", "Android: " + android.os.Build.VERSION.RELEASE
            + " API " + android.os.Build.VERSION.SDK_INT);
        FileLogger.i("App", "Устройство: " + android.os.Build.MANUFACTURER
            + " " + android.os.Build.MODEL);
    }
}
