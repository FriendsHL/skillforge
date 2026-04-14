package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    public AgentService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
        this.objectMapper = new ObjectMapper();
    }

    public AgentEntity createAgent(AgentEntity agent) {
        return agentRepository.save(agent);
    }

    public AgentEntity updateAgent(Long id, AgentEntity updated) {
        AgentEntity existing = agentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + id));
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setModelId(updated.getModelId());
        existing.setSystemPrompt(updated.getSystemPrompt());
        existing.setSkillIds(updated.getSkillIds());
        existing.setConfig(updated.getConfig());
        existing.setSoulPrompt(updated.getSoulPrompt());
        existing.setToolsPrompt(updated.getToolsPrompt());
        existing.setOwnerId(updated.getOwnerId());
        existing.setPublic(updated.isPublic());
        existing.setStatus(updated.getStatus());
        existing.setMaxLoops(updated.getMaxLoops());
        if (updated.getExecutionMode() != null) {
            existing.setExecutionMode(updated.getExecutionMode());
        }
        return agentRepository.save(existing);
    }

    public void deleteAgent(Long id) {
        agentRepository.deleteById(id);
    }

    public AgentEntity getAgent(Long id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + id));
    }

    public List<AgentEntity> listAgents(Long ownerId) {
        if (ownerId != null) {
            return agentRepository.findByOwnerId(ownerId);
        }
        return agentRepository.findAll();
    }

    public List<AgentEntity> listPublicAgents() {
        return agentRepository.findByIsPublicTrue();
    }

    /**
     * 将 AgentEntity 转为 core 模块的 AgentDefinition，解析 JSON 字段。
     */
    public AgentDefinition toAgentDefinition(AgentEntity entity) {
        AgentDefinition def = new AgentDefinition();
        def.setId(String.valueOf(entity.getId()));
        def.setName(entity.getName());
        def.setDescription(entity.getDescription());
        def.setModelId(entity.getModelId());
        def.setSystemPrompt(entity.getSystemPrompt());
        def.setSoulPrompt(entity.getSoulPrompt());
        def.setToolsPrompt(entity.getToolsPrompt());

        // 解析 skillIds JSON
        if (entity.getSkillIds() != null && !entity.getSkillIds().isBlank()) {
            try {
                List<String> ids = objectMapper.readValue(entity.getSkillIds(),
                        new TypeReference<List<String>>() {});
                def.setSkillIds(ids);
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse skillIds JSON: {}", entity.getSkillIds(), e);
                def.setSkillIds(new ArrayList<>());
            }
        }

        // 解析 config JSON
        if (entity.getConfig() != null && !entity.getConfig().isBlank()) {
            try {
                Map<String, Object> configMap = objectMapper.readValue(entity.getConfig(),
                        new TypeReference<Map<String, Object>>() {});
                def.setConfig(configMap);
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse config JSON: {}", entity.getConfig(), e);
                def.setConfig(new HashMap<>());
            }
        }

        // Pass agent-level maxLoops into config map
        if (entity.getMaxLoops() != null) {
            def.getConfig().put("max_loops", entity.getMaxLoops());
        }

        return def;
    }
}
