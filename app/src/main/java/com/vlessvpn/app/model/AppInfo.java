package com.vlessvpn.app.model;

import android.graphics.drawable.Drawable;

/**
 * AppInfo — информация о приложении для чёрного списка.
 */
public class AppInfo {
    public String packageName;
    public String appName;
    public Drawable icon;
    public boolean isSelected;  // В чёрном списке или нет

    public AppInfo(String packageName, String appName, Drawable icon, boolean isSelected) {
        this.packageName = packageName;
        this.appName = appName;
        this.icon = icon;
        this.isSelected = isSelected;
    }
}