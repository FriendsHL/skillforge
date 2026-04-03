package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.engine.ToolCallRecord;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.dto.ChatResponse;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.ModelUsageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ModelUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AgentService agentService;
    private final SessionService sessionService;
    private final SkillRegistry skillRegistry;
    private final AgentLoopEngine agentLoopEngine;
    private final ModelUsageRepository modelUsageRepository;
    private final ObjectMapper objectMapper;

    public ChatService(AgentService agentService,
                       SessionService sessionService,
                       SkillRegistry skillRegistry,
                       AgentLoopEngine agentLoopEngine,
                       ModelUsageRepository modelUsageRepository) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.skillRegistry = skillRegistry;
        this.agentLoopEngine = agentLoopEngine;
        this.modelUsageRepository = modelUsageRepository;
        this.objectMapper = new ObjectMapper();
    }

    public ChatResponse chat(String sessionId, String userMessage, Long userId) {
        // 1. 获取 Session -> 获取 AgentEntity -> 转为 AgentDefinition
        SessionEntity session = sessionService.getSession(sessionId);
        AgentEntity agentEntity = agentService.getAgent(session.getAgentId());
        AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);

        // 2. 从 Session 加载历史 messages
        List<Message> history = sessionService.getSessionMessages(sessionId);

        // 3. 从 SkillRegistry 收集 Agent 绑定的 SkillDefinition 列表
        List<SkillDefinition> skills = new ArrayList<>();
        for (String skillId : agentDef.getSkillIds()) {
            skillRegistry.getSkillDefinition(skillId).ifPresent(skills::add);
        }

        // 4. 调用 AgentLoopEngine.run
        log.info("Running agent loop: sessionId={}, agentId={}, userId={}", sessionId, agentEntity.getId(), userId);
        LoopResult result = agentLoopEngine.run(agentDef, userMessage, history, sessionId, userId);

        // 5. 保存更新后的 messages 到 Session
        sessionService.updateSessionMessages(sessionId, result.getMessages(),
                result.getTotalInputTokens(), result.getTotalOutputTokens());

        // 6. 记录 ModelUsage
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

        // 7. 返回 ChatResponse
        ChatResponse response = new ChatResponse();
        response.setResponse(result.getFinalResponse());
        response.setSessionId(sessionId);
        response.setInputTokens(result.getTotalInputTokens());
        response.setOutputTokens(result.getTotalOutputTokens());
        response.setToolCalls(result.getToolCalls());
        return response;
    }
}
