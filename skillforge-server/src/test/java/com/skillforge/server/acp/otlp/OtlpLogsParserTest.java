package com.skillforge.server.acp.otlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OtlpLogsParser} — the PURE OTLP-JSON logs shape parser
 * (ACP-EXTERNAL-AGENT P2-1). Payloads mirror the spike-captured shape
 * (/tmp/acp-spike/otel-spike.mjs): resourceLogs → scopeLogs → logRecords, with
 * the injected {@code sf.session_id} resource attribute.
 */
class OtlpLogsParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OtlpLogsParser parser = new OtlpLogsParser();

    private JsonNode tree(String json) throws Exception {
        return mapper.readTree(json);
    }

    /** A captured-shape OTLP-JSON logs payload with four cc event types. */
    private String capturedPayload() {
        return """
            {
              "resourceLogs": [
                {
                  "resource": {
                    "attributes": [
                      {"key": "sf.session_id", "value": {"stringValue": "sub-1"}},
                      {"key": "sf.agent_id", "value": {"stringValue": "23"}},
                      {"key": "session.id", "value": {"stringValue": "cc-abc"}},
                      {"key": "user.email", "value": {"stringValue": "secret@example.com"}},
                      {"key": "user.account_uuid", "value": {"stringValue": "uuid-xyz"}}
                    ]
                  },
                  "scopeLogs": [
                    {
                      "logRecords": [
                        {
                          "timeUnixNano": "1700000000000000000",
                          "body": {"stringValue": "claude_code.api_request"},
                          "attributes": [
                            {"key": "event.name", "value": {"stringValue": "api_request"}},
                            {"key": "event.sequence", "value": {"intValue": "5"}},
                            {"key": "model", "value": {"stringValue": "claude-opus"}},
                            {"key": "input_tokens", "value": {"intValue": "100"}},
                            {"key": "output_tokens", "value": {"intValue": "50"}},
                            {"key": "cost_usd", "value": {"doubleValue": 0.012}},
                            {"key": "duration_ms", "value": {"intValue": "1234"}},
                            {"key": "agent.name", "value": {"stringValue": "code-reviewer"}}
                          ]
                        },
                        {
                          "body": {"stringValue": "claude_code.tool_result"},
                          "attributes": [
                            {"key": "tool_name", "value": {"stringValue": "Bash"}},
                            {"key": "tool_use_id", "value": {"stringValue": "tu-1"}},
                            {"key": "success", "value": {"boolValue": true}},
                            {"key": "duration_ms", "value": {"intValue": "42"}},
                            {"key": "tool_result_size_bytes", "value": {"intValue": "2048"}}
                          ]
                        },
                        {
                          "body": {"stringValue": "claude_code.subagent_completed"},
                          "attributes": [
                            {"key": "agent_type", "value": {"stringValue": "general-purpose"}},
                            {"key": "total_tokens", "value": {"intValue": "5000"}},
                            {"key": "total_tool_uses", "value": {"intValue": "8"}},
                            {"key": "model", "value": {"stringValue": "claude-sonnet"}}
                          ]
                        },
                        {
                          "body": {"stringValue": "claude_code.user_prompt"},
                          "attributes": [
                            {"key": "prompt.id", "value": {"stringValue": "p-1"}},
                            {"key": "prompt_length", "value": {"intValue": "37"}},
                            {"key": "prompt", "value": {"stringValue": "my secret prompt content"}}
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;
    }

    @Test
    @DisplayName("parses all four event types, binds sf.session_id, merges resource attrs")
    void parse_capturedShape() throws Exception {
        List<ParsedCcEvent> events = parser.parse(tree(capturedPayload()));

        assertThat(events).hasSize(4);
        assertThat(events).extracting(ParsedCcEvent::eventName).containsExactly(
                "claude_code.api_request",
                "claude_code.tool_result",
                "claude_code.subagent_completed",
                "claude_code.user_prompt");
        // every event gets the resource-level sf.session_id + cc session id.
        assertThat(events).allSatisfy(e -> {
            assertThat(e.sfSessionId()).isEqualTo("sub-1");
            assertThat(e.ccSessionId()).isEqualTo("cc-abc");
        });

        ParsedCcEvent api = events.get(0);
        assertThat(api.eventSeq()).isEqualTo(5L);
        assertThat(api.ts()).isNotNull();
        assertThat(api.attributes()).containsEntry("model", "claude-opus");
        assertThat(api.attributes()).containsEntry("input_tokens", 100L);
        assertThat(api.attributes()).containsEntry("agent.name", "code-reviewer");
        // raw (pre-filter) attrs still carry PII — filtering is the ingest layer's job.
        assertThat(api.attributes()).containsEntry("user.email", "secret@example.com");
    }

    @Test
    @DisplayName("intValue/doubleValue/boolValue are normalized to long/double/boolean")
    void parse_valueTypes() throws Exception {
        ParsedCcEvent tool = parser.parse(tree(capturedPayload())).get(1);
        assertThat(tool.attributes()).containsEntry("tool_name", "Bash");
        assertThat(tool.attributes()).containsEntry("success", true);
        assertThat(tool.attributes()).containsEntry("duration_ms", 42L);
        assertThat(tool.attributes()).containsEntry("tool_result_size_bytes", 2048L);
    }

    @Test
    @DisplayName("event with no resolvable name (no body, no event.name attr) is skipped")
    void parse_skipsNamelessRecord() throws Exception {
        String json = """
            {"resourceLogs":[{"resource":{"attributes":[]},"scopeLogs":[{"logRecords":[
              {"attributes":[{"key":"model","value":{"stringValue":"x"}}]}
            ]}]}]}
            """;
        assertThat(parser.parse(tree(json))).isEmpty();
    }

    @Test
    @DisplayName("event.name attribute is used when body.stringValue is absent")
    void parse_fallsBackToEventNameAttr() throws Exception {
        String json = """
            {"resourceLogs":[{"resource":{"attributes":[
              {"key":"sf.session_id","value":{"stringValue":"s9"}}]},
              "scopeLogs":[{"logRecords":[
              {"attributes":[{"key":"event.name","value":{"stringValue":"claude_code.tool_decision"}}]}
            ]}]}]}
            """;
        List<ParsedCcEvent> events = parser.parse(tree(json));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventName()).isEqualTo("claude_code.tool_decision");
        assertThat(events.get(0).sfSessionId()).isEqualTo("s9");
    }

    @Test
    @DisplayName("null / empty / malformed payloads return empty (never throw)")
    void parse_defensive() throws Exception {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse(tree("{}"))).isEmpty();
        assertThat(parser.parse(tree("{\"resourceLogs\":\"notArray\"}"))).isEmpty();
        assertThat(parser.parse(tree("{\"resourceLogs\":[{}]}"))).isEmpty();
    }

    @Test
    @DisplayName("event without sf.session_id resource attr parses with null sfSessionId (ingest drops it)")
    void parse_missingSfSessionId() throws Exception {
        String json = """
            {"resourceLogs":[{"resource":{"attributes":[]},"scopeLogs":[{"logRecords":[
              {"body":{"stringValue":"claude_code.api_request"},"attributes":[]}
            ]}]}]}
            """;
        List<ParsedCcEvent> events = parser.parse(tree(json));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).sfSessionId()).isNull();
    }
}
