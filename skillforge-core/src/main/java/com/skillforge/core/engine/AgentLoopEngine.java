package com.skillforge.core.engine;

import com.skillforge.core.context.ContextProvider;
import com.skillforge.core.context.SystemPromptBuilder;
import com.skillforge.core.llm.LlmProvider;
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

    private final LlmProvider llmProvider;
    private final SkillRegistry skillRegistry;
    private final List<LoopHook> loopHooks;
    private final List<SkillHook> skillHooks;
    private final List<ContextProvider> contextProviders;

    public AgentLoopEngine(LlmProvider llmProvider,
                           SkillRegistry skillRegistry,
                           List<LoopHook> loopHooks,
                           List<SkillHook> skillHooks,
                           List<ContextProvider> contextProviders) {
        this.llmProvider = llmProvider;
        this.skillRegistry = skillRegistry;
        this.loopHooks = loopHooks != null ? loopHooks : Collections.emptyList();
        this.skillHooks = skillHooks != null ? skillHooks : Collections.emptyList();
        this.contextProviders = contextProviders != null ? contextProviders : Collections.emptyList();
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
        log.info("AgentLoop started for agent={}, session={}, user={}", agentDef.getName(), sessionId, userId);

        // 1. 创建 LoopContext
        LoopContext context = new LoopContext();
        context.setAgentDefinition(agentDef);
        context.setSessionId(sessionId);
        context.setUserId(userId);

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

        // 5. 收集 tools: 内置 Skill 的 ToolSchema + SkillDefinition 的描述
        List<ToolSchema> tools = collectTools();

        // 追踪工具调用记录
        List<ToolCallRecord> toolCallRecords = new CopyOnWriteArrayList<>();
        LlmResponse lastResponse = null;

        // 6. 进入循环
        while (loopCtx.getLoopCount() < loopCtx.getMaxLoops()) {
            log.debug("AgentLoop iteration {} / {}", loopCtx.getLoopCount() + 1, loopCtx.getMaxLoops());

            // a. 构建 LlmRequest
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt(systemPrompt);
            request.setMessages(messages);
            request.setTools(tools);
            request.setModel(agentDef.getModelId());
            request.setMaxTokens(agentDef.getMaxTokens());
            request.setTemperature(agentDef.getTemperature());

            // b. 调用 LLM
            LlmResponse response;
            try {
                response = llmProvider.chat(request);
            } catch (Exception e) {
                log.error("LLM call failed at loop {}", loopCtx.getLoopCount(), e);
                return buildResult(loopCtx, messages, "Error calling LLM: " + e.getMessage(), toolCallRecords);
            }
            lastResponse = response;

            // c. 累加 token 用量
            if (response.getUsage() != null) {
                loopCtx.addInputTokens(response.getUsage().getInputTokens());
                loopCtx.addOutputTokens(response.getUsage().getOutputTokens());
            }

            // d. 将 assistant 响应加入 messages
            Message assistantMsg = buildAssistantMessage(response);
            messages.add(assistantMsg);

            // e. 判断是否 tool_use
            if (!response.isToolUse()) {
                // 循环结束
                log.info("AgentLoop completed with text response at loop {}", loopCtx.getLoopCount() + 1);
                break;
            }

            // 处理 tool_use: 并行执行所有 tool calls
            List<ToolUseBlock> toolUseBlocks = response.getToolUseBlocks();
            log.info("Processing {} tool call(s) at loop {}", toolUseBlocks.size(), loopCtx.getLoopCount() + 1);

            List<CompletableFuture<Message>> futures = new ArrayList<>();
            for (ToolUseBlock block : toolUseBlocks) {
                futures.add(CompletableFuture.supplyAsync(() ->
                        executeToolCall(block, loopCtx, toolCallRecords)));
            }

            // 等待所有 tool 执行完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 将所有 tool_result 加入 messages
            for (CompletableFuture<Message> future : futures) {
                messages.add(future.join());
            }

            // f. loopCount++
            loopCtx.incrementLoopCount();
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
     * 收集所有可用的工具 schema：内置 Skill + SkillDefinition。
     */
    private List<ToolSchema> collectTools() {
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
