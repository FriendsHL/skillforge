package com.skillforge.server.sessionannotation;

import com.skillforge.server.entity.PatternSessionMemberEntity;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.sessionannotation.SessionAnnotationLlmService.SessionAnnotationConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.4): cluster sessions by their (outcome,
 * suspect_surface, top_failing_tool, agent_id) signature and persist clusters
 * into {@code t_session_pattern} + {@code t_pattern_session_member}.
 *
 * <p>STEP 3 of the {@code session-annotator} agent pipeline (§4.1
 * "Clustering — deterministic"). The agent invokes this via
 * {@link com.skillforge.server.tool.sessionannotation.RecomputeClustersTool}
 * once per run.
 *
 * <p>Algorithm (tech-design §5):
 * <ol>
 *   <li>scan {@code t_session_annotation} for rows in the last {@code window}
 *       (by {@code created_at})</li>
 *   <li>per session, fold its annotations into a 4-tuple
 *       (outcome × suspect_surface × top_failing_tool × agent_id) using the
 *       most-recent {@code source='llm'} row per type; reject sessions whose
 *       outcome/suspect_surface row has confidence &lt; 0.5</li>
 *   <li>reject sessions whose outcome is {@code success} (PRD §"准入门槛")</li>
 *   <li>bucket sessions by the 4-tuple; ignore buckets with &lt; 3 members</li>
 *   <li>upsert {@code t_session_pattern} by {@code signature}; insert any
 *       newly-seen member rows (idempotent via composite PK +
 *       {@link DataIntegrityViolationException} catch)</li>
 * </ol>
 *
 * <p>Idempotency: re-running on the same window produces no duplicate rows
 * and no inflated {@code member_count}. Existing patterns whose buckets shrunk
 * are NOT deleted (PRD §5.3).
 *
 * <p>Boundary: this service performs <b>no LLM judgment</b>; it only reads
 * already-persisted annotations and produces deterministic cluster output.
 */
@Service
public class SessionPatternClusterService {

    private static final Logger log = LoggerFactory.getLogger(SessionPatternClusterService.class);

    /** PRD §5.2 — bucket admission threshold. */
    static final int MIN_MEMBERS_PER_PATTERN = 3;

    /** PRD §5.2 — LLM confidence below this is excluded from clustering. */
    static final BigDecimal MIN_LLM_CONFIDENCE = new BigDecimal("0.5");

    private final SessionAnnotationRepository sessionAnnotationRepository;
    private final SessionPatternRepository sessionPatternRepository;
    private final PatternSessionMemberRepository patternSessionMemberRepository;
    private final SessionRepository sessionRepository;

