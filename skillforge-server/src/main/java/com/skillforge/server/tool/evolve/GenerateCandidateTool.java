package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.BehaviorRuleImproverService;
import com.skillforge.server.improve.EvolveEditorContext;
import com.skillforge.server.improve.ImprovementStartResult;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.optreport.OptReportToEventBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C2) — agent-callable thin wrapper that
 * generates an improvement candidate for one of the three optimisation surfaces
 * by delegating to the EXISTING improver services. This tool does NOT
 * re-implement the LLM-fill: it routes to the same candidate-generation path
 * {@code AttributionApprovalService} uses, threading the orchestrator-supplied
 * {@code targetAgentId}, and returns the persisted {@code candidateId}.
 *
 * <p><b>Surface routing</b> (mirrors {@code AttributionApprovalService}'s
 * dispatch methods):
 * <ul>
 *   <li>{@code prompt} → {@link PromptImproverService#startImprovementFromAttribution}
 *       ({@code candidateId} = the new prompt version id)</li>
 *   <li>{@code skill} → {@link SkillDraftService#createDraftFromAttribution}
 *       ({@code candidateId} = the new skill draft id)</li>
 *   <li>{@code behavior_rule} → {@link BehaviorRuleImproverService#startImprovementFromAttribution}
 *       ({@code candidateId} = the new behavior-rule version id)</li>
 * </ul>
 *
 * <p><b>eventId linkage — two input modes.</b> The existing improver
 * candidate-gen methods persist the candidate with a non-null {@code eventId}
 * (the {@code source_event_id} audit-trail column — NOT a hard DB FK; it is a
 * service-level validation + an audit pivot string). The orchestrator does NOT
 * normally pre-create opt-events, so this tool accepts EITHER:
 * <ul>
 *   <li><b>report-issue mode (preferred)</b> — {@code reportId} +
 *       {@code issueId} from the opt-report {@code topIssues} (read via
 *       {@code GetOptReport}). The tool mints the {@code eventId} by delegating
 *       to the EXISTING {@link OptReportToEventBridge#convertIssueToEvent}
 *       (idempotent on {@code (reportId, issueId)}, audit back-link via
 *       {@code source_report_id} / {@code source_issue_id}). No new
 *       event-creation logic is written here.</li>
 *   <li><b>direct mode (backward-compat)</b> — an explicit {@code eventId}
 *       (e.g. a curator-authored event). Threaded straight through.</li>
 * </ul>
 * Exactly one mode must be supplied; when neither is present the tool returns a
 * clean validation error. {@code sessionId} is intentionally NOT a substitute:
 * the audit column is a {@code Long} event id, and the report issue already
 * carries its {@code exampleSessionIds} evidence, so the bridge (report→event)
 * is the aligned anchor.
 *
 * <p><b>ownerId / patternId defaults.</b> The evolve loop is system-driven, so
 * {@code ownerId} defaults to {@code SYSTEM_USER_ID} (0) when the orchestrator
 * omits it. For {@code surface=skill}, the existing
 * {@code createDraftFromAttribution} requires a {@code patternId} — but it is
 * used ONLY for the audit-rationale text + suggested name (not a hard FK), and
 * report-derived events carry a null patternId, so this tool synthesises
 * {@code patternId = eventId} when absent.
 *
 * <p><b>Recursion guard (invariant).</b> Registered ONLY in the main
 * {@code SkillRegistry} (see {@code SkillForgeConfig}); deliberately ABSENT from
 * {@code WorkflowSkillRegistryFactory} (the workflow sub-agent registry) — same
 * isolation invariant as the Module A/B tools. The orchestrator runs top-level.
 */
public class GenerateCandidateTool implements Tool {

    public static final String NAME = "GenerateCandidate";

    /** System owner for system-driven evolve candidates (mirrors OptReportService.SYSTEM_USER_ID). */
    static final long SYSTEM_USER_ID = 0L;

    private static final Logger log = LoggerFactory.getLogger(GenerateCandidateTool.class);

    private final PromptImproverService promptImproverService;
    private final SkillDraftService skillDraftService;
    private final BehaviorRuleImproverService behaviorRuleImproverService;
    private final OptReportToEventBridge optReportToEventBridge;
    private final FlywheelRunRepository flywheelRunRepository;
    private final ObjectMapper objectMapper;

    public GenerateCandidateTool(PromptImproverService promptImproverService,
                                 SkillDraftService skillDraftService,
                                 BehaviorRuleImproverService behaviorRuleImproverService,
                                 OptReportToEventBridge optReportToEventBridge,
                                 FlywheelRunRepository flywheelRunRepository,
                                 ObjectMapper objectMapper) {
        this.promptImproverService = promptImproverService;
        this.skillDraftService = skillDraftService;
        this.behaviorRuleImproverService = behaviorRuleImproverService;
        this.optReportToEventBridge = optReportToEventBridge;
        this.flywheelRunRepository = flywheelRunRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Generate an improvement candidate for the target agent on one optimisation "
                + "surface, reusing the existing improver service (one-shot LLM fill). Inputs:\n"
                + "- \"surface\": one of \"prompt\", \"skill\", \"behavior_rule\".\n"
                + "- \"issue\": the issue / failure pattern description (from the opt-report "
                + "attribution report) the candidate should address (text or JSON).\n"
                + "- \"targetAgentId\": the agent being evolved (numeric agent id).\n"
                + "Provide ONE of these two audit-anchor modes:\n"
                + "- PREFERRED: \"reportId\" + \"issueId\" — the report id and the topIssues[].id "
                + "from GetOptReport; the tool mints the audit event from the report issue.\n"
                + "- OR: \"eventId\" — an explicit pre-existing optimization-event id.\n"
                + "Optional:\n"
                + "- \"ownerId\": owner user id (defaults to the system user when omitted).\n"
                + "- \"patternId\" (skill only): originating pattern id (defaults to the event id).\n"
                + "Returns a \"candidateId\" (prompt version id / skill draft id / behavior-rule "
                + "version id) you then pass to TriggerAbEval.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("surface", Map.of(
                "type", "string",
                // No "agent" here (§7 B2): you don't generate a whole-agent candidate —
                // the orchestrator generates per-surface and composes a bundle itself.
                "enum", EvolveSurface.v1NonAgentWireValues(),
                "description", "Optimisation surface: \"prompt\", \"skill\", or \"behavior_rule\"."
        ));
        properties.put("issue", Map.of(
                "type", "string",
                "description", "The issue / failure pattern description (text or JSON) the "
                        + "candidate should address."
        ));
        properties.put("targetAgentId", Map.of(
                "type", "string",
                "description", "The agent being evolved (numeric agent id)."
        ));
        properties.put("reportId", Map.of(
                "type", "string",
                "description", "Opt-report id (from GetOptReport). Provide with issueId as the "
                        + "preferred audit anchor; the tool mints the event from the report issue."
        ));
        properties.put("issueId", Map.of(
                "type", "string",
                "description", "topIssues[].id from GetOptReport. Required when using reportId."
        ));
        properties.put("eventId", Map.of(
                "type", "string",
                "description", "Explicit pre-existing optimization-event id (alternative to "
                        + "reportId+issueId)."
        ));
        properties.put("patternId", Map.of(
                "type", "string",
                "description", "Originating pattern id (skill only; defaults to the event id)."
        ));
        properties.put("ownerId", Map.of(
                "type", "string",
                "description", "Owner user id (defaults to the system user when omitted)."
        ));
        properties.put("basePromptVersionId", Map.of(
                "type", "string",
                "description", "surface=prompt hill-climb only: build the candidate by improving "
                        + "THIS prompt version (the current-best from a prior winning iteration) "
                        + "instead of the agent's active prompt. Omit on iteration 1."
        ));
        properties.put("baseVersionId", Map.of(
                "type", "string",
                "description", "surface=behavior_rule hill-climb only: build the candidate by "
                        + "improving THIS behavior_rule version (the current-best from a prior "
                        + "winning iteration) instead of the agent's active rules. Omit on iteration 1."
        ));
        properties.put("priorChange", Map.of(
                "type", "string",
                "description", "surface=prompt reflection (evolve only): what was changed LAST "
                        + "round (the prior iteration's changeDesc). Omit on the first round; the "
                        + "editor uses it to avoid repeating / to build on the last change."
        ));
        properties.put("priorEvalReport", Map.of(
                "type", "string",
                "description", "surface=prompt reflection (evolve only): last round's eval report "
                        + "(per-case improved/regressed + reasons + overall delta), compact JSON or "
                        + "prose. Omit on the first round; the editor uses it to treat regressed "
                        + "cases as negative examples and keep improved directions."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("surface", "issue", "targetAgentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError(
                        "input is required (surface, issue, targetAgentId, eventId)");
            }
            EvolveSurface surface = EvolveSurface.fromWire(trimToNull(input.get("surface")));
            if (surface == null) {
                return SkillResult.validationError(
                        "surface is required and must be one of: " + EvolveSurface.acceptedValues());
            }
            String issue = trimToNull(input.get("issue"));
            if (issue == null) {
                return SkillResult.validationError("issue is required");
            }
            String targetAgentId = trimToNull(input.get("targetAgentId"));
            if (targetAgentId == null) {
                return SkillResult.validationError("targetAgentId is required");
            }

            // AUTOEVOLVE-AGENT-LEVEL-BUNDLE (§2.4 / §7 B2): there is no whole-agent
            // generation — the orchestrator generates per-surface candidates and
            // composes a bundle itself, then passes it to TriggerAbEval(surface=agent).
            // Reject cleanly rather than pretending to generate an "agent candidate".
            if (surface == EvolveSurface.AGENT) {
                return SkillResult.validationError(
                        "surface=agent is not supported by GenerateCandidate: generate per-surface "
                                + "candidates (prompt / skill / behavior_rule) and compose them into a "
                                + "bundle, then call TriggerAbEval(surface=agent) with that bundle");
            }

            // Resolve the audit-anchor eventId from ONE of two modes:
            //   direct mode      → explicit "eventId"
            //   report-issue mode → "reportId" + "issueId" → existing bridge mints it
            Long eventId = resolveEventId(input, targetAgentId);

            // SECURITY note: targetAgentId is threaded to the improver service,
            // which validates the agent exists. The improvers persist the
            // candidate against that agent, so the candidate is owned by it by
            // construction (TriggerAbEval / PromoteCandidate re-validate ownership).
            String basePromptVersionId = trimToNull(input.get("basePromptVersionId"));
            // Reflection (evolve only): build an EvolveEditorContext when EITHER
            // priorChange or priorEvalReport is present. iter-1 omits both → null
            // context → byte-identical legacy generation. Blanks trimmed to null
            // so a stray empty string doesn't switch on evolve-editor mode.
            String priorChange = trimToNull(input.get("priorChange"));
            String priorEvalReport = trimToNull(input.get("priorEvalReport"));
            EvolveEditorContext editor = (priorChange != null || priorEvalReport != null)
                    ? new EvolveEditorContext(priorChange, priorEvalReport)
                    : null;
            String candidateId = switch (surface) {
                case PROMPT -> {
                    // BUG-1 hill-climb: when basePromptVersionId is supplied (iter 2+),
                    // build the candidate on the current-best prompt; else (iter 1)
                    // improve the agent's active prompt. Reflection context reaches
                    // BOTH routes (the "best is still original" case omits
                    // basePromptVersionId yet still wants reflection).
                    //
                    // When editor == null (legacy / non-evolve), call the original
                    // (pre-reflection) overloads so behavior — and the call path the
                    // non-evolve attribution flow exercises — stays byte-identical.
                    ImprovementStartResult r;
                    if (basePromptVersionId != null) {
                        r = editor != null
                                ? promptImproverService.improveFromBasePrompt(
                                        eventId, targetAgentId, basePromptVersionId, issue,
                                        ownerIdOrDefault(input), editor)
                                : promptImproverService.improveFromBasePrompt(
                                        eventId, targetAgentId, basePromptVersionId, issue,
                                        ownerIdOrDefault(input));
                    } else {
                        r = editor != null
                                ? promptImproverService.startImprovementFromAttribution(
                                        eventId, targetAgentId, issue, ownerIdOrDefault(input), editor)
                                : promptImproverService.startImprovementFromAttribution(
                                        eventId, targetAgentId, issue, ownerIdOrDefault(input));
                    }
                    yield r.promptVersionId();
                }
                case BEHAVIOR_RULE -> {
                    // Hill-climb carry-forward (§8 #5): when baseVersionId is supplied
                    // (iter 2+), build the candidate on THAT behavior_rule version
                    // (the current-best from a prior winning bundle); else (iter 1)
                    // improve the agent's active baseline. Mirrors prompt's
                    // basePromptVersionId routing. Reflection editor overload = Phase 3.
                    String baseVersionId = trimToNull(input.get("baseVersionId"));
                    ImprovementStartResult r = baseVersionId != null
                            ? behaviorRuleImproverService.startImprovementFromBaseVersion(
                                    eventId, targetAgentId, baseVersionId, issue, ownerIdOrDefault(input))
                            : behaviorRuleImproverService.startImprovementFromAttribution(
                                    eventId, targetAgentId, issue, ownerIdOrDefault(input));
                    yield r.promptVersionId();
                }
                case SKILL -> generateSkillDraft(input, issue, eventId);
                // AGENT is rejected by the early guard above; this arm only keeps
                // the switch exhaustive over EvolveSurface.
                case AGENT -> throw new IllegalStateException(
                        "agent surface must be rejected before the generation switch");
            };

            log.info("[GenerateCandidate] surface={} targetAgentId={} eventId={} -> candidateId={}",
                    surface.wire(), targetAgentId, eventId, candidateId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("candidateId", candidateId);
            response.put("surface", surface.wire());
            response.put("status", "generated");
            response.put("message", "Candidate generated. Pass candidateId to TriggerAbEval "
                    + "(surface=" + surface.wire() + ") to start the A/B.");
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (IllegalArgumentException | NoSuchElementException e) {
            // Bad agent / missing field / unparsable number / unknown reportId or
            // issueId / non-convertible issue surface → LLM fixes + retries (or skips
            // the issue). NoSuchElementException comes from the report→event bridge.
            return SkillResult.validationError(e.getMessage());
        } catch (IllegalStateException e) {
            // Surface precondition not met (e.g. agent has empty system_prompt for the
            // prompt genesis-baseline path) — surface the message to the agent.
            return SkillResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("GenerateCandidate execute failed", e);
            return SkillResult.error("GenerateCandidate error: " + e.getMessage());
        }
    }

    /**
     * Resolve the audit-anchor {@code eventId} from the two supported modes and
     * validate ownership.
     *
     * <ul>
     *   <li><b>direct</b> — an explicit {@code eventId} is threaded through
     *       unchanged (no extra ownership check — a curator-authored event is
     *       trusted; the candidate is still persisted against
     *       {@code targetAgentId} by the improver).</li>
     *   <li><b>report-issue</b> — {@code reportId} + {@code issueId} → the
     *       EXISTING {@link OptReportToEventBridge#convertIssueToEvent} mints
     *       (idempotently) the event. SECURITY: the report's {@code agentId} is
     *       verified to match {@code targetAgentId} BEFORE the bridge is called,
     *       because {@code convertIssueToEvent} commits a {@code t_optimization_event}
     *       row — so an unchecked cross-agent {@code reportId} would otherwise
     *       pollute agent B's optimization-event / approval queue. The ownership
     *       gate therefore precedes the (side-effecting) mint.</li>
     * </ul>
     *
     * @throws IllegalArgumentException neither / both modes supplied, unparsable
     *                                  ids, non-convertible issue surface, or a
     *                                  report/target agent mismatch.
     * @throws NoSuchElementException   reportId or issueId not found.
     * @throws IllegalStateException    report not in {@code completed} status (from the bridge).
     */
    private Long resolveEventId(Map<String, Object> input, String targetAgentId) {
        Long directEventId = parseLong(input.get("eventId"));
        String reportId = trimToNull(input.get("reportId"));
        String issueId = trimToNull(input.get("issueId"));
        boolean hasReportAnchor = reportId != null || issueId != null;

        if (directEventId != null && hasReportAnchor) {
            throw new IllegalArgumentException(
                    "provide EITHER eventId OR (reportId + issueId), not both");
        }
        if (directEventId != null) {
            return directEventId;
        }
        if (reportId == null || issueId == null) {
            throw new IllegalArgumentException(
                    "an audit anchor is required: provide reportId + issueId "
                            + "(preferred, from GetOptReport) or an explicit eventId");
        }

        // SECURITY (ownership gate BEFORE the side-effecting mint): the report
        // must belong to targetAgentId. parseLong wraps NumberFormatException as
        // IllegalArgumentException so a non-numeric targetAgentId is a clean
        // validation error (not a generic 500).
        Long target = parseLong(targetAgentId);
        FlywheelRunEntity report = flywheelRunRepository.findById(reportId)
                .orElseThrow(() -> new NoSuchElementException("opt-report not found: " + reportId));
        Long reportAgentId = report.getAgentId();
        if (reportAgentId == null || !reportAgentId.equals(target)) {
            throw new IllegalArgumentException(
                    "report " + reportId + " belongs to agent " + reportAgentId
                            + " but targetAgentId=" + target + " — cannot anchor a candidate "
                            + "for one agent to another agent's report");
        }

        OptReportToEventBridge.ConvertResult result =
                optReportToEventBridge.convertIssueToEvent(reportId, issueId);
        return result.event().getId();
    }

    /**
     * Skill surface needs patternId + ownerId for the existing
     * {@code createDraftFromAttribution} signature. patternId is an
     * audit/naming value only (not a hard FK) — report-derived events have no
     * pattern, so we synthesise {@code patternId = eventId}. ownerId defaults to
     * the system user. A deterministic suggested name mirrors
     * {@code AttributionApprovalService.dispatchSkillSurface}.
     */
    private String generateSkillDraft(Map<String, Object> input, String issue, Long eventId) {
        Long patternId = parseLong(input.get("patternId"));
        if (patternId == null) {
            patternId = eventId;   // synth — audit/naming only, not a hard FK
        }
        Long ownerId = ownerIdOrDefault(input);
        String suggestedSkillName = "EvolveSkill" + patternId + "_" + eventId;
        SkillDraftEntity draft = skillDraftService.createDraftFromAttribution(
                eventId,
                patternId,
                issue,
                null,   // expectedImpact — unknown at evolve time; service tolerates null
                null,   // changeType — unknown at evolve time; service tolerates null
                ownerId,
                suggestedSkillName);
        return draft.getId();
    }

    private Long ownerIdOrDefault(Map<String, Object> input) {
        Long ownerId = parseLong(input.get("ownerId"));
        return ownerId != null ? ownerId : SYSTEM_USER_ID;
    }

    private static Long parseLong(Object value) {
        String s = trimToNull(value);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a numeric id but got: " + s);
        }
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
