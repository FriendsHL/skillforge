package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.memory.context.MemoryContextProvider;
import com.skillforge.server.memory.context.MemoryContextSnapshot;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.skill.AllocationContext;
import com.skillforge.server.skill.SkillCreatorService;
import com.skillforge.server.skill.SkillSource;
import com.skillforge.server.skill.SkillStorageService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plan r2 §3 + §4 + §9 — SkillDraft 提取 / 审核 / 落盘 service。
 */
@Service
public class SkillDraftService {

    private static final Logger log = LoggerFactory.getLogger(SkillDraftService.class);

    private static final int MAX_SESSIONS = 10;
    private static final int MAX_MESSAGE_CHARS = 4000;

    // Localized provider+model override for skill draft extraction (2026-05-13).
    // bailian token went stale and there isn't a UI model picker yet; future
    // direction is per-call configurability (FE picker or agent-based extraction).
    // Falls back to the configured default-provider when xiaomi-mimo isn't registered.
    private static final String EXTRACT_PROVIDER_NAME = "xiaomi-mimo";
    private static final String EXTRACT_MODEL = "mimo-v2.5-pro";

    /**
     * Phase 1.4d F2 fix (2026-05-17, code reviewer HIGH-3 + java W-R1-3):
     * shared YAML mapper for {@link #parseSkillMdOutput}. Creating
     * {@code new ObjectMapper(new YAMLFactory())} per parse is the canonical
     * performance anti-pattern (java.md known footgun #1 family — ObjectMapper
     * construction is expensive and the instance is thread-safe after config).
     *
     * <p>Note: we deliberately do <b>not</b> {@code registerModule(new JavaTimeModule())}
     * here — the LLM frontmatter we parse never contains {@code Instant}/
     * {@code LocalDateTime} fields (just CSV-ish triggers + body text), so the
     * java.md footgun #1 caveat doesn't apply and skipping the module avoids
     * dragging in jackson-datatype-jsr310 just for nothing.
     */
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /** Plan r2 §9 — dedupe similarity thresholds. */
    static final double DEDUP_HIGH = 0.85;
    static final double DEDUP_MERGE_LOW = 0.60;

