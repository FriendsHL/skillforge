package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.workflow.WorkflowRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — deterministic {@code tool()} node that runs the
 * {@code opt-report} workflow as a blocking sub-flow and returns its
 * {@code reportId} (= the sub-run id). The evolve-loop workflow calls this when
 * the operator did NOT hand it a pre-existing completed {@code reportId} (design
 * §4.2 retreat B1).
 *
 * <p><b>Nesting safety (Q1, spike-verified).</b> {@code ConsolidationLock} is
 * keyed per workflow name, so {@code evolve-loop} (the caller) and
 * {@code opt-report} (the sub-flow) never self-deadlock. The sub-flow runs on a
 * SEPARATE {@code workflowExecutor} thread (pool max 16), so blocking the
 * evolve-loop thread here does not starve the sub-flow's own body (R4: bounded
 * for low concurrency; revisit pool sizing before running many concurrent evolve
 * loops with sub-flows).
 *
 * <p><b>autoApprove.</b> Always passes {@code autoApprove=true} to opt-report so it
 * does not park on its human gate (the human gate moves to the END of the evolve
 * loop). Without this the sub-flow would pause and this block-poll would never see
 * a terminal status.
 *
 * <p><b>Recursion guard.</b> Registered ONLY in the workflow {@code tool()}
 * whitelist ({@code WorkflowEvolveToolRegistryFactory}); NEVER exposed to LLM
 * sub-agents — a sub-agent reaching it would re-open the workflow fan-out path.
 */
public class RunOptReportSubflowTool implements Tool {

    public static final String NAME = "RunOptReportSubflow";

    /** opt-report runs for minutes; block up to this long for it to finish. */
    static final long DEFAULT_BLOCK_TIMEOUT_MS = 30 * 60_000L;
    static final long DEFAULT_POLL_INTERVAL_MS = 5_000L;

    private static final long SYSTEM_USER_ID = 0L;

    private static final Logger log = LoggerFactory.getLogger(RunOptReportSubflowTool.class);

    private final WorkflowRunnerService workflowRunnerService;
    private final FlywheelRunRepository flywheelRunRepository;
    private final ObjectMapper objectMapper;
    private final long blockTimeoutMs;
    private final long pollIntervalMs;

    public RunOptReportSubflowTool(WorkflowRunnerService workflowRunnerService,
                                   FlywheelRunRepository flywheelRunRepository,
                                   ObjectMapper objectMapper) {
        this(workflowRunnerService, flywheelRunRepository, objectMapper,
                DEFAULT_BLOCK_TIMEOUT_MS, DEFAULT_POLL_INTERVAL_MS);
    }

    /** Test constructor — small timeout/interval so blocking-wait tests run fast. */
    RunOptReportSubflowTool(WorkflowRunnerService workflowRunnerService,
                            FlywheelRunRepository flywheelRunRepository,
                            ObjectMapper objectMapper,
                            long blockTimeoutMs,
                            long pollIntervalMs) {
        this.workflowRunnerService = workflowRunnerService;
        this.flywheelRunRepository = flywheelRunRepository;
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
        return "Run the opt-report workflow as a blocking sub-flow and return its reportId. "
                + "Inputs: \"agentId\" (numeric, the agent being evolved), optional \"windowDays\". "
                + "Returns { reportId, status }. autoApprove is always true (the human gate is at the "
                + "end of the evolve loop). Used only when no pre-existing reportId was supplied.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agentId", Map.of("type", "string",
                "description", "The agent being evolved (numeric agent id)."));
        properties.put("windowDays", Map.of("type", "integer",
                "description", "Optional lookback window in days (opt-report default applies when omitted)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("agentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required (agentId)");
            }
            Long agentId = parseLong(input.get("agentId"));
            if (agentId == null || agentId <= 0L) {
                return SkillResult.validationError("agentId is required (positive numeric id)");
            }

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("agentId", agentId);
            args.put("autoApprove", true);
            Integer windowDays = parseInt(input.get("windowDays"));
            if (windowDays != null && windowDays > 0) {
                args.put("windowDays", windowDays);
            }

            Long userId = context != null && context.getUserId() != null
                    ? context.getUserId() : SYSTEM_USER_ID;

            String reportId = workflowRunnerService.startRun("opt-report", args, userId);
            log.info("[RunOptReportSubflow] started opt-report sub-flow reportId={} agentId={}",
                    reportId, agentId);

            // Block (bounded) until the sub-flow reaches a terminal status. Each
            // findById is its own autocommit read, observing the sub-flow's
            // markCompleted as soon as it commits.
            long deadline = System.currentTimeMillis() + blockTimeoutMs;
            FlywheelRunEntity run = flywheelRunRepository.findById(reportId).orElse(null);
            while (run != null
                    && !FlywheelRunEntity.STATUS_COMPLETED.equals(run.getStatus())
                    && !FlywheelRunEntity.STATUS_ERROR.equals(run.getStatus())
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                run = flywheelRunRepository.findById(reportId).orElse(run);
            }

            String status = run == null ? "unknown" : run.getStatus();
            if (!FlywheelRunEntity.STATUS_COMPLETED.equals(status)) {
                return SkillResult.error("opt-report sub-flow " + reportId + " did not complete (status="
                        + status + ") within the block window; cannot drive the evolve loop");
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("reportId", reportId);
            response.put("status", status);
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("RunOptReportSubflow execute failed", e);
            return SkillResult.error("RunOptReportSubflow error: " + e.getMessage());
        }
    }

    private static Long parseLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = trimToNull(value);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        String s = trimToNull(value);
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
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
