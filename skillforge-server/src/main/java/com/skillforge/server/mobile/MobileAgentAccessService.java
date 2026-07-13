package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class MobileAgentAccessService {

    static final String DEFAULT_AGENT_NAME = "Main Assistant";

    private static final String SELECTABLE_AGENT_TYPE = "user";
    private static final String SELECTABLE_STATUS = "active";
    private static final String SOURCE_OWNED = "owned";
    private static final String SOURCE_DEFAULT = "default";
    private static final String SOURCE_SHARED = "shared";
    private static final String ACCESS_DETAIL = "detail";
    private static final String ACCESS_SUMMARY = "summary";
    private static final String TOOL_ACCESS_ALL = "all";
    private static final String TOOL_ACCESS_ALLOWLIST = "allowlist";
    private static final int DEFAULT_MAX_LOOPS = 25;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;
    private final SkillRegistry skillRegistry;
    private final Long defaultAgentId;

    public MobileAgentAccessService(
            AgentRepository agentRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry,
            @Value("${skillforge.mobile.default-agent-id:3}") Long defaultAgentId) {
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
        this.skillRegistry = skillRegistry;
        this.defaultAgentId = defaultAgentId;
    }

    public List<MobileAgentListItemResponse> listSelectableAgents(Long userId) {
        Optional<AgentEntity> defaultAgent = findSelectableDefaultAgent(userId);
        Map<Long, AgentEntity> selectableById = new LinkedHashMap<>();

        addSelectable(selectableById, agentRepository.findByOwnerId(userId), userId);
        addSelectable(selectableById, agentRepository.findByIsPublicTrue(), userId);
        defaultAgent.ifPresent(agent -> selectableById.putIfAbsent(agent.getId(), agent));

        Long selectableDefaultId = defaultAgent.map(AgentEntity::getId).orElse(null);
        return selectableById.values().stream()
                .map(agent -> toListResponse(agent, userId, Objects.equals(agent.getId(), selectableDefaultId)))
                .toList();
    }

    public MobileAgentDetailResponse getAgentDetail(Long agentId, Long userId) {
        AgentEntity agent = requireSelectableAgent(agentId, userId);
        boolean isDefault = Objects.equals(agent.getId(), defaultAgentId);
        List<String> skillNames = parseStringList(agent.getSkillIds());
        ToolConfiguration tools = resolveTools(agent.getToolIds());
        MobileAgentListItemResponse summary = toListResponse(agent, userId, isDefault, skillNames, tools);
        boolean detailAccess = ACCESS_DETAIL.equals(summary.configurationAccess());

        return new MobileAgentDetailResponse(
                summary.id(),
                summary.name(),
                summary.description(),
                summary.role(),
                summary.modelId(),
                summary.status(),
                summary.source(),
                summary.visibility(),
                summary.isDefault(),
                summary.executionMode(),
                summary.skillCount(),
                summary.toolCount(),
                summary.toolAccess(),
                summary.configurationAccess(),
                detailAccess ? normalizeMaxLoops(agent.getMaxLoops()) : null,
                detailAccess ? normalizeThinkingMode(agent.getThinkingMode()) : null,
                detailAccess ? normalizeReasoningEffort(agent.getReasoningEffort()) : null,
                detailAccess ? skillNames : null,
                detailAccess ? tools.names() : null,
                detailAccess ? countEnabledSystemSkills(agent.getDisabledSystemSkills()) : null,
                detailAccess ? new MobileAgentPromptSummaryResponse(
                        promptMetadata(agent.getSystemPrompt()),
                        promptMetadata(agent.getSoulPrompt()),
                        promptMetadata(agent.getToolsPrompt())) : null);
    }

    public AgentEntity requireSelectableAgent(Long agentId, Long userId) {
        return agentRepository.findById(agentId)
                .filter(agent -> isSelectable(agent, userId))
                .orElseThrow(MobileAgentAccessService::notFound);
    }

    public AgentEntity requireSelectableDefaultAgent(Long userId) {
        return findSelectableDefaultAgent(userId)
                .orElseThrow(MobileAgentAccessService::notFound);
    }

    public Optional<AgentEntity> findSelectableDefaultAgent(Long userId) {
        return agentRepository.findById(defaultAgentId)
                .filter(agent -> isSelectable(agent, userId));
    }

    private void addSelectable(Map<Long, AgentEntity> target,
                               List<AgentEntity> candidates,
                               Long userId) {
        candidates.stream()
                .filter(agent -> isSelectable(agent, userId))
                .filter(agent -> agent.getId() != null)
                .forEach(agent -> target.putIfAbsent(agent.getId(), agent));
    }

    private boolean isSelectable(AgentEntity agent, Long userId) {
        return SELECTABLE_AGENT_TYPE.equals(agent.getAgentType())
                && SELECTABLE_STATUS.equals(agent.getStatus())
                && (Boolean.TRUE.equals(agent.isPublic())
                || (userId != null && userId.equals(agent.getOwnerId())));
    }

    private MobileAgentListItemResponse toListResponse(AgentEntity agent, Long userId, boolean isDefault) {
        List<String> skills = parseStringList(agent.getSkillIds());
        ToolConfiguration tools = resolveTools(agent.getToolIds());
        return toListResponse(agent, userId, isDefault, skills, tools);
    }

    private MobileAgentListItemResponse toListResponse(
            AgentEntity agent,
            Long userId,
            boolean isDefault,
            List<String> skills,
            ToolConfiguration tools) {
        boolean owned = userId != null && userId.equals(agent.getOwnerId());
        String source = isDefault ? SOURCE_DEFAULT : owned ? SOURCE_OWNED : SOURCE_SHARED;
        String access = owned || isDefault ? ACCESS_DETAIL : ACCESS_SUMMARY;
        return new MobileAgentListItemResponse(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getRole(),
                agent.getModelId(),
                agent.getStatus(),
                source,
                Boolean.TRUE.equals(agent.isPublic()) ? "public" : "private",
                isDefault,
                normalizeExecutionMode(agent.getExecutionMode()),
                skills.size(),
                tools.names().size(),
                tools.access(),
                access);
    }

    private ToolConfiguration resolveTools(String toolIdsJson) {
        if (toolIdsJson == null || toolIdsJson.isBlank()) {
            List<String> registeredNames = skillRegistry.getAllTools().stream()
                    .map(tool -> tool.getName())
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .distinct()
                    .sorted()
                    .toList();
            return new ToolConfiguration(TOOL_ACCESS_ALL, registeredNames);
        }
        return new ToolConfiguration(TOOL_ACCESS_ALLOWLIST, parseStringList(toolIdsJson));
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode value : root) {
                if (!value.isTextual()) {
                    return List.of();
                }
                String normalized = value.textValue().trim();
                if (!normalized.isEmpty() && !values.contains(normalized)) {
                    values.add(normalized);
                }
            }
            return List.copyOf(values);
        } catch (RuntimeException | java.io.IOException ignored) {
            return List.of();
        }
    }

    private int countEnabledSystemSkills(String disabledSystemSkillsJson) {
        Set<String> disabled = new LinkedHashSet<>(parseStringList(disabledSystemSkillsJson));
        return (int) skillRegistry.getAllSkillDefinitions().stream()
                .filter(definition -> definition.isSystem())
                .map(definition -> definition.getName())
                .filter(Objects::nonNull)
                .filter(name -> !disabled.contains(name))
                .count();
    }

    private static int normalizeMaxLoops(Integer maxLoops) {
        return maxLoops != null && maxLoops > 0 ? maxLoops : DEFAULT_MAX_LOOPS;
    }

    private static String normalizeExecutionMode(String executionMode) {
        return executionMode == null || executionMode.isBlank() ? "ask" : executionMode.trim();
    }

    private static String normalizeThinkingMode(String thinkingMode) {
        if (thinkingMode != null && Set.of("auto", "enabled", "disabled").contains(thinkingMode)) {
            return thinkingMode;
        }
        return "auto";
    }

    private static String normalizeReasoningEffort(String reasoningEffort) {
        if (reasoningEffort != null && Set.of("low", "medium", "high", "max").contains(reasoningEffort)) {
            return reasoningEffort;
        }
        return "provider_default";
    }

    private static MobileAgentPromptMetadataResponse promptMetadata(String prompt) {
        boolean configured = prompt != null && !prompt.isBlank();
        int characterCount = configured ? prompt.codePointCount(0, prompt.length()) : 0;
        return new MobileAgentPromptMetadataResponse(configured, characterCount);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private record ToolConfiguration(String access, List<String> names) {
    }
}
