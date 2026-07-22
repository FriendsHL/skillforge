package com.skillforge.server.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "skillforge.media.ark.image")
public class ArkImageProperties {

    private boolean enabled;
    private String apiKey;
    private String baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3";
    private String model = "doubao-seedream-5.0-lite";
    private int timeoutSeconds = 120;
    private long maxDownloadBytes = 10L * 1024 * 1024;
    private List<String> allowedDownloadHosts = List.of(
            "ark-acg-cn-beijing.tos-cn-beijing.volces.com");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public long getMaxDownloadBytes() { return maxDownloadBytes; }
    public void setMaxDownloadBytes(long maxDownloadBytes) { this.maxDownloadBytes = maxDownloadBytes; }
    public List<String> getAllowedDownloadHosts() { return allowedDownloadHosts; }
    public void setAllowedDownloadHosts(List<String> allowedDownloadHosts) {
        this.allowedDownloadHosts = allowedDownloadHosts == null ? List.of() : List.copyOf(allowedDownloadHosts);
    }
}
