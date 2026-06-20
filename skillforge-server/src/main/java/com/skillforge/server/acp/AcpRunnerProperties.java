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
     *
     * <p>Only consulted in the throwaway temp-dir mode (when {@link #repoRoot} is
     * blank). In git-worktree mode the workspace lives under {@link #worktreeRoot}.
     */
    private String workspaceRoot;

    /**
     * ACP-EXTERNAL-AGENT worktree mode (option A): the git repository cc works on.
     * When set (non-blank), each cc run executes in a fresh {@code git worktree} of
     * this repo on its own branch ({@link #worktreeBranchPrefix} + the sub-session
     * id), based on {@link #worktreeBaseRef} — so cc edits the REAL codebase but is
     * isolated to a reviewable branch and never touches the main working tree.
     *
     * <p>When blank (default), the runner keeps the legacy throwaway temp-dir
     * behavior (cc works in an empty scratch dir) — non-breaking opt-in.
     */
    private String repoRoot;

    /**
     * The commit-ish each worktree branch is based on. Default {@code "HEAD"} (build
     * on the repo's current checkout). Set to e.g. {@code "main"} to base runs on a
     * fixed branch for clean PRs. Only used in worktree mode.
     */
    private String worktreeBaseRef = "HEAD";

    /**
     * Branch-name prefix for per-run worktree branches; the sub-session id is
     * appended. Only used in worktree mode. Must form a valid git branch name.
     */
    private String worktreeBranchPrefix = "acp/cc-";

    /**
     * Where per-run worktree directories are created (one sub-dir per run). MUST be
     * OUTSIDE {@link #repoRoot} (a worktree cannot nest in its own repo). If blank,
     * falls back to {@link #workspaceRoot} then an OS temp dir. Only used in
     * worktree mode.
     */
    private String worktreeRoot;

    /**
     * Whether to KEEP the worktree + branch after a run finishes (default true) so
     * the changes are reviewable / can become a PR — the deliberate "leave the
     * branch for review, do not auto-merge" policy. When false, a finished run runs
     * {@code git worktree remove --force} + {@code git branch -D} (throwaway). Only
     * used in worktree mode.
     */
    private boolean keepWorktreeOnFinish = true;

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

    /**
     * P2-3a: grace delay (seconds) before the cc sub-session trace is finalized after
     * the cc prompt returns its {@code stopReason}. cc exports OTLP log events on a
     * ~1s schedule, so the last {@code api_request} / {@code subagent_completed} can
     * arrive AFTER the prompt completes. Finalizing instantly would miss those late
     * spans in the recomputed tool/event counts. The finalize is scheduled (NOT run on
     * the runner thread) {@code traceFinalizeGraceSeconds} after completion so late
     * events land first. Default 3s (> the 1s flush interval, with headroom). Set to 0
     * to finalize immediately (tests).
     */
    private long traceFinalizeGraceSeconds = 3;

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

    public String getRepoRoot() {
        return repoRoot;
    }

    public void setRepoRoot(String repoRoot) {
        this.repoRoot = repoRoot;
    }

    public String getWorktreeBaseRef() {
        return worktreeBaseRef;
    }

    public void setWorktreeBaseRef(String worktreeBaseRef) {
        this.worktreeBaseRef = (worktreeBaseRef == null || worktreeBaseRef.isBlank())
                ? "HEAD" : worktreeBaseRef;
    }

    public String getWorktreeBranchPrefix() {
        return worktreeBranchPrefix;
    }

    public void setWorktreeBranchPrefix(String worktreeBranchPrefix) {
        this.worktreeBranchPrefix = (worktreeBranchPrefix == null || worktreeBranchPrefix.isBlank())
                ? "acp/cc-" : worktreeBranchPrefix;
    }

    public String getWorktreeRoot() {
        return worktreeRoot;
    }

    public void setWorktreeRoot(String worktreeRoot) {
        this.worktreeRoot = worktreeRoot;
    }

    public boolean isKeepWorktreeOnFinish() {
        return keepWorktreeOnFinish;
    }

    public void setKeepWorktreeOnFinish(boolean keepWorktreeOnFinish) {
        this.keepWorktreeOnFinish = keepWorktreeOnFinish;
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

    public long getTraceFinalizeGraceSeconds() {
        return traceFinalizeGraceSeconds;
    }

    public void setTraceFinalizeGraceSeconds(long traceFinalizeGraceSeconds) {
        // 0 allowed (finalize immediately); negative coerced to the 3s default.
        this.traceFinalizeGraceSeconds = traceFinalizeGraceSeconds >= 0 ? traceFinalizeGraceSeconds : 3;
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
