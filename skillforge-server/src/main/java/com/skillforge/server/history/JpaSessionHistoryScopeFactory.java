package com.skillforge.server.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.ReplaySafety;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** JPA authority bridge from a trusted loop/tool identity to the current durable Tool attempt. */
@Service
public class JpaSessionHistoryScopeFactory implements SessionHistoryScopeFactory {

    private static final List<String> EXECUTING_STATE = List.of("EXECUTING");
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion", "calls", "replaySafety");
    private static final Set<String> CALL_FIELDS = Set.of(
            "providerOrdinal", "toolUseId", "toolName", "input", "replaySafety");
    private static final SessionHistoryAvailabilityPolicy.StoreReadiness NOT_READY =
            new SessionHistoryAvailabilityPolicy.StoreReadiness(
                    false, false, false, false, false);

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final SessionMessageInboxRepository inboxRepository;
    private final ObjectMapper objectMapper;

    public JpaSessionHistoryScopeFactory(
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionToolAttemptRepository attemptRepository,
            SessionMessageInboxRepository inboxRepository,
            ObjectMapper objectMapper) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.inboxRepository = Objects.requireNonNull(inboxRepository, "inboxRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionHistoryAvailabilityPolicy.StoreReadiness readiness(SkillContext context) {
        try {
            LoopDurabilityScope trusted = requireTrustedLoop(context, false);
            SessionEntity session = requireCurrentSession(trusted);
            Instant databaseNow = sessionRepository.currentDatabaseTime();
            if (databaseNow == null) throw unavailable();
            requireCurrentLoop(session, trusted, databaseNow);
            boolean legacyOnly = !SessionHistoryRowAuthority.isVerified(
                    session, messageRepository, objectMapper);

            // The factory bean cannot exist without all four durable participants. Keep the
            // explicit booleans because the rollout policy treats partial deployments as deny.
            boolean attemptWriterAvailable = attemptRepository != null;
            boolean inboxAvailable = inboxRepository != null;
            return new SessionHistoryAvailabilityPolicy.StoreReadiness(
                    true, true, attemptWriterAvailable, inboxAvailable, legacyOnly);
        } catch (RuntimeException unavailable) {
            return NOT_READY;
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CurrentSessionHistoryScope resolve(
            SkillContext context,
            String expectedToolName) {
        requireHistoryToolName(expectedToolName);
        LoopDurabilityScope trusted = requireTrustedLoop(context, true);
        SessionEntity session = requireCurrentSession(trusted);
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        if (databaseNow == null) throw unavailable();
        requireCurrentLoop(session, trusted, databaseNow);
        if (!SessionHistoryRowAuthority.isVerified(session, messageRepository, objectMapper)) {
            throw unavailable();
        }

        List<SessionToolAttemptEntity> attempts = attemptRepository.findBySessionIdAndStateIn(
                trusted.sessionId(), EXECUTING_STATE);
        if (attempts == null || attempts.size() != 1) throw unavailable();
        SessionToolAttemptEntity attempt = attempts.get(0);
        requireCurrentAttempt(attempt, trusted, databaseNow);
        requireManifestCall(attempt, context.getToolUseId(), expectedToolName);
        return CurrentSessionHistoryScope.from(
                context,
                attempt.getHistoryEpoch(),
                attempt.getPreIntentMaxMessageId(),
                attempt.getPreIntentMaxSeq());
    }

    private SessionEntity requireCurrentSession(LoopDurabilityScope trusted) {
        SessionEntity session = sessionRepository.findById(trusted.sessionId())
                .orElseThrow(JpaSessionHistoryScopeFactory::unavailable);
        if (!Objects.equals(session.getUserId(), trusted.userId())
                || session.getHistoryEpoch() != trusted.historyEpoch()) {
            throw unavailable();
        }
        return session;
    }

    private static void requireCurrentLoop(
            SessionEntity session,
            LoopDurabilityScope trusted,
            Instant databaseNow) {
        if (session.isRestorePreparing()
                || !trusted.loopId().equals(session.getActiveLoopId())
                || trusted.loopFence() != session.getLoopFence()
                || !trusted.ownerInstanceId().equals(session.getLoopOwnerInstanceId())
                || session.getLoopLeaseUntil() == null
                || !session.getLoopLeaseUntil().isAfter(databaseNow)) {
            throw unavailable();
        }
    }

    private static void requireCurrentAttempt(
            SessionToolAttemptEntity attempt,
            LoopDurabilityScope trusted,
            Instant databaseNow) {
        boolean emptyFrontier = attempt.getPreIntentMaxMessageId() == -1L
                && attempt.getPreIntentMaxSeq() == -1L;
        boolean populatedFrontier = attempt.getPreIntentMaxMessageId() >= 0L
                && attempt.getPreIntentMaxSeq() >= 0L;
        if (!"EXECUTING".equals(attempt.getState())
                || !trusted.sessionId().equals(attempt.getSessionId())
                || attempt.getHistoryEpoch() != trusted.historyEpoch()
                || !trusted.loopId().equals(attempt.getExecutionLoopId())
                || !Objects.equals(trusted.loopFence(), attempt.getExecutionFence())
                || !trusted.ownerInstanceId().equals(attempt.getExecutionOwnerInstanceId())
                || attempt.getExecutionGeneration() <= 0L
                || attempt.getClaimRequestId() == null
                || attempt.getClaimedAt() == null
                || attempt.getExecutionLeaseUntil() == null
                || !attempt.getExecutionLeaseUntil().isAfter(databaseNow)
                || !(emptyFrontier || populatedFrontier)) {
            throw unavailable();
        }
    }

    private void requireManifestCall(
            SessionToolAttemptEntity attempt,
            String expectedToolUseId,
            String expectedToolName) {
        if (expectedToolUseId == null || expectedToolUseId.isBlank()
                || attempt.getManifestJson() == null
                || !sha256(attempt.getManifestJson()).equals(attempt.getManifestHash())) {
            throw unavailable();
        }
        try {
            JsonNode root = objectMapper.readTree(attempt.getManifestJson());
            if (root == null || !root.isObject() || !hasExactFields(root, MANIFEST_FIELDS)
                    || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").intValue() != 1
                    || !root.path("calls").isArray() || root.path("calls").isEmpty()
                    || !root.path("replaySafety").isTextual()) {
                throw unavailable();
            }
            List<ReplaySafety> callSafety = new ArrayList<>();
            Set<String> toolUseIds = new HashSet<>();
            int targetCount = 0;
            int ordinal = 0;
            for (JsonNode call : root.path("calls")) {
                if (!call.isObject() || !hasExactFields(call, CALL_FIELDS)
                        || !call.path("providerOrdinal").isIntegralNumber()
                        || call.path("providerOrdinal").intValue() != ordinal++
                        || !call.path("toolUseId").isTextual()
                        || call.path("toolUseId").textValue().isBlank()
                        || !call.path("toolName").isTextual()
                        || call.path("toolName").textValue().isBlank()
                        || !call.has("input")
                        || !call.path("replaySafety").isTextual()
                        || !toolUseIds.add(call.path("toolUseId").textValue())) {
                    throw unavailable();
                }
                callSafety.add(ReplaySafety.valueOf(call.path("replaySafety").textValue()));
                if (expectedToolUseId.equals(call.path("toolUseId").textValue())
                        && expectedToolName.equals(call.path("toolName").textValue())) {
                    targetCount++;
                }
            }
            ReplaySafety aggregate = ReplaySafety.valueOf(root.path("replaySafety").textValue());
            if (ReplaySafety.aggregate(callSafety) != aggregate
                    || !aggregate.name().equals(attempt.getReplaySafety())
                    || targetCount != 1) {
                throw unavailable();
            }
        } catch (JsonProcessingException | IllegalArgumentException invalidManifest) {
            throw unavailable();
        }
    }

    private static LoopDurabilityScope requireTrustedLoop(
            SkillContext context,
            boolean requireToolUseId) {
        if (context == null || context.getSessionId() == null || context.getSessionId().isBlank()
                || context.getUserId() == null || context.getUserId() < 0L
                || requireToolUseId
                    && (context.getToolUseId() == null || context.getToolUseId().isBlank())) {
            throw unavailable();
        }
        LoopDurabilityScope trusted = context.getDurabilityScope();
        if (trusted == null
                || !context.getSessionId().equals(trusted.sessionId())
                || context.getUserId() != trusted.userId()) {
            throw unavailable();
        }
        return trusted;
    }

    private static void requireHistoryToolName(String toolName) {
        if (!SessionHistoryToolSchemas.SEARCH_NAME.equals(toolName)
                && !SessionHistoryToolSchemas.READ_NAME.equals(toolName)) {
            throw new IllegalArgumentException("Unknown History Tool");
        }
    }

    private static boolean hasExactFields(JsonNode value, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static SessionHistoryUnavailableException unavailable() {
        return new SessionHistoryUnavailableException(
                "HISTORY_UNAVAILABLE", "History is unavailable for the current Session");
    }
}
