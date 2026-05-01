package com.skillforge.server.skill;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL-IMPORT — end-to-end integration test against an embedded Postgres
 * (Testcontainers via {@link AbstractPostgresIT}). Covers AC-1, AC-2, AC-3:
 *
 * <ul>
 *   <li>AC-1: fresh ClawHub install + ImportSkill → t_skill row + on-disk artifact</li>
 *   <li>AC-2: re-import same slug + version → conflictResolved=true, single row, hash unchanged</li>
 *   <li>AC-3: re-import same slug + new version → row.skillPath points to new dir, old dir preserved + WARN log</li>
 *   <li>Native ON CONFLICT DO NOTHING happy path runs against real PG (judge MUST-2 verification)</li>
 * </ul>
 *
 * <p>Wires {@link SkillImportService} manually because the server module does
 * not yet have a {@code @SpringBootTest} bootstrap — only the JPA slice via
 * {@link AbstractPostgresIT}. SkillRegistry / reconciler / storage / loader
 * are real instances.
 */
@Import(SkillImportIT.TestConfig.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/migration"
})
class SkillImportIT extends AbstractPostgresIT {

    @Autowired private SkillRepository skillRepository;

    @TempDir Path testHome;

    private SkillImportService service;
    private SkillCatalogReconciler reconciler;
    private Path runtimeRoot;
    private Path workspaceRoot;
    private SkillRegistry skillRegistry;

    private ch.qos.logback.classic.Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

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
        // SkillConflictResolver is unused on the import path; null is acceptable here.
        reconciler = new SkillCatalogReconciler(homeResolver, skillRepository, packageLoader, null);

        SkillImportProperties properties = new SkillImportProperties();
        properties.setAllowedSourceRoots(List.of(workspaceRoot.toString()));

        skillRegistry = new SkillRegistry();

        service = new SkillImportService(properties, storageService, skillRepository,
                skillRegistry, packageLoader, reconciler, new ObjectMapper());

        serviceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SkillImportService.class);
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

    private void writeSkillPackage(Path dir, String name, String description) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n\nbody.\n");
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
    @DisplayName("AC-1: fresh ImportSkill creates row + artifact + registry entry (real PG, native INSERT)")
    void freshImport_createsRowAndArtifact() throws IOException {
        Path source = workspaceRoot.resolve("tool-call-retry");
        writeSkillPackage(source, "tool-call-retry", "Retry");
        writeMetaJson(source, "tool-call-retry", "1.0.0");

        ImportResult result = service.importSkill(source, SkillSource.CLAWHUB, 1001L);

        assertThat(result.conflictResolved()).isFalse();
        assertThat(result.source()).isEqualTo("clawhub");

        Path expectedTarget = runtimeRoot.resolve("clawhub/tool-call-retry/1.0.0");
        assertThat(Files.isRegularFile(expectedTarget.resolve("SKILL.md"))).isTrue();

        Optional<SkillEntity> row = skillRepository
                .findByOwnerIdAndNameAndSourceAndIsSystem(1001L, "tool-call-retry", "clawhub", false);
        assertThat(row).isPresent();
        assertThat(row.get().getSkillPath()).isEqualTo(expectedTarget.toString());
        assertThat(row.get().getContentHash()).isNotBlank();
        assertThat(row.get().isEnabled()).isTrue();
        assertThat(row.get().isSystem()).isFalse();
        assertThat(row.get().getArtifactStatus()).isEqualTo("active");
        assertThat(row.get().getId()).isNotNull();

        Optional<SkillDefinition> registered = skillRegistry.getSkillDefinition("tool-call-retry");
        assertThat(registered).isPresent();
        assertThat(registered.get().isSystem()).isFalse();
    }

    @Test
    @DisplayName("AC-2: re-import same slug+version → conflictResolved=true, single row, same hash, no orphan WARN")
    void reimportSameVersion_conflictResolved_singleRow_sameHash() throws IOException {
        Path source = workspaceRoot.resolve("tool-call-retry");
        writeSkillPackage(source, "tool-call-retry", "Retry");
        writeMetaJson(source, "tool-call-retry", "1.0.0");

        ImportResult first = service.importSkill(source, SkillSource.CLAWHUB, 1001L);
        ImportResult second = service.importSkill(source, SkillSource.CLAWHUB, 1001L);

        assertThat(first.conflictResolved()).isFalse();
        assertThat(second.conflictResolved()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());

        // Same source bytes → identical content_hash on the persisted row.
        Optional<SkillEntity> row = skillRepository
                .findByOwnerIdAndNameAndSourceAndIsSystem(1001L, "tool-call-retry", "clawhub", false);
        assertThat(row).isPresent();
        Path target = runtimeRoot.resolve("clawhub/tool-call-retry/1.0.0");
        assertThat(row.get().getContentHash()).isEqualTo(reconciler.hashSkillMd(target));

        // Catalog still has exactly one row for this (owner, name, source) tuple.
        long matchingRows = skillRepository.findByOwnerId(1001L).stream()
                .filter(r -> "tool-call-retry".equals(r.getName()))
                .filter(r -> "clawhub".equals(r.getSource()))
                .count();
        assertThat(matchingRows).isEqualTo(1L);

        // Same path → no orphan warning emitted.
        assertThat(warnEventsContaining("orphan")).isZero();
    }

    @Test
    @DisplayName("AC-3: re-import same slug, new version → row points to new dir, old dir preserved + WARN log")
    void reimportNewVersion_pathSwitches_oldDirOrphan() throws IOException {
        Path source = workspaceRoot.resolve("tool-call-retry");
        writeSkillPackage(source, "tool-call-retry", "v1");
        writeMetaJson(source, "tool-call-retry", "1.0.0");
        ImportResult first = service.importSkill(source, SkillSource.CLAWHUB, 1001L);
        assertThat(first.conflictResolved()).isFalse();

        // bump _meta.json version to simulate `npx clawhub install` of a newer release.
        Files.writeString(source.resolve("_meta.json"),
                "{\"slug\":\"tool-call-retry\",\"version\":\"1.0.1\"}");
        Files.writeString(source.resolve("SKILL.md"),
                "---\nname: tool-call-retry\ndescription: v1.0.1\n---\n\nupdated body.\n");

        ImportResult second = service.importSkill(source, SkillSource.CLAWHUB, 1001L);

        assertThat(second.conflictResolved()).isTrue();
        Path newDir = runtimeRoot.resolve("clawhub/tool-call-retry/1.0.1");
        Path oldDir = runtimeRoot.resolve("clawhub/tool-call-retry/1.0.0");
        assertThat(Path.of(second.skillPath())).isEqualTo(newDir);
        assertThat(Files.isDirectory(oldDir)).isTrue();   // orphan retained
        assertThat(Files.isRegularFile(newDir.resolve("SKILL.md"))).isTrue();

        Optional<SkillEntity> row = skillRepository
                .findByOwnerIdAndNameAndSourceAndIsSystem(1001L, "tool-call-retry", "clawhub", false);
        assertThat(row).isPresent();
        assertThat(row.get().getSkillPath()).isEqualTo(newDir.toString());
        assertThat(row.get().getVersion()).isEqualTo("1.0.1");

        // PRD F2: WARN must surface orphan + old path + "not deleted".
        assertThat(warnEventsContaining("orphan", oldDir.toString(), "not deleted"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Native ON CONFLICT DO NOTHING happy-path runs in real PG (judge MUST-2 IT regression)")
    void insertImportedSkillIgnoreConflict_realPg_returnsOneOnFresh_thenZeroOnDup() {
        // Direct repository call exercises the native query against the live PG schema —
        // the goal is to confirm that ON CONFLICT (COALESCE(owner_id, -1), name) and the
        // column/value list match the V31/V38 schema. If anyone mis-aligns, this test
        // explodes loud (rather than being silently skipped by Mockito-only coverage).
        Instant now = Instant.now();
        int firstRows = skillRepository.insertImportedSkillIgnoreConflict(
                /*ownerId*/ 5001L,
                /*name*/    "native-it-skill",
                /*description*/ "native ON CONFLICT regression",
                /*triggers*/    null,
                /*requiredTools*/ null,
                /*skillPath*/   runtimeRoot.resolve("clawhub/native-it-skill/1.0.0").toString(),
                /*source*/      "clawhub",
                /*version*/     "1.0.0",
                /*contentHash*/ "deadbeef",
                /*lastScannedAt*/ now,
                /*createdAt*/   now);
        assertThat(firstRows).isEqualTo(1);

        // Second insert with same (owner_id, name) tuple → ON CONFLICT swallows.
        int secondRows = skillRepository.insertImportedSkillIgnoreConflict(
                5001L, "native-it-skill", "second", null, null,
                runtimeRoot.resolve("other").toString(), "clawhub", "2.0.0",
                "secondhash", now, now);
        assertThat(secondRows).isEqualTo(0);

        // Re-lookup should find exactly the first row's payload (winner unchanged).
        Optional<SkillEntity> row = skillRepository
                .findByOwnerIdAndNameAndSourceAndIsSystem(5001L, "native-it-skill", "clawhub", false);
        assertThat(row).isPresent();
        assertThat(row.get().getContentHash()).isEqualTo("deadbeef");
        assertThat(row.get().getVersion()).isEqualTo("1.0.0");
        assertThat(row.get().getArtifactStatus()).isEqualTo("active");
    }

    /** Empty marker class so {@code @Import} can register a non-empty Spring config. */
    @TestConfiguration
    static class TestConfig {
    }
}
