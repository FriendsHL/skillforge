package com.skillforge.server.tool.memorysynth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.MemoryProposalEntity;
import com.skillforge.server.repository.MemoryProposalRepository;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MEMORY-LLM-SYNTHESIS dogfood (Tool 4 / D19 batch): create one or more memory-consolidation
 * proposals atomically into {@code t_memory_proposal}.
 *
 * <p>Strong validation (D14 lightweight gate):
 * <ul>
 *   <li>type ∈ {dedup, reflection, optimize, contradiction} — any other type (notably
 *       {@code delete}) is rejected without writing</li>
 *   <li>every {@code sourceMemoryIds} must reference an existing row in {@code t_memory}
 *       belonging to the same user; absent IDs reject the whole proposal</li>
 *   <li>dedup type: {@code sourceMemoryIds.size} ∈ [2, 5] (mass-delete guard) and
 *       {@code winnerMemoryId} must be in {@code sourceMemoryIds}</li>
 *   <li>contradiction type: {@code sourceMemoryIds.size} ≥ 2 (winner picked at approve time)</li>
 *   <li>reflection type: {@code sourceMemoryIds.size} ≥ 2 and {@code suggestedContent} required</li>
 *   <li>optimize type: {@code sourceMemoryIds.size} == 1 and {@code suggestedContent} required</li>
 *   <li>reasoning truncated to 200 chars (UTF-16 surrogate-safe)</li>
 *   <li>suggestedTitle truncated to 256 chars; suggestedContent truncated to 4000 chars</li>
 *   <li>cross-run dedup: same (type, sorted sourceMemoryIds) already in proposed/approved
 *       state → skipped silently (returned in {@code skippedDuplicates} counter)</li>
 * </ul>
 *
 * <p>Writes only to {@code t_memory_proposal} — never touches {@code t_memory} (approve
 * path via {@code MemoryProposalService.approve} is the only memory writer).
 *
 * <p>NOTE on transactions: {@code MemoryProposalRepository.saveAll} is wrapped by Spring's
 * default {@code SimpleJpaRepository} {@code @Transactional}, so the entity list commits in
 * one tx. Validation queries (existence + cross-run dedup) run before save inside the same
 * Hibernate session opened by saveAll's outer tx (OSIV / aux propagation).
 */
