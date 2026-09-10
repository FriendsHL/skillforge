package com.skillforge.server.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.server.entity.SessionToolAttemptResolutionAuditEntity;
import com.skillforge.server.repository.SessionToolAttemptResolutionAuditRepository;
import com.skillforge.server.session.UnknownOutcomeResolutionAck.ActorAuthority;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest.InboxDisposition;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest.InboxDispositionKind;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest.PostAction;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Insert/read-only participant for unknown-outcome audit rows.
 *
 * <p>This component never starts a transaction and exposes no update/delete operation. The caller
 * owns the Session transaction; PostgreSQL runtime-role grants remain the authoritative append-only
 * enforcement.
 */
@Component
final class UnknownOutcomeResolutionAuditWriter {

    private static final byte[] REASON_DOMAIN =
            "skillforge:unknown-outcome-reason:v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INBOX_MESSAGE_DOMAIN =
            "skillforge:unknown-outcome-inbox-message:v1".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> DISPOSITION_FIELDS = Set.of(
            "inboxId", "messageHash", "disposition");

    private final SessionToolAttemptResolutionAuditRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final ObjectWriter canonicalWriter;

    UnknownOutcomeResolutionAuditWriter(
            SessionToolAttemptResolutionAuditRepository repository,
            EntityManager entityManager,
            ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.canonicalWriter = objectMapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    Optional<AuditSnapshot> find(UUID resolutionRequestId) {
        Objects.requireNonNull(resolutionRequestId, "resolutionRequestId");
        return repository.findByResolutionRequestId(resolutionRequestId).map(this::snapshot);
    }

    AuditSnapshot append(AuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (repository.findByResolutionRequestId(entry.resolutionRequestId()).isPresent()) {
            throw new IllegalStateException("resolution request already exists");
        }
        SessionToolAttemptResolutionAuditEntity row = new SessionToolAttemptResolutionAuditEntity();
        row.setResolutionRequestId(entry.resolutionRequestId());
        row.setSessionId(entry.sessionId());
        row.setAttemptId(entry.attemptId());
        row.setStepId(entry.stepId());
        row.setHistoryEpoch(entry.historyEpoch());
        row.setExecutionGeneration(entry.executionGeneration());
        row.setExecutionFence(entry.executionFence());
        row.setActorId(entry.actorId());
        row.setActorAuthority(entry.actorAuthority().name());
        row.setReasonHash(framedHash(REASON_DOMAIN, entry.reason()));
        row.setAction(entry.action().name());
        row.setInboxDispositionsJson(encodeDispositions(entry.inboxDispositions()));
        row.setResultBatchId(entry.resultBatchId());
        row.setOutcomeState("RESOLVED_UNKNOWN");
        SessionToolAttemptResolutionAuditEntity saved = repository.save(row);
        entityManager.flush();
        entityManager.refresh(saved);
        return snapshot(saved);
    }

    String reasonHash(String reason) {
        return framedHash(REASON_DOMAIN, reason);
    }

    String inboxMessageHash(String exactMessageJson) {
        return framedHash(INBOX_MESSAGE_DOMAIN, exactMessageJson);
    }

    private AuditSnapshot snapshot(SessionToolAttemptResolutionAuditEntity row) {
        if (row.getId() == null || row.getId() <= 0L || row.getCreatedAt() == null
                || row.getAttemptId() == null || row.getAttemptId() <= 0L
                || row.getActorId() == null || row.getActorId() < 0L) {
            throw new IllegalStateException("resolution audit is incomplete");
        }
        return new AuditSnapshot(
                row.getId(), row.getResolutionRequestId(), row.getSessionId(),
                row.getAttemptId(), row.getStepId(), row.getHistoryEpoch(),
                row.getExecutionGeneration(), row.getExecutionFence(), row.getActorId(),
                ActorAuthority.valueOf(row.getActorAuthority()), row.getReasonHash(),
                PostAction.valueOf(row.getAction()),
                decodeDispositions(row.getInboxDispositionsJson()), row.getResultBatchId(),
                row.getOutcomeState(), row.getCreatedAt());
    }

    private String encodeDispositions(List<AuditedInboxDisposition> dispositions) {
        List<Map<String, Object>> encoded = new ArrayList<>(dispositions.size());
        for (AuditedInboxDisposition disposition : dispositions) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("inboxId", disposition.inboxId().toString());
            value.put("messageHash", disposition.messageHash());
            value.put("disposition", disposition.disposition().name());
            encoded.add(value);
        }
        try {
            return canonicalWriter.writeValueAsString(encoded);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("resolution audit could not be encoded");
        }
    }

