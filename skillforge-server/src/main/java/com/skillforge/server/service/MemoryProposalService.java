package com.skillforge.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.MemoryProposalEntity;
import com.skillforge.server.repository.MemoryProposalRepository;
import com.skillforge.server.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MEMORY-LLM-SYNTHESIS (V68): apply / reject / edit / revert / auto-archive on
 * {@link MemoryProposalEntity}.
 *
 * <p>The approve path is the only way LLM-proposed memory edits reach {@code t_memory}.
 * Wrapped in {@code @Transactional} + pessimistic-write lock on both the proposal row
 * and source memory rows so concurrent approves serialize at the DB level (B-4 fix).
 */
@Service
public class MemoryProposalService {

    private static final Logger log = LoggerFactory.getLogger(MemoryProposalService.class);

    private static final int DEDUP_MAX_SOURCE_IDS = 5;

    private final MemoryProposalRepository proposalRepository;
    private final MemoryRepository memoryRepository;
    private final ObjectMapper objectMapper;

    public MemoryProposalService(MemoryProposalRepository proposalRepository,
                                 MemoryRepository memoryRepository,
                                 ObjectMapper objectMapper) {
        this.proposalRepository = proposalRepository;
        this.memoryRepository = memoryRepository;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MemoryProposalEntity> list(Long userId, String status, int limit) {
        int effective = Math.max(1, Math.min(limit, 200));
        if (userId == null) {
            return proposalRepository.findByStatusOrderByCreatedAtDesc(
                    status == null ? MemoryProposalEntity.STATUS_PROPOSED : status,
                    PageRequest.of(0, effective));
        }
        return proposalRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                userId,
                status == null ? MemoryProposalEntity.STATUS_PROPOSED : status,
                PageRequest.of(0, effective));
    }

    @Transactional
    public ApproveResult approve(Long proposalId, Long reviewerUserId) {
        MemoryProposalEntity p = proposalRepository.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
        if (!MemoryProposalEntity.STATUS_PROPOSED.equals(p.getStatus())) {
            throw new IllegalStateException(
                    "proposal not in proposed state: id=" + proposalId + " status=" + p.getStatus());
        }

        List<Long> sourceIds = parseSourceIds(p.getSourceMemoryIds());
        if (sourceIds.isEmpty()) {
            if (!MemoryProposalEntity.TYPE_REFLECTION.equals(p.getProposalType())) {
                throw new IllegalStateException("proposal has no sourceMemoryIds: id=" + proposalId);
            }
        }

        // B-4 fix: pessimistic-write lock on source memories.
        List<MemoryEntity> sources = List.of();
        if (!sourceIds.isEmpty()) {
            sources = memoryRepository.findAllByIdForUpdate(sourceIds);
            if (sources.size() != sourceIds.size()) {
                // Source memory deleted between propose-time and approve-time → stale.
                return staleAndPersist(p, "source_memory_missing");
            }

            // W-3 fix: stale check by proposal_type.
            StaleCheckResult stale = checkStaleByType(p.getProposalType(), sources);
            if (stale.isStale()) {
                return staleAndPersist(p, stale.reason());
            }
        }

        // B-3 second line of defense at approve gate.
        if (MemoryProposalEntity.TYPE_DEDUP.equals(p.getProposalType())
                && sources.size() > DEDUP_MAX_SOURCE_IDS) {
            throw new IllegalStateException(
                    "dedup proposal source size " + sources.size() + " > " + DEDUP_MAX_SOURCE_IDS
                            + " blocked at approve gate (id=" + proposalId + ")");
        }

        switch (p.getProposalType()) {
            case MemoryProposalEntity.TYPE_DEDUP -> applyDedup(p, sources);
            case MemoryProposalEntity.TYPE_REFLECTION -> applyReflection(p, sources);
            case MemoryProposalEntity.TYPE_OPTIMIZE -> applyOptimize(p, sources);
            case MemoryProposalEntity.TYPE_CONTRADICTION -> applyContradiction(p, sources);
            default -> throw new IllegalStateException("unknown proposal_type: " + p.getProposalType());
        }

        p.setStatus(MemoryProposalEntity.STATUS_APPROVED);
        p.setReviewedAt(Instant.now());
        p.setReviewedByUserId(reviewerUserId);
        proposalRepository.save(p);
        log.info("MemoryProposalService approved id={} type={} userId={} reviewer={}",
                proposalId, p.getProposalType(), p.getUserId(), reviewerUserId);
        return ApproveResult.success(p.getProposalType());
    }

