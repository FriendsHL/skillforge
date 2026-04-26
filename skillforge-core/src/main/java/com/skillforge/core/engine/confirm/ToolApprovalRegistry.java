package com.skillforge.core.engine.confirm;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot approval tokens for tools that must only execute after an explicit
 * human confirmation in the engine main loop.
 */
public class ToolApprovalRegistry {

    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();

    public String issue(String sessionId, String toolName, String toolUseId, Duration ttl) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        if (toolUseId == null || toolUseId.isBlank()) {
            throw new IllegalArgumentException("toolUseId is required");
        }
        Duration effectiveTtl = ttl != null && !ttl.isNegative() && !ttl.isZero()
                ? ttl : Duration.ofMinutes(5);
        String token = UUID.randomUUID().toString();
        approvals.put(token, new Approval(sessionId, toolName, toolUseId, Instant.now().plus(effectiveTtl)));
        return token;
    }

    public boolean consume(String token, String sessionId, String toolName, String toolUseId) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Approval approval = approvals.remove(token);
        if (approval == null || approval.expiresAt().isBefore(Instant.now())) {
            return false;
        }
        return approval.sessionId().equals(sessionId)
                && approval.toolName().equals(toolName)
                && approval.toolUseId().equals(toolUseId);
    }

    public int size() {
        return approvals.size();
    }

    private record Approval(String sessionId, String toolName, String toolUseId, Instant expiresAt) {
    }
}
