package com.skillforge.server.repository;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.EvalTaskItemEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVAL-V2 M3a (b2): integration test for the new {@code t_eval_task_item} table
 * + {@link EvalTaskItemRepository}. Verifies CRUD and the queries the new
 * Items tab + OBS drill-down rely on.
 */
@DisplayName("EvalTaskItemRepository integration tests")
class EvalTaskItemRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private EvalTaskRepository evalTaskRepository;

    @Autowired
    private EvalTaskItemRepository evalTaskItemRepository;

    @BeforeEach
    void cleanUp() {
        evalTaskItemRepository.deleteAll();
        evalTaskRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private EvalTaskEntity buildTask() {
        EvalTaskEntity task = new EvalTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setAgentDefinitionId("123");
        task.setStatus("RUNNING");
        return task;
    }

    private EvalTaskItemEntity buildItem(String taskId, String scenarioId, String status) {
        EvalTaskItemEntity item = new EvalTaskItemEntity();
        item.setTaskId(taskId);
        item.setScenarioId(scenarioId);
        item.setStatus(status);
        item.setCompositeScore(new BigDecimal("82.50"));
        item.setLoopCount(3);
        item.setToolCallCount(5);
        item.setLatencyMs(1234L);
        item.setAttribution("NONE");
        item.setSessionId("eval-session-" + UUID.randomUUID());
        item.setRootTraceId("trace-" + UUID.randomUUID());
        item.setStartedAt(Instant.now().minusSeconds(10));
        item.setCompletedAt(Instant.now());
        item.touchCreatedAtIfMissing();
        return item;
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("save_and_findById persists entity and retrieves it")
    void save_and_findById_persistsAndRetrieves() {
        EvalTaskEntity task = evalTaskRepository.save(buildTask());
        EvalTaskItemEntity item = evalTaskItemRepository.save(
                buildItem(task.getId(), "scenario-A", "PASS"));
        assertThat(item.getId()).isNotNull();

        EvalTaskItemEntity loaded = evalTaskItemRepository.findById(item.getId()).orElseThrow();
        assertThat(loaded.getTaskId()).isEqualTo(task.getId());
        assertThat(loaded.getScenarioId()).isEqualTo("scenario-A");
        assertThat(loaded.getStatus()).isEqualTo("PASS");
        assertThat(loaded.getCompositeScore()).isEqualByComparingTo("82.50");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByTaskIdOrderByCreatedAtAsc preserves insertion order")
    void findByTaskId_returnsItemsInCreationOrder() {
        EvalTaskEntity task = evalTaskRepository.save(buildTask());
        EvalTaskItemEntity first = evalTaskItemRepository.save(
                buildItem(task.getId(), "scenario-1", "PASS"));
        // Force a slightly later created_at to make the ordering observable
        // (DB DEFAULT now() resolves at INSERT time but on fast inserts within
        // the same μs the order can flip — we control it explicitly).
        EvalTaskItemEntity second = buildItem(task.getId(), "scenario-2", "FAIL");
        second.setCreatedAt(first.getCreatedAt().plusMillis(10));
        evalTaskItemRepository.save(second);

        List<EvalTaskItemEntity> items = evalTaskItemRepository
                .findByTaskIdOrderByCreatedAtAsc(task.getId());
        assertThat(items).extracting(EvalTaskItemEntity::getScenarioId)
                .containsExactly("scenario-1", "scenario-2");
    }

    @Test
    @DisplayName("findByScenarioIdOrderByCreatedAtDesc returns runs across tasks newest first")
    void findByScenarioId_returnsAcrossTasksNewestFirst() {
        EvalTaskEntity older = evalTaskRepository.save(buildTask());
        EvalTaskItemEntity olderItem = buildItem(older.getId(), "shared-scenario", "PASS");
        olderItem.setCreatedAt(Instant.now().minusSeconds(60));
        evalTaskItemRepository.save(olderItem);

        EvalTaskEntity newer = evalTaskRepository.save(buildTask());
        EvalTaskItemEntity newerItem = buildItem(newer.getId(), "shared-scenario", "FAIL");
        newerItem.setCreatedAt(Instant.now());
        evalTaskItemRepository.save(newerItem);

        List<EvalTaskItemEntity> hits = evalTaskItemRepository
                .findByScenarioIdOrderByCreatedAtDesc("shared-scenario");
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).getStatus()).isEqualTo("FAIL");
        assertThat(hits.get(1).getStatus()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("findBySessionId surfaces the eval item linked to a chat session")
    void findBySessionId_returnsLinkedItem() {
        EvalTaskEntity task = evalTaskRepository.save(buildTask());
        EvalTaskItemEntity item = buildItem(task.getId(), "scenario-A", "PASS");
        item.setSessionId("eval-session-XYZ");
        evalTaskItemRepository.save(item);

        List<EvalTaskItemEntity> hits = evalTaskItemRepository.findBySessionId("eval-session-XYZ");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getScenarioId()).isEqualTo("scenario-A");
    }

    @Test
    @DisplayName("countByTaskId returns the number of items for a task")
    void countByTaskId_returnsCount() {
        EvalTaskEntity task = evalTaskRepository.save(buildTask());
        evalTaskItemRepository.save(buildItem(task.getId(), "s1", "PASS"));
        evalTaskItemRepository.save(buildItem(task.getId(), "s2", "FAIL"));
        evalTaskItemRepository.save(buildItem(task.getId(), "s3", "PASS"));

        assertThat(evalTaskItemRepository.countByTaskId(task.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("FK ON DELETE CASCADE: deleting task deletes its items")
    void cascadeDelete_dropsItemsWhenTaskDeleted() {
        EvalTaskEntity task = evalTaskRepository.save(buildTask());
        evalTaskItemRepository.save(buildItem(task.getId(), "s1", "PASS"));
        evalTaskItemRepository.save(buildItem(task.getId(), "s2", "FAIL"));
        // Force flush so the DELETE goes through with FK cascade visible.
        evalTaskItemRepository.flush();
        assertThat(evalTaskItemRepository.countByTaskId(task.getId())).isEqualTo(2);

        evalTaskRepository.deleteById(task.getId());
        evalTaskRepository.flush();

        assertThat(evalTaskItemRepository.countByTaskId(task.getId())).isZero();
    }
}
