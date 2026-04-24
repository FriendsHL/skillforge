package com.skillforge.server.init;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingConfirmationStartupRecoveryTest {

    private SessionRepository sessionRepo;
    private SessionService sessionService;
    private PendingConfirmationStartupRecovery recovery;

    @BeforeEach
    void setup() {
        sessionRepo = mock(SessionRepository.class);
        sessionService = mock(SessionService.class);
        recovery = new PendingConfirmationStartupRecovery(sessionRepo, sessionService);
    }

    private SessionEntity s(String id, String status) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setRuntimeStatus(status);
        return e;
    }

    private Message assistantWithToolUse(String toolUseId, String toolName) {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        ContentBlock tu = ContentBlock.toolUse(toolUseId, toolName,
                java.util.Map.of("command", "clawhub install x"));
        m.setContent(new ArrayList<>(List.of(tu)));
        return m;
    }

    @Test
    @DisplayName("orphan tool_use → fabricated error tool_result + status error")
    void orphanRepaired() {
        SessionEntity sess = s("sid1", "running");
        when(sessionRepo.findAll()).thenReturn(List.of(sess));
        when(sessionService.getFullHistory("sid1")).thenReturn(List.of(
                assistantWithToolUse("tu-1", "Bash")));

        recovery.runRecovery();

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(sessionService).appendNormalMessages(eq("sid1"), captor.capture());
        List<Message> appended = captor.getValue();
        assertThat(appended).hasSize(1);
        Object content = appended.get(0).getContent();
        ContentBlock cb = (ContentBlock) ((List<?>) content).get(0);
        assertThat(cb.getType()).isEqualTo("tool_result");
        assertThat(cb.getToolUseId()).isEqualTo("tu-1");
        assertThat(cb.getIsError()).isTrue();
        assertThat(cb.getContent()).contains("Install confirmation aborted");
        assertThat(sess.getRuntimeStatus()).isEqualTo("error");
    }

    @Test
    @DisplayName("multiple orphans → all repaired in one append batch")
    void multipleOrphans() {
        SessionEntity sess = s("sid1", "waiting_user");
        when(sessionRepo.findAll()).thenReturn(List.of(sess));
        when(sessionService.getFullHistory("sid1")).thenReturn(List.of(
                assistantWithToolUse("tu-1", "Bash"),
                assistantWithToolUse("tu-2", "Bash")));

        recovery.runRecovery();

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(sessionService).appendNormalMessages(eq("sid1"), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("no orphans → status error, no appendNormalMessages")
    void noOrphansJustMark() {
        SessionEntity sess = s("sid1", "running");
        when(sessionRepo.findAll()).thenReturn(List.of(sess));
        // Tool_use with matching tool_result
        Message asst = assistantWithToolUse("tu-1", "Bash");
        Message tr = Message.toolResult("tu-1", "done", false);
        when(sessionService.getFullHistory("sid1")).thenReturn(List.of(asst, tr));

        recovery.runRecovery();

        verify(sessionService, never()).appendNormalMessages(anyString(), any());
        assertThat(sess.getRuntimeStatus()).isEqualTo("error");
        assertThat(sess.getRuntimeError()).contains("Server restarted");
    }

    @Test
    @DisplayName("idle / error session skipped")
    void idleSkipped() {
        when(sessionRepo.findAll()).thenReturn(List.of(
                s("idle", "idle"), s("err", "error")));
        recovery.runRecovery();
        verify(sessionService, never()).getFullHistory(anyString());
        verify(sessionService, never()).saveSession(any());
    }

    @Test
    @DisplayName("collectOrphanToolUseIds: ids with matching tool_result are excluded")
    void collectOrphan() {
        Message tu = assistantWithToolUse("a", "Bash");
        ((List<ContentBlock>) tu.getContent()).add(ContentBlock.toolUse("b", "Bash",
                Collections.emptyMap()));
        Message tr = Message.toolResult("a", "ok", false);
        List<String> orphans = PendingConfirmationStartupRecovery.collectOrphanToolUseIds(
                List.of(tu, tr));
        assertThat(orphans).containsExactly("b");
    }

    @Test
    @DisplayName("SmartLifecycle.phase < WebServerStartStopLifecycle phase")
    void phaseOrdering() {
        assertThat(recovery.getPhase()).isLessThan(Integer.MAX_VALUE - 1);
        assertThat(recovery.getPhase()).isEqualTo(Integer.MIN_VALUE + 100);
    }
}
