package com.skillforge.server.improve;

/**
 * Plan r2 §3 — thrown by {@code SkillDraftService.approveDraft} when artifact write or
 * validation fails. The transactional approveDraft rethrows it after best-effort cleanup,
 * causing the surrounding transaction to roll back (so DB / registry stay clean while the
 * caller surfaces the underlying error to the user).
 */
public class SkillApprovalException extends RuntimeException {
    public SkillApprovalException(String message, Throwable cause) {
        super(message, cause);
    }
}
