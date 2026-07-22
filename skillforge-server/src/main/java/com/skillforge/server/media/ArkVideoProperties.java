package com.skillforge.server.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "skillforge.media.ark.video")
public class ArkVideoProperties {
    private boolean enabled;
    private String apiKey;
    private String baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3";
    private String model = "doubao-seedance-1-5-pro-251215";
    private int timeoutSeconds = 30;
    private long maxDownloadBytes = 200L * 1024 * 1024;
    private List<String> allowedDownloadHosts = List.of("ark-content-generation-cn-beijing.tos-cn-beijing.volces.com");
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
    public String getApiKey() { return apiKey; } public void setApiKey(String v) { apiKey = v; }
    public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String v) { baseUrl = v; }
    public String getModel() { return model; } public void setModel(String v) { model = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; } public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
    public long getMaxDownloadBytes() { return maxDownloadBytes; } public void setMaxDownloadBytes(long v) { maxDownloadBytes = v; }
    public List<String> getAllowedDownloadHosts() { return allowedDownloadHosts; }
    public void setAllowedDownloadHosts(List<String> v) { allowedDownloadHosts = v == null ? List.of() : List.copyOf(v); }
}

