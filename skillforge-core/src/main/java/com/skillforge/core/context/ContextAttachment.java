package com.skillforge.core.context;

import com.skillforge.core.compact.TokenEstimator;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * One typed, traceable context input before provider-compatible rendering.
 */
public record ContextAttachment(
        String id,
        ContextKind kind,
        PromptSourceType sourceType,
        PromptAuthority authority,
        PromptTrustLevel trustLevel,
        PromptPlacement placement,
        ContextLifecycle lifecycle,
        PromptCompactPolicy compactPolicy,
        String content,
        boolean stable,
        boolean cacheable,
        int estimatedTokens,
        Instant expiresAt,
        List<String> sourceIds,
        String contentHash) {

    private static final String MEMORY_HEADING = "## User Memories\n\n";

    public ContextAttachment {
        id = Objects.requireNonNull(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        authority = Objects.requireNonNull(authority, "authority");
        trustLevel = Objects.requireNonNull(trustLevel, "trustLevel");
        placement = Objects.requireNonNull(placement, "placement");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        compactPolicy = Objects.requireNonNull(compactPolicy, "compactPolicy");
        content = content == null ? "" : content;
        estimatedTokens = Math.max(0, estimatedTokens);
        sourceIds = sourceIds == null ? List.of() : sourceIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        contentHash = contentHash == null ? "" : contentHash;
    }

    public static ContextAttachment instruction(
            String id, PromptSourceType sourceType, PromptPlacement placement,
            String content, List<String> sourceIds) {
        boolean stable = placement == PromptPlacement.STABLE_SYSTEM;
        boolean platformInstruction = sourceType == PromptSourceType.GLOBAL;
        return create(id, ContextKind.INSTRUCTION, sourceType,
                platformInstruction ? PromptAuthority.PLATFORM : PromptAuthority.AGENT_CONFIG,
                platformInstruction
                        ? PromptTrustLevel.TRUSTED_INSTRUCTION
                        : PromptTrustLevel.CONFIGURED_INSTRUCTION,
                placement,
                stable ? ContextLifecycle.SESSION : ContextLifecycle.REQUEST_ONLY,
                stable ? PromptCompactPolicy.RELOAD_BY_ID : PromptCompactPolicy.DROP_ON_COMPACT,
                content, stable, stable, sourceIds);
    }

    public static ContextAttachment runtime(String id, String content, List<String> sourceIds) {
        return create(id, ContextKind.RUNTIME_CONTEXT, PromptSourceType.RUNTIME_CONTEXT,
                PromptAuthority.PLATFORM, PromptTrustLevel.TRUSTED_RUNTIME_DATA,
                PromptPlacement.DYNAMIC_SYSTEM, ContextLifecycle.REQUEST_ONLY,
                PromptCompactPolicy.DROP_ON_COMPACT, content, false, false, sourceIds);
    }

    public static ContextAttachment sessionContext(
            String content, Long userId, String sessionId) {
        List<String> sourceIds = java.util.stream.Stream.of(
                        userId == null ? null : "user:" + userId,
                        sessionId == null ? null : "session:" + sanitizeSourceId(sessionId))
                .filter(Objects::nonNull)
                .toList();
        return create("session_context", ContextKind.RUNTIME_CONTEXT,
                PromptSourceType.SESSION_CONTEXT, PromptAuthority.PLATFORM,
                PromptTrustLevel.TRUSTED_RUNTIME_DATA, PromptPlacement.DYNAMIC_SYSTEM,
                ContextLifecycle.SESSION, PromptCompactPolicy.RELOAD_BY_ID,
                content, false, false, sourceIds);
    }

    public static ContextAttachment userMemories(
            String content, Collection<Long> memoryIds) {
        return userMemories(content, memoryIds, false);
    }

    public static ContextAttachment userMemories(
            String content, Collection<Long> memoryIds, boolean bounded) {
        List<String> sourceIds = memoryIds == null ? List.of() : memoryIds.stream()
                .filter(Objects::nonNull)
                .sorted()
                .map(id -> "memory:" + id)
                .toList();
        String rendered = bounded ? boundMemoryFragment(content) : content;
        return create("user_memories", ContextKind.MEMORY_CONTENT,
                PromptSourceType.MEMORY, PromptAuthority.USER_CONTEXT,
                PromptTrustLevel.STORED_DATA, PromptPlacement.DYNAMIC_SYSTEM,
                ContextLifecycle.SESSION,
                sourceIds.isEmpty()
                        ? PromptCompactPolicy.SUMMARIZE
                        : PromptCompactPolicy.RELOAD_BY_ID,
                rendered, false, false, sourceIds);
    }

    private static String boundMemoryFragment(String content) {
        String value = content == null ? "" : content;
        int heading = value.indexOf(MEMORY_HEADING);
        if (heading < 0) {
            return LowTrustContextBoundary.wrap(PromptSourceType.MEMORY, value);
        }
        int body = heading + MEMORY_HEADING.length();
        return value.substring(0, body)
                + LowTrustContextBoundary.wrap(PromptSourceType.MEMORY, value.substring(body));
    }

    public static ContextAttachment lowTrustData(
            String id, PromptSourceType sourceType, String content,
            Collection<String> sourceIds, PromptPlacement placement,
            PromptCompactPolicy compactPolicy) {
        if (sourceType != PromptSourceType.MEMORY
                && sourceType != PromptSourceType.RAG
                && sourceType != PromptSourceType.WEB
                && sourceType != PromptSourceType.FILE
                && sourceType != PromptSourceType.SUBAGENT) {
            throw new IllegalArgumentException("Not a low-trust source: " + sourceType);
        }
        List<String> normalizedIds = sourceIds == null ? List.of() : sourceIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .sorted()
                .toList();
        PromptAuthority authority = sourceType == PromptSourceType.WEB
                ? PromptAuthority.EXTERNAL_CONTEXT : PromptAuthority.USER_CONTEXT;
        return create(id, ContextKind.EXTERNAL_DATA, sourceType, authority,
                LowTrustContextBoundary.trustFor(sourceType), placement,
                ContextLifecycle.REQUEST_ONLY, compactPolicy,
                LowTrustContextBoundary.wrap(sourceType, content),
                false, false, normalizedIds);
    }

    public static ContextAttachment create(
            String id, ContextKind kind, PromptSourceType sourceType,
            PromptAuthority authority, PromptTrustLevel trustLevel, PromptPlacement placement,
            ContextLifecycle lifecycle, PromptCompactPolicy compactPolicy,
            String content, boolean stable, boolean cacheable, List<String> sourceIds) {
        String safeContent = content == null ? "" : content;
        return new ContextAttachment(id, kind, sourceType, authority, trustLevel, placement,
                lifecycle, compactPolicy, safeContent, stable, cacheable,
                TokenEstimator.estimateString(safeContent), null, sourceIds,
                PromptObservationHashes.sha256(safeContent));
    }

    private static String sanitizeSourceId(String value) {
        return value.replaceAll("[\\r\\n\\t]", " ").trim();
    }
}
