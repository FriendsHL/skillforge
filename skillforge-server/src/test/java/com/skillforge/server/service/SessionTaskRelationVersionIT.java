package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionTaskEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionTaskDependencyRepository;
import com.skillforge.server.repository.SessionTaskRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Session task relation version persistence")
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SessionTaskRelationVersionIT {

    private static final long USER_ID = 91L;
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionTaskRepository taskRepository;
    @Autowired private SessionTaskDependencyRepository dependencyRepository;
    @Autowired private EntityManager entityManager;

    private SessionTaskService service;
    private String sessionId;

    @BeforeEach
    void setUp() {
        service = new SessionTaskService(
                sessionRepository,
                taskRepository,
                dependencyRepository,
                new ObjectMapper().findAndRegisterModules(),
                mock(ApplicationEventPublisher.class),
                Clock.systemUTC());
        sessionId = UUID.randomUUID().toString();
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        session.setAgentId(1L);
        session.setTitle("Task relation version IT");
        sessionRepository.saveAndFlush(session);
    }

    @Test
    @DisplayName("adding a dependency advances versions for both affected task projections")
    void update_addBlockedBy_advancesAffectedTaskVersions() {
        String blockerId = createTask("Blocker");
        String dependentId = createTask("Dependent");
        long blockerVersionBefore = persistedVersion(blockerId);
        long dependentVersionBefore = persistedVersion(dependentId);

        service.update(sessionId, USER_ID, dependentId, new SessionTaskService.UpdateCommand(
                null, null, null, null,
                false, null,
                false, Map.of(),
                List.of(blockerId), List.of(), List.of(), List.of()));
        entityManager.flush();
        entityManager.clear();

        assertThat(persistedVersion(blockerId)).isGreaterThan(blockerVersionBefore);
        assertThat(persistedVersion(dependentId)).isGreaterThan(dependentVersionBefore);
    }

    private String createTask(String subject) {
        return service.create(sessionId, USER_ID, new SessionTaskService.CreateCommand(
                subject, subject + " description", subject + " active",
                null, Map.of(), List.of())).task().taskId();
    }

    private long persistedVersion(String taskId) {
        entityManager.flush();
        entityManager.clear();
        return taskRepository.findById(taskId)
                .map(SessionTaskEntity::getVersion)
                .orElseThrow();
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
