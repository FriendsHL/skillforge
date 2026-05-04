package com.skillforge.server.improve;

/**
 * Thrown when {@code SkillDraftService.approveDraft} attempts to persist a {@link
 * com.skillforge.server.entity.SkillEntity} whose {@code (COALESCE(owner_id, -1), name)}
 * tuple already exists, violating the {@code uq_t_skill_owner_name} unique index defined
 * in {@code V31__skill_control_plane.sql}.
 *
 * <p>This is a fallback for the dedupe path: similarity scoring (≥ {@link
 * SkillDraftService#DEDUP_HIGH}) is fuzzy and can let exact-name collisions slip through
 * when the LLM-generated draft happens to land on the same name as an existing skill for
 * the same owner. The unique constraint is the last line of defense.
 *
 * <p>The controller maps this to HTTP 409 Conflict with a structured body
 * ({@code code: "NAME_CONFLICT"}) so the FE can surface the duplicate to the operator
 * instead of showing the raw {@code DataIntegrityViolationException} stacktrace.
 */
public class SkillNameConflictException extends RuntimeException {

    private final String existingSkillName;

    public SkillNameConflictException(String message, String existingSkillName) {
        super(message);
        this.existingSkillName = existingSkillName;
    }

    public String getExistingSkillName() {
        return existingSkillName;
    }
}
