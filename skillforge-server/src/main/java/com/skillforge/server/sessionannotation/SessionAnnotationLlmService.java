package com.skillforge.server.sessionannotation;

import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.repository.SessionAnnotationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.3): LLM-stage annotation pipeline.
 *
 * <p>Companion to {@link SessionAnnotationSignalService} (signal stage). The
 * session-annotator agent — after fetching trace context via the existing
 * {@code GetTrace} tool (STEP 2.1) — makes an LLM judgment on each session and
 * calls {@link com.skillforge.server.tool.sessionannotation.AnnotateSessionTool}
 * which delegates to this service to persist {@code source='llm'} rows into
 * {@code t_session_annotation}.
 *
 * <p>Per PRD §"标注层" each LLM annotation writes 2-3 rows:
 * <ul>
 *   <li>{@code outcome}: success | partial_success | failure | cancelled</li>
 *   <li>{@code suspect_surface}: skill | prompt | behavior_rule | other | unclear</li>
 *   <li>{@code top_failing_tool}: tool name (only written when supplied + non-blank)</li>
 * </ul>
 * All three rows share the same {@code confidence} + {@code reasoning} so a
 * downstream cluster query can join them by session_id without re-fetching.
 *
 * <p>Idempotency: the {@code uq_session_annotation} UNIQUE constraint covers
 * (session_id, annotation_type, annotation_value, source). An identical re-run
 * (same outcome / surface / tool tuple) is handled by the repository's native
 * {@code INSERT ... ON CONFLICT DO NOTHING} upsert — a returned id of
 * {@code null} signals the row already existed and is silently dropped from
 * the result list. A re-judgment with a different value (e.g. outcome changed
 * from {@code failure} to {@code partial_success}) is intentionally NOT
 * prevented; both rows persist and downstream consumers use the most-recent
 * {@code createdAt}. This matches PRD §52 "同 source 内幂等（重跑不重复写）"
 * — only the exact 4-tuple is deduped, not the per-session judgment.
 *
 * <p>V1 W2 fix note: the prior implementation used {@code saveAndFlush + catch
 * DataIntegrityViolationException}, which on Postgres aborts the surrounding
 * transaction on the first conflict and silently drops subsequent rows. The
 * native upsert keeps the transaction healthy and the dedup signal per-row.
 */
@Service
public class SessionAnnotationLlmService {

    private static final Logger log = LoggerFactory.getLogger(SessionAnnotationLlmService.class);

    private final SessionAnnotationRepository sessionAnnotationRepository;

    public SessionAnnotationLlmService(SessionAnnotationRepository sessionAnnotationRepository) {
        this.sessionAnnotationRepository = sessionAnnotationRepository;
    }

    /**
     * Persist one LLM-stage judgment for {@code sessionId} as 2-3 annotation rows.
     *
     * @param sessionId       t_session.id; must be non-blank
     * @param outcome         one of {@link SessionAnnotationConstants#OUTCOME_VALUES}
     * @param suspectSurface  one of {@link SessionAnnotationConstants#SUSPECT_SURFACE_VALUES}
     * @param confidence      0.0 ≤ x ≤ 1.0
     * @param reasoning       non-blank, 1-2 sentence rationale (TEXT column, no fixed cap)
     * @param topFailingTool  optional tool name; null or blank = skip the
     *                        {@code top_failing_tool} row
     * @return ids of every row actually inserted (UNIQUE conflicts not included).
     *         Empty list if the entire judgment was a duplicate re-run.
     * @throws IllegalArgumentException if any input fails validation
     */
    @Transactional
    public List<Long> annotateSession(String sessionId,
                                      String outcome,
                                      String suspectSurface,
                                      BigDecimal confidence,
                                      String reasoning,
                                      String topFailingTool) {
        validate(sessionId, outcome, suspectSurface, confidence, reasoning);

        List<Long> ids = new ArrayList<>(3);
        ids.addAll(tryWrite(sessionId,
                SessionAnnotationConstants.TYPE_OUTCOME, outcome,
                confidence, reasoning));
        ids.addAll(tryWrite(sessionId,
                SessionAnnotationConstants.TYPE_SUSPECT_SURFACE, suspectSurface,
                confidence, reasoning));
        if (topFailingTool != null && !topFailingTool.isBlank()) {
            ids.addAll(tryWrite(sessionId,
                    SessionAnnotationConstants.TYPE_TOP_FAILING_TOOL, topFailingTool.trim(),
                    confidence, reasoning));
        }
        log.info("[llm] sessionId={} outcome={} suspectSurface={} topFailingTool={} rowsWritten={}",
                sessionId, outcome, suspectSurface,
                topFailingTool == null || topFailingTool.isBlank() ? "<none>" : topFailingTool,
                ids.size());
        return ids;
    }

