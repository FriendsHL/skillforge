package com.skillforge.server.service;

import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.MemorySnapshotEntity;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.repository.MemorySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceTest {

    private final List<MemoryEntity> memoriesForUser = new ArrayList<>();
    private final List<MemoryEntity> titleMatches = new ArrayList<>();
    private final List<MemoryEntity> batchMemories = new ArrayList<>();
    private final List<MemoryEntity> savedMemories = new ArrayList<>();
    private final List<MemoryEntity> deletedMemories = new ArrayList<>();
    private final List<MemorySnapshotEntity> savedSnapshots = new ArrayList<>();
    private final List<MemorySnapshotEntity> batchSnapshots = new ArrayList<>();

    private MemoryEntity findByIdResult;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoriesForUser.clear();
        titleMatches.clear();
        batchMemories.clear();
        savedMemories.clear();
        deletedMemories.clear();
        savedSnapshots.clear();
        batchSnapshots.clear();
        findByIdResult = null;

        MemoryRepository memoryRepository = (MemoryRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{MemoryRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByUserId" -> memoriesForUser;
                    case "findByUserIdAndTitle" -> titleMatches;
                    case "findById" -> Optional.ofNullable(findByIdResult);
                    case "findByExtractionBatchIdAndUserId" -> batchMemories;
                    case "save" -> {
                        MemoryEntity saved = (MemoryEntity) args[0];
                        savedMemories.add(saved);
                        yield saved;
                    }
                    case "delete" -> {
                        deletedMemories.add((MemoryEntity) args[0]);
                        yield null;
                    }
                    default -> null;
                });

        MemorySnapshotRepository snapshotRepository = (MemorySnapshotRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{MemorySnapshotRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "saveAll" -> {
                        Iterable<?> iterable = (Iterable<?>) args[0];
                        for (Object item : iterable) {
                            savedSnapshots.add((MemorySnapshotEntity) item);
                        }
                        yield savedSnapshots;
                    }
                    case "findByExtractionBatchIdAndUserId" -> batchSnapshots;
                    default -> null;
                });

        memoryService = new MemoryService(memoryRepository, snapshotRepository, null, null);
    }

    @Test
    @DisplayName("beginExtractionBatch snapshots existing memories for user")
    void beginExtractionBatch_snapshotsExistingMemories() {
        MemoryEntity existing = memory(7L, 1L, "knowledge", "old", "content");
        existing.setTags("tag");
        existing.setRecallCount(3);
        existing.setLastRecalledAt(Instant.parse("2026-04-25T01:02:03Z"));
        existing.setExtractionBatchId("previous-batch");
        existing.setCreatedAt(LocalDateTime.of(2026, 4, 25, 9, 0));
        existing.setUpdatedAt(LocalDateTime.of(2026, 4, 25, 10, 0));
        memoriesForUser.add(existing);

        String batchId = memoryService.beginExtractionBatch(1L);

        assertThat(batchId).isNotBlank();
        assertThat(savedSnapshots).hasSize(1);
        MemorySnapshotEntity snapshot = savedSnapshots.get(0);
        assertThat(snapshot.getExtractionBatchId()).isEqualTo(batchId);
        assertThat(snapshot.getMemoryId()).isEqualTo(7L);
        assertThat(snapshot.getUserId()).isEqualTo(1L);
        assertThat(snapshot.getTitle()).isEqualTo("old");
        assertThat(snapshot.getSourceExtractionBatchId()).isEqualTo("previous-batch");
    }

    @Test
    @DisplayName("createMemoryIfNotDuplicate marks created memory with extraction batch")
    void createMemoryIfNotDuplicate_newMemory_setsBatchId() {
        memoryService.createMemoryIfNotDuplicate(1L, "knowledge", "new", "content", "auto-extract", "batch-1");

        assertThat(savedMemories).hasSize(1);
        assertThat(savedMemories.get(0).getExtractionBatchId()).isEqualTo("batch-1");
    }

    @Test
    @DisplayName("createMemoryIfNotDuplicate marks updated memory with extraction batch")
    void createMemoryIfNotDuplicate_existingMemory_setsBatchId() {
        MemoryEntity existing = memory(7L, 1L, "knowledge", "title", "old");
        titleMatches.add(existing);

        memoryService.createMemoryIfNotDuplicate(1L, "project", "title", "new", "auto-extract", "batch-1");

        assertThat(existing.getType()).isEqualTo("project");
        assertThat(existing.getContent()).isEqualTo("new");
        assertThat(existing.getExtractionBatchId()).isEqualTo("batch-1");
    }

    @Test
    @DisplayName("manual update clears extraction batch ownership")
    void updateMemory_manualUpdate_clearsBatchId() {
        MemoryEntity existing = memory(7L, 1L, "knowledge", "title", "old");
        existing.setExtractionBatchId("batch-1");
        findByIdResult = existing;
        MemoryEntity replacement = memory(null, 1L, "project", "title", "new");

        MemoryEntity result = memoryService.updateMemory(7L, replacement);

        assertThat(result.getExtractionBatchId()).isNull();
    }

    @Test
    @DisplayName("rollbackExtractionBatch restores updated rows and deletes newly created rows")
    void rollbackExtractionBatch_restoresAndDeletes() {
        MemoryEntity changed = memory(7L, 1L, "project", "title", "new");
        changed.setExtractionBatchId("batch-1");
        MemoryEntity created = memory(8L, 1L, "knowledge", "created", "created content");
        created.setExtractionBatchId("batch-1");
        batchMemories.add(changed);
        batchMemories.add(created);
        batchSnapshots.add(snapshot(7L, 1L, "knowledge", "title", "old", "previous-batch"));

        MemoryService.RollbackResult result = memoryService.rollbackExtractionBatch("batch-1", 1L);

        assertThat(result.restored()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(changed.getType()).isEqualTo("knowledge");
        assertThat(changed.getContent()).isEqualTo("old");
        assertThat(changed.getExtractionBatchId()).isEqualTo("previous-batch");
        assertThat(deletedMemories).containsExactly(created);
    }

    @Test
    @DisplayName("beginExtractionBatch carries Memory v2 status / importance / score into snapshot")
    void beginExtractionBatch_carriesMemoryV2Fields() {
        MemoryEntity existing = memory(7L, 1L, "knowledge", "old", "content");
        existing.setStatus("STALE");
        existing.setImportance("high");
        existing.setLastScore(0.42);
        existing.setLastScoredAt(Instant.parse("2026-04-26T00:00:00Z"));
        existing.setArchivedAt(Instant.parse("2026-04-25T00:00:00Z"));
        memoriesForUser.add(existing);

        memoryService.beginExtractionBatch(1L);

        assertThat(savedSnapshots).hasSize(1);
        MemorySnapshotEntity snapshot = savedSnapshots.get(0);
        assertThat(snapshot.getStatus()).isEqualTo("STALE");
        assertThat(snapshot.getImportance()).isEqualTo("high");
        assertThat(snapshot.getLastScore()).isEqualTo(0.42);
        assertThat(snapshot.getLastScoredAt()).isEqualTo(Instant.parse("2026-04-26T00:00:00Z"));
        assertThat(snapshot.getArchivedAt()).isEqualTo(Instant.parse("2026-04-25T00:00:00Z"));
    }

    @Test
    @DisplayName("rollbackExtractionBatch restores Memory v2 status / importance / score fields")
    void rollbackExtractionBatch_restoresMemoryV2Fields() {
        MemoryEntity changed = memory(7L, 1L, "project", "title", "new");
        changed.setExtractionBatchId("batch-1");
        // Simulate post-batch state: status promoted, importance bumped, score recomputed
        changed.setStatus("ACTIVE");
        changed.setImportance("low");
        changed.setLastScore(0.1);
        changed.setLastScoredAt(Instant.parse("2026-04-27T10:00:00Z"));
        batchMemories.add(changed);

        MemorySnapshotEntity snap = snapshot(7L, 1L, "knowledge", "title", "old", "previous-batch");
        snap.setStatus("STALE");
        snap.setImportance("high");
        snap.setLastScore(0.9);
        snap.setLastScoredAt(Instant.parse("2026-04-26T00:00:00Z"));
        snap.setArchivedAt(Instant.parse("2026-04-25T00:00:00Z"));
        batchSnapshots.add(snap);

        MemoryService.RollbackResult result = memoryService.rollbackExtractionBatch("batch-1", 1L);

        assertThat(result.restored()).isEqualTo(1);
        assertThat(changed.getStatus()).isEqualTo("STALE");
        assertThat(changed.getImportance()).isEqualTo("high");
        assertThat(changed.getLastScore()).isEqualTo(0.9);
        assertThat(changed.getLastScoredAt()).isEqualTo(Instant.parse("2026-04-26T00:00:00Z"));
        assertThat(changed.getArchivedAt()).isEqualTo(Instant.parse("2026-04-25T00:00:00Z"));
    }

    @Test
    @DisplayName("rollbackExtractionBatch ignores blank batch id")
    void rollbackExtractionBatch_blankBatchId_noops() {
        MemoryService.RollbackResult result = memoryService.rollbackExtractionBatch("   ", 1L);

        assertThat(result.restored()).isZero();
        assertThat(result.deleted()).isZero();
        assertThat(savedMemories).isEmpty();
    }

    private static MemoryEntity memory(Long id, Long userId, String type, String title, String content) {
        MemoryEntity memory = new MemoryEntity();
        memory.setId(id);
        memory.setUserId(userId);
        memory.setType(type);
        memory.setTitle(title);
        memory.setContent(content);
        return memory;
    }

    private static MemorySnapshotEntity snapshot(Long memoryId, Long userId, String type,
                                                 String title, String content,
                                                 String sourceBatchId) {
        MemorySnapshotEntity snapshot = new MemorySnapshotEntity();
        snapshot.setExtractionBatchId("batch-1");
        snapshot.setMemoryId(memoryId);
        snapshot.setUserId(userId);
        snapshot.setType(type);
        snapshot.setTitle(title);
        snapshot.setContent(content);
        snapshot.setSourceExtractionBatchId(sourceBatchId);
        return snapshot;
    }
}