    @Transactional
    public MemoryProposalEntity reject(Long proposalId, Long reviewerUserId) {
        MemoryProposalEntity p = proposalRepository.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
        if (!MemoryProposalEntity.STATUS_PROPOSED.equals(p.getStatus())) {
            throw new IllegalStateException(
                    "proposal not in proposed state: id=" + proposalId + " status=" + p.getStatus());
        }
        p.setStatus(MemoryProposalEntity.STATUS_REJECTED);
        p.setReviewedAt(Instant.now());
        p.setReviewedByUserId(reviewerUserId);
        return proposalRepository.save(p);
    }

    @Transactional
    public MemoryProposalEntity edit(Long proposalId, EditRequest req) {
        MemoryProposalEntity p = proposalRepository.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
        if (!MemoryProposalEntity.STATUS_PROPOSED.equals(p.getStatus())) {
            throw new IllegalStateException(
                    "proposal not in proposed state: id=" + proposalId + " status=" + p.getStatus());
        }
        if (req.suggestedTitle() != null) p.setSuggestedTitle(truncate(req.suggestedTitle(), 256));
        if (req.suggestedContent() != null) p.setSuggestedContent(truncate(req.suggestedContent(), 4000));
        if (req.suggestedImportance() != null) p.setSuggestedImportance(req.suggestedImportance());
        if (req.winnerMemoryId() != null) p.setWinnerMemoryId(req.winnerMemoryId());
        return proposalRepository.save(p);
    }

    /**
     * F-N1: contradiction-pick + approve fused into a single endpoint to avoid orphan state.
     * Caller passes winnerMemoryId; we set it and run the normal approve flow.
     */
    @Transactional
    public ApproveResult contradictionPickAndApprove(Long proposalId, Long winnerMemoryId,
                                                      Long reviewerUserId) {
        MemoryProposalEntity p = proposalRepository.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
        if (!MemoryProposalEntity.TYPE_CONTRADICTION.equals(p.getProposalType())) {
            throw new IllegalStateException(
                    "contradiction-pick called on non-contradiction proposal id=" + proposalId);
        }
        if (winnerMemoryId == null) {
            throw new IllegalArgumentException("winnerMemoryId required");
        }
        List<Long> sourceIds = parseSourceIds(p.getSourceMemoryIds());
        if (!sourceIds.contains(winnerMemoryId)) {
            throw new IllegalArgumentException(
                    "winnerMemoryId " + winnerMemoryId + " not in sourceMemoryIds " + sourceIds);
        }
        p.setWinnerMemoryId(winnerMemoryId);
        // Persist winner before approve so approve sees the picked value.
        proposalRepository.save(p);
        // Approve via the same flow; lock is re-acquired but pessimistic_write is reentrant
        // within the same JPA transaction. (Spring TX propagation REQUIRED — same tx.)
        return approve(proposalId, reviewerUserId);
    }

