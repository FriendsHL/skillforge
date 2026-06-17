package com.skillforge.server.optreport.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OptReportSummaryParser (V1.2 schema validation)")
class OptReportSummaryParserTest {

    private OptReportSummaryParser parser;

    @BeforeEach
    void setUp() {
        parser = new OptReportSummaryParser(new ObjectMapper().findAndRegisterModules());
    }

    @Test
    @DisplayName("AUTOEVOLVE: unwraps the nested workflow-return shape {status, summary:{topIssues}}")
    void parse_nestedWorkflowReturnShape_unwrapsSummary() {
        // RunWorkflow('opt-report') stores the workflow RETURN value as summary_json,
        // so topIssues are nested under `summary`. The parser must unwrap it.
        String json = """
            {
              "status": "approved",
              "reviewerId": "system:auto",
              "reason": "auto-approved (orchestrator-driven evolve loop)",
              "batchesTotal": 3,
              "summary": {
                "totalSessions": 12,
                "topIssues": [
                  {
                    "id": "issue-1",
                    "title": "Monorepo nav fails",
                    "severity": "high",
                    "sessionCount": 4,
                    "exampleSessionIds": ["sess-abc"],
                    "suspectSurface": "behavior_rule",
                    "confidence": 0.8,
                    "suggestion": "Add a cd-check rule"
                  }
                ]
              }
            }
            """;

        OptReportSummaryJson out = parser.parse(json);

        assertThat(out.topIssues()).hasSize(1);
        assertThat(out.topIssues().get(0).id()).isEqualTo("issue-1");
        assertThat(out.topIssues().get(0).suspectSurface()).isEqualTo("behavior_rule");
    }

    @Test
    @DisplayName("AUTOEVOLVE: top-level topIssues still parse (legacy opt_report shape) — unwrap is conditional")
    void parse_topLevelShape_stillParses_whenSummaryFieldAbsent() {
        String json = """
            { "topIssues": [ { "id": "issue-1", "title": "t", "severity": "low",
              "sessionCount": 1, "exampleSessionIds": ["sess-1"], "suspectSurface": "prompt",
              "confidence": 0.5, "suggestion": "s" } ] }
            """;
        assertThat(parser.parse(json).topIssues()).hasSize(1);
    }

    @Test
    @DisplayName("happy path — parses 2-issue schema with all required fields")
    void parse_happyPath_returnsAllFields() {
        String json = """
            {
              "totalSessions": 12,
              "successRate": 0.5,
              "topIssues": [
                {
                  "id": "issue-1",
                  "title": "ReadFile fails on absolute paths",
                  "severity": "high",
                  "sessionCount": 4,
                  "exampleSessionIds": ["sess-abc", "sess-def"],
                  "suspectSurface": "skill",
                  "confidence": 0.85,
                  "suggestion": "Rewrite ReadFile skill to handle absolute paths",
                  "expectedImpact": "Fix the 30% failure rate on file-reading tasks"
                },
                {
                  "id": "issue-2",
                  "title": "Loop inefficiency on long sessions",
                  "severity": "medium",
                  "sessionCount": 3,
                  "exampleSessionIds": ["sess-xyz"],
                  "suspectSurface": "prompt",
                  "confidence": 0.6,
                  "suggestion": "Add loop-detection heuristic to system prompt"
                }
              ]
            }
            """;

        OptReportSummaryJson out = parser.parse(json);

        assertThat(out.topIssues()).hasSize(2);
        OptReportIssueDto first = out.topIssues().get(0);
        assertThat(first.id()).isEqualTo("issue-1");
        assertThat(first.title()).isEqualTo("ReadFile fails on absolute paths");
        assertThat(first.severity()).isEqualTo("high");
        assertThat(first.sessionCount()).isEqualTo(4);
        assertThat(first.exampleSessionIds()).containsExactly("sess-abc", "sess-def");
        assertThat(first.suspectSurface()).isEqualTo("skill");
        assertThat(first.confidence()).isEqualTo(0.85);
        assertThat(first.suggestion()).contains("Rewrite ReadFile");
        assertThat(first.expectedImpact()).isEqualTo("Fix the 30% failure rate on file-reading tasks");

        OptReportIssueDto second = out.topIssues().get(1);
        assertThat(second.id()).isEqualTo("issue-2");
        assertThat(second.expectedImpact()).isNull();  // optional field
    }

    @Test
    @DisplayName("null/blank input → empty list (not throw)")
    void parse_nullOrBlank_returnsEmpty() {
        assertThat(parser.parse(null).topIssues()).isEmpty();
        assertThat(parser.parse("").topIssues()).isEmpty();
        assertThat(parser.parse("   ").topIssues()).isEmpty();
    }

