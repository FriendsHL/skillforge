package com.skillforge.workflow.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * AUTOEVOLVING V1 Sprint 2 (Task F) — production {@link JournalCache}.
 *
 * <p>On resume the workflow JS is re-run top-to-bottom in a fresh Rhino context.
 * Each {@code agent()} / {@code humanApprove()} call whose deterministic
 * {@code stepIndex} is at/before the resume frontier short-circuits to its
 * first-run result, looked up here BY {@code step_index} (V127 column) — never by
 * {@code created_at}, which is non-deterministic under {@code parallel()}.
 *
 * <p>Reads only completed step rows: an incomplete row at a cache-hit index is a
 * genuine replay bug, so it surfaces as a miss ({@code Optional.empty}) and the
 * caller fails the run rather than silently re-running the agent.
 */
@Service
public class JournalCacheService implements JournalCache {

    private static final Logger log = LoggerFactory.getLogger(JournalCacheService.class);

    private final FlywheelRunStepRepository stepRepository;
    private final ObjectMapper objectMapper;

    public JournalCacheService(FlywheelRunStepRepository stepRepository, ObjectMapper objectMapper) {
        this.stepRepository = stepRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getCachedAgentFinalResponse(String runId, int stepIndex) {
        return stepRepository
                .findByRunIdAndStepIndexAndStepKind(
                        runId, stepIndex, FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH)
                .filter(s -> FlywheelRunStepEntity.STATUS_COMPLETED.equals(s.getStatus()))
                .map(s -> extractFinalResponse(s.getStepOutputJson(), runId, stepIndex));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JsonNode> getApproveDecision(String runId, int stepIndex) {
        return stepRepository
                .findByRunIdAndStepIndexAndStepKind(
                        runId, stepIndex, FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE)
                .filter(s -> FlywheelRunStepEntity.STATUS_COMPLETED.equals(s.getStatus()))
                .map(s -> parseDecision(s.getStepOutputJson(), runId, stepIndex));
    }

    /**
     * The {@code finalResponse} string {@code DefaultWorkflowAgentInvoker} stored
     * in {@code step_output_json} (always present on a completed agent step). On
     * replay {@code HostAgent} re-derives the JS return value from this string —
     * re-parsing it under a schema yields byte-identical shape to the first run.
     */
    private String extractFinalResponse(String stepOutputJson, String runId, int stepIndex) {
        if (stepOutputJson == null || stepOutputJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(stepOutputJson);
            JsonNode fr = node.get("finalResponse");
            return fr == null || fr.isNull() ? "" : fr.asText();
        } catch (Exception e) {
            log.warn("JournalCache: malformed step_output_json for runId={} stepIndex={}: {}",
                    runId, stepIndex, e.getMessage());
            return "";
        }
    }

    private JsonNode parseDecision(String stepOutputJson, String runId, int stepIndex) {
        if (stepOutputJson == null || stepOutputJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(stepOutputJson);
        } catch (Exception e) {
            log.warn("JournalCache: malformed decision json for runId={} stepIndex={}: {}",
                    runId, stepIndex, e.getMessage());
            return objectMapper.createObjectNode();
        }
    }
}
