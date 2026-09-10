package com.skillforge.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.compact.CompactSummaryEnvelope;
import com.skillforge.core.compact.CompactSummaryMessage;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionMessageInboxEntity;
import com.skillforge.server.entity.SessionSummaryEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Captures and revalidates the database state that authorizes one Compact computation.
 *
 * <p>Full Compact deliberately releases its process-local stripe during the summary LLM call. A
 * stripe cannot protect against another server instance, and it also cannot prove that a queued
 * USER, durable Tool attempt, restore, or loop-fence transition did not happen while the LLM was
 * running. This service therefore captures a closed database token under the Session row lock and
 * requires the identical token again in Compact Phase 3 before any summary/rewrite is persisted.
 * Transcript and inbox bodies only contribute to SHA-256 fingerprints and are never logged.
 */
@Service
public class CompactAdmissionService {

    private static final int PAGE_SIZE = 500;
    private static final String AGENT_TOOL_SOURCE = "agent-tool";
    private static final String COMPACT_TOOL_NAME = "compact_context";
    private static final Set<String> FULL_SOURCES = Set.of(
            "user-manual",
            AGENT_TOOL_SOURCE,
            "engine-hard",
            "engine-preemptive",
            "post-overflow");
    private static final Set<String> BLOCKING_ATTEMPT_STATES = Set.of(
            DurableToolAttemptState.INTENT_COMMITTED.name(),
            DurableToolAttemptState.EXECUTING.name(),
            DurableToolAttemptState.WAITING_USER.name(),
            DurableToolAttemptState.UNCERTAIN_PENDING_RESOLUTION.name());

    private final SessionHistoryProperties properties;
    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionSummaryRepository summaryRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final SessionMessageInboxRepository inboxRepository;
    private final PersistedMessageCodec messageCodec;
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;