    private final SessionRepository sessionRepository;
    private final SkillDraftRepository skillDraftRepository;
    private final SkillRepository skillRepository;
    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;
    private final UserWebSocketHandler userWebSocketHandler;
    private final SkillCreatorService skillCreatorService;
    private final SkillPackageLoader skillPackageLoader;
    private final SkillRegistry skillRegistry;
    private final SkillStorageService skillStorageService;
    private final String defaultProviderName;
    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4e (2026-05-17) — ephemeral-fallback +
     * A/B dispatch deps used only by {@link #startAbTestFromDraft}. Nullable
     * so existing tests that don't exercise that path can pass {@code mock(...)}
     * (per W3 fix pattern from PromptImproverService).
     */
    private final com.skillforge.server.repository.EvalScenarioDraftRepository evalScenarioRepository;
    private final com.skillforge.server.repository.OptimizationEventRepository optimizationEventRepository;
    private final com.skillforge.server.repository.PatternSessionMemberRepository patternSessionMemberRepository;
    private final SessionScenarioExtractorService sessionScenarioExtractor;
    private final EphemeralScenarioCleanupService ephemeralScenarioCleanupService;
    private final SkillAbEvalService skillAbEvalService;
    private final MemoryContextProvider memoryContextProvider;

    /**
     * Test-only override for the artifact root directory. {@code null} in production —
     * SkillStorageService.allocate() is the source of truth. {@link #setSkillsDir(String)}
     * preserves the legacy 2-layer {@code <skillsDir>/<ownerId>/<skillId>} layout used by
     * SkillDraftServiceApproveDraftTest fixtures.
     */
    private String skillsDir = null;

    public SkillDraftService(SessionRepository sessionRepository,
                             SkillDraftRepository skillDraftRepository,
                             SkillRepository skillRepository,
                             LlmProviderFactory llmProviderFactory,
                             ObjectMapper objectMapper,
                             LlmProperties llmProperties,
                             UserWebSocketHandler userWebSocketHandler,
                             SkillCreatorService skillCreatorService,
                             SkillPackageLoader skillPackageLoader,
                             SkillRegistry skillRegistry,
                             SkillStorageService skillStorageService,
                             com.skillforge.server.repository.EvalScenarioDraftRepository evalScenarioRepository,
                             com.skillforge.server.repository.OptimizationEventRepository optimizationEventRepository,
                             com.skillforge.server.repository.PatternSessionMemberRepository patternSessionMemberRepository,
                             SessionScenarioExtractorService sessionScenarioExtractor,
                             EphemeralScenarioCleanupService ephemeralScenarioCleanupService,
                             @org.springframework.context.annotation.Lazy SkillAbEvalService skillAbEvalService) {
        this(sessionRepository, skillDraftRepository, skillRepository, llmProviderFactory,
                objectMapper, llmProperties, userWebSocketHandler, skillCreatorService,
                skillPackageLoader, skillRegistry, skillStorageService, evalScenarioRepository,
                optimizationEventRepository, patternSessionMemberRepository, sessionScenarioExtractor,
                ephemeralScenarioCleanupService, skillAbEvalService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillDraftService(SessionRepository sessionRepository,
                             SkillDraftRepository skillDraftRepository,
                             SkillRepository skillRepository,
                             LlmProviderFactory llmProviderFactory,
                             ObjectMapper objectMapper,
                             LlmProperties llmProperties,
                             UserWebSocketHandler userWebSocketHandler,
                             SkillCreatorService skillCreatorService,
                             SkillPackageLoader skillPackageLoader,
                             SkillRegistry skillRegistry,
                             SkillStorageService skillStorageService,
                             com.skillforge.server.repository.EvalScenarioDraftRepository evalScenarioRepository,
                             com.skillforge.server.repository.OptimizationEventRepository optimizationEventRepository,
                             com.skillforge.server.repository.PatternSessionMemberRepository patternSessionMemberRepository,
                             SessionScenarioExtractorService sessionScenarioExtractor,
                             EphemeralScenarioCleanupService ephemeralScenarioCleanupService,
                             @org.springframework.context.annotation.Lazy SkillAbEvalService skillAbEvalService,
                             MemoryContextProvider memoryContextProvider) {
        this.sessionRepository = sessionRepository;
        this.skillDraftRepository = skillDraftRepository;
        this.skillRepository = skillRepository;
        this.llmProviderFactory = llmProviderFactory;
        this.objectMapper = objectMapper;
        this.userWebSocketHandler = userWebSocketHandler;
        this.skillCreatorService = skillCreatorService;
        this.skillPackageLoader = skillPackageLoader;
        this.skillRegistry = skillRegistry;
        this.skillStorageService = skillStorageService;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
        this.evalScenarioRepository = evalScenarioRepository;
        this.optimizationEventRepository = optimizationEventRepository;
        this.patternSessionMemberRepository = patternSessionMemberRepository;
        this.sessionScenarioExtractor = sessionScenarioExtractor;
        this.ephemeralScenarioCleanupService = ephemeralScenarioCleanupService;
        this.skillAbEvalService = skillAbEvalService;
        this.memoryContextProvider = memoryContextProvider;
    }

    // Not @Transactional — LLM call is IO-bound (5-20s); holding a DB connection that long
    // would exhaust the pool. saveAll() carries its own transaction from SimpleJpaRepository.
    public int extractFromRecentSessions(Long agentId, Long userId) {
        try {
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

            // Plan r2 §4 — Extractor produces ONLY metadata (no SKILL.md body).
            // Render → SkillCreatorService at approveDraft time.
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
                    - requiredTools: comma-separated tool names needed (Bash, Read, Grep, etc.) or empty string
                    - promptHint: instructions for how the agent should execute this skill (3-5 sentences). This becomes the SKILL.md body when approved.
                    - extractionRationale: why this session demonstrates a reusable skill""";

            String userMessage = String.format(
                    "Here are the recent session histories. Extract reusable skills.%n%n%s",
                    sessionSummaries);

            LlmProvider provider = llmProviderFactory.getProvider(EXTRACT_PROVIDER_NAME);
            boolean preferredAvailable = provider != null;
            if (!preferredAvailable) {
                log.warn("Preferred skill draft provider '{}' not available, falling back to default '{}'",
                        EXTRACT_PROVIDER_NAME, defaultProviderName);
                provider = llmProviderFactory.getProvider(defaultProviderName);
            }
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
            // Only pin the model when we resolved the preferred provider; otherwise leave
            // it unset so the fallback provider picks its own default-model.
            if (preferredAvailable) {
                request.setModel(EXTRACT_MODEL);
            }

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

            // Anchor session id — used by the per-(owner, agent) pending-draft gate
            // (SkillDraftController.triggerExtraction) to resolve drafts back to
            // their originating agent via sourceSessionId → t_session.agent_id.
            // Picking the most-recent eligible session is a heuristic (a draft is
            // derived from multiple sessions); good enough since all sessions in
            // this batch belong to the same agent.
            String anchorSessionId = eligibleSessions.get(0).getId();

            // Plan r2 §9 — pre-save dedupe scoring against existing skills + drafts of this owner.
            List<SkillEntity> existingSkills = skillRepository.findByOwnerId(resolvedOwnerId);
            List<SkillDraftEntity> existingDrafts = skillDraftRepository
                    .findByOwnerIdAndStatus(resolvedOwnerId, "draft");

            List<SkillDraftEntity> toSave = new ArrayList<>();
            int high = 0, merge = 0;
            for (Map<String, String> item : extracted) {
                String name = item.get("name");
                String description = item.get("description");
                if (name == null || name.isBlank() || description == null || description.isBlank()) {
                    continue;
                }
                // SKILL-DASHBOARD-POLISH F: skip drafts whose name already collides with an
                // existing enabled/disabled skill row for this owner — case-insensitive.
                // Without this skip, the cron extractor would produce a "dead" draft that
                // approveDraft() can never satisfy: V64's partial unique index on
                // (owner_id, name) WHERE enabled=true would fire as soon as a forceCreate
                // approval flips the new row to enabled, producing a 409 SkillNameConflict
                // error in the UI. Skipping at extraction time is friendlier than rejecting
                // at approval time. Logged at DEBUG so INFO logs stay clean.
                final String draftName = name;
                boolean exactNameExists = existingSkills.stream()
                        .anyMatch(s -> s.getName() != null
                                && s.getName().equalsIgnoreCase(draftName));
                if (exactNameExists) {
                    log.debug("Skipping draft '{}' for ownerId={}: exact-name match with existing skill",
                            draftName, resolvedOwnerId);
                    continue;
                }
                SkillDraftEntity entity = new SkillDraftEntity();
                entity.setId(UUID.randomUUID().toString());
                entity.setOwnerId(resolvedOwnerId);
                entity.setSourceSessionId(anchorSessionId);
                entity.setName(name);
                entity.setDescription(description);
                entity.setTriggers(item.get("triggers"));
                entity.setRequiredTools(item.get("requiredTools"));
                entity.setPromptHint(item.get("promptHint"));
                entity.setExtractionRationale(item.get("extractionRationale"));
                entity.setStatus("draft");

                // Plan r2 §9 + Code Judge r1 B-FE-3 — persist similarity / merge candidate
                // on every draft (not auto-fold high-sim). FE Modal.confirm + forceCreate
                // controls whether high-sim drafts can ultimately be approved.
                DedupeMatch match = scoreSimilarity(entity, existingSkills, existingDrafts);
                if (match != null && match.similarity > 0) {
                    entity.setSimilarity(match.similarity);
                    entity.setMergeCandidateId(extractCandidateId(match.matchedRef));
                    entity.setMergeCandidateName(match.matchedName);
                    if (match.similarity >= DEDUP_HIGH) {
                        high++;
                    } else if (match.similarity >= DEDUP_MERGE_LOW) {
                        merge++;
                    }
                }
                toSave.add(entity);
            }

            skillDraftRepository.saveAll(toSave);
            log.info("Extracted skill drafts for agent {} (ownerId={}): saved={} highSim={} mergeFlagged={}",
                    agentId, resolvedOwnerId, toSave.size(), high, merge);

            if (userId != null) {
                // agentId included so the FE TaskTracker can match the running
                // task entry by relatedId when multiple extractions run in parallel.
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "skill_draft_extracted");
                payload.put("count", toSave.size());
                payload.put("highSimilarityFlagged", high);
                payload.put("mergeFlagged", merge);
                if (agentId != null) payload.put("agentId", agentId);
                userWebSocketHandler.broadcast(userId, payload);
            }
            return toSave.size();
        } catch (Exception e) {
            log.error("Skill draft extraction failed for agent {}: {}", agentId, e.getMessage(), e);
            if (userId != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "skill_draft_failed");
                payload.put("error", e.getMessage() != null ? e.getMessage() : "unknown error");
                if (agentId != null) payload.put("agentId", agentId);
                userWebSocketHandler.broadcast(userId, payload);
            }
            return 0;
        }
    }

    /**
     * V3 ATTRIBUTION-AGENT Phase 1.3 — attribution-aware draft creation.
     * FLYWHEEL-LOOP-CLOSURE Phase 1.1 (2026-05-16) — upgraded from deterministic stub
     * to <b>sync LLM fill</b>: triggers / requiredTools / SKILL.md body are now
     * generated by xiaomi-mimo (mimo-v2.5-pro) so {@code candidate_ready} truly
     * means "draft is A/B-testable". Mirrors
     * {@code PromptImproverService.startImprovementFromAttribution} (REQUIRES_NEW
     * tx + audit-trail rethrow), using the
     * {@link SessionScenarioExtractorService} / {@link #extractFromRecentSessions}
     * provider pattern (preferred xiaomi-mimo → fallback default, maxTokens=4000
     * to survive mimo-v2.5-pro reasoning, model only pinned when preferred resolved).
     *
     * <p>Called by {@code AttributionApprovalService.approve} when a curator's
     * proposal targets {@code surface=skill}. Persists a SkillDraftEntity tagged
     * with the originating eventId so the dashboard can pivot back to the
     * curator's reasoning chain.
     *
     * <p>No new schema column added: the {@code eventId} + {@code patternId} +
     * {@code changeType} are folded into
     * {@link SkillDraftEntity#getExtractionRationale()} as a structured prefix.
     * Reviewers can grep {@code [attribution:eventId=} to find every
     * attribution-derived draft. {@code sourceSessionId} stays null because
     * an attribution proposal isn't anchored to a single session — it generalises
     * across the pattern's member sessions.
     *
     * <p>Existing {@link #extractFromRecentSessions} signature unchanged.
     *
     * @param eventId               the {@code t_optimization_event.id} that triggered
     *                              this draft (folded into extractionRationale)
     * @param patternId             the {@code t_session_pattern.id} the proposal targets
     * @param attributedDescription curator's 1-3 sentence change description
     * @param expectedImpact        curator's expected metric change
     * @param changeType            curator's free-form change type identifier
     * @param ownerId               draft owner (typically pattern's agent owner)
     * @param suggestedSkillName    suggested name for the draft (typically derived
     *                              from changeType + patternId by caller)
     */
    /*
     * REQUIRES_NEW (Phase 1.3 reviewer fix — V2 W2 same lesson):
     * AttributionApprovalService.approve runs in @Transactional(REQUIRED) and
     * catches RuntimeException from this method to persist
     * stage=candidate_failed. If we JOIN that outer tx (default REQUIRED),
     * a draft-write failure would mark the outer tx setRollbackOnly →
     * approve's candidate_failed save() would commit but the surrounding
     * tx still rolls back → operator never sees the failure.
     * REQUIRES_NEW gives this method an independent tx that commits or
     * rolls back without touching the outer one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SkillDraftEntity createDraftFromAttribution(Long eventId,
                                                      Long patternId,
                                                      String attributedDescription,
                                                      String expectedImpact,
                                                      String changeType,
                                                      Long ownerId,
                                                      String suggestedSkillName) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        if (patternId == null) throw new IllegalArgumentException("patternId is required");
        if (ownerId == null) throw new IllegalArgumentException("ownerId is required");
        if (attributedDescription == null || attributedDescription.isBlank()) {
            throw new IllegalArgumentException("attributedDescription is required and must be non-blank");
        }
        if (suggestedSkillName == null || suggestedSkillName.isBlank()) {
            throw new IllegalArgumentException("suggestedSkillName is required and must be non-blank");
        }

        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId(UUID.randomUUID().toString());
        draft.setOwnerId(ownerId);
        // sourceSessionId left null intentionally — attribution proposals
        // generalise across pattern members, not a single session anchor.
        draft.setName(suggestedSkillName.trim());
        draft.setDescription(attributedDescription.trim());
        draft.setStatus("draft");
        // extractionRationale carries the structured attribution metadata so
        // future grep / dashboard queries can pivot back to the source event
        // without needing a new FK column (prefer field reuse over schema
        // migration). Set BEFORE the LLM call so the audit-trail row written
        // on LLM failure still carries the pivot info.
        StringBuilder rationale = new StringBuilder();
        rationale.append("[attribution:eventId=").append(eventId)
                .append("|patternId=").append(patternId);
        if (changeType != null && !changeType.isBlank()) {
            rationale.append("|changeType=").append(changeType.trim());
        }
        rationale.append("] ").append(attributedDescription.trim());
        draft.setExtractionRationale(rationale.toString());

        // Phase 1.1 — try synchronous LLM fill; on failure persist a
        // content-blank draft so the audit trail (eventId/patternId/draft id)
        // survives, then rethrow so the outer AttributionApprovalService.approve
        // catches and writes stage=candidate_failed.
        // (C2 ratify 2026-05-16: removed parentSkill lookup — current caller's
        // synthetic "AttrSkill<patternId>_<eventId>" never matches an existing
        // skill, so the lookup was pure dead code. When a future curator gains
        // an "improve existing skill" capability, re-introduce the lookup +
        // parent prompt branch at that time.)
        try {
            SkillContentResult result =
                    generateCandidateSkillMdFromAttribution(ownerId, attributedDescription, expectedImpact);
            draft.setTriggers(safeCsv(result.triggers()));
            draft.setRequiredTools(safeCsv(result.requiredTools()));
            draft.setPromptHint(result.skillMdBody());
        } catch (RuntimeException llmEx) {
            // Audit-trail rethrow — mirrors V3.1 PromptImproverService.
            // Empty triggers/tools/hint signal "needs operator intervention";
            // extractionRationale (set above) preserves the why.
            draft.setTriggers("");
            draft.setRequiredTools("");
            draft.setPromptHint("");
            skillDraftRepository.save(draft);
            log.error("Attribution skill-draft LLM fill FAILED: draftId={} eventId={} patternId={}: {}",
                    draft.getId(), eventId, patternId, llmEx.getMessage());
            throw llmEx;
        }

        // Skip dedupe / similarity scoring — attribution drafts are deterministic
        // outputs of a curator decision, not LLM-extracted batches that need
        // collision detection. If two attribution events target the same pattern
        // (24h cooldown should prevent this) the operator sees both drafts in
        // the dashboard and can pick / merge manually.

        SkillDraftEntity saved = skillDraftRepository.save(draft);
        log.info("Attribution-derived skill draft created: draftId={} eventId={} patternId={} ownerId={} "
                        + "triggersLen={} toolsLen={} hintLen={}",
                saved.getId(), eventId, patternId, ownerId,
                saved.getTriggers() == null ? 0 : saved.getTriggers().length(),
                saved.getRequiredTools() == null ? 0 : saved.getRequiredTools().length(),
                saved.getPromptHint() == null ? 0 : saved.getPromptHint().length());
        return saved;
    }

    /**
     * Phase 1.1 helper — synchronous LLM call generating a candidate SKILL.md
     * (frontmatter triggers/required_tools + body) from an attribution proposal.
     * Mirrors {@code PromptImproverService.generateCandidatePromptFromAttribution}
     * in structure (REQUIRES_NEW caller, audit-trail rethrow on the outside) but
     * uses the same xiaomi-mimo / mimo-v2.5-pro / fallback-to-default pattern as
     * {@link #extractFromRecentSessions} and {@link SessionScenarioExtractorService}.
     *
     * @param attributedDescription curator's 1-3 sentence proposed change
     * @param expectedImpact        curator's expected metric impact (nullable)
     * @return parsed frontmatter (triggers / required_tools) + SKILL.md body
     * @throws RuntimeException on LLM error / empty response / unparseable output
     */
    private SkillContentResult generateCandidateSkillMdFromAttribution(String attributedDescription,
                                                                       String expectedImpact) {
        return generateCandidateSkillMdFromAttribution(null, attributedDescription, expectedImpact);
    }

    private SkillContentResult generateCandidateSkillMdFromAttribution(Long ownerUserId,
                                                                       String attributedDescription,
                                                                       String expectedImpact) {
        SkillMdPrompt prompt = buildSkillMdGenPrompt(attributedDescription, expectedImpact,
                loadRenderedMemoryContext(ownerUserId, attributedDescription, expectedImpact));

        LlmProvider provider = llmProviderFactory.getProvider(EXTRACT_PROVIDER_NAME);
        boolean preferredAvailable = provider != null;
        if (!preferredAvailable) {
            log.warn("Preferred attribution skill-draft provider '{}' not available, "
                    + "falling back to default '{}'", EXTRACT_PROVIDER_NAME, defaultProviderName);
            provider = llmProviderFactory.getProvider(defaultProviderName);
        }
        if (provider == null) {
            throw new RuntimeException("No LLM provider available for attribution skill-draft fill");
        }

        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(prompt.systemPrompt());
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(prompt.userMessage()));
        request.setMessages(messages);
        // mimo-v2.5-pro reasoning model — reserve generous budget so the
        // frontmatter + body both survive. SessionScenarioExtractorService
        // uses the same constant; centralise once we have a 3rd caller.
        request.setMaxTokens(4000);
        request.setTemperature(0.3);
        // Only pin the model when we actually resolved the preferred provider;
        // otherwise the fallback provider would receive an unknown model id.
        if (preferredAvailable) {
            request.setModel(EXTRACT_MODEL);
        }

        LlmResponse response = provider.chat(request);
        String content = response == null ? null : response.getContent();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("LLM returned empty content for attribution skill-draft fill");
        }
        return parseSkillMdOutput(content.trim());
    }

    /**
     * Phase 1.1 prompt builder — broken out from
     * {@link #generateCandidateSkillMdFromAttribution} so the prompt text can
     * be unit-tested independently if we ever need to (none today; helper kept
     * because future tweaks to the system / user prompt should be diff-able
     * without touching the LLM-call orchestration).
     */
    private static SkillMdPrompt buildSkillMdGenPrompt(String attributedDescription,
                                                       String expectedImpact,
                                                       String memoryContext) {
        String systemPrompt = """
                You are an expert SkillForge skill author. Convert an attribution proposal \
                (curator-derived change description) into a concrete SKILL.md file.

                Output STRICT format — nothing before, nothing after:
                ---
                triggers: [<phrase1>, <phrase2>, ...]
                required_tools: [<ToolName1>, <ToolName2>, ...]
                ---
                <SKILL.md body: 3-8 sentences instructing the agent how to execute
                the skill. No headings, no code fences, no meta-commentary.>

                Rules:
                - triggers: 2-5 short trigger phrases the agent recognises (lowercase,
                  no surrounding quotes)
                - required_tools: only canonical tool names (Bash, Read, Write, Edit,
                  Grep, Glob, WebFetch, WebSearch). Empty list [] if none needed.
                - Body must be self-contained instructions; do not reference the
                  attribution metadata.""";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Attribution proposal (from curator):\n")
                .append(attributedDescription.trim());
        if (expectedImpact != null && !expectedImpact.isBlank()) {
            userPrompt.append("\n\nExpected impact: ").append(expectedImpact.trim());
        }
        if (memoryContext != null && !memoryContext.isBlank()) {
            userPrompt.append("\n\nRelevant long-term memory context:\n")
                    .append(memoryContext.trim());
        }
        userPrompt.append("\n\nGenerate the SKILL.md per the strict format above.");
        return new SkillMdPrompt(systemPrompt, userPrompt.toString());
    }

    private String loadRenderedMemoryContext(Long ownerUserId,
                                             String attributedDescription,
                                             String expectedImpact) {
        if (memoryContextProvider == null || ownerUserId == null) {
            return "";
        }
        StringBuilder task = new StringBuilder();
        if (attributedDescription != null) {
            task.append(attributedDescription.trim());
        }
        if (expectedImpact != null && !expectedImpact.isBlank()) {
            task.append("\n").append(expectedImpact.trim());
        }
        try {
            MemoryContextSnapshot snapshot = memoryContextProvider.load(ownerUserId, task.toString());
            return snapshot != null && snapshot.rendered() != null ? snapshot.rendered() : "";
        } catch (RuntimeException e) {
            log.warn("Attribution skill-draft memory context load failed ownerUserId={}: {}",
                    ownerUserId, e.getMessage());
            return "";
        }
    }

    /** Phase 1.1 prompt holder — private since only {@code generateCandidateSkillMdFromAttribution} consumes it. */
    private record SkillMdPrompt(String systemPrompt, String userMessage) {}

    /**
     * Parse the strict {@code ---\n<yaml>\n---\n<body>} format produced by
     * {@link #generateCandidateSkillMdFromAttribution}. Tolerates an optional
     * markdown code fence wrapping the whole response (some providers wrap
     * everything in ```` ```markdown ... ``` ```` blocks).
     *
     * <p>Robustness contract: any malformed response throws
     * {@link RuntimeException} so the outer {@code createDraftFromAttribution}
     * audit-trail catch path triggers (no silent half-fills).
     *
     * <p>Package-private + static for direct unit-test access.
     */
    static SkillContentResult parseSkillMdOutput(String llmResponse) {
        String cleaned = llmResponse.trim();
        // Strip outer markdown code fence if the model wrapped its output.
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("```(?:markdown|md|yaml)?\\s*", "");
            int closeFence = cleaned.lastIndexOf("```");
            if (closeFence >= 0) {
                cleaned = cleaned.substring(0, closeFence).trim();
            }
        }
        if (!cleaned.startsWith("---")) {
            throw new RuntimeException("LLM output missing leading '---' frontmatter delimiter");
        }
        // Locate the closing delimiter — first '---' on its own line after the opener.
        int secondDelim = cleaned.indexOf("\n---", 3);
        if (secondDelim < 0) {
            throw new RuntimeException("LLM output missing closing '---' frontmatter delimiter");
        }
        String frontmatterYaml = cleaned.substring(3, secondDelim).trim();
        String body = cleaned.substring(secondDelim + 4).replaceFirst("^\\s*", "").trim();

        Map<String, Object> fm;
        try {
            fm = YAML_MAPPER.readValue(frontmatterYaml, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM frontmatter YAML: " + e.getMessage(), e);
        }
        if (fm == null) {
            throw new RuntimeException("LLM frontmatter parsed to null");
        }

        String triggers = renderCsv(fm.get("triggers"));
        String requiredTools = renderCsv(fm.get("required_tools"));
        return new SkillContentResult(triggers, requiredTools, body);
    }

    /** Normalize frontmatter value (string or list) into a comma-separated string. */
    private static String renderCsv(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
        }
        return value.toString().trim();
    }

    /** {@code null}-safe CSV pass-through used at entity-write time. */
    private static String safeCsv(String csv) {
        return csv == null ? "" : csv;
    }

    /**
     * Phase 1.1 parsed-frontmatter container. Package-private for unit tests
     * accessing {@link #parseSkillMdOutput} directly.
     */
    record SkillContentResult(String triggers, String requiredTools, String skillMdBody) {}

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.3 (2026-05-16) — called by
     * {@link com.skillforge.server.attribution.OptimizationEventAutoTriggerListener}
     * when an attribution event reaches {@code candidate_ready} on the
     * {@code surface=skill} path. Runs an A/B comparison between the parent
     * skill (or an empty SKILL.md baseline when no parent resolves) and the
     * candidate draft, using {@code evalScenarioIds} or the parent agent's
     * held-out scenarios when null.
     *
     * <p><b>Phase 1.3 scope</b>: signature + draft lookup + empty-scenarios
     * guard. The actual {@link SkillAbRunEntity} creation +
     * {@code SkillAbEvalService.createAndTrigger} call is Phase 1.4 — Phase
     * 1.4 will replace the {@link UnsupportedOperationException} body with
     * the real composition (per tech-design §服务层设计 #1 skill path).
     * Throwing here means the listener's catch path triggers an
     * {@code ab_failed} WS broadcast, so dogfood can observe wiring before
     * the Phase 1.4 implementation lands.
     *
     * @param candidateDraftId UUID of the {@link SkillDraftEntity} (V88
     *                         sidecar column {@code candidate_skill_draft_uuid})
     * @param evalScenarioIds  explicit scenario IDs; {@code null} or empty →
     *                         use parent agent's held-out scenarios. Empty
     *                         (and empty held-out) throws
     *                         {@link IllegalStateException} until Phase 1.4
     *                         ephemeral fallback lands.
     * @return newly-created {@code SkillAbRunEntity.id}
     * @throws IllegalArgumentException        candidate draft not found or
     *                                         not in {@code draft} status
     * @throws IllegalStateException           no EvalScenarios available
     *                                         (Phase 1.4 ephemeral fallback
     *                                         not yet implemented)
     * @throws UnsupportedOperationException   Phase 1.3 stub; Phase 1.4 wires
     *                                         the real {@code createAndTrigger}
     *                                         body
     */
    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4e (2026-05-17) sub-task 3 — real
     * implementation replacing Phase 1.4a synthetic-abRunId scaffold.
     * Mirrors {@code PromptImproverService.runAbTestAgainst} structure +
     * uses {@link #promoteDraftToTransientSkill(String)} (sub-task 2,
     * Phase 1.4c) to materialise the candidate side as a non-production
     * SkillEntity, plus a paired empty-SKILL.md baseline SkillEntity since
     * attribution-curator path has no real parent skill (Ratify #7-B).
     *
     * <p><b>Cleanup contract</b>:
     * <ul>
     *   <li>Synchronous failure (createAndTrigger throws) → delete transient
     *       candidate + baseline + ephemeral scenarios (via W5 sibling bean).</li>
     *   <li>A/B completes ABCANDIDATE_LOST or FAILED →
     *       {@code SkillSelfImproveLoop.onAbCompleted} branch (Phase 1.4e loser
     *       cleanup hook) deletes the transient candidate via name suffix /
     *       source dual pivot.</li>
     * </ul>
     *
     * <p><b>Caveat (documented for Phase 1.6 dogfood)</b>: the real
     * {@link SkillAbEvalService#createAndTrigger(Long, Long, String, String, Long)}
     * signature does not accept an explicit {@code scenarios} list — it loads
     * scenarios internally via {@code ScenarioLoader}. The ephemeral
     * scenarios we save here are therefore <b>not directly consumed</b> by
     * the current SkillAbEvalService run; they only ensure the
     * {@code t_eval_scenario} status='ephemeral' rows exist as a Phase 1.6
     * dogfood observability signal (operator can verify the fallback path
     * fired). Phase 1.4f or follow-up may extend createAndTrigger to accept
     * explicit scenarios; for now the eval execution falls back to
     * {@code ScenarioLoader.loadAll()} held-out set inside the pipeline.
     */
    @Transactional
    public String startAbTestFromDraft(String candidateDraftId, List<String> evalScenarioIds) {
        if (candidateDraftId == null || candidateDraftId.isBlank()) {
            throw new IllegalArgumentException("candidateDraftId is required");
        }
        SkillDraftEntity draft = skillDraftRepository.findById(candidateDraftId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Candidate skill draft not found: " + candidateDraftId));
        if (!"draft".equals(draft.getStatus())) {
            throw new IllegalArgumentException(
                    "Candidate skill draft is not in 'draft' status: " + candidateDraftId
                            + " (current=" + draft.getStatus() + ")");
        }

        // Promote draft → transient candidate SkillEntity (Phase 1.4c sub-2).
        SkillEntity candidateSkill = promoteDraftToTransientSkill(candidateDraftId);

        // Phase 1.4e: paired empty-SKILL.md baseline SkillEntity. parentSkillId
        // wiring lets SkillAbEvalService.createAndTrigger's "fork" validation
        // pass (candidate.parentSkillId == parentSkillId).
        SkillEntity baselineSkill = new SkillEntity();
        baselineSkill.setName(candidateSkill.getName() + "_baseline_empty");
        baselineSkill.setDescription("");
        baselineSkill.setTriggers("");
        baselineSkill.setRequiredTools("");
        baselineSkill.setOwnerId(candidateSkill.getOwnerId());
        baselineSkill.setEnabled(false);
        baselineSkill.setSource("attribution_ab_baseline_empty");
        baselineSkill.setSystem(false);
        baselineSkill.setRiskLevel("low");
        baselineSkill = skillRepository.save(baselineSkill);
        // Wire candidate.parentSkillId so createAndTrigger's fork-validation passes.
        candidateSkill.setParentSkillId(baselineSkill.getId());
        candidateSkill = skillRepository.save(candidateSkill);

        // Ephemeral fallback (mirrors PromptImproverService Ratify #7-E path).
        EphemeralBatch batch = buildEphemeralScenariosForSkillCandidate(
                candidateDraftId, evalScenarioIds);

        try {
            // agentId: attribution path uses ownerId fallback (no per-skill agent).
            String agentId = candidateSkill.getOwnerId() == null
                    ? "0" : String.valueOf(candidateSkill.getOwnerId());
            com.skillforge.server.entity.SkillAbRunEntity abRun = skillAbEvalService.createAndTrigger(
                    baselineSkill.getId(),
                    candidateSkill.getId(),
                    agentId,
                    /*baselineEvalRunId*/ null,
                    /*triggeredByUserId*/ null);
            log.info("Attribution skill A/B run created: draftId={} candidateSkillId={} "
                            + "baselineSkillId={} abRunId={} ephemeralCount={}",
                    candidateDraftId, candidateSkill.getId(), baselineSkill.getId(),
                    abRun.getId(), batch.ephemeralIds().size());
            return abRun.getId();
        } catch (RuntimeException ex) {
            // F5 fix (Phase 2 r2, code reviewer MEDIUM): this method is
            // @Transactional, so the outer tx will rollback when we rethrow ex
            // → candidateSkill + baselineSkill saves are automatically
            // undone. Explicit skillRepository.delete here was dead code
            // (it would also be rolled back). Ephemeral scenarios, on the
            // other hand, are persisted by EphemeralScenarioCleanupService /
            // SessionScenarioExtractorService via separate REQUIRES_NEW
            // transactions and DON'T rollback with our outer tx, so they
            // MUST be cleaned up explicitly here.
            log.error("Attribution skill A/B createAndTrigger failed draftId={}: {} "
                            + "(transient candidate+baseline SkillEntity will be auto-rolled-back "
                            + "by outer @Transactional; only ephemeral scenarios need explicit cleanup)",
                    candidateDraftId, ex.getMessage(), ex);
            ephemeralScenarioCleanupService.cleanupEphemerals(batch.ephemeralIds());
            throw ex;
        }
    }

    /**
     * Phase 1.4e Ratify #7-E helper — extract 3 ephemeral EvalScenarios from
     * the pattern members of the attribution event that produced the given
     * candidate SkillDraft. Symmetric to
     * {@code PromptImproverService.buildEphemeralScenariosForPromptCandidate}.
     */
    private EphemeralBatch buildEphemeralScenariosForSkillCandidate(String candidateDraftId,
                                                                     List<String> evalScenarioIds) {
        if (evalScenarioIds != null && !evalScenarioIds.isEmpty()) {
            List<com.skillforge.server.entity.EvalScenarioEntity> explicit =
                    evalScenarioRepository.findAllById(evalScenarioIds);
            return new EphemeralBatch(explicit, java.util.Collections.emptyList());
        }
        List<com.skillforge.server.entity.OptimizationEventEntity> events =
                optimizationEventRepository.findByCandidateSkillDraftUuid(candidateDraftId);
        if (events.isEmpty()) {
            log.warn("Ephemeral fallback: no OptimizationEvent linked to skill draft {} "
                            + "(V88 sidecar populated?). Returning empty scenario batch.",
                    candidateDraftId);
            return new EphemeralBatch(java.util.Collections.emptyList(),
                    java.util.Collections.emptyList());
        }
        com.skillforge.server.entity.OptimizationEventEntity event = events.get(0);
        Long patternId = event.getPatternId();
        if (patternId == null) {
            log.warn("Ephemeral fallback: OptimizationEvent {} has no patternId; cannot "
                    + "extract member scenarios", event.getId());
            return new EphemeralBatch(java.util.Collections.emptyList(),
                    java.util.Collections.emptyList());
        }
        List<com.skillforge.server.entity.PatternSessionMemberEntity> members =
                patternSessionMemberRepository.findByPatternIdOrderByAddedAtDesc(
                        patternId, org.springframework.data.domain.PageRequest.of(0, 3));
        List<com.skillforge.server.entity.EvalScenarioEntity> ephemerals = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (com.skillforge.server.entity.PatternSessionMemberEntity member : members) {
            com.skillforge.server.entity.SessionEntity sess =
                    sessionRepository.findById(member.getSessionId()).orElse(null);
            if (sess == null) continue;
            com.skillforge.server.entity.EvalScenarioEntity ephemeral =
                    sessionScenarioExtractor.extractFromSession(sess);
            if (ephemeral == null) continue;
            ephemeral.setStatus("ephemeral");
            ephemerals.add(evalScenarioRepository.save(ephemeral));
            ids.add(ephemeral.getId());
        }
        return new EphemeralBatch(ephemerals, ids);
    }

    /** Phase 1.4e internal container for ephemeral scenario batch + their IDs. */
    private record EphemeralBatch(
            List<com.skillforge.server.entity.EvalScenarioEntity> scenarios,
            List<String> ephemeralIds) {}

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4c sub-task 2 (Ratify #7-B path (a),
     * 2026-05-16) — create a non-production {@link SkillEntity} from a
     * {@link SkillDraftEntity} for use as the candidate side of an A/B run
     * triggered via {@link #startAbTestFromDraft}. Identity marker = name
     * suffix {@code _candidate_<short-uuid>} (Ratify #7-B (a); SkillEntity
     * has no {@code status} field, so the suffix is the dedupe / cleanup
     * pivot). {@code enabled=false} guarantees the transient row never enters
     * production routing.
     *
     * <p>Cleanup of the loser (delete transient SkillEntity when A/B fails)
     * is Phase 1.4d scope — happens in the SkillAbCompletedEvent listener
     * branch the V4 sub-listener owns; this helper just stages the row.
     *
     * @param draftId UUID of the source SkillDraft
     * @return persisted transient SkillEntity (enabled=false)
     * @throws IllegalArgumentException when the draft is missing
     */
    @Transactional
    public SkillEntity promoteDraftToTransientSkill(String draftId) {
        if (draftId == null || draftId.isBlank()) {
            throw new IllegalArgumentException("draftId is required");
        }
        SkillDraftEntity draft = skillDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Skill draft not found: " + draftId));
        SkillEntity transientSkill = new SkillEntity();
        // Ratify #7-B (a): name suffix carries the transient identity. The
        // 8-char UUID slice gives sufficient uniqueness for the cleanup-by-
        // name-pattern delete path while keeping the column legible.
        String transientUuid = UUID.randomUUID().toString();
        String suffix = "_candidate_" + transientUuid.substring(0, 8);
        transientSkill.setName(draft.getName() + suffix);
        transientSkill.setDescription(draft.getDescription());
        transientSkill.setTriggers(draft.getTriggers());
        transientSkill.setRequiredTools(draft.getRequiredTools());
        transientSkill.setOwnerId(draft.getOwnerId());
        transientSkill.setEnabled(false);
        transientSkill.setSource("attribution_ab_transient");
        transientSkill.setSystem(false);
        transientSkill.setRiskLevel("low");

        // R3 fix (Phase 1.6 dogfood discovery, 2026-05-17): write the LLM-
        // filled SKILL.md body (in draft.promptHint) to disk + set skillPath.
        // Pre-R3 the transient SkillEntity had skillPath=null, so when A/B
        // eval loaded the candidate skill from the registry the file content
        // came back empty → meaningless empty-vs-empty A/B result. Now we
        // mirror the approveDraft pattern: allocate via skillStorageService +
        // render via skillCreatorService (which writes SKILL.md frontmatter +
        // body from draft.promptHint).
        Long ownerForPath = draft.getOwnerId() != null ? draft.getOwnerId() : 0L;
        Path targetDir = (skillsDir != null && !skillsDir.isBlank())
                ? Path.of(skillsDir, String.valueOf(ownerForPath), transientUuid)
                        .toAbsolutePath().normalize()
                : skillStorageService.allocate(SkillSource.EVOLUTION_FORK,
                        AllocationContext.forEvolutionFork(
                                String.valueOf(ownerForPath),
                                String.valueOf(draft.getName()),  // logical parent name
                                transientUuid));
        try {
            skillCreatorService.render(draft, targetDir);
        } catch (IOException e) {
            cleanupDirSafely(targetDir);
            throw new RuntimeException("Failed to render transient SKILL.md to "
                    + targetDir + ": " + e.getMessage(), e);
        }
        transientSkill.setSkillPath(targetDir.toString());

        SkillEntity saved = skillRepository.save(transientSkill);
        log.info("Promoted SkillDraft {} → transient SkillEntity {} (name='{}' skillPath={})",
                draftId, saved.getId(), saved.getName(), saved.getSkillPath());
        return saved;
    }

    /** Backwards-compat overload — defaults forceCreate=false. */
    @Transactional
    public SkillDraftEntity approveDraft(String draftId, Long reviewedBy) {
        return approveDraft(draftId, reviewedBy, false);
    }

    /**
     * Plan r2 §3 + Code Judge r1 B-FE-2 — strict 7-step state machine for approveDraft
     * with forceCreate gating.
     * <p>Step 0 high-sim gate (similarity ≥ {@link #DEDUP_HIGH} && !forceCreate → reject) →
     * Step 1 lock draft → Step 2 generate skillId (UUID) → Step 3+4 render+validate
     * artifact (catch + cleanup + RETHROW) → Step 5 DB save SkillEntity → Step 6 update
     * draft status (in-tx, after save, before afterCommit) → Step 7 register in
     * SkillRegistry afterCommit (failure logged, never rethrown — DB already committed).
     *
     * @param forceCreate when {@code true}, bypass the high-similarity gate (FE Modal.confirm
     *                    flow sets this after the operator explicitly acknowledges the duplicate).
     */
    @Transactional
    public SkillDraftEntity approveDraft(String draftId, Long reviewedBy, boolean forceCreate) {
        // STEP 1: lock draft
        SkillDraftEntity draft = skillDraftRepository.findByIdForUpdate(draftId)
                .orElseThrow(() -> new RuntimeException("Skill draft not found: " + draftId));
        if (!"draft".equals(draft.getStatus())) {
            throw new RuntimeException("Draft is not in 'draft' status: " + draftId);
        }

        // SKILL-DASHBOARD-POLISH-V2 §H — pre-flight exact-name match check.
        // Run BEFORE the high-sim gate so the FE gets a structured 409 with
        // existingSkillId and can offer "Update existing" as a merge action
        // even when similarity scoring (which is fuzzy and language-sensitive)
        // didn't trip the high gate. Only flags currently-enabled rows because
        // V64 partial unique index is partial on enabled=true — disabled rows
        // (forks, archived candidates) should not block a fresh approve.
        Long ownerForCheck = draft.getOwnerId();
        if (ownerForCheck != null && draft.getName() != null && !draft.getName().isBlank()) {
            SkillEntity existing = skillRepository
                    .findFirstByOwnerIdAndNameAndEnabledTrue(ownerForCheck, draft.getName())
                    .orElse(null);
            if (existing != null) {
                throw new SkillNameConflictException(
                        "Skill named '" + draft.getName() + "' already exists for this owner. "
                                + "Use POST /skill-drafts/{id}/merge?targetSkillId="
                                + existing.getId() + " to update it, or rename the draft.",
                        draft.getName(),
                        existing.getId());
            }
        }

        // STEP 0 (gate): high-similarity drafts require explicit forceCreate=true.
        // This is the BE half of the FE Modal.confirm + forceCreate flow (plan §9).
        Double sim = draft.getSimilarity();
        if (!forceCreate && sim != null && sim >= DEDUP_HIGH) {
            throw new HighSimilarityRejectedException(
                    String.format("Draft has high similarity (%.2f) with '%s'. "
                            + "Re-submit with forceCreate=true to override.",
                            sim, draft.getMergeCandidateName()),
                    sim, draft.getMergeCandidateId(), draft.getMergeCandidateName());
        }

        // STEP 2: build entity (don't save yet) + allocate skillId at application layer
        // (avoid coupling artifact dir name to DB IDENTITY which doesn't exist until save).
        Long ownerIdForPath = draft.getOwnerId() != null ? draft.getOwnerId() : 0L;
        String skillId = UUID.randomUUID().toString();
        // P1-D: prefer SkillStorageService for runtime path; fall back to legacy
        // 2-layer skillsDir override only when set by tests (setSkillsDir).
        Path targetDir = (skillsDir != null && !skillsDir.isBlank())
                ? Path.of(skillsDir, String.valueOf(ownerIdForPath), skillId)
                        .toAbsolutePath().normalize()
                : skillStorageService.allocate(SkillSource.DRAFT_APPROVE,
                        AllocationContext.forDraftApprove(
                                String.valueOf(ownerIdForPath), skillId));

        // STEP 3+4: render artifact + validate. Any failure → cleanup + RETHROW (case C).
        SkillDefinition validatedDef;
        try {
            skillCreatorService.render(draft, targetDir);
            validatedDef = skillPackageLoader.loadFromDirectory(targetDir);
        } catch (Exception e) {
            cleanupDirSafely(targetDir);
            throw new SkillApprovalException(
                    "Skill artifact write/validate failed: " + e.getMessage(), e);
        }

        // STEP 5: DB save SkillEntity (with skill_path)
        SkillEntity entity = new SkillEntity();
        entity.setName(draft.getName());
        entity.setDescription(draft.getDescription());
        entity.setTriggers(draft.getTriggers());
        entity.setRequiredTools(draft.getRequiredTools());
        entity.setOwnerId(draft.getOwnerId());
        entity.setSource(SkillSource.DRAFT_APPROVE.wireName());
        entity.setEnabled(true);
        entity.setRiskLevel("low");
        entity.setSystem(false);
        entity.setSkillPath(targetDir.toString());
        SkillEntity savedSkill;
        try {
            savedSkill = skillRepository.save(entity);
        } catch (DataIntegrityViolationException dive) {
            // uq_t_skill_owner_name violation — exact-name collision that similarity-based
            // dedupe (≥ DEDUP_HIGH) didn't catch. Clean up the on-disk artifact dir before
            // tx rollback so we don't leak an orphan (UserSkillLoader scan would still
            // recover it, but proactive cleanup is cheaper). Then map to a structured 409.
            // Note: we don't look up the existing skill's id — PostgreSQL aborts the
            // transaction on a unique violation (see SkillRepository#insertImportedSkillIgnoreConflict
            // javadoc), so any subsequent SELECT in this tx would itself fail. The FE
            // surfaces the conflict by name, which is sufficient.
            cleanupDirSafely(targetDir);
            throw new SkillNameConflictException(
                    "Skill named '" + draft.getName() + "' already exists for this owner.",
                    draft.getName());
        }
        // If save throws other DB failures → tx rollback, afterCommit not invoked,
        // registry stays clean; orphan dir on disk is detected by UserSkillLoader scan (case A).

        // STEP 6: update draft status (in-tx, after save, before afterCommit registration)
        draft.setStatus("approved");
        draft.setSkillId(savedSkill.getId());
        draft.setReviewedAt(Instant.now());
        draft.setReviewedBy(reviewedBy);
        SkillDraftEntity savedDraft = skillDraftRepository.save(draft);

        // STEP 7: register in SkillRegistry afterCommit. Failure here logs ERROR but does
        // NOT rethrow — tx is already committed, throwing would be useless. UserSkillLoader
        // re-registers on next startup (case B recovery path).
        final SkillDefinition defForRegistry = validatedDef;
        final Long persistedSkillId = savedSkill.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        defForRegistry.setSystem(false);
                        skillRegistry.registerSkillDefinition(defForRegistry);
                        log.info("Registered user skill afterCommit: id={}, name={}",
                                persistedSkillId, defForRegistry.getName());
                    } catch (Exception e) {
                        log.error("Registry afterCommit failed for skill id={}, name={} — "
                                + "UserSkillLoader will recover on next restart.",
                                persistedSkillId, defForRegistry.getName(), e);
                        // Do NOT rethrow: tx is committed.
                    }
                }
            });
        } else {
            // No active synchronization (e.g. unit test calling without @Transactional proxy).
            // Fall back to immediate registration; mirrors the afterCommit semantics best-effort.
            try {
                defForRegistry.setSystem(false);
                skillRegistry.registerSkillDefinition(defForRegistry);
            } catch (Exception e) {
                log.error("Registry registration (no-tx fallback) failed for skill id={}: {}",
                        persistedSkillId, e.getMessage(), e);
            }
        }

        return savedDraft;
    }

    /**
     * SKILL-DASHBOARD-POLISH-V2 §H — merge a draft into an existing user skill.
     *
     * <p>Steps:
     * <ol>
     *   <li>Lock draft (pessimistic write) + validate {@code status='draft'}.</li>
     *   <li>Load target skill; ownership check ensures draft.ownerId == target.ownerId.</li>
     *   <li>Render new SKILL.md to target.skillPath (overwrites existing). The target
     *       row's {@code skillPath} must be set; we don't allocate a new path because
     *       merge implies "keep the same skill identity, replace the artifact".</li>
     *   <li>Update target skill's description / triggers / promptHint / requiredTools
     *       from draft. <b>Do NOT touch enabled / usageCount / successCount / failureCount /
     *       version / parentSkillId / semver</b> — those are runtime state, and changing
     *       enabled would re-trigger the V64 partial unique index check which is exactly
     *       what merge avoids vs forceCreate.</li>
     *   <li>Update draft: {@code status='approved'}, {@code skillId=targetSkillId},
     *       {@code reviewedAt=now}, {@code reviewedBy}.</li>
     *   <li>Re-register the SkillDefinition afterCommit so the SkillRegistry picks
     *       up the new prompt body (best effort — UserSkillLoader recovers on restart).</li>
     * </ol>
     *
     * @param draftId        UUID of the draft row
     * @param targetSkillId  id of an existing user-owned (non-system) skill to update
     * @param reviewedBy     user id of the operator
     * @return the updated draft (status now "approved")
     */
    @Transactional
    public SkillDraftEntity mergeIntoExistingSkill(String draftId, Long targetSkillId, Long reviewedBy) {
        // STEP 1 — lock draft + validate
        SkillDraftEntity draft = skillDraftRepository.findByIdForUpdate(draftId)
                .orElseThrow(() -> new RuntimeException("Skill draft not found: " + draftId));
        if (!"draft".equals(draft.getStatus())) {
            throw new RuntimeException("Draft is not in 'draft' status: " + draftId);
        }

        // STEP 2 — load target skill + ownership check
        SkillEntity target = skillRepository.findById(targetSkillId)
                .orElseThrow(() -> new RuntimeException("Target skill not found: " + targetSkillId));
        if (target.isSystem()) {
            throw new RuntimeException("Cannot merge into a system skill: " + targetSkillId);
        }
        // ownership: draft.ownerId must match target.ownerId; system skills (ownerId==null)
        // are blocked above, and a draft from owner X must not write into owner Y's skill.
        if (target.getOwnerId() == null
                || draft.getOwnerId() == null
                || !target.getOwnerId().equals(draft.getOwnerId())) {
            throw new RuntimeException("Ownership mismatch: draft owner " + draft.getOwnerId()
                    + " cannot merge into skill " + targetSkillId
                    + " (owner " + target.getOwnerId() + ")");
        }

        // STEP 3 — render new SKILL.md to target.skillPath (overwrite existing).
        String skillPath = target.getSkillPath();
        if (skillPath == null || skillPath.isBlank()) {
            throw new RuntimeException("Target skill has no skillPath; cannot render artifact: "
                    + targetSkillId);
        }
        Path targetDir = Path.of(skillPath).toAbsolutePath().normalize();
        SkillDefinition validatedDef;
        try {
            skillCreatorService.render(draft, targetDir);
            validatedDef = skillPackageLoader.loadFromDirectory(targetDir);
        } catch (Exception e) {
            // Don't cleanup — we overwrote an existing artifact in place; deleting the
            // dir would leave the target in worse shape than before. SkillCatalogReconciler
            // will re-mark artifactStatus on next scan.
            throw new SkillApprovalException(
                    "Skill artifact write/validate failed during merge: " + e.getMessage(), e);
        }

        // STEP 4 — update target skill content fields (NOT runtime state).
        // CRITICAL: do NOT touch enabled. Target is presumably already enabled (V64 partial
        // unique only fires on enabled=true rows; flipping enabled here could collide with
        // forks). Leaving runtime counters / parent / semver alone preserves audit trail.
        target.setDescription(draft.getDescription());
        target.setTriggers(draft.getTriggers());
        target.setRequiredTools(draft.getRequiredTools());
        SkillEntity savedTarget = skillRepository.save(target);

        // STEP 5 — flip draft status
        draft.setStatus("approved");
        draft.setSkillId(savedTarget.getId());
        draft.setReviewedAt(Instant.now());
        draft.setReviewedBy(reviewedBy);
        SkillDraftEntity savedDraft = skillDraftRepository.save(draft);

        // STEP 6 — re-register afterCommit so SkillRegistry serves the new body.
        final SkillDefinition defForRegistry = validatedDef;
        final Long persistedSkillId = savedTarget.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        defForRegistry.setSystem(false);
                        skillRegistry.registerSkillDefinition(defForRegistry);
                        log.info("Re-registered skill afterCommit (merge): id={}, name={}",
                                persistedSkillId, defForRegistry.getName());
                    } catch (Exception e) {
                        log.error("Registry afterCommit failed after merge for skill id={}, name={} — "
                                + "UserSkillLoader will recover on next restart.",
                                persistedSkillId, defForRegistry.getName(), e);
                    }
                }
            });
        } else {
            try {
                defForRegistry.setSystem(false);
                skillRegistry.registerSkillDefinition(defForRegistry);
            } catch (Exception e) {
                log.error("Registry registration (no-tx fallback) failed after merge for skill id={}: {}",
                        persistedSkillId, e.getMessage(), e);
            }
        }

        return savedDraft;
    }

    /**
     * SKILL-DASHBOARD-POLISH-V2 §H — Rename branch of the merge UX modal. Used
     * when the operator picks "Rename and create new" after a 409 NAME_CONFLICT.
     * Reset similarity / merge candidate caches so the next approve call rescans
     * against the new name (would otherwise carry stale flags from the original
     * extract).
     */
    @Transactional
    public SkillDraftEntity renameDraft(String draftId, String newName, Long reviewedBy) {
        SkillDraftEntity draft = skillDraftRepository.findByIdForUpdate(draftId)
                .orElseThrow(() -> new RuntimeException("Skill draft not found: " + draftId));
        if (!"draft".equals(draft.getStatus())) {
            throw new RuntimeException("Draft is not in 'draft' status: " + draftId);
        }
        if (newName == null || newName.isBlank()) {
            throw new RuntimeException("newName must not be blank");
        }
        draft.setName(newName.trim());
        // Clear stale dedupe metadata so approveDraft rescans the new name.
        draft.setSimilarity(null);
        draft.setMergeCandidateId(null);
        draft.setMergeCandidateName(null);
        return skillDraftRepository.save(draft);
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
        return getDrafts(ownerId, null);
    }

    /**
     * FLYWHEEL-VISUAL-STATUS Phase 2: source-filtered overload. When
     * {@code source} is null/blank returns the full owner-scoped list (legacy
     * behavior); when set, filters by {@link SkillDraftEntity#source} exact
     * match (V91 free-form provenance string).
     */
    @Transactional(readOnly = true)
    public List<SkillDraftEntity> getDrafts(Long ownerId, String source) {
        if (source == null || source.isBlank()) {
            return skillDraftRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        }
        return skillDraftRepository.findByOwnerIdAndSourceOrderByCreatedAtDesc(ownerId, source);
    }

    public boolean hasPendingDrafts(Long ownerId) {
        return skillDraftRepository.countByOwnerIdAndStatus(ownerId, "draft") > 0;
    }

    public long countPendingDraftsForAgent(Long ownerId, Long agentId) {
        return skillDraftRepository.countByOwnerIdAndStatusForAgent(ownerId, "draft", agentId);
    }

    public boolean hasPendingDraftsForAgent(Long ownerId, Long agentId) {
        return countPendingDraftsForAgent(ownerId, agentId) > 0;
    }

    /** Legacy drafts with null sourceSessionId — block all extractions until cleared. */
    public long countUnattachedPendingDrafts(Long ownerId) {
        return skillDraftRepository.countByOwnerIdAndStatusAndSourceSessionIdIsNull(ownerId, "draft");
    }

    /**
     * Plan r2 §9 — similarity scoring. Combined metric:
     *   0.5 * jaccard(name tokens) + 0.3 * jaccard(triggers∪tools) + 0.2 * normalized Levenshtein(description)
     */
    DedupeMatch scoreSimilarity(SkillDraftEntity candidate,
                                List<SkillEntity> existingSkills,
                                List<SkillDraftEntity> existingDrafts) {
        DedupeMatch best = null;
        for (SkillEntity s : existingSkills) {
            double sim = combinedSimilarity(
                    candidate.getName(), s.getName(),
                    Arrays.asList(safeSplit(candidate.getTriggers()), safeSplit(candidate.getRequiredTools())),
                    Arrays.asList(safeSplit(s.getTriggers()), safeSplit(s.getRequiredTools())),
                    candidate.getDescription(), s.getDescription());
            if (best == null || sim > best.similarity) {
                best = new DedupeMatch(sim, s.getName(), "skill:" + s.getId());
            }
        }
        for (SkillDraftEntity d : existingDrafts) {
            double sim = combinedSimilarity(
                    candidate.getName(), d.getName(),
                    Arrays.asList(safeSplit(candidate.getTriggers()), safeSplit(candidate.getRequiredTools())),
                    Arrays.asList(safeSplit(d.getTriggers()), safeSplit(d.getRequiredTools())),
                    candidate.getDescription(), d.getDescription());
            if (best == null || sim > best.similarity) {
                best = new DedupeMatch(sim, d.getName(), "draft:" + d.getId());
            }
        }
        return best;
    }

    private static double combinedSimilarity(String nameA, String nameB,
                                             List<List<String>> setsA,
                                             List<List<String>> setsB,
                                             String descA, String descB) {
        double nameSim = jaccard(tokensOf(nameA), tokensOf(nameB));
        Set<String> setA = flatten(setsA);
        Set<String> setB = flatten(setsB);
        double setSim = jaccard(setA, setB);
        double descSim = 1.0 - normalizedLevenshtein(safe(descA), safe(descB));
        return 0.5 * nameSim + 0.3 * setSim + 0.2 * descSim;
    }

    private static Set<String> flatten(List<List<String>> sets) {
        Set<String> out = new HashSet<>();
        for (List<String> s : sets) {
            if (s != null) out.addAll(s);
        }
        return out;
    }

    private static List<String> safeSplit(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.stream(csv.split("[,\\s]+"))
                .filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .toList();
    }

    private static Set<String> tokensOf(String s) {
        if (s == null || s.isBlank()) return Collections.emptySet();
        // Split on transitions / non-alphanumeric to surface PascalCase tokens.
        String spaced = s.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .toLowerCase();
        Set<String> out = new HashSet<>();
        for (String t : spaced.split("\\s+")) {
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    /** @return Levenshtein distance / max(len(a), len(b)); 0 = identical, 1 = totally different. */
    private static double normalizedLevenshtein(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 0.0;
        return (double) levenshtein(a, b) / max;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeAppend(String base, String suffix) {
        return (base == null ? "" : base) + suffix;
    }

    /**
     * Pull the numeric id out of {@code "skill:99"} / {@code "draft:abc-..."} refs.
     * Drafts have UUID ids that don't fit Long; in that case return null and the FE
     * just shows the name. Skill rows have BIGINT ids and fit cleanly.
     */
    private static Long extractCandidateId(String matchedRef) {
        if (matchedRef == null) return null;
        int colon = matchedRef.indexOf(':');
        if (colon < 0 || colon == matchedRef.length() - 1) return null;
        String idPart = matchedRef.substring(colon + 1);
        try {
            return Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            return null;   // draft UUIDs deliberately fall through.
        }
    }

    private void cleanupDirSafely(Path dir) {
        try {
            if (dir != null && Files.isDirectory(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {
            log.warn("Best-effort cleanup of orphan dir failed: {}", dir);
        }
    }

    /** Visible for tests (lets unit tests override default ./data/skills). */
    public void setSkillsDir(String skillsDir) {
        this.skillsDir = skillsDir;
    }

    /** Visible for tests + dedupe internals. */
    static final class DedupeMatch {
        final double similarity;
        final String matchedName;
        final String matchedRef;
        DedupeMatch(double similarity, String matchedName, String matchedRef) {
            this.similarity = similarity;
            this.matchedName = matchedName;
            this.matchedRef = matchedRef;
        }
    }
}
