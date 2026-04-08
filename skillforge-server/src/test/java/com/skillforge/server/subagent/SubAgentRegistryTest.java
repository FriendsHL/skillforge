package com.skillforge.server.subagent;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SubAgentPendingResultEntity;
import com.skillforge.server.entity.SubAgentRunEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SubAgentPendingResultRepository;
import com.skillforge.server.repository.SubAgentRunRepository;
import com.skillforge.server.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试 SubAgentRegistry 的核心分支:
 *  - depth 上限
 *  - 每父并发子上限
 *  - 子结束时:enqueue + (父 idle ⇒ 自动 chatAsync 唤醒)
 *  - 子结束时:父 running ⇒ 只 enqueue 不抢跑
 *
 * 持久化仓库用内存 HashMap 打桩模拟(不拉真实 JPA),这样依然是纯单元测试。
 */
class SubAgentRegistryTest {

    private SessionRepository sessionRepository;
    private SubAgentRunRepository runRepository;
    private SubAgentPendingResultRepository pendingRepository;
    private ChatService chatService;
    private ObjectProvider<ChatService> chatServiceProvider;
    private SubAgentRegistry registry;

    // 内存 storage
    private final Map<String, SubAgentRunEntity> runStore = new HashMap<>();
    private final Map<Long, SubAgentPendingResultEntity> pendingStore = new HashMap<>();
    private final AtomicLong pendingIdSeq = new AtomicLong(0);

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        runRepository = mock(SubAgentRunRepository.class);
        pendingRepository = mock(SubAgentPendingResultRepository.class);
        chatService = mock(ChatService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(chatService);
        chatServiceProvider = provider;

        // ---- runRepository stub ----
        when(runRepository.save(any(SubAgentRunEntity.class))).thenAnswer(inv -> {
            SubAgentRunEntity e = inv.getArgument(0);
            runStore.put(e.getRunId(), e);
            return e;
        });
        when(runRepository.findById(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return Optional.ofNullable(runStore.get(id));
        });
        when(runRepository.findByParentSessionId(anyString())).thenAnswer(inv -> {
            String pid = inv.getArgument(0);
            List<SubAgentRunEntity> out = new ArrayList<>();
            for (SubAgentRunEntity e : runStore.values()) {
                if (pid.equals(e.getParentSessionId())) out.add(e);
            }
            return out;
        });

        // ---- pendingRepository stub ----
        when(pendingRepository.save(any(SubAgentPendingResultEntity.class))).thenAnswer(inv -> {
            SubAgentPendingResultEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(pendingIdSeq.incrementAndGet());
            }
            pendingStore.put(e.getId(), e);
            return e;
        });
        when(pendingRepository.findByParentSessionIdOrderByIdAsc(anyString())).thenAnswer(inv -> {
            String pid = inv.getArgument(0);
            List<SubAgentPendingResultEntity> out = new ArrayList<>();
            for (SubAgentPendingResultEntity e : pendingStore.values()) {
                if (pid.equals(e.getParentSessionId())) out.add(e);
            }
            out.sort(Comparator.comparing(SubAgentPendingResultEntity::getId));
            return out;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            Iterable<?> rows = inv.getArgument(0);
            for (Object r : rows) {
                SubAgentPendingResultEntity e = (SubAgentPendingResultEntity) r;
                pendingStore.remove(e.getId());
            }
            return null;
        }).when(pendingRepository).deleteAll(any(Iterable.class));

