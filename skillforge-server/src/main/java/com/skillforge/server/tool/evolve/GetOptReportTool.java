package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.optreport.dto.OptReportIssueDto;
import com.skillforge.server.optreport.dto.OptReportSummaryJson;
import com.skillforge.server.optreport.dto.OptReportSummaryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C — agent-callable READ tool that returns the
 * {@code topIssues} of a completed opt-report so the evolve orchestrator can
 * drive its iteration loop.
 *
 * <p><b>Why a read tool exists.</b> {@code RunWorkflow('opt-report')} starts the
 * report ASYNC and returns only a {@code runId} (= the report id); it does not
 * return the finished summary inline. The orchestrator therefore reads the
 * report back through this tool once it is {@code completed}. In the focused-loop
 * e2e the orchestrator is handed a pre-existing {@code reportId} directly.
 *
 * <p><b>Thin / reuse.</b> No new persistence and no re-implementation: the report
 * is a {@link FlywheelRunEntity} (loop_kind={@code opt_report}) and parsing reuses
 * the EXISTING {@link OptReportSummaryParser} — the same validated parse the
 * {@code OptReportController} read endpoints use. Output is shaped so each issue
 * is directly threadable into {@code GenerateCandidate} (reportId + issueId +
 * surface).
 *
 * <p><b>Access pin.</b> The optional {@code expectedAgentId} lets the orchestrator
 * pin the report to the agent it is evolving — a mismatch is a clean validation
 * error so an orchestrator evolving agent A cannot drive its loop off agent B's
 * report.
 *
 * <p><b>Recursion guard (invariant).</b> Registered ONLY in the main
 * {@code SkillRegistry} (see {@code SkillForgeConfig}); deliberately ABSENT from
 * {@code WorkflowSkillRegistryFactory} (the workflow sub-agent registry) — same
 * isolation invariant as the Module A/B/C tools. The orchestrator runs top-level.
 */
public class GetOptReportTool implements Tool {

    public static final String NAME = "GetOptReport";

    /**
     * Block up to this long waiting for the opt-report run to complete, re-checking
     * every {@link #POLL_INTERVAL_MS}. A blocking call is essential: the orchestrator
     * has a bounded agent-loop budget (maxLoops≈25), and an opt-report run takes
     * minutes — tight-polling from the agent loop would exhaust the budget before
     * the report finishes. One blocking call covers most of the wait in a single
     * loop iteration. Bounded so the agent loop is never wedged forever; the
     * orchestrator simply calls again to extend the wait.
     */
    static final long DEFAULT_BLOCK_TIMEOUT_MS = 90_000L;
    static final long DEFAULT_POLL_INTERVAL_MS = 3_000L;

    private static final Logger log = LoggerFactory.getLogger(GetOptReportTool.class);

    private final FlywheelRunRepository runRepository;
    private final OptReportSummaryParser summaryParser;
    private final ObjectMapper objectMapper;
    private final long blockTimeoutMs;
    private final long pollIntervalMs;

    public GetOptReportTool(FlywheelRunRepository runRepository,
                            OptReportSummaryParser summaryParser,
                            ObjectMapper objectMapper) {
        this(runRepository, summaryParser, objectMapper,
                DEFAULT_BLOCK_TIMEOUT_MS, DEFAULT_POLL_INTERVAL_MS);
    }

