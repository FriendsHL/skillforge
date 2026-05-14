package com.skillforge.server.service.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.repository.ScheduledTaskRunRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.ScheduledTaskService;
import com.skillforge.server.service.SessionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression IT for the stale-entity overwrite that wiped {@code reused_session_id}
 * after the first fire of a {@code session_mode=reuse} task — caught against a
 * production task whose row stayed {@code reused_session_id IS NULL} despite two
 * successful fires, each opening a fresh session.
 *
 * <p>Root cause: {@code ScheduledTaskExecutor.fire()} read the task at the top
 * via {@code findById} (snapshot A), {@code openSessionForTask} then re-read the
 * row inside the critical section (snapshot B) and persisted the new
 * {@code reused_session_id} on snapshot B. The outer code subsequently set
 * {@code last_fire_at} + {@code status} on snapshot A (still null) and called
 * {@code repository.save(task)} — which JPA emits as a full UPDATE (no
 * {@code @DynamicUpdate}), writing the stale null back over the just-persisted
 * session id.
 *
 * <p>This test cannot be reproduced with pure mocks because the unit test in
 * {@code ScheduledTaskExecutorTest} mocks {@code repository.save} to echo the
 * persisted {@code reused_session_id} back onto the outer reference — masking
 * the very gap the bug lives in. Only a real DB (snapshots A and B are
 * distinct managed entities) surfaces the regression.
 *
 * <p>The fix syncs the {@code sessionId} onto the outer task reference before
 * the final save, so the second save no longer carries a stale null.
 */
@DisplayName("ScheduledTaskExecutor reuse-mode persistence IT")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
class ScheduledTaskExecutorReusePersistenceIT extends AbstractPostgresIT {

    @Autowired private ScheduledTaskRepository scheduledTaskRepository;
    @Autowired private ScheduledTaskRunRepository scheduledTaskRunRepository;
    @Autowired private EntityManager entityManager;

    private ScheduledTaskExecutor executor;
    private SessionService sessionServiceMock;
    private ChatService chatServiceMock;
    private UserTaskScheduler schedulerMock;
    private SchedulerChannelDispatcher dispatcherMock;

    @BeforeEach
    void setUp() {
        // Two-transaction layering note (do not break by adding @Transactional to
        // AbstractPostgresIT): @DataJpaTest wraps this test method in a tx that
        // rolls back at the end. ScheduledTaskExecutor + ScheduledTaskService are
        // constructed with `new` (not Spring beans) so their @Transactional has
        // no AOP proxy — instead, SimpleJpaRepository.save's own @Transactional
        // commits each save in its own short-lived inner tx. The flush()+clear()
        // calls below evict the L1 cache so findById assertions hit the DB. The
        // outer @DataJpaTest rollback still cleans up via the deleteAll() in
        // this @BeforeEach because deleteAll runs inside the outer tx.
        scheduledTaskRunRepository.deleteAll();
        scheduledTaskRepository.deleteAll();

        sessionServiceMock = mock(SessionService.class);
        chatServiceMock = mock(ChatService.class);
        schedulerMock = mock(UserTaskScheduler.class);
        dispatcherMock = mock(SchedulerChannelDispatcher.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<UserTaskScheduler> schedulerProvider =
                (ObjectProvider<UserTaskScheduler>) mock(ObjectProvider.class);
        when(schedulerProvider.getObject()).thenReturn(schedulerMock);
        when(schedulerProvider.getIfAvailable()).thenReturn(schedulerMock);

        // Real ScheduledTaskService against the real repos — fire() relies on its
        // markRunStart / attachRunSession / markRunFinish writes hitting the same
        // DB the assertions read from.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ScheduledTaskService scheduledTaskService = new ScheduledTaskService(
                scheduledTaskRepository,
                scheduledTaskRunRepository,
                mock(ApplicationEventPublisher.class),
                objectMapper);

        executor = new ScheduledTaskExecutor(
                scheduledTaskRepository,
                scheduledTaskService,
                sessionServiceMock,
                chatServiceMock,
                schedulerProvider,
                dispatcherMock,
                "https://dash.test");
    }

    private ScheduledTaskEntity seedReuseTask() {
        ScheduledTaskEntity t = new ScheduledTaskEntity();
        t.setName("daily-reuse");
        t.setCreatorUserId(7L);
        t.setAgentId(42L);
        t.setCronExpr("0 0 9 * * *");
        t.setTimezone("Asia/Shanghai");
        t.setPromptTemplate("hello");
        t.setSessionMode(ScheduledTaskEntity.SESSION_MODE_REUSE);
        t.setEnabled(true);
        t.setStatus(ScheduledTaskEntity.STATUS_IDLE);
        t.setConcurrencyPolicy(ScheduledTaskEntity.CONCURRENCY_SKIP_IF_RUNNING);
        scheduledTaskRepository.save(t);
        entityManager.flush();
        entityManager.clear();
        return t;
    }

    private SessionEntity newSession(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(42L);
        return s;
    }

    @Test
    @DisplayName("two consecutive fires of session_mode=reuse share one session_id and reused_session_id stays persisted")
    void reuseMode_twoConsecutiveFires_persistReusedSessionId() {
        ScheduledTaskEntity seeded = seedReuseTask();
        Long taskId = seeded.getId();

        when(schedulerMock.tryMarkRunning(taskId)).thenReturn(true);
        // SessionService.createSession should only be called once across both fires —
        // the second fire must reuse the persisted session id from the first.
        when(sessionServiceMock.createSession(7L, 42L)).thenReturn(newSession("sess-reuse-it"));

        // --- fire 1 ---
        executor.fire(taskId, false);
        entityManager.flush();
        entityManager.clear();

        ScheduledTaskEntity afterFire1 = scheduledTaskRepository.findById(taskId).orElseThrow();
        assertThat(afterFire1.getReusedSessionId())
                .as("first fire must persist reused_session_id (otherwise next fire builds a new session)")
                .isEqualTo("sess-reuse-it");
        assertThat(afterFire1.getLastFireAt())
                .as("first fire must also persist last_fire_at on the same save")
                .isNotNull();

        // --- fire 2 ---
        // Re-arm the scheduler mock; in prod the previous run finishing would clear it.
        Mockito.reset(schedulerMock);
        when(schedulerMock.tryMarkRunning(taskId)).thenReturn(true);
        executor.fire(taskId, false);
        entityManager.flush();
        entityManager.clear();

        ScheduledTaskEntity afterFire2 = scheduledTaskRepository.findById(taskId).orElseThrow();
        assertThat(afterFire2.getReusedSessionId())
                .as("second fire must not wipe reused_session_id with a stale-entity save")
                .isEqualTo("sess-reuse-it");

        // SessionService.createSession was only invoked for the first fire — the
        // second fire took the early-return branch in openSessionForTask.
        verify(sessionServiceMock, times(1)).createSession(7L, 42L);
        // ChatService dispatched both fires onto the same session id.
        verify(chatServiceMock, times(2))
                .chatAsync(eq("sess-reuse-it"), anyString(), eq(7L), anyBoolean());
    }
}
