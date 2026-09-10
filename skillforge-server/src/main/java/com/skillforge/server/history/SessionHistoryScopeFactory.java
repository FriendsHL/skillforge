package com.skillforge.server.history;

import com.skillforge.core.skill.SkillContext;

/** Resolves model-free current-Session History authority from Harness and durable state. */
public interface SessionHistoryScopeFactory {

    SessionHistoryAvailabilityPolicy.StoreReadiness readiness(SkillContext context);

    CurrentSessionHistoryScope resolve(SkillContext context, String expectedToolName);
}
