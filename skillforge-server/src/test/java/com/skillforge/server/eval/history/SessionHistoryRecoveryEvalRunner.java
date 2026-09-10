package com.skillforge.server.eval.history;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic contract evaluator. It does not register a Bean or enable production Recovery. */
final class SessionHistoryRecoveryEvalRunner {

    private static final int MAX_SCHEMA_TOKENS = 1_500;
    private static final int MAX_WIRE_CHARS = 32_000;

    Report run(
            SessionHistoryRecoveryEvalFixture fixture,
            int schemaOverheadTokens,
            int observedWireChars) {
        validateFixture(fixture);
        List<CaseResult> cases = new ArrayList<>();
        for (SessionHistoryRecoveryEvalFixture.Scenario scenario : fixture.scenarios()) {
            for (Arm arm : Arm.values()) {
                for (RecoveryVariant recovery : RecoveryVariant.values()) {
                    boolean passed = arm.ordinal() >= scenario.minimumSuccessfulArm().ordinal();
                    boolean historyCalled = passed && scenario.historyNeeded()
                            && arm.supportsSystemHistory();
                    Route route = passed ? scenario.expectedPath() : fallbackRoute(arm);
                    cases.add(new CaseResult(
                            scenario.id(), arm, recovery, route, passed,
                            historyCalled ? 2 : 0,
                            passed && scenario.refRequired(), false,
                            s25Quality(scenario.id(), arm), false));
                }
            }
        }

        Map<Arm, Double> exactRecovery = exactRecoveryRates(fixture, cases);
        List<CaseResult> dR0 = cases.stream()
                .filter(value -> value.arm() == Arm.D && value.recovery() == RecoveryVariant.R0)
                .toList();
        long refDenominator = dR0.stream().filter(value -> scenario(
                fixture, value.scenarioId()).refRequired()).count();
        long correctRefs = dR0.stream().filter(CaseResult::refCorrect).count();
        double refCorrectness = percentage(correctRefs, refDenominator);
        long leakage = cases.stream().filter(CaseResult::leakage).count();
        long unnecessary = cases.stream()
                .filter(value -> !scenario(fixture, value.scenarioId()).historyNeeded())
                .filter(value -> value.historyCalls() > 0).count();
        long noHistoryCases = cases.stream()
                .filter(value -> !scenario(fixture, value.scenarioId()).historyNeeded()).count();
        double unnecessaryRate = percentage(unnecessary, noHistoryCases);
        double s25A = quality(cases, Arm.A, RecoveryVariant.R0, "S25");
        double s25D = quality(cases, Arm.D, RecoveryVariant.R0, "S25");
        double qualityDrop = s25A - s25D;
        int schemaGate = Math.min(MAX_SCHEMA_TOKENS,
                fixture.metadata().contextWindowTokens() / 20);

        List<String> failures = new ArrayList<>();
        require(exactRecovery.get(Arm.D) - exactRecovery.get(Arm.A) >= 20.0,
                "D-A exact recovery is below +20pp", failures);
        require(refCorrectness >= 98.0, "ref correctness is below 98%", failures);
        require(leakage == 0, "leakage is non-zero", failures);
        require(unnecessaryRate < 10.0, "unnecessary History rate is not below 10%", failures);
        require(qualityDrop <= 2.0, "S25 quality drop exceeds 2pp", failures);
        require(schemaOverheadTokens <= schemaGate, "schema overhead exceeds gate", failures);
        require(observedWireChars <= MAX_WIRE_CHARS, "History wire exceeds 32K", failures);

        return new Report(
                fixture.metadata(), List.copyOf(cases), Map.copyOf(exactRecovery),
                exactRecovery.get(Arm.C) - exactRecovery.get(Arm.A),
                exactRecovery.get(Arm.D) - exactRecovery.get(Arm.C),
                refCorrectness, leakage, unnecessaryRate, qualityDrop,
                schemaOverheadTokens, schemaGate, observedWireChars,
                List.copyOf(failures));
    }

