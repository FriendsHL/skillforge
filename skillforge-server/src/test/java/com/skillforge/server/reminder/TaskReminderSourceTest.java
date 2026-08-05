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
    private SessionTaskResponse task(String id,String status,boolean blocked){return new SessionTaskResponse(id,id,id,"Doing "+id,status,null,blocked,blocked?List.of("b"):List.of(),List.of(),Instant.EPOCH,Instant.EPOCH,0);}
}
