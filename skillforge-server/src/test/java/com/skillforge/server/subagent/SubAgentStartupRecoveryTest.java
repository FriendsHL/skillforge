package com.skillforge.server.subagent;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SubAgentRunEntity;
import com.skillforge.server.init.SubAgentStartupRecovery;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SubAgentRunRepository;
import com.skillforge.server.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubAgentStartupRecovery 单元测试 —— 纯 Mockito,无 Spring 上下文。
 */
class SubAgentStartupRecoveryTest {

    private SubAgentRunRepository runRepository;
    private SessionRepository sessionRepository;
    private SubAgentRegistry subAgentRegistry;
    private ChatService chatService;
    private SubAgentStartupRecovery recovery;

    @BeforeEach
    void setUp() {
        runRepository = mock(SubAgentRunRepository.class);
        sessionRepository = mock(SessionRepository.class);
        subAgentRegistry = mock(SubAgentRegistry.class);
        chatService = mock(ChatService.class);
        AgentRoster agentRoster = mock(AgentRoster.class);
        recovery = new SubAgentStartupRecovery(runRepository, sessionRepository, subAgentRegistry, chatService, agentRoster);
    }

    private SubAgentRunEntity run(String runId, String childId) {
        SubAgentRunEntity e = new SubAgentRunEntity();
        e.setRunId(runId);
        e.setParentSessionId("parent-" + runId);
        e.setChildSessionId(childId);
        e.setChildAgentName("child-agent");
        e.setStatus("RUNNING");
        e.setSpawnedAt(Instant.now().minusSeconds(60));
        return e;
    }

    private SessionEntity child(String id, String runtimeStatus) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(11L);
        s.setAgentId(2L);
        s.setParentSessionId("parent-of-" + id);
        s.setDepth(1);
        s.setRuntimeStatus(runtimeStatus);
        return s;
    }

    @Test
    void running_child_gets_resubmitted_via_chatAsync() {
        SubAgentRunEntity r = run("rr1", "c-running");
        SessionEntity c = child("c-running", "running");

        when(runRepository.findByStatus("RUNNING")).thenReturn(List.of(r));
        when(sessionRepository.findById("c-running")).thenReturn(Optional.of(c));

        recovery.run(null);

        // OBS-4 §2.1: startup recovery uses 4-arg chatAsync(preserveActiveRoot=true) to keep
        // child's persisted active_root (decision Q5: active_root persisted across JVM restart).
        verify(chatService, times(1)).chatAsync(eq("c-running"), contains("Resume from restart"), eq(11L), eq(true));
        verify(subAgentRegistry, never())
                .onSessionLoopFinished(anyString(), anyString(), anyString(), anyInt(), anyLong());
        verify(subAgentRegistry, never()).notifyParentOfOrphanRun(any(), anyString());
    }

    @Test
    void idle_child_replays_finally_hook_via_registry() {
        SubAgentRunEntity r = run("rr2", "c-idle");
        SessionEntity c = child("c-idle", "idle");

        when(runRepository.findByStatus("RUNNING")).thenReturn(List.of(r));
        when(sessionRepository.findById("c-idle")).thenReturn(Optional.of(c));

        recovery.run(null);

        verify(subAgentRegistry, times(1))
                .onSessionLoopFinished(eq("c-idle"), anyString(), eq("completed"), anyInt(), anyLong());
        // OBS-4 §2.1: neither 3-arg nor 4-arg chatAsync should fire for non-running children.
        verify(chatService, never()).chatAsync(anyString(), anyString(), any());
        verify(chatService, never()).chatAsync(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void null_child_session_is_cancelled_and_parent_notified() {
        SubAgentRunEntity r = run("rr3", null);

        when(runRepository.findByStatus("RUNNING")).thenReturn(List.of(r));

        recovery.run(null);

        ArgumentCaptor<SubAgentRunEntity> saved = ArgumentCaptor.forClass(SubAgentRunEntity.class);
        verify(runRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("CANCELLED");
        verify(subAgentRegistry, times(1)).notifyParentOfOrphanRun(eq(r), anyString());
        // OBS-4 §2.1: neither 3-arg nor 4-arg chatAsync should fire for non-running children.
        verify(chatService, never()).chatAsync(anyString(), anyString(), any());
        verify(chatService, never()).chatAsync(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void no_running_rows_no_ops() {
        when(runRepository.findByStatus("RUNNING")).thenReturn(List.of());
        recovery.run(null);
        // OBS-4 §2.1: neither 3-arg nor 4-arg chatAsync should fire for non-running children.
        verify(chatService, never()).chatAsync(anyString(), anyString(), any());
        verify(chatService, never()).chatAsync(anyString(), anyString(), any(), anyBoolean());
        verify(subAgentRegistry, never())
                .onSessionLoopFinished(anyString(), anyString(), anyString(), anyInt(), anyLong());
    }
}
