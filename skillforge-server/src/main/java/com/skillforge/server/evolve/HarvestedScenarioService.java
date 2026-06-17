package com.skillforge.server.evolve;

import com.skillforge.server.entity.EvalDatasetEntity;
import com.skillforge.server.entity.EvalDatasetVersionEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.evolve.dto.ActivateScenarioResponse;
import com.skillforge.server.evolve.dto.HarvestedScenarioDto;
import com.skillforge.server.repository.EvalDatasetRepository;
import com.skillforge.server.repository.EvalDatasetVersionRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.service.EvalDatasetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b — human-gated activation of harvested
 * (session-derived) bad-case scenarios into an agent's eval dataset, plus the
 * read/harvest orchestration the dashboard endpoints delegate to.
 *
 * <p><b>Activate flow (Option A′, agent-scoped).</b> Activating a draft does NOT
 * mutate any global/shared dataset version (those are immutable snapshots).
 * Instead it maintains an agent-specific {@code <agentId>-mixed} dataset:
 * <ol>
 *   <li>first activation seeds that dataset from the currently-resolved default's
 *       members (the full benchmark);</li>
 *   <li>each activation publishes a NEW immutable version = current members ∪ the
 *       activated case.</li>
 * </ol>
 * Because {@link EvalDatasetService#findDefaultVersionIdForAgent} prefers an
 * agent-specific {@code *mixed*} dataset and picks its MAX version, the new
 * version automatically becomes that agent's resolved default — so the next A/B
 * run scores the full benchmark plus this agent's harvested cases, with zero
 * resolution-logic change and global datasets left untouched.
 *
 * <p>The activate path is the human gate: the REST layer rejects the system user
 * so the orchestrator can never activate a draft.
 */
@Service
public class HarvestedScenarioService {

    private static final Logger log = LoggerFactory.getLogger(HarvestedScenarioService.class);

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_ACTIVE = "active";
    private static final String MIXED_DATASET_SUFFIX = "-mixed";

    private final EvalScenarioDraftRepository scenarioRepository;
    private final EvalDatasetService evalDatasetService;
    private final EvalDatasetRepository datasetRepository;
    private final EvalDatasetVersionRepository versionRepository;
    private final BadCaseClusterService clusterService;
    private final BadCaseHarvestService harvestService;

    public HarvestedScenarioService(EvalScenarioDraftRepository scenarioRepository,
                                    EvalDatasetService evalDatasetService,
                                    EvalDatasetRepository datasetRepository,
                                    EvalDatasetVersionRepository versionRepository,
                                    BadCaseClusterService clusterService,
                                    BadCaseHarvestService harvestService) {
        this.scenarioRepository = scenarioRepository;
        this.evalDatasetService = evalDatasetService;
        this.datasetRepository = datasetRepository;
        this.versionRepository = versionRepository;
        this.clusterService = clusterService;
        this.harvestService = harvestService;
    }

    /** Thrown when the scenario id does not resolve — mapped to 404 by the controller. */
    public static class ScenarioNotFoundException extends RuntimeException {
        public ScenarioNotFoundException(String id) {
            super("Harvested scenario not found: " + id);
        }
    }

    /**
     * Activate a draft harvested scenario into its agent's mixed dataset (one
     * transaction: publish the new version + flip status/reviewedAt).
     *
     * @throws ScenarioNotFoundException scenario id not found (→404)
     * @throws IllegalStateException     scenario is not in {@code draft} status (→409)
     * @throws IllegalArgumentException  wrong source_type / missing agentId (→400)
     */
    @Transactional
    public ActivateScenarioResponse activate(String scenarioId, Long userId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId is required");
        }
        EvalScenarioEntity sc = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new ScenarioNotFoundException(scenarioId));
        if (!STATUS_DRAFT.equals(sc.getStatus())) {
            throw new IllegalStateException(
                    "scenario " + scenarioId + " is not a draft (status=" + sc.getStatus()
                            + "); only draft scenarios can be activated");
        }
        if (!EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED.equals(sc.getSourceType())) {
            throw new IllegalArgumentException(
                    "scenario " + scenarioId + " is not session_derived (source_type="
                            + sc.getSourceType() + "); only harvested bad cases are activated this way");
        }
        String agentId = sc.getAgentId();
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException(
                    "scenario " + scenarioId + " has no agentId; cannot resolve a target dataset");
        }

        // Resolve the agent-specific mixed dataset (or seed one from the current
        // resolved default) and compute the base member set BEFORE publishing.
        EvalDatasetEntity agentDataset = findAgentMixedDataset(agentId);
        List<String> baseMemberIds;
        if (agentDataset != null) {
            String latestVersionId = latestVersionId(agentDataset.getId());
            baseMemberIds = latestVersionId != null
                    ? idsOf(evalDatasetService.getScenariosForVersion(latestVersionId))
                    : new ArrayList<>();
        } else {
            // First activation for this agent: seed from the currently-resolved
            // default version (the global benchmark), then create the agent dataset.
            String defaultVersionId = evalDatasetService.findDefaultVersionIdForAgent(agentId);
            baseMemberIds = defaultVersionId != null
                    ? idsOf(evalDatasetService.getScenariosForVersion(defaultVersionId))
                    : new ArrayList<>();
            agentDataset = evalDatasetService.createDataset(new EvalDatasetService.CreateDatasetRequest(
                    agentId + MIXED_DATASET_SUFFIX,
                    "Agent-scoped mixed eval dataset (benchmark + harvested bad cases) for agent " + agentId,
                    userId, agentId, null, false));
            log.info("[HarvestedScenario] created agent mixed dataset {} ({}) seeded from {} benchmark members",
                    agentDataset.getId(), agentDataset.getName(), baseMemberIds.size());
        }

        // Union (harvested case appended; LinkedHashSet dedupes + preserves order).
        LinkedHashSet<String> members = new LinkedHashSet<>(baseMemberIds);
        members.add(sc.getId());
        List<String> memberList = new ArrayList<>(members);

        // Atomic (single @Transactional on this method): publishing the new dataset
        // version, and flipping the scenario to active/reviewedAt below, commit
        // together — a publish failure rolls the status flip back, and vice versa,
        // so the scenario never ends up "active" without being a dataset member.
        EvalDatasetVersionEntity version =
                evalDatasetService.publishVersion(agentDataset.getId(), memberList, userId);

        sc.setStatus(STATUS_ACTIVE);
        sc.setReviewedAt(Instant.now());
        scenarioRepository.save(sc);

        log.info("[HarvestedScenario] activated scenario {} into dataset {} v{} ({} members) by user {}",
                scenarioId, agentDataset.getId(), version.getVersionNumber(), memberList.size(), userId);

        return new ActivateScenarioResponse(
                sc.getId(), sc.getStatus(), agentId,
                version.getId(), version.getVersionNumber(), memberList.size(), sc.getReviewedAt());
    }

    /** List an agent's harvested (session_derived) scenarios in the given status. */
    @Transactional(readOnly = true)
    public List<HarvestedScenarioDto> listHarvestedScenarios(String agentId, String status) {
        if (agentId == null || agentId.isBlank()) {
            return List.of();
        }
        return scenarioRepository
                .findByAgentIdAndStatusAndSourceType(
                        agentId, status, EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED)
                .stream()
                .map(HarvestedScenarioDto::from)
                .toList();
    }

    /**
     * Cluster the agent's recent failed tool calls and rebuild one draft bad-case
     * scenario per harvestable cluster. Returns the created draft scenario ids
     * (clusters whose representative tool is not harvestable are skipped). Each
     * harvest runs in {@link BadCaseHarvestService}'s own transaction, so one
     * failure does not roll back the others.
     */
    public List<String> harvestBadCases(Long agentId, int windowDays) {
        List<BadCaseClusterService.RepresentativeSpan> reps =
                clusterService.representativeSpans(agentId, windowDays);
        List<String> created = new ArrayList<>();
        for (BadCaseClusterService.RepresentativeSpan rep : reps) {
            harvestService.harvestToolFailureCase(rep.sessionId(), rep.failingSpanId())
                    .ifPresent(s -> created.add(s.getId()));
        }
        log.info("[HarvestedScenario] harvest-bad-cases agentId={} windowDays={} clusters={} created={}",
                agentId, windowDays, reps.size(), created.size());
        return created;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Find the agent-specific {@code *mixed*} dataset (agent_id == agentId AND
     * name contains "mixed"), or null. Mirrors the agent-specific half of
     * {@link EvalDatasetService#findDefaultVersionIdForAgent}'s pattern matching.
     */
    private EvalDatasetEntity findAgentMixedDataset(String agentId) {
        // Targeted query (agent datasets are low-cardinality) + in-Java name filter
        // so we don't full-table-scan; mirrors the agent-specific half of
        // EvalDatasetService.findDefaultVersionIdForAgent's pattern match.
        for (EvalDatasetEntity d : datasetRepository.findByAgentId(agentId)) {
            if (d.getName() == null || !d.getName().toLowerCase().contains("mixed")) continue;
            return d;
        }
        return null;
    }

    /** Latest (MAX version_number) version id of a dataset, or null when it has none. */
    private String latestVersionId(String datasetId) {
        Integer maxVersion = versionRepository.findMaxVersionNumber(datasetId).orElse(null);
        if (maxVersion == null) {
            return null;
        }
        return versionRepository.findByDatasetIdAndVersionNumber(datasetId, maxVersion)
                .map(EvalDatasetVersionEntity::getId)
                .orElse(null);
    }

    private static List<String> idsOf(List<EvalScenarioEntity> scenarios) {
        List<String> ids = new ArrayList<>(scenarios.size());
        for (EvalScenarioEntity s : scenarios) {
            ids.add(s.getId());
        }
        return ids;
    }
}
