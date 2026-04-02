package com.vlessvpn.app.model;

import com.google.gson.annotations.SerializedName;

public class ConfigUrlItem {

    @SerializedName("url")
    private String url;

    @SerializedName("enabled")
    private boolean enabled;

    public ConfigUrlItem() {
        // Для Gson
    }

    public ConfigUrlItem(String url, boolean enabled) {
        this.url = url;
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}