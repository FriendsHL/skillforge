package com.skillforge.server.repository;

import com.skillforge.server.entity.ScheduledTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, Long> {

    /** List a user's scheduled tasks newest-first. */
    List<ScheduledTaskEntity> findByCreatorUserIdOrderByIdDesc(Long creatorUserId);

    /**
     * BE-2 startup recovery: register every enabled task on application boot
     * (see brief §3 INV-1). Disabled tasks are deliberately not loaded.
     */
    List<ScheduledTaskEntity> findByEnabledTrue();

    /**
     * SYSTEM-AGENT-TYPING Phase 2.1: batched lookup of every scheduled task for
     * the supplied set of agent ids. Used by {@code SystemAgentMonitorService}
     * to resolve {@code cron_expr} / {@code last_fire_at} for all system agents
     * in one query (replaces a per-agent N+1 fetch). Empty collection returns
     * an empty list rather than scanning the whole table.
     *
     * <p>An agent may have ≥0 scheduled tasks; the monitor card uses the first
     * non-disabled task it finds (see service for the policy), so call sites
     * that need just one row should sort + take {@code findFirst()} after
     * grouping by {@code agentId}.
     */
    List<ScheduledTaskEntity> findByAgentIdIn(Collection<Long> agentIds);
}
