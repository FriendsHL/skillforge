package com.skillforge.core.engine;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.view.SessionSkillView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 循环上下文，贯穿整个 Agent Loop 生命周期。
 */
public class LoopContext {

    private AgentDefinition agentDefinition;
    private List<Message> messages;
    private String sessionId;
    private Long userId;
    /**
     * OBS-2 M1: trace id (UUID) 由 ChatService 在 chatStream / answerAsk / answerConfirmation
     * 入口生成，透传到 engine。AgentLoopEngine 使用此值作为 rootSpan id（AGENT_LOOP），
     * 让 t_llm_trace.trace_id == AGENT_LOOP span id 形成 trace lifecycle 锚点。
     */
    private String traceId;
    private String workingDirectory;
    private long totalInputTokens;
    private long totalOutputTokens;
    private int loopCount;
    private int maxLoops;
    private String executionMode = "ask";
    /** 外部(CancellationRegistry)设置为 true 后,下次循环迭代开头 / LLM 调用返回后会立即退出。 */
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    /** 当前活跃 SSE 流的取消动作(e.g. OkHttp Call::cancel)。cancel 时同步调用以中断阻塞读。 */
    private final AtomicReference<Runnable> streamCanceller = new AtomicReference<>();
    /**
     * 本轮 iteration 内是否已经执行过一次 compact。
     * 用于防止 "compact → 仍超限 → 再 compact → ..." 死循环, 也防止同一 iteration 里
     * engine-soft 已执行后 agent-tool 再次触发。每次 iteration 开头清零。
     */
    private boolean compactedThisIteration = false;

    /** Loop start timestamp for duration tracking. */
    private long loopStartTimeMs = System.currentTimeMillis();

    /** Per-tool call count tracking for high-frequency detection. */
    private final Map<String, Integer> toolCallCounts = new ConcurrentHashMap<>();

    /** Recent tool call hashes (toolName#inputHash) for no-progress detection. */
    private final List<String> recentToolHashes = new ArrayList<>();

    /** Outcome hashes per callHash: tracks if results are changing. */
    private final Map<String, List<Integer>> outcomeHashes = new ConcurrentHashMap<>();

    /** Consecutive compact failures for circuit breaker. */
    private int consecutiveCompactFailures = 0;

    /**
     * Epoch-millis when the compact breaker opened (failures >= threshold). 0 = closed.
     * See AgentLoopEngine#BREAKER_HALF_OPEN_WINDOW_MS for the half-open retry window.
     */
    private volatile long compactBreakerOpenedAt = 0L;

    /** For eval mode: override ClaudeProvider's default 300s stream timeout. -1 = use default. */
    private long maxLlmStreamTimeoutMs = -1;

    /** Thread-safe queue for user messages sent while the loop is running. */
    private final ConcurrentLinkedQueue<String> pendingUserMessages = new ConcurrentLinkedQueue<>();

    /** Tool names to exclude from the tool list (depth-aware filtering for multi-agent collab). */
    private Set<String> excludedSkillNames = Collections.emptySet();

    /** Tool names this agent is allowed to use. Null = all tools allowed. */
    private Set<String> allowedToolNames;

    /**
     * How long the session was idle before this run started, in seconds.
     * -1 = unknown / not set (cold cleanup will not trigger).
     * Set by the server layer (ChatService) from session.lastUserMessageAt.
     */
    private long sessionIdleSeconds = -1;

    /**
     * Set by {@code LifecycleHookLoopAdapter} when a synchronous hook returns ABORT. The engine
     * checks this flag at iteration boundaries and exits cleanly. Kept separate from
     * {@link #cancelRequested} so observability can distinguish user-cancel vs. hook-abort.
     */
    private volatile boolean abortedByHook = false;
    /** Optional human-readable reason set alongside {@link #abortedByHook}. */
    private volatile String abortedByHookReason;

