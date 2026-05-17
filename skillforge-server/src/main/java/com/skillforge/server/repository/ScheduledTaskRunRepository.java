package com.skillforge.server.repository;

import com.skillforge.server.entity.ScheduledTaskRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRunEntity, Long> {

    /**
     * Run history for a single task, newest-first. Pageable lets the controller
     * plumb {@code limit} / {@code offset} from the query string.
     */
    List<ScheduledTaskRunEntity> findByTaskIdOrderByTriggeredAtDesc(Long taskId, Pageable pageable);

    /**
     * SYSTEM-AGENT-TYPING Phase 2.1: latest run for a single task — used by
     * {@code SystemAgentMonitorService} to surface {@code last_run_status} on the
     * observability cards. Returned {@link Optional} is empty when the task has
     * never fired (post-bootstrap, pre-cron).
     */
    Optional<ScheduledTaskRunEntity> findFirstByTaskIdOrderByTriggeredAtDesc(Long taskId);

    /**
     * SYSTEM-AGENT-TYPING Phase 2.1: batched 7-day trigger count across a
     * fixed set of system-agent task ids. Single query in place of N
     * per-task COUNTs — keeps the monitor endpoint O(1) DB hops regardless
     * of how many system agents are listed (today: 5).
     */
    long countByTaskIdInAndTriggeredAtAfter(Collection<Long> taskIds, Instant since);
}
