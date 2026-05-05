package com.skillforge.server.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVAL-V2 M3a (b1) — spawn 路径上 origin 复制的 IT。
 *
 * <p>覆盖 plan §4 三处 spawn 复制中的核心路径 {@link SessionService#createSubSession} ——
 * 这是 SubAgentRegistry / RealSubAgentExecutor / CollabRunService 等所有"派生 child"路径
 * 的 single source of truth。child.origin 必须等于 parent.origin，正是 origin 隔离的关键
 * 不变量：eval 任务派出来的 child 也必须 origin='eval'，否则 trace 会进 production dashboard。
 *
 * <p>{@code CollabRunService.spawnMember} 与 {@code CompactionService.createBranchFromCheckpoint}
 * 也调用 {@code createSubSession}（前者）或采用相同的"复制父 origin"代码模式（后者），
 * 由各自的 unit test (mocked) 验证它们额外的 setOrigin 调用；本 IT 验证最关键的"新 child
 * 行写到 DB 后 origin 字段确实落库等于 parent" 这一持久化层保证。
 */
@DisplayName("EVAL-V2 M3a b1: spawn child session inherits parent origin")
class SpawnChildOriginInheritIT extends AbstractPostgresIT {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionMessageRepository sessionMessageRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionMessageRepository.deleteAll();
        sessionRepository.deleteAll();
        agentRepository.deleteAll();
        sessionService = new SessionService(
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(),
                new ObjectMapper(),
                transactionManager
        );
    }

    private SessionEntity newParent(Long userId, String origin) {
        SessionEntity parent = new SessionEntity();
        parent.setId(UUID.randomUUID().toString());
        parent.setUserId(userId);
        parent.setAgentId(10L);
        parent.setTitle("parent-" + origin);
        parent.setStatus("active");
        parent.setRuntimeStatus("idle");
        parent.setDepth(0);
        parent.setOrigin(origin);
        return sessionRepository.save(parent);
    }

    @Test
    @DisplayName("createSubSession from production parent → child origin = production")
    void createSubSession_productionParent_childInheritsProduction() {
        SessionEntity parent = newParent(1L, SessionEntity.ORIGIN_PRODUCTION);

        SessionEntity child = sessionService.createSubSession(parent, 99L,
                UUID.randomUUID().toString());

        // Reload from DB to confirm column persisted, not just in-memory object.
        SessionEntity reloaded = sessionRepository.findById(child.getId()).orElseThrow();
        assertThat(reloaded.getOrigin()).isEqualTo(SessionEntity.ORIGIN_PRODUCTION);
        assertThat(reloaded.getParentSessionId()).isEqualTo(parent.getId());
        assertThat(reloaded.getDepth()).isEqualTo(1);
    }

    @Test
    @DisplayName("createSubSession from eval parent → child origin = eval (does NOT default to production)")
    void createSubSession_evalParent_childInheritsEval() {
        SessionEntity parent = newParent(1L, SessionEntity.ORIGIN_EVAL);

        SessionEntity child = sessionService.createSubSession(parent, 99L,
                UUID.randomUUID().toString());

        SessionEntity reloaded = sessionRepository.findById(child.getId()).orElseThrow();
        assertThat(reloaded.getOrigin()).isEqualTo(SessionEntity.ORIGIN_EVAL);
        assertThat(reloaded.getParentSessionId()).isEqualTo(parent.getId());
    }

    @Test
    @DisplayName("recursive eval spawning: grand-child of eval parent is also eval")
    void createSubSession_recursive_keepsEvalOrigin() {
        SessionEntity grandparent = newParent(1L, SessionEntity.ORIGIN_EVAL);

        SessionEntity child = sessionService.createSubSession(grandparent, 99L,
                UUID.randomUUID().toString());
        SessionEntity grandchild = sessionService.createSubSession(child, 88L,
                UUID.randomUUID().toString());

        SessionEntity reloaded = sessionRepository.findById(grandchild.getId()).orElseThrow();
        assertThat(reloaded.getOrigin()).isEqualTo(SessionEntity.ORIGIN_EVAL);
        assertThat(reloaded.getDepth()).isEqualTo(2);
    }
}
