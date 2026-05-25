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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 — focused contract test for the new
 * {@code ownerAgentRole} field on {@link BehaviorRuleAbRunResponse}.
 *
 * <p>This test complements {@link BehaviorRuleAbRunResponseContractTest}
 * (which covers the full 20-field shape + outer envelope + Instant types).
 * Here we exercise the THREE entry points to the DTO factory and assert
 * that {@code ownerAgentRole} behaves correctly in each:
 * <ol>
 *   <li>3-arg {@code from(entity, mapper, ownerAgentRole)} — pure mapper,
 *       role passed through.</li>
 *   <li>2-arg {@code from(entity, mapper)} — backwards-compat, role=null.</li>
 *   <li>1-arg {@code from(entity)} — legacy, role=null + scenarios=null.</li>
 * </ol>
 *
 * <p><b>java.md footgun #6 / #6b</b>: DTO is the FE-BE contract surface. r1-FIX
 * (java-design W1): {@code from()} stays a PURE MAPPER — no
 * {@code AgentRepository} dependency. Tests here construct DTOs directly from
 * an entity + a String role (no repo mock), proving the abstraction stayed
 * clean.
 */
@DisplayName("BehaviorRuleAbRunResponse — ownerAgentRole field (FLYWHEEL V1)")
class BehaviorRuleAbRunResponseRoleFieldTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @DisplayName("3-arg from(): ownerAgentRole flows from caller into DTO + JSON")
    @Test
    void three_arg_factory_propagates_role() throws Exception {
        BehaviorRuleAbRunResponse resp = BehaviorRuleAbRunResponse.from(
                sampleEntity(), objectMapper, "design");
        assertThat(resp).isNotNull();
        assertThat(resp.ownerAgentRole()).isEqualTo("design");

        String json = objectMapper.writeValueAsString(resp);
        JsonNode tree = objectMapper.readTree(json);
        assertThat(tree.get("ownerAgentRole").isTextual()).isTrue();
        assertThat(tree.get("ownerAgentRole").asText()).isEqualTo("design");
    }

    @DisplayName("2-arg from(): ownerAgentRole defaults to null (backwards-compat)")
    @Test
    void two_arg_factory_defaults_role_null() throws Exception {
        BehaviorRuleAbRunResponse resp = BehaviorRuleAbRunResponse.from(
                sampleEntity(), objectMapper);
        assertThat(resp).isNotNull();
        assertThat(resp.ownerAgentRole()).isNull();

        String json = objectMapper.writeValueAsString(resp);
        JsonNode tree = objectMapper.readTree(json);
        // null still serializes (as JSON null) — FE sees null cleanly
        assertThat(tree.has("ownerAgentRole")).isTrue();
        assertThat(tree.get("ownerAgentRole").isNull()).isTrue();
    }

    @DisplayName("1-arg from(): ownerAgentRole + scenarioResults both null (legacy)")
    @Test
    void one_arg_factory_defaults_both_null() {
        BehaviorRuleAbRunResponse resp = BehaviorRuleAbRunResponse.from(sampleEntity());
        assertThat(resp).isNotNull();
        assertThat(resp.ownerAgentRole()).isNull();
        assertThat(resp.scenarioResults()).isNull();
    }

    @DisplayName("null entity → null DTO (no NPE) even with role passed in")
    @Test
    void null_entity_returns_null_dto() {
        assertThat(BehaviorRuleAbRunResponse.from(null, objectMapper, "design")).isNull();
        assertThat(BehaviorRuleAbRunResponse.from(null, objectMapper)).isNull();
        assertThat(BehaviorRuleAbRunResponse.from(null)).isNull();
    }

    @DisplayName("ownerAgentRole roundtrips via JSON without loss")
    @Test
    void role_roundtrip_byte_stable() throws Exception {
        // Test each of the 5 closed-set role values through serialize/deserialize.
        for (String role : new String[]{"general", "code", "design", "research", "main_assistant"}) {
            BehaviorRuleAbRunResponse original = BehaviorRuleAbRunResponse.from(
                    sampleEntity(), objectMapper, role);
            String json = objectMapper.writeValueAsString(original);
            BehaviorRuleAbRunResponse parsed = objectMapper.readValue(
                    json, BehaviorRuleAbRunResponse.class);
            assertThat(parsed.ownerAgentRole()).isEqualTo(role);
            // Whole-record roundtrip: re-serialize and compare JSON trees
            assertThat(objectMapper.readTree(objectMapper.writeValueAsString(parsed)))
                    .isEqualTo(objectMapper.readTree(json));
        }
    }

    // r2-BE-3 (WARN-3): removed weak json_shape_contains_20_fields test.
    // Strict field-name assertion lives in BehaviorRuleAbRunResponseContractTest
    // .json_shape_field_names_match_fe_interface (containsExactlyInAnyOrder).
    // A count-only assertion would silently pass on field-rename drift, so
    // it was duplicative of the contract test AND weaker — net negative.

    private BehaviorRuleAbRunEntity sampleEntity() {
        BehaviorRuleAbRunEntity e = new BehaviorRuleAbRunEntity();
        e.setId("ab-role-1");
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
        e.setStartedAt(Instant.parse("2026-05-25T10:00:00Z"));
        e.setCompletedAt(Instant.parse("2026-05-25T10:15:00Z"));
        return e;
    }
}
