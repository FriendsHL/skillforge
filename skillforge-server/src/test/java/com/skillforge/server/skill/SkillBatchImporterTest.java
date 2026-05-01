package com.skillforge.server.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SKILL-IMPORT-BATCH — unit tests for
 * {@link SkillBatchImporter#batchImportFromMarketplace}. Covers AC-1..AC-5:
 *
 * <ul>
 *   <li>AC-1 fresh batch → multiple rows in {@code imported}</li>
 *   <li>AC-2 same slug already present → bucketed into {@code updated}</li>
 *   <li>AC-3 subdir without SKILL.md → bucketed into {@code skipped}</li>
 *   <li>AC-4 cross-source name conflict (importSkill throws
 *       {@link IllegalStateException}) → bucketed into {@code skipped}</li>
 *   <li>AC-5 one subdir crashes → bucketed into {@code failed} but other
 *       subdirs continue</li>
 * </ul>
 *
 * <p>Pattern follows {@link SkillImportServiceTest}: real
 * {@link SkillStorageService} / {@link SkillCatalogReconciler} /
 * {@link SkillPackageLoader} with Mockito-stubbed JPA collaborators and a
 * real filesystem in {@link TempDir}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillBatchImporterTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillConflictResolver conflictResolver;
    @Mock private SkillForgeHomeResolver homeResolver;

    private SkillImportProperties properties;
    private SkillImportService service;
    private SkillBatchImporter batchImporter;

    private Path runtimeRoot;
    private Path workspaceRoot;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws IOException {
        runtimeRoot = tmp.resolve("data/skills");
        workspaceRoot = tmp.resolve("workspace/skills");
        Files.createDirectories(runtimeRoot);
        Files.createDirectories(workspaceRoot);

        when(homeResolver.getRuntimeRoot()).thenReturn(runtimeRoot);
        SkillStorageService storageService = new SkillStorageService(homeResolver);
        SkillPackageLoader packageLoader = new SkillPackageLoader();
        SkillCatalogReconciler reconciler = new SkillCatalogReconciler(
                homeResolver, skillRepository, packageLoader, conflictResolver);

        properties = new SkillImportProperties();
        properties.setAllowedSourceRoots(List.of(workspaceRoot.toString()));

        service = new SkillImportService(properties, storageService, skillRepository,
                skillRegistry, packageLoader, reconciler, new ObjectMapper());
        batchImporter = new SkillBatchImporter(service, properties);

        // Default: fresh import path — no existing rows; native insert wins.
        AtomicLong idSeq = new AtomicLong(1);
        when(skillRepository.findByOwnerIdAndNameAndSourceAndIsSystem(any(), any(), any(), eq(false)))
                .thenReturn(Optional.empty());
        when(skillRepository.insertImportedSkillIgnoreConflict(
                any(), anyString(), any(), any(), any(),
                anyString(), anyString(), any(),
                anyString(), any(Instant.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    long id = idSeq.getAndIncrement();
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
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> {
            SkillEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(idSeq.getAndIncrement());
            }
            return e;
        });
    }

    private void writeSkillPackage(Path dir, String name, String description) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\n"
                        + "description: " + description + "\n"
                        + "---\n\nbody.\n");
    }

    private void writeMetaJson(Path dir, String slug, String version) throws IOException {
        Files.writeString(dir.resolve("_meta.json"),
                "{\"slug\":\"" + slug + "\",\"version\":\"" + version + "\"}");
    }

    @Test
    @DisplayName("batchImport_freshClawhubRoot_importsAllThreeSubdirs (AC-1)")
    void batchImport_freshClawhubRoot_importsAllThreeSubdirs() throws IOException {
        // Arrange — three skill packages directly under the allowed root
        for (String slug : List.of("alpha", "beta", "gamma")) {
            Path sub = workspaceRoot.resolve(slug);
            writeSkillPackage(sub, slug, slug + " desc");
            writeMetaJson(sub, slug, "1.0.0");
        }

        // Act
        BatchImportResult result = batchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 7L);

        // Assert
        assertThat(result.imported()).hasSize(3);
        assertThat(result.imported()).extracting(BatchImportResult.ImportedItem::name)
                .containsExactlyInAnyOrder("alpha", "beta", "gamma");
        assertThat(result.imported()).allSatisfy(item ->
                assertThat(item.skillPath()).contains("clawhub").contains("1.0.0"));
        assertThat(result.updated()).isEmpty();
        assertThat(result.skipped()).isEmpty();
        assertThat(result.failed()).isEmpty();
    }

    @Test
    @DisplayName("batchImport_existingRowSameSlug_bucketsIntoUpdated (AC-2)")
    void batchImport_existingRowSameSlug_bucketsIntoUpdated() throws IOException {
        // Arrange — one skill package + a pre-existing row for that slug
        Path sub = workspaceRoot.resolve("tool-call-retry");
        writeSkillPackage(sub, "tool-call-retry", "Retry");
        writeMetaJson(sub, "tool-call-retry", "1.0.0");

        SkillEntity existing = new SkillEntity();
        existing.setId(99L);
        existing.setName("tool-call-retry");
        existing.setOwnerId(7L);
        existing.setSource("clawhub");
        existing.setSkillPath(runtimeRoot.resolve("clawhub/tool-call-retry/1.0.0").toString());
        existing.setContentHash("stale");
        existing.setEnabled(true);
        when(skillRepository.findByOwnerIdAndNameAndSourceAndIsSystem(
                eq(7L), eq("tool-call-retry"), eq("clawhub"), eq(false)))
                .thenReturn(Optional.of(existing));

        // Act
        BatchImportResult result = batchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 7L);

        // Assert — the conflict-resolved import lands in `updated`, not `imported`
        assertThat(result.imported()).isEmpty();
        assertThat(result.updated()).hasSize(1);
        assertThat(result.updated().get(0).name()).isEqualTo("tool-call-retry");
        assertThat(result.skipped()).isEmpty();
        assertThat(result.failed()).isEmpty();
    }

    @Test
    @DisplayName("batchImport_subdirWithoutSkillMd_bucketsIntoSkipped (AC-3)")
    void batchImport_subdirWithoutSkillMd_bucketsIntoSkipped() throws IOException {
        // Arrange — one valid + one bare directory with no SKILL.md
        Path good = workspaceRoot.resolve("good");
        writeSkillPackage(good, "good", "valid");
        writeMetaJson(good, "good", "1.0.0");
        Files.createDirectories(workspaceRoot.resolve("empty-dir"));

        // Act
        BatchImportResult result = batchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 7L);

        // Assert
        assertThat(result.imported()).hasSize(1);
        assertThat(result.imported().get(0).name()).isEqualTo("good");
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).name()).isEqualTo("empty-dir");
        assertThat(result.skipped().get(0).reason()).isEqualTo("no SKILL.md");
        assertThat(result.failed()).isEmpty();
    }

    @Test
    @DisplayName("batchImport_importSkillThrowsIllegalState_bucketsIntoSkipped (AC-4)")
    void batchImport_importSkillThrowsIllegalState_bucketsIntoSkipped() throws IOException {
        // Arrange — a valid package, but importSkill will throw IllegalStateException
        // when we re-look-up the row after the native insert reports rows=0 and the
        // re-lookup also returns empty (simulating cross-source / invariant violation).
        Path sub = workspaceRoot.resolve("tool-call-retry");
        writeSkillPackage(sub, "tool-call-retry", "Retry");
        writeMetaJson(sub, "tool-call-retry", "1.0.0");

        // Override the default insert stub: pretend opponent already won (rows=0)
        // AND the re-lookup also returns empty — importSkill raises
        // IllegalStateException, which the batch path treats as a cross-source
        // skip per tech-design D2.
        when(skillRepository.insertImportedSkillIgnoreConflict(
                any(), anyString(), any(), any(), any(),
                anyString(), anyString(), any(),
                anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(0);
        when(skillRepository.findByOwnerIdAndNameAndSourceAndIsSystem(
                anyLong(), anyString(), anyString(), eq(false)))
                .thenReturn(Optional.empty());

        // Act
        BatchImportResult result = batchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 7L);

        // Assert
        assertThat(result.imported()).isEmpty();
        assertThat(result.updated()).isEmpty();
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).name()).isEqualTo("tool-call-retry");
        assertThat(result.skipped().get(0).reason())
                .contains("cross-source name conflict:");
        assertThat(result.failed()).isEmpty();
    }

    @Test
    @DisplayName("batchImport_oneSubdirCrashes_otherSubdirsContinue (AC-5)")
    void batchImport_oneSubdirCrashes_otherSubdirsContinue() throws IOException {
        // Arrange — three packages: one will deliberately crash the JPA layer.
        for (String slug : List.of("alpha", "boom", "gamma")) {
            Path sub = workspaceRoot.resolve(slug);
            writeSkillPackage(sub, slug, slug + " desc");
            writeMetaJson(sub, slug, "1.0.0");
        }
        // Crash specifically on "boom" — the native insert blows up
        // (simulates a transient DB / IO error). The other subdirs must still
        // run to completion.
        when(skillRepository.insertImportedSkillIgnoreConflict(
                any(), eq("boom"), any(), any(), any(),
                anyString(), anyString(), any(),
                anyString(), any(Instant.class), any(Instant.class)))
                .thenThrow(new RuntimeException("simulated DB error on boom"));

        // Act
        BatchImportResult result = batchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 7L);

        // Assert — `boom` lands in `failed`; alpha + gamma still imported.
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).name()).isEqualTo("boom");
        assertThat(result.failed().get(0).error()).contains("simulated DB error");
        assertThat(result.imported()).extracting(BatchImportResult.ImportedItem::name)
                .containsExactlyInAnyOrder("alpha", "gamma");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    @DisplayName("batchImport_nonDirectoryEntriesAtRoot_areIgnored")
    void batchImport_nonDirectoryEntriesAtRoot_areIgnored() throws IOException {
        // Arrange — mix a valid subdir, a stray file, and a hidden file. Only
        // directories should be considered (Files::isDirectory filter).
        Path good = workspaceRoot.resolve("good");
        writeSkillPackage(good, "good", "valid");
        writeMetaJson(good, "good", "1.0.0");
        Files.writeString(workspaceRoot.resolve("README.md"), "ignored top-level file");
        Files.writeString(workspaceRoot.resolve(".DS_Store"), "");

        // Act
        BatchImportResult result = batchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 7L);

        // Assert — only the directory was inspected.
        assertThat(result.imported()).hasSize(1);
        assertThat(result.imported().get(0).name()).isEqualTo("good");
        assertThat(result.skipped()).isEmpty();
        assertThat(result.failed()).isEmpty();
    }

    @Test
    @DisplayName("batchImport_emptyAllowedRoots_returnsAllEmpty")
    void batchImport_emptyAllowedRoots_returnsAllEmpty() {
        // Arrange — drop all roots from the config.
        properties.setAllowedSourceRoots(List.of());

        // Act
        BatchImportResult result = batchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 7L);

        // Assert — no buckets populated, no exceptions.
        assertThat(result.imported()).isEmpty();
        assertThat(result.updated()).isEmpty();
        assertThat(result.skipped()).isEmpty();
        assertThat(result.failed()).isEmpty();
    }
}
