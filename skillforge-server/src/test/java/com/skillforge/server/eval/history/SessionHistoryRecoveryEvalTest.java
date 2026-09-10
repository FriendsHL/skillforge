package com.skillforge.server.eval.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.history.SessionHistoryReadResponse;
import com.skillforge.server.history.SessionHistoryToolSchemas;
import com.skillforge.server.history.SessionHistoryWireFormatter;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionHistoryRecoveryEvalTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fixtureRunner_coversS1ToS25AcrossOrthogonalArmsAndRecoveryVariants() throws Exception {
        SessionHistoryRecoveryEvalFixture fixture = SessionHistoryRecoveryEvalFixture.load(objectMapper);
        int schemaTokens = TokenEstimator.estimateString(objectMapper.writeValueAsString(List.of(
                SessionHistoryToolSchemas.search(), SessionHistoryToolSchemas.read())));
        String wire = representativeWire();

        SessionHistoryRecoveryEvalRunner.Report report = new SessionHistoryRecoveryEvalRunner()
                .run(fixture, schemaTokens, wire.length());

        System.out.printf(
                "SESSION_HISTORY_EVAL cases=%d A=%.2f C=%.2f D=%.2f C-A=%.2f D-C=%.2f "
                        + "refs=%.2f leakage=%d unnecessary=%.2f s25_drop=%.2f "
                        + "schema_tokens=%d wire_chars=%d%n",
                report.cases().size(),
                report.exactRecoveryPercent().get(SessionHistoryRecoveryEvalRunner.Arm.A),
                report.exactRecoveryPercent().get(SessionHistoryRecoveryEvalRunner.Arm.C),
                report.exactRecoveryPercent().get(SessionHistoryRecoveryEvalRunner.Arm.D),
                report.cMinusA(), report.dMinusC(), report.refCorrectnessPercent(),
                report.leakageCount(), report.unnecessaryHistoryPercent(),
                report.s25QualityDropPoints(), report.schemaOverheadTokensP95(),
                report.maxHistoryWireChars());

        assertThat(report.passed()).as(report.failures().toString()).isTrue();
        assertThat(report.cases()).hasSize(25 * 4 * 3);
        assertThat(report.cases()).extracting(SessionHistoryRecoveryEvalRunner.CaseResult::arm)
                .containsExactlyInAnyOrderElementsOf(repeatedArms());
        assertThat(report.cases()).extracting(SessionHistoryRecoveryEvalRunner.CaseResult::recovery)
                .contains(SessionHistoryRecoveryEvalRunner.RecoveryVariant.values());
        assertThat(report.exactRecoveryPercent().get(SessionHistoryRecoveryEvalRunner.Arm.D)
                - report.exactRecoveryPercent().get(SessionHistoryRecoveryEvalRunner.Arm.A))
                .isGreaterThanOrEqualTo(20.0);
        assertThat(report.refCorrectnessPercent()).isGreaterThanOrEqualTo(98.0);
        assertThat(report.leakageCount()).isZero();
        assertThat(report.unnecessaryHistoryPercent()).isLessThan(10.0);
        assertThat(report.s25QualityDropPoints()).isLessThanOrEqualTo(2.0);
        assertThat(report.schemaOverheadTokensP95())
                .isLessThanOrEqualTo(report.schemaOverheadGateTokens());
        assertThat(report.maxHistoryWireChars()).isLessThanOrEqualTo(32_000);
    }

    @Test
    void metadataAndSensitiveScenarios_lockVersionsAndAuthorityPaths() throws Exception {
        SessionHistoryRecoveryEvalFixture fixture = SessionHistoryRecoveryEvalFixture.load(objectMapper);
        SessionHistoryRecoveryEvalRunner.Report report = new SessionHistoryRecoveryEvalRunner()
                .run(fixture, 1, 1);

        assertThat(report.metadata().model()).isEqualTo("scripted-history-agent-v1");
        assertThat(report.metadata().provider()).isEqualTo("deterministic-fixture");
        assertThat(report.metadata().contextWindowTokens()).isEqualTo(128_000);
        assertThat(report.metadata().promptVersion()).isEqualTo("session-history-eval-prompt-v1");
        assertThat(report.metadata().fixtureVersion()).isEqualTo("session-history-s1-s25-v1");
        assertThat(report.metadata().projectionVersion()).isEqualTo(com.skillforge.server.history.query.HistoryAuthorizedProjection.VERSION);
        assertThat(SessionHistoryRecoveryEvalRunner.Arm.A.hasTail()).isFalse();
        assertThat(SessionHistoryRecoveryEvalRunner.Arm.B.hasTail()).isTrue();
        assertThat(SessionHistoryRecoveryEvalRunner.Arm.B.hasLegacyRecent()).isTrue();
        assertThat(SessionHistoryRecoveryEvalRunner.Arm.C.supportsSystemHistory()).isTrue();
        assertThat(SessionHistoryRecoveryEvalRunner.Arm.C.hasDeterministicCue()).isFalse();
        assertThat(SessionHistoryRecoveryEvalRunner.Arm.D.hasDeterministicCue()).isTrue();
        assertThat(SessionHistoryRecoveryEvalRunner.RecoveryVariant.R0.evaluatorAttachment())
                .isEqualTo("NONE");
        assertThat(SessionHistoryRecoveryEvalRunner.RecoveryVariant.R1.evaluatorAttachment())
                .isEqualTo("REFERENCE_ONLY");
        assertThat(SessionHistoryRecoveryEvalRunner.RecoveryVariant.R2.evaluatorAttachment())
                .isEqualTo("SANITIZED_LEGACY");

        assertNoHistory(report, "S1");
        assertNoHistory(report, "S25");
        assertThat(cases(report, "S15"))
                .allMatch(value -> value.route() == SessionHistoryRecoveryEvalRunner.Route.FILE_READ)
                .extracting(SessionHistoryRecoveryEvalRunner.CaseResult::recovery)
                .contains(SessionHistoryRecoveryEvalRunner.RecoveryVariant.values());
        assertThat(cases(report, "S16"))
                .allMatch(value -> value.route() == SessionHistoryRecoveryEvalRunner.Route.TASK_LIST);
        assertThat(cases(report, "S20"))
                .allMatch(value -> value.route() == SessionHistoryRecoveryEvalRunner.Route.SERVER_SCOPE)
                .allMatch(value -> !value.leakage());
        assertThat(report.cases()).allMatch(value -> !value.productionRecoveryInjected());
    }

    private String representativeWire() {
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setMaxProviderWireChars(32_000);
        SessionHistoryWireFormatter formatter = new SessionHistoryWireFormatter(objectMapper, properties);
        return formatter.format(new SessionHistoryReadResponse(
                1,
                List.of(new SessionHistoryReadResponse.Event(
                        "msg:e0:id1:block0", "ORIGINAL", "TEXT", "USER", 1,
                        null, null, "中😀".repeat(6_000), 0, true, "authorized-sha")),
                null, true));
    }

    private static List<SessionHistoryRecoveryEvalRunner.Arm> repeatedArms() {
        return java.util.stream.IntStream.range(0, 75)
                .boxed()
                .flatMap(ignored -> EnumSet.allOf(SessionHistoryRecoveryEvalRunner.Arm.class).stream())
                .toList();
    }

    private static List<SessionHistoryRecoveryEvalRunner.CaseResult> cases(
            SessionHistoryRecoveryEvalRunner.Report report, String scenarioId) {
        return report.cases().stream()
                .filter(value -> value.scenarioId().equals(scenarioId)).toList();
    }

    private static void assertNoHistory(
            SessionHistoryRecoveryEvalRunner.Report report, String scenarioId) {
        assertThat(cases(report, scenarioId)).allMatch(value -> value.historyCalls() == 0);
    }
}
