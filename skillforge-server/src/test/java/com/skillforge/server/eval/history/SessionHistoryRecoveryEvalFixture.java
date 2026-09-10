package com.skillforge.server.eval.history;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Immutable, classpath-backed input for the offline Session History acceptance evaluator. */
record SessionHistoryRecoveryEvalFixture(
        Metadata metadata,
        List<Scenario> scenarios) {

    static SessionHistoryRecoveryEvalFixture load(ObjectMapper objectMapper) throws IOException {
        String resource = "/eval/session-history-recovery/s1-s25-v1.json";
        try (InputStream input = SessionHistoryRecoveryEvalFixture.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing evaluator fixture " + resource);
            return objectMapper.readValue(input, SessionHistoryRecoveryEvalFixture.class);
        }
    }

    record Metadata(
            String model,
            String provider,
            int contextWindowTokens,
            String promptVersion,
            String fixtureVersion,
            int projectionVersion) { }

    record Scenario(
            String id,
            SessionHistoryRecoveryEvalRunner.Route expectedPath,
            SessionHistoryRecoveryEvalRunner.Arm minimumSuccessfulArm,
            boolean exactRecoveryCase,
            boolean historyNeeded,
            boolean refRequired,
            List<String> assertions) { }
}
