package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * P1-D — Per-row mutator used by {@link SkillCatalogReconciler}.
 *
 * <p>Lives as an independent {@code @Service} so each call from the reconciler
 * crosses the Spring AOP proxy and {@link Propagation#REQUIRES_NEW} actually
 * starts an isolated transaction. <b>Calling these methods via {@code this.}
 * from inside the reconciler would silently bypass {@code REQUIRES_NEW}</b>
 * (Spring AOP self-invocation footgun, see java.md). With separate beans, a
 * per-row failure rolls back only that row and the rescan loop continues with
 * the rest.
 */
@Service
public class SkillConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(SkillConflictResolver.class);

    private final SkillRepository skillRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public SkillConflictResolver(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    /**
     * Native upsert for a system skill row, in its own REQUIRES_NEW transaction.
     * Mirrors the legacy {@code SystemSkillLoader.upsertSystemSkillRow} so a single
     * row failure cannot poison subsequent upserts (footgun #8 — EntityManager
     * rollback contagion). Best-effort: SQL exceptions are logged at WARN.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertSystemRow(SkillDefinition def, String skillPath, String contentHash) {
        String triggers = def.getTriggers() != null ? String.join(",", def.getTriggers()) : null;
        String requiredTools = def.getRequiredTools() != null
                ? String.join(",", def.getRequiredTools())
                : null;
        String sql = "INSERT INTO t_skill "
                + "(owner_id, name, description, triggers, required_tools, skill_path, "
                + " is_public, is_system, enabled, source, usage_count, success_count, failure_count, "
                + " content_hash, artifact_status, last_scanned_at) "
                + "VALUES (NULL, :name, :description, :triggers, :requiredTools, :skillPath, "
                + "        false, true, true, 'system', 0, 0, 0, "
                + "        :contentHash, 'active', :lastScannedAt) "
                + "ON CONFLICT (COALESCE(owner_id, -1), name) DO UPDATE SET "
                + "  description     = EXCLUDED.description, "
                + "  triggers        = EXCLUDED.triggers, "
                + "  required_tools  = EXCLUDED.required_tools, "
                + "  skill_path      = EXCLUDED.skill_path, "
                + "  is_system       = true, "
                + "  enabled         = true, "
                + "  content_hash    = EXCLUDED.content_hash, "
                + "  artifact_status = 'active', "
                + "  last_scanned_at = EXCLUDED.last_scanned_at";
        try {
            entityManager.createNativeQuery(sql)
                    .setParameter("name", def.getName())
                    .setParameter("description", def.getDescription())
                    .setParameter("triggers", triggers)
                    .setParameter("requiredTools", requiredTools)
                    .setParameter("skillPath", skillPath)
                    .setParameter("contentHash", contentHash)
                    .setParameter("lastScannedAt", Timestamp.from(Instant.now()))
                    .executeUpdate();
        } catch (Exception e) {
            log.warn("upsertSystemRow failed for '{}': {}", def.getName(), e.getMessage());
        }
    }

    /**
     * Mark a runtime row as shadowed by a system skill of the same name. Sets
     * {@code enabled=false}, {@code artifact_status='shadowed'},
     * {@code shadowed_by='system:<name>'}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markShadowedBySystem(Long id, String systemName) {
        SkillEntity row = skillRepository.findById(id).orElse(null);
        if (row == null) {
            log.warn("markShadowedBySystem skip: row id={} vanished", id);
            return;
        }
        row.setEnabled(false);
        row.setArtifactStatus("shadowed");
        row.setShadowedBy("system:" + systemName);
        skillRepository.save(row);
    }

    /**
     * Mark a runtime row as shadowed by another runtime row (the winner).
     * Sets {@code enabled=false}, {@code artifact_status='shadowed'},
     * {@code shadowed_by='runtime:<winnerId>'}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markShadowedByRuntime(Long id, Long winnerId) {
        SkillEntity row = skillRepository.findById(id).orElse(null);
        if (row == null) {
            log.warn("markShadowedByRuntime skip: row id={} vanished", id);
            return;
        }
        row.setEnabled(false);
        row.setArtifactStatus("shadowed");
        row.setShadowedBy("runtime:" + winnerId);
        skillRepository.save(row);
    }

    /** Mark a row as missing (artifact directory not found on disk). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markMissing(Long id) {
        SkillEntity row = skillRepository.findById(id).orElse(null);
        if (row == null) return;
        row.setArtifactStatus("missing");
        row.setEnabled(false);
        skillRepository.save(row);
    }

    /** Mark a row as invalid (package failed to load). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markInvalid(Long id) {
        SkillEntity row = skillRepository.findById(id).orElse(null);
        if (row == null) return;
        row.setArtifactStatus("invalid");
        row.setEnabled(false);
        skillRepository.save(row);
    }

    /**
     * Reset a previously shadowed/missing/invalid row back to {@code active}
     * status if its underlying artifact has reappeared and is no longer
     * shadowed. Idempotent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearShadowOrError(Long id) {
        SkillEntity row = skillRepository.findById(id).orElse(null);
        if (row == null) return;
        row.setArtifactStatus("active");
        row.setShadowedBy(null);
        skillRepository.save(row);
    }
}