    public CompactAdmissionService(
            SessionHistoryProperties properties,
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionSummaryRepository summaryRepository,
            SessionToolAttemptRepository attemptRepository,
            SessionMessageInboxRepository inboxRepository,
            PersistedMessageCodec messageCodec,
            ObjectMapper objectMapper,
            SessionService sessionService) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.summaryRepository = Objects.requireNonNull(summaryRepository, "summaryRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.inboxRepository = Objects.requireNonNull(inboxRepository, "inboxRepository");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    /** Phase 1 admission. The Session lock makes the multi-repository token one coherent snapshot. */
    @Transactional
    public AdmissionSnapshot capture(String sessionId, String level, String source) {
        requireText(sessionId, "sessionId");
        requireText(level, "level");
        requireText(source, "source");
        if (!properties.isEnabled()) {
            return AdmissionSnapshot.disabled(sessionId, level, source);
        }
        if ("full".equalsIgnoreCase(level) && !FULL_SOURCES.contains(source)) {
            throw rejected("Unsupported Full Compact source");
        }
        return captureLocked(sessionId, level, source);
    }

    /**
     * Phase 3 stale check. When this returns, the Session row remains locked by the caller's outer
     * persistence transaction, so no durable writer that follows the Session-first lock order can
     * invalidate the token before Compact persistence commits.
     */
    @Transactional
    public void revalidate(AdmissionSnapshot expected) {
        Objects.requireNonNull(expected, "expected");
        if (!expected.enforced()) {
            return;
        }
        if (!properties.isEnabled()) {
            throw stale();
        }
        AdmissionSnapshot actual;
        try {
            actual = captureLocked(expected.sessionId(), expected.level(), expected.source());
        } catch (IllegalStateException noLongerAdmissible) {
            // A closed/replaced attempt, deleted Session, or newly ambiguous open-attempt set is
            // not a new admission error for Phase 3: it is precisely a stale Phase-1 token.
            throw stale();
        }
        if (!expected.sameVerificationState(actual)) {
            throw stale();
        }
    }

    /**
     * Selects the only input allowed for an agent-authored Full Compact: the in-memory view that
     * ends at the durable pre-intent frontier. The exact persisted assistant intent must be the last
     * element and is returned as an untouched suffix for the Engine result view.
     */
    public PreparedMessages prepareFullInput(
            AdmissionSnapshot admission, List<Message> currentMessages) {
        Objects.requireNonNull(admission, "admission");
        if (!admission.enforced()) {
            return new PreparedMessages(currentMessages, List.of());
        }
        requireCandidateMatchesAuthoritative(admission, currentMessages);
        List<Message> authoritative = admission.modelView().messages();
        if (!admission.agentTool()) {
            return new PreparedMessages(authoritative, List.of());
        }
        if (!"full".equalsIgnoreCase(admission.level()) || admission.openAttempt() == null) {
            throw rejected("Agent Compact is missing a durable compact intent");
        }
        if (authoritative.isEmpty()) {
            throw rejected("Agent Compact requires its authoritative durable intent");
        }
        Message assistant = authoritative.get(authoritative.size() - 1);
        String assistantHash = sha256(messageCodec.writeMessage(assistant));
        if (!assistantHash.equals(admission.openAttempt().assistantPayloadHash())) {
            throw rejected("Agent Compact intent does not match the durable assistant");
        }
        return new PreparedMessages(
                List.copyOf(authoritative.subList(0, authoritative.size() - 1)),
                List.of(assistant));
    }

    /** Normal Light Compact uses the same exact authoritative model view as its Phase-1 token. */
    public List<Message> prepareLightInput(
            AdmissionSnapshot admission, List<Message> currentMessages) {
        Objects.requireNonNull(admission, "admission");
        if (!admission.enforced()) {
            return currentMessages;
        }
        requireCandidateMatchesAuthoritative(admission, currentMessages);
        return admission.modelView().messages();
    }

    private void requireCandidateMatchesAuthoritative(
            AdmissionSnapshot admission, List<Message> currentMessages) {
        // A null candidate is the REST path: it deliberately consumes the server-captured view.
        if (currentMessages == null) {
            return;
        }
        List<String> candidateJson = canonicalMessageJson(currentMessages);
        if (!candidateJson.equals(admission.modelView().canonicalMessages())) {
            throw rejected("Compact input does not match the authoritative durable model view");
        }
    }

    private AdmissionSnapshot captureLocked(String sessionId, String level, String source) {
        SessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> rejected("Compact Session is unavailable"));
        List<SessionMessageEntity> messages = loadMessages(sessionId);
        if (session.getMessageCount() != messages.size()) {
            // Master-on never falls back to the legacy messages_json CLOB or a partially
            // backfilled row store. The Session counter and complete row view must describe the
            // same durable transcript before any model projection is allowed into Phase 2.
            throw rejected("Compact row store is not authoritative for this Session");
        }
        List<SessionSummaryEntity> summaries = new ArrayList<>(
                summaryRepository.findBySessionIdOrderByStartSeqAsc(sessionId));
        summaries.sort(Comparator.comparing(
                SessionSummaryEntity::getId,
                Comparator.nullsFirst(Long::compareTo)));

        TranscriptToken transcript = transcriptToken(messages, summaries);
        InboxToken inbox = inboxToken(sessionId);
        List<SessionToolAttemptEntity> blocking = attemptRepository.findBySessionIdAndStateIn(
                sessionId, BLOCKING_ATTEMPT_STATES);
        if (blocking.size() > 1) {
            throw rejected("Compact found multiple unresolved Tool attempts");
        }

        boolean agentTool = "full".equalsIgnoreCase(level) && AGENT_TOOL_SOURCE.equals(source);
        AttemptToken openAttempt = blocking.isEmpty() ? null : attemptToken(blocking.get(0));
        if (!agentTool && openAttempt != null) {
            throw rejected("Compact is blocked by an unresolved Tool attempt");
        }
        if (agentTool) {
            validateAgentCompactAttempt(session, messages, openAttempt);
        }

        AuthoritativeModelView modelView = authoritativeModelView(sessionId);

        return new AdmissionSnapshot(
                true,
                sessionId,
                level.toLowerCase(java.util.Locale.ROOT),
                source,
                session.getHistoryEpoch(),
                transcript,
                session.getActiveLoopId(),
                session.getLoopFence(),
                openAttempt,
                inbox,
                agentTool,
                modelView);
    }