    /**
     * W11 micro-optimization: cached root-session resolution for install confirmation.
     * Populated on first lookup, reused for subsequent install commands within the same loop.
     * {@code transient} (never persisted) — start-of-loop value is {@code null}.
     */
    private transient String rootSessionIdCache;

    /**
     * Memory v2 (PR-2): ids of memories that the {@code memoryProvider} injected into the
     * system prompt at loop start. Forwarded into {@link com.skillforge.core.skill.SkillContext}
     * on every tool dispatch so tools like {@code memory_search} can exclude already-injected
     * memories from their results (avoids double-presenting the same content). Defaults to an
     * immutable empty set; populated by {@link AgentLoopEngine} after calling
     * {@code memoryProvider.apply(userId, userMessage)}.
     */
    private Set<Long> injectedMemoryIds = Collections.emptySet();

    /**
     * Plan r2 §5: per-session skill 授权视图（system + user 包级 skills）。
     * 引擎 collectTools 的 SkillDefinition 段、executeToolCall 的 skill 查找都从这里读。
     * <p>{@code null} 仅出现在未注入的旧调用路径（向后兼容）；引擎处理时按"无授权 skill 包"语义。
     */
    private SessionSkillView skillView;

    /**
     * Plan r2 §5 B-4：第 N 次 NOT_ALLOWED 触发反 hijack 短路时由 executeToolCall 置 true。
     * 主循环每个 tool_use round 末尾识别 → 立刻终止本 turn，避免 LLM 死循环烧 token。
     * 跨 turn 累计；session 关闭时随 LoopContext 释放。
     */
    private volatile boolean abortToolUse = false;

    /** Per-skill NOT_ALLOWED 计数（B-4 反 hijack）。跨 turn 累计。 */
    private final ConcurrentHashMap<String, Integer> notAllowedCount = new ConcurrentHashMap<>();

    public LoopContext() {
        this.messages = new ArrayList<>();
        this.maxLoops = 25;
    }

    /** 请求取消当前循环(幂等)。同时中断正在进行的 SSE 流读取。 */
    public void requestCancel() {
        cancelRequested.set(true);
        Runnable canceller = streamCanceller.get();
        if (canceller != null) {
            try { canceller.run(); } catch (Exception ignored) {}
        }
    }

