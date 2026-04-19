package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SkillDraftService {

    private static final Logger log = LoggerFactory.getLogger(SkillDraftService.class);

    private static final int MAX_SESSIONS = 10;
    private static final int MAX_MESSAGE_CHARS = 4000;

    private final SessionRepository sessionRepository;
    private final SkillDraftRepository skillDraftRepository;
    private final SkillRepository skillRepository;
    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;
    private final UserWebSocketHandler userWebSocketHandler;
    private final String defaultProviderName;

    public SkillDraftService(SessionRepository sessionRepository,
                             SkillDraftRepository skillDraftRepository,
                             SkillRepository skillRepository,
                             LlmProviderFactory llmProviderFactory,
                             ObjectMapper objectMapper,
                             LlmProperties llmProperties,
                             UserWebSocketHandler userWebSocketHandler) {
        this.sessionRepository = sessionRepository;
        this.skillDraftRepository = skillDraftRepository;
        this.skillRepository = skillRepository;
        this.llmProviderFactory = llmProviderFactory;
        this.objectMapper = objectMapper;
        this.userWebSocketHandler = userWebSocketHandler;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
    }

    // Not @Transactional — LLM call is IO-bound (5-20s); holding a DB connection that long
    // would exhaust the pool. saveAll() carries its own transaction from SimpleJpaRepository.
    public int extractFromRecentSessions(Long agentId, Long userId) {
        try {
            // DB-level filter+sort+limit avoids loading all sessions into memory (H3 fix)
            List<SessionEntity> eligibleSessions = sessionRepository
                    .findRecentEligibleSessionsForSkillDraft(agentId, PageRequest.of(0, MAX_SESSIONS));

            if (eligibleSessions.isEmpty()) {
                log.info("No eligible sessions found for agent {} to extract skill drafts", agentId);
                return 0;
            }

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

            String systemPrompt = """
                    You are an expert at analyzing AI agent conversation histories and extracting reusable skill patterns.

                    Analyze the sessions and identify distinct, reusable skills the agent performed.
                    A skill is a specific capability (a repeatable action pattern with clear inputs/outputs).

                    Detect the session type: CODE_GENERATION, SEARCH_ANALYSIS, DATA_ANALYSIS, or GENERAL.
                    For CODE_GENERATION: focus on code patterns and tool sequences.
                    For SEARCH_ANALYSIS: focus on search strategies and synthesis patterns.
                    For DATA_ANALYSIS: focus on data processing and reporting patterns.
                    For GENERAL: focus on any clear repeatable task patterns.

                    Output ONLY a JSON array (no markdown fences, no explanation), max 3 items.
                    Each element: {"name", "description", "triggers", "requiredTools", "promptHint", "extractionRationale"}
                    - name: short PascalCase identifier (2-4 words, no spaces)
                    - description: what this skill does (1-2 sentences)
                    - triggers: comma-separated phrases that indicate when to use this skill
                    - requiredTools: comma-separated tool names needed (Bash, FileRead, Grep, etc.) or empty string
                    - promptHint: detailed instructions for how the agent should execute this skill (3-5 sentences)
                    - extractionRationale: why this session demonstrates a reusable skill""";

            String userMessage = String.format(
                    "Here are the recent session histories. Extract reusable skills.%n%n%s",
                    sessionSummaries);

            LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
            if (provider == null) {
                log.error("No LLM provider available for skill draft extraction");
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
                log.warn("LLM returned empty response for skill draft extraction");
                return 0;
            }

            List<Map<String, String>> extracted;
            try {
                String cleaned = content.trim();
                if (cleaned.startsWith("```")) {
                    cleaned = cleaned.replaceFirst("```(?:json)?\\s*", "");
                    cleaned = cleaned.replaceFirst("\\s*```$", "");
                }
                extracted = objectMapper.readValue(cleaned, new TypeReference<>() {});
            } catch (Exception e) {
                log.error("Failed to parse LLM skill draft output: {}", e.getMessage());
                return 0;
            }

            Long resolvedOwnerId = userId != null ? userId : 0L;
            List<SkillDraftEntity> toSave = new ArrayList<>();
            for (Map<String, String> item : extracted) {
                String name = item.get("name");
                String description = item.get("description");
                if (name == null || name.isBlank() || description == null || description.isBlank()) {
                    continue;
                }
                SkillDraftEntity entity = new SkillDraftEntity();
                entity.setId(UUID.randomUUID().toString());
                entity.setOwnerId(resolvedOwnerId);
                entity.setName(name);
                entity.setDescription(description);
                entity.setTriggers(item.get("triggers"));
                entity.setRequiredTools(item.get("requiredTools"));
                entity.setPromptHint(item.get("promptHint"));
                entity.setExtractionRationale(item.get("extractionRationale"));
                entity.setStatus("draft");
                // sourceSessionId is intentionally null for batch extraction across multiple sessions;
                // set it only when extracting from a single specific session (future P1-2 work).
                toSave.add(entity);
            }

            // saveAll carries its own @Transactional (SimpleJpaRepository); DB exceptions propagate
            // cleanly without leaving the EntityManager in a rollback-only state (M1 fix).
            skillDraftRepository.saveAll(toSave);
            log.info("Extracted and saved {} skill drafts for agent {} (ownerId={})",
                    toSave.size(), agentId, resolvedOwnerId);

            if (userId != null) {
                userWebSocketHandler.broadcast(userId, Map.of(
                        "type", "skill_draft_extracted",
                        "count", toSave.size()
                ));
            }
            return toSave.size();
        } catch (Exception e) {
            log.error("Skill draft extraction failed for agent {}: {}", agentId, e.getMessage(), e);
            if (userId != null) {
                userWebSocketHandler.broadcast(userId, Map.of(
                        "type", "skill_draft_failed",
                        "error", e.getMessage() != null ? e.getMessage() : "unknown error"
                ));
            }
            return 0;
        }
    }

    @Transactional
    public SkillDraftEntity approveDraft(String draftId, Long reviewedBy) {
        // Pessimistic lock prevents concurrent approve calls creating duplicate SkillEntities (H2 fix)
        SkillDraftEntity draft = skillDraftRepository.findByIdForUpdate(draftId)
                .orElseThrow(() -> new RuntimeException("Skill draft not found: " + draftId));
        if (!"draft".equals(draft.getStatus())) {
            throw new RuntimeException("Draft is not in 'draft' status: " + draftId);
        }

        SkillEntity skill = new SkillEntity();
        skill.setName(draft.getName());
        skill.setDescription(draft.getDescription());
        skill.setTriggers(draft.getTriggers());
        skill.setRequiredTools(draft.getRequiredTools());
        skill.setOwnerId(draft.getOwnerId());
        skill.setSource("extracted");
        skill.setEnabled(true);
        skill.setRiskLevel("low");

        SkillEntity saved = skillRepository.save(skill);

        draft.setStatus("approved");
        draft.setSkillId(saved.getId());
        draft.setReviewedAt(Instant.now());
        draft.setReviewedBy(reviewedBy);
        skillDraftRepository.save(draft);
        return draft;
    }

    @Transactional
    public SkillDraftEntity discardDraft(String draftId, Long reviewedBy) {
        SkillDraftEntity draft = skillDraftRepository.findByIdForUpdate(draftId)
                .orElseThrow(() -> new RuntimeException("Skill draft not found: " + draftId));
        if (!"draft".equals(draft.getStatus())) {
            throw new RuntimeException("Draft is not in 'draft' status: " + draftId);
        }
        draft.setStatus("discarded");
        draft.setReviewedAt(Instant.now());
        draft.setReviewedBy(reviewedBy);
        return skillDraftRepository.save(draft);
    }

    @Transactional(readOnly = true)
    public List<SkillDraftEntity> getDrafts(Long ownerId) {
        return skillDraftRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    public boolean hasPendingDrafts(Long ownerId) {
        return skillDraftRepository.countByOwnerIdAndStatus(ownerId, "draft") > 0;
    }
}
