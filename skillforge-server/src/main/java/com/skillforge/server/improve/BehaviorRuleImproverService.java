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
                    baselineRulesJson, attributedDescription);
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
                        + "eventId={} ownerId={} versionNumber={} contentLen={} "
                        + "(BYPASSING checkEligibility per V3 ratify)",
                version.getId(), agentId, eventId, ownerId, nextVersion,
                version.getRulesJson() != null ? version.getRulesJson().length() : 0);

        // abRunId left null — Phase 1.2 wires the A/B trigger.
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
        String systemPrompt = """
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

        String userMessage = String.format("""
                Current rules JSON:
                ---
                %s
                ---

                Attribution rationale (from curator):
                %s

                Produce the improved rules JSON.""",
                baselineRulesJson,
                attributedDescription.trim());

        LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
        if (provider == null) {
            throw new RuntimeException("No LLM provider available for behavior_rule generation");
        }

        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(systemPrompt);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userMessage));
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
}
