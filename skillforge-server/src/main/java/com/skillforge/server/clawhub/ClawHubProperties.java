package com.skillforge.server.clawhub;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ClawHub 集成配置(application.yml 中以 "clawhub.*" 前缀绑定)。
 *
 * 默认值刻意保守:
 * - install 操作即使在 Agent auto 模式下也强制 ask_user 二次确认。
 * - zip 大小 / entry 数量 / 压缩比阈值低,只允许"小而精"的 skill 包通过。
 */
@ConfigurationProperties(prefix = "clawhub")
public class ClawHubProperties {

    /** 是否启用 ClawHub 集成。关闭后 ClawHubSkill 仍然注册但调用会被拒绝。 */
    private boolean enabled = true;

    /** API base URL。默认 https://clawhub.ai */
    private String baseUrl = "https://clawhub.ai";

    /** install 解压目标根目录。最终路径 = {installDir}/{slug}/{version}/ */
    private String installDir = "./data/skills/clawhub";

    /** 即使在 auto 执行模式下,install 是否仍强制走 ask_user。默认 true(强烈建议)。 */
    private boolean requireAskUser = true;

    /** ask_user 等待用户回答的超时秒数。 */
    private long askUserTimeoutSeconds = 600;

    /** download 网络请求超时(毫秒)。 */
    private int httpTimeoutMs = 30_000;

    // ======== 安全检查阈值 ========

    /** zip 内允许的最大条目数(超过即 reject)。 */
    private int maxZipEntries = 200;

    /** 解压后总字节上限(zip bomb 防护)。 */
    private long maxUncompressedBytes = 10L * 1024 * 1024; // 10 MB

    /** 单条目解压后字节上限。 */
    private long maxSingleEntryBytes = 2L * 1024 * 1024; // 2 MB

    /** 压缩比阈值(uncompressed / compressed)。超过判定为 zip bomb。 */
    private int maxCompressionRatio = 100;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getInstallDir() { return installDir; }
    public void setInstallDir(String installDir) { this.installDir = installDir; }

    public boolean isRequireAskUser() { return requireAskUser; }
    public void setRequireAskUser(boolean requireAskUser) { this.requireAskUser = requireAskUser; }

    public long getAskUserTimeoutSeconds() { return askUserTimeoutSeconds; }
    public void setAskUserTimeoutSeconds(long askUserTimeoutSeconds) { this.askUserTimeoutSeconds = askUserTimeoutSeconds; }

    public int getHttpTimeoutMs() { return httpTimeoutMs; }
    public void setHttpTimeoutMs(int httpTimeoutMs) { this.httpTimeoutMs = httpTimeoutMs; }

    public int getMaxZipEntries() { return maxZipEntries; }
    public void setMaxZipEntries(int maxZipEntries) { this.maxZipEntries = maxZipEntries; }

    public long getMaxUncompressedBytes() { return maxUncompressedBytes; }
    public void setMaxUncompressedBytes(long maxUncompressedBytes) { this.maxUncompressedBytes = maxUncompressedBytes; }

    public long getMaxSingleEntryBytes() { return maxSingleEntryBytes; }
    public void setMaxSingleEntryBytes(long maxSingleEntryBytes) { this.maxSingleEntryBytes = maxSingleEntryBytes; }

    public int getMaxCompressionRatio() { return maxCompressionRatio; }
    public void setMaxCompressionRatio(int maxCompressionRatio) { this.maxCompressionRatio = maxCompressionRatio; }
}
