package com.skillforge.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates that an agent's declared tool contract can be satisfied by the runtime. */
@Component
public class AgentToolConfigurationValidator {

    private static final Logger log = LoggerFactory.getLogger(AgentToolConfigurationValidator.class);

    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;
    private final AgentRepository agentRepository;

    public AgentToolConfigurationValidator(
            SkillRegistry skillRegistry,
            ObjectMapper objectMapper,
            AgentRepository agentRepository) {
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
        this.agentRepository = agentRepository;
    }

    public void validate(AgentEntity agent) {
        List<ConfigurationIssue> blockingIssues = inspect(agent).stream()
                .filter(issue -> !"TOOL_NOT_REGISTERED".equals(issue.code()))
                .toList();
        if (blockingIssues.isEmpty()) {
            return;
        }
        String details = blockingIssues.stream()
                .map(issue -> issue.code() + "=" + issue.toolNames())
                .collect(Collectors.joining("; "));
        throw new IllegalArgumentException("Agent tool configuration invalid: " + details);
    }

    public List<ConfigurationIssue> inspect(AgentEntity agent) {
        Objects.requireNonNull(agent, "agent");

        ParsedToolList configured = parseConfiguredTools(agent.getToolIds());
        if (!configured.valid()) {
            return List.of(new ConfigurationIssue("TOOL_IDS_INVALID", List.of()));
        }

        Set<String> registered = skillRegistry.getAllTools().stream()
                .map(tool -> tool.getName())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<ConfigurationIssue> issues = new ArrayList<>();

        if (!configured.unrestricted()) {
            List<String> unknownConfigured = configured.toolNames().stream()
                    .filter(name -> !registered.contains(name))
                    .toList();
            if (!unknownConfigured.isEmpty()) {
                issues.add(new ConfigurationIssue("TOOL_NOT_REGISTERED", unknownConfigured));
            }
        }

        ParsedToolList required = parseRequiredTools(agent.getConfig());
        if (!required.valid()) {
            issues.add(new ConfigurationIssue("REQUIRED_TOOL_IDS_INVALID", List.of()));
            return List.copyOf(issues);
        }

        List<String> unknownRequired = required.toolNames().stream()
                .filter(name -> !registered.contains(name))
                .toList();
        if (!unknownRequired.isEmpty()) {
            issues.add(new ConfigurationIssue("REQUIRED_TOOL_NOT_REGISTERED", unknownRequired));
        }

        if (!configured.unrestricted()) {
            Set<String> allowed = Set.copyOf(configured.toolNames());
            List<String> missingRequired = required.toolNames().stream()
                    .filter(name -> !allowed.contains(name))
                    .toList();
            if (!missingRequired.isEmpty()) {
                issues.add(new ConfigurationIssue("REQUIRED_TOOL_NOT_ALLOWED", missingRequired));
            }
        }
        return List.copyOf(issues);
    }

    private ParsedToolList parseConfiguredTools(String json) {
        if (json == null || json.isBlank()) {
            return ParsedToolList.unrestrictedList();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return ParsedToolList.invalid();
            }
            List<String> toolNames = readToolNames(root);
            if (toolNames == null) {
                return ParsedToolList.invalid();
            }
            return toolNames.isEmpty()
                    ? ParsedToolList.unrestrictedList()
                    : new ParsedToolList(toolNames, false, true);
        } catch (Exception e) {
            return ParsedToolList.invalid();
        }
    }

    private ParsedToolList parseRequiredTools(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return new ParsedToolList(List.of(), false, true);
        }
        try {
            JsonNode config = objectMapper.readTree(configJson);
            if (!config.isObject() || !config.has("required_tool_ids")) {
                return new ParsedToolList(List.of(), false, true);
            }
            JsonNode required = config.get("required_tool_ids");
            if (!required.isArray()) {
                return ParsedToolList.invalid();
            }
            List<String> toolNames = readToolNames(required);
            return toolNames == null
                    ? ParsedToolList.invalid()
                    : new ParsedToolList(toolNames, false, true);
        } catch (Exception e) {
            // Agent config historically accepts provider-specific free-form JSON. Only an
            // explicitly present required_tool_ids field participates in this validator.
            return new ParsedToolList(List.of(), false, true);
        }
    }

    private static List<String> readToolNames(JsonNode array) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonNode node : array) {
            if (!node.isTextual() || node.textValue().isBlank()) {
                return null;
            }
            names.add(node.textValue());
        }
        return List.copyOf(names);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void auditExistingAgents() {
        for (AgentEntity agent : agentRepository.findAll()) {
            for (ConfigurationIssue issue : inspect(agent)) {
                log.warn("Agent tool configuration issue agentId={} code={} tools={}",
                        agent.getId(), issue.code(), issue.toolNames());
            }
        }
    }

    public record ConfigurationIssue(String code, List<String> toolNames) { }

    private record ParsedToolList(List<String> toolNames, boolean unrestricted, boolean valid) {
        private static ParsedToolList unrestrictedList() {
            return new ParsedToolList(List.of(), true, true);
        }

        private static ParsedToolList invalid() {
            return new ParsedToolList(List.of(), false, false);
        }
    }
}