    @Test
    @DisplayName("missing topIssues key → empty list (V1.0/V1.1 backward-compat)")
    void parse_missingTopIssues_returnsEmpty() {
        String json = "{ \"totalSessions\": 5, \"successRate\": 0.4 }";
        assertThat(parser.parse(json).topIssues()).isEmpty();
    }

    @Test
    @DisplayName("missing required field 'title' → IllegalArgumentException")
    void parse_missingTitle_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1",
                  "severity": "high",
                  "sessionCount": 1,
                  "exampleSessionIds": ["sess-a"],
                  "suspectSurface": "skill",
                  "confidence": 0.5,
                  "suggestion": "Try X"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    @DisplayName("invalid severity → IllegalArgumentException")
    void parse_invalidSeverity_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1",
                  "title": "x",
                  "severity": "CRITICAL",
                  "sessionCount": 1,
                  "exampleSessionIds": ["sess-a"],
                  "suspectSurface": "skill",
                  "confidence": 0.5,
                  "suggestion": "Try X"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("severity")
                .hasMessageContaining("CRITICAL");
    }

    @Test
    @DisplayName("invalid suspectSurface → IllegalArgumentException")
    void parse_invalidSuspectSurface_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1",
                  "title": "x",
                  "severity": "high",
                  "sessionCount": 1,
                  "exampleSessionIds": ["sess-a"],
                  "suspectSurface": "infrastructure",
                  "confidence": 0.5,
                  "suggestion": "Try X"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suspectSurface");
    }

    @Test
    @DisplayName("confidence out of [0,1] → IllegalArgumentException")
    void parse_confidenceOutOfRange_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1",
                  "title": "x",
                  "severity": "high",
                  "sessionCount": 1,
                  "exampleSessionIds": ["sess-a"],
                  "suspectSurface": "skill",
                  "confidence": 1.5,
                  "suggestion": "Try X"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    @DisplayName("empty exampleSessionIds array → IllegalArgumentException")
    void parse_emptyExampleSessionIds_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1",
                  "title": "x",
                  "severity": "high",
                  "sessionCount": 1,
                  "exampleSessionIds": [],
                  "suspectSurface": "skill",
                  "confidence": 0.5,
                  "suggestion": "Try X"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exampleSessionIds");
    }

    @Test
    @DisplayName("sessionCount < exampleSessionIds.length → IllegalArgumentException")
    void parse_sessionCountLessThanExamples_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1",
                  "title": "x",
                  "severity": "high",
                  "sessionCount": 1,
                  "exampleSessionIds": ["a", "b", "c"],
                  "suspectSurface": "skill",
                  "confidence": 0.5,
                  "suggestion": "Try X"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionCount");
    }

    @Test
    @DisplayName("duplicate issue ids → IllegalArgumentException")
    void parse_duplicateIds_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5, "suggestion": "y"
                },
                {
                  "id": "issue-1", "title": "z", "severity": "low",
                  "sessionCount": 1, "exampleSessionIds": ["b"],
                  "suspectSurface": "prompt", "confidence": 0.7, "suggestion": "w"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicated");
    }

    @Test
    @DisplayName("malformed JSON → IllegalArgumentException")
    void parse_malformedJson_throws() {
        assertThatThrownBy(() -> parser.parse("{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    @DisplayName("topIssues is object (not array) → IllegalArgumentException")
    void parse_topIssuesWrongType_throws() {
        String json = "{ \"topIssues\": { \"id\": \"issue-1\" } }";
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("array");
    }

    // ─────────────────────────────────────────────────────────────────────
    // V1.5+ actionType / targetRuleText (rule dedup)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("V1.5+ actionType='new' without targetRuleText → ok")
    void parse_actionTypeNew_ok() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5,
                  "suggestion": "y",
                  "actionType": "new"
                }
            ]}
            """;
        OptReportIssueDto issue = parser.parse(json).topIssues().get(0);
        assertThat(issue.actionType()).isEqualTo("new");
        assertThat(issue.targetRuleText()).isNull();
    }

    @Test
    @DisplayName("V1.5+ actionType='modify' but targetRuleText missing → IllegalArgumentException")
    void parse_actionTypeModify_requiresTargetRuleText() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5,
                  "suggestion": "y",
                  "actionType": "modify"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetRuleText")
                .hasMessageContaining("modify");
    }

    @Test
    @DisplayName("V1.5+ actionType='duplicate' but targetRuleText blank → IllegalArgumentException")
    void parse_actionTypeDuplicate_requiresTargetRuleText() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5,
                  "suggestion": "y",
                  "actionType": "duplicate",
                  "targetRuleText": "   "
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetRuleText")
                .hasMessageContaining("duplicate");
    }

    @Test
    @DisplayName("V1.5+ actionType='upgrade' (not in enum) → IllegalArgumentException")
    void parse_actionTypeInvalid_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5,
                  "suggestion": "y",
                  "actionType": "upgrade"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actionType")
                .hasMessageContaining("upgrade");
    }

    @Test
    @DisplayName("V1.5+ actionType missing → null (backward-compat with V1.4 reports)")
    void parse_actionTypeMissing_defaultsToNullForBackwardCompat() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5,
                  "suggestion": "y"
                }
            ]}
            """;
        OptReportIssueDto issue = parser.parse(json).topIssues().get(0);
        assertThat(issue.actionType()).isNull();
        assertThat(issue.targetRuleText()).isNull();
    }

    @Test
    @DisplayName("V1.5+ actionType='modify' + targetRuleText present → ok")
    void parse_actionTypeModify_withTargetRuleText_ok() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "behavior_rule", "confidence": 0.7,
                  "suggestion": "在现有 rule 上加 XYZ",
                  "actionType": "modify",
                  "targetRuleText": "git 操作前确认目录"
                }
            ]}
            """;
        OptReportIssueDto issue = parser.parse(json).topIssues().get(0);
        assertThat(issue.actionType()).isEqualTo("modify");
        assertThat(issue.targetRuleText()).isEqualTo("git 操作前确认目录");
    }

    // ─────────────────────────────────────────────────────────────────────
    // V1.6 (G4) friction / recurrence / rootCause / proposedFix + suggestion demotion
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("G4: friction/recurrence/rootCause/proposedFix parse; suggestion optional")
    void parse_g4Facets_parseAndSuggestionOptional() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "Bash 路径循环", "severity": "high",
                  "sessionCount": 4, "exampleSessionIds": ["a", "b"],
                  "suspectSurface": "skill", "confidence": 0.7,
                  "friction": "repeated_tool_failure",
                  "recurrence": 5,
                  "rootCause": "agent 没在 cd 前确认目录",
                  "proposedFix": "加 pwd 检查的 behavior_rule"
                }
            ]}
            """;
        OptReportIssueDto issue = parser.parse(json).topIssues().get(0);
        assertThat(issue.friction()).isEqualTo("repeated_tool_failure");
        assertThat(issue.recurrence()).isEqualTo(5);
        assertThat(issue.rootCause()).isEqualTo("agent 没在 cd 前确认目录");
        assertThat(issue.proposedFix()).isEqualTo("加 pwd 检查的 behavior_rule");
        assertThat(issue.suggestion()).isNull();  // omitted, allowed because rootCause present
    }

    @Test
    @DisplayName("G4: invalid friction value → IllegalArgumentException")
    void parse_invalidFriction_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5,
                  "suggestion": "y",
                  "friction": "tool_error"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("friction")
                .hasMessageContaining("tool_error");
    }

    @Test
    @DisplayName("G4: recurrence omitted → defaults to 1")
    void parse_recurrenceMissing_defaultsToOne() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5,
                  "suggestion": "y"
                }
            ]}
            """;
        OptReportIssueDto issue = parser.parse(json).topIssues().get(0);
        assertThat(issue.recurrence()).isEqualTo(1);
        assertThat(issue.friction()).isNull();        // optional
        assertThat(issue.rootCause()).isNull();       // optional (suggestion carries it)
    }

    @Test
    @DisplayName("G4: recurrence < 1 → IllegalArgumentException")
    void parse_recurrenceBelowOne_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5,
                  "suggestion": "y",
                  "recurrence": 0
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recurrence");
    }

    @Test
    @DisplayName("G4: both suggestion AND rootCause missing → IllegalArgumentException")
    void parse_noSuggestionNoRootCause_throws() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5,
                  "proposedFix": "改点东西"
                }
            ]}
            """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suggestion")
                .hasMessageContaining("rootCause");
    }

    @Test
    @DisplayName("optional expectedImpact blank → coerced to null")
    void parse_blankExpectedImpact_coercedToNull() {
        String json = """
            { "topIssues": [
                {
                  "id": "issue-1", "title": "x", "severity": "high",
                  "sessionCount": 1, "exampleSessionIds": ["a"],
                  "suspectSurface": "skill", "confidence": 0.5,
                  "suggestion": "y", "expectedImpact": "   "
                }
            ]}
            """;
        OptReportIssueDto issue = parser.parse(json).topIssues().get(0);
        assertThat(issue.expectedImpact()).isNull();
    }
}
