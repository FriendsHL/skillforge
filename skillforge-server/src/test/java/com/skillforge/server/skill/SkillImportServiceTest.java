package com.skillforge.server.skill;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-IMPORT — covers AC-1, AC-2, AC-3, AC-4, AC-5 with real file IO + a real
 * {@link SkillCatalogReconciler} (Mockito-stubbed JPA collaborators).
 *
 * <p>Choosing a real reconciler/loader/storage instance over deeper mocking
 * keeps the hash-equivalence guarantee (AC-1 / AC-2 / AC-3) honest — if anyone
 * later changes the reconciler's hash algorithm, this test will fail.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillImportServiceTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillConflictResolver conflictResolver;
    @Mock private SkillForgeHomeResolver homeResolver;

    private SkillImportProperties properties;
    private SkillStorageService storageService;
    private SkillCatalogReconciler reconciler;
    private SkillPackageLoader packageLoader;
    private ObjectMapper objectMapper;
    private SkillImportService service;

    private Path runtimeRoot;
    private Path workspaceRoot;
    private Path home;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws IOException {
        home = tmp;
        runtimeRoot = home.resolve("data/skills");
        workspaceRoot = home.resolve("workspace/skills");
        Files.createDirectories(runtimeRoot);
        Files.createDirectories(workspaceRoot);

        when(homeResolver.getRuntimeRoot()).thenReturn(runtimeRoot);
        storageService = new SkillStorageService(homeResolver);
        packageLoader = new SkillPackageLoader();
        reconciler = new SkillCatalogReconciler(homeResolver, skillRepository, packageLoader, conflictResolver);
        objectMapper = new ObjectMapper();

        properties = new SkillImportProperties();
        properties.setAllowedSourceRoots(List.of(workspaceRoot.toString()));

        service = new SkillImportService(properties, storageService, skillRepository,
                skillRegistry, packageLoader, reconciler, objectMapper);

        // Default save() = identity-injecting passthrough for applyUpdate paths only.
        AtomicLong idSeq = new AtomicLong(1);
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> {
            SkillEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(idSeq.getAndIncrement());
            }
            return e;
        });
        when(skillRepository.findByOwnerIdAndNameAndSourceAndIsSystem(any(), any(), any(), eq(false)))
                .thenReturn(Optional.empty());

        // Default insertImportedSkillIgnoreConflict = "we won the insert" (rows=1) and
        // a subsequent re-lookup yields the persisted row with id 1 carrying the
        // payload we just inserted.
        AtomicLong insertedId = new AtomicLong(0);
        when(skillRepository.insertImportedSkillIgnoreConflict(
                any(), anyString(), any(), any(), any(),
                anyString(), anyString(), any(),
                anyString(), any(Instant.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    long id = idSeq.getAndIncrement();
                    insertedId.set(id);
                    SkillEntity e = new SkillEntity();
                    e.setId(id);
                    e.setOwnerId(inv.getArgument(0));
                    e.setName(inv.getArgument(1));
                    e.setDescription(inv.getArgument(2));
                    e.setTriggers(inv.getArgument(3));
                    e.setRequiredTools(inv.getArgument(4));
                    e.setSkillPath(inv.getArgument(5));
                    e.setSource(inv.getArgument(6));
                    e.setVersion(inv.getArgument(7));
                    e.setContentHash(inv.getArgument(8));
                    e.setLastScannedAt(inv.getArgument(9));
                    e.setSystem(false);
                    e.setEnabled(true);
                    e.setArtifactStatus("active");
                    when(skillRepository.findByOwnerIdAndNameAndSourceAndIsSystem(
                            eq((Long) inv.getArgument(0)),
                            eq((String) inv.getArgument(1)),
                            eq((String) inv.getArgument(6)),
                            eq(false)))
                            .thenReturn(Optional.of(e));
                    return 1;
                });

        // Attach a logback ListAppender to capture WARN messages emitted by the service.
        serviceLogger = (Logger) LoggerFactory.getLogger(SkillImportService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (serviceLogger != null && logAppender != null) {
            serviceLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    private Path writeSkillPackage(Path dir, String name, String description) throws IOException {
        Files.createDirectories(dir);
        String md = "---\nname: " + name + "\n"
                + "description: " + description + "\n"
                + "---\n\nBody.\n";
        Files.writeString(dir.resolve("SKILL.md"), md);
        return dir;
    }

    private void writeMetaJson(Path dir, String slug, String version) throws IOException {
        Files.writeString(dir.resolve("_meta.json"),
                "{\"slug\":\"" + slug + "\",\"version\":\"" + version + "\"}");
    }

    private long warnEventsContaining(String... substrings) {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(msg -> {
                    for (String s : substrings) {
                        if (!msg.contains(s)) return false;
                    }
                    return true;
                })
                .count();
    }

    @Test
    @DisplayName("importSkill_freshClawhubPackage_createsRowAndCopiesFiles")
    void importSkill_freshClawhubPackage_createsRowAndCopiesFiles() throws IOException {
        Path source = workspaceRoot.resolve("tool-call-retry");
        writeSkillPackage(source, "tool-call-retry", "Retry on tool failures");
        writeMetaJson(source, "tool-call-retry", "1.0.0");

        ImportResult result = service.importSkill(source, SkillSource.CLAWHUB, 7L);

        assertThat(result.conflictResolved()).isFalse();
        assertThat(result.name()).isEqualTo("tool-call-retry");
        assertThat(result.source()).isEqualTo("clawhub");
        Path expectedTarget = runtimeRoot.resolve("clawhub/tool-call-retry/1.0.0");
        assertThat(Path.of(result.skillPath())).isEqualTo(expectedTarget);
        assertThat(Files.isRegularFile(expectedTarget.resolve("SKILL.md"))).isTrue();

        // Native insert path was used (not JPA save) — matches tech-design D6/judge MUST-2.
        verify(skillRepository).insertImportedSkillIgnoreConflict(
                eq(7L), eq("tool-call-retry"), any(), any(), any(),
                eq(expectedTarget.toString()), eq("clawhub"), eq("1.0.0"),
                anyString(), any(Instant.class), any(Instant.class));
        verify(skillRepository, never()).save(any(SkillEntity.class));

        verify(skillRegistry).registerSkillDefinition(any(SkillDefinition.class));
    }

    @Test
    @DisplayName("importSkill_contentHashMatchesReconcilerAlgorithm")
    void importSkill_contentHashMatchesReconcilerAlgorithm() throws IOException {
        Path source = workspaceRoot.resolve("tool-call-retry");
        writeSkillPackage(source, "tool-call-retry", "Retry on tool failures");
        writeMetaJson(source, "tool-call-retry", "1.0.0");

        ImportResult result = service.importSkill(source, SkillSource.CLAWHUB, 7L);

        // Reconciler must compute the same hash on the freshly-copied target,
        // otherwise the next reconciler pass will treat the row as content-changed
        // and rewrite it (per design D6 in tech-design).
        Path target = Path.of(result.skillPath());
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(skillRepository).insertImportedSkillIgnoreConflict(
                any(), anyString(), any(), any(), any(),
                anyString(), anyString(), any(),
                hashCaptor.capture(), any(Instant.class), any(Instant.class));
        assertThat(hashCaptor.getValue()).isEqualTo(reconciler.hashSkillMd(target));
    }

    @Test
    @DisplayName("importSkill_existingRowSameVersion_updatesAndDoesNotEmitOrphanWarn")
    void importSkill_existingRowSameVersion_updatesAndDoesNotEmitOrphanWarn() throws IOException {
        Path source = workspaceRoot.resolve("tool-call-retry");
        writeSkillPackage(source, "tool-call-retry", "Retry on tool failures");
        writeMetaJson(source, "tool-call-retry", "1.0.0");

        Path samePath = runtimeRoot.resolve("clawhub/tool-call-retry/1.0.0");
        SkillEntity existing = new SkillEntity();
        existing.setId(99L);
        existing.setName("tool-call-retry");
        existing.setOwnerId(7L);
        existing.setSource("clawhub");
        existing.setSkillPath(samePath.toString());
        existing.setContentHash("stale");
        existing.setEnabled(true);

        when(skillRepository.findByOwnerIdAndNameAndSourceAndIsSystem(eq(7L), eq("tool-call-retry"),
                eq("clawhub"), eq(false))).thenReturn(Optional.of(existing));

        ImportResult result = service.importSkill(source, SkillSource.CLAWHUB, 7L);

        assertThat(result.conflictResolved()).isTrue();
        assertThat(result.id()).isEqualTo(99L);

        ArgumentCaptor<SkillEntity> captor = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository).save(captor.capture());
        SkillEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(99L);
        assertThat(saved.getContentHash()).isNotEqualTo("stale");
        assertThat(saved.getArtifactStatus()).isEqualTo("active");

        // Same path → no orphan WARN.
        assertThat(warnEventsContaining("orphan")).isZero();
    }

    @Test
    @DisplayName("importSkill_existingRowDifferentVersion_pointsAtNewVersion_andLeavesOrphan")
    void importSkill_existingRowDifferentVersion_pointsAtNewVersion_andLeavesOrphan() throws IOException {
        // Pre-existing v1.0.0 directory + matching row pointing at it.
        Path oldVersionTarget = runtimeRoot.resolve("clawhub/tool-call-retry/1.0.0");
        Files.createDirectories(oldVersionTarget);
        Files.writeString(oldVersionTarget.resolve("SKILL.md"),
                "---\nname: tool-call-retry\ndescription: old\n---\n");

        SkillEntity existing = new SkillEntity();
        existing.setId(99L);
        existing.setName("tool-call-retry");
        existing.setOwnerId(7L);
        existing.setSource("clawhub");
        existing.setSkillPath(oldVersionTarget.toString());
        existing.setContentHash("oldhash");
        when(skillRepository.findByOwnerIdAndNameAndSourceAndIsSystem(eq(7L), eq("tool-call-retry"),
                eq("clawhub"), eq(false))).thenReturn(Optional.of(existing));

        // New version 1.0.1 in the workspace
        Path source = workspaceRoot.resolve("tool-call-retry");
        writeSkillPackage(source, "tool-call-retry", "Retry on tool failures (v1.0.1)");
        writeMetaJson(source, "tool-call-retry", "1.0.1");

        ImportResult result = service.importSkill(source, SkillSource.CLAWHUB, 7L);

        assertThat(result.conflictResolved()).isTrue();
        Path newVersionTarget = runtimeRoot.resolve("clawhub/tool-call-retry/1.0.1");
        assertThat(Path.of(result.skillPath())).isEqualTo(newVersionTarget);
        // Old dir on disk is preserved (becomes orphan, reconciler will surface it later).
        assertThat(Files.isDirectory(oldVersionTarget)).isTrue();

        ArgumentCaptor<SkillEntity> captor = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository).save(captor.capture());
        SkillEntity saved = captor.getValue();
        assertThat(saved.getSkillPath()).isEqualTo(newVersionTarget.toString());

        // PRD F2: log.warn must surface the orphan dir + "not deleted" semantics.
        assertThat(warnEventsContaining("orphan", oldVersionTarget.toString(), "not deleted")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("importSkill_sourcePathOutsideAllowedRoots_throwsIllegalArgument")
    void importSkill_sourcePathOutsideAllowedRoots_throwsIllegalArgument() throws IOException {
        Path forbidden = home.resolve("etc-not-allowed");
        writeSkillPackage(forbidden, "evil", "no");

        assertThatThrownBy(() -> service.importSkill(forbidden, SkillSource.CLAWHUB, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in allowed roots");

        // No row written, no copy performed, no registry side-effect.
        verify(skillRepository, times(0)).insertImportedSkillIgnoreConflict(
                any(), anyString(), any(), any(), any(),
                anyString(), anyString(), any(),
                anyString(), any(Instant.class), any(Instant.class));
        verify(skillRepository, times(0)).save(any(SkillEntity.class));
    }

    @Test
    @DisplayName("importSkill_missingSkillMd_throwsIllegalArgument")
    void importSkill_missingSkillMd_throwsIllegalArgument() throws IOException {
        Path source = workspaceRoot.resolve("empty-pkg");
        Files.createDirectories(source);

        assertThatThrownBy(() -> service.importSkill(source, SkillSource.CLAWHUB, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL.md not found");
    }

    @Test
    @DisplayName("importSkill_metaJsonMissing_versionFallsBackToLatest")
    void importSkill_metaJsonMissing_versionFallsBackToLatest() throws IOException {
        Path source = workspaceRoot.resolve("no-meta");
        writeSkillPackage(source, "no-meta", "no _meta.json on disk");

        ImportResult result = service.importSkill(source, SkillSource.CLAWHUB, 7L);

        Path expectedTarget = runtimeRoot.resolve("clawhub/no-meta/latest");
        assertThat(Path.of(result.skillPath())).isEqualTo(expectedTarget);
        assertThat(Files.isRegularFile(expectedTarget.resolve("SKILL.md"))).isTrue();
    }

    @Test
    @DisplayName("importSkill_concurrentInsertCollision_recoversByUpdate")
    void importSkill_concurrentInsertCollision_recoversByUpdate() throws IOException {
        Path source = workspaceRoot.resolve("tool-call-retry");
        writeSkillPackage(source, "tool-call-retry", "Retry on tool failures");
        writeMetaJson(source, "tool-call-retry", "1.0.0");

        // First lookup: empty (we're the underdog, nobody has it yet).
        // insertImportedSkillIgnoreConflict returns 0 → opponent already won.
        // Second lookup: returns the winner row with id 123.
        SkillEntity winner = new SkillEntity();
        winner.setId(123L);
        winner.setName("tool-call-retry");
        winner.setOwnerId(7L);
        winner.setSource("clawhub");
        winner.setContentHash("winner-hash");

        when(skillRepository.findByOwnerIdAndNameAndSourceAndIsSystem(eq(7L), eq("tool-call-retry"),
                eq("clawhub"), eq(false)))
                .thenReturn(Optional.empty())     // first lookup: nothing yet
                .thenReturn(Optional.of(winner)); // re-lookup after insertIgnoreConflict rows=0

        // Override default insertImportedSkillIgnoreConflict (rows=1) to simulate "opponent won".
        when(skillRepository.insertImportedSkillIgnoreConflict(
                any(), anyString(), any(), any(), any(),
                anyString(), anyString(), any(),
                anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(0);

        ImportResult result = service.importSkill(source, SkillSource.CLAWHUB, 7L);

        assertThat(result.conflictResolved()).isTrue();
        assertThat(result.id()).isEqualTo(123L);
        // applyUpdate path takes over with a JPA save() on the winner row.
        verify(skillRepository, times(1)).save(any(SkillEntity.class));
    }

    @Test
    @DisplayName("importSkill_metaSlugDiffersFromFrontmatterName_usesSlugForRowName")
    void importSkill_metaSlugDiffersFromFrontmatterName_usesSlugForRowName() throws IOException {
        // SKILL.md frontmatter name = "human-readable-name"
        // _meta.json slug = "tool-call-retry"
        // Row name + lookup key MUST be the slug (more stable across frontmatter renames).
        Path source = workspaceRoot.resolve("tool-call-retry");
        Files.createDirectories(source);
        Files.writeString(source.resolve("SKILL.md"),
                "---\nname: human-readable-name\ndescription: x\n---\n");
        writeMetaJson(source, "tool-call-retry", "1.0.0");

        ImportResult result = service.importSkill(source, SkillSource.CLAWHUB, 7L);

        // Lookup key + row name = slug (judge MUST-3 fix). Lookup happens twice
        // (initial probe + re-lookup after winning the native insert) — both must
        // use the slug "tool-call-retry", not the frontmatter "human-readable-name".
        assertThat(result.name()).isEqualTo("tool-call-retry");
        verify(skillRepository).insertImportedSkillIgnoreConflict(
                eq(7L), eq("tool-call-retry"), any(), any(), any(),
                anyString(), eq("clawhub"), anyString(),
                anyString(), any(Instant.class), any(Instant.class));
        verify(skillRepository, times(2)).findByOwnerIdAndNameAndSourceAndIsSystem(
                eq(7L), eq("tool-call-retry"), eq("clawhub"), eq(false));
        // Sanity: never looked up by the frontmatter name.
        verify(skillRepository, never()).findByOwnerIdAndNameAndSourceAndIsSystem(
                eq(7L), eq("human-readable-name"), anyString(), eq(false));
    }
}