public class CreateMemoryProposalTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateMemoryProposalTool.class);

    private static final int REASONING_MAX_LEN = 200;
    private static final int TITLE_MAX_LEN = 256;
    private static final int CONTENT_MAX_LEN = 4000;
    private static final int DEDUP_MAX_SOURCE_IDS = 5;
    private static final int MAX_PROPOSALS_PER_BATCH = 50;

    private static final Set<String> VALID_TYPES = Set.of(
            MemoryProposalEntity.TYPE_DEDUP,
            MemoryProposalEntity.TYPE_REFLECTION,
            MemoryProposalEntity.TYPE_OPTIMIZE,
            MemoryProposalEntity.TYPE_CONTRADICTION);

    private static final Set<String> VALID_IMPORTANCE = Set.of("high", "medium", "low");

    private final MemoryProposalRepository proposalRepository;
    private final MemoryRepository memoryRepository;
    private final ObjectMapper objectMapper;

    public CreateMemoryProposalTool(MemoryProposalRepository proposalRepository,
                                    MemoryRepository memoryRepository,
                                    ObjectMapper objectMapper) {
        this.proposalRepository = proposalRepository;
        this.memoryRepository = memoryRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "CreateMemoryProposal";
    }

    @Override
    public String getDescription() {
        return "Create memory consolidation proposals in a single batch (writes to "
                + "t_memory_proposal only — never directly to t_memory). Supported types: "
                + "dedup / reflection / optimize / contradiction. NEVER use type=delete; "
                + "the rule-based system already handles age-based archival. Each proposal "
                + "must reference only memory IDs returned by a prior ListMemoryCandidates "
                + "call in this same session. Strong validation: dedup sourceMemoryIds size "
                + "is capped at " + DEDUP_MAX_SOURCE_IDS + " (mass-delete guard); reasoning "
                + "is truncated to " + REASONING_MAX_LEN + " chars; duplicate (type, "
                + "sourceMemoryIds) sets already in proposed/approved state are skipped.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> proposalProps = new LinkedHashMap<>();
        proposalProps.put("type", Map.of(
                "type", "string",
                "enum", List.of(MemoryProposalEntity.TYPE_DEDUP,
                        MemoryProposalEntity.TYPE_REFLECTION,
                        MemoryProposalEntity.TYPE_OPTIMIZE,
                        MemoryProposalEntity.TYPE_CONTRADICTION),
                "description", "Required. One of dedup/reflection/optimize/contradiction."
        ));
        proposalProps.put("sourceMemoryIds", Map.of(
                "type", "array",
                "items", Map.of("type", "integer"),
                "description", "Required. Memory IDs cited as sources. dedup: 2..5 (mass-delete "
                        + "guard); reflection: >=2 unless backed by session evidence and top-level "
                        + "userId; optimize: exactly 1; contradiction: >=2."
        ));
        proposalProps.put("winnerMemoryId", Map.of(
                "type", "integer",
                "description", "dedup type required (must be in sourceMemoryIds); contradiction "
                        + "type optional (winner picked at approve time)."
        ));
        proposalProps.put("suggestedTitle", Map.of(
                "type", "string",
                "description", "Suggested memory title (auto-truncated to " + TITLE_MAX_LEN
                        + " chars). reflection/optimize types only."
        ));
        proposalProps.put("suggestedContent", Map.of(
                "type", "string",
                "description", "Suggested memory content (auto-truncated to " + CONTENT_MAX_LEN
                        + " chars). REQUIRED for reflection and optimize."
        ));
        proposalProps.put("suggestedImportance", Map.of(
                "type", "string",
                "enum", List.of("high", "medium", "low"),
                "description", "reflection type only. Defaults to medium when omitted."
        ));
        proposalProps.put("reasoning", Map.of(
                "type", "string",
                "description", "Why this proposal makes sense. Auto-truncated to "
                        + REASONING_MAX_LEN + " chars (UTF-16 surrogate-safe)."
        ));
        proposalProps.put("evidence", Map.of(
                "type", "array",
                "items", Map.of("type", "object"),
                "description", "Optional evidence objects. For transcript-backed reflections include "
                        + "source='session', sessionId, seqNo, and quote."
        ));

        Map<String, Object> proposalSchema = new LinkedHashMap<>();
        proposalSchema.put("type", "object");
        proposalSchema.put("properties", proposalProps);
        proposalSchema.put("required", List.of("type", "sourceMemoryIds"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("synthesisRunId", Map.of(
                "type", "string",
                "description", "Required. Caller-generated run identifier (e.g. \"synth-<uuid>\"). "
                        + "Stamped on every emitted proposal so admins can filter and audit per run."
        ));
        properties.put("userId", Map.of(
                "type", "integer",
                "description", "Optional for memory-backed proposals; required when a reflection has "
                        + "no sourceMemoryIds and is backed only by session evidence."
        ));
        properties.put("proposals", Map.of(
                "type", "array",
                "items", proposalSchema,
                "description", "Required. Batch of proposals (at most " + MAX_PROPOSALS_PER_BATCH + ")."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("synthesisRunId", "proposals"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.validationError("input is required");
            }
            Object runIdObj = input.get("synthesisRunId");
            String synthesisRunId = runIdObj == null ? null : runIdObj.toString().trim();
            if (synthesisRunId == null || synthesisRunId.isBlank()) {
                return SkillResult.validationError("synthesisRunId is required");
            }
            if (synthesisRunId.length() > 64) {
                return SkillResult.validationError("synthesisRunId must be <= 64 chars");
            }

            Object proposalsObj = input.get("proposals");
            if (!(proposalsObj instanceof List<?> proposalList) || proposalList.isEmpty()) {
                return SkillResult.validationError("proposals must be a non-empty array");
            }
            if (proposalList.size() > MAX_PROPOSALS_PER_BATCH) {
                return SkillResult.validationError("proposals size " + proposalList.size()
                        + " exceeds max " + MAX_PROPOSALS_PER_BATCH);
            }

            Long contextUserId = context == null ? null : context.getUserId();
            Long explicitUserId = SkillInputUtils.toLong(input.get("userId"));

            List<RejectionRecord> rejections = new ArrayList<>();
            List<MemoryProposalEntity> toSave = new ArrayList<>();
            int skippedDuplicates = 0;

            for (int i = 0; i < proposalList.size(); i++) {
                Object raw = proposalList.get(i);
                if (!(raw instanceof Map<?, ?> rawMap)) {
                    rejections.add(new RejectionRecord(i, "proposal must be an object"));
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) rawMap;

                ProposalDraft draft = parseProposal(i, p, contextUserId, explicitUserId);
                if (!draft.ok()) {
                    rejections.add(new RejectionRecord(i, draft.error()));
                    continue;
                }

                // Cross-run dedup against existing proposed/approved rows.
                if (alreadyHasEquivalentProposal(draft.userId(), draft.sourceMemoryIds(), draft.type())) {
                    skippedDuplicates++;
                    continue;
                }

                MemoryProposalEntity entity = buildEntity(draft, synthesisRunId);
                toSave.add(entity);
            }

            List<Long> createdIds = new ArrayList<>();
            if (!toSave.isEmpty()) {
                List<MemoryProposalEntity> saved = proposalRepository.saveAll(toSave);
                for (MemoryProposalEntity e : saved) {
                    if (e.getId() != null) createdIds.add(e.getId());
                }
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("createdProposalIds", createdIds);
            payload.put("createdCount", createdIds.size());
            payload.put("skippedDuplicates", skippedDuplicates);
            payload.put("rejectedByValidation", rejections.size());
            payload.put("totalRequested", proposalList.size());
            payload.put("synthesisRunId", synthesisRunId);
            if (!rejections.isEmpty()) {
                List<Map<String, Object>> rej = new ArrayList<>();
                for (RejectionRecord r : rejections) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("index", r.index());
                    entry.put("error", r.error());
                    rej.add(entry);
                }
                payload.put("rejections", rej);
            }
            log.info("CreateMemoryProposalTool: runId={} created={} skipped={} rejected={} total={}",
                    synthesisRunId, createdIds.size(), skippedDuplicates,
                    rejections.size(), proposalList.size());
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("CreateMemoryProposalTool execute failed", e);
            return SkillResult.error("CreateMemoryProposal error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────────────────────────────────────────

    private ProposalDraft parseProposal(int idx,
                                        Map<String, Object> p,
                                        Long contextUserId,
                                        Long explicitUserId) {
        String type = asString(p.get("type"));
        if (type == null || type.isBlank()) {
            return ProposalDraft.fail("type is required");
        }
        type = type.toLowerCase();
        if (!VALID_TYPES.contains(type)) {
            return ProposalDraft.fail("type=" + type + " not in " + VALID_TYPES
                    + " (delete is forbidden — rule-based archival handles age-out)");
        }

        Object evidenceObj = p.get("evidence");
        String evidenceJson = serializeEvidence(evidenceObj);
        boolean hasTranscriptEvidence = hasSessionEvidence(evidenceObj);

        Object sourceMemoryIdsObj = p.get("sourceMemoryIds");
        if (!isArrayLike(sourceMemoryIdsObj)) {
            return ProposalDraft.fail("sourceMemoryIds must be an array");
        }
        List<Long> sourceIds = parseLongList(sourceMemoryIdsObj);
        if (sourceIds.isEmpty()) {
            if (!MemoryProposalEntity.TYPE_REFLECTION.equals(type)) {
                return ProposalDraft.fail("sourceMemoryIds may be empty only for transcript-backed reflection proposals");
            }
            if (!hasTranscriptEvidence) {
                return ProposalDraft.fail("reflection with empty sourceMemoryIds requires session evidence");
            }
        }
        // De-dup the input array itself (LLM occasionally repeats).
        Set<Long> dedup = new LinkedHashSet<>(sourceIds);
        sourceIds = new ArrayList<>(dedup);

        switch (type) {
            case MemoryProposalEntity.TYPE_DEDUP -> {
                if (sourceIds.size() < 2) {
                    return ProposalDraft.fail("dedup requires >= 2 sourceMemoryIds, got " + sourceIds.size());
                }
                if (sourceIds.size() > DEDUP_MAX_SOURCE_IDS) {
                    return ProposalDraft.fail("dedup sourceMemoryIds size " + sourceIds.size()
                            + " > " + DEDUP_MAX_SOURCE_IDS + " (mass-delete guard)");
                }
            }
            case MemoryProposalEntity.TYPE_CONTRADICTION,
                 MemoryProposalEntity.TYPE_REFLECTION -> {
                if (!sourceIds.isEmpty() && sourceIds.size() < 2) {
                    return ProposalDraft.fail(type + " requires >= 2 sourceMemoryIds, got " + sourceIds.size());
                }
            }
            case MemoryProposalEntity.TYPE_OPTIMIZE -> {
                if (sourceIds.size() != 1) {
                    return ProposalDraft.fail("optimize requires exactly 1 sourceMemoryId, got " + sourceIds.size());
                }
            }
            default -> { /* unreachable */ }
        }

        Long resolvedUserId = null;
        if (sourceIds.isEmpty()) {
            if (explicitUserId == null || explicitUserId <= 0) {
                return ProposalDraft.fail("userId is required for transcript-backed reflection proposals");
            }
            resolvedUserId = explicitUserId;
        } else {
            // Existence + same-user check.
            List<MemoryEntity> rows = memoryRepository.findAllById(sourceIds);
            if (rows.size() != sourceIds.size()) {
                Set<Long> found = new HashSet<>();
                for (MemoryEntity m : rows) found.add(m.getId());
                List<Long> missing = new ArrayList<>();
                for (Long id : sourceIds) if (!found.contains(id)) missing.add(id);
                return ProposalDraft.fail("sourceMemoryIds reference missing memory rows: " + missing);
            }
            for (MemoryEntity m : rows) {
                if (m.getUserId() == null) {
                    return ProposalDraft.fail("sourceMemoryId " + m.getId() + " has null userId — refusing");
                }
                if (resolvedUserId == null) {
                    resolvedUserId = m.getUserId();
                } else if (!resolvedUserId.equals(m.getUserId())) {
                    return ProposalDraft.fail("sourceMemoryIds span multiple users (" + resolvedUserId
                            + " vs " + m.getUserId() + ") — refusing");
                }
            }
        }
        // Gap-2 fix: SYSTEM context (userId=0, dogfood fan-out from memory-curator
        // sub-session) is exempt from the cross-user gate — the sub processes one
        // target user's memories on behalf of SYSTEM. The same-user-within-sourceIds
        // check above (lines 321-326) still prevents a SYSTEM call from mixing two
        // users' memories into a single proposal.
        boolean isSystemContext = contextUserId != null && contextUserId == 0L;
        if (!isSystemContext
                && contextUserId != null && resolvedUserId != null
                && !contextUserId.equals(resolvedUserId)) {
            return ProposalDraft.fail("context userId=" + contextUserId
                    + " does not match sourceMemoryIds userId=" + resolvedUserId + " — refusing cross-user proposal");
        }

        // Type-specific extras.
        Long winnerMemoryId = SkillInputUtils.toLong(p.get("winnerMemoryId"));
        if (MemoryProposalEntity.TYPE_DEDUP.equals(type)) {
            if (winnerMemoryId == null) {
                return ProposalDraft.fail("dedup requires winnerMemoryId");
            }
            if (!sourceIds.contains(winnerMemoryId)) {
                return ProposalDraft.fail("dedup winnerMemoryId " + winnerMemoryId
                        + " not in sourceMemoryIds " + sourceIds);
            }
        } else if (winnerMemoryId != null
                && MemoryProposalEntity.TYPE_CONTRADICTION.equals(type)
                && !sourceIds.contains(winnerMemoryId)) {
            return ProposalDraft.fail("contradiction winnerMemoryId " + winnerMemoryId
                    + " not in sourceMemoryIds " + sourceIds);
        } else if (winnerMemoryId != null
                && !MemoryProposalEntity.TYPE_DEDUP.equals(type)
                && !MemoryProposalEntity.TYPE_CONTRADICTION.equals(type)) {
            // reflection / optimize must not carry winnerMemoryId — silently drop.
            winnerMemoryId = null;
        }

        String suggestedTitle = truncate(asString(p.get("suggestedTitle")), TITLE_MAX_LEN);
        String suggestedContent = truncate(asString(p.get("suggestedContent")), CONTENT_MAX_LEN);
        String suggestedImportance = normalizeImportance(asString(p.get("suggestedImportance")));
        String reasoning = truncate(asString(p.get("reasoning")), REASONING_MAX_LEN);

        if ((MemoryProposalEntity.TYPE_REFLECTION.equals(type)
                || MemoryProposalEntity.TYPE_OPTIMIZE.equals(type))
                && (suggestedContent == null || suggestedContent.isBlank())) {
            return ProposalDraft.fail(type + " requires non-blank suggestedContent");
        }

        return new ProposalDraft(true, null, type, sourceIds, winnerMemoryId,
                suggestedTitle, suggestedContent, suggestedImportance, reasoning, evidenceJson, resolvedUserId);
    }

    private boolean alreadyHasEquivalentProposal(Long userId, List<Long> sourceIds, String type) {
        if (userId == null || sourceIds.isEmpty()) return false;
        try {
            String firstIdJsonArray = "[" + sourceIds.get(0) + "]";
            List<MemoryProposalEntity> candidates =
                    proposalRepository.findReferencingMemoryId(userId, firstIdJsonArray);
            for (MemoryProposalEntity c : candidates) {
                if (!type.equals(c.getProposalType())) continue;
                if (!MemoryProposalEntity.STATUS_PROPOSED.equals(c.getStatus())
                        && !MemoryProposalEntity.STATUS_APPROVED.equals(c.getStatus())) {
                    continue;
                }
                List<Long> existing = parseSourceIds(c.getSourceMemoryIds());
                if (existing.size() == sourceIds.size()
                        && existing.containsAll(sourceIds)
                        && sourceIds.containsAll(existing)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Best-effort. Native @> jsonb query is unavailable on the embedded H2 used by
            // unit tests; treat as "no duplicate" rather than blocking the whole batch.
            log.debug("CreateMemoryProposalTool cross-run dedup check failed (ignored): {}", e.getMessage());
        }
        return false;
    }

    private MemoryProposalEntity buildEntity(ProposalDraft d, String synthesisRunId) {
        MemoryProposalEntity p = new MemoryProposalEntity();
        p.setUserId(d.userId());
        p.setSynthesisRunId(synthesisRunId);
        p.setProposalType(d.type());
        try {
            p.setSourceMemoryIds(objectMapper.writeValueAsString(d.sourceMemoryIds()));
        } catch (Exception e) {
            // Fallback to manual array literal — list contains Long primitives only.
            p.setSourceMemoryIds(d.sourceMemoryIds().toString());
        }
        p.setWinnerMemoryId(d.winnerMemoryId());
        p.setSuggestedTitle(d.suggestedTitle());
        p.setSuggestedContent(d.suggestedContent());
        p.setSuggestedImportance(d.suggestedImportance());
        p.setReasoning(d.reasoning());
        p.setEvidenceJson(d.evidenceJson());
        p.setStatus(MemoryProposalEntity.STATUS_PROPOSED);
        return p;
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────

    private List<Long> parseSourceIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Long> ids = objectMapper.readValue(json, new TypeReference<List<Long>>() {});
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String serializeEvidence(Object evidenceObj) {
        if (!(evidenceObj instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.debug("CreateMemoryProposalTool failed to serialize evidence (ignored): {}", e.getMessage());
            return null;
        }
    }

    private static boolean hasSessionEvidence(Object evidenceObj) {
        if (!(evidenceObj instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> evidence)) {
                continue;
            }
            String source = asString(evidence.get("source"));
            Object sessionId = evidence.get("sessionId");
            String quote = asString(evidence.get("quote"));
            if ("session".equals(source) && sessionId != null && quote != null && !quote.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static List<Long> parseLongList(Object raw) {
        if (raw == null) return List.of();
        Iterable<?> iter;
        if (raw instanceof Iterable<?> it) {
            iter = it;
        } else if (raw.getClass().isArray()) {
            iter = Arrays.asList((Object[]) raw);
        } else {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (Object item : iter) {
            Long v = SkillInputUtils.toLong(item);
            if (v != null) out.add(v);
        }
        return out;
    }

    private static boolean isArrayLike(Object raw) {
        return raw instanceof Iterable<?> || (raw != null && raw.getClass().isArray());
    }

    private static String asString(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        return v.toString();
    }

    private static String normalizeImportance(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.toLowerCase();
        return VALID_IMPORTANCE.contains(lower) ? lower : null;
    }

    static String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (maxLen <= 0) return "";
        if (s.length() <= maxLen) return s;
        int end = maxLen;
        // UTF-16 surrogate-safe: don't slice a surrogate pair in half.
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        if (end <= 0) return "";
        return s.substring(0, end);
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Records
    // ─────────────────────────────────────────────────────────────────────────────────

    private record ProposalDraft(boolean ok,
                                  String error,
                                  String type,
                                  List<Long> sourceMemoryIds,
                                  Long winnerMemoryId,
                                  String suggestedTitle,
                                  String suggestedContent,
                                  String suggestedImportance,
                                  String reasoning,
                                  String evidenceJson,
                                  Long userId) {
        static ProposalDraft fail(String error) {
            return new ProposalDraft(false, error, null, List.of(), null,
                    null, null, null, null, null, null);
        }
    }

    private record RejectionRecord(int index, String error) {
    }
}
