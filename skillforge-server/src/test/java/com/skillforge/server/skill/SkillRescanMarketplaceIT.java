package com.skillforge.server.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL-IMPORT-BATCH — end-to-end integration test against an embedded
 * Postgres (Testcontainers via {@link AbstractPostgresIT}). Covers AC-1
 * (fresh batch) and AC-2 (re-scan → conflictResolved) end-to-end through real
 * file IO, real {@link SkillImportService}, and a real schema.
 *
 * <p>Mirrors {@link SkillImportIT} bootstrap: manual wiring because the
 * server module does not yet have a {@code @SpringBootTest} bootstrap — the
 * JPA slice via {@link AbstractPostgresIT} is sufficient and avoids dragging
 * in unrelated context.
 */
@Import(SkillRescanMarketplaceIT.TestConfig.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/migration"
})
class SkillRescanMarketplaceIT extends AbstractPostgresIT {

    @Autowired private SkillRepository skillRepository;

    @TempDir Path testHome;

    private SkillImportService service;
    private SkillBatchImporter batchImporter;
    private Path runtimeRoot;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() throws IOException {
        runtimeRoot = testHome.resolve("data/skills");
        workspaceRoot = testHome.resolve("workspace/skills");
        Files.createDirectories(runtimeRoot);
        Files.createDirectories(workspaceRoot);

        SkillForgeHomeResolver homeResolver = new SkillForgeHomeResolver(testHome.toString());
        homeResolver.postConstruct();

        SkillStorageService storageService = new SkillStorageService(homeResolver);
        SkillPackageLoader packageLoader = new SkillPackageLoader();
        // The current import path does not invoke SkillConflictResolver, but
        // wiring a Mockito stub instead of {@code null} prevents a future
        // collaborator change from blowing up here as a NullPointerException
        // (Judge MUST-2 / +1 line zero-risk hardening).
        SkillConflictResolver conflictResolver = Mockito.mock(SkillConflictResolver.class);
        SkillCatalogReconciler reconciler = new SkillCatalogReconciler(
                homeResolver, skillRepository, packageLoader, conflictResolver);

        SkillImportProperties properties = new SkillImportProperties();
        properties.setAllowedSourceRoots(List.of(workspaceRoot.toString()));

        SkillRegistry skillRegistry = new SkillRegistry();

        service = new SkillImportService(properties, storageService, skillRepository,
                skillRegistry, packageLoader, reconciler, new ObjectMapper());
        batchImporter = new SkillBatchImporter(service, properties);
    }

    private void writeSkillPackage(Path dir, String name, String description, String version) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n\nbody.\n");
        Files.writeString(dir.resolve("_meta.json"),
                "{\"slug\":\"" + name + "\",\"version\":\"" + version + "\"}");
    }

    @Test
    @DisplayName("AC-1: fresh batch rescan registers every subdir as imported (real PG, native INSERT)")
    void freshBatchRescan_importsAllSubdirs() throws IOException {
        // Three packages under the marketplace root.
        for (String slug : List.of("alpha", "beta", "gamma")) {
            writeSkillPackage(workspaceRoot.resolve(slug), slug, slug + " desc", "1.0.0");
        }
        // A bare directory should be skipped.
        Files.createDirectories(workspaceRoot.resolve("empty-dir"));

        BatchImportResult result = batchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 1001L);

        assertThat(result.imported()).hasSize(3);
        assertThat(result.imported()).extracting(BatchImportResult.ImportedItem::name)
                .containsExactlyInAnyOrder("alpha", "beta", "gamma");
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).name()).isEqualTo("empty-dir");
        assertThat(result.skipped().get(0).reason()).isEqualTo("no SKILL.md");
        assertThat(result.failed()).isEmpty();
        assertThat(result.updated()).isEmpty();

        for (String slug : List.of("alpha", "beta", "gamma")) {
            Optional<SkillEntity> row = skillRepository
                    .findByOwnerIdAndNameAndSourceAndIsSystem(1001L, slug, "clawhub", false);
            assertThat(row).as("row for %s must exist after batch import", slug).isPresent();
            assertThat(row.get().getSkillPath())
                    .isEqualTo(runtimeRoot.resolve("clawhub/" + slug + "/1.0.0").toString());
            assertThat(row.get().isSystem()).isFalse();
            assertThat(row.get().isEnabled()).isTrue();
            assertThat(row.get().getArtifactStatus()).isEqualTo("active");
        }
    }

    @Test
    @DisplayName("AC-2: second batch rescan with same slugs → all updated, no row count growth")
    void secondBatchRescan_sameSlugs_bucketsIntoUpdated() throws IOException {
        for (String slug : List.of("alpha", "beta", "gamma")) {
            writeSkillPackage(workspaceRoot.resolve(slug), slug, slug + " desc", "1.0.0");
        }

        BatchImportResult first = batchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 1001L);
        BatchImportResult second = batchImporter.batchImportFromMarketplace(SkillSource.CLAWHUB, 1001L);

        assertThat(first.imported()).hasSize(3);
        assertThat(second.imported()).isEmpty();
        assertThat(second.updated()).hasSize(3);
        assertThat(second.updated()).extracting(BatchImportResult.ImportedItem::name)
                .containsExactlyInAnyOrder("alpha", "beta", "gamma");

        // Catalog still has exactly 3 user rows for owner 1001 — no duplicates.
        long userRows = skillRepository.findByOwnerId(1001L).stream()
                .filter(r -> "clawhub".equals(r.getSource()))
                .count();
        assertThat(userRows).isEqualTo(3L);
    }

    /** Empty marker class so {@code @Import} can register a non-empty Spring config. */
    @TestConfiguration
    static class TestConfig {
    }
}
