package com.skillforge.server.tool.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.server.dto.SessionTaskResponse;
import com.skillforge.server.dto.SessionTaskSnapshotResponse;
import com.skillforge.server.service.SessionTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant; import java.util.List; import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat; import static org.mockito.ArgumentMatchers.*; import static org.mockito.Mockito.*;

class TaskToolsTest {
    @Test
    void unknownExecutionFailureDoesNotExposeInternalDetails() {
        SessionTaskService service = mock(SessionTaskService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SkillContext context = new SkillContext(null, "s1", 7L);
        when(service.get("s1", 7L, "t1"))
                .thenThrow(new RuntimeException("SQL failed for t_secret with password=secret"));

        var result = new TaskGetTool(service, mapper).execute(Map.of("taskId", "t1"), context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Task operation failed", "TASK_EXECUTION_FAILED");
        assertThat(result.getError()).doesNotContain("SQL", "t_secret", "password", "secret");
    }

    @Test void updateAcceptsClaudeStyleAliasesAndListExcludesDeletedByDefault(){
        SessionTaskService service=mock(SessionTaskService.class); ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        SessionTaskResponse task=new SessionTaskResponse("t1","S","D","A","pending",null,false,List.of(),List.of(),Instant.EPOCH,Instant.EPOCH,0);
        when(service.update(anyString(),anyLong(),anyString(),nullable(Long.class),any())).thenReturn(new SessionTaskSnapshotResponse("s1",Map.of("total",1L),List.of(task),Instant.EPOCH));
        SkillContext context=new SkillContext(null,"s1",7L);
        var result=new TaskUpdateTool(service,mapper).execute(Map.of("task_id","t1","active_form","Doing"),context);
        assertThat(result.isSuccess()).isTrue(); ArgumentCaptor<SessionTaskService.UpdateCommand> command=ArgumentCaptor.forClass(SessionTaskService.UpdateCommand.class);
        verify(service).update(eq("s1"),eq(7L),eq("t1"),isNull(),command.capture()); assertThat(command.getValue().activeForm()).isEqualTo("Doing");

        when(service.snapshot("s1",7L,false,false)).thenReturn(new SessionTaskSnapshotResponse("s1",Map.of("total",1L),List.of(task),Instant.EPOCH));
        var listed=new TaskListTool(service,mapper).execute(Map.of(),context); assertThat(listed.isSuccess()).isTrue(); verify(service).snapshot("s1",7L,false,false);
    }
    @Test void schemasExposeCanonicalNamesOnly(){
        SessionTaskService service=mock(SessionTaskService.class); ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        String update=new TaskUpdateTool(service,mapper).getToolSchema().getInputSchema().toString();
        assertThat(update).contains("taskId","expectedRevision","activeForm","addBlockedBy")
                .doesNotContain("task_id","active_form","removeBlockedBy","removeBlocks");
        assertThat(new TaskGetTool(service,mapper).isReadOnly()).isTrue(); assertThat(new TaskListTool(service,mapper).isReadOnly()).isTrue();
        assertThat(new TaskListTool(service,mapper).getToolSchema().getInputSchema().toString())
                .contains("availableOnly");
        String create=new TaskCreateTool(service,mapper).getToolSchema().getInputSchema().toString();
        assertThat(create).contains("activeForm", "required=[subject, description]")
                .doesNotContain("owner", "blockedBy");
    }
    @Test void listRejectsUnsupportedStatusInsteadOfSilentlyReturningEmpty(){
        SessionTaskService service=mock(SessionTaskService.class); ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        SkillContext context=new SkillContext(null,"s1",7L);

        var result=new TaskListTool(service,mapper).execute(Map.of("statuses",List.of("stale")),context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("TASK_INPUT_INVALID","statuses");
        verifyNoInteractions(service);
    }
}