    /** Provider 在 SSE 流开始时调用,注册取消动作。设置后立即检查是否已取消,防 TOCTOU race。 */
    public void setStreamCanceller(Runnable r) {
        streamCanceller.set(r);
        // 防止 requestCancel 先于 setStreamCanceller 执行导致 call.cancel() 被跳过
        if (r != null && cancelRequested.get()) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    /** Provider 在 SSE 流结束后调用,清除取消动作引用。 */
    public void clearStreamCanceller() {
        streamCanceller.set(null);
    }

    /** 是否已请求取消。 */
    public boolean isCancelled() {
        return cancelRequested.get();
    }

    public AgentDefinition getAgentDefinition() {
        return agentDefinition;
    }

    public void setAgentDefinition(AgentDefinition agentDefinition) {
        this.agentDefinition = agentDefinition;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** OBS-2 M1: trace id (UUID) — see field doc. */
    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public long getTotalInputTokens() {
        return totalInputTokens;
    }

    public void setTotalInputTokens(long totalInputTokens) {
        this.totalInputTokens = totalInputTokens;
    }

    public long getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public void setTotalOutputTokens(long totalOutputTokens) {
        this.totalOutputTokens = totalOutputTokens;
    }

    public int getLoopCount() {
        return loopCount;
    }

    public void setLoopCount(int loopCount) {
        this.loopCount = loopCount;
    }

    public int getMaxLoops() {
        return maxLoops;
    }

    public void setMaxLoops(int maxLoops) {
        this.maxLoops = maxLoops;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    /**
     * 累加输入 token 数量。
     */
    public void addInputTokens(long tokens) {
        this.totalInputTokens += tokens;
    }

    /**
     * 累加输出 token 数量。
     */
    public void addOutputTokens(long tokens) {
        this.totalOutputTokens += tokens;
    }

    /**
     * 递增循环计数。
     */
    public void incrementLoopCount() {
        this.loopCount++;
    }

    public boolean isCompactedThisIteration() {
        return compactedThisIteration;
    }

    public void markCompactedThisIteration() {
        this.compactedThisIteration = true;
    }

    public void resetCompactedThisIteration() {
        this.compactedThisIteration = false;
    }

    public Set<String> getExcludedSkillNames() {
        return excludedSkillNames;
    }

    public void setExcludedSkillNames(Set<String> excludedSkillNames) {
        this.excludedSkillNames = excludedSkillNames != null ? excludedSkillNames : Collections.emptySet();
    }

    public Set<String> getAllowedToolNames() {
        return allowedToolNames;
    }

    public void setAllowedToolNames(Set<String> allowedToolNames) {
        this.allowedToolNames = allowedToolNames;
    }

    /** Enqueue a user message to be injected at the next iteration boundary. Thread-safe. */
    public void enqueueUserMessage(String text) {
        if (text != null && !text.isBlank()) {
            pendingUserMessages.add(text);
        }
    }

    /** Drain all pending user messages. Returns empty list if none. Thread-safe. */
    public List<String> drainPendingUserMessages() {
        List<String> drained = new ArrayList<>();
        String msg;
        while ((msg = pendingUserMessages.poll()) != null) {
            drained.add(msg);
        }
        return drained;
    }

    /** Check if there are pending messages without draining. */
    public boolean hasPendingUserMessages() {
        return !pendingUserMessages.isEmpty();
    }

    // --- Anti-runaway: tool call counting ---

    /** Record a tool call for frequency tracking. */
    public void recordToolCall(String toolName) {
        toolCallCounts.merge(toolName, 1, Integer::sum);
    }

    /** Get the call count for a specific tool. */
    public int getToolCallCount(String toolName) {
        return toolCallCounts.getOrDefault(toolName, 0);
    }

    /** Get all tool call counts. */
    public Map<String, Integer> getToolCallCounts() {
        return toolCallCounts;
    }

    /** Elapsed time since loop start in milliseconds. */
    public long getElapsedMs() {
        return System.currentTimeMillis() - loopStartTimeMs;
    }

    // --- No-progress detection ---

    /**
     * Record a tool outcome for no-progress detection.
     * Tracks callHash (toolName#inputHash) and outcomeHash (output prefix hash).
     */
    public void recordToolOutcome(String toolName, String inputStr, String outputStr) {
        String callHash = toolName + "#" + (inputStr != null ? inputStr.hashCode() : 0);
        int outcomeHash = outputStr != null
                ? outputStr.substring(0, Math.min(200, outputStr.length())).hashCode()
                : 0;
        synchronized (recentToolHashes) {
            recentToolHashes.add(callHash);
            // keep sliding window of last 20
            if (recentToolHashes.size() > 20) {
                recentToolHashes.remove(0);
            }
        }
        outcomeHashes.computeIfAbsent(callHash, k -> new ArrayList<>()).add(outcomeHash);
    }

    /**
     * Check if the agent is stuck in a no-progress loop:
     * same callHash appears >= 3 times in recent window with unchanged outcome.
     */
    public boolean isNoProgress() {
        synchronized (recentToolHashes) {
            if (recentToolHashes.size() < 3) return false;
            Map<String, Integer> counts = new java.util.HashMap<>();
            for (String h : recentToolHashes) {
                counts.merge(h, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() >= 3) {
                    // 检查 outcome 是否也相同（真正的 no-progress）
                    List<Integer> outcomes = outcomeHashes.get(e.getKey());
                    if (outcomes != null && outcomes.size() >= 3) {
                        // 最后 3 次 outcome 都相同 → no-progress
                        int last = outcomes.size();
                        if (outcomes.get(last - 1).equals(outcomes.get(last - 2))
                                && outcomes.get(last - 2).equals(outcomes.get(last - 3))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // --- Compact circuit breaker ---

    public void incrementCompactFailures() {
        this.consecutiveCompactFailures++;
    }

    public void resetCompactFailures() {
        this.consecutiveCompactFailures = 0;
        this.compactBreakerOpenedAt = 0L;
    }

    public int getConsecutiveCompactFailures() {
        return consecutiveCompactFailures;
    }

    public long getCompactBreakerOpenedAt() {
        return compactBreakerOpenedAt;
    }

    /** Record/refresh the breaker-open timestamp. Called after each failure that crosses the threshold. */
    public void refreshCompactBreakerOpenedAt() {
        this.compactBreakerOpenedAt = System.currentTimeMillis();
    }

    public long getMaxLlmStreamTimeoutMs() {
        return maxLlmStreamTimeoutMs;
    }

    public void setMaxLlmStreamTimeoutMs(long maxLlmStreamTimeoutMs) {
        this.maxLlmStreamTimeoutMs = maxLlmStreamTimeoutMs;
    }

    public long getSessionIdleSeconds() {
        return sessionIdleSeconds;
    }

    public void setSessionIdleSeconds(long sessionIdleSeconds) {
        this.sessionIdleSeconds = sessionIdleSeconds;
    }

    public boolean isAbortedByHook() {
        return abortedByHook;
    }

    public void setAbortedByHook(boolean abortedByHook) {
        this.abortedByHook = abortedByHook;
    }

    public String getAbortedByHookReason() {
        return abortedByHookReason;
    }

    public void setAbortedByHookReason(String abortedByHookReason) {
        this.abortedByHookReason = abortedByHookReason;
    }

    /** Mark this loop as aborted by a lifecycle hook with the given reason. */
    public void markAbortedByHook(String reason) {
        this.abortedByHook = true;
        this.abortedByHookReason = reason;
    }

    public String getRootSessionIdCache() {
        return rootSessionIdCache;
    }

    public void setRootSessionIdCache(String rootSessionIdCache) {
        this.rootSessionIdCache = rootSessionIdCache;
    }

    /**
     * Memory v2 (PR-2): returns the ids of memories injected into the system prompt at loop
     * start. Always returns a non-null, immutable view to defend against caller mutation.
     */
    public Set<Long> getInjectedMemoryIds() {
        return injectedMemoryIds;
    }

    /**
     * Memory v2 (PR-2): sets the ids of memories injected into the system prompt. {@code null}
     * input collapses to an immutable empty set. Stored as an immutable {@link Set#copyOf}
     * snapshot so subsequent caller mutations cannot leak in.
     */
    public void setInjectedMemoryIds(Set<Long> injectedMemoryIds) {
        this.injectedMemoryIds = injectedMemoryIds == null
                ? Collections.emptySet()
                : Set.copyOf(injectedMemoryIds);
    }

    /** Plan r2 §5: per-session skill 授权视图（可能为 null on legacy callers）。 */
    public SessionSkillView getSkillView() {
        return skillView;
    }

    public void setSkillView(SessionSkillView skillView) {
        this.skillView = skillView;
    }

    /** Plan r2 §5 B-4: 引擎主循环每 tool_use round 末尾检查；true → 终止本 turn。 */
    public boolean isAbortToolUse() {
        return abortToolUse;
    }

    public void setAbortToolUse(boolean abortToolUse) {
        this.abortToolUse = abortToolUse;
    }

    /**
     * 累加并返回某 skill 的 NOT_ALLOWED 累积次数（含本次）。线程安全。
     * 第 2 次起 executeToolCall 触发 abortToolUse=true。
     */
    public int incrementNotAllowedCount(String skillName) {
        if (skillName == null) return 0;
        return notAllowedCount.merge(skillName, 1, Integer::sum);
    }

    /** @return current NOT_ALLOWED count for a skill (0 if never seen). */
    public int getNotAllowedCount(String skillName) {
        if (skillName == null) return 0;
        return notAllowedCount.getOrDefault(skillName, 0);
    }
}