    /**
     * Revert an optimize: restore {@code original_content} → {@code content}, clear
     * {@code original_content}, clear {@code memory_kind}. Idempotent on already-reverted.
     */
    @Transactional
    public MemoryProposalEntity revert(Long proposalId) {
        MemoryProposalEntity p = proposalRepository.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
        if (!MemoryProposalEntity.TYPE_OPTIMIZE.equals(p.getProposalType())) {
            throw new IllegalStateException(
                    "revert only valid for optimize proposals: id=" + proposalId);
        }
        if (!MemoryProposalEntity.STATUS_APPROVED.equals(p.getStatus())) {
            throw new IllegalStateException(
                    "revert requires approved status: id=" + proposalId + " status=" + p.getStatus());
        }
        List<Long> sourceIds = parseSourceIds(p.getSourceMemoryIds());
        if (sourceIds.isEmpty()) {
            throw new IllegalStateException("optimize proposal has no source memory: id=" + proposalId);
        }
        Optional<MemoryEntity> mOpt = memoryRepository.findById(sourceIds.get(0));
        if (mOpt.isEmpty()) {
            throw new IllegalStateException("optimize target memory missing: id=" + sourceIds.get(0));
        }
        MemoryEntity m = mOpt.get();
        if (m.getOriginalContent() != null) {
            m.setContent(m.getOriginalContent());
            m.setOriginalContent(null);
            m.setMemoryKind(null);
            memoryRepository.save(m);
        }
        // Proposal stays approved — revert is a memory action, not a proposal-status action.
        return p;
    }

