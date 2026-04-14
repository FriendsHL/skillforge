package com.skillforge.core.engine;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;

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

    /** Thread-safe queue for user messages sent while the loop is running. */
    private final ConcurrentLinkedQueue<String> pendingUserMessages = new ConcurrentLinkedQueue<>();

    /** Skill names to exclude from the tool list (depth-aware filtering for multi-agent collab). */
    private Set<String> excludedSkillNames = Collections.emptySet();

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
    }

    public int getConsecutiveCompactFailures() {
        return consecutiveCompactFailures;
    }
}