        registry = new SubAgentRegistry(sessionRepository, runRepository, pendingRepository, chatServiceProvider);
    }

    private SessionEntity session(String id, int depth, String runtimeStatus, String parentId) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(1L);
        s.setDepth(depth);
        s.setRuntimeStatus(runtimeStatus);
        s.setParentSessionId(parentId);
        return s;
    }

    // ============ depth / 并发 上限 ============

    @Test
    void registerRun_rejects_when_depth_at_limit() {
        SessionEntity parent = session("p1", SubAgentRegistry.MAX_DEPTH, "running", null);
        assertThatThrownBy(() -> registry.registerRun(parent, 2L, "child", "do stuff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("depth limit");
    }

    @Test
    void registerRun_rejects_when_active_children_at_limit() {
        SessionEntity parent = session("p2", 0, "running", null);
        when(sessionRepository.countByParentSessionIdAndRuntimeStatus("p2", "running"))
                .thenReturn((long) SubAgentRegistry.MAX_ACTIVE_CHILDREN_PER_PARENT);
        assertThatThrownBy(() -> registry.registerRun(parent, 2L, "child", "do stuff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max");
    }

    @Test
    void registerRun_succeeds_under_limits_and_assigns_runId() {
        SessionEntity parent = session("p3", 0, "running", null);
        when(sessionRepository.countByParentSessionIdAndRuntimeStatus("p3", "running")).thenReturn(0L);

        SubAgentRegistry.SubAgentRun run = registry.registerRun(parent, 9L, "researcher", "task body");

        assertThat(run.runId).isNotBlank();
        assertThat(run.parentSessionId).isEqualTo("p3");
        assertThat(run.childAgentId).isEqualTo(9L);
        assertThat(run.status).isEqualTo("RUNNING");
        assertThat(registry.listRunsForParent("p3")).hasSize(1);
    }

    // ============ 子完成回调:父 idle 时自动 resume ============

    @Test
    void childFinished_with_parent_idle_enqueues_and_resumes_parent() {
        // 先注册一个 run,attach 子 session id
        SessionEntity parent = session("pa", 0, "idle", null);
        when(sessionRepository.countByParentSessionIdAndRuntimeStatus("pa", "running")).thenReturn(0L);
        SubAgentRegistry.SubAgentRun run = registry.registerRun(parent, 9L, "researcher", "task body");
        registry.attachChildSession(run.runId, "ca");

        // child 跑完
        SessionEntity child = session("ca", 1, "idle", "pa");
        child.setSubAgentRunId(run.runId);
        when(sessionRepository.findById("ca")).thenReturn(Optional.of(child));
        // child 触发 maybeResumeParent("ca") 时也会被查 — 它没 pending,不影响
        when(sessionRepository.findById("pa")).thenReturn(Optional.of(parent));

        registry.onSessionLoopFinished("ca", "the answer is 42", "completed", 3, 1234L);

        // 父被唤醒一次,消息体包含子 final message
        verify(chatService, times(1)).chatAsync(eq("pa"), anyString(), eq(7L));
        // 从持久层重新读出 run 做断言(DTO 本身不随持久化更新)
        SubAgentRegistry.SubAgentRun refreshed = registry.getRun(run.runId);
        assertThat(refreshed.status).isEqualTo("COMPLETED");
        assertThat(refreshed.finalMessage).isEqualTo("the answer is 42");
    }

    @Test
    void childFinished_with_parent_running_only_enqueues_no_resume() {
        SessionEntity parent = session("pb", 0, "running", null);
        when(sessionRepository.countByParentSessionIdAndRuntimeStatus("pb", "running")).thenReturn(0L);
        SubAgentRegistry.SubAgentRun run = registry.registerRun(parent, 9L, "researcher", "task body");
        registry.attachChildSession(run.runId, "cb");

        SessionEntity child = session("cb", 1, "idle", "pb");
        child.setSubAgentRunId(run.runId);
        when(sessionRepository.findById("cb")).thenReturn(Optional.of(child));
        when(sessionRepository.findById("pb")).thenReturn(Optional.of(parent));

        registry.onSessionLoopFinished("cb", "partial done", "completed", 1, 100L);

        // 父在跑,不能抢跑
        verify(chatService, never()).chatAsync(anyString(), anyString(), any());
        SubAgentRegistry.SubAgentRun refreshed = registry.getRun(run.runId);
        assertThat(refreshed.status).isEqualTo("COMPLETED");
    }

    @Test
    void parentLoopFinished_drains_pending_results_after_it_goes_idle() {
        // 模拟竞态:子 cc 跑完时父 pc 还 running ⇒ 仅 enqueue
        SessionEntity parentRunning = session("pc", 0, "running", null);
        when(sessionRepository.countByParentSessionIdAndRuntimeStatus("pc", "running")).thenReturn(0L);
        SubAgentRegistry.SubAgentRun run = registry.registerRun(parentRunning, 9L, "researcher", "task");
        registry.attachChildSession(run.runId, "cc");

        SessionEntity child = session("cc", 1, "idle", "pc");
        child.setSubAgentRunId(run.runId);
        when(sessionRepository.findById("cc")).thenReturn(Optional.of(child));
        when(sessionRepository.findById("pc")).thenReturn(Optional.of(parentRunning));

        registry.onSessionLoopFinished("cc", "child done", "completed", 1, 50L);
        verify(chatService, never()).chatAsync(anyString(), anyString(), any());

        // 之后父自己 loop 跑完进入 idle,在 finally 里再次回调 onSessionLoopFinished("pc",...)
        SessionEntity parentIdleNow = session("pc", 0, "idle", null);
        when(sessionRepository.findById("pc")).thenReturn(Optional.of(parentIdleNow));

        registry.onSessionLoopFinished("pc", "parent turn done", "completed", 0, 10L);

        // 这次应该 drain 队列并唤醒父
        verify(chatService, times(1)).chatAsync(eq("pc"), anyString(), eq(7L));
    }

    @Test
    void multiple_children_results_are_combined_into_single_resume() {
        SessionEntity parentRunning = session("pd", 0, "running", null);
        when(sessionRepository.countByParentSessionIdAndRuntimeStatus("pd", "running")).thenReturn(0L);
        SubAgentRegistry.SubAgentRun r1 = registry.registerRun(parentRunning, 9L, "alpha", "t1");
        registry.attachChildSession(r1.runId, "cd1");
        SubAgentRegistry.SubAgentRun r2 = registry.registerRun(parentRunning, 9L, "beta", "t2");
        registry.attachChildSession(r2.runId, "cd2");

        SessionEntity c1 = session("cd1", 1, "idle", "pd");
        c1.setSubAgentRunId(r1.runId);
        SessionEntity c2 = session("cd2", 1, "idle", "pd");
        c2.setSubAgentRunId(r2.runId);
        when(sessionRepository.findById("cd1")).thenReturn(Optional.of(c1));
        when(sessionRepository.findById("cd2")).thenReturn(Optional.of(c2));
        when(sessionRepository.findById("pd")).thenReturn(Optional.of(parentRunning));

        registry.onSessionLoopFinished("cd1", "alpha result", "completed", 1, 10L);
        registry.onSessionLoopFinished("cd2", "beta result", "completed", 1, 10L);
        verify(chatService, never()).chatAsync(anyString(), anyString(), any());

        // 父 loop 跑完
        SessionEntity parentIdleNow = session("pd", 0, "idle", null);
        when(sessionRepository.findById("pd")).thenReturn(Optional.of(parentIdleNow));
        registry.onSessionLoopFinished("pd", "parent done", "completed", 0, 5L);

        // 一次性合并 drain
        verify(chatService, times(1)).chatAsync(eq("pd"), anyString(), eq(7L));
        assertThat(registry.listRunsForParent("pd"))
                .extracting(r -> r.status)
                .containsExactlyInAnyOrder("COMPLETED", "COMPLETED");
    }
}