    /** Sweep proposed proposals older than 7 days → auto_archived. */
    @Transactional
    public int autoArchiveStale() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(7));
        List<MemoryProposalEntity> stale = proposalRepository.findStaleProposed(cutoff);
        int n = 0;
        for (MemoryProposalEntity p : stale) {
            p.setStatus(MemoryProposalEntity.STATUS_AUTO_ARCHIVED);
            proposalRepository.save(p);
            n++;
        }
        if (n > 0) {
            log.info("MemoryProposalService auto-archived {} stale proposals (cutoff={})", n, cutoff);
        }
        return n;
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Apply per proposal_type
    // ─────────────────────────────────────────────────────────────────────────────────

    private void applyDedup(MemoryProposalEntity p, List<MemoryEntity> sources) {
        Long winnerId = p.getWinnerMemoryId();
        if (winnerId == null) {
            throw new IllegalStateException("dedup approve requires winnerMemoryId set: id=" + p.getId());
        }
        for (MemoryEntity s : sources) {
            if (s.getId().equals(winnerId)) continue;
            s.setStatus("ARCHIVED");
            if (s.getArchivedAt() == null) s.setArchivedAt(Instant.now());
            // N-4 fix: prefix matches V66 "dedup_merge_with_" + adds proposal context.
            s.setArchivedReason("llm_dedup_merge_with_" + winnerId + "_proposal_" + p.getId());
            // W-6 fix: dedup path also stamps synthesis_run_id on the archived row.
            s.setSynthesisRunId(p.getSynthesisRunId());
            memoryRepository.save(s);
        }
    }

    private void applyReflection(MemoryProposalEntity p, List<MemoryEntity> sources) {
        MemoryEntity reflection = new MemoryEntity();
        reflection.setUserId(p.getUserId());
        reflection.setTitle(p.getSuggestedTitle());
        reflection.setContent(p.getSuggestedContent());
        reflection.setImportance(p.getSuggestedImportance() != null
                ? p.getSuggestedImportance() : "medium");
        reflection.setStatus("ACTIVE");
        reflection.setMemoryKind("reflection");
        reflection.setDerivedFromMemoryIds(p.getSourceMemoryIds());
        reflection.setSynthesisRunId(p.getSynthesisRunId());
        // type stays in the business taxonomy — knowledge is a sensible default.
        reflection.setType("knowledge");
        reflection.setTags("auto-reflection,llm-synthesis");
        memoryRepository.save(reflection);
        // Source memories are intentionally not modified.
    }

    private void applyOptimize(MemoryProposalEntity p, List<MemoryEntity> sources) {
        if (sources.size() != 1) {
            throw new IllegalStateException(
                    "optimize requires exactly 1 source memory: id=" + p.getId() + " got=" + sources.size());
        }
        MemoryEntity target = sources.get(0);
        target.setOriginalContent(target.getContent());
        target.setContent(p.getSuggestedContent());
        if (p.getSuggestedTitle() != null) {
            target.setTitle(p.getSuggestedTitle());
        }
        target.setMemoryKind("optimized");
        target.setSynthesisRunId(p.getSynthesisRunId());
        memoryRepository.save(target);
    }

    private void applyContradiction(MemoryProposalEntity p, List<MemoryEntity> sources) {
        if (p.getWinnerMemoryId() == null) {
            throw new IllegalStateException(
                    "contradiction approve requires winnerMemoryId set; use contradictionPickAndApprove path: id="
                            + p.getId());
        }
        // Reuses dedup apply: archive losers, stamp synthesisRunId. archivedReason prefix
        // differentiates via llm_contradiction_ to keep audit trail distinguishable.
        Long winnerId = p.getWinnerMemoryId();
        for (MemoryEntity s : sources) {
            if (s.getId().equals(winnerId)) {
                // Bump winner importance: user has actively endorsed it over a contradicting fact.
                s.setImportance("high");
                memoryRepository.save(s);
                continue;
            }
            s.setStatus("ARCHIVED");
            if (s.getArchivedAt() == null) s.setArchivedAt(Instant.now());
            s.setArchivedReason("llm_contradiction_" + winnerId + "_proposal_" + p.getId());
            s.setSynthesisRunId(p.getSynthesisRunId());
            memoryRepository.save(s);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────

    private StaleCheckResult checkStaleByType(String type, List<MemoryEntity> sources) {
        return switch (type) {
            case MemoryProposalEntity.TYPE_DEDUP,
                 MemoryProposalEntity.TYPE_CONTRADICTION -> sources.stream()
                    .anyMatch(m -> "ARCHIVED".equals(m.getStatus()) || "STALE".equals(m.getStatus()))
                    ? StaleCheckResult.stale("source_archived_or_stale")
                    : StaleCheckResult.ok();
            case MemoryProposalEntity.TYPE_REFLECTION -> sources.stream()
                    .anyMatch(m -> "ARCHIVED".equals(m.getStatus()))
                    ? StaleCheckResult.stale("source_archived")
                    : StaleCheckResult.ok();
            case MemoryProposalEntity.TYPE_OPTIMIZE -> sources.stream()
                    .anyMatch(m -> !"ACTIVE".equals(m.getStatus()))
                    ? StaleCheckResult.stale("source_not_active")
                    : StaleCheckResult.ok();
            default -> StaleCheckResult.stale("unknown_type");
        };
    }

    private ApproveResult staleAndPersist(MemoryProposalEntity p, String reason) {
        p.setStatus(MemoryProposalEntity.STATUS_STALE);
        proposalRepository.save(p);
        log.info("MemoryProposalService staled id={} type={} reason={}",
                p.getId(), p.getProposalType(), reason);
        return ApproveResult.staleSourceMemory(reason);
    }

    private List<Long> parseSourceIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Long> ids = objectMapper.readValue(json, new TypeReference<List<Long>>() {});
            return ids != null ? ids : List.of();
        } catch (Exception e) {
            log.warn("MemoryProposalService: failed to parse sourceMemoryIds={}: {}", json, e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        int end = maxLen;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    // ─────────────────────────────────────────────────────────────────────────────────
    // Records / exceptions
    // ─────────────────────────────────────────────────────────────────────────────────

    public record EditRequest(String suggestedTitle,
                              String suggestedContent,
                              String suggestedImportance,
                              Long winnerMemoryId) {
    }

    public record ApproveResult(boolean success, String appliedType, String reason) {
        public static ApproveResult success(String appliedType) {
            return new ApproveResult(true, appliedType, null);
        }
        public static ApproveResult staleSourceMemory(String reason) {
            return new ApproveResult(false, null, reason);
        }
    }

    /** Trivial holder so the switch in {@link #approve} stays readable. */
    private record StaleCheckResult(boolean isStale, String reason) {
        static StaleCheckResult ok() { return new StaleCheckResult(false, null); }
        static StaleCheckResult stale(String r) { return new StaleCheckResult(true, r); }
    }

    public static class ProposalNotFoundException extends RuntimeException {
        public ProposalNotFoundException(Long id) {
            super("memory proposal not found: id=" + id);
        }
    }
}