    public SessionPatternClusterService(
            SessionAnnotationRepository sessionAnnotationRepository,
            SessionPatternRepository sessionPatternRepository,
            PatternSessionMemberRepository patternSessionMemberRepository,
            SessionRepository sessionRepository) {
        this.sessionAnnotationRepository = sessionAnnotationRepository;
        this.sessionPatternRepository = sessionPatternRepository;
        this.patternSessionMemberRepository = patternSessionMemberRepository;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Re-compute clusters over annotations created within the last {@code window}.
     *
     * @param window look-back; must be positive. Throws {@link IllegalArgumentException}
     *               otherwise to avoid silently scanning the entire annotation history.
     * @return how many cluster rows were upserted + how many new member rows were inserted.
     */
    @Transactional
    public RecomputeResult recompute(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be a positive Duration; got " + window);
        }
        Instant since = Instant.now().minus(window);

        List<String> sessionIds = sessionAnnotationRepository.findDistinctSessionIdsCreatedSince(since);
        if (sessionIds.isEmpty()) {
            log.info("[cluster] no annotations in window {} since {}", window, since);
            return new RecomputeResult(0, 0);
        }

        // V5 EVAL-DYNAMIC-USER-SIM Phase 1.3 isolation: drop any session whose origin
        // is 'user_sim' (UserSimulatorAgent trial transcript) before bucketing. V1
        // SessionAnnotationSignalService already filters origin=production at the
        // signal-writing step, so this is defense-in-depth — guards future direct
        // annotation paths (e.g. operator UI / agent-authored annotations) against
        // leaking user_sim sessions into the production attribution flywheel.
        sessionIds = excludeUserSimSessions(sessionIds);
        if (sessionIds.isEmpty()) {
            log.info("[cluster] no production-origin sessions in window {} since {}", window, since);
            return new RecomputeResult(0, 0);
        }

        // Build per-session 4-tuples by folding their annotations.
        Map<String, SessionTuple> tupleBySession = new HashMap<>();
        Map<String, Long> agentIdBySession = loadAgentIds(sessionIds);
        for (String sessionId : sessionIds) {
            SessionTuple tuple = buildTuple(sessionId, agentIdBySession.get(sessionId));
            if (tuple != null) {
                tupleBySession.put(sessionId, tuple);
            }
        }
        if (tupleBySession.isEmpty()) {
            log.info("[cluster] no sessions met admission criteria in window {} (success-only / low-confidence / missing outcome)", window);
            return new RecomputeResult(0, 0);
        }

        // Group session ids by signature. LinkedHashMap so logging order is deterministic.
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (Map.Entry<String, SessionTuple> e : tupleBySession.entrySet()) {
            SessionTuple t = e.getValue();
            buckets.computeIfAbsent(t.signature(), k -> new Bucket(t)).add(e.getKey(), t.latestAnnotationAt());
        }

        int patternsUpserted = 0;
        int membersAdded = 0;
        for (Bucket bucket : buckets.values()) {
            if (bucket.sessionIds().size() < MIN_MEMBERS_PER_PATTERN) continue;
            UpsertResult res = upsertBucket(bucket);
            patternsUpserted++;
            membersAdded += res.newMembers();
        }

        log.info("[cluster] window={} since={} sessionsScanned={} bucketsEligible={} patternsUpserted={} membersAdded={}",
                window, since, sessionIds.size(), buckets.values().stream().filter(b -> b.sessionIds().size() >= MIN_MEMBERS_PER_PATTERN).count(),
                patternsUpserted, membersAdded);
        return new RecomputeResult(patternsUpserted, membersAdded);
    }

    /**
     * For the given session, return its 4-tuple or {@code null} if the session is
     * excluded (outcome=success, confidence&lt;0.5, missing outcome/surface, etc).
     */
    private SessionTuple buildTuple(String sessionId, Long agentId) {
        List<SessionAnnotationEntity> rows = sessionAnnotationRepository.findBySessionId(sessionId);
        if (rows.isEmpty()) return null;

        SessionAnnotationEntity outcomeRow = pickLatestLlmRowOfType(rows, SessionAnnotationConstants.TYPE_OUTCOME);
        SessionAnnotationEntity surfaceRow = pickLatestLlmRowOfType(rows, SessionAnnotationConstants.TYPE_SUSPECT_SURFACE);
        SessionAnnotationEntity toolRow = pickLatestLlmRowOfType(rows, SessionAnnotationConstants.TYPE_TOP_FAILING_TOOL);

        if (outcomeRow == null || surfaceRow == null) {
            // Not yet judged by the LLM pass — skip until next run picks it up.
            return null;
        }
        // PRD §"准入门槛": success outcomes don't enter clustering.
        if (SessionAnnotationConstants.OUTCOME_SUCCESS.equals(outcomeRow.getAnnotationValue())) {
            return null;
        }
        // PRD §"准入门槛": confidence < 0.5 excluded. Either row failing is enough.
        if (outcomeRow.getConfidence() == null
                || outcomeRow.getConfidence().compareTo(MIN_LLM_CONFIDENCE) < 0) {
            return null;
        }
        if (surfaceRow.getConfidence() == null
                || surfaceRow.getConfidence().compareTo(MIN_LLM_CONFIDENCE) < 0) {
            return null;
        }

        String outcome = outcomeRow.getAnnotationValue();
        String surface = surfaceRow.getAnnotationValue();
        // top_failing_tool is optional. Treat null AND blank as the same "no tool" signal,
        // so a bucket signature "...|" doesn't compete with "...| ".
        String topTool = toolRow == null || toolRow.getAnnotationValue() == null
                || toolRow.getAnnotationValue().isBlank()
                ? null : toolRow.getAnnotationValue();
        String signature = buildSignature(outcome, surface, topTool, agentId);

        Instant latest = outcomeRow.getCreatedAt();
        if (surfaceRow.getCreatedAt() != null && surfaceRow.getCreatedAt().isAfter(latest)) {
            latest = surfaceRow.getCreatedAt();
        }
        if (toolRow != null && toolRow.getCreatedAt() != null && toolRow.getCreatedAt().isAfter(latest)) {
            latest = toolRow.getCreatedAt();
        }
        return new SessionTuple(signature, outcome, surface, topTool, agentId, latest);
    }

