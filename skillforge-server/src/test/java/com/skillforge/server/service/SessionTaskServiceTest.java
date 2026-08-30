package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionTaskDependencyEntity;
import com.skillforge.server.entity.SessionTaskDependencyId;
import com.skillforge.server.entity.SessionTaskEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionTaskDependencyRepository;
import com.skillforge.server.repository.SessionTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionTaskServiceTest {
    SessionRepository sessions=mock(SessionRepository.class); SessionTaskRepository tasks=mock(SessionTaskRepository.class);
    SessionTaskDependencyRepository deps=mock(SessionTaskDependencyRepository.class); ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    List<SessionTaskEntity> taskRows; List<SessionTaskDependencyEntity> depRows; SessionTaskService service;
    @BeforeEach void setUp(){
        taskRows=new ArrayList<>(); depRows=new ArrayList<>(); SessionEntity session=new SessionEntity(); session.setId("s1"); session.setUserId(7L);
        when(sessions.findByIdForUpdate("s1")).thenReturn(Optional.of(session)); when(sessions.findById("s1")).thenReturn(Optional.of(session)); when(sessions.existsById("s1")).thenReturn(true);
        when(tasks.findBySessionIdOrderByCreatedAtAscIdAsc("s1")).thenAnswer(i->new ArrayList<>(taskRows));
        when(tasks.findByIdAndSessionId(anyString(),eq("s1"))).thenAnswer(i->taskRows.stream().filter(t->t.getId().equals(i.getArgument(0))).findFirst());
        when(tasks.save(any())).thenAnswer(i->{SessionTaskEntity t=i.getArgument(0); if(!taskRows.contains(t))taskRows.add(t); return t;});
        when(tasks.saveAndFlush(any())).thenAnswer(i->{SessionTaskEntity t=i.getArgument(0); if(!taskRows.contains(t))taskRows.add(t); return t;});
        when(deps.findBySessionId("s1")).thenAnswer(i->new ArrayList<>(depRows));
        when(deps.save(any())).thenAnswer(i->{SessionTaskDependencyEntity d=i.getArgument(0);depRows.add(d);return d;});
        when(deps.saveAll(any())).thenAnswer(i->{List<SessionTaskDependencyEntity> add=(List<SessionTaskDependencyEntity>)i.getArgument(0);depRows.addAll(add);return add;});
        doAnswer(i->{
            Iterable<SessionTaskDependencyId> removed=i.getArgument(0);
            removed.forEach(id->depRows.removeIf(d->d.getTaskId().equals(id.getTaskId())
                    && d.getBlockedByTaskId().equals(id.getBlockedByTaskId())));
            return null;
        }).when(deps).deleteAllById(any());
        service=new SessionTaskService(sessions,tasks,deps,new ObjectMapper(),events,Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC));
    }
    @Test void reopeningBlockerDemotesRunningDependent(){
        SessionTaskEntity blocker=task("a","completed",null); SessionTaskEntity dependent=task("b","in_progress",null); taskRows.addAll(List.of(blocker,dependent));
        depRows.add(new SessionTaskDependencyEntity("s1","b","a"));
        service.update("s1",7L,"a",new SessionTaskService.UpdateCommand(null,null,null,"pending",false,null,false,null,List.of(),List.of(),List.of(),List.of()));
        assertThat(dependent.getStatus()).isEqualTo("pending"); verify(events).publishEvent(any(Object.class));
    }
    @Test void reopeningBlockerRecursivelyDemotesRunningDescendant(){
        SessionTaskEntity root=task("a","completed",null), middle=task("b","completed",null), leaf=task("c","in_progress",null);
        taskRows.addAll(List.of(root,middle,leaf)); depRows.add(new SessionTaskDependencyEntity("s1","b","a")); depRows.add(new SessionTaskDependencyEntity("s1","c","b"));
        service.update("s1",7L,"a",new SessionTaskService.UpdateCommand(null,null,null,"pending",false,null,false,null,List.of(),List.of(),List.of(),List.of()));
        assertThat(leaf.getStatus()).isEqualTo("pending");
    }
    @Test void addBlocksDemotesRunningDownstreamAndDuplicateEdgeIsNotWrittenAgain(){
        SessionTaskEntity blocker=task("a","pending",null), running=task("b","in_progress",null); taskRows.addAll(List.of(blocker,running));
        service.update("s1",7L,"a",new SessionTaskService.UpdateCommand(null,null,null,null,false,null,false,null,List.of(),List.of(),List.of("b","b"),List.of()));
        assertThat(running.getStatus()).isEqualTo("pending"); assertThat(depRows).hasSize(1);
    }
    @Test void createDefaultsOptionalActiveFormToSubject(){
        var created=service.create("s1",7L,new SessionTaskService.CreateCommand("Ship tests","Verified",null," ",Map.of(),List.of()));
        assertThat(created.task().activeForm()).isEqualTo("Ship tests"); assertThat(created.task().owner()).isNull();
        assertThat(created.snapshot().tasks()).singleElement().isEqualTo(created.task());
    }
    @Test void switchingNullOwnerAtomicallyDemotesPreviousTask(){
        SessionTaskEntity old=task("a","in_progress",null), next=task("b","pending"," "); taskRows.addAll(List.of(old,next));
        service.update("s1",7L,"b",new SessionTaskService.UpdateCommand(null,null,null,"in_progress",false,null,false,null,List.of(),List.of(),List.of(),List.of()));
        assertThat(old.getStatus()).isEqualTo("pending"); assertThat(next.getStatus()).isEqualTo("in_progress");
    }
    @Test void dependencyCycleIsRejected(){
        SessionTaskEntity a=task("a","pending",null), b=task("b","pending",null); taskRows.addAll(List.of(a,b)); depRows.add(new SessionTaskDependencyEntity("s1","a","b"));
        assertThatThrownBy(()->service.update("s1",7L,"b",new SessionTaskService.UpdateCommand(null,null,null,null,false,null,false,null,List.of("a"),List.of(),List.of(),List.of())))
                .isInstanceOf(SessionTaskException.class).hasMessageContaining("cycle");
    }
    @Test void completingBlockerTouchesDependentSoRealtimeClientsAcceptDerivedState(){
        SessionTaskEntity blocker=task("a","pending",null), dependent=task("b","pending",null);
        taskRows.addAll(List.of(blocker,dependent)); depRows.add(new SessionTaskDependencyEntity("s1","b","a"));
        assertThat(service.snapshot("s1",7L,true).tasks().stream()
                .filter(task->task.taskId().equals("b")).findFirst().orElseThrow().blocked()).isTrue();

        service.update("s1",7L,"a",new SessionTaskService.UpdateCommand(
                null,null,null,"completed",false,null,false,null,
                List.of(),List.of(),List.of(),List.of()));

        assertThat(dependent.getUpdatedAt()).isEqualTo(Instant.parse("2026-08-05T00:00:00Z"));
        assertThat(service.snapshot("s1",7L,true).tasks().stream()
                .filter(task->task.taskId().equals("b")).findFirst().orElseThrow().blocked()).isFalse();
    }
    @Test void removingDependencyTouchesBothEndsAndClearsRelationship(){
        SessionTaskEntity blocker=task("a","pending",null), dependent=task("b","pending",null);
        taskRows.addAll(List.of(blocker,dependent)); depRows.add(new SessionTaskDependencyEntity("s1","b","a"));

        service.update("s1",7L,"b",new SessionTaskService.UpdateCommand(
                null,null,null,null,false,null,false,null,
                List.of(),List.of("a"),List.of(),List.of()));

        assertThat(blocker.getUpdatedAt()).isEqualTo(Instant.parse("2026-08-05T00:00:00Z"));
        assertThat(dependent.getUpdatedAt()).isEqualTo(Instant.parse("2026-08-05T00:00:00Z"));
        assertThat(depRows).isEmpty();
    }
    @Test void anotherUsersSessionIsHiddenAsNotFound(){
        SessionEntity foreign=new SessionEntity(); foreign.setId("s1"); foreign.setUserId(8L);
        when(sessions.findById("s1")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(()->service.snapshot("s1",7L,true))
                .isInstanceOfSatisfying(SessionTaskException.class,
                        error->assertThat(error.getCode()).isEqualTo("TASK_NOT_FOUND"));
    }
    @Test void optimisticConflictReturnsRetryableStructuredDomainError(){
        SessionTaskEntity current=task("a","pending",null); taskRows.add(current);
        when(tasks.saveAndFlush(current)).thenThrow(
                new ObjectOptimisticLockingFailureException(SessionTaskEntity.class,"a"));

        assertThatThrownBy(()->service.update("s1",7L,"a",new SessionTaskService.UpdateCommand(
                "updated",null,null,null,false,null,false,null,
                List.of(),List.of(),List.of(),List.of())))
                .isInstanceOfSatisfying(SessionTaskException.class,error->{
                    assertThat(error.getCode()).isEqualTo("TASK_CONFLICT");
                    assertThat(error.isRetryable()).isTrue();
                });
    }
    @Test void expectedRevisionRejectsStaleTeamUpdateBeforeMutation(){
        SessionTaskEntity current=task("a","pending",null); taskRows.add(current);

        assertThatThrownBy(()->service.update("s1",7L,"a",9L,new SessionTaskService.UpdateCommand(
                "updated",null,null,null,false,null,false,null,
                List.of(),List.of(),List.of(),List.of())))
                .isInstanceOfSatisfying(SessionTaskException.class,error->{
                    assertThat(error.getCode()).isEqualTo("TASK_REVISION_CONFLICT");
                    assertThat(error.isRetryable()).isTrue();
                });

        assertThat(current.getSubject()).isEqualTo("a");
        verify(tasks, never()).saveAndFlush(current);
    }
    private SessionTaskEntity task(String id,String status,String owner){SessionTaskEntity t=new SessionTaskEntity();t.setId(id);t.setSessionId("s1");t.setUserId(7L);t.setSubject(id);t.setDescription(id);t.setActiveForm(id);t.setStatus(status);t.setOwner(owner);return t;}
}
