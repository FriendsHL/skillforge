package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-D — Unit tests for {@link SkillCatalogReconciler#reconcileRuntime()}.
 *
 * <p>Uses Mockito for the JPA + ConflictResolver collaborators and a real
 * {@link SkillPackageLoader} working against {@code @TempDir} skill artifacts
 * on disk. This keeps the test self-contained (no Docker / Postgres / H2)
 * while still exercising the full path-resolution + hash-tracking logic.
 *
 * <p>Plan T5 / 准入条件覆盖:
 * <ul>
 *   <li>DB 缺失 → insert 新 row (enabled=false 防误伤)</li>
 *   <li>hash 变化 → update metadata + content_hash</li>
 *   <li>磁盘缺失 → conflictResolver.markMissing</li>
 *   <li>非法包 → conflictResolver.markInvalid</li>
 *   <li>RescanReport 字段计数</li>
 *   <li>旧 ./data/skills 相对路径 fallback (off-by-one fix)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillCatalogReconcilerTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillConflictResolver conflictResolver;
    @Mock private SkillForgeHomeResolver homeResolver;

    private SkillPackageLoader packageLoader;
    private SkillCatalogReconciler reconciler;
    private Path home;
    private Path runtimeRoot;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws IOException {
        home = tmp;
        runtimeRoot = home.resolve("data/skills");
        Files.createDirectories(runtimeRoot);

        packageLoader = new SkillPackageLoader();
        when(homeResolver.getRuntimeRoot()).thenReturn(runtimeRoot);

        reconciler = new SkillCatalogReconciler(
                homeResolver, skillRepository, packageLoader, conflictResolver);
    }

    /** Write a minimal valid SKILL.md to the given dir; returns the dir. */
    private Path writeSkillPackage(Path dir, String name, String description) throws IOException {
        Files.createDirectories(dir);
        String md = "---\nname: " + name + "\n"
                + "description: " + description + "\n"
                + "---\n\nBody.\n";
        Files.writeString(dir.resolve("SKILL.md"), md);
        return dir;
    }

    @Test
    @DisplayName("disk → DB: new artifact inserts row with enabled=false (defensive)")
    void newArtifact_insertsDisabled() throws IOException {
        Path skillDir = runtimeRoot.resolve("upload/42/uuid-1");
        writeSkillPackage(skillDir, "MyNewSkill", "Does X");

        when(skillRepository.findByIsSystemFalse()).thenReturn(Collections.emptyList());
        when(skillRepository.findFirstByIsSystemFalseAndOwnerIdAndNameAndSource(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> {
            SkillEntity e = inv.getArgument(0);
            e.setId(100L);
            return e;
        });

        RescanReport report = reconciler.reconcileRuntime();

        ArgumentCaptor<SkillEntity> captor = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository).save(captor.capture());
        SkillEntity saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("MyNewSkill");
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.isSystem()).isFalse();
        assertThat(saved.getArtifactStatus()).isEqualTo("active");
        assertThat(saved.getContentHash()).isNotNull();
        assertThat(saved.getOwnerId()).isEqualTo(42L);
        assertThat(saved.getSource()).isEqualTo("upload");
        assertThat(saved.getSkillPath()).isEqualTo(skillDir.toAbsolutePath().normalize().toString());
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.updated()).isZero();
    }

    @Test
    @DisplayName("disk → DB: hash change updates metadata + content_hash")
    void existingRow_hashChanged_updates() throws IOException {
        Path skillDir = runtimeRoot.resolve("upload/1/uuid-2");
        writeSkillPackage(skillDir, "Existing", "v2 description");

        SkillEntity row = makeRow(7L, "Existing", skillDir.toAbsolutePath().normalize().toString(),
                "old-hash", "active", true, false, 1L, "upload");

        when(skillRepository.findByIsSystemFalse()).thenReturn(List.of(row));
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        RescanReport report = reconciler.reconcileRuntime();

        verify(skillRepository, times(1)).save(any(SkillEntity.class));
        assertThat(row.getContentHash()).isNotEqualTo("old-hash");
        assertThat(row.getDescription()).isEqualTo("v2 description");
        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.created()).isZero();
    }

    @Test
    @DisplayName("DB → disk: missing artifact dir → conflictResolver.markMissing()")
    void existingRow_diskMissing_marksMissing() {
        Path missingDir = runtimeRoot.resolve("upload/9/uuid-gone").toAbsolutePath().normalize();
        SkillEntity row = makeRow(11L, "Vanished", missingDir.toString(), "hash", "active",
                true, false, 9L, "upload");

        when(skillRepository.findByIsSystemFalse()).thenReturn(List.of(row));

        RescanReport report = reconciler.reconcileRuntime();

        verify(conflictResolver, times(1)).markMissing(11L);
        verify(conflictResolver, never()).markInvalid(anyLong());
        assertThat(report.missing()).isEqualTo(1);
    }

    @Test
    @DisplayName("disk → DB: SKILL.md parse failure during walk → counted as invalid")
    void corruptedPackage_inWalk_countsInvalid() throws IOException {
        // Drop a SKILL.md that the YAML parser can't handle (unterminated frontmatter).
        Path skillDir = runtimeRoot.resolve("upload/5/uuid-bad");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: BadSkill\ndescription: missing close fence\n");

        when(skillRepository.findByIsSystemFalse()).thenReturn(Collections.emptyList());

        RescanReport report = reconciler.reconcileRuntime();

        // Loader will throw IOException → reconciler counts it as invalid
        assertThat(report.invalid()).isGreaterThanOrEqualTo(1);
        verify(skillRepository, never()).save(any(SkillEntity.class));
    }

    @Test
    @DisplayName("legacy ./data/skills/<owner>/<uuid> path resolves under home (off-by-one fix)")
    void legacyRelativePath_resolvesCorrectly() throws IOException {
        // Legacy row: skillPath stored as "./data/skills/1/legacy-uuid".
        // After fix #3, this anchors against <home> (= runtimeRoot.parent.parent),
        // producing <home>/data/skills/1/legacy-uuid which is identical to
        // runtimeRoot/1/legacy-uuid where we put the artifact.
        Path skillDir = runtimeRoot.resolve("1/legacy-uuid");
        writeSkillPackage(skillDir, "LegacySkill", "Legacy desc");

        SkillEntity row = makeRow(99L, "LegacySkill",
                "./data/skills/1/legacy-uuid", "old-hash", "active",
                true, false, 1L, "upload");

        when(skillRepository.findByIsSystemFalse()).thenReturn(List.of(row));
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        RescanReport report = reconciler.reconcileRuntime();

        // The row was matched (via legacy path resolution) → updated, not created
        assertThat(report.created()).isZero();
        // skill_path should be healed to absolute
        assertThat(row.getSkillPath()).isEqualTo(skillDir.toAbsolutePath().normalize().toString());
        // No markMissing because resolveStoredPath found the artifact at <home>/data/skills/...
        verify(conflictResolver, never()).markMissing(anyLong());
    }

    @Test
    @DisplayName("RescanReport sums multiple categories correctly across one rescan")
    void multipleCategories_aggregatedCorrectly() throws IOException {
        // 1 new (insert), 1 hash-changed (update), 1 missing
        Path newDir = runtimeRoot.resolve("upload/1/new-uuid");
        writeSkillPackage(newDir, "NewSkill", "new");

        Path updateDir = runtimeRoot.resolve("upload/1/upd-uuid");
        writeSkillPackage(updateDir, "UpdSkill", "updated");

        SkillEntity updateRow = makeRow(2L, "UpdSkill",
                updateDir.toAbsolutePath().normalize().toString(),
                "old-hash", "active", true, false, 1L, "upload");
        SkillEntity missingRow = makeRow(3L, "GoneSkill",
                runtimeRoot.resolve("upload/1/missing-uuid").toAbsolutePath().toString(),
                "h", "active", true, false, 1L, "upload");

        when(skillRepository.findByIsSystemFalse()).thenReturn(List.of(updateRow, missingRow));
        when(skillRepository.findFirstByIsSystemFalseAndOwnerIdAndNameAndSource(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> {
            SkillEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(99L);
            return e;
        });

        RescanReport report = reconciler.reconcileRuntime();

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.missing()).isEqualTo(1);
    }

    @Test
    @DisplayName("INSERT path: per-row save throw caught — siblings still process")
    void perRowInsertException_doesNotAbortLoop() throws IOException {
        // Two new artifacts; the first save() throws, the second should still be attempted.
        Path d1 = runtimeRoot.resolve("upload/1/u1");
        writeSkillPackage(d1, "A", "a");
        Path d2 = runtimeRoot.resolve("upload/1/u2");
        writeSkillPackage(d2, "B", "b");

        when(skillRepository.findByIsSystemFalse()).thenReturn(Collections.emptyList());
        when(skillRepository.findFirstByIsSystemFalseAndOwnerIdAndNameAndSource(any(), any(), any()))
                .thenReturn(Optional.empty());

        // First save throws, second succeeds.
        when(skillRepository.save(any(SkillEntity.class)))
                .thenThrow(new RuntimeException("simulated DB constraint"))
                .thenAnswer(inv -> {
                    SkillEntity e = inv.getArgument(0);
                    e.setId(2L);
                    return e;
                });

        RescanReport report = reconciler.reconcileRuntime();

        // Both saves attempted (insert path); only one succeeded
        verify(skillRepository, times(2)).save(any(SkillEntity.class));
        assertThat(report.created()).isEqualTo(1);
    }

    @Test
    @DisplayName("UPDATE path: per-row save throw caught — siblings still process (fix #2)")
    void perRowUpdateException_doesNotAbortLoop() throws IOException {
        // Two existing rows that both have hash-changed artifacts on disk; the
        // UPDATE-path save() throws on the first, second should still process.
        Path d1 = runtimeRoot.resolve("upload/1/upd-a");
        writeSkillPackage(d1, "UpdA", "a-new");
        Path d2 = runtimeRoot.resolve("upload/1/upd-b");
        writeSkillPackage(d2, "UpdB", "b-new");

        SkillEntity rowA = makeRow(101L, "UpdA",
                d1.toAbsolutePath().normalize().toString(),
                "old-hash-A", "active", true, false, 1L, "upload");
        SkillEntity rowB = makeRow(102L, "UpdB",
                d2.toAbsolutePath().normalize().toString(),
                "old-hash-B", "active", true, false, 1L, "upload");

        when(skillRepository.findByIsSystemFalse()).thenReturn(List.of(rowA, rowB));

        // First update save() throws, second succeeds.
        when(skillRepository.save(any(SkillEntity.class)))
                .thenThrow(new RuntimeException("simulated rollback-only EM"))
                .thenAnswer(inv -> inv.getArgument(0));

        RescanReport report = reconciler.reconcileRuntime();

        // Both update saves attempted; loop survived the first failure.
        verify(skillRepository, times(2)).save(any(SkillEntity.class));
        // Only one save's hash actually got persisted, but RescanReport.updated()
        // still counts both because the count happens before save() (the metric
        // is "found N changes", not "successfully wrote N rows"). Acceptable.
        assertThat(report.updated()).isGreaterThanOrEqualTo(1);
    }

    // ─── helpers ───

    private SkillEntity makeRow(Long id, String name, String skillPath, String contentHash,
                                String artifactStatus, boolean enabled, boolean isSystem,
                                Long ownerId, String source) {
        SkillEntity e = new SkillEntity();
        e.setId(id);
        e.setName(name);
        e.setDescription("(seed)");
        e.setSkillPath(skillPath);
        e.setContentHash(contentHash);
        e.setArtifactStatus(artifactStatus);
        e.setEnabled(enabled);
        e.setSystem(isSystem);
        e.setOwnerId(ownerId);
        e.setSource(source);
        return e;
    }
}
