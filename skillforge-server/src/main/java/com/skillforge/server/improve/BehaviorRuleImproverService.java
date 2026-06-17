package com.skillforge.server.improve;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — attribution-aware behavior_rule
 * candidate generator. Direct clone of the V3.1
 * {@code PromptImproverService.startImprovementFromAttribution} pattern
 * (commit {@code 91c3108}) — same REQUIRES_NEW tx isolation, same synchronous
 * LLM fill, same audit-trail fallback on LLM failure.
 *
 * <p>5-ratify #5 (2026-05-15): LLM provider is {@code defaultProvider}
 * (consistent with V3 PromptImproverService — no per-agent {@code llm_model}
 * dispatch yet).
 *
 * <p>Phase 1.1 scope: produces a {@link BehaviorRuleVersionEntity}
 * ({@code status=candidate}, {@code source='attribution'},
 * {@code improvementRationale} set, {@code rulesJson} populated from LLM).
 * Does NOT trigger an A/B run — Phase 1.2's
 * {@code AbstractAbEvalRunner<BehaviorRuleVersionEntity>} wires that.
 *
 * <p>BYPASSES {@code checkEligibility} (agent {@code lastPromotedAt} 24h
 * cooldown) on purpose — V3 already enforces a 24h pattern-level cooldown via
 * {@code t_optimization_event.cooldown_expires_at}; layering both would
 * double-gate. Same ratify rationale as PromptImproverService's attribution
 * path.
 */
