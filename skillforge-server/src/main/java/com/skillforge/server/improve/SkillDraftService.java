package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
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
                             SkillStorageService skillStorageService) {
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
                userWebSocketHandler.broadcast(userId, Map.of(
                        "type", "skill_draft_extracted",
                        "count", toSave.size(),
                        "highSimilarityFlagged", high,
                        "mergeFlagged", merge
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
        SkillEntity savedSkill = skillRepository.save(entity);
        // If this throws (UNIQUE conflict / DB failure) → tx rollback, afterCommit not invoked,
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
