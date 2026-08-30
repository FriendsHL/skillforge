package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionTaskEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionTaskAttemptRepository;
import com.skillforge.server.repository.SessionTaskRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SessionTaskService.class, TeamTaskGraphService.class,
        TeamTaskClaimConcurrencyIT.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeamTaskClaimConcurrencyIT {
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @AfterAll
    static void stop() throws IOException {
        POSTGRES.close();
    }

    @Autowired private SessionRepository sessions;
    @Autowired private CollabRunRepository collabs;
    @Autowired private SessionTaskRepository tasks;
    @Autowired private SessionTaskAttemptRepository attempts;
    @Autowired private TeamTaskGraphService service;

    @Test
    void twoWorkersClaimingSameRevisionProduceExactlyOneActiveAttempt() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        String leaderId = "leader-" + suffix;
        String workerA = "worker-a-" + suffix;
        String workerB = "worker-b-" + suffix;
        String collabId = "collab-" + suffix;
        sessions.saveAllAndFlush(List.of(
                session(leaderId, 3L, collabId),
                session(workerA, 9L, collabId),
                session(workerB, 10L, collabId)));
        CollabRunEntity collab = new CollabRunEntity();
        collab.setCollabRunId(collabId); collab.setLeaderSessionId(leaderId); collab.setStatus("RUNNING");
        collab.setCreatedAt(java.time.Instant.now());
        collabs.saveAndFlush(collab);

        String taskId = service.create(leaderId, 7L, new SessionTaskService.CreateCommand(
                "Concurrent claim", "Exactly one worker claims", "Claiming",
                null, Map.of(), List.of())).task().taskId();
        long revision = tasks.findById(taskId).orElseThrow().getVersion();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> claimA = () -> claim(start, workerA, taskId, revision);
        Callable<Boolean> claimB = () -> claim(start, workerB, taskId, revision);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var futureA = executor.submit(claimA);
            var futureB = executor.submit(claimB);
            start.countDown();
            assertThat(List.of(futureA.get(), futureB.get()).stream().filter(Boolean::booleanValue).count())
                    .isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }

        SessionTaskEntity claimed = tasks.findById(taskId).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo("in_progress");
        assertThat(claimed.getOwner()).isIn(workerA, workerB);
        assertThat(attempts.findByTaskIdAndStatus(taskId, "ACTIVE")).isPresent();
        assertThat(attempts.findByTaskIdOrderByAttemptNoDesc(taskId)).hasSize(1);
    }

    private boolean claim(CountDownLatch start, String workerId, String taskId, long revision)
            throws InterruptedException {
        start.await();
        try {
            service.update(workerId, 7L, taskId, revision, new SessionTaskService.UpdateCommand(
                    null, null, null, "in_progress", false, null, false, null,
                    List.of(), List.of(), List.of(), List.of()));
            return true;
        } catch (SessionTaskException error) {
            assertThat(error.getCode()).isIn("TASK_REVISION_CONFLICT", "TASK_ALREADY_CLAIMED");
            return false;
        }
    }

    private static SessionEntity session(String id, Long agentId, String collabId) {
        SessionEntity session = new SessionEntity();
        session.setId(id); session.setUserId(7L); session.setAgentId(agentId);
        session.setTitle(id); session.setCollabRunId(collabId); session.setRuntimeStatus("running");
        return session;
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean CancellationRegistry cancellationRegistry() { return new CancellationRegistry(); }
    }
}
