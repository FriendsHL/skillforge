package com.skillforge.server.repository;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SessionRepository integration tests")
class SessionRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private SessionRepository sessionRepository;

    @BeforeEach
    void cleanUp() {
        sessionRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private SessionEntity buildSession(Long userId, Long agentId) {
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setAgentId(agentId);
        session.setTitle("Test session");
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        return session;
    }

    // -----------------------------------------------------------------------
    // Basic CRUD
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("save_and_findById: persists entity and retrieves it by id")
    void save_and_findById_persistsAndRetrieves() {
        SessionEntity session = buildSession(1L, 10L);
        String id = session.getId();

        sessionRepository.save(session);

        Optional<SessionEntity> found = sessionRepository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(1L);
        assertThat(found.get().getAgentId()).isEqualTo(10L);
        assertThat(found.get().getRuntimeStatus()).isEqualTo("idle");
        assertThat(found.get().getStatus()).isEqualTo("active");
    }

    // -----------------------------------------------------------------------
    // findByUserIdOrderByUpdatedAtDesc
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByUserId: returns only sessions belonging to that user, ordered by updatedAt desc")
    void findByUserId_returnsOnlyMatchingSessions() {
        sessionRepository.save(buildSession(42L, 10L));
        sessionRepository.save(buildSession(42L, 10L));
        sessionRepository.save(buildSession(99L, 10L)); // different user

        List<SessionEntity> results = sessionRepository.findByUserIdOrderByUpdatedAtDesc(42L);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(s -> s.getUserId().equals(42L));
    }

    // -----------------------------------------------------------------------
    // findByUserIdAndParentSessionIdIsNull
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByUserIdAndParentSessionIdIsNull: filters out child sessions")
    void findByUserIdAndParentNull_filtersChildSessions() {
        SessionEntity root = buildSession(7L, 10L);
        SessionEntity child = buildSession(7L, 10L);
        child.setParentSessionId(root.getId());

        sessionRepository.save(root);
        sessionRepository.save(child);

        List<SessionEntity> topLevel =
                sessionRepository.findByUserIdAndParentSessionIdIsNullOrderByUpdatedAtDesc(7L);

        assertThat(topLevel).hasSize(1);
        assertThat(topLevel.get(0).getId()).isEqualTo(root.getId());
    }

    // -----------------------------------------------------------------------
    // findByAgentId + countByAgentId
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByAgentId: returns sessions for a given agent")
    void findByAgentId_returnsMatchingSessions() {
        sessionRepository.save(buildSession(1L, 20L));
        sessionRepository.save(buildSession(2L, 20L));
        sessionRepository.save(buildSession(3L, 99L)); // different agent

        List<SessionEntity> results = sessionRepository.findByAgentId(20L);
        long count = sessionRepository.countByAgentId(20L);

        assertThat(results).hasSize(2);
        assertThat(count).isEqualTo(2);
        assertThat(results).allMatch(s -> s.getAgentId().equals(20L));
    }

    // -----------------------------------------------------------------------
    // findByParentSessionId + countByParentSessionIdAndRuntimeStatus
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByParentSessionId: returns child sessions with correct runtimeStatus count")
    void findByParentSessionId_returnsChildrenAndCountsByStatus() {
        SessionEntity parent = buildSession(1L, 10L);
        sessionRepository.save(parent);

        SessionEntity child1 = buildSession(1L, 10L);
        child1.setParentSessionId(parent.getId());
        child1.setRuntimeStatus("idle");

        SessionEntity child2 = buildSession(1L, 10L);
        child2.setParentSessionId(parent.getId());
        child2.setRuntimeStatus("running");

        sessionRepository.save(child1);
        sessionRepository.save(child2);

        List<SessionEntity> children = sessionRepository.findByParentSessionId(parent.getId());
        long idleCount = sessionRepository.countByParentSessionIdAndRuntimeStatus(parent.getId(), "idle");
        long runningCount = sessionRepository.countByParentSessionIdAndRuntimeStatus(parent.getId(), "running");

        assertThat(children).hasSize(2);
        assertThat(idleCount).isEqualTo(1);
        assertThat(runningCount).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // findByCollabRunId + countByCollabRunId + findByCollabRunIdAndRuntimeStatus
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("collabRunId queries: finds sessions by collabRunId and filters by runtimeStatus")
    void collabRunId_queries_workCorrectly() {
        String runId = UUID.randomUUID().toString();

        SessionEntity s1 = buildSession(1L, 10L);
        s1.setCollabRunId(runId);
        s1.setRuntimeStatus("idle");

        SessionEntity s2 = buildSession(2L, 10L);
        s2.setCollabRunId(runId);
        s2.setRuntimeStatus("running");

        SessionEntity s3 = buildSession(3L, 10L); // different run

        sessionRepository.save(s1);
        sessionRepository.save(s2);
        sessionRepository.save(s3);

        List<SessionEntity> all = sessionRepository.findByCollabRunId(runId);
        long total = sessionRepository.countByCollabRunId(runId);
        List<SessionEntity> running = sessionRepository.findByCollabRunIdAndRuntimeStatus(runId, "running");

        assertThat(all).hasSize(2);
        assertThat(total).isEqualTo(2);
        assertThat(running).hasSize(1);
        assertThat(running.get(0).getRuntimeStatus()).isEqualTo("running");
    }

    // -----------------------------------------------------------------------
    // findByLastUserMessageAtIsNull
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByLastUserMessageAtIsNull: returns only sessions with null lastUserMessageAt")
    void findByLastUserMessageAtIsNull_returnsOnlyNullRows() {
        SessionEntity withNull = buildSession(1L, 10L);
        // lastUserMessageAt left null by default

        SessionEntity withValue = buildSession(2L, 10L);
        withValue.setLastUserMessageAt(java.time.Instant.now());

        sessionRepository.save(withNull);
        sessionRepository.save(withValue);

        List<SessionEntity> nullRows = sessionRepository.findByLastUserMessageAtIsNull();

        assertThat(nullRows).hasSize(1);
        assertThat(nullRows.get(0).getId()).isEqualTo(withNull.getId());
    }
}
