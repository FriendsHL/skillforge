package com.skillforge.server.eval.usersim;

import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.engine.ToolCallRecord;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.config.EvalUserSimulatorProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SimulatorTrialEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.eval.EvalEngineFactory;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.improve.surface.OptimizableSurface;
import com.skillforge.server.improve.surface.SandboxContext;
import com.skillforge.server.improve.surface.SurfaceRegistry;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SimulatorTrialRepository;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2: Java-orchestrated dual-engine ping-pong
 * loop driving a UserSimulatorAgent ↔ candidate-agent multi-turn trial.
 *
 * <p>Design (Phase 1.2.0 ratify-locked, team-lead 4-ACK 2026-05-16): mirrors
 * the proven V4 {@code SkillAbEvalService.runMultiTurnScenario} pattern —
 * Java outer for-loop, each turn submits one {@code engine.run(...)} to a
 * worker pool, accumulates history, breaks on UserSim termination signal.
 *
 * <p>Why not SubAgentDispatchService: that path is fire-and-forget async with
 * results delivered as user messages — not suitable for tight per-turn
 * ping-pong inside a synchronous orchestrator.
 *
 * <p>Why not ChatService.chatAsync: chatAsync drives one agent loop only +
 * touches the core 7+1 invariant files; ping-pong needs two engines coordinated
 * by Java, not LLM control flow.
 *
 * <p>Iron Law: this orchestrator does NOT touch
 * AgentLoopEngine/Message/ChatService/SessionService.rewriteMessages —
 * persistence-shape-invariant is automatically held because candidate-side
 * messages go through {@code SessionService.appendNormalMessages} (no reminder
 * injection / no commonPrefixSize round-trip).
 *
 * <p>UserSim side: history is in-memory only, not persisted (V4 parity).
 * Candidate side: persisted into a real {@code t_session origin='user_sim'}
 * row.
 */
