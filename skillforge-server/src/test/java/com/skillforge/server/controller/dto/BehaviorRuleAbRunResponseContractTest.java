package com.skillforge.server.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — FE-BE contract test for
 * {@link BehaviorRuleAbRunResponse} (per java.md known footgun #6 / #6b).
 *
 * <p>What this catches:
 * <ul>
 *   <li>Field rename on either side (BE rename → JSON shape drifts → FE sees
 *       null silently).</li>
 *   <li>Type change (Double → Long, etc.) that would silently fail FE
 *       deserialization.</li>
 *   <li>Outer envelope drift — this DTO must serialize as a flat object, NOT
 *       inside an envelope like {@code {"data":{...}}}. Jackson serializing
 *       the record directly is the only correct shape.</li>
 *   <li>Roundtrip: deserialize → reserialize must produce identical JSON
 *       (no silent field reordering / loss).</li>
 * </ul>
 */
@DisplayName("BehaviorRuleAbRunResponse FE-BE contract")
class BehaviorRuleAbRunResponseContractTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Mirror Spring Boot's default mapper. JavaTimeModule is mandatory
        // per java.md footgun #1 — Instant fields silently mis-serialize
        // without it.
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @DisplayName("JSON shape contains exactly the 20 expected fields (no extras, no missing)")
    @Test
    void json_shape_field_names_match_fe_interface() throws Exception {
        BehaviorRuleAbRunResponse resp = sample();
        String json = objectMapper.writeValueAsString(resp);
        JsonNode tree = objectMapper.readTree(json);

        // FE TS interface (src/api/behaviorRule.ts) MUST have these fields and
        // ONLY these. Any drift here surfaces as a FE deserialization gap.
        // FLYWHEEL-AB-AGENT-AWARE-DATASET V1 added ownerAgentRole (field #20).
        Set<String> expected = Set.of(
                "id", "agentId", "candidateVersionId", "status", "abRunKind",
                "baselinePassRate", "candidatePassRate", "deltaPassRate",
                "targetDeltaPp", "regressionDeltaPp",
                "targetCount", "regressionCount",
                "datasetVersionId", "promoted", "failureReason",
                "startedAt", "completedAt", "dualCriteriaSatisfied",
                "scenarioResults", "ownerAgentRole");
        Set<String> actual = new java.util.HashSet<>();
        tree.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @DisplayName("scenarioResults populates from abScenarioResultsJson when ObjectMapper overload used")
    @Test
    void scenario_results_populates_from_json() throws Exception {
        com.skillforge.server.entity.BehaviorRuleAbRunEntity e = sampleEntity();
        // Realistic per-scenario JSON shape produced by AbEvalPipeline.
        e.setAbScenarioResultsJson("""
                [{"scenarioId":"s-1","scenarioName":"S 1",
                  "baseline":{"status":"PENDING_JUDGE","oracleScore":0.3},
                  "candidate":{"status":"PENDING_JUDGE","oracleScore":0.8}}]
                """);
        BehaviorRuleAbRunResponse resp = BehaviorRuleAbRunResponse.from(e, objectMapper);
        assertThat(resp.scenarioResults()).hasSize(1);
        assertThat(resp.scenarioResults().get(0).scenarioId()).isEqualTo("s-1");
        assertThat(resp.scenarioResults().get(0).candidate().oracleScore()).isEqualTo(0.8);
    }

    @DisplayName("scenarioResults=null when JSON malformed (FE degrades; doesn't throw)")
    @Test
    void scenario_results_null_on_malformed_json() {
        com.skillforge.server.entity.BehaviorRuleAbRunEntity e = sampleEntity();
        e.setAbScenarioResultsJson("not json {");
        BehaviorRuleAbRunResponse resp = BehaviorRuleAbRunResponse.from(e, objectMapper);
        assertThat(resp.scenarioResults()).isNull();
    }

    @DisplayName("legacy from(entity) overload (no ObjectMapper) → scenarioResults=null")
    @Test
    void legacy_from_overload_returns_null_scenarios() {
        com.skillforge.server.entity.BehaviorRuleAbRunEntity e = sampleEntity();
        e.setAbScenarioResultsJson("[{\"scenarioId\":\"s-1\"}]");
        BehaviorRuleAbRunResponse resp = BehaviorRuleAbRunResponse.from(e);
        // Legacy overload skips parsing — backwards-compatible behavior for
        // older test callers / pure-projection contexts.
        assertThat(resp.scenarioResults()).isNull();
    }

    @DisplayName("type contract: Double/Integer/String/Instant types match TS")
    @Test
    void json_type_contract() throws Exception {
        BehaviorRuleAbRunResponse resp = sample();
        String json = objectMapper.writeValueAsString(resp);
        JsonNode tree = objectMapper.readTree(json);

        // strings
        assertThat(tree.get("id").isTextual()).isTrue();
        assertThat(tree.get("agentId").isTextual()).isTrue();
        assertThat(tree.get("candidateVersionId").isTextual()).isTrue();
        assertThat(tree.get("status").isTextual()).isTrue();
        assertThat(tree.get("abRunKind").isTextual()).isTrue();
        assertThat(tree.get("datasetVersionId").isTextual()).isTrue();
        // numbers (Double → number, Integer → number)
        assertThat(tree.get("baselinePassRate").isNumber()).isTrue();
        assertThat(tree.get("targetCount").isNumber()).isTrue();
        // booleans
        assertThat(tree.get("promoted").isBoolean()).isTrue();
        assertThat(tree.get("dualCriteriaSatisfied").isBoolean()).isTrue();
        // Instant → ISO string (NOT timestamp number — would crash FE)
        assertThat(tree.get("startedAt").isTextual()).isTrue();
        assertThat(tree.get("completedAt").isTextual()).isTrue();
    }

    @DisplayName("outer shape is a flat object (NOT wrapped in envelope)")
    @Test
    void no_envelope_wrapping() throws Exception {
        BehaviorRuleAbRunResponse resp = sample();
        String json = objectMapper.writeValueAsString(resp);
        // FE wrapper writes `api.get<BehaviorRuleAbRunResponse>(...)` then
        // reads `r.data` (single object). If we accidentally wrapped this in
        // {data: {...}} or {items: [...]} the FE would see `r.data.data` or
        // crash on `[...resp]`. Detect by asserting the root has `id`
        // directly, not nested under any wrapper key.
        JsonNode tree = objectMapper.readTree(json);
        assertThat(tree.has("id")).isTrue();
        assertThat(tree.has("data")).isFalse();
        assertThat(tree.has("items")).isFalse();
        assertThat(tree.isObject()).isTrue();
    }

    @DisplayName("roundtrip: JSON → Object → JSON produces identical content")
    @Test
    void roundtrip_byte_stable_after_normalization() throws Exception {
        BehaviorRuleAbRunResponse original = sample();
        String json1 = objectMapper.writeValueAsString(original);
        BehaviorRuleAbRunResponse parsed = objectMapper.readValue(json1, BehaviorRuleAbRunResponse.class);
        String json2 = objectMapper.writeValueAsString(parsed);
        // Normalize via JsonNode comparison to be robust against field order.
        assertThat(objectMapper.readTree(json2)).isEqualTo(objectMapper.readTree(json1));
        assertThat(parsed.id()).isEqualTo(original.id());
        assertThat(parsed.targetDeltaPp()).isEqualTo(original.targetDeltaPp());
        assertThat(parsed.dualCriteriaSatisfied()).isEqualTo(original.dualCriteriaSatisfied());
    }

    @DisplayName("nullables stay null in JSON (do NOT default to 0 / empty string)")
    @Test
    void nullables_render_as_null() throws Exception {
        BehaviorRuleAbRunEntity entity = new BehaviorRuleAbRunEntity();
        entity.setId("r1");
        entity.setAgentId("100");
        entity.setCandidateVersionId("v1");
        entity.setBaselineVersionId("");
        entity.setStatus(BehaviorRuleAbRunEntity.STATUS_PENDING);
        entity.setAbRunKind(BehaviorRuleAbRunEntity.KIND_WITH_VS_WITHOUT);
        // All Double / Integer / Instant fields left null.

        BehaviorRuleAbRunResponse resp = BehaviorRuleAbRunResponse.from(entity);
        String json = objectMapper.writeValueAsString(resp);
        JsonNode tree = objectMapper.readTree(json);

        assertThat(tree.get("targetDeltaPp").isNull()).isTrue();
        assertThat(tree.get("regressionDeltaPp").isNull()).isTrue();
        assertThat(tree.get("targetCount").isNull()).isTrue();
        assertThat(tree.get("baselinePassRate").isNull()).isTrue();
        assertThat(tree.get("completedAt").isNull()).isTrue();
        // Boolean promoted is primitive `false` on entity, so non-null here
        assertThat(tree.get("promoted").isBoolean()).isTrue();
    }

    private BehaviorRuleAbRunResponse sample() {
        return BehaviorRuleAbRunResponse.from(sampleEntity());
    }

    private BehaviorRuleAbRunEntity sampleEntity() {
        BehaviorRuleAbRunEntity e = new BehaviorRuleAbRunEntity();
        e.setId("ab-1");
        e.setAgentId("100");
        e.setCandidateVersionId("v1");
        e.setBaselineVersionId("");
        e.setStatus(BehaviorRuleAbRunEntity.STATUS_COMPLETED);
        e.setAbRunKind(BehaviorRuleAbRunEntity.KIND_WITH_VS_WITHOUT);
        e.setBaselinePassRate(40.0);
        e.setCandidatePassRate(55.0);
        e.setDeltaPassRate(15.0);
        e.setTargetDeltaPp(12.0);
        e.setRegressionDeltaPp(-1.0);
        e.setTargetCount(8);
        e.setRegressionCount(41);
        e.setDatasetVersionId("dv-1");
        e.setPromoted(false);
        e.setFailureReason(null);
        e.setStartedAt(Instant.parse("2026-05-24T10:00:00Z"));
        e.setCompletedAt(Instant.parse("2026-05-24T10:15:00Z"));
        return e;
    }
}
