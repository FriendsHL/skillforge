package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.ModelUsageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AgentService agentService;
    private final SessionService sessionService;
    private final SkillRegistry skillRegistry;
    private final AgentLoopEngine agentLoopEngine;
    private final ModelUsageRepository modelUsageRepository;
    private final ChatEventBroadcaster broadcaster;
    private final ThreadPoolExecutor chatLoopExecutor;
    private final SessionTitleService sessionTitleService;
    private final SubAgentRegistry subAgentRegistry;
    private final CancellationRegistry cancellationRegistry;
    private final ObjectMapper objectMapper;

    public ChatService(AgentService agentService,
                       SessionService sessionService,
                       SkillRegistry skillRegistry,
                       AgentLoopEngine agentLoopEngine,
                       ModelUsageRepository modelUsageRepository,
                       ChatEventBroadcaster broadcaster,
                       @Qualifier("chatLoopExecutor") ThreadPoolExecutor chatLoopExecutor,
                       SessionTitleService sessionTitleService,
                       SubAgentRegistry subAgentRegistry,
                       CancellationRegistry cancellationRegistry) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.skillRegistry = skillRegistry;
        this.agentLoopEngine = agentLoopEngine;
        this.modelUsageRepository = modelUsageRepository;
        this.broadcaster = broadcaster;
        this.chatLoopExecutor = chatLoopExecutor;
        this.sessionTitleService = sessionTitleService;
        this.subAgentRegistry = subAgentRegistry;
        this.cancellationRegistry = cancellationRegistry;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 异步启动 chat loop。
     * 同步完成:持久化 user message、广播 running、提交线程池。
     * 异步执行:engine.run() + 持久化结果 + 广播 idle/error。
     *
     * @throws RejectedExecutionException 线程池满(controller 层捕获返 429)
     */
    public void chatAsync(String sessionId, String userMessage, Long userId) {
        // 1. 读当前 session 和 agent
        SessionEntity session = sessionService.getSession(sessionId);
        AgentEntity agentEntity = agentService.getAgent(session.getAgentId());

        // 2. 把 user message 立即持久化到 session.messagesJson,这样即使前端刷新也能看到
        List<Message> history = sessionService.getSessionMessages(sessionId);
        Message userMsg = Message.user(userMessage);
        List<Message> historyWithUser = new ArrayList<>(history);
        historyWithUser.add(userMsg);
        sessionService.saveSessionMessages(sessionId, historyWithUser);

        // 2.1 第一条 user message 时立即生成截断标题(同步,极快)
        if (history.isEmpty()) {
            sessionTitleService.applyImmediateTitle(sessionId, userMessage);
        }

        // 3. 更新 runtime 状态
        session.setRuntimeStatus("running");
        session.setRuntimeStep("Starting");
        session.setRuntimeError(null);
        sessionService.saveSession(session);

        // 4. 广播 user message + running 状态
        if (broadcaster != null) {
            broadcaster.messageAppended(sessionId, userMsg);
            broadcaster.sessionStatus(sessionId, "running", "Starting", null);
            // Per-user 通道:列表页据此把卡片翻成 running
            broadcaster.userEvent(session.getUserId(), sessionUpdatedPayload(session, historyWithUser.size()));
        }

        // 5. 提交到线程池异步跑 loop
        chatLoopExecutor.execute(() -> runLoop(sessionId, userMessage, userId, agentEntity, history));
    }

    /**
     * 组装 per-user 通道的 session_updated 轻量载荷(只含列表卡片需要的字段)。
     */
    private Map<String, Object> sessionUpdatedPayload(SessionEntity s, int messageCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "session_updated");
        m.put("sessionId", s.getId());
        m.put("runtimeStatus", s.getRuntimeStatus());
        m.put("runtimeStep", s.getRuntimeStep());
        m.put("runtimeError", s.getRuntimeError());
        m.put("messageCount", messageCount);
        m.put("title", s.getTitle());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    /**
     * 实际的 loop 执行(在 chatLoopExecutor 线程里跑)。
     */
    private void runLoop(String sessionId, String userMessage, Long userId,
                         AgentEntity agentEntity, List<Message> history) {
        long startedAt = System.currentTimeMillis();
        String finalMessage = null;
        int toolCallCount = 0;
        String finalStatus = "completed";
        try {
            // 解析 agent definition,并把 session 的 executionMode 注入 config
            AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);
            SessionEntity freshSession = sessionService.getSession(sessionId);
            String mode = freshSession.getExecutionMode();
            if (mode == null || mode.isBlank()) {
                mode = agentEntity.getExecutionMode() != null ? agentEntity.getExecutionMode() : "ask";
            }
            agentDef.getConfig().put("execution_mode", mode);

            // 收集 zip 包 Skill 定义
            List<SkillDefinition> skills = new ArrayList<>();
            for (String skillId : agentDef.getSkillIds()) {
                skillRegistry.getSkillDefinition(skillId).ifPresent(skills::add);
            }

            log.info("Running agent loop (async): sessionId={}, agentId={}, mode={}", sessionId, agentEntity.getId(), mode);
            // 预建 LoopContext 并注册到 CancellationRegistry, 让 /cancel 端点可以找到它
            LoopContext preCtx = new LoopContext();
            cancellationRegistry.register(sessionId, preCtx);
            LoopResult result = agentLoopEngine.run(agentDef, userMessage, history, sessionId, userId, preCtx);
            finalMessage = result.getFinalResponse();
            toolCallCount = result.getToolCalls() != null ? result.getToolCalls().size() : 0;

            boolean wasCancelled = "cancelled".equals(result.getStatus());
            if (wasCancelled) {
                finalStatus = "cancelled";
            }

            // 保存最终 messages(engine 已经把 user msg + 之后所有消息组装好了)
            sessionService.updateSessionMessages(sessionId, result.getMessages(),
                    result.getTotalInputTokens(), result.getTotalOutputTokens());

            // 记录 ModelUsage
            ModelUsageEntity usage = new ModelUsageEntity();
            usage.setUserId(userId);
            usage.setAgentId(agentEntity.getId());
            usage.setSessionId(sessionId);
            usage.setModelId(agentDef.getModelId());
            usage.setInputTokens((int) result.getTotalInputTokens());
            usage.setOutputTokens((int) result.getTotalOutputTokens());
            try {
                usage.setToolCalls(objectMapper.writeValueAsString(result.getToolCalls()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize tool calls", e);
                usage.setToolCalls("[]");
            }
            modelUsageRepository.save(usage);

            // 更新 session runtime 状态 = idle
            // 取消退出也是 idle, 通过 step="cancelled" 标注, 避免引入新的 runtimeStatus 枚举值
            SessionEntity s = sessionService.getSession(sessionId);
            s.setRuntimeStatus("idle");
            s.setRuntimeStep(wasCancelled ? "cancelled" : null);
            s.setRuntimeError(null);
            sessionService.saveSession(s);
            if (broadcaster != null) {
                broadcaster.sessionStatus(sessionId, "idle", wasCancelled ? "cancelled" : null, null);
                broadcaster.userEvent(s.getUserId(), sessionUpdatedPayload(s, result.getMessages().size()));
            }

            // 在 loop 完成后(messages 已经累积了若干轮)异步触发智能命名
            int finalCount = result.getMessages().size();
            log.info("Triggering maybeScheduleSmartRename: sessionId={}, msgCount={}", sessionId, finalCount);
            sessionTitleService.maybeScheduleSmartRename(sessionId, finalCount);

            log.info("Agent loop completed: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("Agent loop failed: sessionId={}", sessionId, e);
            finalStatus = "error";
            finalMessage = e.getMessage();
            try {
                SessionEntity s = sessionService.getSession(sessionId);
                s.setRuntimeStatus("error");
                s.setRuntimeStep(null);
                s.setRuntimeError(e.getMessage());
                sessionService.saveSession(s);
                if (broadcaster != null) {
                    broadcaster.sessionStatus(sessionId, "error", null, e.getMessage());
                    broadcaster.userEvent(s.getUserId(), sessionUpdatedPayload(s, s.getMessageCount()));
                }
            } catch (Exception inner) {
                log.error("Failed to mark session error: sessionId={}", sessionId, inner);
            }
        } finally {
            // 无论结果如何, 清理 CancellationRegistry 条目
            try {
                cancellationRegistry.unregister(sessionId);
            } catch (Exception ignored) {
            }
            // SubAgent 回调钩子:如果这是子 session,把结果 push 到父;如果这是父,drain 等待中的子结果
            try {
                subAgentRegistry.onSessionLoopFinished(sessionId, finalMessage, finalStatus,
                        toolCallCount, System.currentTimeMillis() - startedAt);
            } catch (Exception hookErr) {
                log.error("SubAgentRegistry hook failed: sessionId={}", sessionId, hookErr);
            }
        }
    }
}
