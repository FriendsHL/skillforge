package com.skillforge.server.subagent;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SubAgentRunEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SubAgentRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubAgentRunSweeper 单元测试。用 Mockito mock 所有依赖,复用与 SubAgentRegistryTest 相同的风格。
 */
class SubAgentRunSweeperTest {

    private SubAgentRunRepository runRepository;
    private SessionRepository sessionRepository;
    private SubAgentRegistry subAgentRegistry;
    private SubAgentRunSweeper sweeper;

    @BeforeEach
    void setUp() {
        runRepository = mock(SubAgentRunRepository.class);
        sessionRepository = mock(SessionRepository.class);
        subAgentRegistry = mock(SubAgentRegistry.class);
        CollabRunRepository collabRunRepository = mock(CollabRunRepository.class);
        sweeper = new SubAgentRunSweeper(runRepository, sessionRepository, subAgentRegistry, collabRunRepository);
    }

    private SubAgentRunEntity run(String runId, String parentId, String childId, Instant spawnedAt) {
        SubAgentRunEntity e = new SubAgentRunEntity();
        e.setRunId(runId);
        e.setParentSessionId(parentId);
        e.setChildSessionId(childId);
        e.setChildAgentName("child-agent");
        e.setStatus("RUNNING");
        e.setSpawnedAt(spawnedAt);
        return e;
    }

    private SessionEntity child(String id, String parentId, String runtimeStatus, LocalDateTime updatedAt) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(2L);
        s.setParentSessionId(parentId);
        s.setDepth(1);
        s.setRuntimeStatus(runtimeStatus);
        s.setUpdatedAt(updatedAt);
        return s;
    }

    @Test
    void idle_child_beyond_grace_triggers_registry_recovery() {
        SubAgentRunEntity r = run("r1", "p1", "c1", Instant.now().minusSeconds(120));
        LocalDateTime staleUpdatedAt = LocalDateTime.ofInstant(
                Instant.now().minusSeconds(90), ZoneId.systemDefault());
        SessionEntity c = child("c1", "p1", "idle", staleUpdatedAt);

        when(runRepository.findByStatus("RUNNING")).thenReturn(List.of(r));
        when(sessionRepository.findById("c1")).thenReturn(Optional.of(c));

        sweeper.sweepOnce();

        verify(subAgentRegistry, times(1))
                .onSessionLoopFinished(eq("c1"), anyString(), eq("completed"), anyInt(), anyLong());
        verify(subAgentRegistry, never()).notifyParentOfOrphanRun(any(), anyString());
    }

    @Test
    void fresh_idle_child_within_grace_is_left_alone() {
        SubAgentRunEntity r = run("r-fresh", "p-fresh", "c-fresh", Instant.now().minusSeconds(10));
        LocalDateTime freshUpdatedAt = LocalDateTime.ofInstant(
                Instant.now().minusSeconds(2), ZoneId.systemDefault());
        SessionEntity c = child("c-fresh", "p-fresh", "idle", freshUpdatedAt);

        when(runRepository.findByStatus("RUNNING")).thenReturn(List.of(r));
        when(sessionRepository.findById("c-fresh")).thenReturn(Optional.of(c));

        sweeper.sweepOnce();

        verify(subAgentRegistry, never())
                .onSessionLoopFinished(anyString(), anyString(), anyString(), anyInt(), anyLong());
        verify(subAgentRegistry, never()).notifyParentOfOrphanRun(any(), anyString());
    }

    @Test
    void no_child_after_timeout_is_marked_cancelled_and_parent_notified() {
        Instant ancientSpawn = Instant.now().minusSeconds(60 * 60); // 1h
        SubAgentRunEntity r = run("r2", "p2", null, ancientSpawn);

        when(runRepository.findByStatus("RUNNING")).thenReturn(List.of(r));

        sweeper.sweepOnce();

        ArgumentCaptor<SubAgentRunEntity> saved = ArgumentCaptor.forClass(SubAgentRunEntity.class);
        verify(runRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("CANCELLED");
        assertThat(saved.getValue().getFinalMessage()).contains("never attached");

        verify(subAgentRegistry, times(1)).notifyParentOfOrphanRun(eq(r), anyString());
    }

    @Test
    void deleted_child_session_is_marked_cancelled_and_parent_notified() {
        SubAgentRunEntity r = run("r3", "p3", "c3-gone", Instant.now().minusSeconds(600));

        when(runRepository.findByStatus("RUNNING")).thenReturn(List.of(r));
        when(sessionRepository.findById("c3-gone")).thenReturn(Optional.empty());

        sweeper.sweepOnce();

        ArgumentCaptor<SubAgentRunEntity> saved = ArgumentCaptor.forClass(SubAgentRunEntity.class);
        verify(runRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("CANCELLED");
        assertThat(saved.getValue().getFinalMessage()).contains("no longer exists");

        verify(subAgentRegistry, times(1)).notifyParentOfOrphanRun(eq(r), anyString());
    }
}
