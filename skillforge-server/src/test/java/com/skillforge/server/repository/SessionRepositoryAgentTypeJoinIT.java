package com.skillforge.server.repository;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM-AGENT-TYPING Phase 2 visibility follow-up (2026-05-18) —
 * end-to-end IT for {@code findByAgentTypeAndOriginOrderByUpdatedAtDesc}
 * against real PostgreSQL.
 *
 * <p>Verifies the cross-entity JPQL JOIN works on PG (the production DB)
 * and correctly:
 * <ul>
 *   <li>scopes by the OWNING agent's {@code agent_type}, not session.userId</li>
 *   <li>excludes child sessions ({@code parent_session_id IS NULL})</li>
 *   <li>filters by {@code origin} (eval rows excluded under production filter)</li>
 * </ul>
 */
@DisplayName("SessionRepository findByAgentTypeAndOriginOrderByUpdatedAtDesc IT")
class SessionRepositoryAgentTypeJoinIT extends AbstractPostgresIT {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private AgentRepository agentRepository;

    @BeforeEach
    void cleanUp() {
        sessionRepository.deleteAll();
        agentRepository.deleteAll();
    }

    private AgentEntity persistAgent(String name, String agentType) {
        AgentEntity a = new AgentEntity();
        a.setName(name);
        a.setStatus("active");
        a.setAgentType(agentType);
        return agentRepository.save(a);
    }

    private SessionEntity persistSession(Long userId, Long agentId, String origin,
                                          String parentSessionId) {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(userId);
        s.setAgentId(agentId);
        s.setTitle("agentType-join-it");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        s.setOrigin(origin);
        s.setParentSessionId(parentSessionId);
        return sessionRepository.save(s);
    }

    @Test
    @DisplayName("system-agent sessions returned regardless of userId; user-agent sessions excluded")
    void joinByAgentType_systemScope_includesAllUsersOfSystemAgent() {
        AgentEntity userAgent = persistAgent("regular-user-agent", "user");
        AgentEntity sysAgent1 = persistAgent("memory-curator", "system");
        AgentEntity sysAgent2 = persistAgent("session-annotator", "system");

        // 3 sessions on system agents owned by cron (userId=0) and 1 by some
        // other user (userId=99) — all should appear regardless of userId.
        SessionEntity sysS1 = persistSession(0L, sysAgent1.getId(),
                SessionEntity.ORIGIN_PRODUCTION, null);
        SessionEntity sysS2 = persistSession(0L, sysAgent2.getId(),
                SessionEntity.ORIGIN_PRODUCTION, null);
        SessionEntity sysS3 = persistSession(99L, sysAgent1.getId(),
                SessionEntity.ORIGIN_PRODUCTION, null);
        // user-agent session — should NOT appear.
        SessionEntity userS1 = persistSession(1L, userAgent.getId(),
                SessionEntity.ORIGIN_PRODUCTION, null);

        List<SessionEntity> systemList = sessionRepository
                .findByAgentTypeAndOriginOrderByUpdatedAtDesc(
                        "system", SessionEntity.ORIGIN_PRODUCTION);

        assertThat(systemList)
                .extracting(SessionEntity::getId)
                .containsExactlyInAnyOrder(sysS1.getId(), sysS2.getId(), sysS3.getId())
                .doesNotContain(userS1.getId());
    }

    @Test
    @DisplayName("eval-origin sessions filtered out under production-origin query")
    void joinByAgentType_originFilterApplied() {
        AgentEntity sysAgent = persistAgent("attribution-curator", "system");
        SessionEntity prod = persistSession(0L, sysAgent.getId(),
                SessionEntity.ORIGIN_PRODUCTION, null);
        SessionEntity eval = persistSession(0L, sysAgent.getId(),
                SessionEntity.ORIGIN_EVAL, null);

        List<SessionEntity> production = sessionRepository
                .findByAgentTypeAndOriginOrderByUpdatedAtDesc(
                        "system", SessionEntity.ORIGIN_PRODUCTION);
        List<SessionEntity> evalList = sessionRepository
                .findByAgentTypeAndOriginOrderByUpdatedAtDesc(
                        "system", SessionEntity.ORIGIN_EVAL);

        assertThat(production).extracting(SessionEntity::getId).containsExactly(prod.getId());
        assertThat(evalList).extracting(SessionEntity::getId).containsExactly(eval.getId());
    }

    @Test
    @DisplayName("child (sub-agent) sessions excluded — parentSessionId IS NULL guard")
    void joinByAgentType_excludesChildSessions() {
        AgentEntity sysAgent = persistAgent("metrics-collector", "system");
        SessionEntity top = persistSession(0L, sysAgent.getId(),
                SessionEntity.ORIGIN_PRODUCTION, null);
        SessionEntity child = persistSession(0L, sysAgent.getId(),
                SessionEntity.ORIGIN_PRODUCTION, top.getId());

        List<SessionEntity> result = sessionRepository
                .findByAgentTypeAndOriginOrderByUpdatedAtDesc(
                        "system", SessionEntity.ORIGIN_PRODUCTION);

        assertThat(result)
                .extracting(SessionEntity::getId)
                .containsExactly(top.getId())
                .doesNotContain(child.getId());
    }

    @Test
    @DisplayName("returns empty when no agent of the requested type exists")
    void joinByAgentType_emptyWhenNoMatchingType() {
        AgentEntity userAgent = persistAgent("user-only-agent", "user");
        persistSession(1L, userAgent.getId(), SessionEntity.ORIGIN_PRODUCTION, null);

        List<SessionEntity> result = sessionRepository
                .findByAgentTypeAndOriginOrderByUpdatedAtDesc(
                        "system", SessionEntity.ORIGIN_PRODUCTION);

        assertThat(result).isEmpty();
    }
}