    /**
     * Build the cluster signature. {@code null} {@code topFailingTool} is rendered
     * as empty string per §5.1 ("无 tool failure 则填 null，signature 段写空字符串").
     */
    static String buildSignature(String outcome, String suspectSurface,
                                 String topFailingTool, Long agentId) {
        return outcome
                + "|" + suspectSurface
                + "|" + (topFailingTool == null ? "" : topFailingTool)
                + "|" + (agentId == null ? "" : agentId.toString());
    }

    /**
     * Most-recent {@code source='llm'} row of the given annotation type for one session.
     * "Most recent" is by {@code createdAt} desc (ties broken by id desc deterministically).
     */
    private static SessionAnnotationEntity pickLatestLlmRowOfType(
            List<SessionAnnotationEntity> rows, String annotationType) {
        SessionAnnotationEntity best = null;
        for (SessionAnnotationEntity r : rows) {
            if (!SessionAnnotationEntity.SOURCE_LLM.equals(r.getSource())) continue;
            if (!Objects.equals(r.getAnnotationType(), annotationType)) continue;
            if (best == null) {
                best = r;
                continue;
            }
            Instant ra = r.getCreatedAt();
            Instant rb = best.getCreatedAt();
            if (ra != null && rb != null) {
                if (ra.isAfter(rb)) best = r;
                else if (ra.equals(rb) && r.getId() != null && best.getId() != null
                        && r.getId() > best.getId()) best = r;
            }
        }
        return best;
    }

    private Map<String, Long> loadAgentIds(List<String> sessionIds) {
        if (sessionIds.isEmpty()) return Collections.emptyMap();
        Map<String, Long> out = new HashMap<>();
        for (SessionEntity s : sessionRepository.findAllById(sessionIds)) {
            out.put(s.getId(), s.getAgentId());
        }
        return out;
    }

    /**
     * V5 EVAL-DYNAMIC-USER-SIM Phase 1.3: filter out user_sim-origin sessions before
     * clustering. Preserves input list order. Defense-in-depth — see
     * {@link #recompute(Duration)} javadoc.
     */
    private List<String> excludeUserSimSessions(List<String> sessionIds) {
        if (sessionIds.isEmpty()) return sessionIds;
        Map<String, SessionEntity> byId = new HashMap<>();
        for (SessionEntity s : sessionRepository.findAllById(sessionIds)) {
            byId.put(s.getId(), s);
        }
        List<String> out = new ArrayList<>(sessionIds.size());
        for (String id : sessionIds) {
            SessionEntity s = byId.get(id);
            // Unknown sessions (not yet loaded / deleted) pass through — clustering
            // will skip them at tuple-build time when agentId is null.
            if (s == null || !"user_sim".equals(s.getOrigin())) {
                out.add(id);
            }
        }
        return out;
    }

