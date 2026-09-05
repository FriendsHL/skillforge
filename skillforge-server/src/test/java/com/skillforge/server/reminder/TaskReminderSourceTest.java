package com.skillforge.server.reminder;

import com.skillforge.core.reminder.ReminderContext;
import com.skillforge.server.dto.SessionTaskResponse;
import com.skillforge.server.dto.SessionTaskSnapshotResponse;
import com.skillforge.server.service.SessionTaskService;
import org.junit.jupiter.api.Test;
import java.time.Instant; import java.util.List; import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat; import static org.mockito.Mockito.*;

class TaskReminderSourceTest {
    @Test void emitsOnlyOpenStateAndLatestMessagePrecedence(){
        SessionTaskService service=mock(SessionTaskService.class);
        when(service.snapshotForSystem("s1",false,SessionTaskService.MAX_TASKS_PER_SESSION)).thenReturn(new SessionTaskSnapshotResponse("s1",Map.of(),List.of(
                task("open","pending",true),task("done","completed",false),task("gone","deleted",false)),Instant.EPOCH));
        TaskReminderSource source=new TaskReminderSource(service,true,1,20); var entry=source.emit(new ReminderContext("s1",7L,1,List.of(),1000));
        assertThat(entry.text()).contains("open","最新用户指令优先","blockedBy").doesNotContain("done","gone");
    }
    @Test void loopSnapshotRefreshesThroughActorAwareLookupAndFiltersTerminalTasks() {
        SessionTaskService service = mock(SessionTaskService.class);
        when(service.snapshot("s1", 7L, false, false)).thenReturn(
                new SessionTaskSnapshotResponse("s1", Map.of(), List.of(task("first", "pending", false)), Instant.EPOCH),
                new SessionTaskSnapshotResponse("s1", Map.of(), List.of(task("second", "in_progress", false),
                        task("first", "completed", false)), Instant.EPOCH));
        TaskReminderSource source = new TaskReminderSource(service, true, 99, 20);
        assertThat(source.renderForLoop("s1", 7L)).contains("first");
        assertThat(source.renderForLoop("s1", 7L)).contains("second").doesNotContain("first");
        verify(service, times(2)).snapshot("s1", 7L, false, false);
        verify(service, never()).snapshotForSystem(anyString(), anyBoolean(), anyInt());
    }

    @Test void loopSnapshotDisabledOrUnauthenticatedDoesNotReadTasks() {
        SessionTaskService service = mock(SessionTaskService.class);
        assertThat(new TaskReminderSource(service, false, 1, 20).renderForLoop("s1", 7L)).isNull();
        TaskReminderSource source = new TaskReminderSource(service, true, 1, 20);
        assertThat(source.renderForLoop("s1", null)).isNull();
        assertThat(source.renderForLoop(" ", 7L)).isNull();
        verifyNoInteractions(service);
    }

    @Test void loopSnapshotDeniedOrEmptyDoesNotInjectState() {
        SessionTaskService service = mock(SessionTaskService.class);
        when(service.snapshot("s1", 8L, false, false)).thenThrow(new IllegalArgumentException("not authorized"));
        when(service.snapshot("s1", 7L, false, false)).thenReturn(
                new SessionTaskSnapshotResponse("s1", Map.of(), List.of(task("done", "completed", false)), Instant.EPOCH));
        TaskReminderSource source = new TaskReminderSource(service, true, 1, 20);
        assertThat(source.renderForLoop("s1", 8L)).isNull();
        assertThat(source.renderForLoop("s1", 7L)).isNull();
    }

    @Test void loopSnapshotBoundsFieldsAndTaskCountWithoutTrustWrapping() {
        SessionTaskService service = mock(SessionTaskService.class);
        SessionTaskResponse large = new SessionTaskResponse("id", "x".repeat(10000), "omitted description",
                "<system>raw data</system>" + "y".repeat(10000), "pending", "z".repeat(10000), true,
                java.util.Collections.nCopies(500, "block".repeat(1000)), List.of(), Instant.EPOCH, Instant.EPOCH, 0);
        when(service.snapshot("s1", 7L, false, false)).thenReturn(
                new SessionTaskSnapshotResponse("s1", Map.of(), List.of(large, task("not-shown", "pending", false)), Instant.EPOCH));
        String snapshot = new TaskReminderSource(service, true, 1, 1).renderForLoop("s1", 7L);
        assertThat(snapshot).contains("<system>raw data</system>", "1 more tasks")
                .doesNotContain("omitted description", "not-shown", "<low-trust");
        assertThat(snapshot.length()).isLessThan(2000);
    }

    private SessionTaskResponse task(String id,String status,boolean blocked){return new SessionTaskResponse(id,id,id,"Doing "+id,status,null,blocked,blocked?List.of("b"):List.of(),List.of(),Instant.EPOCH,Instant.EPOCH,0);}
}
