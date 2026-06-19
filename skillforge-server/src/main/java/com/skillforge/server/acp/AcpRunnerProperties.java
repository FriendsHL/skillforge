package com.skillforge.server.acp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the ACP external-agent runner (ACP-EXTERNAL-AGENT P1a-2).
 *
 * <p>Prefix {@code skillforge.acp}. All values have sane defaults so the runner
 * works out of the box in dev; production can override the adapter package, the
 * owning agent/user, the workspace root, and the per-run timeout.
 */
@ConfigurationProperties(prefix = "skillforge.acp")
public class AcpRunnerProperties {

    /**
     * The cc ACP adapter npm package launched via {@code npx --yes}. Defaults to
     * the renamed cc adapter (see {@link ProcessAcpTransport#DEFAULT_ADAPTER_PACKAGE}).
     */
    private String adapterPackage = ProcessAcpTransport.DEFAULT_ADAPTER_PACKAGE;

    /**
     * SkillForge agent id that owns the cc-run sub-session. If unset, the runner
     * falls back to any available agent so the sub-session is viewable. Set this
     * to a designated "external/cc" agent in production.
     */
    private Long agentId;

    /**
     * SkillForge user id that owns the cc-run sub-session. Defaults to 1 (the dev
     * admin in single-user dev) so the session shows up in the dashboard.
     */
    private Long userId = 1L;

    /**
     * Workspace root for the cc child process. If set, each run uses a fresh temp
     * directory created UNDER this root; if unset, a fresh OS temp directory is
     * used. NEVER the SkillForge repo root — cc would operate on our own source.
     */
    private String workspaceRoot;

    /**
     * Per-prompt deadline in seconds — how long a single cc run may take before the runner
     * cancels it. Default 1800 (30 min): real cc coding tasks (repo-wide ripgrep, many steps)
     * routinely exceed 5 min; 300s was too tight (observed: a run hit 304s mid-work). Matches the
     * SkillForge engine maxDurationMs (30 min). Override via skillforge.acp.prompt-timeout-seconds.
     * cc's own futures never time out by contract, so this deadline is the sole bound.
     */
    private long promptTimeoutSeconds = 1800;

    /**
     * Deadline in seconds for a human to answer a bridged cc permission request
     * (AC-3). If the user never answers, the bridge responds {@code cancelled} so
     * the cc session does not hang.
     */
    private long permissionTimeoutSeconds = 300;

    /**
     * ACP session permission mode for cc runs. Default {@code "auto"} = cc's model
     * classifier approves/denies tool permissions autonomously (no user prompt), so
     * cc completes without the user having to answer per-tool confirmation cards.
     *
     * <p>Set {@code "default"} to make cc prompt the user for dangerous operations
     * (AC-3) — only useful once the confirmation card reliably reaches the user
     * (today the card is bound to the cc sub-session the user cannot open, so every
     * prompt auto-cancels after the permission timeout and cc loops forever). Other
     * valid values: {@code acceptEdits} / {@code plan} / {@code dontAsk} /
     * {@code bypassPermissions}. Override via {@code skillforge.acp.permission-mode}.
     */
    private String permissionMode = "auto";

    /**
     * P2-1: OTLP/HTTP base URL the spawned cc telemetry exporter points at — the
     * SkillForge server's own base (the {@code OtlpReceiverController} listens at
     * {@code <endpoint>/v1/logs} + {@code /v1/metrics}). cc appends the
     * {@code /v1/<signal>} path itself, so this is the BASE only. Default
     * {@code http://localhost:8080} (the dev server). Set blank to DISABLE telemetry
     * env injection (no OTLP env reaches cc).
     */
    private String otlpEndpoint = "http://localhost:8080";

    /**
     * security-W3: max threads for the bounded permission-wait pool. Each in-flight
     * permission request holds one thread for up to {@code permissionTimeoutSeconds}.
     * Bounded to prevent thread-exhaustion DoS; on overflow the bridge responds
     * {@code cancelled} inline.
     */
    private int permissionWaitMaxThreads = 16;

    public String getAdapterPackage() {
        return adapterPackage;
    }

    public void setAdapterPackage(String adapterPackage) {
        this.adapterPackage = (adapterPackage == null || adapterPackage.isBlank())
                ? ProcessAcpTransport.DEFAULT_ADAPTER_PACKAGE : adapterPackage;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId != null ? userId : 1L;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public long getPromptTimeoutSeconds() {
        return promptTimeoutSeconds;
    }

    public void setPromptTimeoutSeconds(long promptTimeoutSeconds) {
        this.promptTimeoutSeconds = promptTimeoutSeconds > 0 ? promptTimeoutSeconds : 1800;
    }

    public long getPermissionTimeoutSeconds() {
        return permissionTimeoutSeconds;
    }

    public void setPermissionTimeoutSeconds(long permissionTimeoutSeconds) {
        this.permissionTimeoutSeconds = permissionTimeoutSeconds > 0 ? permissionTimeoutSeconds : 300;
    }

    public int getPermissionWaitMaxThreads() {
        return permissionWaitMaxThreads;
    }

    public void setPermissionWaitMaxThreads(int permissionWaitMaxThreads) {
        this.permissionWaitMaxThreads = permissionWaitMaxThreads > 0 ? permissionWaitMaxThreads : 16;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(String permissionMode) {
        this.permissionMode = (permissionMode == null || permissionMode.isBlank())
                ? "auto" : permissionMode;
    }

    public String getOtlpEndpoint() {
        return otlpEndpoint;
    }

    public void setOtlpEndpoint(String otlpEndpoint) {
        this.otlpEndpoint = otlpEndpoint;
    }
}
