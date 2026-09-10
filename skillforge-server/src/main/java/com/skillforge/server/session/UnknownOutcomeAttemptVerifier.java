package com.skillforge.server.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.durability.ReplaySafety;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Revalidates the immutable assistant/manifest evidence before manual resolution can close it. */
@Component
final class UnknownOutcomeAttemptVerifier {

    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion", "calls", "replaySafety");
    private static final Set<String> CALL_FIELDS = Set.of(
            "providerOrdinal", "toolUseId", "toolName", "input", "replaySafety");

    private final SessionMessageRepository messageRepository;
    private final PersistedMessageCodec messageCodec;
    private final ObjectMapper objectMapper;

    UnknownOutcomeAttemptVerifier(
            SessionMessageRepository messageRepository,
            PersistedMessageCodec messageCodec,
            ObjectMapper objectMapper) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    List<ManifestCall> verify(SessionToolAttemptEntity attempt) {
        try {
            if (!sha256(attempt.getManifestJson()).equals(attempt.getManifestHash())) {
                throw invalid();
            }
            SessionMessageEntity assistantRow = messageRepository
                    .findById(attempt.getAssistantMessageId())
                    .filter(row -> attempt.getSessionId().equals(row.getSessionId()))
                    .orElseThrow(UnknownOutcomeAttemptVerifier::invalid);
            SessionMessageEntity tail = messageRepository
                    .findTopBySessionIdOrderBySeqNoDesc(attempt.getSessionId())
                    .orElseThrow(UnknownOutcomeAttemptVerifier::invalid);
            if (assistantRow.getPrunedAt() != null
                    || !Objects.equals(assistantRow.getId(), tail.getId())
                    || assistantRow.getSeqNo() != tail.getSeqNo()
                    || !"assistant".equals(assistantRow.getRole())) {
                throw invalid();
            }
            PersistedMessageCodec.PersistedMessage decoded = messageCodec.decodeRow(
                    new PersistedMessageCodec.EncodedRow(
                            assistantRow.getRole(), assistantRow.getContentJson(),
                            assistantRow.getReasoningContent(), assistantRow.getMsgType(),
                            assistantRow.getMessageType(), assistantRow.getControlId(),
                            assistantRow.getAnsweredAt(), assistantRow.getMetadataJson(),
                            assistantRow.getTraceId()));
            Message assistant = decoded.message();
            if (assistant.getRole() != Message.Role.ASSISTANT
                    || !sha256(messageCodec.writeMessage(assistant))
                            .equals(attempt.getAssistantPayloadHash())) {
                throw invalid();
            }
            List<ManifestCall> manifest = decodeManifest(attempt);
            List<ToolUseBlock> toolUses = assistant.getToolUseBlocks();
            if (toolUses.size() != manifest.size()) throw invalid();
            for (int ordinal = 0; ordinal < manifest.size(); ordinal++) {
                ManifestCall expected = manifest.get(ordinal);
                ToolUseBlock actual = toolUses.get(ordinal);
                if (!expected.toolUseId().equals(actual.getId())
                        || !expected.toolName().equals(actual.getName())
                        || !expected.input().equals(objectMapper.valueToTree(actual.getInput()))) {
                    throw invalid();
                }
            }
            return manifest;
        } catch (UnknownOutcomeResolutionException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException corruptedPayload) {
            throw invalid();
        }
    }

    private List<ManifestCall> decodeManifest(SessionToolAttemptEntity attempt) {
        try {
            JsonNode root = objectMapper.readTree(attempt.getManifestJson());
            if (root == null || !root.isObject() || !exactFields(root, MANIFEST_FIELDS)
                    || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").intValue() != 1
                    || !root.path("calls").isArray() || root.path("calls").isEmpty()
                    || !root.path("replaySafety").isTextual()
                    || !attempt.getReplaySafety().equals(root.path("replaySafety").textValue())) {
                throw invalid();
            }
            ReplaySafety.valueOf(root.path("replaySafety").textValue());
            List<ManifestCall> calls = new ArrayList<>(root.path("calls").size());
            for (int ordinal = 0; ordinal < root.path("calls").size(); ordinal++) {
                JsonNode call = root.path("calls").get(ordinal);
                if (!call.isObject() || !exactFields(call, CALL_FIELDS)
                        || !call.path("providerOrdinal").isIntegralNumber()
                        || call.path("providerOrdinal").intValue() != ordinal
                        || !call.path("toolUseId").isTextual()
                        || call.path("toolUseId").textValue().isBlank()
                        || !call.path("toolName").isTextual()
                        || call.path("toolName").textValue().isBlank()
                        || !call.path("input").isObject()
                        || !call.path("replaySafety").isTextual()) {
                    throw invalid();
                }
                ReplaySafety.valueOf(call.path("replaySafety").textValue());
                calls.add(new ManifestCall(
                        ordinal,
                        call.path("toolUseId").textValue(),
                        call.path("toolName").textValue(),
                        call.path("input")));
            }
            return List.copyOf(calls);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static boolean exactFields(JsonNode value, Set<String> expected) {
        Set<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static UnknownOutcomeResolutionException invalid() {
        return new UnknownOutcomeResolutionException(
                UnknownOutcomeResolutionException.Code.RESOLUTION_NOT_AVAILABLE,
                "Unknown outcome resolution is not available");
    }

    record ManifestCall(
            int providerOrdinal, String toolUseId, String toolName, JsonNode input) {
    }
}
