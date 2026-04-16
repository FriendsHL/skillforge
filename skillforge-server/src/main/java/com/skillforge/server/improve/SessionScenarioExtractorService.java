package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SessionScenarioExtractorService {

    private static final Logger log = LoggerFactory.getLogger(SessionScenarioExtractorService.class);

    private static final int MAX_SESSIONS = 10;
    private static final int MAX_MESSAGE_CHARS = 4000;
    private static final int MAX_SCENARIOS = 5;

    private final SessionRepository sessionRepository;
    private final EvalScenarioDraftRepository evalScenarioDraftRepository;
    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;
    private final String defaultProviderName;

    public SessionScenarioExtractorService(SessionRepository sessionRepository,
                                           EvalScenarioDraftRepository evalScenarioDraftRepository,
                                           LlmProviderFactory llmProviderFactory,
                                           ObjectMapper objectMapper,
                                           LlmProperties llmProperties) {
        this.sessionRepository = sessionRepository;
        this.evalScenarioDraftRepository = evalScenarioDraftRepository;
        this.llmProviderFactory = llmProviderFactory;
        this.objectMapper = objectMapper;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
    }

    @Transactional
    public int extractFromSessions(String agentId, Long userId) {
        // Load recent completed/idle sessions for this agent
        List<SessionEntity> allSessions = sessionRepository.findByAgentId(Long.parseLong(agentId));
        List<SessionEntity> eligibleSessions = allSessions.stream()
                .filter(s -> "completed".equals(s.getRuntimeStatus()) || "idle".equals(s.getRuntimeStatus()))
                .filter(s -> s.getMessagesJson() != null && !s.getMessagesJson().isBlank())
                .sorted((a, b) -> {
                    if (a.getCompletedAt() == null && b.getCompletedAt() == null) return 0;
                    if (a.getCompletedAt() == null) return 1;
                    if (b.getCompletedAt() == null) return -1;
                    return b.getCompletedAt().compareTo(a.getCompletedAt());
                })
                .limit(MAX_SESSIONS)
                .collect(Collectors.toList());

        if (eligibleSessions.isEmpty()) {
            log.info("No eligible sessions found for agent {} to extract scenarios", agentId);
            return 0;
        }

        // Build session summaries for LLM
        StringBuilder sessionSummaries = new StringBuilder();
        for (int i = 0; i < eligibleSessions.size(); i++) {
            SessionEntity session = eligibleSessions.get(i);
            String messages = session.getMessagesJson();
            String truncated = messages.length() > MAX_MESSAGE_CHARS
                    ? messages.substring(0, MAX_MESSAGE_CHARS) + "..."
                    : messages;
            sessionSummaries.append("--- Session ").append(i + 1).append(" ---\n");
            if (session.getTitle() != null) {
                sessionSummaries.append("Title: ").append(session.getTitle()).append("\n");
            }
            sessionSummaries.append(truncated).append("\n\n");
        }

        // Call LLM
        String systemPrompt = """
                You are an expert at analyzing AI agent conversation histories and extracting \
                representative, repeatable evaluation scenarios from them.

                Rules:
                - Analyze the provided session histories and extract distinct, representative task scenarios
                - Each scenario must be repeatable: the task description should be clear enough to execute independently
                - Each scenario must be evaluable: oracleExpected should describe what a correct completion looks like
                - Filter out casual chat, greetings, or conversations with no substantive task
                - Output ONLY a JSON array (no markdown fences, no explanation)
                - Each element: {"name", "description", "task", "oracleType": "llm_judge", "oracleExpected", "extractionRationale"}
                - Maximum 5 scenarios
                - name: short identifier (2-5 words)
                - task: the user request that the agent should handle
                - oracleExpected: description of what a correct response looks like
                - extractionRationale: why this session makes a good eval scenario""";

        String userMessage = String.format("""
                Here are the recent session histories for an AI agent. \
                Analyze them and extract representative evaluation scenarios.

                %s""", sessionSummaries);

        LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
        if (provider == null) {
            log.error("No LLM provider available for scenario extraction");
            return 0;
        }

        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(systemPrompt);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userMessage));
        request.setMessages(messages);
        request.setMaxTokens(3000);
        request.setTemperature(0.3);

        LlmResponse response = provider.chat(request);
        String content = response.getContent();
        if (content == null || content.isBlank()) {
            log.warn("LLM returned empty response for scenario extraction");
            return 0;
        }

        // Parse JSON array from LLM output
        List<Map<String, String>> extracted;
        try {
            // Strip markdown fences if present
            String cleaned = content.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceFirst("```(?:json)?\\s*", "");
                cleaned = cleaned.replaceFirst("\\s*```$", "");
            }
            extracted = objectMapper.readValue(cleaned, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse LLM scenario extraction output: {}", e.getMessage());
            return 0;
        }

        // Build valid entities (sourceSessionId is null for batch extraction — scenarios are synthesized
        // across multiple sessions and don't have a single source session)
        List<EvalScenarioEntity> toSave = new ArrayList<>();
        for (Map<String, String> item : extracted) {
            String name = item.get("name");
            String task = item.get("task");
            if (name == null || name.isBlank() || task == null || task.isBlank()) {
                continue;
            }

            EvalScenarioEntity entity = new EvalScenarioEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setAgentId(agentId);
            entity.setName(name);
            entity.setDescription(item.get("description"));
            entity.setTask(task);
            entity.setOracleType(item.getOrDefault("oracleType", "llm_judge"));
            entity.setOracleExpected(item.get("oracleExpected"));
            entity.setExtractionRationale(item.get("extractionRationale"));
            entity.setStatus("draft");
            toSave.add(entity);
        }

        // Atomic save — all-or-nothing within this @Transactional method
        evalScenarioDraftRepository.saveAll(toSave);
        log.info("Extracted and saved {} scenario drafts for agent {}", toSave.size(), agentId);
        return toSave.size();
    }
}
