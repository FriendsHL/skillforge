package com.skillforge.server.subagent;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CollabRunService.class, CollabRunServiceConcurrencyIT.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CollabRunServiceConcurrencyIT {
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
    @Autowired private CollabRunService service;
    @Autowired private SessionService sessionService;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentCreateRunForSameLeaderReusesOneRunningCollab() throws Exception {
        String leaderId = "leader-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        SessionEntity leader = new SessionEntity();
        leader.setId(leaderId);
        leader.setUserId(7L);
        leader.setAgentId(3L);
        leader.setTitle(leaderId);
        leader.setRuntimeStatus("running");
        sessions.saveAndFlush(leader);
        when(sessionService.getSession(leaderId)).thenReturn(leader);

        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> createAfter(start, leaderId));
            var second = pool.submit(() -> createAfter(start, leaderId));
            start.countDown();

            String firstId = first.get(20, TimeUnit.SECONDS);
            String secondId = second.get(20, TimeUnit.SECONDS);
            assertThat(firstId).isEqualTo(secondId);
        } finally {
            pool.shutdownNow();
        }

        List<CollabRunEntity> leaderRuns = collabs.findAll().stream()
                .filter(run -> leaderId.equals(run.getLeaderSessionId()))
                .toList();
        assertThat(leaderRuns).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo("RUNNING");
            assertThat(sessions.findById(leaderId).orElseThrow().getCollabRunId())
                    .isEqualTo(run.getCollabRunId());
        });
    }

    private String createAfter(CountDownLatch start, String leaderId) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start barrier timed out");
            }
            return service.createRun(leaderId, 2, 20).getCollabRunId();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", error);
        }
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
        @Bean SessionService sessionService() { return mock(SessionService.class); }
        @Bean AgentService agentService() { return mock(AgentService.class); }
        @Bean SubAgentRegistry subAgentRegistry() { return mock(SubAgentRegistry.class); }
        @Bean AgentRoster agentRoster() { return mock(AgentRoster.class); }
        @Bean CancellationRegistry cancellationRegistry() { return mock(CancellationRegistry.class); }
        @Bean ObjectProvider<ChatService> chatServiceProvider() {
            @SuppressWarnings("unchecked")
            ObjectProvider<ChatService> provider = mock(ObjectProvider.class);
            return provider;
        }
        @Bean ChatEventBroadcaster broadcaster() { return mock(ChatEventBroadcaster.class); }
    }
}