    private AuthoritativeModelView authoritativeModelView(String sessionId) {
        List<Message> captured = sessionService.getContextMessages(sessionId);
        List<Message> messages = new ArrayList<>(captured == null ? 0 : captured.size());
        if (captured != null) {
            for (Message message : captured) {
                messages.add(copyAuthoritativeMessage(message));
            }
        }
        List<String> canonical = canonicalMessageJson(messages);
        MessageDigest digest = digest("skillforge:compact:model-view:v1");
        for (int index = 0; index < canonical.size(); index++) {
            update(digest, "message.index", index);
            update(digest, "message.json", canonical.get(index));
        }
        return new AuthoritativeModelView(
                messages, canonical, HexFormat.of().formatHex(digest.digest()));
    }

    private Message copyAuthoritativeMessage(Message message) {
        Objects.requireNonNull(message, "authoritative message");
        if (message instanceof CompactSummaryMessage summary) {
            CompactSummaryEnvelope.TrustedSummary trusted = summary.trustedSummary();
            if (CompactSummaryEnvelope.parseTrusted(message, trusted).isEmpty()) {
                throw rejected("Authoritative compact summary carrier is invalid");
            }
            return new CompactSummaryMessage(trusted);
        }
        return messageCodec.readMessage(messageCodec.writeMessage(message));
    }

    private List<String> canonicalMessageJson(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> canonical = new ArrayList<>(messages.size());
        for (Message message : messages) {
            canonical.add(messageCodec.writeMessage(message));
        }
        return List.copyOf(canonical);
    }

    private void validateAgentCompactAttempt(
            SessionEntity session,
            List<SessionMessageEntity> messages,
            AttemptToken attempt) {
        if (attempt == null) {
            throw rejected("Agent Compact requires a durable compact intent");
        }
        if (!DurableToolAttemptState.EXECUTING.name().equals(attempt.state())
                || attempt.historyEpoch() != session.getHistoryEpoch()
                || !Objects.equals(attempt.executionLoopId(), session.getActiveLoopId())
                || !Objects.equals(attempt.executionFence(), session.getLoopFence())) {
            throw rejected("Agent Compact durable intent is not authoritative");
        }
        SessionMessageEntity assistant = messages.stream()
                .filter(row -> Objects.equals(row.getId(), attempt.assistantMessageId()))
                .findFirst()
                .orElseThrow(() -> rejected("Agent Compact assistant occurrence is unavailable"));
        if (messages.isEmpty()
                || !Objects.equals(messages.get(messages.size() - 1).getId(), assistant.getId())
                || assistant.getSeqNo() <= attempt.preIntentMaxSeq()
                || !frontierImmediatelyPrecedes(
                        messages, attempt.preIntentMaxMessageId(), attempt.preIntentMaxSeq())
                || !attempt.assistantPayloadHash().equals(messagePayloadHash(assistant))) {
            throw rejected("Agent Compact durable frontier is inconsistent");
        }
        Message assistantMessage = decodeMessage(assistant);
        if (!manifestMatchesAssistant(attempt, assistantMessage)) {
            throw rejected("Agent Compact manifest does not match its durable assistant");
        }
    }

