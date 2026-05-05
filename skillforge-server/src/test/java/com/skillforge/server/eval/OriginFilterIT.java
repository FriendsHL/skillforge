package com.skillforge.server.eval;

import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.ModelUsageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVAL-V2 M3a (b1) — V50 origin 字段 + 5 处过滤点的端到端 IT。
 *
 * <p>覆盖的过滤点（PRD §11 R3 / tech-design §2.2）：
 * <ol>
 *   <li>SessionRepository.findByUserIdAndParentSessionIdIsNullAndOriginOrderByUpdatedAtDesc
 *       — eval session 不出现在 production 列表</li>
 *   <li>LlmTraceRepository.findBySessionIdAndOriginOrderByStartedAtDesc /
 *       findByOriginOrderByStartedAtDesc — eval trace 不出现在 production trace 视图</li>
 *   <li>ModelUsageRepository.findDailyUsage / findUsageByModel / findUsageByAgent
 *       — eval usage 不计入 dashboard 聚合</li>
 *   <li>+5: CompactionService / startup recovery 的 origin 跳过逻辑由各自单元测试 + 本 IT
 *       共享的 fixture 间接验证（核心 5 处的 SQL/JPQL 行为已覆盖；compaction 的早返回
 *       属于业务分支不依赖 schema 行为，由 OriginCompactionAndRecoveryTest 单元 mock 验）</li>
 * </ol>
 *
 * <p>红绿验证：每个 case 都先写一对 production / eval 行，断言默认查询仅看到 production，
 * 显式 eval 过滤仅看到 eval。
 */
@DisplayName("EVAL-V2 M3a b1: origin filter (5 points) end-to-end IT")
class OriginFilterIT extends AbstractPostgresIT {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private LlmTraceRepository llmTraceRepository;

    @Autowired
    private ModelUsageRepository modelUsageRepository;

    @Autowired
    private AgentRepository agentRepository;

    @BeforeEach
    void cleanUp() {
        modelUsageRepository.deleteAll();
        llmTraceRepository.deleteAll();
        sessionRepository.deleteAll();
        agentRepository.deleteAll();
    }

    private SessionEntity buildSession(Long userId, Long agentId, String origin) {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(userId);
        s.setAgentId(agentId);
        s.setTitle("origin-it");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        s.setOrigin(origin);
        return sessionRepository.save(s);
    }

    private LlmTraceEntity buildTrace(String sessionId, String origin) {
        LlmTraceEntity t = new LlmTraceEntity();
        String traceId = UUID.randomUUID().toString();
        t.setTraceId(traceId);
        t.setRootTraceId(traceId);
        t.setSessionId(sessionId);
        t.setStartedAt(Instant.now());
        t.setSource("live");
        t.setStatus("ok");
        t.setCreatedAt(Instant.now());
        t.setOrigin(origin);
        return llmTraceRepository.save(t);
    }

    private AgentEntity buildAgent(String name) {
        AgentEntity a = new AgentEntity();
        a.setName(name);
        a.setStatus("active");
        return agentRepository.save(a);
    }

    private ModelUsageEntity buildUsage(Long userId, Long agentId, String sessionId,
                                        String modelId, int input, int output) {
        ModelUsageEntity u = new ModelUsageEntity();
        u.setUserId(userId);
        u.setAgentId(agentId);
        u.setSessionId(sessionId);
        u.setModelId(modelId);
        u.setInputTokens(input);
        u.setOutputTokens(output);
        return modelUsageRepository.save(u);
    }

    // -----------------------------------------------------------------------
    // Filter point #1: SessionRepository — listUserSessions default to production
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("#1 listUserSessions default 'production' excludes eval session")
    void filter1_listUserSessions_excludesEval() {
        SessionEntity prod = buildSession(7L, 10L, SessionEntity.ORIGIN_PRODUCTION);
        SessionEntity eval = buildSession(7L, 10L, SessionEntity.ORIGIN_EVAL);

        List<SessionEntity> productionList = sessionRepository
                .findByUserIdAndParentSessionIdIsNullAndOriginOrderByUpdatedAtDesc(
                        7L, SessionEntity.ORIGIN_PRODUCTION);
        List<SessionEntity> evalList = sessionRepository
                .findByUserIdAndParentSessionIdIsNullAndOriginOrderByUpdatedAtDesc(
                        7L, SessionEntity.ORIGIN_EVAL);

        assertThat(productionList).extracting(SessionEntity::getId).containsExactly(prod.getId());
        assertThat(evalList).extracting(SessionEntity::getId).containsExactly(eval.getId());
    }