    private UpsertResult upsertBucket(Bucket bucket) {
        Optional<SessionPatternEntity> existing = sessionPatternRepository.findBySignature(bucket.signature());
        Instant now = Instant.now();
        SessionPatternEntity pattern;
        if (existing.isPresent()) {
            pattern = existing.get();
            // last_seen_at = latest annotation timestamp seen in this run (never go back in time).
            if (bucket.latestAt() != null
                    && (pattern.getLastSeenAt() == null || bucket.latestAt().isAfter(pattern.getLastSeenAt()))) {
                pattern.setLastSeenAt(bucket.latestAt());
            }
            pattern.setUpdatedAt(now);
        } else {
            pattern = new SessionPatternEntity();
            pattern.setSignature(bucket.signature());
            pattern.setOutcome(bucket.outcome());
            pattern.setSuspectSurface(bucket.suspectSurface());
            pattern.setTopFailingTool(bucket.topFailingTool());
            pattern.setAgentId(bucket.agentId());
            pattern.setMemberCount(0);
            // V1: suggested_surface mirrors suspect_surface until V3 attribution runs (tech-design §5).
            pattern.setSuggestedSurface(bucket.suspectSurface());
            pattern.setFirstSeenAt(bucket.earliestAt() != null ? bucket.earliestAt() : now);
            pattern.setLastSeenAt(bucket.latestAt() != null ? bucket.latestAt() : now);
            pattern.setCreatedAt(now);
            pattern.setUpdatedAt(now);
        }
        // Save first (need pattern.id for member insert).
        pattern = sessionPatternRepository.save(pattern);

        int newMembers = 0;
        for (String sessionId : bucket.sessionIds()) {
            PatternSessionMemberEntity m = new PatternSessionMemberEntity();
            m.setPatternId(pattern.getId());
            m.setSessionId(sessionId);
            m.setAddedAt(now);
            try {
                patternSessionMemberRepository.saveAndFlush(m);
                newMembers++;
            } catch (DataIntegrityViolationException dive) {
                // (pattern_id, session_id) already present — idempotent re-run, expected.
                log.debug("[cluster] member already exists patternId={} sessionId={}",
                        pattern.getId(), sessionId);
            }
        }
        // member_count = old + newly inserted (NOT bucket size, because old members
        // outside the current window stay; new members for this run are appended).
        pattern.setMemberCount(pattern.getMemberCount() + newMembers);
        sessionPatternRepository.save(pattern);

        return new UpsertResult(newMembers);
    }

    /** Result tuple for {@link #recompute}. */
    public record RecomputeResult(int patternsUpserted, int membersAdded) {}

    private record UpsertResult(int newMembers) {}

    /**
     * 4-tuple identifying one session's cluster bucket. {@code latestAnnotationAt} is
     * the most-recent timestamp among the rows that produced this tuple — used to
     * roll up {@code last_seen_at} on the pattern.
     */
    private record SessionTuple(String signature,
                                String outcome,
                                String suspectSurface,
                                String topFailingTool,
                                Long agentId,
                                Instant latestAnnotationAt) {}

    /** All sessions sharing one signature, plus extents for last/first_seen_at upsert. */
    private static final class Bucket {
        private final String signature;
        private final String outcome;
        private final String suspectSurface;
        private final String topFailingTool;
        private final Long agentId;
        private final List<String> sessionIds = new ArrayList<>();
        private Instant earliestAt;
        private Instant latestAt;

        Bucket(SessionTuple seed) {
            this.signature = seed.signature();
            this.outcome = seed.outcome();
            this.suspectSurface = seed.suspectSurface();
            this.topFailingTool = seed.topFailingTool();
            this.agentId = seed.agentId();
        }

        void add(String sessionId, Instant at) {
            sessionIds.add(sessionId);
            if (at == null) return;
            if (earliestAt == null || at.isBefore(earliestAt)) earliestAt = at;
            if (latestAt == null || at.isAfter(latestAt)) latestAt = at;
        }

        String signature() { return signature; }
        String outcome() { return outcome; }
        String suspectSurface() { return suspectSurface; }
        String topFailingTool() { return topFailingTool; }
        Long agentId() { return agentId; }
        List<String> sessionIds() { return sessionIds; }
        Instant earliestAt() { return earliestAt; }
        Instant latestAt() { return latestAt; }
    }
}
