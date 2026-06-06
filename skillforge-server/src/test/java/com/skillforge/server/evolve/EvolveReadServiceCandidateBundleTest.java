package com.skillforge.server.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.server.evolve.dto.CandidateBundle;
import com.skillforge.server.evolve.dto.EvolveIterationDto;
import com.skillforge.server.evolve.dto.EvolveRunDetailDto;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — {@link EvolveReadService} candidateBundle parsing:
 * the {@code candidateBundle} sidecar in {@code step_output_json} is surfaced on
 * {@link EvolveIterationDto}, null when absent or all-blank, and
 * {@link EvolveReadService#listKeptCandidateBundles} returns only kept bundles.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvolveReadService candidateBundle parsing")
class EvolveReadServiceCandidateBundleTest {

    @Mock private FlywheelRunRepository runRepository;
    @Mock private FlywheelRunStepRepository stepRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private SkillDraftRepository skillDraftRepository;

    private EvolveReadService service;

    private static final Instant NOW = Instant.parse("2026-06-03T10:00:00Z");
    private static final String RUN_ID = "run-1";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new EvolveReadService(runRepository, stepRepository, agentRepository,
                skillDraftRepository, objectMapper);
    }

    private void stubEvolveRun() {
        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId(RUN_ID);
        run.setAgentId(7L);
        run.setLoopKind(FlywheelRunEntity.LOOP_KIND_EVOLVE);
        run.setStatus("completed");
        run.setCreatedAt(NOW);
        run.setUpdatedAt(NOW);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(agentRepository.findById(7L)).thenReturn(Optional.empty());
    }

    private FlywheelRunStepEntity step(int index, String stepOutputJson) {
        FlywheelRunStepEntity s = new FlywheelRunStepEntity();
        s.setId("step-" + index);
        s.setRunId(RUN_ID);
        s.setStepKind(FlywheelRunStepEntity.STEP_KIND_EVOLVE_ITERATION);
        s.setStepIndex(index);
        s.setStepOutputJson(stepOutputJson);
        s.setCreatedAt(NOW);
        return s;
    }

    private void stubSteps(FlywheelRunStepEntity... steps) {
        when(stepRepository.findByRunIdAndStepKindOrderByStepIndexAsc(
                eq(RUN_ID), eq(FlywheelRunStepEntity.STEP_KIND_EVOLVE_ITERATION)))
                .thenReturn(List.of(steps));
    }

    @Test
    @DisplayName("parses candidateBundle pointers from step_output_json")
    void parse_candidateBundle_present() {
        stubEvolveRun();
        stubSteps(step(1, "{\"iteration\":1,\"surface\":\"agent\",\"changeDesc\":\"bundle\","
                + "\"candidateId\":\"cand-1\",\"kept\":true,"
                + "\"candidateBundle\":{\"promptVersionId\":\"pv-1\","
                + "\"behaviorRuleVersionId\":\"rv-1\",\"skillDraftId\":\"sd-1\"}}"));

        Optional<EvolveRunDetailDto> detailOpt = service.getRunDetail(RUN_ID);

        assertThat(detailOpt).isPresent();
        CandidateBundle bundle = detailOpt.get().iterations().get(0).candidateBundle();
        assertThat(bundle).isNotNull();
        assertThat(bundle.promptVersionId()).isEqualTo("pv-1");
        assertThat(bundle.behaviorRuleVersionId()).isEqualTo("rv-1");
        assertThat(bundle.skillDraftId()).isEqualTo("sd-1");
    }

    @Test
    @DisplayName("candidateBundle null when the sidecar is absent")
    void parse_candidateBundle_absent_null() {
        stubEvolveRun();
        stubSteps(step(1, "{\"iteration\":1,\"surface\":\"prompt\",\"changeDesc\":\"x\","
                + "\"candidateId\":\"cand-1\",\"kept\":true}"));

        EvolveIterationDto iter = service.getRunDetail(RUN_ID).get().iterations().get(0);
        assertThat(iter.candidateBundle()).isNull();
    }

    @Test
    @DisplayName("candidateBundle null when all pointers are blank")
    void parse_candidateBundle_allBlank_null() {
        stubEvolveRun();
        stubSteps(step(1, "{\"iteration\":1,\"surface\":\"agent\",\"changeDesc\":\"x\","
                + "\"candidateId\":\"cand-1\",\"kept\":true,"
                + "\"candidateBundle\":{\"promptVersionId\":\"\",\"behaviorRuleVersionId\":null}}"));

        EvolveIterationDto iter = service.getRunDetail(RUN_ID).get().iterations().get(0);
        assertThat(iter.candidateBundle()).isNull();
    }

    @Test
    @DisplayName("candidateBundle null when sidecar is a non-object text fallback")
    void parse_candidateBundle_textFallback_null() {
        stubEvolveRun();
        stubSteps(step(1, "{\"iteration\":1,\"surface\":\"agent\",\"changeDesc\":\"x\","
                + "\"candidateId\":\"cand-1\",\"kept\":true,"
                + "\"candidateBundle\":\"not-an-object\"}"));

        EvolveIterationDto iter = service.getRunDetail(RUN_ID).get().iterations().get(0);
        assertThat(iter.candidateBundle()).isNull();
    }

    @Test
    @DisplayName("listKeptCandidateBundles returns only kept iterations' bundles")
    void listKeptCandidateBundles_filtersKept() {
        stubEvolveRun();
        stubSteps(
                // kept=false → excluded even though it has a bundle
                step(1, "{\"iteration\":1,\"surface\":\"agent\",\"changeDesc\":\"a\","
                        + "\"candidateId\":\"c1\",\"kept\":false,"
                        + "\"candidateBundle\":{\"promptVersionId\":\"pv-x\"}}"),
                // kept=true with bundle → included
                step(2, "{\"iteration\":2,\"surface\":\"agent\",\"changeDesc\":\"b\","
                        + "\"candidateId\":\"c2\",\"kept\":true,"
                        + "\"candidateBundle\":{\"promptVersionId\":\"pv-1\",\"skillDraftId\":\"sd-1\"}}"),
                // kept=true but no bundle → excluded (null filtered)
                step(3, "{\"iteration\":3,\"surface\":\"prompt\",\"changeDesc\":\"c\","
                        + "\"candidateId\":\"c3\",\"kept\":true}"));

        List<CandidateBundle> kept = service.listKeptCandidateBundles(RUN_ID);

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).promptVersionId()).isEqualTo("pv-1");
        assertThat(kept.get(0).skillDraftId()).isEqualTo("sd-1");
    }

    @Test
    @DisplayName("listKeptCandidateBundles empty for an unknown / non-evolve run")
    void listKeptCandidateBundles_unknownRun_empty() {
        when(runRepository.findById("nope")).thenReturn(Optional.empty());
        assertThat(service.listKeptCandidateBundles("nope")).isEmpty();
    }

    // ── Phase 2a: semanticDelta passthrough (object single-surface / array multi-surface) ──

    @Test
    @DisplayName("semanticDelta object (single-surface) passes through verbatim")
    void semanticDelta_object_passthrough() {
        stubEvolveRun();
        stubSteps(step(1, "{\"iteration\":1,\"surface\":\"agent\",\"changeDesc\":\"x\","
                + "\"candidateId\":\"cand-1\",\"kept\":true,"
                + "\"semanticDelta\":{\"surface\":\"prompt\",\"before\":\"b\",\"after\":\"a\","
                + "\"diff\":\"d\",\"changeDesc\":\"x\"}}"));

        EvolveIterationDto iter = service.getRunDetail(RUN_ID).get().iterations().get(0);
        assertThat(iter.semanticDelta()).isNotNull();
        assertThat(iter.semanticDelta().isObject()).isTrue();
        assertThat(iter.semanticDelta().get("surface").asText()).isEqualTo("prompt");
    }

    @Test
    @DisplayName("semanticDelta array (multi-surface) passes through verbatim (Phase 2a)")
    void semanticDelta_array_passthrough() {
        stubEvolveRun();
        stubSteps(step(1, "{\"iteration\":1,\"surface\":\"agent\",\"changeDesc\":\"x\","
                + "\"candidateId\":\"cand-1\",\"kept\":true,"
                + "\"semanticDelta\":[{\"surface\":\"prompt\",\"before\":\"b1\",\"after\":\"a1\"},"
                + "{\"surface\":\"behavior_rule\",\"before\":\"b2\",\"after\":\"a2\"}]}"));

        EvolveIterationDto iter = service.getRunDetail(RUN_ID).get().iterations().get(0);
        assertThat(iter.semanticDelta()).isNotNull();
        assertThat(iter.semanticDelta().isArray()).isTrue();
        assertThat(iter.semanticDelta()).hasSize(2);
        assertThat(iter.semanticDelta().get(0).get("surface").asText()).isEqualTo("prompt");
        assertThat(iter.semanticDelta().get(1).get("surface").asText()).isEqualTo("behavior_rule");
    }

    @Test
    @DisplayName("semanticDelta null when sidecar is a non-object/array text fallback")
    void semanticDelta_textFallback_null() {
        stubEvolveRun();
        stubSteps(step(1, "{\"iteration\":1,\"surface\":\"agent\",\"changeDesc\":\"x\","
                + "\"candidateId\":\"cand-1\",\"kept\":true,"
                + "\"semanticDelta\":\"not-structured\"}"));

        EvolveIterationDto iter = service.getRunDetail(RUN_ID).get().iterations().get(0);
        assertThat(iter.semanticDelta()).isNull();
    }

    // ── r1 F1: subSessionId correlation safe-degrade ──

    @Test
    @DisplayName("F1: subSessionId correlated by iteration order when candidate-step count matches ledger count")
    void subSessionId_correlated_whenCountsMatch() {
        stubEvolveRun();
        stubSteps(
                step(1, "{\"iteration\":1,\"surface\":\"prompt\",\"changeDesc\":\"a\",\"candidateId\":\"c1\",\"kept\":true}"),
                step(2, "{\"iteration\":2,\"surface\":\"prompt\",\"changeDesc\":\"b\",\"candidateId\":\"c2\",\"kept\":false}"));
        stubCandidateSteps(candidateStep(0, "sub-1"), candidateStep(6, "sub-2"));

        List<EvolveIterationDto> iters = service.getRunDetail(RUN_ID).get().iterations();
        assertThat(iters.get(0).subSessionId()).isEqualTo("sub-1");
        assertThat(iters.get(1).subSessionId()).isEqualTo("sub-2");
    }

    @Test
    @DisplayName("F1: subSessionId degrades to null (never WRONG) when candidate-step count != ledger count")
    void subSessionId_safeNull_whenCountsMismatch() {
        stubEvolveRun();
        // 1 ledger row but 2 candidate dispatch steps (e.g. run errored mid-iteration):
        // positional zip would mis-point — must degrade to null, not the wrong session.
        stubSteps(
                step(1, "{\"iteration\":1,\"surface\":\"prompt\",\"changeDesc\":\"a\",\"candidateId\":\"c1\",\"kept\":true}"));
        stubCandidateSteps(candidateStep(0, "sub-1"), candidateStep(6, "sub-2"));

        EvolveIterationDto iter = service.getRunDetail(RUN_ID).get().iterations().get(0);
        assertThat(iter.subSessionId()).isNull();
    }

    private FlywheelRunStepEntity candidateStep(int index, String subSessionId) {
        FlywheelRunStepEntity s = new FlywheelRunStepEntity();
        s.setId("disp-" + index);
        s.setRunId(RUN_ID);
        s.setStepKind(FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH);
        s.setStepIndex(index);
        s.setSubAgentSessionId(subSessionId);
        s.setCreatedAt(NOW);
        return s;
    }

    private void stubCandidateSteps(FlywheelRunStepEntity... steps) {
        when(stepRepository.findByRunIdAndStepKindOrderByStepIndexAsc(
                eq(RUN_ID), eq(FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH)))
                .thenReturn(List.of(steps));
    }
}