    private boolean manifestMatchesAssistant(AttemptToken attempt, Message assistant) {
        try {
            String manifestJson = attempt.manifestJson();
            if (manifestJson == null || attempt.manifestHash() == null
                    || !sha256(manifestJson).equals(attempt.manifestHash())) {
                return false;
            }
            JsonNode root = objectMapper.readTree(manifestJson);
            if (root == null || !root.isObject()
                    || !hasExactFields(root, Set.of("schemaVersion", "calls", "replaySafety"))
                    || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").intValue() != 1
                    || !root.path("replaySafety").isTextual()
                    || !Objects.equals(root.path("replaySafety").textValue(), attempt.replaySafety())) {
                return false;
            }
            JsonNode calls = root.path("calls");
            List<ToolUseBlock> blocks = assistant.getToolUseBlocks();
            if (!calls.isArray() || calls.isEmpty() || calls.size() != blocks.size()) {
                return false;
            }
            boolean containsCompact = false;
            for (int index = 0; index < calls.size(); index++) {
                JsonNode call = calls.get(index);
                ToolUseBlock block = blocks.get(index);
                if (!call.isObject()
                        || !hasExactFields(call, Set.of(
                                "providerOrdinal", "toolUseId", "toolName", "input", "replaySafety"))
                        || !call.path("providerOrdinal").isIntegralNumber()
                        || call.path("providerOrdinal").intValue() != index
                        || !call.path("toolUseId").isTextual()
                        || !Objects.equals(call.path("toolUseId").textValue(), block.getId())
                        || !call.path("toolName").isTextual()
                        || !Objects.equals(call.path("toolName").textValue(), block.getName())
                        || !call.has("input")
                        || !call.get("input").equals(objectMapper.valueToTree(block.getInput()))
                        || !call.path("replaySafety").isTextual()) {
                    return false;
                }
                containsCompact |= COMPACT_TOOL_NAME.equals(block.getName());
            }
            return containsCompact;
        } catch (Exception invalidManifest) {
            return false;
        }
    }