@Service
public class BehaviorRuleImproverService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorRuleImproverService.class);

    private final AgentRepository agentRepository;
    private final BehaviorRuleVersionRepository versionRepository;
    private final LlmProviderFactory llmProviderFactory;
    private final String defaultProviderName;

    public BehaviorRuleImproverService(AgentRepository agentRepository,
                                        BehaviorRuleVersionRepository versionRepository,
                                        LlmProviderFactory llmProviderFactory,
                                        LlmProperties llmProperties) {
        this.agentRepository = agentRepository;
        this.versionRepository = versionRepository;
        this.llmProviderFactory = llmProviderFactory;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
    }

    /**
     * Build + persist a candidate behavior_rule version from a curator's
     * attribution rationale.
     *
     * <p>REQUIRES_NEW tx isolation (same lesson as
     * PromptImproverService.startImprovementFromAttribution / V2 W2):
     * {@code AttributionApprovalService.approve} runs in {@code REQUIRED}
     * and catches RuntimeException to write {@code stage=candidate_failed}.
     * If we joined that outer tx, any version-write failure would mark the
     * outer tx setRollbackOnly → approve's candidate_failed save() would
     * still commit but the outer rolls back → operator never sees the
     * failure row. REQUIRES_NEW gives us an independent tx so failures
     * surface in the dashboard.
     *
     * @param eventId               source optimization event id (logged for audit)
     * @param agentId               target agent id (string per V82 column)
     * @param attributedDescription curator's 1-3 sentence rationale
     * @param ownerId               approver user id (logged for audit)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImprovementStartResult startImprovementFromAttribution(Long eventId,
                                                                  String agentId,
                                                                  String attributedDescription,
                                                                  Long ownerId) {
        // Non-evolve / legacy callers: delegate with editor=null → byte-identical.
        return startImprovementFromAttribution(eventId, agentId, attributedDescription, ownerId, null);
    }

    /**
     * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 3 (§9 line A #1) — reflection-aware overload
     * of {@link #startImprovementFromAttribution}. {@code editor == null} reproduces
     * the legacy behavior exactly (byte-identical candidate generation); a non-null
     * {@code editor} switches the candidate-rule LLM call to evolve-editor mode.
     *
     * <p><b>Why this overload ALSO carries {@code @Transactional(REQUIRES_NEW)}</b>
     * (Phase 3 review W1): it is itself an EXTERNAL entry point — {@code
     * GenerateCandidateTool} calls THIS editor overload directly on the reflection
     * path. So it must be proxied to get its own REQUIRES_NEW tx. The no-editor
     * entry point's {@code this.}-delegation into here is a self-invocation whose
     * REQUIRES_NEW is harmlessly a no-op (it simply runs inside the outer entry
     * point's already-new tx). Do NOT remove {@code @Transactional} from this
     * overload, or external editor-path calls would lose tx isolation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImprovementStartResult startImprovementFromAttribution(Long eventId,
                                                                  String agentId,
                                                                  String attributedDescription,
                                                                  Long ownerId,
                                                                  EvolveEditorContext editor) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (attributedDescription == null || attributedDescription.isBlank()) {
            throw new IllegalArgumentException("attributedDescription is required and must be non-blank");
        }

        AgentEntity agent = agentRepository.findById(Long.parseLong(agentId))
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

        // Load current active baseline (may be null — first attribution for an
        // agent has no DB-backed baseline; fall back to "[]" so the LLM sees
        // an empty starting point and can synthesize from scratch).
        BehaviorRuleVersionEntity baseline = versionRepository
                .findByAgentIdAndStatus(agentId, BehaviorRuleVersionEntity.STATUS_ACTIVE)
                .orElse(null);
        String baselineRulesJson = baseline != null ? baseline.getRulesJson() : "[]";

        int nextVersion = versionRepository.findMaxVersionNumber(agentId).orElse(0) + 1;

        BehaviorRuleVersionEntity version = new BehaviorRuleVersionEntity();
        version.setId(UUID.randomUUID().toString());
        version.setAgentId(agentId);
        version.setVersionNumber(nextVersion);
        version.setStatus(BehaviorRuleVersionEntity.STATUS_CANDIDATE);
        version.setSource(BehaviorRuleVersionEntity.SOURCE_ATTRIBUTION);
        version.setSourceEventId(eventId);
        if (baseline != null) {
            version.setBaselineVersionId(baseline.getId());
        }
        version.setImprovementRationale(attributedDescription.trim());

        // V3.1-style synchronous LLM fill: if LLM fails, persist row with
        // rulesJson="[]" + log.error + rethrow so caller (AttributionApprovalService)
        // catches and writes stage=candidate_failed. Without saving the row
        // first we'd lose the audit-trail eventId link.
        try {
            String improvedRules = generateCandidateRulesFromAttribution(
                    baselineRulesJson, attributedDescription, agent, editor);
            version.setRulesJson(improvedRules);
        } catch (RuntimeException llmEx) {
            version.setRulesJson("[]");
            versionRepository.save(version);
            log.error("Attribution behavior-rule LLM fill FAILED: versionId={} agentId={} eventId={}: {}",
                    version.getId(), agentId, eventId, llmEx.getMessage());
            throw llmEx;
        }

        versionRepository.save(version);

        log.info("Attribution-derived behavior_rule version created: versionId={} agentId={} "
                        + "eventId={} ownerId={} versionNumber={} contentLen={} evolveEditor={} "
                        + "(BYPASSING checkEligibility per V3 ratify)",
                version.getId(), agentId, eventId, ownerId, nextVersion,
                version.getRulesJson() != null ? version.getRulesJson().length() : 0, editor != null);

        // abRunId left null — Phase 1.2 wires the A/B trigger.
        return new ImprovementStartResult(agentId, null, version.getId(), "PENDING");
    }

    /**
     * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 2 (§8 #1) — hill-climb carry-forward
     * sibling of {@link #startImprovementFromAttribution}. Instead of improving the
     * agent's CURRENT ACTIVE baseline, it improves the SPECIFIED {@code baseVersionId}
     * (the current-best behavior_rule version from a prior winning bundle), so the
     * evolve loop can build the next rule candidate on top of the running best.
     *
     * <p>Differences from {@link #startImprovementFromAttribution}:
     * <ul>
     *   <li>baseline rulesJson comes from {@code baseVersionId} (loaded by id,
     *       ownership-checked), not from {@code findByAgentIdAndStatus(ACTIVE)}.</li>
     *   <li>the new candidate's {@code baselineVersionId} is set to
     *       {@code baseVersionId}.</li>
     * </ul>
     * The reflection (priorChange / priorEvalReport) editor overload is Phase 3.
     *
     * @throws IllegalArgumentException baseVersionId unknown, or it belongs to a
     *                                  different agent than {@code agentId}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImprovementStartResult startImprovementFromBaseVersion(Long eventId,
                                                                  String agentId,
                                                                  String baseVersionId,
                                                                  String attributedDescription,
                                                                  Long ownerId) {
        // Delegate with editor=null → byte-identical to the Phase 2 behavior.
        return startImprovementFromBaseVersion(
                eventId, agentId, baseVersionId, attributedDescription, ownerId, null);
    }

    /**
     * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 3 (§9 line A #1) — reflection-aware overload
     * of {@link #startImprovementFromBaseVersion}. {@code editor == null} reproduces
     * the Phase 2 carry-forward behavior exactly; a non-null {@code editor} switches
     * the candidate-rule LLM call to evolve-editor mode (priorChange / priorEvalReport).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImprovementStartResult startImprovementFromBaseVersion(Long eventId,
                                                                  String agentId,
                                                                  String baseVersionId,
                                                                  String attributedDescription,
                                                                  Long ownerId,
                                                                  EvolveEditorContext editor) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (baseVersionId == null || baseVersionId.isBlank()) {
            throw new IllegalArgumentException("baseVersionId is required");
        }
        if (attributedDescription == null || attributedDescription.isBlank()) {
            throw new IllegalArgumentException("attributedDescription is required and must be non-blank");
        }

        AgentEntity agent = agentRepository.findById(Long.parseLong(agentId))
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

        // Load the SPECIFIED base version (content-by-id) and validate ownership —
        // a carry-forward must build on a version that belongs to this agent.
        BehaviorRuleVersionEntity base = versionRepository.findById(baseVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "baseVersionId not found: " + baseVersionId));
        if (!agentId.equals(base.getAgentId())) {
            throw new IllegalArgumentException(
                    "baseVersionId " + baseVersionId + " belongs to agent " + base.getAgentId()
                            + " but agentId=" + agentId + " — cannot carry forward another agent's version");
        }
        String baselineRulesJson = base.getRulesJson() != null ? base.getRulesJson() : "[]";

        int nextVersion = versionRepository.findMaxVersionNumber(agentId).orElse(0) + 1;

        BehaviorRuleVersionEntity version = new BehaviorRuleVersionEntity();
        version.setId(UUID.randomUUID().toString());
        version.setAgentId(agentId);
        version.setVersionNumber(nextVersion);
        version.setStatus(BehaviorRuleVersionEntity.STATUS_CANDIDATE);
        version.setSource(BehaviorRuleVersionEntity.SOURCE_ATTRIBUTION);
        version.setSourceEventId(eventId);
        version.setBaselineVersionId(baseVersionId);
        version.setImprovementRationale(attributedDescription.trim());

        try {
            String improvedRules = generateCandidateRulesFromAttribution(
                    baselineRulesJson, attributedDescription, agent, editor);
            version.setRulesJson(improvedRules);
        } catch (RuntimeException llmEx) {
            version.setRulesJson("[]");
            versionRepository.save(version);
            log.error("Carry-forward behavior-rule LLM fill FAILED: versionId={} agentId={} "
                            + "baseVersionId={} eventId={}: {}",
                    version.getId(), agentId, baseVersionId, eventId, llmEx.getMessage());
            throw llmEx;
        }

        versionRepository.save(version);

        log.info("Carry-forward behavior_rule version created: versionId={} agentId={} "
                        + "baseVersionId={} eventId={} ownerId={} versionNumber={} contentLen={} evolveEditor={}",
                version.getId(), agentId, baseVersionId, eventId, ownerId, nextVersion,
                version.getRulesJson() != null ? version.getRulesJson().length() : 0, editor != null);

        return new ImprovementStartResult(agentId, null, version.getId(), "PENDING");
    }

    /**
     * Build the LLM prompt + invoke the default provider to derive an improved
     * rules JSON from the baseline. System prompt mirrors
     * PromptImproverService.generateCandidatePromptFromAttribution's structure
     * (output ONLY the artifact, no commentary).
     *
     * <p>Public so the {@code surface.BehaviorRuleSurface} (which lives in
     * a sibling package) can reuse the same LLM-call shape from its generic
     * {@code createCandidate} path without duplicating the system prompt.
     * Unit tests in this same package also exercise it directly.
     *
     * <p>Phase 1.1 reviewer fix (W2): {@code AgentEntity} parameter removed
     * — the helper body does not read agent state today. Phase 1.2 may add
     * it back as a real input (e.g. injecting {@code agent.systemPrompt} into
     * the LLM system prompt for richer context); doing so now would be a
     * never-used arg + a YAGNI footgun in adapter call sites.
     */
    public String generateCandidateRulesFromAttribution(String baselineRulesJson,
                                                         String attributedDescription) {
        // Non-evolve / legacy callers (AttributionApprovalService, BehaviorRuleSurface):
        // delegate with agent=null + editor=null → BYTE-IDENTICAL to the original body.
        return generateCandidateRulesFromAttribution(baselineRulesJson, attributedDescription, null, null);
    }

    /**
     * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 3 (§9 line A #1) — reflection-aware
     * candidate-rule generator. Mirrors {@code PromptImproverService}'s
     * editor-threading shape for the behavior_rule surface.
     *
     * <p><b>{@code editor == null}</b> (the shared non-evolve attribution path):
     * the system prompt is the legacy hardcoded behavior-engineer prompt and the
     * user message is the legacy 2-block body — BYTE-IDENTICAL to the original
     * 2-arg method, so {@code AttributionApprovalService} / {@code BehaviorRuleSurface}
     * are unaffected. {@code agent} is unused in this branch.
     *
     * <p><b>{@code editor != null}</b> (evolve-editor mode): the system prompt is the
     * seeded {@code evolve-editor} agent's prompt (defensive fallback to legacy), and
     * reflection blocks (target-agent config + last round's change + last round's eval
     * report) are appended to the user message — "build on the last change / avoid
     * repeating regressions".
     */
    public String generateCandidateRulesFromAttribution(String baselineRulesJson,
                                                         String attributedDescription,
                                                         AgentEntity agent,
                                                         EvolveEditorContext editor) {
        final String legacySystemPrompt = """
                You are an expert agent-behavior engineer. Your task is to analyze an \
                attribution report from a curator agent and produce an improved \
                behavior_rule list.

                Input format:
                - Current rules JSON: a JSON array, each element {id, priority, when, \
                then, rationale}.
                - Attribution rationale: a free-form description of the failure pattern \
                the rules should address.

                Output format:
                - Output ONLY valid JSON — a JSON array matching the same schema.
                - No markdown fences, no commentary, no leading/trailing text.
                - Preserve existing rule semantics where they remain valid.
                - Add / refine rules to specifically address the attribution rationale.
                - Keep rule count reasonable (≤ 10 unless current count justifies more).
                """;
        String systemPrompt = legacySystemPrompt;
        if (editor != null) {
            systemPrompt = resolveEvolveEditorSystemPrompt(legacySystemPrompt);
        }

        StringBuilder userMessage = new StringBuilder(String.format("""
                Current rules JSON:
                ---
                %s
                ---

                Attribution rationale (from curator):
                %s

                Produce the improved rules JSON.""",
                baselineRulesJson,
                attributedDescription.trim()));

        if (editor != null) {
            userMessage.append("\n\n").append(buildEvolveEditorReflectionBlocks(agent, editor));
        }

        LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
        if (provider == null) {
            throw new RuntimeException("No LLM provider available for behavior_rule generation");
        }

        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(systemPrompt);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userMessage.toString()));
        request.setMessages(messages);
        request.setMaxTokens(2000);
        request.setTemperature(0.3);

        LlmResponse response = provider.chat(request);
        String content = response.getContent();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("LLM returned empty candidate rules JSON for attribution flow");
        }
        return content.trim();
    }

    /**
     * Resolve the evolve-editor system prompt: prefer the seeded {@code evolve-editor}
     * agent's {@code system_prompt}; fall back to {@code fallback} when absent/blank
     * (defensive — a fresh install that hasn't seeded the editor still produces a
     * usable candidate). Mirrors {@code PromptImproverService.resolveEvolveEditorSystemPrompt}.
     */
    private String resolveEvolveEditorSystemPrompt(String fallback) {
        try {
            return agentRepository.findFirstByName("evolve-editor")
                    .map(AgentEntity::getSystemPrompt)
                    .filter(p -> p != null && !p.isBlank())
                    .orElse(fallback);
        } catch (RuntimeException e) {
            log.warn("evolve-editor agent lookup failed, falling back to legacy prompt: {}", e.getMessage());
            return fallback;
        }
    }

    /**
     * Build the evolve-editor reflection sections appended to the user message: the
     * target agent's current config (read-only; this surface only changes the rules)
     * + what was changed last round + last round's eval report. Stable Chinese labels
     * so a reviewer / test can assert their presence. Mirrors
     * {@code PromptImproverService.buildEvolveEditorReflectionBlocks} (label says
     * "本次只改 rules").
     */
    private String buildEvolveEditorReflectionBlocks(AgentEntity agent, EvolveEditorContext editor) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标 agent 当前配置（仅供参考，本次只改 rules）：\n")
                .append("- systemPrompt: ").append(blankToNone(agent == null ? null : agent.getSystemPrompt())).append('\n')
                .append("- skills: ").append(blankToNone(agent == null ? null : agent.getSkillIds())).append('\n')
                .append("- tools: ").append(blankToNone(agent == null ? null : agent.getToolIds())).append('\n')
                .append("- modelId: ").append(blankToNone(agent == null ? null : agent.getModelId()));

        String priorChange = editor.priorChangeSummary();
        if (priorChange != null && !priorChange.isBlank()) {
            sb.append("\n\n上一轮改动：\n").append(priorChange.trim());
        }

        String priorReport = editor.priorEvalReportJson();
        if (priorReport != null && !priorReport.isBlank()) {
            sb.append("\n\n上一轮评测报告（哪些 case 提升/腐化 + 原因 + 整体涨跌）：\n")
                    .append(priorReport.trim());
        }

        sb.append("\n\n综合上述（方向 + 当前配置 + 上轮改动 + 上轮评测）给出本次更好的 rules。");
        return sb.toString();
    }

    private static String blankToNone(String value) {
        return (value == null || value.isBlank()) ? "(none)" : value.trim();
    }
}
