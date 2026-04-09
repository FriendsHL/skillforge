package com.skillforge.core.engine;

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
    private final TokenCounter tokenCounter;
    private final ContextCompactor contextCompactor;
    /** 可选:实时事件广播(server 注入 WebSocket 实现)。null 时降级为无广播模式。 */
    private ChatEventBroadcaster broadcaster;
    /** 可选:ask_user 待答复注册中心。null 时 ask_user 调用会直接返回错误。 */
    private PendingAskRegistry pendingAskRegistry;
    /** ask_user 默认超时 */
    private long askUserTimeoutSeconds = 30 * 60L;

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
        this.tokenCounter = new TokenCounter();
        // contextCompactor needs an LlmProvider; resolve lazily on first use
        LlmProvider defaultProvider = llmProviderFactory.getProvider(defaultProviderName);
        this.contextCompactor = defaultProvider != null ? new ContextCompactor(defaultProvider, tokenCounter) : null;
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
            context = hook.beforeLoop(context);
            if (context == null) {
                log.warn("LoopHook interrupted the loop before start");
                return new LoopResult("Loop interrupted by hook", messages,
                        0, 0, 0, Collections.emptyList());
            }
        }
        // beforeLoop 可能修改了 messages
        messages = context.getMessages();
        // 确保 context 引用是 effectively final（beforeLoop 可能替换了 context 对象）
        final LoopContext loopCtx = context;

        // 4. 构建 system prompt
        List<SkillDefinition> skillDefs = new ArrayList<>(skillRegistry.getAllSkillDefinitions());
        String systemPrompt = new SystemPromptBuilder(agentDef, skillDefs, contextProviders).build();

        // 5. 收集 tools: 内置 Skill 的 ToolSchema + SkillDefinition 的描述 + (可选) ask_user
        List<ToolSchema> tools = collectTools(loopCtx.getExecutionMode());

        // 追踪工具调用记录
        List<ToolCallRecord> toolCallRecords = new CopyOnWriteArrayList<>();
        LlmResponse lastResponse = null;

        // 5.5 解析要使用的 LlmProvider 和模型名
        String[] resolvedModel = new String[1];
        LlmProvider llmProvider = resolveProvider(agentDef, resolvedModel);
        String actualModelId = resolvedModel[0];

        // 6. 进入循环
        boolean cancelled = false;
        while (loopCtx.getLoopCount() < loopCtx.getMaxLoops()) {
            // 取消检查(每次迭代开头)
            if (loopCtx.isCancelled()) {
                log.info("AgentLoop cancelled at loop {} (pre-iteration)", loopCtx.getLoopCount() + 1);
                cancelled = true;
                break;
            }
            log.debug("AgentLoop iteration {} / {}", loopCtx.getLoopCount() + 1, loopCtx.getMaxLoops());

            // a. 上下文压缩检查
            if (contextCompactor != null) {
                int maxContextTokens = agentDef.getMaxContextTokens();
                List<Message> compactedMessages = contextCompactor.compactIfNeeded(messages, systemPrompt, maxContextTokens);
                if (compactedMessages != messages) {
                    int beforeTokens = tokenCounter.countMessageTokens(messages);
                    int afterTokens = tokenCounter.countMessageTokens(compactedMessages);
                    log.info("Context compacted: {} messages -> {} messages, {} tokens -> {} tokens",
                            messages.size(), compactedMessages.size(), beforeTokens, afterTokens);
                    messages = compactedMessages;
                }
            }

            // b. 构建 LlmRequest
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt(systemPrompt);
            request.setMessages(messages);
            request.setTools(tools);
            request.setModel(actualModelId);
            request.setMaxTokens(agentDef.getMaxTokens());
            request.setTemperature(agentDef.getTemperature());

            // b. 流式调用 LLM,文本增量通过 broadcaster.assistantDelta 推到前端
            final java.util.concurrent.atomic.AtomicReference<LlmResponse> respHolder = new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicReference<Throwable> errHolder = new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.CountDownLatch streamDone = new java.util.concurrent.CountDownLatch(1);
            final String broadcastSid = loopCtx.getSessionId();
            try {
                // 流式 tool_use 分片需要记住 name(按 toolUseId 维度)才能广播 toolUseDelta
                final java.util.Map<String, String> streamToolNames = new java.util.concurrent.ConcurrentHashMap<>();
                llmProvider.chatStream(request, new com.skillforge.core.llm.LlmStreamHandler() {
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
                streamDone.await();
            } catch (Exception e) {
                log.error("LLM stream call failed at loop {}", loopCtx.getLoopCount(), e);
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
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

            // 取消检查(LLM 调用刚返回)
            if (loopCtx.isCancelled()) {
                log.info("AgentLoop cancelled after LLM call at loop {}", loopCtx.getLoopCount() + 1);
                cancelled = true;
                break;
            }

            // c. 累加 token 用量
            if (response.getUsage() != null) {
                loopCtx.addInputTokens(response.getUsage().getInputTokens());
                loopCtx.addOutputTokens(response.getUsage().getOutputTokens());
            }

            // d. 将 assistant 响应加入 messages 并广播
            Message assistantMsg = buildAssistantMessage(response);
            messages.add(assistantMsg);
            if (broadcaster != null && loopCtx.getSessionId() != null) {
                broadcaster.messageAppended(loopCtx.getSessionId(), assistantMsg);
            }

            // e. 判断是否 tool_use
            if (!response.isToolUse()) {
                // 循环结束
                log.info("AgentLoop completed with text response at loop {}", loopCtx.getLoopCount() + 1);
                break;
            }

            // 处理 tool_use: 先把 ask_user 从列表里拆出来走特殊分支,其余并行执行
            List<ToolUseBlock> toolUseBlocks = response.getToolUseBlocks();
            log.info("Processing {} tool call(s) at loop {}", toolUseBlocks.size(), loopCtx.getLoopCount() + 1);

            List<CompletableFuture<Message>> futures = new ArrayList<>();
            Map<Integer, Message> askResults = new HashMap<>();
            for (int i = 0; i < toolUseBlocks.size(); i++) {
                ToolUseBlock block = toolUseBlocks.get(i);
                if (AskUserTool.NAME.equals(block.getName())) {
                    Message result = handleAskUser(block, loopCtx);
                    askResults.put(i, result);
                } else {
                    final int idx = i;
                    final ToolUseBlock fblock = block;
                    if (broadcaster != null && loopCtx.getSessionId() != null) {
                        broadcaster.toolStarted(loopCtx.getSessionId(), fblock.getId(), fblock.getName(), fblock.getInput());
                    }
                    final long toolStart = System.currentTimeMillis();
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        Message r;
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
                            if (broadcaster != null && loopCtx.getSessionId() != null) {
                                long dur = System.currentTimeMillis() - toolStart;
                                broadcaster.toolFinished(loopCtx.getSessionId(), fblock.getId(), status, dur, errorMsg);
                            }
                        }
                        synchronized (askResults) {
                            askResults.put(idx, r);
                        }
                        return r;
                    }));
                }
            }

            // 等待所有并行 tool 执行完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 按原顺序加入 messages + 广播
            for (int i = 0; i < toolUseBlocks.size(); i++) {
                Message toolResult = askResults.get(i);
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
            return result;
        }

        // 检查是否因达到上限而退出
        if (loopCtx.getLoopCount() >= loopCtx.getMaxLoops()) {
            log.warn("AgentLoop reached max loops limit: {}", loopCtx.getMaxLoops());
            String limitMsg = "I've reached the maximum number of processing steps (" + loopCtx.getMaxLoops()
                    + "). Please try breaking your request into smaller parts.";
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
     * 收集所有可用的工具 schema：内置 Skill + SkillDefinition + (可选) ask_user。
     */
    private List<ToolSchema> collectTools(String executionMode) {
        List<ToolSchema> tools = new ArrayList<>();

        // 内置 Skill
        for (Skill skill : skillRegistry.getAllSkills()) {
            ToolSchema schema = skill.getToolSchema();
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
            Optional<Skill> skillOpt = skillRegistry.getSkill(skillName);
            if (skillOpt.isPresent()) {
                Skill skill = skillOpt.get();
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
                SkillResult result = skill.execute(processedInput, skillContext);
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
                toolCallRecords.add(new ToolCallRecord(skillName, input, output, result.isSuccess(), duration, startTime));
                log.debug("Skill '{}' executed, success={}, duration={}ms", skillName, result.isSuccess(), duration);
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
}