    private static boolean hasExactFields(JsonNode value, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static boolean frontierImmediatelyPrecedes(
            List<SessionMessageEntity> messages, long messageId, long seqNo) {
        if (messageId == -1L || seqNo == -1L) {
            return messageId == -1L && seqNo == -1L && messages.size() == 1;
        }
        if (messages.size() < 2) {
            return false;
        }
        SessionMessageEntity predecessor = messages.get(messages.size() - 2);
        return Objects.equals(predecessor.getId(), messageId) && predecessor.getSeqNo() == seqNo;
    }

    private List<SessionMessageEntity> loadMessages(String sessionId) {
        List<SessionMessageEntity> all = new ArrayList<>();
        for (int pageNumber = 0; ; pageNumber++) {
            Page<SessionMessageEntity> page = messageRepository.findBySessionIdOrderBySeqNoAsc(
                    sessionId, PageRequest.of(pageNumber, PAGE_SIZE));
            all.addAll(page.getContent());
            if (!page.hasNext()) return all;
        }
    }

    private InboxToken inboxToken(String sessionId) {
        MessageDigest digest = digest("skillforge:compact:inbox:v1");
        long count = 0L;
        Long lastId = null;
        for (int pageNumber = 0; ; pageNumber++) {
            List<SessionMessageInboxEntity> page = inboxRepository.findBySessionIdOrderByIdAsc(
                    sessionId, PageRequest.of(pageNumber, PAGE_SIZE));
            for (SessionMessageInboxEntity row : page) {
                update(digest, "id", row.getId());
                update(digest, "inboxId", row.getInboxId());
                update(digest, "userId", row.getUserId());
                update(digest, "messageJson", row.getMessageJson());
                update(digest, "createdAt", row.getCreatedAt());
                count++;
                lastId = row.getId();
            }
            // The repository contract returns a bounded List rather than Page. A short page is the
            // keyset-equivalent exhaustion signal; an exact full page advances to the next page.
            if (page.size() < PAGE_SIZE) break;
        }
        return new InboxToken(count, lastId, HexFormat.of().formatHex(digest.digest()));
    }

    private TranscriptToken transcriptToken(
            List<SessionMessageEntity> messages, List<SessionSummaryEntity> summaries) {
        MessageDigest digest = digest("skillforge:compact:transcript-provenance:v1");
        for (SessionMessageEntity row : messages) {
            update(digest, "message.id", row.getId());
            update(digest, "message.seq", row.getSeqNo());
            update(digest, "message.role", row.getRole());
            update(digest, "message.msgType", row.getMsgType());
            update(digest, "message.content", row.getContentJson());
            update(digest, "message.metadata", row.getMetadataJson());
            update(digest, "message.messageType", row.getMessageType());
            update(digest, "message.controlId", row.getControlId());
            update(digest, "message.reasoning", row.getReasoningContent());
            update(digest, "message.traceId", row.getTraceId());
            update(digest, "message.writeBatchId", row.getWriteBatchId());
            update(digest, "message.writeBatchOrdinal", row.getWriteBatchOrdinal());
            update(digest, "message.prunedAt", row.getPrunedAt());
            update(digest, "message.compactedBySummaryId", row.getCompactedBySummaryId());
            update(digest, "message.answeredAt", row.getAnsweredAt());
        }
        for (SessionSummaryEntity row : summaries) {
            update(digest, "summary.id", row.getId());
            update(digest, "summary.start", row.getStartSeq());
            update(digest, "summary.end", row.getEndSeq());
            update(digest, "summary.text", row.getSummaryText());
            update(digest, "summary.level", row.getLevel());
            update(digest, "summary.source", row.getSource());
            update(digest, "summary.tokensBefore", row.getTokensBefore());
            update(digest, "summary.tokensAfter", row.getTokensAfter());
            update(digest, "summary.count", row.getCompactedMessageCount());
            update(digest, "summary.recovery", row.getRecoveryPayload());
            update(digest, "summary.supersededBy", row.getSupersededBy());
            update(digest, "summary.createdAt", row.getCreatedAt());
        }
        SessionMessageEntity tail = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        return new TranscriptToken(
                tail == null ? -1L : Objects.requireNonNull(tail.getId(), "tail.id"),
                tail == null ? -1L : tail.getSeqNo(),
                HexFormat.of().formatHex(digest.digest()));
    }

    private AttemptToken attemptToken(SessionToolAttemptEntity attempt) {
        return new AttemptToken(
                attempt.getId(),
                attempt.getStepId() == null ? null : attempt.getStepId().toString(),
                attempt.getHistoryEpoch(),
                attempt.getOriginLoopId(),
                attempt.getOriginFence(),
                attempt.getAssistantMessageId(),
                attempt.getAssistantPayloadHash(),
                attempt.getPreIntentMaxMessageId(),
                attempt.getPreIntentMaxSeq(),
                attempt.getManifestJson(),
                attempt.getManifestHash(),
                attempt.getReplaySafety(),
                attempt.getState(),
                attempt.getExecutionLoopId(),
                attempt.getExecutionFence(),
                attempt.getExecutionOwnerInstanceId(),
                attempt.getExecutionGeneration(),
                attempt.getClaimRequestId() == null ? null : attempt.getClaimRequestId().toString(),
                attempt.getResultBatchId() == null ? null : attempt.getResultBatchId().toString(),
                attempt.getPostActionState());
    }

    private String messagePayloadHash(SessionMessageEntity row) {
        return sha256(messageCodec.writeMessage(decodeMessage(row)));
    }

    private Message decodeMessage(SessionMessageEntity row) {
        PersistedMessageCodec.PersistedMessage decoded = messageCodec.decodeRow(
                new PersistedMessageCodec.EncodedRow(
                        row.getRole(),
                        row.getContentJson(),
                        row.getReasoningContent(),
                        row.getMsgType(),
                        row.getMessageType(),
                        row.getControlId(),
                        row.getAnsweredAt(),
                        row.getMetadataJson(),
                        row.getTraceId()));
        return decoded.message();
    }

    private static MessageDigest digest(String domain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "domain", domain);
            return digest;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String tag, Object value) {
        byte[] tagBytes = tag.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(tagBytes.length).array());
        digest.update(tagBytes);
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static IllegalStateException rejected(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("Compact admission snapshot is stale");
    }