    /**
     * Validate every input. Centralised here so the Tool layer is a pure JSON
     * adapter — Tool tests verify wiring, this service's tests own the validation.
     */
    private static void validate(String sessionId,
                                 String outcome,
                                 String suspectSurface,
                                 BigDecimal confidence,
                                 String reasoning) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must be non-blank");
        }
        if (reasoning == null || reasoning.isBlank()) {
            throw new IllegalArgumentException("reasoning must be non-blank");
        }
        if (outcome == null || !SessionAnnotationConstants.OUTCOME_VALUES.contains(outcome)) {
            throw new IllegalArgumentException(
                    "outcome must be one of " + SessionAnnotationConstants.OUTCOME_VALUES
                            + "; got " + outcome);
        }
        if (suspectSurface == null
                || !SessionAnnotationConstants.SUSPECT_SURFACE_VALUES.contains(suspectSurface)) {
            throw new IllegalArgumentException(
                    "suspect_surface must be one of " + SessionAnnotationConstants.SUSPECT_SURFACE_VALUES
                            + "; got " + suspectSurface);
        }
        if (confidence == null) {
            throw new IllegalArgumentException("confidence is required (0..1)");
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "confidence must be within [0, 1]; got " + confidence);
        }
    }

    /**
     * Insert one row via Postgres-native {@code ON CONFLICT DO NOTHING RETURNING id}.
     * On UNIQUE conflict the repository returns {@code null} and we drop the row
     * silently — same idempotency contract as the prior catch-DIVE flow, but
     * without poisoning the surrounding transaction (see class-level Javadoc
     * "V1 W2 fix note"). {@code created_at} is set server-side by the SQL
     * {@code NOW()} default so the timestamp reflects the actual insert, which
     * also keeps the 2-3 rows in this batch consistent with each other.
     */
    private List<Long> tryWrite(String sessionId,
                                String annotationType,
                                String annotationValue,
                                BigDecimal confidence,
                                String reasoning) {
        Long insertedId = sessionAnnotationRepository.upsertSkipDuplicate(
                sessionId,
                annotationType,
                annotationValue,
                SessionAnnotationEntity.SOURCE_LLM,
                confidence,
                reasoning);
        if (insertedId == null) {
            log.debug("[llm] sessionId={} type={} value={} already llm-annotated — skipping",
                    sessionId, annotationType, annotationValue);
            return List.of();
        }
        return List.of(insertedId);
    }

    /**
     * Centralised enum-value constants. Kept here (not enum types) because the
     * t_session_annotation column is VARCHAR and the agent feeds raw strings —
     * a Java enum would require parse/format on every hop. {@link Set}s give
     * O(1) validation without leaking implementation details.
     */
    public static final class SessionAnnotationConstants {
        public static final String TYPE_OUTCOME = "outcome";
        public static final String TYPE_SUSPECT_SURFACE = "suspect_surface";
        public static final String TYPE_TOP_FAILING_TOOL = "top_failing_tool";

        public static final String OUTCOME_SUCCESS = "success";
        public static final String OUTCOME_PARTIAL_SUCCESS = "partial_success";
        public static final String OUTCOME_FAILURE = "failure";
        public static final String OUTCOME_CANCELLED = "cancelled";
        public static final Set<String> OUTCOME_VALUES = Set.of(
                OUTCOME_SUCCESS, OUTCOME_PARTIAL_SUCCESS, OUTCOME_FAILURE, OUTCOME_CANCELLED);

        public static final String SURFACE_SKILL = "skill";
        public static final String SURFACE_PROMPT = "prompt";
        public static final String SURFACE_BEHAVIOR_RULE = "behavior_rule";
        public static final String SURFACE_OTHER = "other";
        public static final String SURFACE_UNCLEAR = "unclear";
        public static final Set<String> SUSPECT_SURFACE_VALUES = Set.of(
                SURFACE_SKILL, SURFACE_PROMPT, SURFACE_BEHAVIOR_RULE, SURFACE_OTHER, SURFACE_UNCLEAR);

        private SessionAnnotationConstants() {}
    }
}