    private List<AuditedInboxDisposition> decodeDispositions(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()
                    || root.size() > UnknownOutcomeResolutionRequest.MAX_INBOX_DISPOSITIONS) {
                throw new IllegalStateException("resolution audit is invalid");
            }
            List<AuditedInboxDisposition> values = new ArrayList<>(root.size());
            Set<UUID> ids = new LinkedHashSet<>();
            for (JsonNode node : root) {
                if (!node.isObject() || !exactFields(node, DISPOSITION_FIELDS)
                        || !node.path("inboxId").isTextual()
                        || !node.path("messageHash").isTextual()
                        || !node.path("disposition").isTextual()) {
                    throw new IllegalStateException("resolution audit is invalid");
                }
                UUID inboxId = UUID.fromString(node.path("inboxId").textValue());
                String messageHash = node.path("messageHash").textValue();
                InboxDispositionKind disposition = InboxDispositionKind.valueOf(
                        node.path("disposition").textValue());
                if (!ids.add(inboxId) || !messageHash.matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException("resolution audit is invalid");
                }
                values.add(new AuditedInboxDisposition(inboxId, messageHash, disposition));
            }
            return List.copyOf(values);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException("resolution audit is invalid");
        }
    }

    private static boolean exactFields(JsonNode node, Set<String> expected) {
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static String framedHash(byte[] domain, String value) {
        Objects.requireNonNull(value, "hash value");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain);
            digest.update((byte) 0);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
            digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    record AuditEntry(
            UUID resolutionRequestId,
            String sessionId,
            long attemptId,
            UUID stepId,
            long historyEpoch,
            long executionGeneration,
            long executionFence,
            long actorId,
            ActorAuthority actorAuthority,
            String reason,
            PostAction action,
            List<AuditedInboxDisposition> inboxDispositions,
            UUID resultBatchId) {

        AuditEntry {
            Objects.requireNonNull(resolutionRequestId, "resolutionRequestId");
            if (sessionId == null || sessionId.isBlank() || attemptId <= 0L) {
                throw new IllegalArgumentException("invalid audit scope");
            }
            Objects.requireNonNull(stepId, "stepId");
            if (historyEpoch < 0L || executionGeneration <= 0L || executionFence < 0L
                    || actorId < 0L) {
                throw new IllegalArgumentException("invalid audit identity");
            }
            Objects.requireNonNull(actorAuthority, "actorAuthority");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(action, "action");
            inboxDispositions = List.copyOf(Objects.requireNonNull(
                    inboxDispositions, "inboxDispositions"));
            Objects.requireNonNull(resultBatchId, "resultBatchId");
        }
    }

    record AuditSnapshot(
            long auditId,
            UUID resolutionRequestId,
            String sessionId,
            long attemptId,
            UUID stepId,
            long historyEpoch,
            long executionGeneration,
            long executionFence,
            long actorId,
            ActorAuthority actorAuthority,
            String reasonHash,
            PostAction action,
            List<AuditedInboxDisposition> inboxDispositions,
            UUID resultBatchId,
            String outcomeState,
            Instant createdAt) {

        AuditSnapshot {
            inboxDispositions = List.copyOf(inboxDispositions);
        }

        List<InboxDisposition> publicDispositions() {
            return inboxDispositions.stream()
                    .map(value -> new InboxDisposition(value.inboxId(), value.disposition()))
                    .toList();
        }
    }

    record AuditedInboxDisposition(
            UUID inboxId,
            String messageHash,
            InboxDispositionKind disposition) {

        AuditedInboxDisposition {
            Objects.requireNonNull(inboxId, "inboxId");
            if (messageHash == null || !messageHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("messageHash must be lowercase SHA-256");
            }
            Objects.requireNonNull(disposition, "disposition");
        }
    }
}
