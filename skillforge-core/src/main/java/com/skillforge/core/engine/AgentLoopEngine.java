package com.skillforge.core.engine;

import com.skillforge.core.compact.CompactableToolRegistry;
import com.skillforge.core.compact.ContextCompactTool;
import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.ContextCompactorCallback.CompactCallbackResult;
import com.skillforge.core.compact.TimeBasedColdCleanup;
import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.context.ContextProvider;
import com.skillforge.core.context.SystemPromptBuilder;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Agent Loop 核心引擎，驱动 LLM 与 Skill 的交互循环。
 * <p>
 * 核心流程：接收用户消息 -> 调用 LLM -> 处理 tool_use -> 返回 tool_result -> 再次调用 LLM，
 * 直到 LLM 返回纯文本响应或达到最大循环次数。
 */
public class AgentLoopEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopEngine.class);

    private final LlmProviderFactory llmProviderFactory;
    private final String defaultProviderName;
    private final SkillRegistry skillRegistry;
    private final List<LoopHook> loopHooks;
    private final List<SkillHook> skillHooks;
    private final List<ContextProvider> contextProviders;
    /** 可选:实时事件广播(server 注入 WebSocket 实现)。null 时降级为无广播模式。 */
    private ChatEventBroadcaster broadcaster;
    /** 可选:ask_user 待答复注册中心。null 时 ask_user 调用会直接返回错误。 */
    private PendingAskRegistry pendingAskRegistry;
    /** ask_user 默认超时 */
    private long askUserTimeoutSeconds = 30 * 60L;
    /**
     * 可选:context 压缩回调。server 通过 setter 注入 CompactionService 的实现。
     * null 时 compact_context 工具不会被注入, 也不会跑 B1/B2 safety net。
     */
    private ContextCompactorCallback compactorCallback;
    /** 可选:链路追踪收集器。null 时不记录 span。 */
    private TraceCollector traceCollector;
    /** 可选:记忆提供者。接受 userId 返回记忆 markdown 字符串,拼接到 system prompt 末尾。 */
    private java.util.function.Function<Long, String> memoryProvider;
    private java.util.function.Function<Long, String> claudeMdProvider;
    /** 默认 context window, 单位 token。从 AgentDefinition config 覆盖。 */
    private int defaultContextWindowTokens = 32000;

    public AgentLoopEngine(LlmProviderFactory llmProviderFactory,
                           String defaultProviderName,
                           SkillRegistry skillRegistry,
                           List<LoopHook> loopHooks,
                           List<SkillHook> skillHooks,
                           List<ContextProvider> contextProviders) {
        this.llmProviderFactory = llmProviderFactory;
        this.defaultProviderName = defaultProviderName;
        this.skillRegistry = skillRegistry;
        this.loopHooks = loopHooks != null ? loopHooks : Collections.emptyList();
        this.skillHooks = skillHooks != null ? skillHooks : Collections.emptyList();
        this.contextProviders = contextProviders != null ? contextProviders : Collections.emptyList();
    }

    /** Setter injection: 延迟注入,避免 core 模块强依赖 server 组件。 */
    public void setBroadcaster(ChatEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public void setPendingAskRegistry(PendingAskRegistry pendingAskRegistry) {
        this.pendingAskRegistry = pendingAskRegistry;
    }

    public void setAskUserTimeoutSeconds(long askUserTimeoutSeconds) {
        this.askUserTimeoutSeconds = askUserTimeoutSeconds;
    }

    public void setCompactorCallback(ContextCompactorCallback compactorCallback) {
        this.compactorCallback = compactorCallback;
    }

    public void setTraceCollector(TraceCollector traceCollector) {
        this.traceCollector = traceCollector;
    }

    public void setMemoryProvider(java.util.function.Function<Long, String> memoryProvider) {
        this.memoryProvider = memoryProvider;
    }

    public void setClaudeMdProvider(java.util.function.Function<Long, String> claudeMdProvider) {
        this.claudeMdProvider = claudeMdProvider;
    }

    public void setDefaultContextWindowTokens(int defaultContextWindowTokens) {
        if (defaultContextWindowTokens > 0) {
            this.defaultContextWindowTokens = defaultContextWindowTokens;
        }
    }

    // package-private for AgentLoopEngineCompactTest to verify per-agent override is honored
    int resolveContextWindow(AgentDefinition agentDef) {
        Object val = agentDef.getConfig().get("context_window_tokens");
        if (val instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        return defaultContextWindowTokens;
    }

    /**
     * 执行 Agent Loop 核心循环。
     *
     * @param agentDef    Agent 定义
     * @param userMessage 用户消息
     * @param history     历史消息（可为空）
     * @param sessionId   会话 ID
     * @param userId      用户 ID
     * @return LoopResult 包含最终响应和更新后的消息列表
     */
    public LoopResult run(AgentDefinition agentDef, String userMessage,
                          List<Message> history, String sessionId, Long userId) {
        return run(agentDef, userMessage, history, sessionId, userId, null);
    }

    /**
     * 带 externalContext 的重载:允许调用方(ChatService)在 run 之前先拿到 LoopContext 引用,
     * 注册到 CancellationRegistry 等外部组件,然后交给 engine 驱动。
     * externalContext 为 null 时行为等同于旧版 run。
     */
    public LoopResult run(AgentDefinition agentDef, String userMessage,
                          List<Message> history, String sessionId, Long userId,
                          LoopContext externalContext) {
        log.info("AgentLoop started for agent={}, session={}, user={}", agentDef.getName(), sessionId, userId);

        // 1. 创建或复用 LoopContext
        LoopContext context = externalContext != null ? externalContext : new LoopContext();
        context.setAgentDefinition(agentDef);
        context.setSessionId(sessionId);
        context.setUserId(userId);

        // 从 AgentDefinition config 读取 max_loops 配置
        Object maxLoopsVal = agentDef.getConfig().get("max_loops");
        if (maxLoopsVal instanceof Number) {
            context.setMaxLoops(((Number) maxLoopsVal).intValue());
        }

        // 从 AgentDefinition config 读取 execution_mode (ask / auto)
        Object modeVal = agentDef.getConfig().get("execution_mode");
        if (modeVal instanceof String) {
            context.setExecutionMode((String) modeVal);
        }

        // 组装 messages: history + user message
        List<Message> messages = new ArrayList<>();
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Message.user(userMessage));
        context.setMessages(messages);

        // 2. 执行所有 LoopHook.beforeLoop()
        for (LoopHook hook : loopHooks) {
            LoopContext prev = context;
            context = hook.beforeLoop(context);
            if (context == null) {
                log.warn("LoopHook interrupted the loop before start");
                // Check whether the hook set abortedByHook on the pre-hook context for richer reason.
                boolean hookAbort = prev != null && prev.isAbortedByHook();
                String reason = hookAbort ? prev.getAbortedByHookReason() : null;
                String msg = hookAbort
                        ? "[Aborted by lifecycle hook: " + (reason != null ? reason : "unspecified") + "]"
                        : "Loop interrupted by hook";
                LoopResult result = new LoopResult(msg, messages, 0, 0, 0, Collections.emptyList());
                if (hookAbort) {
                    result.setStatus("aborted_by_hook");
                }
                return result;
            }
        }
        // beforeLoop 可能修改了 messages
        messages = context.getMessages();
        // 确保 context 引用是 effectively final（beforeLoop 可能替换了 context 对象）
        final LoopContext loopCtx = context;

        // 4. 构建 system prompt（注入全局 CLAUDE.md）
        String claudeMd = claudeMdProvider != null ? claudeMdProvider.apply(userId) : null;
        List<SkillDefinition> skillDefs = new ArrayList<>(skillRegistry.getAllSkillDefinitions());
        String systemPrompt = new SystemPromptBuilder(agentDef, skillDefs, contextProviders).build(claudeMd);

        // 4.0.1 注入 Session Context (userId / sessionId) — 让 Agent 自动知道当前用户/会话
        if (userId != null || loopCtx.getSessionId() != null) {
            StringBuilder userCtx = new StringBuilder("\n\n## Session Context\n");
            if (userId != null) {
                userCtx.append("- userId: ").append(sanitizePromptValue(String.valueOf(userId))).append("\n");
            }
            if (loopCtx.getSessionId() != null) {
                userCtx.append("- sessionId: ").append(sanitizePromptValue(loopCtx.getSessionId())).append("\n");
            }
            systemPrompt = systemPrompt + userCtx;
        }

        // 4.1 注入用户记忆到 system prompt (skip if lightContext / skip_memory flag set)
        boolean skipMemory = Boolean.TRUE.equals(agentDef.getConfig().get("skip_memory"));
        if (memoryProvider != null && !skipMemory) {
            String memories = memoryProvider.apply(userId);
            if (memories != null && !memories.isBlank()) {
                systemPrompt = systemPrompt + "\n\n## User Memories\n\n" + memories;
            }
        }

        // 5. 收集 tools: 内置 Skill 的 ToolSchema + SkillDefinition 的描述 + (可选) ask_user + compact_context
        List<ToolSchema> tools = collectTools(loopCtx.getExecutionMode(), loopCtx.getExcludedSkillNames(), loopCtx.getAllowedToolNames());
        final int contextWindowTokens = resolveContextWindow(agentDef);

        // 追踪工具调用记录
        List<ToolCallRecord> toolCallRecords = new CopyOnWriteArrayList<>();
        LlmResponse lastResponse = null;

        // 5.5 解析要使用的 LlmProvider 和模型名
        String[] resolvedModel = new String[1];
        LlmProvider llmProvider = resolveProvider(agentDef, resolvedModel);
        String actualModelId = resolvedModel[0];

        // Trace: AGENT_LOOP root span
        final TraceSpan rootSpan;
        if (traceCollector != null) {
            rootSpan = new TraceSpan("AGENT_LOOP", agentDef.getName());
            rootSpan.setSessionId(sessionId);
            rootSpan.setModelId(actualModelId);
            rootSpan.setInput(userMessage);
        } else {
            rootSpan = null;
        }

        // 6. 进入循环
        boolean cancelled = false;
        boolean budgetExceeded = false;
        boolean durationExceeded = false;
        int maxTokensRetryCount = 0;
        while (loopCtx.getLoopCount() < loopCtx.getMaxLoops()) {
            // 取消检查(每次迭代开头)
            if (loopCtx.isCancelled()) {
                log.info("AgentLoop cancelled at loop {} (pre-iteration)", loopCtx.getLoopCount() + 1);
                cancelled = true;
                break;
            }

            // Token budget check
            long maxInputTokens = 500000;
            Object maxTokVal = agentDef.getConfig().get("max_input_tokens");
            if (maxTokVal instanceof Number) maxInputTokens = ((Number) maxTokVal).longValue();
            if (loopCtx.getTotalInputTokens() > maxInputTokens) {
                log.warn("Token budget exceeded: {} > {}", loopCtx.getTotalInputTokens(), maxInputTokens);
                budgetExceeded = true;
                break;
            }

            // Duration check
            long maxDurationMs = 600000; // 10 minutes default
            Object maxDurVal = agentDef.getConfig().get("max_duration_seconds");
            if (maxDurVal instanceof Number) maxDurationMs = ((Number) maxDurVal).longValue() * 1000;
            if (loopCtx.getElapsedMs() > maxDurationMs) {
                log.warn("Duration limit exceeded: {}ms > {}ms", loopCtx.getElapsedMs(), maxDurationMs);
                durationExceeded = true;
                break;
            }

            log.debug("AgentLoop iteration {} / {}", loopCtx.getLoopCount() + 1, loopCtx.getMaxLoops());

            // 每次迭代开头清掉"本轮已压缩"标志 —— 这是防止无限压缩循环的核心
            loopCtx.resetCompactedThisIteration();

            // Drain any queued user messages into the conversation
            injectQueuedMessages(loopCtx, messages);

            // Time-based cold cleanup: on first iteration, if session was idle, clear old tool results
            if (loopCtx.getLoopCount() == 0 && loopCtx.getSessionIdleSeconds() >= 0) {
                long idleThreshold = TimeBasedColdCleanup.DEFAULT_IDLE_THRESHOLD_SECONDS;
                Object thresholdVal = agentDef.getConfig().get("cold_cleanup_idle_seconds");
                if (thresholdVal instanceof Number n && n.longValue() > 0) {
                    idleThreshold = n.longValue();
                }
                int keepRecent = TimeBasedColdCleanup.DEFAULT_KEEP_RECENT;
                Object keepVal = agentDef.getConfig().get("cold_cleanup_keep_recent");
                if (keepVal instanceof Number n && n.intValue() > 0) {
                    keepRecent = n.intValue();
                }
                CompactableToolRegistry coldRegistry = CompactableToolRegistry.fromAgentConfig(agentDef.getConfig());
                TimeBasedColdCleanup.apply(messages, loopCtx.getSessionIdleSeconds(),
                        idleThreshold, keepRecent, coldRegistry);
            }

            // a. B1/B2 safety net: 基于 TokenEstimator 的估算自动触发压缩
            //
            // B1 = engine-soft light compact, 条件 ratio > 0.60 或 detectWaste
            // B2 = engine-hard full compact, 条件 "B1 刚跑过(本轮) 且 ratio 仍 > 0.80"
            //   (以 0.80 为阈值:  如果 B1 收回了足够 token, ratio 应该明显下降,
            //    否则说明 light 不够用, 必须上 full LLM 总结)
            //
            // 防循环: 这两个分支各自只会在本 iteration 执行一次 —— B2 的前置条件是
            // b1RanInThisIteration, 而 B1 分支的执行条件是 ratio/waste, 改过后 ratio
            // 通常下降不再满足触发. 这保证了 "B2 只在 B1 执行后触发"
            // 且"每个 iteration 至多一次完整 B1→B2 序列".
            boolean b1RanInThisIteration = false;
            if (compactorCallback != null && loopCtx.getConsecutiveCompactFailures() < 3) {
                int estTokens = TokenEstimator.estimate(messages);
                double ratio = contextWindowTokens > 0 ? (double) estTokens / contextWindowTokens : 0;
                boolean waste = detectWaste(messages);
                if (ratio > 0.60 || waste) {
                    String reason = waste
                            ? "engine-soft: waste detected (large tool_result / dedup / retry loop)"
                            : String.format("engine-soft: estTokens=%d / window=%d (ratio=%.2f)",
                                    estTokens, contextWindowTokens, ratio);
                    try {
                        CompactCallbackResult cr = compactorCallback.compactLight(
                                loopCtx.getSessionId(), messages, "engine-soft", reason);
                        b1RanInThisIteration = true;
                        if (cr != null && cr.performed) {
                            loopCtx.resetCompactFailures();
                            messages = cr.messages;
                            loopCtx.setMessages(messages);
                            loopCtx.markCompactedThisIteration();
                            estTokens = TokenEstimator.estimate(messages);
                            ratio = contextWindowTokens > 0 ? (double) estTokens / contextWindowTokens : 0;
                            log.info("engine-soft light compact done: sessionId={}, reclaimed={} tokens, new ratio={}",
                                    loopCtx.getSessionId(), cr.tokensReclaimed, String.format("%.2f", ratio));
                        } else {
                            loopCtx.incrementCompactFailures();
                        }
                    } catch (Exception e) {
                        loopCtx.incrementCompactFailures();
                        log.warn("engine-soft compact failed (consecutive failures: {}): {}",
                                loopCtx.getConsecutiveCompactFailures(), e.getMessage());
                    }
                }
                // B2: B1 刚跑过 (无论实际 performed 还是 no-op) 且 ratio 仍 > 0.80 → 升级到 full
                if (b1RanInThisIteration && ratio > 0.80) {
                    String reason = String.format("engine-hard: B1 ran but ratio still %.2f (estTokens=%d / window=%d)",
                            ratio, estTokens, contextWindowTokens);
                    try {
                        CompactCallbackResult cr = compactorCallback.compactFull(
                                loopCtx.getSessionId(), messages, "engine-hard", reason);
                        if (cr != null && cr.performed) {
                            loopCtx.resetCompactFailures();
                            messages = cr.messages;
                            loopCtx.setMessages(messages);
                            loopCtx.markCompactedThisIteration();
                            log.info("engine-hard full compact done: sessionId={}, reclaimed={} tokens",
                                    loopCtx.getSessionId(), cr.tokensReclaimed);
                        } else {
                            loopCtx.incrementCompactFailures();
                        }
                    } catch (Exception e) {
                        loopCtx.incrementCompactFailures();
                        log.warn("engine-hard compact failed (consecutive failures: {}): {}",
                                loopCtx.getConsecutiveCompactFailures(), e.getMessage());
                    }
                }
            } else if (compactorCallback != null && loopCtx.getConsecutiveCompactFailures() >= 3) {
                log.warn("Skipping compact: circuit breaker open after {} consecutive failures",
                        loopCtx.getConsecutiveCompactFailures());
            }

            // b. 构建 LlmRequest — P-4/P-5 通过 system prompt 后缀注入，避免破坏 user/assistant 交替
            StringBuilder promptSuffix = new StringBuilder();
            // P-4: waste detected → append guidance to system prompt (after B1/B2 compact)
            if (compactorCallback != null && detectWaste(messages)) {
                promptSuffix.append("\n\n[IMPORTANT] Repetitive or inefficient tool usage pattern detected. "
                        + "Adjust your strategy: if multiple searches have not found the needed information, "
                        + "answer based on what you already have. If a tool keeps failing, try a different "
                        + "approach or inform the user of the limitation.");
                log.info("Appending waste-detection guidance to system prompt");
            }
            // Anti-runaway warnings — pick the most severe one only (avoid stacking multiple IMPORTANT)
            if (loopCtx.isNoProgress()) {
                // Highest priority: same tool + same input + same output repeatedly
                promptSuffix.append("\n\n[IMPORTANT] No-progress detected: you are repeatedly calling the same tool "
                        + "with the same parameters and getting the same results. Stop this pattern immediately "
                        + "and answer based on what you already have, or try a completely different approach.");
            } else {
                // Lower priority: tool called many times (even with different params)
                for (Map.Entry<String, Integer> entry : loopCtx.getToolCallCounts().entrySet()) {
                    if (entry.getValue() >= 8) {
                        promptSuffix.append("\n\n[IMPORTANT] You have called the tool '").append(entry.getKey())
                                .append("' ").append(entry.getValue()).append(" times. If it is not producing useful new results, ")
                                .append("stop calling it and answer based on what you already have.");
                        break;
                    }
                }
            }
            // Stop hook: approaching token budget
            if (loopCtx.getTotalInputTokens() > maxInputTokens * 0.8) {
                promptSuffix.append("\n\n[NOTICE] You have consumed "
                        + loopCtx.getTotalInputTokens() + "/" + maxInputTokens
                        + " input tokens. Consider wrapping up soon.");
            }
            // Stop hook: approaching max loops
            if (loopCtx.getLoopCount() > loopCtx.getMaxLoops() * 0.8) {
                promptSuffix.append("\n\n[NOTICE] You are at iteration "
                        + (loopCtx.getLoopCount() + 1) + "/" + loopCtx.getMaxLoops()
                        + ". Start wrapping up your work.");
            }

            // P-5: loop ending reminder — only once at remaining==2
            int remaining = loopCtx.getMaxLoops() - loopCtx.getLoopCount();
            if (remaining == 2) {
                promptSuffix.append("\n\n[IMPORTANT] You have only " + remaining
                        + " iterations left. Wrap up your work and provide the user with "
                        + "the best answer you can based on the information gathered so far.");
                log.info("Appending loop-ending reminder: {} iterations remaining", remaining);
            }

            LlmRequest request = new LlmRequest();
            request.setSystemPrompt(promptSuffix.isEmpty() ? systemPrompt
                    : systemPrompt + promptSuffix);
            request.setMessages(messages);
            request.setTools(tools);
            request.setModel(actualModelId);
            request.setMaxTokens(agentDef.getMaxTokens());
            request.setTemperature(agentDef.getTemperature());

            // Preemptive compaction: last-resort check before LLM call
            if (compactorCallback != null && loopCtx.getConsecutiveCompactFailures() < 3) {
                int estTokens = TokenEstimator.estimate(messages);
                double ratio = contextWindowTokens > 0 ? (double) estTokens / contextWindowTokens : 0;
                if (ratio > 0.85) {
                    log.info("Preemptive compaction triggered: ratio={}, estTokens={}, window={}",
                            String.format("%.2f", ratio), estTokens, contextWindowTokens);
                    try {
                        CompactCallbackResult cr = compactorCallback.compactFull(
                                loopCtx.getSessionId(), messages, "engine-preemptive",
                                String.format("ratio %.2f > 0.85 before LLM call", ratio));
                        if (cr != null && cr.performed) {
                            messages = cr.messages;
                            loopCtx.setMessages(messages);
                            loopCtx.resetCompactFailures();
                            request.setMessages(messages);
                            log.info("Preemptive compaction done: reclaimed {} tokens", cr.tokensReclaimed);
                        } else {
                            loopCtx.incrementCompactFailures();
                        }
                    } catch (Exception e) {
                        loopCtx.incrementCompactFailures();
                        log.warn("Preemptive compaction failed (consecutive failures: {}): {}",
                                loopCtx.getConsecutiveCompactFailures(), e.getMessage());
                    }
                }
            }

            // b. 流式调用 LLM,文本增量通过 broadcaster.assistantDelta 推到前端
            final long llmCallStart = System.currentTimeMillis();
            final java.util.concurrent.atomic.AtomicReference<LlmResponse> respHolder = new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicReference<Throwable> errHolder = new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.CountDownLatch streamDone = new java.util.concurrent.CountDownLatch(1);
            final String broadcastSid = loopCtx.getSessionId();
            try {
                // 流式 tool_use 分片需要记住 name(按 toolUseId 维度)才能广播 toolUseDelta
                final java.util.Map<String, String> streamToolNames = new java.util.concurrent.ConcurrentHashMap<>();
                llmProvider.chatStream(request, new com.skillforge.core.llm.LlmStreamHandler() {
                    @Override public void onStreamStart(Runnable cancelAction) {
                        if (cancelAction != null) {
                            loopCtx.setStreamCanceller(cancelAction);
                        } else {
                            loopCtx.clearStreamCanceller();
                        }
                    }
                    @Override public boolean isCancelled() {
                        return loopCtx.isCancelled();
                    }
                    @Override public void onText(String text) {
                        if (broadcaster != null && broadcastSid != null && text != null && !text.isEmpty()) {
                            broadcaster.assistantDelta(broadcastSid, text);
                            broadcaster.textDelta(broadcastSid, text);
                        }
                    }
                    @Override public void onToolUseStart(String toolUseId, String name) {
                        if (toolUseId != null) {
                            streamToolNames.put(toolUseId, name != null ? name : "");
                        }
                    }
                    @Override public void onToolUseInputDelta(String toolUseId, String jsonFragment) {
                        if (broadcaster != null && broadcastSid != null && toolUseId != null
                                && jsonFragment != null && !jsonFragment.isEmpty()) {
                            broadcaster.toolUseDelta(broadcastSid, toolUseId,
                                    streamToolNames.getOrDefault(toolUseId, ""), jsonFragment);
                        }
                    }
                    @Override public void onToolUseEnd(String toolUseId, java.util.Map<String, Object> parsedInput) {
                        if (broadcaster != null && broadcastSid != null && toolUseId != null) {
                            broadcaster.toolUseComplete(broadcastSid, toolUseId, parsedInput);
                        }
                    }
                    @Override public void onToolUse(com.skillforge.core.model.ToolUseBlock block) {
                        // tool_use 的可视化由后续 tool_started 事件覆盖,此处不广播
                    }
                    @Override public void onComplete(LlmResponse fullResponse) {
                        respHolder.set(fullResponse);
                        if (broadcaster != null && broadcastSid != null) {
                            broadcaster.assistantStreamEnd(broadcastSid);
                        }
                        streamDone.countDown();
                    }
                    @Override public void onError(Throwable error) {
                        errHolder.set(error);
                        if (broadcaster != null && broadcastSid != null) {
                            broadcaster.assistantStreamEnd(broadcastSid);
                        }
                        streamDone.countDown();
                    }
                });
                // A-2: 整体 300s 超时兜底
                boolean completed = streamDone.await(300, java.util.concurrent.TimeUnit.SECONDS);
                if (!completed) {
                    log.warn("LLM stream timed out after 300s at loop {}, requesting cancel", loopCtx.getLoopCount());
                    loopCtx.requestCancel();
                    throw new RuntimeException("LLM stream timed out after 300 seconds");
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                log.error("LLM stream call failed at loop {}", loopCtx.getLoopCount(), e);
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }
            // 取消检查必须在 errHolder 之前:cancel 路径会设 errHolder 来释放 latch,
            // 但不应把 cancel 当错误抛出
            if (loopCtx.isCancelled()) {
                log.info("AgentLoop cancelled during LLM call at loop {}", loopCtx.getLoopCount() + 1);
                cancelled = true;
                break;
            }
            if (errHolder.get() != null) {
                Throwable e = errHolder.get();
                log.error("LLM stream returned error at loop {}", loopCtx.getLoopCount(), e);
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }
            LlmResponse response = respHolder.get();
            if (response == null) {
                throw new RuntimeException("LLM stream completed without response");
            }
            lastResponse = response;

            // c. 累加 token 用量
            int iterInputTokens = 0, iterOutputTokens = 0;
            if (response.getUsage() != null) {
                iterInputTokens = response.getUsage().getInputTokens();
                iterOutputTokens = response.getUsage().getOutputTokens();
                loopCtx.addInputTokens(iterInputTokens);
                loopCtx.addOutputTokens(iterOutputTokens);
            }

            // max_tokens recovery: detect truncated output and retry
            String stopReason = response.getStopReason();
            if ("length".equals(stopReason) || "max_tokens".equals(stopReason)) {
                maxTokensRetryCount++;
                if (maxTokensRetryCount <= 2) {
                    if (maxTokensRetryCount == 1) {
                        // First attempt: escalate max_output_tokens
                        int escalatedMaxTokens = Math.min(agentDef.getMaxTokens() * 4, 16384);
                        log.info("max_tokens hit, escalating to {} (attempt {})", escalatedMaxTokens, maxTokensRetryCount);
                        request.setMaxTokens(escalatedMaxTokens);
                        continue; // retry — loopCount not incremented
                    } else {
                        // Second attempt: compact + retry
                        log.info("max_tokens hit again, trying compact + retry (attempt {})", maxTokensRetryCount);
                        if (compactorCallback != null) {
                            try {
                                CompactCallbackResult cr = compactorCallback.compactFull(
                                        loopCtx.getSessionId(), messages, "engine-max-tokens",
                                        "max_tokens recovery: output truncated");
                                if (cr != null && cr.performed) {
                                    messages = cr.messages;
                                    loopCtx.setMessages(messages);
                                    continue; // retry
                                }
                            } catch (Exception e) {
                                log.warn("max_tokens compact failed: {}", e.getMessage());
                            }
                        }
                    }
                }
                // Exhausted retries or compact failed — fall through to normal processing
                log.warn("max_tokens recovery exhausted after {} attempts", maxTokensRetryCount);
            } else {
                // Reset counter on successful non-truncated response
                maxTokensRetryCount = 0;
            }

            // Trace: LLM_CALL span
            if (traceCollector != null && rootSpan != null) {
                long llmCallEnd = System.currentTimeMillis();
                TraceSpan llmSpan = new TraceSpan("LLM_CALL", actualModelId);
                llmSpan.setSessionId(sessionId);
                llmSpan.setParentSpanId(rootSpan.getId());
                llmSpan.setModelId(actualModelId);
                llmSpan.setStartTimeMs(llmCallStart);
                llmSpan.setEndTimeMs(llmCallEnd);
                llmSpan.setDurationMs(llmCallEnd - llmCallStart);
                llmSpan.setIterationIndex(loopCtx.getLoopCount());
                llmSpan.setInputTokens(iterInputTokens);
                llmSpan.setOutputTokens(iterOutputTokens);
                // input: structured summary of messages
                StringBuilder llmInputSummary = new StringBuilder();
                llmInputSummary.append("messages: ").append(messages.size());
                for (int mi = messages.size() - 1; mi >= 0; mi--) {
                    Message m = messages.get(mi);
                    if (m.getRole() == Message.Role.USER) {
                        String txt = m.getTextContent();
                        if (txt != null && !txt.isBlank()) {
                            String preview = txt.length() > 200 ? txt.substring(0, 200) + "..." : txt;
                            llmInputSummary.append(" | last_user: ").append(preview);
                        } else if (m.getContent() instanceof List<?> contentBlocks) {
                            long trCount = contentBlocks.stream()
                                    .filter(o -> o instanceof ContentBlock cb && "tool_result".equals(cb.getType()))
                                    .count();
                            if (trCount > 0) {
                                llmInputSummary.append(" | tool_results: ").append(trCount).append(" results");
                            }
                        }
                        break;
                    }
                }
                llmSpan.setInput(llmInputSummary.toString());
                // output: LLM 的文本回复 + tool_use 名称列表
                StringBuilder llmOutput = new StringBuilder();
                if (response.getContent() != null && !response.getContent().isEmpty()) {
                    llmOutput.append(response.getContent());
                }
                if (response.isToolUse() && response.getToolUseBlocks() != null) {
                    if (!llmOutput.isEmpty()) llmOutput.append("\n");
                    llmOutput.append("[tool_use: ");
                    llmOutput.append(response.getToolUseBlocks().stream()
                            .map(b -> b.getName())
                            .collect(Collectors.joining(", ")));
                    llmOutput.append("]");
                }
                llmSpan.setOutput(llmOutput.toString());
                llmSpan.setSuccess(true);
                traceCollector.record(llmSpan);
            }

            // d. 将 assistant 响应加入 messages 并广播
            Message assistantMsg = buildAssistantMessage(response);
            messages.add(assistantMsg);
            if (broadcaster != null && loopCtx.getSessionId() != null) {
                broadcaster.messageAppended(loopCtx.getSessionId(), assistantMsg);
            }

            // e. 判断是否 tool_use
            if (!response.isToolUse()) {
                // Before breaking, check if user queued new messages while we were streaming
                if (injectQueuedMessages(loopCtx, messages)) {
                    if (loopCtx.getLoopCount() + 1 >= loopCtx.getMaxLoops()) {
                        log.warn("Queued message(s) arrived but loop is at max iterations, appending without LLM processing");
                        break;
                    }
                    loopCtx.incrementLoopCount();
                    continue;
                }
                // 循环结束
                log.info("AgentLoop completed with text response at loop {}", loopCtx.getLoopCount() + 1);
                break;
            }

            // 处理 tool_use: 先把 ask_user / compact_context 从列表里拆出来走特殊分支,其余并行执行
            List<ToolUseBlock> toolUseBlocks = response.getToolUseBlocks();
            log.info("Processing {} tool call(s) at loop {}", toolUseBlocks.size(), loopCtx.getLoopCount() + 1);

            List<CompletableFuture<Message>> futures = new ArrayList<>();
            Map<Integer, Message> askResults = new HashMap<>();
            for (int i = 0; i < toolUseBlocks.size(); i++) {
                ToolUseBlock block = toolUseBlocks.get(i);
                if (AskUserTool.NAME.equals(block.getName())) {
                    long askStart = System.currentTimeMillis();
                    Message result = handleAskUser(block, loopCtx);
                    askResults.put(i, result);
                    // Trace: ASK_USER span
                    if (traceCollector != null && rootSpan != null) {
                        TraceSpan askSpan = new TraceSpan("ASK_USER", "ask_user");
                        askSpan.setSessionId(sessionId);
                        askSpan.setParentSpanId(rootSpan.getId());
                        askSpan.setIterationIndex(loopCtx.getLoopCount());
                        askSpan.setStartTimeMs(askStart);
                        askSpan.setInput(block.getInput() != null ? block.getInput().toString() : "");
                        askSpan.setOutput(result.getTextContent());
                        askSpan.end();
                        traceCollector.record(askSpan);
                    }
                } else if (ContextCompactTool.NAME.equals(block.getName())) {
                    long compactStart = System.currentTimeMillis();
                    Message result = handleCompactContext(block, loopCtx);
                    askResults.put(i, result);
                    // Trace: COMPACT span
                    if (traceCollector != null && rootSpan != null) {
                        TraceSpan compactSpan = new TraceSpan("COMPACT", "compact_context");
                        compactSpan.setSessionId(sessionId);
                        compactSpan.setParentSpanId(rootSpan.getId());
                        compactSpan.setIterationIndex(loopCtx.getLoopCount());
                        compactSpan.setStartTimeMs(compactStart);
                        compactSpan.setInput(block.getInput() != null ? block.getInput().toString() : "");
                        compactSpan.setOutput(result.getTextContent());
                        compactSpan.end();
                        traceCollector.record(compactSpan);
                    }
                    // compact 可能替换了 messages, 同步回本地 messages 引用
                    messages = loopCtx.getMessages();
                } else {
                    final int idx = i;
                    final ToolUseBlock fblock = block;
                    if (broadcaster != null && loopCtx.getSessionId() != null) {
                        broadcaster.toolStarted(loopCtx.getSessionId(), fblock.getId(), fblock.getName(), fblock.getInput());
                    }
                    final long toolStart = System.currentTimeMillis();
                    final int currentIteration = loopCtx.getLoopCount();
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        Message r = null;
                        String status = "success";
                        String errorMsg = null;
                        try {
                            r = executeToolCall(fblock, loopCtx, toolCallRecords);
                            if (r != null && r.getContent() instanceof java.util.List<?> blocks) {
                                for (Object o : blocks) {
                                    if (o instanceof com.skillforge.core.model.ContentBlock cb && Boolean.TRUE.equals(cb.getIsError())) {
                                        status = "error";
                                        errorMsg = String.valueOf(cb.getContent());
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            status = "error";
                            errorMsg = e.getMessage();
                            r = Message.toolResult(fblock.getId(), "Tool execution error: " + e.getMessage(), true);
                        } finally {
                            long dur = System.currentTimeMillis() - toolStart;
                            // Record tool call for anti-runaway tracking
                            loopCtx.recordToolCall(fblock.getName());
                            String toolOutputText = r != null ? r.getTextContent() : "";
                            String toolInputStr = fblock.getInput() != null ? fblock.getInput().toString() : "";
                            loopCtx.recordToolOutcome(fblock.getName(), toolInputStr, toolOutputText);
                            // Truncate tool result output — 回写到 Message 和 trace
                            String truncatedOutput = ToolResultTruncator.truncate(toolOutputText);
                            if (r != null && !truncatedOutput.equals(toolOutputText)) {
                                // 重建截断后的 tool_result message
                                r = Message.toolResult(fblock.getId(), truncatedOutput, "error".equals(status));
                                log.info("Truncated tool result for {}: {}→{} chars",
                                        fblock.getName(), toolOutputText.length(), truncatedOutput.length());
                            }
                            toolOutputText = truncatedOutput;
                            if (broadcaster != null && loopCtx.getSessionId() != null) {
                                broadcaster.toolFinished(loopCtx.getSessionId(), fblock.getId(), status, dur, errorMsg);
                            }
                            // Trace: TOOL_CALL span
                            if (traceCollector != null && rootSpan != null) {
                                TraceSpan toolSpan = new TraceSpan("TOOL_CALL", fblock.getName());
                                toolSpan.setSessionId(loopCtx.getSessionId());
                                toolSpan.setParentSpanId(rootSpan.getId());
                                toolSpan.setToolUseId(fblock.getId());
                                toolSpan.setIterationIndex(currentIteration);
                                toolSpan.setStartTimeMs(toolStart);
                                toolSpan.setEndTimeMs(toolStart + dur);
                                toolSpan.setDurationMs(dur);
                                toolSpan.setInput(fblock.getInput() != null ? mapToJson(fblock.getInput()) : "");
                                toolSpan.setOutput(toolOutputText != null ? toolOutputText : "");
                                toolSpan.setSuccess("success".equals(status));
                                toolSpan.setError(errorMsg);
                                traceCollector.record(toolSpan);
                            }
                        }
                        synchronized (askResults) {
                            askResults.put(idx, r);
                        }
                        return r;
                    }));
                }
            }

            // 等待所有并行 tool 执行完成 (A-1: 120s timeout)
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(120, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("Tool execution timed out after 120s, cancelling remaining futures");
                futures.forEach(f -> f.cancel(true));
            } catch (java.util.concurrent.ExecutionException e) {
                log.error("Tool execution failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Tool execution wait interrupted, cancelling futures");
                futures.forEach(f -> f.cancel(true));
            }

            // 按原顺序加入 messages + 广播; 为超时未完成的工具补充 error tool_result
            for (int i = 0; i < toolUseBlocks.size(); i++) {
                Message toolResult = askResults.get(i);
                if (toolResult == null) {
                    // Tool did not complete (timed out) — inject error tool_result
                    toolResult = Message.toolResult(toolUseBlocks.get(i).getId(),
                            "Tool execution timed out after 120 seconds", true);
                }
                messages.add(toolResult);
                if (broadcaster != null && loopCtx.getSessionId() != null) {
                    broadcaster.messageAppended(loopCtx.getSessionId(), toolResult);
                }
            }

            // f. loopCount++
            loopCtx.incrementLoopCount();
        }

        // 取消退出
        if (cancelled) {
            LoopResult result = buildResult(loopCtx, messages, "[Cancelled by user]", toolCallRecords);
            result.setStatus("cancelled");
            if (traceCollector != null && rootSpan != null) {
                rootSpan.setOutput("[Cancelled by user]");
                rootSpan.setInputTokens((int) loopCtx.getTotalInputTokens());
                rootSpan.setOutputTokens((int) loopCtx.getTotalOutputTokens());
                rootSpan.setSuccess(false);
                rootSpan.setError("cancelled");
                rootSpan.end();
                traceCollector.record(rootSpan);
            }
            return result;
        }

        // Budget / duration exceeded exits
        if (budgetExceeded || durationExceeded) {
            String reason = budgetExceeded ? "token_budget_exceeded" : "duration_exceeded";
            String msg = budgetExceeded
                    ? "Token budget exceeded (" + loopCtx.getTotalInputTokens() + " tokens). Providing best answer with current information."
                    : "Duration limit exceeded (" + (loopCtx.getElapsedMs() / 1000) + "s). Providing best answer with current information.";
            LoopResult result = buildResult(loopCtx, messages, msg, toolCallRecords);
            result.setStatus(reason);
            if (traceCollector != null && rootSpan != null) {
                rootSpan.setOutput(msg);
                rootSpan.setInputTokens((int) loopCtx.getTotalInputTokens());
                rootSpan.setOutputTokens((int) loopCtx.getTotalOutputTokens());
                rootSpan.setSuccess(false);
                rootSpan.setError(reason);
                rootSpan.end();
                traceCollector.record(rootSpan);
            }
            return result;
        }

        // 检查是否因达到上限而退出
        if (loopCtx.getLoopCount() >= loopCtx.getMaxLoops()) {
            log.warn("AgentLoop reached max loops limit: {}", loopCtx.getMaxLoops());
            String limitMsg = "I've reached the maximum number of processing steps (" + loopCtx.getMaxLoops()
                    + "). Please try breaking your request into smaller parts.";
            if (traceCollector != null && rootSpan != null) {
                rootSpan.setOutput(limitMsg);
                rootSpan.setInputTokens((int) loopCtx.getTotalInputTokens());
                rootSpan.setOutputTokens((int) loopCtx.getTotalOutputTokens());
                rootSpan.setSuccess(false);
                rootSpan.setError("max_loops");
                rootSpan.end();
                traceCollector.record(rootSpan);
            }
            return buildResult(loopCtx, messages, limitMsg, toolCallRecords);
        }

        // 7. 执行所有 LoopHook.afterLoop()
        for (LoopHook hook : loopHooks) {
            try {
                hook.afterLoop(loopCtx, lastResponse);
            } catch (Exception e) {
                log.error("LoopHook.afterLoop failed", e);
            }
        }

        // 8. 返回 LoopResult
        String finalText = lastResponse != null ? lastResponse.getContent() : "";
        // Trace: 关闭 AGENT_LOOP root span
        if (traceCollector != null && rootSpan != null) {
            rootSpan.setOutput(finalText);
            rootSpan.setInputTokens((int) loopCtx.getTotalInputTokens());
            rootSpan.setOutputTokens((int) loopCtx.getTotalOutputTokens());
            rootSpan.end();
            traceCollector.record(rootSpan);
        }
        return buildResult(loopCtx, messages, finalText, toolCallRecords);
    }

    /**
     * 根据 AgentDefinition 的 modelId 解析要使用的 LlmProvider。
     * <p>
     * 支持两种格式:
     * <ul>
     *   <li>"deepseek:deepseek-chat" — 使用名为 "deepseek" 的 provider，覆盖模型为 "deepseek-chat"</li>
     *   <li>"gpt-4o" — 使用默认 provider</li>
     * </ul>
     */
    /**
     * 解析 provider 和实际 model name。返回长度为 2 的数组: [0]=resolvedModelName, provider 通过返回值。
     */
    private LlmProvider resolveProvider(AgentDefinition agentDef, String[] resolvedModel) {
        String modelId = agentDef.getModelId();

        if (modelId != null && modelId.contains(":")) {
            // 格式: "providerName:modelName"
            int colonIndex = modelId.indexOf(':');
            String providerName = modelId.substring(0, colonIndex);
            String modelName = modelId.substring(colonIndex + 1);

            LlmProvider provider = llmProviderFactory.getProvider(providerName);
            if (provider != null) {
                resolvedModel[0] = modelName;
                return provider;
            }
            log.warn("Provider '{}' not found, falling back to default provider '{}'", providerName, defaultProviderName);
        }

        resolvedModel[0] = modelId;
        LlmProvider defaultProvider = llmProviderFactory.getProvider(defaultProviderName);
        if (defaultProvider == null) {
            throw new IllegalStateException("Default LLM provider '" + defaultProviderName + "' is not configured");
        }
        return defaultProvider;
    }

    /**
     * Drain queued user messages from LoopContext and inject as a single merged user message.
     * Multiple messages are joined with "\n\n---\n\n" to avoid consecutive user messages
     * violating the LLM API alternation contract.
     *
     * @return true if at least one message was injected
     */
    private boolean injectQueuedMessages(LoopContext loopCtx, List<Message> messages) {
        List<String> pending = loopCtx.drainPendingUserMessages();
        if (pending.isEmpty()) return false;
        String merged = String.join("\n\n---\n\n", pending);
        messages.add(Message.user(merged));
        log.debug("Injected {} queued user message(s) at iteration {}", pending.size(), loopCtx.getLoopCount() + 1);
        return true;
    }

    /**
     * 收集所有可用的工具 schema：内置 Skill + SkillDefinition + (可选) ask_user。
     */
    private List<ToolSchema> collectTools(String executionMode, java.util.Set<String> excludedSkillNames,
                                             java.util.Set<String> allowedToolNames) {
        List<ToolSchema> tools = new ArrayList<>();

        // 内置 Skill (filter out excluded skills for depth-aware multi-agent collab,
        // and apply allowedToolNames whitelist if configured)
        for (Tool tool : skillRegistry.getAllTools()) {
            if (excludedSkillNames != null && excludedSkillNames.contains(tool.getName())) {
                continue;
            }
            if (allowedToolNames != null && !allowedToolNames.isEmpty()
                    && !allowedToolNames.contains(tool.getName())) {
                continue;
            }
            ToolSchema schema = tool.getToolSchema();
            if (schema != null) {
                tools.add(schema);
            }
        }

        // SkillDefinition (zip 包 Skill) — 生成简单的 ToolSchema
        for (SkillDefinition def : skillRegistry.getAllSkillDefinitions()) {
            Map<String, Object> inputSchema = new HashMap<>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", Collections.emptyMap());
            tools.add(new ToolSchema(def.getName(), def.getDescription(), inputSchema));
        }

        // ask_user:仅在 ask 模式下注入,auto 模式下 LLM 看不到这个 tool
        if ("ask".equalsIgnoreCase(executionMode) && pendingAskRegistry != null) {
            tools.add(AskUserTool.toolSchema());
        }

        // compact_context: 只在压缩回调可用时注入, 允许 LLM 自行决定何时压缩
        if (compactorCallback != null) {
            tools.add(ContextCompactTool.toolSchema());
        }

        return tools;
    }

    /**
     * 构建 assistant 消息。如果 LLM 返回了 tool_use 块，则用 ContentBlock 列表构建。
     */
    private Message buildAssistantMessage(LlmResponse response) {
        Message msg = new Message();
        msg.setRole(Message.Role.ASSISTANT);

        List<ToolUseBlock> toolUseBlocks = response.getToolUseBlocks();
        if (toolUseBlocks == null || toolUseBlocks.isEmpty()) {
            // 纯文本响应
            msg.setContent(response.getContent());
        } else {
            // 包含 tool_use 的响应，构建 ContentBlock 列表
            List<ContentBlock> blocks = new ArrayList<>();
            if (response.getContent() != null && !response.getContent().isEmpty()) {
                blocks.add(ContentBlock.text(response.getContent()));
            }
            for (ToolUseBlock block : toolUseBlocks) {
                blocks.add(ContentBlock.toolUse(block.getId(), block.getName(), block.getInput()));
            }
            msg.setContent(blocks);
        }

        return msg;
    }

    /**
     * 处理 ask_user tool_use:
     * 1. 注册 PendingAsk
     * 2. 广播 ask_user 事件 + session_status=waiting_user
     * 3. 阻塞等待用户答复(CountDownLatch)
     * 4. 将答复包装成 tool_result 返回
     */
    @SuppressWarnings("unchecked")
    private Message handleAskUser(ToolUseBlock block, LoopContext loopContext) {
        String toolUseId = block.getId();
        Map<String, Object> input = block.getInput() != null ? block.getInput() : Collections.emptyMap();

        if (pendingAskRegistry == null || broadcaster == null || loopContext.getSessionId() == null) {
            log.warn("ask_user called but broadcaster/registry not configured");
            return Message.toolResult(toolUseId,
                    "ask_user is not available in this context. Proceed with your best judgment or return a text response.",
                    true);
        }

        String question = input.get("question") != null ? input.get("question").toString() : "";
        String contextStr = input.get("context") != null ? input.get("context").toString() : "";
        boolean allowOther = !Boolean.FALSE.equals(input.get("allowOther"));

        ChatEventBroadcaster.AskUserEvent event = new ChatEventBroadcaster.AskUserEvent();
        event.askId = UUID.randomUUID().toString();
        event.question = question;
        event.context = contextStr;
        event.allowOther = allowOther;
        event.options = new ArrayList<>();

        Object optsRaw = input.get("options");
        if (optsRaw instanceof List<?> optsList) {
            for (Object o : optsList) {
                if (o instanceof Map<?, ?> m) {
                    ChatEventBroadcaster.AskUserEvent.Option opt = new ChatEventBroadcaster.AskUserEvent.Option();
                    Object lbl = m.get("label");
                    Object desc = m.get("description");
                    opt.label = lbl != null ? lbl.toString() : "";
                    opt.description = desc != null ? desc.toString() : null;
                    event.options.add(opt);
                } else if (o instanceof String s) {
                    event.options.add(new ChatEventBroadcaster.AskUserEvent.Option(s, null));
                }
            }
        }

        pendingAskRegistry.register(event.askId);
        String sessionId = loopContext.getSessionId();

        log.info("ask_user invoked: sessionId={}, askId={}, question={}", sessionId, event.askId, question);

        broadcaster.sessionStatus(sessionId, "waiting_user", "Waiting for your reply", null);
        broadcaster.askUser(sessionId, event);

        String answer;
        try {
            answer = pendingAskRegistry.await(event.askId, askUserTimeoutSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            broadcaster.sessionStatus(sessionId, "running", null, null);
            return Message.toolResult(toolUseId, "User answer wait was interrupted.", true);
        }

        broadcaster.sessionStatus(sessionId, "running", null, null);

        if (answer == null) {
            return Message.toolResult(toolUseId,
                    "User did not respond within the timeout. Continue with your best judgment or return a text response explaining you are still waiting.",
                    false);
        }
        return Message.toolResult(toolUseId, "User answered: " + answer, false);
    }

    private static String sanitizePromptValue(String value) {
        return value == null ? null : value.replaceAll("[\r\n\t]", " ").trim();
    }

    /**
     * 检测消息流是否有浪费信号, 用于 B1 触发 light 压缩。
     */
    private boolean detectWaste(List<Message> messages) {
        if (messages == null || messages.size() < 2) return false;
        // 1) 任一 tool_result content > 5KB
        // 2) 3+ 连续 is_error=true tool_result
        int consecutiveErrors = 0;
        for (Message m : messages) {
            if (m.getContent() instanceof List<?> blocks) {
                boolean hasError = false;
                boolean hasToolResult = false;
                for (Object o : blocks) {
                    if (!(o instanceof ContentBlock cb)) continue;
                    if ("tool_result".equals(cb.getType())) {
                        hasToolResult = true;
                        String c = cb.getContent();
                        if (c != null && c.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 5 * 1024) {
                            return true;
                        }
                        if (Boolean.TRUE.equals(cb.getIsError())) hasError = true;
                    }
                }
                if (hasToolResult && hasError) {
                    consecutiveErrors++;
                    if (consecutiveErrors >= 3) return true;
                } else if (hasToolResult) {
                    consecutiveErrors = 0;
                }
            }
        }
        // 3) 3+ 连续相同 tool_use (name + input hash)
        String lastSig = null;
        int consecutiveSame = 1;
        for (Message m : messages) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb && "tool_use".equals(cb.getType())) {
                        String sig = (cb.getName() != null ? cb.getName() : "") + "#"
                                + (cb.getInput() != null ? cb.getInput().toString().hashCode() : 0);
                        if (sig.equals(lastSig)) {
                            consecutiveSame++;
                            if (consecutiveSame >= 3) return true;
                        } else {
                            consecutiveSame = 1;
                            lastSig = sig;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 处理 LLM 发起的 compact_context tool_use。
     * <p>防循环保护: 本 iteration 已经跑过一次 compact (engine-soft, engine-hard, 或更早的 agent-tool)
     * 就直接短路 —— 无论 light 还是 full 都不再执行. 这是"每 iteration 最多一次 compact"的硬保证.
     *
     * <p>Package-private for unit testing the anti-loop guard.
     */
    Message handleCompactContext(ToolUseBlock block, LoopContext loopCtx) {
        String toolUseId = block.getId();
        Map<String, Object> input = block.getInput() != null ? block.getInput() : Collections.emptyMap();
        String level = input.get("level") != null ? input.get("level").toString() : "light";
        String reason = input.get("reason") != null ? input.get("reason").toString() : "(no reason)";

        if (compactorCallback == null) {
            return Message.toolResult(toolUseId, "compact_context is not available in this runtime.", true);
        }
        // 防循环:任何 level 都被此 flag 阻断 —— 一个 iteration 内至多一次 compact
        if (loopCtx.isCompactedThisIteration()) {
            log.info("compact_context short-circuited: already ran this iteration (level={})", level);
            return Message.toolResult(toolUseId,
                    "Skipped: a compact already ran this iteration", false);
        }

        try {
            CompactCallbackResult cr;
            if ("full".equalsIgnoreCase(level)) {
                cr = compactorCallback.compactFull(loopCtx.getSessionId(), loopCtx.getMessages(),
                        "agent-tool", reason);
            } else {
                cr = compactorCallback.compactLight(loopCtx.getSessionId(), loopCtx.getMessages(),
                        "agent-tool", reason);
            }
            if (cr != null && cr.performed) {
                loopCtx.setMessages(cr.messages);
                loopCtx.markCompactedThisIteration();
                String summary = String.format("Compact %s done: reclaimed %d tokens (%d→%d). %s",
                        level, cr.tokensReclaimed, cr.beforeTokens, cr.afterTokens,
                        cr.summary != null ? cr.summary : "");
                return Message.toolResult(toolUseId, summary, false);
            }
            String msg = cr != null && cr.summary != null ? cr.summary : "no-op";
            return Message.toolResult(toolUseId, "compact_context no-op: " + msg, false);
        } catch (Exception e) {
            log.error("compact_context failed", e);
            return Message.toolResult(toolUseId, "compact_context error: " + e.getMessage(), true);
        }
    }

    /**
     * 执行单个 tool call，返回 tool_result 消息。
     */
    private Message executeToolCall(ToolUseBlock block, LoopContext loopContext,
                                    List<ToolCallRecord> toolCallRecords) {
        String skillName = block.getName();
        String toolUseId = block.getId();
        Map<String, Object> input = block.getInput() != null ? block.getInput() : Collections.emptyMap();
        long startTime = System.currentTimeMillis();

        log.debug("Executing tool call: skill={}, id={}", skillName, toolUseId);

        try {
            // 检查是否为 SkillDefinition (zip 包 Skill)
            Optional<SkillDefinition> skillDefOpt = skillRegistry.getSkillDefinition(skillName);
            if (skillDefOpt.isPresent()) {
                // SkillDefinition 不走 execute()，直接返回 promptContent
                SkillDefinition skillDef = skillDefOpt.get();
                String content = skillDef.getPromptContent() != null ? skillDef.getPromptContent() : "";
                long duration = System.currentTimeMillis() - startTime;
                toolCallRecords.add(new ToolCallRecord(skillName, input, content, true, duration, startTime));
                log.debug("SkillDefinition '{}' returned promptContent, duration={}ms", skillName, duration);
                return Message.toolResult(toolUseId, content, false);
            }

            // 检查是否为内置 Skill
            Optional<Tool> toolOpt = skillRegistry.getTool(skillName);
            if (toolOpt.isPresent()) {
                Tool tool = toolOpt.get();
                SkillContext skillContext = new SkillContext(
                        loopContext.getWorkingDirectory(),
                        loopContext.getSessionId(),
                        loopContext.getUserId());

                // 执行 SkillHook.beforeSkillExecute()
                Map<String, Object> processedInput = input;
                for (SkillHook hook : skillHooks) {
                    processedInput = hook.beforeSkillExecute(skillName, processedInput, skillContext);
                    if (processedInput == null) {
                        log.warn("SkillHook rejected execution of skill '{}'", skillName);
                        long duration = System.currentTimeMillis() - startTime;
                        String errorMsg = "Skill execution rejected by hook";
                        toolCallRecords.add(new ToolCallRecord(skillName, input, errorMsg, false, duration, startTime));
                        return Message.toolResult(toolUseId, errorMsg, true);
                    }
                }

                // 执行 Skill
                SkillResult result = tool.execute(processedInput, skillContext);
                long duration = System.currentTimeMillis() - startTime;

                // 执行 SkillHook.afterSkillExecute()
                for (SkillHook hook : skillHooks) {
                    try {
                        hook.afterSkillExecute(skillName, processedInput, result, skillContext);
                    } catch (Exception e) {
                        log.error("SkillHook.afterSkillExecute failed for skill '{}'", skillName, e);
                    }
                }

                String output = result.isSuccess() ? result.getOutput() : result.getError();
                output = ToolResultTruncator.truncate(output);
                toolCallRecords.add(new ToolCallRecord(skillName, input, output, result.isSuccess(), duration, startTime));
                log.debug("Tool '{}' executed, success={}, duration={}ms", skillName, result.isSuccess(), duration);
                return Message.toolResult(toolUseId, output, !result.isSuccess());
            }

            // 找不到 Skill
            long duration = System.currentTimeMillis() - startTime;
            String errorMsg = "Unknown skill: " + skillName;
            toolCallRecords.add(new ToolCallRecord(skillName, input, errorMsg, false, duration, startTime));
            log.warn("Skill '{}' not found in registry", skillName);
            return Message.toolResult(toolUseId, errorMsg, true);

        } catch (Exception e) {
            // 异常不中断循环，返回 error tool_result
            long duration = System.currentTimeMillis() - startTime;
            String errorMsg = "Skill execution error: " + e.getMessage();
            toolCallRecords.add(new ToolCallRecord(skillName, input, errorMsg, false, duration, startTime));
            log.error("Skill '{}' threw exception", skillName, e);
            return Message.toolResult(toolUseId, errorMsg, true);
        }
    }

    /**
     * 构建 LoopResult。
     */
    private LoopResult buildResult(LoopContext context, List<Message> messages,
                                   String finalResponse, List<ToolCallRecord> toolCallRecords) {
        return new LoopResult(
                finalResponse,
                messages,
                context.getTotalInputTokens(),
                context.getTotalOutputTokens(),
                context.getLoopCount(),
                new ArrayList<>(toolCallRecords)
        );
    }

    /**
     * Convert a Map to a JSON string without external dependencies.
     * Handles String, Number, Boolean, null, Map, and List types recursively.
     */
    @SuppressWarnings("unchecked")
    static String mapToJson(Map<String, Object> map) {
        if (map == null) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            sb.append(valueToJson(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String valueToJson(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Map<?, ?> m) return mapToJson((Map<String, Object>) m);
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(valueToJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(v.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
