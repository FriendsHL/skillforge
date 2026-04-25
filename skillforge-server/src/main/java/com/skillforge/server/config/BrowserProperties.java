package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * BrowserTool 相关配置。
 * <p>
 * 配置示例:
 * <pre>
 * skillforge:
 *   browser:
 *     profile-dir: ./data/browser-profile
 *     default-timeout-ms: 30000
 *     login-timeout-seconds: 300
 * </pre>
 */
@ConfigurationProperties(prefix = "skillforge.browser")
public class BrowserProperties {

    /**
     * 持久化用户数据目录。用户登录态(Cookies/LocalStorage/IndexedDB)会保存在此。
     */
    private String profileDir = "./data/browser-profile";

    /**
     * 页面操作默认超时 (ms)。
     */
    private int defaultTimeoutMs = 30000;

    /**
     * login action 等待用户完成手动登录的超时时间 (秒)。
     */
    private int loginTimeoutSeconds = 300;

    public String getProfileDir() {
        return profileDir;
    }

    public void setProfileDir(String profileDir) {
        this.profileDir = profileDir;
    }

    public int getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public int getLoginTimeoutSeconds() {
        return loginTimeoutSeconds;
    }

    public void setLoginTimeoutSeconds(int loginTimeoutSeconds) {
        this.loginTimeoutSeconds = loginTimeoutSeconds;
    }
}
