package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skillforge.session-message-store")
public class SessionMessageStoreProperties {

    /** 是否启用行存储读取；关闭时仅走 legacy messagesJson。 */
    private boolean rowReadEnabled = true;

    /** 是否启用行存储写入；关闭时仅写 legacy messagesJson。 */
    private boolean rowWriteEnabled = true;

    /** 是否启用双读校验（行存储 vs legacy）一致性日志。 */
    private boolean dualReadVerifyEnabled = false;

    /** 是否启用启动期 legacy -> row 回填任务。 */
    private boolean backfillEnabled = true;

    /** 单次启动最多回填的 session 数量，防止阻塞过久。 */
    private int maxBackfillSessionsPerStartup = 300;

    public boolean isRowReadEnabled() {
        return rowReadEnabled;
    }

    public void setRowReadEnabled(boolean rowReadEnabled) {
        this.rowReadEnabled = rowReadEnabled;
    }

    public boolean isRowWriteEnabled() {
        return rowWriteEnabled;
    }

    public void setRowWriteEnabled(boolean rowWriteEnabled) {
        this.rowWriteEnabled = rowWriteEnabled;
    }

    public boolean isDualReadVerifyEnabled() {
        return dualReadVerifyEnabled;
    }

    public void setDualReadVerifyEnabled(boolean dualReadVerifyEnabled) {
        this.dualReadVerifyEnabled = dualReadVerifyEnabled;
    }

    public boolean isBackfillEnabled() {
        return backfillEnabled;
    }

    public void setBackfillEnabled(boolean backfillEnabled) {
        this.backfillEnabled = backfillEnabled;
    }

    public int getMaxBackfillSessionsPerStartup() {
        return maxBackfillSessionsPerStartup;
    }

    public void setMaxBackfillSessionsPerStartup(int maxBackfillSessionsPerStartup) {
        this.maxBackfillSessionsPerStartup = maxBackfillSessionsPerStartup;
    }
}
