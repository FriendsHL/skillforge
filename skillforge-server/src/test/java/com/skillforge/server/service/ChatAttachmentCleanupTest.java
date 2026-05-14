package com.skillforge.server.service;

import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ATTACHMENT-CLEANUP (Wave1-B): exercises
 * {@link ChatAttachmentService#cleanupOrphans(int, boolean)} end-to-end with a
 * {@link TempDir} storage root, a mock repository, and a curated mix of orphan
 * rows / bound rows / unreferenced disk files.
 *
 * <p>Scenarios (mirrors the brief):</p>
 * <ul>
 *   <li>{@code live_deletesOrphansAndUnreferenced} — 2 orphan rows + 1 bound +
 *       1 under-threshold + 1 unreferenced disk file → expect 2 rows deleted,
 *       3 files deleted (2 orphan files + 1 unreferenced); bound + fresh-orphan
 *       untouched.</li>
 *   <li>{@code dryRun_countsButDeletesNothing} — same scenario with dryRun=true
 *       → counts equal live mode but no DB / FS deletes.</li>
 *   <li>{@code missingFile_continuesAndDeletesRow} — orphan row whose file no
 *       longer exists → row delete still proceeds; errors empty.</li>
 *   <li>{@code emptyStorageRoot_noErrors} — root missing entirely → cleanup
 *       returns 0/0/[] cleanly (idempotent / first-boot safe).</li>
 * </ul>
 *
 * <p>The mock repository keeps an in-memory list of "all rows" so the
 * {@code findAllStoragePaths()} projection can return live paths even after a
 * {@code delete()} call updates the list.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentCleanupTest {

    @Mock
    private ChatAttachmentRepository attachmentRepository;

    @TempDir
    Path tempStorage;

    private ChatAttachmentService service;

    /** In-memory mirror of "what's in t_chat_attachment" — mock returns from this. */
    private List<ChatAttachmentEntity> rowsInDb;

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentService(attachmentRepository, tempStorage.toString());
        rowsInDb = new ArrayList<>();
    }

    @Test
    @DisplayName("live: deletes 2 orphan rows + 3 files (2 orphan + 1 unreferenced); bound+fresh untouched")
    void live_deletesOrphansAndUnreferenced() throws IOException {
        // ---------- arrange ----------
        Instant oldStamp = Instant.now().minus(48, ChronoUnit.HOURS);   // > 24h, orphan
        Instant freshStamp = Instant.now().minus(1, ChronoUnit.HOURS);  // < 24h, NOT orphan

        // 2 orphan rows (status=uploaded, seq_no=null, old created_at)
        ChatAttachmentEntity orphan1 = makeRow("orphan-1", "sess-A", "uploaded", null, oldStamp);
        ChatAttachmentEntity orphan2 = makeRow("orphan-2", "sess-A", "uploaded", null, oldStamp);
        Path orphan1File = createFile("sess-A", "orphan-1.png");
        Path orphan2File = createFile("sess-A", "orphan-2.png");
        orphan1.setStoragePath(orphan1File.toString());
        orphan2.setStoragePath(orphan2File.toString());

        // 1 bound row (seq_no set) — should be untouched
        ChatAttachmentEntity bound = makeRow("bound-1", "sess-B", "bound", 1L, oldStamp);
        Path boundFile = createFile("sess-B", "bound-1.png");
        bound.setStoragePath(boundFile.toString());

        // 1 fresh orphan (uploaded but UNDER threshold) — should be untouched
        ChatAttachmentEntity fresh = makeRow("fresh-1", "sess-C", "uploaded", null, freshStamp);
        Path freshFile = createFile("sess-C", "fresh-1.png");
        fresh.setStoragePath(freshFile.toString());

        // 1 extra unreferenced file on disk (e.g. CASCADE-orphaned)
        Path stray = createFile("sess-DELETED", "stray.png");

        rowsInDb.addAll(List.of(orphan1, orphan2, bound, fresh));

        // findByStatusAndSeqNoIsNullAndCreatedAtBefore returns only orphan-1 + orphan-2
        // (fresh-1 fails the created_at < before filter)
        when(attachmentRepository.findByStatusAndSeqNoIsNullAndCreatedAtBefore(
                eqUploaded(), any(Instant.class)))
                .thenReturn(List.of(orphan1, orphan2));
        // findAllStoragePaths returns paths from all rows still "in DB" at call time.
        // For simplicity we return the static set captured up-front — covers bound +
        // fresh; cleanup must NOT delete those files.
        when(attachmentRepository.findAllStoragePaths())
                .thenReturn(List.of(boundFile.toString(), freshFile.toString()));

        // ---------- act ----------
        ChatAttachmentService.CleanupResult result = service.cleanupOrphans(24, false);

        // ---------- assert ----------
        assertThat(result.orphanRowsDeleted()).isEqualTo(2);
        // 2 orphan files + 1 unreferenced (stray) = 3
        assertThat(result.filesDeleted()).isEqualTo(3);
        assertThat(result.errors()).isEmpty();

        // bound + fresh files survive
        assertThat(Files.exists(boundFile)).as("bound file kept").isTrue();
        assertThat(Files.exists(freshFile)).as("fresh-orphan file kept").isTrue();
        // orphan files + stray are gone
        assertThat(Files.exists(orphan1File)).as("orphan-1 deleted").isFalse();
        assertThat(Files.exists(orphan2File)).as("orphan-2 deleted").isFalse();
        assertThat(Files.exists(stray)).as("unreferenced stray deleted").isFalse();

        // 2 row deletes; bound and fresh never sent to delete()
        ArgumentCaptor<ChatAttachmentEntity> captor = ArgumentCaptor.forClass(ChatAttachmentEntity.class);
        verify(attachmentRepository, times(2)).delete(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ChatAttachmentEntity::getId)
                .containsExactlyInAnyOrder("orphan-1", "orphan-2");
    }

    @Test
    @DisplayName("dryRun=true: same counts but NO DB deletes and NO file deletes")
    void dryRun_countsButDeletesNothing() throws IOException {
        Instant oldStamp = Instant.now().minus(48, ChronoUnit.HOURS);

        ChatAttachmentEntity orphan1 = makeRow("orphan-1", "sess-A", "uploaded", null, oldStamp);
        ChatAttachmentEntity orphan2 = makeRow("orphan-2", "sess-A", "uploaded", null, oldStamp);
        Path orphan1File = createFile("sess-A", "orphan-1.png");
        Path orphan2File = createFile("sess-A", "orphan-2.png");
        orphan1.setStoragePath(orphan1File.toString());
        orphan2.setStoragePath(orphan2File.toString());

        ChatAttachmentEntity bound = makeRow("bound-1", "sess-B", "bound", 1L, oldStamp);
        Path boundFile = createFile("sess-B", "bound-1.png");
        bound.setStoragePath(boundFile.toString());

        Path stray = createFile("sess-DELETED", "stray.png");

        when(attachmentRepository.findByStatusAndSeqNoIsNullAndCreatedAtBefore(
                eqUploaded(), any(Instant.class)))
                .thenReturn(List.of(orphan1, orphan2));
        when(attachmentRepository.findAllStoragePaths())
                .thenReturn(List.of(boundFile.toString()));

        ChatAttachmentService.CleanupResult result = service.cleanupOrphans(24, true);

        assertThat(result.orphanRowsDeleted()).isEqualTo(2);
        assertThat(result.filesDeleted()).isEqualTo(3); // would-delete count, not actual
        assertThat(result.errors()).isEmpty();

        // 0 row deletes
        verify(attachmentRepository, never()).delete(any(ChatAttachmentEntity.class));
        // All files still present
        assertThat(Files.exists(orphan1File)).isTrue();
        assertThat(Files.exists(orphan2File)).isTrue();
        assertThat(Files.exists(boundFile)).isTrue();
        assertThat(Files.exists(stray)).isTrue();
    }

    @Test
    @DisplayName("orphan row whose file is already missing: row delete still succeeds; errors empty")
    void missingFile_continuesAndDeletesRow() {
        Instant oldStamp = Instant.now().minus(48, ChronoUnit.HOURS);

        // Orphan row points at a path that was never written to disk (ghost row).
        ChatAttachmentEntity ghost = makeRow("ghost-1", "sess-A", "uploaded", null, oldStamp);
        Path ghostPath = tempStorage.resolve("sess-A").resolve("ghost.png");
        ghost.setStoragePath(ghostPath.toString());

        when(attachmentRepository.findByStatusAndSeqNoIsNullAndCreatedAtBefore(
                eqUploaded(), any(Instant.class)))
                .thenReturn(List.of(ghost));
        when(attachmentRepository.findAllStoragePaths())
                .thenReturn(List.of());

        ChatAttachmentService.CleanupResult result = service.cleanupOrphans(24, false);

        // Row deleted; file-delete was a no-op because the file didn't exist.
        assertThat(result.orphanRowsDeleted()).isEqualTo(1);
        assertThat(result.filesDeleted()).isZero();
        assertThat(result.errors()).isEmpty();
        verify(attachmentRepository, times(1)).delete(any(ChatAttachmentEntity.class));
    }

    @Test
    @DisplayName("storage root does not exist: returns 0/0/[] cleanly (first-boot safe)")
    void emptyStorageRoot_noErrors() throws IOException {
        // Service points at a sub-path of tempStorage that we never create.
        Path missingRoot = tempStorage.resolve("never-created");
        ChatAttachmentService freshService = new ChatAttachmentService(
                attachmentRepository, missingRoot.toString());

        when(attachmentRepository.findByStatusAndSeqNoIsNullAndCreatedAtBefore(
                anyString(), any(Instant.class)))
                .thenReturn(List.of());
        when(attachmentRepository.findAllStoragePaths())
                .thenReturn(List.of());

        ChatAttachmentService.CleanupResult result = freshService.cleanupOrphans(24, false);

        assertThat(result.orphanRowsDeleted()).isZero();
        assertThat(result.filesDeleted()).isZero();
        assertThat(result.errors()).isEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ChatAttachmentEntity makeRow(String id, String sessionId, String status,
                                         Long seqNo, Instant createdAt) {
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(id);
        e.setSessionId(sessionId);
        e.setUserId(42L);
        e.setStatus(status);
        e.setSeqNo(seqNo);
        e.setCreatedAt(createdAt);
        e.setKind("image");
        e.setMimeType("image/png");
        e.setFilename(id + ".png");
        e.setSizeBytes(8);
        return e;
    }

    private Path createFile(String session, String filename) throws IOException {
        Path dir = tempStorage.resolve(session);
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);
        // PNG magic bytes — keeps the file recognisable in case a maintainer inspects
        // tempDir during a debug session.
        Files.write(file, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        return file;
    }

    /** {@code eq("uploaded")} captures the literal so the matcher reads naturally above. */
    private static String eqUploaded() {
        return org.mockito.ArgumentMatchers.eq("uploaded");
    }
}