    /** Test constructor — small timeout/interval so blocking-wait tests run fast. */
    GetOptReportTool(FlywheelRunRepository runRepository,
                     OptReportSummaryParser summaryParser,
                     ObjectMapper objectMapper,
                     long blockTimeoutMs,
                     long pollIntervalMs) {
        this.runRepository = runRepository;
        this.summaryParser = summaryParser;
        this.objectMapper = objectMapper;
        this.blockTimeoutMs = blockTimeoutMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Read a completed opt-report's topIssues so you can drive the evolve loop. "
                + "Inputs:\n"
                + "- \"reportId\": the opt-report id (the runId returned by "
                + "RunWorkflow('opt-report'), or a pre-existing report id).\n"
                + "- \"expectedAgentId\" (required): the agent you are evolving; the report "
                + "must belong to it (else a validation error). Always pass your targetAgentId.\n"
                + "Returns { reportId, agentId, status, issueCount, topIssues: [{ id, title, "
                + "severity, surface, suspectSurface, fixSurface, convertible, sessionCount, "
                + "exampleSessionIds, suggestion, actionType, friction, recurrence, rootCause, "
                + "proposedFix }] }. friction/recurrence/rootCause/proposedFix carry the "
                + "holistic root cause (prefer them over suggestion when present). Pass each issue's reportId + "
                + "id (issueId) + surface to GenerateCandidate. Only issues with "
                + "convertible=true can be turned into a candidate (surface other/unclear cannot).";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reportId", Map.of(
                "type", "string",
                "description", "The opt-report id (runId from RunWorkflow('opt-report'))."
        ));
        properties.put("expectedAgentId", Map.of(
                "type", "string",
                "description", "The agent being evolved; report must belong to it (your targetAgentId)."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("reportId", "expectedAgentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required (reportId)");
            }
            String reportId = trimToNull(input.get("reportId"));
            if (reportId == null) {
                return SkillResult.validationError("reportId is required");
            }

            FlywheelRunEntity report = runRepository.findById(reportId).orElse(null);
            if (report == null) {
                return SkillResult.validationError("opt-report not found: " + reportId);
            }
            // Accept an opt-report from EITHER producer:
            //   - legacy OptReportService → loop_kind=opt_report
            //   - RunWorkflow('opt-report') → loop_kind=workflow (the summary is
            //     nested under `summary` in summary_json; OptReportSummaryParser
            //     unwraps it). A non-opt-report workflow run simply parses to zero
            //     topIssues below (harmless), so we don't need to inspect
            //     workflow_name here.
            String loopKind = report.getLoopKind();
            if (!FlywheelRunEntity.LOOP_KIND_OPT_REPORT.equals(loopKind)
                    && !FlywheelRunEntity.LOOP_KIND_WORKFLOW.equals(loopKind)) {
                return SkillResult.validationError(
                        "run " + reportId + " is not an opt-report (loop_kind=" + loopKind + ")");
            }

            // expectedAgentId is REQUIRED + server-enforced (not just schema-advertised):
            // reading a report by id alone would leak another agent's issue titles +
            // exampleSessionIds. Enforcing here — rather than trusting the LLM to pass
            // it — mirrors the FR-C7 budget-cap hardening (don't rely on prompt/schema
            // for an access gate). Pin the read to the agent being evolved.
            Long expectedAgentId;
            try {
                expectedAgentId = parseLong(input.get("expectedAgentId"));
            } catch (IllegalArgumentException e) {
                return SkillResult.validationError(e.getMessage());
            }
            if (expectedAgentId == null) {
                return SkillResult.validationError(
                        "expectedAgentId is required (the agent you are evolving); the report "
                                + "read is pinned to it to prevent cross-agent issue disclosure");
            }
            if (!expectedAgentId.equals(report.getAgentId())) {
                return SkillResult.validationError(
                        "report " + reportId + " belongs to agent " + report.getAgentId()
                                + " but expectedAgentId=" + expectedAgentId);
            }

            // Block (bounded) until the report reaches a terminal state, re-reading
            // the row every POLL_INTERVAL_MS. This keeps the orchestrator's agent
            // loop at 1-2 iterations here instead of ~25 tight polls (which would
            // blow its maxLoops budget while opt-report runs for minutes). Each
            // findById is its own autocommit read, so it observes the workflow's
            // markCompleted as soon as it commits.
            long deadline = System.currentTimeMillis() + blockTimeoutMs;
            while (!FlywheelRunEntity.STATUS_COMPLETED.equals(report.getStatus())
                    && !FlywheelRunEntity.STATUS_ERROR.equals(report.getStatus())
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                report = runRepository.findById(reportId).orElse(report);
            }
            if (FlywheelRunEntity.STATUS_ERROR.equals(report.getStatus())) {
                return SkillResult.error(
                        "opt-report " + reportId + " failed (status=error); it cannot be read. "
                                + "Pick a different issue source or re-run the report.");
            }
            if (!FlywheelRunEntity.STATUS_COMPLETED.equals(report.getStatus())) {
                // Still running after the bounded wait — tell the agent to call again
                // to keep waiting (one more call = one more BLOCK_TIMEOUT window).
                return SkillResult.error(
                        "opt-report " + reportId + " is still running (status="
                                + report.getStatus() + ") after waiting ~"
                                + (blockTimeoutMs / 1000) + "s; call GetOptReport again to keep waiting");
            }

            String summaryJson = report.getSummaryJson();
            if (summaryJson == null || summaryJson.isBlank()) {
                return SkillResult.error(
                        "opt-report " + reportId + " is completed but has no summary; "
                                + "it may have found no candidate sessions");
            }

            OptReportSummaryJson summary = summaryParser.parse(summaryJson);
            List<Map<String, Object>> issues = new ArrayList<>();
            for (OptReportIssueDto issue : summary.topIssues()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", issue.id());
                m.put("title", issue.title());
                m.put("severity", issue.severity());
                m.put("surface", issue.effectiveSurface());
                m.put("suspectSurface", issue.suspectSurface());
                m.put("fixSurface", issue.fixSurface());
                m.put("convertible",
                        OptReportIssueDto.CONVERTIBLE_SURFACES.contains(issue.effectiveSurface()));
                m.put("sessionCount", issue.sessionCount());
                m.put("exampleSessionIds", issue.exampleSessionIds());
                m.put("suggestion", issue.suggestion());
                m.put("actionType", issue.actionType());
                // concern#2 (AUTOEVOLVE-CLOSE-LOOP): expose the G4/G5-enriched fields so
                // the orchestrator can see the holistic root cause (for issue selection /
                // prioritisation). null-safe for pre-G4 reports. Candidate-gen itself
                // already receives the enriched description server-side via
                // GenerateCandidate report-issue mode; these are for orchestrator visibility.
                m.put("friction", issue.friction());
                m.put("recurrence", issue.recurrence());
                m.put("rootCause", issue.rootCause());
                m.put("proposedFix", issue.proposedFix());
                issues.add(m);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("reportId", reportId);
            response.put("agentId", report.getAgentId());
            response.put("status", report.getStatus());
            response.put("issueCount", issues.size());
            response.put("topIssues", issues);

            log.info("[GetOptReport] reportId={} agentId={} issueCount={}",
                    reportId, report.getAgentId(), issues.size());
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (IllegalArgumentException e) {
            // summaryParser rejected a schema-invalid summary_json.
            return SkillResult.error("opt-report summary is not parseable: " + e.getMessage());
        } catch (Exception e) {
            log.error("GetOptReport execute failed", e);
            return SkillResult.error("GetOptReport error: " + e.getMessage());
        }
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
