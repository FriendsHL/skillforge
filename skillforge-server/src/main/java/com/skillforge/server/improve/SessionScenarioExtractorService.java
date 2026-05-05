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
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * EVAL-V2 M2: mechanically extract a single scenario from one session.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>≤1 user message → returns a single-turn {@code EvalScenarioEntity}
     *       (legacy shape: {@code task} = the lone user message text or session
     *       title fallback; {@code conversationTurns} = NULL).</li>
     *   <li>≥2 user messages → multi-turn case: {@code conversationTurns} is a
     *       JSON array of {@code {role, content}} entries (assistant turns become
     *       the {@link EvalScenario#ASSISTANT_PLACEHOLDER} literal); {@code task}
     *       is a short summary built from concatenated user messages so existing
     *       text-based UIs / search still find the case.</li>
     * </ul>
     *
     * <p>The returned entity is <b>not</b> persisted — caller decides when/how
     * to {@code save}; this lets tests assert pure extraction behaviour.
     *
     * @return a draft EvalScenarioEntity, or {@code null} if no usable user
     *         messages were found in the session (caller can skip).
     */
    public EvalScenarioEntity extractFromSession(SessionEntity session) {
        if (session == null) return null;
        List<Map<String, Object>> rawMessages = parseMessages(session.getMessagesJson());

        List<Map<String, Object>> userAssistantMsgs = rawMessages.stream()
                .filter(m -> {
                    String role = optionalRole(m.get("role"));
                    return "user".equals(role) || "assistant".equals(role);
                })
                .collect(Collectors.toList());

        List<String> userTexts = userAssistantMsgs.stream()
                .filter(m -> "user".equals(optionalRole(m.get("role"))))
                .map(m -> extractTextContent(m.get("content")))
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toList());

        if (userTexts.isEmpty()) {
            // Session has no addressable user content — nothing to extract.
            return null;
        }

        EvalScenarioEntity entity = new EvalScenarioEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setAgentId(session.getAgentId() != null ? session.getAgentId().toString() : "0");
        entity.setName(deriveName(session, userTexts));
        entity.setSourceSessionId(session.getId());
        entity.setStatus("draft");
        entity.setOracleType("llm_judge");
        entity.setExtractionRationale(userTexts.size() > 1
                ? "Multi-turn session with " + userTexts.size() + " user messages — extracted as conversation_turns spec."
                : "Single user message — extracted as single-turn task.");

        if (userTexts.size() <= 1) {
            // Legacy single-turn path: task = first (only) user message text.
            entity.setTask(userTexts.get(0));
            // conversationTurns left null
            return entity;
        }

        // Multi-turn path: build {role, content} list with assistant placeholder.
        List<Map<String, String>> turns = new ArrayList<>();
        for (Map<String, Object> m : userAssistantMsgs) {
            String role = optionalRole(m.get("role"));
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            Map<String, String> turn = new LinkedHashMap<>();
            turn.put("role", role);
            if ("assistant".equals(role)) {
                turn.put("content", EvalScenario.ASSISTANT_PLACEHOLDER);
            } else {
                String text = extractTextContent(m.get("content"));
                if (text == null || text.isBlank()) continue;
                turn.put("content", text);
            }
            turns.add(turn);
        }

        // If the session ends with a user turn (no trailing assistant), pad with
        // a placeholder so the runtime always has at least one assistant slot
        // following each user turn — matches the spec example shape.
        if (!turns.isEmpty() && "user".equals(turns.get(turns.size() - 1).get("role"))) {
            Map<String, String> trailing = new LinkedHashMap<>();
            trailing.put("role", "assistant");
            trailing.put("content", EvalScenario.ASSISTANT_PLACEHOLDER);
            turns.add(trailing);
        }

        try {
            entity.setConversationTurns(objectMapper.writeValueAsString(turns));
        } catch (Exception e) {
            log.warn("Failed to serialize conversation_turns for session {}: {}",
                    session.getId(), e.getMessage());
            // Degrade to single-turn fallback rather than dropping the extraction.
            entity.setTask(userTexts.get(0));
            return entity;
        }

        entity.setTask(buildMultiTurnSummary(userTexts));
        return entity;
    }

    /** Used internally + by tests to validate the single-turn vs multi-turn branch. */
    private List<Map<String, Object>> parseMessages(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse session messagesJson: {}", e.getMessage());
            return List.of();
        }
    }

    private static String optionalRole(Object raw) {
        if (raw == null) return "";
        return raw.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Message {@code content} is a string for plain text turns and a list of
     * content blocks (text / tool_use / tool_result) for richer turns. We
     * concatenate any text blocks; non-text blocks are dropped (they're not
     * useful as eval prompt content).
     */
    @SuppressWarnings("unchecked")
    private static String extractTextContent(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String s) return s;
        if (raw instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (o instanceof Map<?, ?> blockMap) {
                    Object type = blockMap.get("type");
                    Object text = blockMap.get("text");
                    if (("text".equals(type) || type == null) && text != null) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(text);
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return raw.toString();
    }

    private static String deriveName(SessionEntity session, List<String> userTexts) {
        if (session.getTitle() != null && !session.getTitle().isBlank()) {
            return session.getTitle().length() > 120
                    ? session.getTitle().substring(0, 120)
                    : session.getTitle();
        }
        String first = userTexts.get(0);
        String oneLine = first.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }

    private static String buildMultiTurnSummary(List<String> userTexts) {
        StringBuilder sb = new StringBuilder("Multi-turn session — user messages:");
        int max = Math.min(userTexts.size(), 5);
        for (int i = 0; i < max; i++) {
            String t = userTexts.get(i).replaceAll("\\s+", " ").trim();
            if (t.length() > 200) t = t.substring(0, 200) + "…";
            sb.append("\n").append(i + 1).append(". ").append(t);
        }
        if (userTexts.size() > max) {
            sb.append("\n…(+").append(userTexts.size() - max).append(" more)");
        }
        return sb.toString();
    }
}