    public record AdmissionSnapshot(
            boolean enforced,
            String sessionId,
            String level,
            String source,
            long historyEpoch,
            TranscriptToken tail,
            String activeLoopId,
            long loopFence,
            AttemptToken openAttempt,
            InboxToken inbox,
            boolean agentTool,
            AuthoritativeModelView modelView) {

        public AdmissionSnapshot {
            Objects.requireNonNull(modelView, "modelView");
        }

        /** Compatibility constructor for focused mocks created before model-view binding. */
        public AdmissionSnapshot(
                boolean enforced,
                String sessionId,
                String level,
                String source,
                long historyEpoch,
                TranscriptToken tail,
                String activeLoopId,
                long loopFence,
                AttemptToken openAttempt,
                InboxToken inbox,
                boolean agentTool) {
            this(enforced, sessionId, level, source, historyEpoch, tail, activeLoopId, loopFence,
                    openAttempt, inbox, agentTool, AuthoritativeModelView.DISABLED);
        }

        boolean sameVerificationState(AdmissionSnapshot other) {
            return other != null
                    && enforced == other.enforced
                    && historyEpoch == other.historyEpoch
                    && loopFence == other.loopFence
                    && agentTool == other.agentTool
                    && Objects.equals(sessionId, other.sessionId)
                    && Objects.equals(level, other.level)
                    && Objects.equals(source, other.source)
                    && Objects.equals(tail, other.tail)
                    && Objects.equals(activeLoopId, other.activeLoopId)
                    && Objects.equals(openAttempt, other.openAttempt)
                    && Objects.equals(inbox, other.inbox)
                    && Objects.equals(modelView.hash(), other.modelView.hash());
        }

        static AdmissionSnapshot disabled(String sessionId, String level, String source) {
            return new AdmissionSnapshot(
                    false,
                    sessionId,
                    level.toLowerCase(java.util.Locale.ROOT),
                    source,
                    -1L,
                    TranscriptToken.DISABLED,
                    null,
                    -1L,
                    null,
                    InboxToken.DISABLED,
                    false,
                    AuthoritativeModelView.DISABLED);
        }
    }

    public record AuthoritativeModelView(
            List<Message> messages,
            List<String> canonicalMessages,
            String hash) {
        private static final AuthoritativeModelView DISABLED =
                new AuthoritativeModelView(List.of(), List.of(), "disabled");

        public AuthoritativeModelView {
            messages = List.copyOf(messages);
            canonicalMessages = List.copyOf(canonicalMessages);
            Objects.requireNonNull(hash, "hash");
            if (messages.size() != canonicalMessages.size()) {
                throw new IllegalArgumentException(
                        "authoritative messages and canonical JSON must stay aligned");
            }
        }
    }

    public record TranscriptToken(long messageId, long seqNo, String provenanceHash) {
        private static final TranscriptToken DISABLED = new TranscriptToken(-1L, -1L, "disabled");
    }

    public record InboxToken(long count, Long lastId, String fingerprint) {
        private static final InboxToken DISABLED = new InboxToken(-1L, null, "disabled");
    }

    public record AttemptToken(
            Long attemptId,
            String stepId,
            long historyEpoch,
            String originLoopId,
            long originFence,
            Long assistantMessageId,
            String assistantPayloadHash,
            long preIntentMaxMessageId,
            long preIntentMaxSeq,
            String manifestJson,
            String manifestHash,
            String replaySafety,
            String state,
            String executionLoopId,
            Long executionFence,
            String executionOwnerInstanceId,
            long executionGeneration,
            String claimRequestId,
            String resultBatchId,
            String postActionState) {
    }

    public record PreparedMessages(List<Message> compactInput, List<Message> preservedSuffix) {
        public PreparedMessages {
            compactInput = compactInput == null ? null : List.copyOf(compactInput);
            preservedSuffix = List.copyOf(preservedSuffix);
        }
    }
}
