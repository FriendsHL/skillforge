package com.skillforge.core.engine.confirm;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory whitelist of previously approved {@code (rootSessionId, toolName, installTarget)}
 * triples. Implements "Plan C" inheritance semantics:
 * SubAgent / TeamCreate children share their root session's cache by writing and reading
 * under the same rootSessionId, but only for the identical {@code (tool, target)} combo.
 *
 * <p>Sentinel rule: {@code installTarget == "*"} is the unparseable / multi-install fallback
 * from {@link InstallTargetParser}; cache refuses to read or write that sentinel, forcing
 * every such invocation back through the prompt flow.
 */
public class SessionConfirmCache {

    private static final String SEP = "::";

    /** rootSessionId → set of composed {@code "<tool>::<target>"} entries. */
    private final ConcurrentMap<String, Set<String>> approved = new ConcurrentHashMap<>();

    public boolean isApproved(String rootSessionId, String toolName, String installTarget) {
        if (rootSessionId == null || toolName == null || installTarget == null) {
            return false;
        }
        if ("*".equals(installTarget)) {
            return false;
        }
        Set<String> set = approved.get(rootSessionId);
        return set != null && set.contains(compose(toolName, installTarget));
    }

    public void approve(String rootSessionId, String toolName, String installTarget) {
        if (rootSessionId == null || toolName == null || installTarget == null) {
            return;
        }
        if ("*".equals(installTarget)) {
            return;
        }
        approved.computeIfAbsent(rootSessionId, k -> ConcurrentHashMap.newKeySet())
                .add(compose(toolName, installTarget));
    }

    /** Drop all approvals for a root session; called when that root session's loop ends. */
    public void clear(String rootSessionId) {
        if (rootSessionId == null) return;
        approved.remove(rootSessionId);
    }

    private static String compose(String tool, String target) {
        return tool + SEP + target;
    }
}
