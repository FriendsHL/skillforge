package com.skillforge.server.service.event;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SubAgentPendingResultRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.subagent.SubAgentRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TeamTaskAvailableListener.class, SubAgentRegistry.class,
        TeamTaskAvailableListenerIT.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeamTaskAvailableListenerIT {
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
    @Autowired private SubAgentPendingResultRepository pending;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void afterCommitTaskAvailablePersistsMailboxMessage() {
        String collabRunId = "team-mailbox-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String actorId = java.util.UUID.randomUUID().toString();
        String peerId = java.util.UUID.randomUUID().toString();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            sessions.save(member(actorId, collabRunId));
            sessions.save(member(peerId, collabRunId));
            events.publishEvent(new TeamTaskAvailableEvent(42L, collabRunId,
                    actorId, "task-1", "Review"));
        });

        assertThat(pending.findByTargetSessionIdAndStatusIsNullOrderBySeqNoAsc(peerId))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getMessageId())
                            .isEqualTo(TeamTaskAvailableListener.messageIdFor(42L, peerId))
                            .hasSize(36);
                    assertThat(message.getPayload()).contains("[TeamTaskEvent]", "task-1", "availableOnly=true");
                });
        assertThat(pending.findByTargetSessionIdAndStatusIsNullOrderBySeqNoAsc(actorId)).isEmpty();
    }

    private static SessionEntity member(String id, String collabRunId) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        session.setUserId(7L);
        session.setAgentId(2L);
        session.setTitle(id);
        session.setRuntimeStatus("running");
        session.setCollabRunId(collabRunId);
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
        @Bean
        ObjectProvider<ChatService> chatServiceProvider() {
            @SuppressWarnings("unchecked")
            ObjectProvider<ChatService> provider = mock(ObjectProvider.class);
            return provider;
        }
    }
}
