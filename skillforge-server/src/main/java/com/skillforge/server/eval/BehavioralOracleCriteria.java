package com.skillforge.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parsed criteria for the {@code tool_error_absence} behavioral oracle — the
 * single source of truth for the criteria JSON shape stored in
 * {@code EvalScenario.oracle.expected}.
 *
 * <p>Before this record the judge (which scores) and the A/B pipeline (which only
 * needs the multi-round count) each {@code readTree}'d the same JSON and hard-coded
 * the field-name literals. Centralizing parsing here keeps those literals in one
 * place and stops the A/B layer from reaching into oracle-criteria internals.
 *
 * <p>Fields are intentionally neutral, describing only WHAT a successful run looks
 * like (which tool, on which file, how many times to measure, which error signals
 * a reproduction): the oracle stays result-type and encodes nothing about HOW the
 * agent should accomplish the task.
 *
 * @param tool           target tool name (e.g. {@code "Edit"}); null/blank matches any tool
 * @param errorSignature failure-output substring that marks a reproduction; null/blank → caller treats as un-scoreable
 * @param passWhen       {@code "no_match"} (default) or {@code "match"}
 * @param filePath       optional sandbox-relative target file for engagement path-scope; null → no path constraint
 * @param rounds         repeat count; defaults to 1 (single round, BC-M1 compatible), clamped to >= 1
 */
public record BehavioralOracleCriteria(
        String tool, String errorSignature, String passWhen, String filePath, int rounds) {

    private static final Logger log = LoggerFactory.getLogger(BehavioralOracleCriteria.class);

    public static final String DEFAULT_PASS_WHEN = "no_match";

    /**
     * Parse the oracle's {@code expected} JSON into criteria. Null/blank/invalid
     * JSON yields all-default criteria ({@code passWhen="no_match"}, {@code rounds=1},
     * nullable {@code tool}/{@code errorSignature}/{@code filePath}); a null
     * {@code errorSignature} signals the caller it cannot score the run.
     */
    public static BehavioralOracleCriteria parse(String oracleExpectedJson, ObjectMapper objectMapper) {
        String tool = null;
        String errorSignature = null;
        String passWhen = DEFAULT_PASS_WHEN;
        String filePath = null;
        int rounds = 1;
        if (oracleExpectedJson != null && !oracleExpectedJson.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(oracleExpectedJson);
                tool = textOrNull(root, "tool");
                errorSignature = textOrNull(root, "errorSignature");
                String pw = textOrNull(root, "passWhen");
                if (pw != null && !pw.isBlank()) {
                    passWhen = pw;
                }
                filePath = textOrNull(root, "filePath");
                JsonNode roundsNode = root.path("rounds");
                if (!roundsNode.isMissingNode() && !roundsNode.isNull()) {
                    rounds = Math.max(1, roundsNode.asInt(1));
                }
            } catch (Exception e) {
                log.warn("behavioral oracle criteria JSON not parseable, using defaults: {}", e.getMessage());
            }
        }
        return new BehavioralOracleCriteria(tool, errorSignature, passWhen, filePath, rounds);
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return (node.isMissingNode() || node.isNull()) ? null : node.asText(null);
    }
}
