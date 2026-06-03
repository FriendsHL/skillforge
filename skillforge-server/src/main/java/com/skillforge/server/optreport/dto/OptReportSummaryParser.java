package com.skillforge.server.optreport.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OPT-REPORT-V1.2 (2026-05-23): validating parser for
 * {@code t_opt_report.summary_json}.
 *
 * <p>The LLM authoring the report follows the V102 prompt that pins
 * down the {@link OptReportIssueDto} schema. Reality: LLMs miss fields,
 * use the wrong enum value, drift confidence outside [0, 1]. This
 * parser is the validation gate before any FE rendering / OptEvent
 * conversion — every "schema invalid" failure surfaces as a clear
 * {@link IllegalArgumentException} so callers can decide whether to
 * 400 the operator (convert path) or just hide the structured panel
 * (read path).
 *
 * <p>Why not Bean Validation / @Valid: the input is a free-form String
 * stored in JSONB, not a request DTO. Doing the checks here keeps the
 * Jackson roundtrip simple (record default constructor) and avoids
 * pulling in {@code jakarta.validation} just for a one-off parser.
 *
 * <p>Empty/null input is treated as "no topIssues" — returns
 * {@code OptReportSummaryJson(List.of())}, not throws. Callers like
 * {@code OptReportController.getReport} want the FE to render the
 * markdown body even when summary_json is absent (V1.0/V1.1 reports
 * don't carry the V1.2 schema).
 */
@Component
public final class OptReportSummaryParser {

    private final ObjectMapper objectMapper;

    public OptReportSummaryParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse + validate {@code summary_json}. Returns an empty issue list
     * (not throws) when {@code summaryJson} is null / blank — see class
     * javadoc.
     *
     * @throws IllegalArgumentException if the JSON is malformed or any
     *         {@code topIssues[i]} violates the V1.2 schema.
     */
    public OptReportSummaryJson parse(String summaryJson) {
        if (summaryJson == null || summaryJson.isBlank()) {
            return new OptReportSummaryJson(List.of());
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(summaryJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "summary_json is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(
                    "summary_json must be a JSON object; got: "
                            + (root == null ? "null" : root.getNodeType().toString()));
        }
        // AUTOEVOLVE-AGENT-FLYWHEEL: a report produced by RunWorkflow('opt-report')
        // stores the workflow's RETURN value as summary_json — shape
        // {status, summary:{...topIssues...}, reviewerId, ...} — i.e. the summary is
        // nested under "summary". The legacy OptReportService path stores the summary
        // directly (topIssues at the top level). Unwrap the nested form so BOTH
        // producers (and both consumers — GetOptReport + OptReportToEventBridge) parse
        // uniformly without each special-casing the shape.
        if (!root.has("topIssues")
                && root.has("summary")
                && root.get("summary").isObject()
                && root.get("summary").has("topIssues")) {
            root = root.get("summary");
        }
        JsonNode arr = root.get("topIssues");
        if (arr == null || arr.isNull()) {
            // Older V1.0/V1.1 reports may not carry topIssues at all —
            // treat as "no convertible issues" rather than schema error.
            return new OptReportSummaryJson(List.of());
        }
        if (!arr.isArray()) {
            throw new IllegalArgumentException(
                    "summary_json.topIssues must be an array; got: " + arr.getNodeType());
        }
        List<OptReportIssueDto> issues = new ArrayList<>(arr.size());
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            issues.add(parseIssue(arr.get(i), i, seenIds));
        }
        return new OptReportSummaryJson(List.copyOf(issues));
    }

    private OptReportIssueDto parseIssue(JsonNode node, int idx, Set<String> seenIds) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "] must be a JSON object");
        }

        String id = requireText(node, "id", idx);
        if (!seenIds.add(id)) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "].id is duplicated: '" + id + "'");
        }
        String title = requireText(node, "title", idx);
        String severity = requireText(node, "severity", idx);
        if (!OptReportIssueDto.SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "].severity must be one of "
                            + OptReportIssueDto.SEVERITIES + "; got: '" + severity + "'");
        }

        JsonNode countNode = node.get("sessionCount");
        if (countNode == null || !countNode.isIntegralNumber()) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "].sessionCount is required and must be an integer");
        }
        int sessionCount = countNode.asInt();
        if (sessionCount < 1) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "].sessionCount must be ≥ 1; got: " + sessionCount);
        }

        JsonNode idsNode = node.get("exampleSessionIds");
        if (idsNode == null || !idsNode.isArray() || idsNode.isEmpty()) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "].exampleSessionIds is required and must be a non-empty array");
        }
        List<String> exampleSessionIds = new ArrayList<>(idsNode.size());
        for (int j = 0; j < idsNode.size(); j++) {
            JsonNode sid = idsNode.get(j);
            if (sid == null || !sid.isTextual() || sid.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "topIssues[" + idx + "].exampleSessionIds[" + j + "] must be a non-blank string");
            }
            exampleSessionIds.add(sid.asText());
        }
        // V1.2 prompt requires sessionCount ≥ exampleSessionIds.length
        // (the LLM may identify N sessions but only quote 2 examples; the
        // converse — "count < examples" — is contradictory and likely a
        // hallucination).
        if (sessionCount < exampleSessionIds.size()) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "].sessionCount (" + sessionCount
                            + ") must be ≥ exampleSessionIds.length ("
                            + exampleSessionIds.size() + ")");
        }

        String suspectSurface = requireText(node, "suspectSurface", idx);
        if (!OptReportIssueDto.SURFACES.contains(suspectSurface)) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "].suspectSurface must be one of "
                            + OptReportIssueDto.SURFACES + "; got: '" + suspectSurface + "'");
        }

        // V1.3+: fixSurface is optional. If provided, validate against SURFACES.
        // null / missing / blank → null (downstream effectiveSurface() falls
        // back to suspectSurface for legacy reports / LLM that doesn't split).
        JsonNode fixSurfaceNode = node.get("fixSurface");
        String fixSurface = null;
        if (fixSurfaceNode != null && fixSurfaceNode.isTextual() && !fixSurfaceNode.asText().isBlank()) {
            fixSurface = fixSurfaceNode.asText();
            if (!OptReportIssueDto.SURFACES.contains(fixSurface)) {
                throw new IllegalArgumentException(
                        "topIssues[" + idx + "].fixSurface must be one of "
                                + OptReportIssueDto.SURFACES + " or omitted; got: '" + fixSurface + "'");
            }
        }

        JsonNode confNode = node.get("confidence");
        if (confNode == null || !confNode.isNumber()) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "].confidence is required and must be a number");
        }
        double confidence = confNode.asDouble();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "].confidence must be in [0.0, 1.0]; got: " + confidence);
        }

        // V1.6 (G4): suggestion is now OPTIONAL — superseded by the
        // rootCause/proposedFix split. Accept null/missing/blank → null. The
        // joint check below enforces "at least one of suggestion / rootCause".
        String suggestion = optionalText(node, "suggestion");

        // expectedImpact is optional — accept null/missing/blank → store null.
        JsonNode impactNode = node.get("expectedImpact");
        String expectedImpact = null;
        if (impactNode != null && impactNode.isTextual() && !impactNode.asText().isBlank()) {
            expectedImpact = impactNode.asText();
        }

        // V1.5+: actionType is optional. null / missing / blank → null
        // (downstream treats as "new" for backward compat). If present, must
        // be one of {"new","modify","duplicate"} or we throw — defends
        // against LLM drift like "create" / "update" / "upgrade".
        JsonNode actionTypeNode = node.get("actionType");
        String actionType = null;
        if (actionTypeNode != null && !actionTypeNode.isNull()) {
            if (!actionTypeNode.isTextual() || actionTypeNode.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "topIssues[" + idx + "].actionType must be a non-blank string or omitted; "
                                + "got: " + actionTypeNode.getNodeType());
            }
            actionType = actionTypeNode.asText();
            if (!OptReportIssueDto.ACTION_TYPES.contains(actionType)) {
                throw new IllegalArgumentException(
                        "topIssues[" + idx + "].actionType must be one of "
                                + OptReportIssueDto.ACTION_TYPES + " or omitted; got: '"
                                + actionType + "'");
            }
        }

        // V1.5+: targetRuleText is optional in general but REQUIRED non-blank
        // when actionType ∈ {modify, duplicate}. This stops the LLM from
        // labelling an issue "modify existing rule X" without quoting which
        // rule X — the whole point of V1.5 is letting the operator audit the
        // claim. {@code actionType=new} (or null) → must be null/blank.
        JsonNode targetTextNode = node.get("targetRuleText");
        String targetRuleText = null;
        if (targetTextNode != null && targetTextNode.isTextual() && !targetTextNode.asText().isBlank()) {
            targetRuleText = targetTextNode.asText();
        }
        if (("modify".equals(actionType) || "duplicate".equals(actionType))
                && (targetRuleText == null || targetRuleText.isBlank())) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "].targetRuleText is required and must be a "
                            + "non-blank string when actionType='" + actionType + "' "
                            + "(quote the existing rule/skill/prompt segment verbatim)");
        }

        // V1.6 (G4): friction is optional. null / missing / blank → null. If
        // present, must be one of FRICTION_VALUES or we throw — defends against
        // LLM drift like "tool_error" / "confused".
        JsonNode frictionNode = node.get("friction");
        String friction = null;
        if (frictionNode != null && !frictionNode.isNull()) {
            if (!frictionNode.isTextual() || frictionNode.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "topIssues[" + idx + "].friction must be a non-blank string or omitted; "
                                + "got: " + frictionNode.getNodeType());
            }
            friction = frictionNode.asText();
            if (!OptReportIssueDto.FRICTION_VALUES.contains(friction)) {
                throw new IllegalArgumentException(
                        "topIssues[" + idx + "].friction must be one of "
                                + OptReportIssueDto.FRICTION_VALUES + " or omitted; got: '"
                                + friction + "'");
            }
        }

        // V1.6 (G4): recurrence is optional — defaults to 1 (single occurrence /
        // no matching production cluster / cold store). If present must be an
        // integer ≥ 1. Carries t_session_pattern.member_count (MULTIPLE TIMES).
        int recurrence = 1;
        JsonNode recurrenceNode = node.get("recurrence");
        if (recurrenceNode != null && !recurrenceNode.isNull()) {
            if (!recurrenceNode.isIntegralNumber()) {
                throw new IllegalArgumentException(
                        "topIssues[" + idx + "].recurrence must be an integer or omitted; got: "
                                + recurrenceNode.getNodeType());
            }
            recurrence = recurrenceNode.asInt();
            if (recurrence < 1) {
                throw new IllegalArgumentException(
                        "topIssues[" + idx + "].recurrence must be ≥ 1; got: " + recurrence);
            }
        }

        // V1.6 (G4): rootCause / proposedFix are optional null-safe text.
        String rootCause = optionalText(node, "rootCause");
        String proposedFix = optionalText(node, "proposedFix");

        // V1.6 (G4) joint check: an issue must carry at least one actionable
        // statement. suggestion was demoted to optional in favour of the
        // rootCause/proposedFix split, so we reject only when BOTH suggestion
        // and rootCause are absent — that issue has no fix direction at all.
        if (suggestion == null && rootCause == null) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "] must carry at least one of 'suggestion' or "
                            + "'rootCause' (both missing/blank — issue has no actionable content)");
        }

        return new OptReportIssueDto(
                id,
                title,
                severity,
                sessionCount,
                List.copyOf(exampleSessionIds),
                suspectSurface,
                fixSurface,
                confidence,
                suggestion,
                expectedImpact,
                actionType,
                targetRuleText,
                friction,
                recurrence,
                rootCause,
                proposedFix);
    }

    private static String requireText(JsonNode node, String field, int idx) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "topIssues[" + idx + "]." + field + " is required and must be a non-blank string");
        }
        return v.asText();
    }

    /**
     * V1.6 (G4): read an optional text field — returns null when the field is
     * missing / null / non-textual / blank (never throws). Used for the
     * optional facets (suggestion / rootCause / proposedFix).
     */
    private static String optionalText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            return null;
        }
        return v.asText();
    }
}
