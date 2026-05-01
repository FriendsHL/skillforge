package com.skillforge.server.skill;

import com.skillforge.server.skill.BatchImportResult.FailedItem;
import com.skillforge.server.skill.BatchImportResult.ImportedItem;
import com.skillforge.server.skill.BatchImportResult.SkippedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * SKILL-IMPORT-BATCH — orchestrator for batch rescan of marketplace whitelist
 * roots. Extracted from {@link SkillImportService} so each per-subdir
 * {@code importSkill()} call goes through the Spring proxy and its
 * {@code @Transactional} annotation actually applies. (Calling
 * {@code this.importSkill(...)} from the same class as
 * {@code batchImportFromMarketplace} would bypass the proxy and the
 * {@code @Modifying} native-INSERT inside {@link SkillImportService} would
 * throw {@code InvalidDataAccessApiUsageException} because no transaction is
 * active.)
 *
 * <p>This class itself is intentionally NOT {@code @Transactional}: each call
 * to {@link SkillImportService#importSkill} runs in its own sub-transaction
 * (see PRD F3 / tech-design D2 partial-success contract). Wrapping the batch
 * in a single outer transaction would let one failed import roll back every
 * previously-successful import in the same scan.
 */
@Service
public class SkillBatchImporter {

    private static final Logger log = LoggerFactory.getLogger(SkillBatchImporter.class);

    private final SkillImportService skillImportService;
    private final SkillImportProperties properties;

    public SkillBatchImporter(SkillImportService skillImportService,
                              SkillImportProperties properties) {
        this.skillImportService = skillImportService;
        this.properties = properties;
    }

    /**
     * Scan every configured allowed root for first-level subdirectories
     * containing a {@code SKILL.md}, and call
     * {@link SkillImportService#importSkill} on each. Bucket results into
     * {@link BatchImportResult}: a single failure never aborts the rest of
     * the batch.
     *
     * <p>TODO: current {@code allowed-source-roots} is a flat list and does
     * not map roots to {@link SkillSource} — every root is scanned regardless
     * of the requested {@code source}. Safe today because
     * {@code importSkill}'s cross-source same-name defence raises
     * {@link IllegalStateException} which we bucket into {@code skipped}, but
     * suboptimal once multiple marketplaces are wired in. Upgrade
     * {@code SkillImportProperties} to {@code Map<SkillSource, List<Path>>}
     * as a SKILL-IMPORT-BATCH follow-up.
     *
     * @param source  marketplace source applied to every imported subdir
     * @param ownerId user that owns the imported rows
     * @return immutable {@link BatchImportResult} with 4 buckets
     */
    public BatchImportResult batchImportFromMarketplace(SkillSource source, Long ownerId) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownerId, "ownerId");

        List<ImportedItem> imported = new ArrayList<>();
        List<ImportedItem> updated = new ArrayList<>();
        List<SkippedItem> skipped = new ArrayList<>();
        List<FailedItem> failed = new ArrayList<>();

        for (Path root : properties.resolvedAllowedRoots()) {
            if (!Files.isDirectory(root)) {
                log.debug("batch rescan: skipping non-directory root {}", root);
                continue;
            }
            // try-with-resources on the directory stream is required: Files.list
            // returns a Stream backed by a DirectoryStream that holds an OS
            // file-descriptor until close() is called.
            try (Stream<Path> children = Files.list(root)) {
                children
                        .filter(Files::isDirectory)
                        .forEach(sub -> handleSubdir(sub, source, ownerId,
                                imported, updated, skipped, failed));
            } catch (IOException e) {
                log.warn("batch rescan: failed to list root {}: {}", root, e.getMessage());
            }
        }

        log.info("batch rescan complete: source={} owner={} imported={} updated={} skipped={} failed={}",
                source.wireName(), ownerId, imported.size(), updated.size(), skipped.size(), failed.size());
        return new BatchImportResult(imported, updated, skipped, failed);
    }

    /**
     * Try to import a single subdir, classifying the outcome into the four
     * batch buckets. Wide {@code RuntimeException} catch is intentional —
     * tech-design D2: failure of one subdir must not abort the batch. We split
     * {@link IllegalStateException} (importSkill's cross-source / invariant
     * defence) and {@link IllegalArgumentException} (validation, e.g.
     * not-in-allowed-roots) into {@code skipped}; everything else (IO, DB,
     * unexpected) goes to {@code failed} so operators can investigate.
     */
    private void handleSubdir(Path sub, SkillSource source, Long ownerId,
                              List<ImportedItem> imported, List<ImportedItem> updated,
                              List<SkippedItem> skipped, List<FailedItem> failed) {
        String name = sub.getFileName().toString();
        Path skillMd = sub.resolve("SKILL.md");
        Path skillMdLower = sub.resolve("skill.md");
        if (!Files.isRegularFile(skillMd) && !Files.isRegularFile(skillMdLower)) {
            skipped.add(new SkippedItem(name, "no SKILL.md"));
            return;
        }
        try {
            // Proxy call — SkillImportService.importSkill is @Transactional and
            // we MUST go through the Spring proxy here for the annotation to
            // take effect; calling on a `this`-typed reference inside the same
            // class would bypass it.
            ImportResult r = skillImportService.importSkill(sub, source, ownerId);
            ImportedItem item = new ImportedItem(r.name(), r.skillPath());
            if (r.conflictResolved()) {
                updated.add(item);
            } else {
                imported.add(item);
            }
        } catch (IllegalStateException e) {
            // SKILL-IMPORT defence: cross-source same-name conflict / unique
            // invariant violation. Surface as a skip with the original message.
            skipped.add(new SkippedItem(name, "cross-source name conflict: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            // Validation failure inside importSkill (whitelist mismatch,
            // unparseable SKILL.md, blank name, etc.).
            skipped.add(new SkippedItem(name, e.getMessage()));
        } catch (RuntimeException e) {
            failed.add(new FailedItem(name,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            log.warn("batch rescan: subdir={} import failed", sub, e);
        }
    }
}