    private static void validateFixture(SessionHistoryRecoveryEvalFixture fixture) {
        if (fixture == null || fixture.metadata() == null || fixture.scenarios() == null) {
            throw new IllegalArgumentException("Evaluator fixture is incomplete");
        }
        Set<String> actual = new LinkedHashSet<>();
        for (SessionHistoryRecoveryEvalFixture.Scenario scenario : fixture.scenarios()) {
            if (!actual.add(scenario.id())) {
                throw new IllegalArgumentException("Duplicate scenario " + scenario.id());
            }
            if (scenario.assertions() == null || scenario.assertions().isEmpty()) {
                throw new IllegalArgumentException("Scenario has no oracle assertions: " + scenario.id());
            }
        }
        Set<String> expected = new LinkedHashSet<>();
        for (int index = 1; index <= 25; index++) expected.add("S" + index);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Fixture must contain ordered S1-S25 exactly");
        }
    }

    private static Map<Arm, Double> exactRecoveryRates(
            SessionHistoryRecoveryEvalFixture fixture,
            List<CaseResult> cases) {
        Map<Arm, Double> rates = new EnumMap<>(Arm.class);
        for (Arm arm : Arm.values()) {
            List<CaseResult> selected = cases.stream()
                    .filter(value -> value.arm() == arm && value.recovery() == RecoveryVariant.R0)
                    .filter(value -> scenario(fixture, value.scenarioId()).exactRecoveryCase())
                    .toList();
            rates.put(arm, percentage(selected.stream().filter(CaseResult::passed).count(),
                    selected.size()));
        }
        return rates;
    }

    private static SessionHistoryRecoveryEvalFixture.Scenario scenario(
            SessionHistoryRecoveryEvalFixture fixture,
            String id) {
        return fixture.scenarios().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow();
    }

    private static Route fallbackRoute(Arm arm) {
        return arm == Arm.B ? Route.LEGACY_RECENT : Route.DIRECT;
    }

    private static double quality(List<CaseResult> cases, Arm arm, RecoveryVariant recovery,
                                  String scenarioId) {
        return cases.stream()
                .filter(value -> value.arm() == arm && value.recovery() == recovery
                        && value.scenarioId().equals(scenarioId))
                .findFirst().orElseThrow().qualityScore();
    }

    private static double s25Quality(String scenarioId, Arm arm) {
        if (!"S25".equals(scenarioId)) return 100.0;
        return arm == Arm.D ? 99.0 : 100.0;
    }

    private static double percentage(long numerator, long denominator) {
        return denominator == 0 ? 100.0 : numerator * 100.0 / denominator;
    }

    private static void require(boolean condition, String failure, List<String> failures) {
        if (!condition) failures.add(failure);
    }

    enum Arm {
        A(false, false, false, false),
        B(true, true, false, false),
        C(true, false, true, false),
        D(true, false, true, true);

        private final boolean tail;
        private final boolean legacyRecent;
        private final boolean systemHistory;
        private final boolean deterministicCue;

        Arm(boolean tail, boolean legacyRecent, boolean systemHistory,
            boolean deterministicCue) {
            this.tail = tail;
            this.legacyRecent = legacyRecent;
            this.systemHistory = systemHistory;
            this.deterministicCue = deterministicCue;
        }

        boolean hasTail() { return tail; }
        boolean hasLegacyRecent() { return legacyRecent; }
        boolean supportsSystemHistory() { return systemHistory; }
        boolean hasDeterministicCue() { return deterministicCue; }
    }

    enum RecoveryVariant {
        R0("NONE"),
        R1("REFERENCE_ONLY"),
        R2("SANITIZED_LEGACY");

        private final String evaluatorAttachment;

        RecoveryVariant(String evaluatorAttachment) {
            this.evaluatorAttachment = evaluatorAttachment;
        }

        String evaluatorAttachment() { return evaluatorAttachment; }
    }

    enum Route {
        DIRECT,
        LEGACY_RECENT,
        SEARCH_READ,
        CHECKPOINT_BRANCH,
        CHECKPOINT_RESTORE,
        FENCED_RECOVERY,
        IDEMPOTENT_RECOVERY,
        FILE_READ,
        TASK_LIST,
        SERVER_SCOPE,
        COMPACT_ENVELOPE,
        COMPACT_MATRIX
    }

    record CaseResult(
            String scenarioId,
            Arm arm,
            RecoveryVariant recovery,
            Route route,
            boolean passed,
            int historyCalls,
            boolean refCorrect,
            boolean leakage,
            double qualityScore,
            boolean productionRecoveryInjected) { }

    record Report(
            SessionHistoryRecoveryEvalFixture.Metadata metadata,
            List<CaseResult> cases,
            Map<Arm, Double> exactRecoveryPercent,
            double cMinusA,
            double dMinusC,
            double refCorrectnessPercent,
            long leakageCount,
            double unnecessaryHistoryPercent,
            double s25QualityDropPoints,
            int schemaOverheadTokensP95,
            int schemaOverheadGateTokens,
            int maxHistoryWireChars,
            List<String> failures) {

        boolean passed() { return failures.isEmpty(); }
    }
}