@Service
public class SimulatorTrialOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SimulatorTrialOrchestrator.class);

    public static final String TERMINATE_MARKER = "[TERMINATE]";

    public static final String REASON_TASK_COMPLETED = "task_completed";
    public static final String REASON_FAILURE_SIGNAL = "failure_signal";
    public static final String REASON_MAX_TURNS = "max_turns";
    public static final String REASON_TIMEOUT = "timeout";
    public static final String REASON_ERROR = "error";

    private final SimulatorTrialRepository trialRepository;
    private final EvalScenarioDraftRepository scenarioRepository;
    private final AgentRepository agentRepository;
    private final SessionService sessionService;
    private final EvalEngineFactory evalEngineFactory;
    private final SkillRegistry skillRegistry;
    private final SurfaceRegistry surfaceRegistry;
    private final SandboxSkillRegistryFactory sandboxFactory;
    private final SkillPackageLoader skillPackageLoader;
    private final EvalUserSimulatorProperties properties;
    private final ExecutorService loopExecutor;

    public SimulatorTrialOrchestrator(SimulatorTrialRepository trialRepository,
                                       EvalScenarioDraftRepository scenarioRepository,
                                       AgentRepository agentRepository,
                                       SessionService sessionService,
                                       EvalEngineFactory evalEngineFactory,
                                       SkillRegistry skillRegistry,
                                       SurfaceRegistry surfaceRegistry,
                                       SandboxSkillRegistryFactory sandboxFactory,
                                       SkillPackageLoader skillPackageLoader,
                                       EvalUserSimulatorProperties properties,
                                       @Qualifier("abEvalLoopExecutor") ExecutorService loopExecutor) {
        this.trialRepository = trialRepository;
        this.scenarioRepository = scenarioRepository;
        this.agentRepository = agentRepository;
        this.sessionService = sessionService;
        this.evalEngineFactory = evalEngineFactory;
        this.skillRegistry = skillRegistry;
        this.surfaceRegistry = surfaceRegistry;
        this.sandboxFactory = sandboxFactory;
        this.skillPackageLoader = skillPackageLoader;
        this.properties = properties;
        this.loopExecutor = loopExecutor;
    }

    /**
     * Run one trial end-to-end. Returns the persisted outcome.
     *
     * @param request validated trial config; null fields default per spec
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public SimulationOutcome runTrial(TrialRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.scenarioId(), "scenarioId");
        Objects.requireNonNull(request.persona(), "persona");

        // V5 known limitation: behavior_rule surface candidate inject can't take effect
        // without modifying AgentLoopEngine (core 7+1 Iron Law file). Reject early so
        // operator never gets a misleading "candidate trial" that actually ran baseline.
        // V5.1 backlog: thread BehaviorRuleRegistry override through AgentLoopEngine.
        if (UserSimAgentConstants.SURFACE_BEHAVIOR_RULE.equals(request.candidateSurfaceType())) {
            throw new IllegalArgumentException(
                    "behavior_rule dynamic sim 暂不支持 — V4 结构 limitation (AgentLoopEngine 是核心 7+1 "
                            + "不能改)，V5.1 backlog；仅 prompt + skill surface 当前可用");
        }

        // 1. Load scenario
        EvalScenarioEntity scenario = scenarioRepository.findById(request.scenarioId())
                .orElseThrow(() -> new IllegalArgumentException("Scenario not found: " + request.scenarioId()));

        // 2. Resolve candidate agent
        Long agentId;
        try {
            agentId = Long.parseLong(scenario.getAgentId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Scenario " + scenario.getId()
                    + " has non-numeric agentId='" + scenario.getAgentId() + "'");
        }
        AgentEntity candidateAgent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        AgentEntity userSimAgent = agentRepository.findFirstByName(UserSimAgentConstants.AGENT_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "user-simulator system agent not seeded (V85 migration not applied?)"));

        int maxTurns = request.maxTurns() != null && request.maxTurns() > 0
                ? request.maxTurns() : properties.getMaxTurns();

        // 3. Create candidate-side session (origin='user_sim') + trial row
        SessionEntity candidateSession = createCandidateSession(candidateAgent.getId(), scenario, request.persona());

        String trialId = UUID.randomUUID().toString();
        SimulatorTrialEntity trial = new SimulatorTrialEntity();
        trial.setTrialId(trialId);
        trial.setScenarioId(scenario.getId());
        trial.setCandidateAgentVersionId(request.candidateAgentVersionId());
        trial.setCandidateSurfaceType(request.candidateSurfaceType());
        trial.setPersona(request.persona());
        trial.setSessionId(candidateSession.getId());
        trial.setTurnsUsed(0);
        trialRepository.save(trial);

        // 4. Optional V4 surface inject (audit hook + per-surface candidate substitution).
        //    'prompt' → override candidateDef.systemPrompt.
        //    'skill'  → build sandbox SkillRegistry with candidate SkillDefinition + use it
        //               for candidate engine (V4 SkillAbEvalService.runMultiTurnScenario pattern).
        //    'behavior_rule' → rejected above (early).
        AgentDefinition candidateDef = toRunnableDefinition(candidateAgent);
        AgentDefinition userSimDef = toRunnableDefinition(userSimAgent);

        SkillRegistry candidateRegistry = skillRegistry;   // default: shared registry (baseline behavior)
        boolean usingSandbox = false;
        if (request.candidateAgentVersionId() != null && request.candidateSurfaceType() != null
                && !request.candidateSurfaceType().isBlank()) {
            try {
                SurfaceInjectResult injectResult = applyCandidateSurfaceInject(
                        candidateDef, candidateSession, agentId, request, trialId);
                if (injectResult.sandboxRegistry() != null) {
                    candidateRegistry = injectResult.sandboxRegistry();
                    usingSandbox = true;
                }
            } catch (IllegalArgumentException badArg) {
                // Bad input — surface candidate doesn't exist / surface unknown.
                throw badArg;
            } catch (Exception e) {
                log.warn("[SimulatorTrialOrchestrator] trial={} candidate surface inject failed ({} / {}): {}",
                        trialId, request.candidateSurfaceType(), request.candidateAgentVersionId(), e.getMessage());
                // Fall through to baseline registry (defensive — better to run a baseline
                // trial than crash; warn surfaces the issue).
            }
        }

        // 5. Compose UserSim kickoff message from scenario fields
        String kickoff = renderKickoffMessage(scenario, request.persona(), trialId, maxTurns);

        // 6. Run ping-pong loop. UserSim always uses the shared SkillRegistry; only the
        //    candidate side uses the (potentially sandboxed) candidateRegistry so candidate
        //    skill injections don't leak into UserSim's tool surface (RecordSimulationResult).
        SimulationOutcome outcome;
        try {
            outcome = runPingPongLoop(
                    trialId, scenario, candidateDef, userSimDef, candidateSession,
                    candidateRegistry, kickoff, request.persona(), maxTurns);
        } finally {
            // Always clean up sandbox dir (if any) — same as V4 SkillAbEvalService finally block.
            if (usingSandbox) {
                try {
                    sandboxFactory.cleanupSandbox(trialId, scenario.getId());
                } catch (Exception cleanupEx) {
                    log.warn("[SimulatorTrialOrchestrator] trial={} sandbox cleanup failed: {}",
                            trialId, cleanupEx.getMessage());
                }
            }
        }

        // 7. Persist final outcome (idempotent — RecordSimulationResult may already have written)
        SimulatorTrialEntity saved = trialRepository.findById(trialId)
                .orElse(trial);
        // Idempotent: if RecordSimulationResult already wrote a reason, keep it.
        if (saved.getTerminationReason() == null || saved.getTerminationReason().isBlank()) {
            saved.setTerminationReason(outcome.terminationReason());
        }
        if (saved.getTurnsUsed() == null || saved.getTurnsUsed() == 0) {
            saved.setTurnsUsed(outcome.turnsUsed());
        }
        if ((saved.getObservedFailureSignals() == null || saved.getObservedFailureSignals().isBlank())
                && outcome.observedFailureSignals() != null && !outcome.observedFailureSignals().isEmpty()) {
            saved.setObservedFailureSignals(String.join(",", outcome.observedFailureSignals()));
        }
        trialRepository.save(saved);

        return new SimulationOutcome(
                trialId,
                candidateSession.getId(),
                saved.getTurnsUsed(),
                saved.getTerminationReason(),
                outcome.observedFailureSignals());
    }

    /**
     * V4 OptimizableSurface candidate inject for the trial. Surface-specific dispatch:
     *
     * <ul>
     *   <li>{@code prompt} — mutate {@code candidateDef.systemPrompt} so engine.run
     *       sees the candidate text instead of baseline.</li>
     *   <li>{@code skill} — build a sandbox {@link SkillRegistry} via
     *       {@link SandboxSkillRegistryFactory#buildSandboxRegistryWithSkills} with
     *       the candidate {@link SkillDefinition} registered; caller swaps this
     *       registry into the candidate engine. Same mechanism V4
     *       {@code SkillAbEvalService.runMultiTurnScenario} uses.</li>
     *   <li>{@code behavior_rule} — rejected upstream in {@link #runTrial}; we
     *       never reach this branch.</li>
     * </ul>
     *
     * @return {@link SurfaceInjectResult} carrying an optional sandbox registry (only
     *         non-null for skill surface)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    SurfaceInjectResult applyCandidateSurfaceInject(AgentDefinition candidateDef,
                                                     SessionEntity candidateSession,
                                                     Long agentId,
                                                     TrialRequest request,
                                                     String trialId) {
        OptimizableSurface surface = surfaceRegistry.get(request.candidateSurfaceType());
        Object version = surface.loadVersion(request.candidateAgentVersionId());
        if (version == null) {
            throw new IllegalArgumentException("Candidate version not found: surface="
                    + request.candidateSurfaceType() + " versionId=" + request.candidateAgentVersionId());
        }
        // Always call injectForSandbox so the surface's session-keyed map gets the
        // candidate registration (audit + future query). Side effect only.
        SandboxContext ctx = new SandboxContext(agentId, candidateSession.getId(), null);
        surface.injectForSandbox(ctx, version);

        switch (request.candidateSurfaceType()) {
            case UserSimAgentConstants.SURFACE_PROMPT -> {
                applyPromptCandidate(candidateDef, version);
                return SurfaceInjectResult.promptOnly();
            }
            case UserSimAgentConstants.SURFACE_SKILL -> {
                SkillRegistry sandbox = buildSkillSandboxRegistry(trialId, request.scenarioId(), version);
                return SurfaceInjectResult.withSandbox(sandbox);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown candidateSurfaceType: " + request.candidateSurfaceType());
        }
    }

    /**
     * V4 prompt-surface candidate substitution. Reads {@code PromptVersionEntity.getContent()}
     * reflectively to keep this module decoupled from the prompt-versioning entity package
     * (avoids cross-package compile cycle through {@code skillforge.server.prompt}).
     */
    void applyPromptCandidate(AgentDefinition candidateDef, Object promptVersion) {
        try {
            Object content = promptVersion.getClass().getMethod("getContent").invoke(promptVersion);
            if (content instanceof String s && !s.isBlank()) {
                candidateDef.setSystemPrompt(s);
                log.info("[SimulatorTrialOrchestrator] prompt-surface candidate substituted ({} chars)", s.length());
                return;
            }
            log.warn("[SimulatorTrialOrchestrator] prompt-surface candidate getContent() returned non-string or blank — "
                    + "trial will run on baseline prompt");
        } catch (NoSuchMethodException nsme) {
            log.warn("[SimulatorTrialOrchestrator] prompt-surface candidate does not expose getContent() — "
                    + "version class={}", promptVersion.getClass().getName());
        } catch (Exception reflectionEx) {
            log.warn("[SimulatorTrialOrchestrator] prompt surface candidate substitution failed: {}",
                    reflectionEx.getMessage());
        }
    }

    /**
     * V4 SandboxSkillRegistryFactory pattern: build a sandboxed {@link SkillRegistry}
     * containing the candidate skill. Returned to caller as the engine's registry — engine
     * will resolve {@code tool_use} blocks against this registry instead of the shared
     * production one, so the candidate skill (not baseline) actually drives the trial.
     *
     * <p>Conversion {@code SkillEntity → SkillDefinition} mirrors
     * {@code SkillAbEvalService.buildSkillDefinition}: prefer package on disk (when
     * {@code skill_path} is set) so the full skill bundle (scripts, fixtures) reaches
     * the engine; otherwise fall back to metadata-only definition.
     */
    SkillRegistry buildSkillSandboxRegistry(String trialId, String scenarioId, Object skillVersion) {
        if (!(skillVersion instanceof SkillEntity skill)) {
            throw new IllegalArgumentException("Skill surface returned non-SkillEntity version: "
                    + (skillVersion == null ? "null" : skillVersion.getClass().getName()));
        }
        SkillDefinition def = toSkillDefinition(skill);
        try {
            SkillRegistry sandbox = sandboxFactory.buildSandboxRegistryWithSkills(
                    trialId, scenarioId, List.of(def));
            // Tools that UserSim + production agents normally have (e.g. RecordSimulationResult)
            // live in the shared SkillRegistry. The sandbox registry only carries sandboxed
            // file tools + the candidate skill — candidate engine's tool_use will resolve
            // against this narrower set, mirroring V4 SkillAbEvalService behavior.
            log.info("[SimulatorTrialOrchestrator] trial={} built skill sandbox registry with candidate skillId={} name={}",
                    trialId, skill.getId(), skill.getName());
            return sandbox;
        } catch (java.io.IOException ioe) {
            throw new RuntimeException("Failed to build skill sandbox registry: " + ioe.getMessage(), ioe);
        }
    }

    private SkillDefinition toSkillDefinition(SkillEntity skill) {
        if (skill.getSkillPath() != null) {
            try {
                return skillPackageLoader.loadFromDirectory(Path.of(skill.getSkillPath()));
            } catch (java.io.IOException e) {
                log.warn("[SimulatorTrialOrchestrator] failed to load skill package from {}, falling back to metadata: {}",
                        skill.getSkillPath(), e.getMessage());
            }
        }
        SkillDefinition def = new SkillDefinition();
        def.setId(String.valueOf(skill.getId()));
        def.setName(skill.getName());
        def.setDescription(skill.getDescription());
        StringBuilder prompt = new StringBuilder();
        prompt.append("# ").append(skill.getName()).append("\n\n");
        if (skill.getDescription() != null) {
            prompt.append(skill.getDescription()).append("\n\n");
        }
        if (skill.getTriggers() != null) {
            prompt.append("**Use when:** ").append(skill.getTriggers()).append("\n\n");
        }
        if (skill.getRequiredTools() != null) {
            prompt.append("**Required tools:** ").append(skill.getRequiredTools()).append("\n");
        }
        def.setPromptContent(prompt.toString());
        if (skill.getTriggers() != null) {
            def.setTriggers(Arrays.asList(skill.getTriggers().split(",")));
        }
        if (skill.getRequiredTools() != null) {
            def.setRequiredTools(Arrays.asList(skill.getRequiredTools().split(",")));
        }
        return def;
    }

    private SimulationOutcome runPingPongLoop(String trialId, EvalScenarioEntity scenario,
                                               AgentDefinition candidateDef, AgentDefinition userSimDef,
                                               SessionEntity candidateSession,
                                               SkillRegistry candidateRegistry,
                                               String kickoff, String persona, int maxTurns) {
        // Two engines so candidate-side surface inject (skill sandbox) doesn't leak into
        // UserSim's tool surface. UserSim always uses the shared registry (has access to
        // RecordSimulationResult); candidate uses the potentially-sandboxed registry.
        AgentLoopEngine userSimEngine = evalEngineFactory.buildEvalEngine(skillRegistry);
        AgentLoopEngine candidateEngine = evalEngineFactory.buildEvalEngine(candidateRegistry);
        long startMs = System.currentTimeMillis();
        long budgetMs = properties.getTrialBudgetMs();
        long turnTimeoutMs = properties.getTurnTimeoutMs();

        List<Message> userSimHistory = new ArrayList<>();
        List<Message> candidateHistory = new ArrayList<>();
        List<String> observedSignals = new ArrayList<>();

        String pendingUserText = kickoff;          // first UserSim invocation receives kickoff as "userMessage"
        int turnsUsed = 0;
        String terminationReason = REASON_MAX_TURNS;

        for (int turn = 0; turn < maxTurns; turn++) {
            long remaining = budgetMs - (System.currentTimeMillis() - startMs);
            if (remaining <= 5_000L) {
                log.warn("[SimulatorTrialOrchestrator] trial={} budget exhausted at turn {}", trialId, turn);
                terminationReason = REASON_TIMEOUT;
                break;
            }

            // ── UserSim turn ──────────────────────────────────────────────
            LoopResult userSimResult;
            try {
                userSimResult = runOneTurn(userSimEngine, userSimDef, pendingUserText,
                        new ArrayList<>(userSimHistory),
                        "user-sim-" + trialId, Math.min(turnTimeoutMs, remaining));
            } catch (TimeoutException e) {
                log.warn("[SimulatorTrialOrchestrator] trial={} UserSim turn {} timeout", trialId, turn);
                terminationReason = REASON_TIMEOUT;
                break;
            } catch (Exception e) {
                log.error("[SimulatorTrialOrchestrator] trial={} UserSim turn {} failed", trialId, turn, e);
                terminationReason = REASON_ERROR;
                break;
            }

            String userSimText = userSimResult.getFinalResponse() != null ? userSimResult.getFinalResponse() : "";
            userSimHistory.add(Message.user(pendingUserText));
            userSimHistory.add(Message.assistant(userSimText));

            // Detect termination: marker + RecordSimulationResult tool call
            boolean hasTerminateMarker = userSimText.contains(TERMINATE_MARKER);
            ToolCallRecord recordCall = findRecordSimulationCall(userSimResult.getToolCalls());
            if (hasTerminateMarker || recordCall != null) {
                if (recordCall != null) {
                    terminationReason = extractReasonFromToolCall(recordCall);
                    extractObservedSignals(recordCall, observedSignals);
                } else {
                    // marker present but no tool call → inspect text for hint
                    terminationReason = inferReasonFromText(userSimText);
                }
                turnsUsed = turn + 1;
                break;
            }

            // ── Candidate turn ───────────────────────────────────────────
            String userTextForCandidate = stripTerminate(userSimText);
            if (userTextForCandidate.isBlank()) {
                userTextForCandidate = "(用户没说话)";   // defensive: don't pass empty to engine
            }

            long remaining2 = budgetMs - (System.currentTimeMillis() - startMs);
            if (remaining2 <= 5_000L) {
                terminationReason = REASON_TIMEOUT;
                turnsUsed = turn + 1;
                break;
            }

            LoopResult candidateResult;
            try {
                candidateResult = runOneTurn(candidateEngine, candidateDef, userTextForCandidate,
                        new ArrayList<>(candidateHistory),
                        candidateSession.getId(), Math.min(turnTimeoutMs, remaining2));
            } catch (TimeoutException e) {
                log.warn("[SimulatorTrialOrchestrator] trial={} candidate turn {} timeout", trialId, turn);
                terminationReason = REASON_TIMEOUT;
                turnsUsed = turn + 1;
                break;
            } catch (Exception e) {
                log.error("[SimulatorTrialOrchestrator] trial={} candidate turn {} failed", trialId, turn, e);
                terminationReason = REASON_ERROR;
                turnsUsed = turn + 1;
                break;
            }

            String candidateText = candidateResult.getFinalResponse() != null ? candidateResult.getFinalResponse() : "";
            // Persist candidate turn into candidateSession (origin='user_sim'). One row per
            // exchange — minimal persistence, V4 SkillAbEvalService equivalent.
            try {
                sessionService.appendNormalMessages(candidateSession.getId(),
                        List.of(Message.user(userTextForCandidate), Message.assistant(candidateText)));
            } catch (Exception persistEx) {
                log.warn("[SimulatorTrialOrchestrator] trial={} persist turn {} failed: {}",
                        trialId, turn, persistEx.getMessage());
            }

            candidateHistory.add(Message.user(userTextForCandidate));
            candidateHistory.add(Message.assistant(candidateText));

            // Feed candidate response back as next UserSim "user" message
            pendingUserText = candidateText.isBlank() ? "(agent 没回复)" : candidateText;
            turnsUsed = turn + 1;
        }

        if (turnsUsed == maxTurns && REASON_MAX_TURNS.equals(terminationReason)) {
            observedSignals.add("达到 max_turns=" + maxTurns + " 但未触发明确成功 / 失败");
        }

        return new SimulationOutcome(trialId, candidateSession.getId(), turnsUsed,
                terminationReason, observedSignals);
    }

    private LoopResult runOneTurn(AgentLoopEngine engine, AgentDefinition def, String userMessage,
                                   List<Message> history, String sessionId, long timeoutMs)
            throws Exception {
        LoopContext ctx = new LoopContext();
        ctx.setMaxLoops(8);   // per-turn inner loop cap (one user message turn shouldn't need many iter)
        ctx.setExecutionMode("auto");
        ctx.setMaxLlmStreamTimeoutMs(20_000L);

        Future<LoopResult> future = loopExecutor.submit(
                () -> engine.run(def, userMessage, history, sessionId, null, ctx));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutEx) {
            future.cancel(true);
            throw timeoutEx;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw ie;
        }
    }

    /**
     * Build a candidate {@link SessionEntity} stamped with {@code origin='user_sim'}.
     * Calls {@code SessionService.createSession} then sets the origin + saves. Mirrors
     * the V4 EvalOrchestrator's "create session + flip origin" pattern.
     */
    private SessionEntity createCandidateSession(Long agentId, EvalScenarioEntity scenario, String persona) {
        Long ownerId = 1L;   // system agent owner
        SessionEntity session = sessionService.createSession(ownerId, agentId, scenario.getId());
        session.setOrigin("user_sim");
        session.setTitle("UserSim Trial — " + truncate(scenario.getName(), 80)
                + " — " + truncate(persona, 40));
        return sessionService.saveSession(session);
    }

    private AgentDefinition toRunnableDefinition(AgentEntity entity) {
        // Defensive: build a minimal AgentDefinition without invoking the full
        // AgentService.toAgentDefinition (which may resolve behavior rules and
        // do other server-side enrichment we don't strictly need here).
        AgentDefinition def = new AgentDefinition();
        def.setId(entity.getId() == null ? null : String.valueOf(entity.getId()));
        def.setName(entity.getName());
        def.setDescription(entity.getDescription());
        def.setSystemPrompt(entity.getSystemPrompt());
        def.setModelId(entity.getModelId());
        if (def.getConfig() == null) {
            def.setConfig(new HashMap<>());
        }
        return def;
    }

    private String renderKickoffMessage(EvalScenarioEntity scenario, String persona, String trialId, int maxTurns) {
        StringBuilder sb = new StringBuilder();
        sb.append("trialId: ").append(trialId).append("\n");
        sb.append("persona: ").append(persona).append("\n");
        sb.append("businessGoal: ").append(safe(scenario.getBusinessGoal())).append("\n");
        sb.append("successCriteria: ").append(safe(scenario.getSuccessCriteria())).append("\n");
        sb.append("userConstraints: ").append(safe(scenario.getUserConstraints())).append("\n");
        sb.append("failureSignals: ").append(safe(scenario.getFailureSignals())).append("\n");
        sb.append("max_turns: ").append(maxTurns).append("\n\n");
        sb.append("开始对话。按 persona 性格生成第一条用户输入对 AI agent 说。");
        sb.append("达成 / 失败 / 超 max_turns 时调 RecordSimulationResult 工具后输出 ")
                .append(TERMINATE_MARKER).append("。");
        return sb.toString();
    }

    private ToolCallRecord findRecordSimulationCall(List<ToolCallRecord> toolCalls) {
        if (toolCalls == null) return null;
        for (ToolCallRecord tc : toolCalls) {
            if ("RecordSimulationResult".equalsIgnoreCase(tc.getSkillName())) {
                return tc;
            }
        }
        return null;
    }

    private String extractReasonFromToolCall(ToolCallRecord call) {
        if (call == null || call.getInput() == null) return REASON_TASK_COMPLETED;
        Object r = call.getInput().get("terminationReason");
        if (r instanceof String s && !s.isBlank()) return s;
        return REASON_TASK_COMPLETED;
    }

    @SuppressWarnings("unchecked")
    private void extractObservedSignals(ToolCallRecord call, List<String> out) {
        if (call == null || call.getInput() == null) return;
        Object raw = call.getInput().get("observedFailureSignals");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) out.add(o.toString());
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            out.add(s);
        }
    }

    /** Fallback when UserSim outputs [TERMINATE] without calling the tool. */
    private String inferReasonFromText(String text) {
        if (text == null) return REASON_TASK_COMPLETED;
        String lower = text.toLowerCase();
        if (lower.contains("failure") || lower.contains("放弃") || lower.contains("不满意")) {
            return REASON_FAILURE_SIGNAL;
        }
        if (lower.contains("max_turns") || lower.contains("超时") || lower.contains("循环")) {
            return REASON_MAX_TURNS;
        }
        return REASON_TASK_COMPLETED;
    }

    private String stripTerminate(String text) {
        return text == null ? "" : text.replace(TERMINATE_MARKER, "").trim();
    }

    private String safe(String s) {
        return s == null ? "(未指定)" : s;
    }

    private String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DTO records
    // ─────────────────────────────────────────────────────────────────────────

    public record TrialRequest(String scenarioId, String candidateAgentVersionId,
                                String candidateSurfaceType, String persona, Integer maxTurns) { }

    public record SimulationOutcome(String trialId, String sessionId, int turnsUsed,
                                     String terminationReason, List<String> observedFailureSignals) { }

    /**
     * Result of {@link #applyCandidateSurfaceInject}: signals whether the caller should
     * swap the candidate engine to a sandboxed registry (skill surface) or stick with the
     * shared registry (prompt surface — candidate change rides on {@code candidateDef}).
     */
    record SurfaceInjectResult(SkillRegistry sandboxRegistry) {
        static SurfaceInjectResult promptOnly() { return new SurfaceInjectResult(null); }
        static SurfaceInjectResult withSandbox(SkillRegistry registry) {
            Objects.requireNonNull(registry, "sandboxRegistry");
            return new SurfaceInjectResult(registry);
        }
    }
}
