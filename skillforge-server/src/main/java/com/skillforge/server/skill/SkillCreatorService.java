package com.skillforge.server.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plan r2 §4 — Stage B template renderer for approveDraft pipeline.
 * <p>Takes draft metadata (name / description / triggers / required_tools / promptHint) and
 * writes a SKILL.md file to {@code targetDir}. Does NOT call the LLM (avoids re-entering the
 * agent loop from inside approveDraft).
 * <p>The output schema mirrors what {@link com.skillforge.core.skill.SkillPackageLoader}
 * expects to parse:
 * <pre>
 *   ---
 *   name: ...
 *   description: ...
 *   allowed-tools: ...
 *   ---
 *   (body — promptHint or fallback)
 * </pre>
 *
 * <p>SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18): grew the
 * {@link #dispatchEvaluation(String, String, List)} entry-point that spins
 * 2N SubAgent child sessions per scenario (with_skill / without_skill
 * baseline) for the evaluation gate on the 4 skill-creation entry-points
 * (upload / marketplace / natural-language / extract-from-sessions). The
 * companion {@code SkillCreatorEvalCoordinator} listener picks up the
 * resulting {@code SessionLoopFinishedEvent}s, aggregates judge output, and
 * writes the {@code EvaluationResult} back into {@code t_skill_draft}.
 *
 * <p>Why both ctors: the legacy 0-arg ctor preserves the
 * {@code SkillCreatorServiceTest#render_producesParseableSkillMd}-style
 * test that builds {@code new SkillCreatorService()} (render-only path).
 * Spring picks the 9-arg ctor at runtime via {@code @Autowired} so the
 * dispatch / eval surface gets wired without breaking the unit-test
 * fixture (matching the V6 R3 promoteDraftToTransientSkill pattern in
 * SkillDraftService — see line 864).
 */
@Service
public class SkillCreatorService {

    private static final Logger log = LoggerFactory.getLogger(SkillCreatorService.class);

    /**
     * Pass-rate delta threshold for promote vs reject. cc agentskills.io uses
     * a heuristic-driven threshold; 5pp is a conservative start (matches the
     * spec D7 default). Dogfood Phase 1.6 will tune.
     */
    public static final double PASS_RATE_DELTA_THRESHOLD = 0.05;

    /**
     * Score threshold for "passing" a single scenario when computing
     * {@code passRate}. cc convention is 0.7 (≥ 70%) on a 0..1 composite.
     */
    public static final double SCENARIO_PASS_SCORE = 0.7;

    public static final String EVALUATOR_VERSION = "skill-creator-1.0";

    public static final String STATUS_EVALUATING = "evaluating";
    public static final String STATUS_EVALUATED_PASSED = "evaluated_passed";
    public static final String STATUS_REJECTED = "rejected";

    /** Wire-format markers for the eval-context JSON column on child sessions. */
    public static final String BASELINE_WITH_SKILL = "with_skill";
    public static final String BASELINE_WITHOUT_SKILL = "without_skill";

    // ───── render-only deps (Phase 1 V1 baseline; 0-arg ctor) ─────
    // (none — render() is a pure function over the draft fields + filesystem)

    // ───── dispatchEvaluation deps (Phase 1.1) ─────
    private final SkillDraftRepository draftRepository;
    private final SkillRepository skillRepository;
    private final EvalScenarioDraftRepository scenarioRepository;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final AgentService agentService;
    private final SubAgentRegistry subAgentRegistry;
    private final ObjectMapper objectMapper;
    /**
     * Phase 1.1 B1-fix (2026-05-18, java-reviewer Phase 2.0): publishes
     * {@link SkillEvalDispatchReadyEvent} after the synchronous dispatch
     * transaction completes the per-scenario child-session writes. The
     * {@link SkillCreatorEvalCoordinator#onSkillEvalDispatchReady} listener
     * fires on AFTER_COMMIT and is responsible for calling
     * {@code chatService.chatAsync} per child session — by which time the
     * fresh row + override columns are visible to the async loop's read.
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Legacy 0-arg ctor — preserves the render-only path exercised by the
     * existing {@code SkillCreatorServiceTest#render_producesParseableSkillMd}
     * unit test (which builds {@code new SkillCreatorService()} directly).
     * Calling {@link #dispatchEvaluation(String, String, List)} on this
     * instance will throw NPE — intentionally; the 0-arg path is for the
     * pre-eval render path only.
     */
    public SkillCreatorService() {
        this.draftRepository = null;
        this.skillRepository = null;
        this.scenarioRepository = null;
        this.sessionRepository = null;
        this.sessionService = null;
        this.chatService = null;
        this.agentService = null;
        this.subAgentRegistry = null;
        this.objectMapper = null;
        this.eventPublisher = null;
    }

    /**
     * Spring-wired ctor (Phase 1.1). {@code @Lazy ChatService} mirrors
     * SubAgentTool's bean wiring — ChatService depends on SkillRegistry, and
     * a cycle would otherwise prevent context startup.
     *
     * <p>Phase 1.1 B1-fix (2026-05-18): {@link ApplicationEventPublisher} added
     * so the synchronous dispatch can defer the async loop start to an
     * AFTER_COMMIT listener (avoids the chatAsync-sees-uncommitted-state race).
     */
    @Autowired
    public SkillCreatorService(SkillDraftRepository draftRepository,
                                SkillRepository skillRepository,
                                EvalScenarioDraftRepository scenarioRepository,
                                SessionRepository sessionRepository,
                                SessionService sessionService,
                                @Lazy ChatService chatService,
                                AgentService agentService,
                                SubAgentRegistry subAgentRegistry,
                                ObjectMapper objectMapper,
                                ApplicationEventPublisher eventPublisher) {
        this.draftRepository = draftRepository;
        this.skillRepository = skillRepository;
        this.scenarioRepository = scenarioRepository;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.agentService = agentService;
        this.subAgentRegistry = subAgentRegistry;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Render the draft into a SKILL.md inside {@code targetDir}. Creates {@code targetDir}
     * (and parents) if missing.
     *
     * @param draft     draft metadata
     * @param targetDir directory to write SKILL.md into
     * @throws IOException on filesystem failures (caller is expected to catch + rethrow per
     *                     plan §3 STEP 3+4).
     */
    public void render(SkillDraftEntity draft, Path targetDir) throws IOException {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(targetDir, "targetDir");

        Files.createDirectories(targetDir);

        String name = safe(draft.getName(), "unnamed-skill");
        String description = safe(draft.getDescription(), "");
        String requiredTools = safe(draft.getRequiredTools(), "");
        String triggers = safe(draft.getTriggers(), "");
        String body = safe(draft.getPromptHint(), "");

        StringBuilder md = new StringBuilder();
        md.append("---\n");
        md.append("name: ").append(escapeYamlScalar(name)).append('\n');
        md.append("description: ").append(escapeYamlScalar(description)).append('\n');
        if (!requiredTools.isBlank()) {
            // SkillDefinition.allowedTools is a List<String>; emit YAML flow-sequence syntax
            // so SkillPackageLoader can parse it back. Accept either ',' or whitespace as
            // input separator (LLM extractor output is inconsistent).
            String[] parts = requiredTools.split("[,\\s]+");
            StringBuilder flow = new StringBuilder("[");
            boolean first = true;
            for (String p : parts) {
                if (p == null || p.isBlank()) continue;
                if (!first) flow.append(", ");
                flow.append(escapeYamlScalar(p.trim()));
                first = false;
            }
            flow.append("]");
            if (flow.length() > 2) {  // not just "[]"
                md.append("allowed-tools: ").append(flow).append('\n');
            }
        }
        if (!triggers.isBlank()) {
            // Triggers in SkillDefinition is also a List<String>.
            String[] parts = triggers.split("[,]+");
            StringBuilder flow = new StringBuilder("[");
            boolean first = true;
            for (String p : parts) {
                if (p == null || p.isBlank()) continue;
                if (!first) flow.append(", ");
                flow.append(escapeYamlScalar(p.trim()));
                first = false;
            }
            flow.append("]");
            if (flow.length() > 2) {
                md.append("triggers: ").append(flow).append('\n');
            }
        }
        md.append("---\n\n");
        md.append("# ").append(name).append("\n\n");
        if (!description.isBlank()) {
            md.append(description).append("\n\n");
        }
        if (!body.isBlank()) {
            md.append(body).append('\n');
        } else {
            md.append("_(promptHint not provided by extractor; populate manually before use)_\n");
        }

        Path skillMd = targetDir.resolve("SKILL.md");
        Files.writeString(skillMd, md.toString(), StandardCharsets.UTF_8);
        log.debug("SkillCreatorService rendered SKILL.md at {}", skillMd);
    }

    /**
     * SKILL-CREATOR-WITH-EVAL Phase 1.1 entry-point. Spins {@code 2 *
     * scenarioIds.size()} SubAgent child sessions against the target agent
     * (one {@code with_skill} run + one {@code without_skill} run per
     * scenario) and returns the list of runIds. Async completion is collected
     * by {@code SkillCreatorEvalCoordinator} (listener on
     * {@code SessionLoopFinishedEvent}; see Phase 1.0 verify report for the
     * spec D2 / D7 — callback hook path d).
     *
     * <p>Caller responsibility:
     * <ul>
     *   <li>{@code parentSessionId} — must be a valid existing session under
     *       which the SubAgent dispatches will spawn. Typically the
     *       skill-creator agent's current session (entry 3 natural-language)
     *       or a synthetic system session created by the controller for the
     *       upload / marketplace / extract entry-points.</li>
     *   <li>{@code draft} — must have {@code targetAgentId} set (the upload /
     *       marketplace / natural-language entry-points populate it directly;
     *       the extract-from-sessions entry-point back-fills from
     *       {@code sourceSession.agent_id}).</li>
     *   <li>{@code scenarioIds} — ephemeral {@link com.skillforge.server.entity.EvalScenarioEntity}
     *       ids written by the entry-point (zip parse / session
     *       conversion / agent step 1 prompt capture).</li>
     * </ul>
     *
     * <p>Behaviour:
     * <ol>
     *   <li>Resolve target agent + render a transient {@link SkillEntity}
     *       (V6 R3 {@code promoteDraftToTransientSkill} pattern — see
     *       SkillDraftService:864). The transient skill carries the draft's
     *       rendered SKILL.md and stays {@code enabled=false} so production
     *       traffic doesn't accidentally pick it up.</li>
     *   <li>Compute the {@code with_skill} override list = current agent
     *       skill names + candidate transient skill name. {@code without_skill}
     *       override = empty list (clean baseline per cc agentskills.io).</li>
     *   <li>Stash a pending stub into {@code draft.evaluationResultJson} so
     *       the coordinator listener can find the expected count when child
     *       sessions complete asynchronously, even after a server restart.</li>
     *   <li>Serial-dispatch 2N SubAgent runs — synchronous-iteration avoids
     *       the {@code SubAgentRegistry.MAX_ACTIVE_CHILDREN_PER_PARENT=5} cap
     *       (verified Phase 1.0 line 43) for batches of N≥3 scenarios.</li>
     * </ol>
     *
     * @return list of SubAgent runIds — caller usually only logs them; the
     *         async aggregation result lands on {@code draft.evaluationResultJson}.
     * @throws IllegalArgumentException missing draft / scenario / parent session
     * @throws IllegalStateException    legacy 0-arg ctor used (deps not wired)
     */
    @Transactional
    public List<String> dispatchEvaluation(String parentSessionId,
                                            String draftId,
                                            List<String> scenarioIds) {
        requireWired();
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(scenarioIds, "scenarioIds");
        if (scenarioIds.isEmpty()) {
            throw new IllegalArgumentException("scenarioIds must not be empty");
        }

        SkillDraftEntity draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Skill draft not found: " + draftId));

        // (1) Resolve target agent id — back-fill from sourceSession if extract path didn't.
        Long targetAgentId = draft.getTargetAgentId();
        if (targetAgentId == null && draft.getSourceSessionId() != null) {
            targetAgentId = sessionRepository.findById(draft.getSourceSessionId())
                    .map(SessionEntity::getAgentId)
                    .orElse(null);
            if (targetAgentId != null) {
                draft.setTargetAgentId(targetAgentId);
            }
        }
        if (targetAgentId == null) {
            throw new IllegalArgumentException("Draft " + draftId + " has no resolvable target agent "
                    + "(neither targetAgentId nor sourceSessionId set).");
        }
        AgentEntity targetAgent = agentService.getAgent(targetAgentId);

        // (1b) Resolve parent session — caller-provided OR auto-create a
        // synthetic eval-orchestrator session so the 4 entry-points (upload /
        // marketplace / natural-language / extract-from-sessions) can call us
        // uniformly. The orchestrator session is origin='eval' so it never
        // appears in production list endpoints; SubAgentRegistry.registerRun
        // requires a non-null parent so we have to give it one.
        SessionEntity parent;
        if (parentSessionId != null) {
            parent = sessionRepository.findById(parentSessionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Parent session not found: " + parentSessionId));
        } else {
            parent = createSyntheticOrchestratorSession(targetAgentId, draft.getOwnerId(), draftId);
            log.info("SkillCreatorService.dispatchEvaluation: auto-created synthetic orchestrator "
                    + "session={} for draft={}", parent.getId(), draftId);
        }

        // (2) Render transient candidate SkillEntity. We reuse SkillDraftService.
        //     promoteDraftToTransientSkill via a sibling path here would be a circular
        //     dep (SkillDraftService → SkillCreatorService → SkillDraftService);
        //     instead we inline the equivalent V6 R3 logic with a dedicated suffix
        //     so eval-time transients can be distinguished from A/B-eval transients
        //     in cleanup queries.
        SkillEntity transientSkill = renderTransientCandidateSkill(draft);
        draft.setCandidateSkillId(transientSkill.getId());

        // (3) Build effective override lists.
        AgentDefinition agentDef = agentService.toAgentDefinition(targetAgent);
        List<String> currentSkillNames = agentDef.getSkillIds() != null
                ? new ArrayList<>(agentDef.getSkillIds())
                : new ArrayList<>();
        String candidateName = transientSkill.getName();
        List<String> withSkillOverride = new ArrayList<>(currentSkillNames);
        if (!withSkillOverride.contains(candidateName)) {
            withSkillOverride.add(candidateName);
        }
        List<String> withoutSkillOverride = List.of();

        // (4) Pending stub — lets the coordinator know expectedCount + scenario list
        //     even after server restart. Coordinator overwrites with the final
        //     EvaluationResult once all 2N children land.
        int expectedCount = 2 * scenarioIds.size();
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("_pending", true);
        pending.put("expectedCount", expectedCount);
        pending.put("scenarioIds", scenarioIds);
        pending.put("parentSessionId", parentSessionId);
        try {
            draft.setEvaluationResultJson(objectMapper.writeValueAsString(pending));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize pending evaluation stub", e);
        }
        draft.setStatus(STATUS_EVALUATING);
        draftRepository.save(draft);

        // (5) Serial dispatch 2N SubAgent run-rows + child sessions, BUT do
        //     NOT start the async chat loop yet. The loops must fire on
        //     AFTER_COMMIT (see SkillEvalDispatchReadyEvent + java-reviewer
        //     Phase 2.0 B1 fix), otherwise the async transaction reads
        //     {@code skill_overrides_json} as null and silently falls back
        //     to {@code agent.skillIds} — both with_skill and without_skill
        //     sides end up using the agent's actual skill list.
        List<String> runIds = new ArrayList<>();
        List<String> childSessionIds = new ArrayList<>();
        Map<String, String> taskBySession = new LinkedHashMap<>();
        for (String scenarioId : scenarioIds) {
            var scenario = scenarioRepository.findById(scenarioId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Eval scenario not found: " + scenarioId));
            String task = scenario.getTask();
            DispatchHandle withHandle = dispatchOne(parent, targetAgent, task, withSkillOverride,
                    draftId, scenarioId, BASELINE_WITH_SKILL);
            DispatchHandle withoutHandle = dispatchOne(parent, targetAgent, task, withoutSkillOverride,
                    draftId, scenarioId, BASELINE_WITHOUT_SKILL);
            runIds.add(withHandle.runId());
            runIds.add(withoutHandle.runId());
            childSessionIds.add(withHandle.childSessionId());
            childSessionIds.add(withoutHandle.childSessionId());
            taskBySession.put(withHandle.childSessionId(), task);
            taskBySession.put(withoutHandle.childSessionId(), task);
        }

        // (6) Publish AFTER_COMMIT signal. The coordinator listener fires
        //     chatService.chatAsync per child session in its own REQUIRES_NEW
        //     transaction — by that point the child SessionEntity rows from
        //     step (5) are committed and the override columns are visible to
        //     ChatService.runLoop's freshSession.getSkillOverridesJson() read.
        eventPublisher.publishEvent(new SkillEvalDispatchReadyEvent(
                draftId, childSessionIds, parent.getUserId(), taskBySession));

        log.info("SkillCreatorService.dispatchEvaluation: draftId={} parentSessionId={} "
                        + "candidateSkillId={} runIds.size={} (expected 2N={}) — "
                        + "AFTER_COMMIT event queued, async loops will start once outer tx commits",
                draftId, parentSessionId, transientSkill.getId(), runIds.size(), expectedCount);
        return runIds;
    }

    /**
     * Phase 1.1 B1-fix (2026-05-18): tuple of "what we just persisted" so the
     * caller can stash both the SubAgent runId and the child session id (the
     * latter is what the AFTER_COMMIT listener needs to {@code chatAsync}).
     */
    private record DispatchHandle(String runId, String childSessionId) {}

    /**
     * Render a transient {@link SkillEntity} suffix-tagged for skill-creator-
     * eval so cleanup queries can distinguish it from A/B-eval transients
     * (the V6 R3 path tags with {@code _candidate_<uuid>} +
     * {@code source="attribution_ab_transient"}; we tag with {@code _eval_<uuid>}
     * + {@code source="skill-creator-eval-transient"}).
     *
     * <p>Skill body is rendered from {@code draft.promptHint} via the same
     * {@link #render(SkillDraftEntity, Path)} pipeline; the disk path is
     * allocated under the standard {@code SkillStorageService.allocate}
     * runtime root (see Phase 1.0 verify item #5).
     */
    private SkillEntity renderTransientCandidateSkill(SkillDraftEntity draft) {
        String transientUuid = java.util.UUID.randomUUID().toString();
        String suffix = "_eval_" + transientUuid.substring(0, 8);

        SkillEntity transientSkill = new SkillEntity();
        transientSkill.setName(draft.getName() + suffix);
        transientSkill.setDescription(draft.getDescription());
        transientSkill.setTriggers(draft.getTriggers());
        transientSkill.setRequiredTools(draft.getRequiredTools());
        transientSkill.setOwnerId(draft.getOwnerId());
        transientSkill.setEnabled(false);
        transientSkill.setSource("skill-creator-eval-transient");
        transientSkill.setSystem(false);
        transientSkill.setRiskLevel("low");

        // For Phase 1.1 minimal scope we don't write the SKILL.md to disk —
        // the eval-time SubAgent child reads the skill from SkillRegistry by
        // name, and the listener (Phase 1.1 coordinator) only needs the
        // database row to identify the candidate. Phase 1.6 dogfood will
        // re-evaluate whether the engine needs the on-disk artifact too;
        // current SkillAbEvalService path uses SandboxSkillRegistryFactory
        // which doesn't require disk presence. (Approval path -- not Phase 1.1
        // -- will materialize the skill to disk if/when operator promotes.)
        transientSkill.setSkillPath(null);

        return skillRepository.save(transientSkill);
    }

    /**
     * Inline equivalent of {@code SubAgentTool.handleDispatch} but written in
     * Java for direct invocation by {@link #dispatchEvaluation}. We don't go
     * through SubAgentTool here because:
     * <ul>
     *   <li>The eval path needs to stamp {@code eval_context_json} on the
     *       child session for the coordinator's listener (a SubAgentTool
     *       concept doesn't belong in that LLM-facing surface).</li>
     *   <li>SubAgentTool is wired via {@code SkillRegistry} for tool-call
     *       dispatch; invoking it directly from a service forces a fake
     *       {@code SkillContext} and re-parses the input map — unnecessary
     *       indirection for a server-internal caller.</li>
     * </ul>
     */
    private DispatchHandle dispatchOne(SessionEntity parent, AgentEntity agent, String task,
                                       List<String> skillOverrides, String draftId,
                                       String scenarioId, String baselineLabel) {
        SubAgentRegistry.SubAgentRun run = subAgentRegistry.registerRun(
                parent, agent.getId(), agent.getName(), task);

        SessionEntity child = sessionService.createSubSession(parent, agent.getId(), run.runId);

        // skill_overrides_json — picked up by ChatService.runLoop on loop start.
        try {
            child.setSkillOverridesJson(objectMapper.writeValueAsString(skillOverrides));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize skill overrides for child "
                    + child.getId(), e);
        }
        // eval_context_json — the coordinator's marker.
        Map<String, String> evalCtx = new LinkedHashMap<>();
        evalCtx.put("draftId", draftId);
        evalCtx.put("scenarioId", scenarioId);
        evalCtx.put("baselineLabel", baselineLabel);
        try {
            child.setEvalContextJson(objectMapper.writeValueAsString(evalCtx));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize eval context for child "
                    + child.getId(), e);
        }
        // OBS-4 §2.5 INV-4: inherit parent active_root for trace continuity.
        child.setActiveRootTraceId(parent.getActiveRootTraceId());
        // Tag eval origin so eval traffic doesn't pollute production aggregates.
        child.setOrigin(SessionEntity.ORIGIN_EVAL);
        sessionService.saveSession(child);

        subAgentRegistry.attachChildSession(run.runId, child.getId());

        // Phase 1.1 B1-fix (2026-05-18, java-reviewer Phase 2.0): DO NOT call
        // chatService.chatAsync here. The async runLoop would otherwise read
        // freshSession.getSkillOverridesJson() in a fresh transaction that
        // can't see the row we just saved (outer @Transactional hasn't
        // committed yet). The override would silently fall back to
        // agent.skillIds, giving with_skill and without_skill the same skill
        // list. Deferred to SkillCreatorEvalCoordinator.onSkillEvalDispatchReady
        // (AFTER_COMMIT listener; see SkillEvalDispatchReadyEvent class doc).

        return new DispatchHandle(run.runId, child.getId());
    }

    /**
     * SKILL-CREATOR-WITH-EVAL Phase 1.2 (2026-05-18) — parse a zip's
     * {@code evals/evals.json} into ephemeral {@link EvalScenarioEntity}
     * rows ready for {@link #dispatchEvaluation}. Used by the upload (entry 1)
     * and marketplace-import (entry 2) hooks to bootstrap the eval batch
     * before dispatch.
     *
     * <p>Expected JSON shape (matches {@code system-skills/skill-creator/evals/
     * evals.json} self-eval template):
     * <pre>
     * {
     *   "skill_name": "...",
     *   "evals": [ {"id": 1, "prompt": "...", "expected_output": "...", ...}, ... ]
     * }
     * </pre>
     *
     * <p>Returns an empty list when:
     * <ul>
     *   <li>{@code evals/evals.json} does not exist (skill author didn't ship one)</li>
     *   <li>JSON is malformed or {@code evals} array is empty</li>
     *   <li>individual eval entries miss {@code prompt} (the only mandatory field)</li>
     * </ul>
     *
     * <p>Empty list → entry-point hook silently skips dispatchEvaluation
     * (legacy "register skill, no eval" path). Non-empty list → caller saves
     * them and calls dispatchEvaluation.
     *
     * <p>Each emitted scenario gets {@code status="ephemeral"} so it falls
     * under {@link com.skillforge.server.improve.EphemeralScenarioCleanupService}
     * cleanup after the coordinator aggregates (verified Phase 1.0).
     *
     * @param extractedSkillRoot absolute path to the directory the zip was unpacked into
     * @param targetAgentId      agent id that the eval will run against; written to
     *                           {@link EvalScenarioEntity#getAgentId()}
     */
    public List<EvalScenarioEntity> buildEphemeralScenariosFromZip(java.nio.file.Path extractedSkillRoot,
                                                                    Long targetAgentId) {
        requireWired();
        if (extractedSkillRoot == null || targetAgentId == null) {
            return List.of();
        }
        java.nio.file.Path evalsJson = extractedSkillRoot.resolve("evals").resolve("evals.json");
        if (!java.nio.file.Files.isRegularFile(evalsJson)) {
            return List.of();
        }
        try {
            String body = java.nio.file.Files.readString(evalsJson, java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> root = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object evalsObj = root.get("evals");
            if (!(evalsObj instanceof List<?> entries)) return List.of();

            List<EvalScenarioEntity> out = new ArrayList<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> m)) continue;
                Object promptObj = m.get("prompt");
                if (promptObj == null) continue;
                String task = promptObj.toString();
                if (task.isBlank()) continue;

                EvalScenarioEntity sc = new EvalScenarioEntity();
                sc.setId(java.util.UUID.randomUUID().toString());
                sc.setAgentId(String.valueOf(targetAgentId));
                Object idObj = m.get("id");
                String displayId = idObj == null ? sc.getId().substring(0, 8) : idObj.toString();
                sc.setName("skill-eval-" + displayId);
                Object descObj = m.get("description");
                sc.setDescription(descObj == null ? null : descObj.toString());
                sc.setCategory("skill-creator-eval");
                sc.setTask(task);
                Object expected = m.get("expected_output");
                if (expected != null) {
                    sc.setOracleExpected(expected.toString());
                }
                sc.setStatus("ephemeral"); // V6 cleanup pattern
                out.add(sc);
            }
            return out;
        } catch (java.io.IOException e) {
            log.warn("buildEphemeralScenariosFromZip: failed to parse {} — falling back to no eval: {}",
                    evalsJson, e.getMessage());
            return List.of();
        }
    }

    /**
     * SKILL-CREATOR-WITH-EVAL Phase 1.2 (2026-05-18) — convert a list of
     * source sessions to ephemeral evaluation scenarios for the
     * extract-from-sessions entry-point (entry 4). Each session contributes
     * one scenario whose {@code task} is the first user message in the
     * session's transcript.
     *
     * <p>Returns an empty list if no session has a parseable first-user
     * message; entry 4 hook treats empty as "skip eval, just save draft".
     *
     * <p>Phase 1.6 dogfood may augment this with multi-turn task generation
     * (using subsequent assistant turns as oracle_expected). For Phase 1.2
     * we keep it single-turn: first user prompt = task, last assistant
     * response = oracle_expected (heuristic baseline).
     */
    public List<EvalScenarioEntity> buildEphemeralScenariosFromSessions(List<SessionEntity> sessions,
                                                                         Long targetAgentId) {
        requireWired();
        if (sessions == null || sessions.isEmpty() || targetAgentId == null) {
            return List.of();
        }
        List<EvalScenarioEntity> out = new ArrayList<>();
        for (SessionEntity s : sessions) {
            String firstUserPrompt = extractFirstUserPrompt(s.getMessagesJson());
            if (firstUserPrompt == null || firstUserPrompt.isBlank()) continue;

            EvalScenarioEntity sc = new EvalScenarioEntity();
            sc.setId(java.util.UUID.randomUUID().toString());
            sc.setAgentId(String.valueOf(targetAgentId));
            String sidShort = s.getId().substring(0, Math.min(8, s.getId().length()));
            sc.setName("session-derived-" + sidShort);
            sc.setCategory("session_derived");
            sc.setTask(firstUserPrompt);
            sc.setSourceSessionId(s.getId());
            sc.setStatus("ephemeral");
            out.add(sc);
        }
        return out;
    }

    /** Best-effort first-user-message extraction. Returns null on parse failure. */
    private String extractFirstUserPrompt(String messagesJson) {
        if (messagesJson == null || messagesJson.isBlank()) return null;
        try {
            List<Map<String, Object>> messages = objectMapper.readValue(
                    messagesJson, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> msg : messages) {
                Object role = msg.get("role");
                if (!"user".equals(role)) continue;
                Object content = msg.get("content");
                if (content instanceof String s && !s.isBlank()) {
                    return s;
                }
                if (content instanceof List<?> blocks && !blocks.isEmpty()) {
                    // Multimodal array shape — pluck the first text block.
                    for (Object block : blocks) {
                        if (block instanceof Map<?, ?> bm) {
                            Object type = bm.get("type");
                            Object text = bm.get("text");
                            if ("text".equals(type) && text instanceof String ts && !ts.isBlank()) {
                                return ts;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("extractFirstUserPrompt: failed to parse messagesJson — {}", e.getMessage());
        }
        return null;
    }

    /**
     * Create an ephemeral parent session for entries 1, 2, 4 that don't have
     * a natural user-facing session to dispatch under. Tagged
     * {@code origin='eval'} so list / dashboard endpoints filter it out;
     * runtime_status='idle' so it's never picked up by chat-resume sweepers;
     * title makes its provenance obvious to anyone who queries DB directly.
     */
    private SessionEntity createSyntheticOrchestratorSession(Long targetAgentId,
                                                              Long ownerUserId,
                                                              String draftId) {
        SessionEntity orchestrator = new SessionEntity();
        orchestrator.setId(java.util.UUID.randomUUID().toString());
        // ownerUserId may be null for legacy drafts (pre-V91); fall back to 0
        // for system-owned eval traffic. SessionEntity.userId is NOT NULL.
        orchestrator.setUserId(ownerUserId == null ? 0L : ownerUserId);
        orchestrator.setAgentId(targetAgentId);
        orchestrator.setTitle("[skill-creator-eval orchestrator " + draftId + "]");
        orchestrator.setMessagesJson("[]");
        orchestrator.setRuntimeStatus("idle");
        orchestrator.setOrigin(SessionEntity.ORIGIN_EVAL);
        // No active_root_trace_id — orchestrator session itself doesn't run
        // any LLM trace. Children copy parent.activeRootTraceId (which is
        // null here, so each child becomes its own trace root via the
        // existing INV-4 fallback). That's the right semantics: eval scenarios
        // are independent traces.
        return sessionService.saveSession(orchestrator);
    }

    /**
     * Guard: throw a clear error if the legacy 0-arg ctor was used and a
     * dispatch path is invoked. Better than NPE during the first DB query.
     */
    private void requireWired() {
        if (draftRepository == null || objectMapper == null) {
            throw new IllegalStateException(
                    "SkillCreatorService.dispatchEvaluation called on a render-only "
                            + "instance — the legacy 0-arg constructor doesn't wire Spring beans. "
                            + "Inject the service via @Autowired (Phase 1.1+ Spring path).");
        }
    }

    private static String safe(String s, String fallback) {
        return s == null ? fallback : s;
    }

    /**
     * Minimal YAML scalar escape — quote if the value contains a colon or starts with a
     * special character. We don't need full YAML coverage because draft fields are short
     * single-line strings.
     */
    private static String escapeYamlScalar(String s) {
        if (s == null) return "";
        boolean needQuote = s.contains(":") || s.contains("#") || s.startsWith("-")
                || s.startsWith("[") || s.startsWith("{") || s.startsWith("!")
                || s.startsWith("*") || s.startsWith("&") || s.startsWith("?")
                || s.startsWith("|") || s.startsWith(">") || s.contains("\n");
        if (!needQuote) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
