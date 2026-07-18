package com.skillforge.server.service;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAttachmentGeneratedImportTest {

    @Mock
    private ChatAttachmentRepository repository;

    @TempDir
    Path tempDir;

    private Path storageRoot;
    private Path stagingRoot;
    private ChatAttachmentService service;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = Files.createDirectories(tempDir.resolve("managed"));
        stagingRoot = Files.createDirectories(tempDir.resolve("staging/7/session-1/trace-1"));
        service = new ChatAttachmentService(repository, storageRoot.toString());
    }

    @Test
    void importsWithHashAndDeterministicSessionToolIdempotency() throws Exception {
        Path source = stagingRoot.resolve("report.pdf");
        Files.writeString(source, "%PDF-1.4\nartifact");
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-1"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ChatAttachmentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ChatAttachmentEntity imported = service.importGeneratedFile(
                "session-1", 7L, "tool-1", source, "Quarterly report", stagingRoot);

        assertThat(imported.getOrigin()).isEqualTo("agent_generated");
        assertThat(imported.getSourceToolUseId()).isEqualTo("tool-1");
        assertThat(imported.getSha256()).hasSize(64);
        assertThat(imported.getCaption()).isEqualTo("Quarterly report");
        assertThat(Path.of(imported.getStoragePath())).isRegularFile();
        assertThat(Path.of(imported.getStoragePath()).getParent())
                .isEqualTo(storageRoot.resolve("session-1"));
        assertThat(Files.list(storageRoot.resolve("session-1")))
                .noneMatch(path -> path.getFileName().toString().endsWith(".part"));
    }

    @Test
    void replayReturnsExistingOnlyWhenContentHashMatches() throws Exception {
        Path source = stagingRoot.resolve("report.pdf");
        Files.writeString(source, "%PDF-1.4\nsame");
        ChatAttachmentEntity existing = new ChatAttachmentEntity();
        existing.setId("existing");
        existing.setSessionId("session-1");
        existing.setSourceToolUseId("tool-1");
        existing.setSha256(ChatAttachmentService.sha256(source));
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-1"))
                .thenReturn(Optional.of(existing));
        when(repository.reserveForPublishing(any(), any())).thenReturn(1);

        ChatAttachmentEntity result = service.importGeneratedFile(
                "session-1", 7L, "tool-1", source, null, stagingRoot);

        assertThat(result).isSameAs(existing);
        verify(repository, never()).saveAndFlush(any());

        Files.writeString(source, "%PDF-1.4\ndifferent");
        assertThatThrownBy(() -> service.importGeneratedFile(
                "session-1", 7L, "tool-1", source, null, stagingRoot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different content");
    }

    @Test
    void rejectsSymlinkEscapeAndCrossSessionManagedPath() throws Exception {
        Path outside = tempDir.resolve("outside.pdf");
        Files.writeString(outside, "%PDF-1.4\noutside");
        Path symlink = stagingRoot.resolve("escape.pdf");
        Files.createSymbolicLink(symlink, outside);

        assertThatThrownBy(() -> service.importGeneratedFile(
                "session-1", 7L, "tool-1", symlink, null, stagingRoot))
                .isInstanceOf(SecurityException.class);

        Path otherSession = Files.createDirectories(storageRoot.resolve("session-2")).resolve("other.pdf");
        Files.writeString(otherSession, "%PDF-1.4\nother");
        assertThatThrownBy(() -> service.importGeneratedFile(
                "session-1", 7L, "tool-2", otherSession, null, stagingRoot))
                .isInstanceOf(SecurityException.class);

        Path outsideDirectory = Files.createDirectories(tempDir.resolve("outside-directory"));
        Files.writeString(outsideDirectory.resolve("nested.pdf"), "%PDF-1.4\nnested");
        Files.createSymbolicLink(stagingRoot.resolve("linked-directory"), outsideDirectory);
        assertThatThrownBy(() -> service.importGeneratedFile(
                "session-1", 7L, "tool-3",
                stagingRoot.resolve("linked-directory/nested.pdf"), null, stagingRoot))
                .isInstanceOfAny(SecurityException.class, IllegalStateException.class);
    }

    @Test
    void rejectsHistoricalFileFromTheSameSessionManagedDirectory() throws Exception {
        Path historical = Files.createDirectories(storageRoot.resolve("session-1"))
                .resolve("historical.pdf");
        Files.writeString(historical, "%PDF-1.4\nhistorical");

        assertThatThrownBy(() -> service.importGeneratedFile(
                "session-1", 7L, "tool-history", historical, null, stagingRoot))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("current run workspace");

        assertThat(historical).hasContent("%PDF-1.4\nhistorical");
        verify(repository, never()).findBySessionIdAndSourceToolUseId(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void retryReplacesStaleZeroByteTargetWhenDatabaseRowWasNeverCreated() throws Exception {
        Path source = stagingRoot.resolve("report.pdf");
        Files.writeString(source, "%PDF-1.4\nrecovered");
        String id = UUID.nameUUIDFromBytes("session-1\ntool-crash".getBytes(StandardCharsets.UTF_8)).toString();
        Path staleTarget = Files.createDirectories(storageRoot.resolve("session-1")).resolve(id + ".pdf");
        Files.createFile(staleTarget);
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-crash"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ChatAttachmentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ChatAttachmentEntity imported = service.importGeneratedFile(
                "session-1", 7L, "tool-crash", source, null, stagingRoot);

        assertThat(Path.of(imported.getStoragePath())).hasContent("%PDF-1.4\nrecovered");
        assertThat(imported.getSizeBytes()).isPositive();
    }

    @Test
    void pathReplacementAfterOpenIsRejectedOnVerifiedFallback() throws Exception {
        Path source = stagingRoot.resolve("report.pdf");
        Files.writeString(source, "%PDF-1.4\noriginal");
        Path movedOriginal = stagingRoot.resolve("opened-original.pdf");
        service = new ChatAttachmentService(repository, storageRoot.toString(), opened -> {
            try {
                Files.move(opened, movedOriginal);
                Files.writeString(opened, "%PDF-1.4\nreplacement");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThatThrownBy(() -> service.importGeneratedFile(
                "session-1", 7L, "tool-race", source, null, stagingRoot))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("changed");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void serializesConcurrentImportsForTheSameSessionAndToolKey() throws Exception {
        Path source = stagingRoot.resolve("report.pdf");
        Files.writeString(source, "%PDF-1.4\nconcurrent");
        CountDownLatch firstOpened = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        service = new ChatAttachmentService(repository, storageRoot.toString(), ignored -> {
            int now = active.incrementAndGet();
            maximum.accumulateAndGet(now, Math::max);
            firstOpened.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                active.decrementAndGet();
            }
        });
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-concurrent"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ChatAttachmentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.importGeneratedFile(
                    "session-1", 7L, "tool-concurrent", source, null, stagingRoot));
            assertThat(firstOpened.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.importGeneratedFile(
                    "session-1", 7L, "tool-concurrent", source, null, stagingRoot));
            Thread.sleep(100);
            assertThat(maximum).hasValue(1);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(maximum).hasValue(1);
    }

    @Test
    void databaseConflictDeletesOnlyTheLosingPhysicalAttempt() throws Exception {
        Path source = stagingRoot.resolve("report.pdf");
        Files.writeString(source, "%PDF-1.4\nconcurrent winner");
        ChatAttachmentEntity winner = new ChatAttachmentEntity();
        winner.setId("winner");
        winner.setSessionId("session-1");
        winner.setSourceToolUseId("tool-cross-jvm");
        winner.setSha256(ChatAttachmentService.sha256(source));
        winner.setStoragePath(storageRoot.resolve("session-1/winner.pdf").toString());
        when(repository.findBySessionIdAndSourceToolUseId("session-1", "tool-cross-jvm"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        winner.setStatus("published");
        when(repository.saveAndFlush(any(ChatAttachmentEntity.class)))
                .thenThrow(new DataIntegrityViolationException("simulated concurrent insert"));

        ChatAttachmentEntity result = service.importGeneratedFile(
                "session-1", 7L, "tool-cross-jvm", source, null, stagingRoot);

        assertThat(result).isSameAs(winner);
        try (var files = Files.list(storageRoot.resolve("session-1"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void marksReferencedGeneratedAttachmentPublishedWithAtomicStatusTransition() {
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(List.of(
                ContentBlock.imageRef("generated-1", "image/png", "chart.png")));

        service.markPublishedFromMessages(List.of(assistant));

        verify(repository).markGeneratedPublished("generated-1");
        verify(repository, never()).findAllById(any());
    }
}