    @Test
    @DisplayName("#1 default origin column on legacy/new sessions is 'production' (V50 DEFAULT)")
    void filter1_defaultOrigin_isProduction() {
        // Use entity default rather than explicit setter — replicates "ALTER TABLE ... DEFAULT" path.
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(99L);
        s.setAgentId(10L);
        s.setTitle("legacy-default");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        SessionEntity saved = sessionRepository.save(s);

        SessionEntity reloaded = sessionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getOrigin()).isEqualTo(SessionEntity.ORIGIN_PRODUCTION);
    }

    // -----------------------------------------------------------------------
    // Filter point #2: LlmTraceRepository — origin-filtered listings
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("#2 listTraces default 'production' excludes eval trace (sessionId path)")
    void filter2_listTracesBySession_excludesEval() {
        SessionEntity prodSession = buildSession(1L, 10L, SessionEntity.ORIGIN_PRODUCTION);
        SessionEntity evalSession = buildSession(1L, 10L, SessionEntity.ORIGIN_EVAL);

        // 同一 sessionId 下不会同时有两 origin（origin 跟 session 一致），但 trace 表
        // 是独立行的，本测试用两条 session 验 trace 的 origin 过滤独立可工作。
        LlmTraceEntity prodTrace = buildTrace(prodSession.getId(), SessionEntity.ORIGIN_PRODUCTION);
        LlmTraceEntity evalTrace = buildTrace(evalSession.getId(), SessionEntity.ORIGIN_EVAL);

        List<LlmTraceEntity> prodOnly = llmTraceRepository
                .findBySessionIdAndOriginOrderByStartedAtDesc(prodSession.getId(),
                        SessionEntity.ORIGIN_PRODUCTION);
        List<LlmTraceEntity> evalOnly = llmTraceRepository
                .findBySessionIdAndOriginOrderByStartedAtDesc(evalSession.getId(),
                        SessionEntity.ORIGIN_EVAL);

        assertThat(prodOnly).extracting(LlmTraceEntity::getTraceId)
                .containsExactly(prodTrace.getTraceId());
        assertThat(evalOnly).extracting(LlmTraceEntity::getTraceId)
                .containsExactly(evalTrace.getTraceId());

        // Cross-check: querying eval origin on a production session returns empty.
        List<LlmTraceEntity> none = llmTraceRepository
                .findBySessionIdAndOriginOrderByStartedAtDesc(prodSession.getId(),
                        SessionEntity.ORIGIN_EVAL);
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("#2 listTraces global path origin filter (no sessionId)")
    void filter2_listTracesGlobal_filtersByOrigin() {
        SessionEntity prodSession = buildSession(1L, 10L, SessionEntity.ORIGIN_PRODUCTION);
        SessionEntity evalSession = buildSession(1L, 10L, SessionEntity.ORIGIN_EVAL);

        buildTrace(prodSession.getId(), SessionEntity.ORIGIN_PRODUCTION);
        buildTrace(evalSession.getId(), SessionEntity.ORIGIN_EVAL);

        List<LlmTraceEntity> prod = llmTraceRepository
                .findByOriginOrderByStartedAtDesc(SessionEntity.ORIGIN_PRODUCTION);
        List<LlmTraceEntity> eval = llmTraceRepository
                .findByOriginOrderByStartedAtDesc(SessionEntity.ORIGIN_EVAL);

        assertThat(prod).hasSize(1);
        assertThat(prod).allMatch(t -> SessionEntity.ORIGIN_PRODUCTION.equals(t.getOrigin()));
        assertThat(eval).hasSize(1);
        assertThat(eval).allMatch(t -> SessionEntity.ORIGIN_EVAL.equals(t.getOrigin()));
    }

    // -----------------------------------------------------------------------
    // Filter point #3: ModelUsageRepository — dashboard JPQL JOIN session.origin
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("#3 findDailyUsage origin='production' excludes eval usage tokens AND keeps channel-only (sessionId NULL)")
    void filter3_findDailyUsage_excludesEvalKeepsChannelOnly() {
        AgentEntity agent = buildAgent("Production Agent");
        SessionEntity prodSession = buildSession(1L, agent.getId(), SessionEntity.ORIGIN_PRODUCTION);
        SessionEntity evalSession = buildSession(1L, agent.getId(), SessionEntity.ORIGIN_EVAL);

        buildUsage(1L, agent.getId(), prodSession.getId(), "claude-sonnet-4", 100, 50);
        buildUsage(1L, agent.getId(), evalSession.getId(), "claude-sonnet-4", 999, 999);
        // r2 fix WARNING-2 user-decision (b): channel-only path historically writes
        // ModelUsageEntity rows with sessionId=NULL. They must continue counting toward
        // production dashboard (R3 only excludes eval traffic, not channel-only).
        buildUsage(1L, agent.getId(), null, "claude-sonnet-4", 7, 3);

        LocalDateTime since = LocalDate_yesterdayAtMidnight();
        List<Object[]> prodRows = modelUsageRepository.findDailyUsage(since,
                SessionEntity.ORIGIN_PRODUCTION);
        List<Object[]> evalRows = modelUsageRepository.findDailyUsage(since,
                SessionEntity.ORIGIN_EVAL);

        // production: 100 + 7 = 107 input, 50 + 3 = 53 output (channel-only is included).
        assertThat(prodRows).hasSize(1);
        assertThat(((Number) prodRows.get(0)[1]).longValue()).isEqualTo(107);
        assertThat(((Number) prodRows.get(0)[2]).longValue()).isEqualTo(53);

        // eval: only the 999/999 row; channel-only does NOT leak into eval result.
        assertThat(evalRows).hasSize(1);
        assertThat(((Number) evalRows.get(0)[1]).longValue()).isEqualTo(999);
        assertThat(((Number) evalRows.get(0)[2]).longValue()).isEqualTo(999);
    }

    @Test
    @DisplayName("#3 findUsageByModel origin='production' excludes eval rows + keeps channel-only")
    void filter3_findUsageByModel_excludesEvalKeepsChannelOnly() {
        AgentEntity agent = buildAgent("a-1");
        SessionEntity prodSession = buildSession(1L, agent.getId(), SessionEntity.ORIGIN_PRODUCTION);
        SessionEntity evalSession = buildSession(1L, agent.getId(), SessionEntity.ORIGIN_EVAL);

        buildUsage(1L, agent.getId(), prodSession.getId(), "claude-sonnet-4", 10, 5);
        buildUsage(1L, agent.getId(), evalSession.getId(), "claude-sonnet-4", 1000, 500);
        // channel-only sessionId NULL row counts toward production aggregate.
        buildUsage(1L, agent.getId(), null, "claude-sonnet-4", 2, 1);

        List<Object[]> prodRows = modelUsageRepository.findUsageByModel(
                SessionEntity.ORIGIN_PRODUCTION);

        assertThat(prodRows).hasSize(1);
        assertThat(prodRows.get(0)[0]).isEqualTo("claude-sonnet-4");
        // 10+5 + 2+1 = 18 (channel-only included), eval 1500 excluded.
        assertThat(((Number) prodRows.get(0)[1]).longValue()).isEqualTo(18);
    }

    @Test
    @DisplayName("#3 findUsageByAgent origin='production' excludes eval rows + keeps channel-only")
    void filter3_findUsageByAgent_excludesEvalKeepsChannelOnly() {
        AgentEntity agent = buildAgent("Coder");
        SessionEntity prodSession = buildSession(1L, agent.getId(), SessionEntity.ORIGIN_PRODUCTION);
        SessionEntity evalSession = buildSession(1L, agent.getId(), SessionEntity.ORIGIN_EVAL);

        buildUsage(1L, agent.getId(), prodSession.getId(), "claude", 7, 3);
        buildUsage(1L, agent.getId(), evalSession.getId(), "claude", 100, 100);
        // channel-only sessionId NULL row counts toward production by-agent aggregate.
        buildUsage(1L, agent.getId(), null, "claude", 1, 1);

        List<Object[]> prodRows = modelUsageRepository.findUsageByAgent(
                SessionEntity.ORIGIN_PRODUCTION);

        assertThat(prodRows).hasSize(1);
        assertThat(prodRows.get(0)[0]).isEqualTo("Coder");
        // 7+3 + 1+1 = 12, eval 200 excluded.
        assertThat(((Number) prodRows.get(0)[1]).longValue()).isEqualTo(12);
    }

    // -----------------------------------------------------------------------
    // Filter point #4 + #5 (compaction / recovery early-return) are exercised by
    // OriginCompactionAndRecoveryTest unit tests under same sub-task.
    // -----------------------------------------------------------------------

    private static LocalDateTime LocalDate_yesterdayAtMidnight() {
        return java.time.LocalDate.now().minusDays(1).atStartOfDay();
    }
}
