package com.skillforge.server.runtime;

import com.skillforge.server.exception.MultimodalNoVisionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeFailureClassifierTest {

    private final RuntimeFailureClassifier classifier = new RuntimeFailureClassifier();

    @ParameterizedTest
    @MethodSource("providerHttpFailures")
    void classify_providerHttpStatus_usesStableSanitizedFact(
            int status, String expectedCode, boolean candidateRetryable) {
        RuntimeException providerFailure = new RuntimeException(
                "Claude API error: HTTP " + status + " - {\"secret\":\"sk-raw-provider-body\"}");

        RuntimeFailureFact fact = classifier.classify(
                new IllegalStateException("outer", providerFailure), safeBoundary());

        assertThat(fact.source()).isEqualTo("model_provider");
        assertThat(fact.code()).isEqualTo(expectedCode);
        assertThat(fact.retryable()).isEqualTo(candidateRetryable);
        assertThat(fact.sideEffects()).isEqualTo("none");
        assertThat(fact.sanitizedError())
                .doesNotContain("sk-raw-provider-body", "secret", "IllegalStateException");
    }

    static Stream<Arguments> providerHttpFailures() {
        return Stream.of(
                Arguments.of(401, "PROVIDER_UNAUTHORIZED", false),
                Arguments.of(403, "PROVIDER_FORBIDDEN", false),
                Arguments.of(429, "PROVIDER_RATE_LIMITED", true),
                Arguments.of(500, "PROVIDER_HTTP_500", true),
                Arguments.of(502, "PROVIDER_HTTP_502", true),
                Arguments.of(503, "PROVIDER_HTTP_503", true),
                Arguments.of(504, "PROVIDER_HTTP_504", true));
    }

    @ParameterizedTest
    @MethodSource("networkFailures")
    void classify_networkCauseChain_mapsStableCodes(
            Throwable cause, String expectedCode, boolean expectedRetryable) {
        RuntimeFailureFact fact = classifier.classify(
                new RuntimeException("wrapper", new IllegalStateException("nested", cause)),
                safeBoundary());

        assertThat(fact.source()).isEqualTo("network");
        assertThat(fact.code()).isEqualTo(expectedCode);
        assertThat(fact.retryable()).isEqualTo(expectedRetryable);
        assertThat(fact.sideEffects()).isEqualTo("none");
    }

    static Stream<Arguments> networkFailures() {
        return Stream.of(
                Arguments.of(new SocketTimeoutException("read timed out"), "NETWORK_TIMEOUT", true),
                Arguments.of(new UnknownHostException("internal-provider.example"), "NETWORK_DNS_FAILURE", true),
                Arguments.of(new ConnectException("connection refused"), "NETWORK_CONNECT_FAILED", true),
                Arguments.of(new SSLHandshakeException("certificate details"), "NETWORK_TLS_FAILED", false));
    }

    @Test
    void classify_retryCandidateWithStreamDelta_isFailClosed() {
        RuntimeFailureFact fact = classifier.classify(
                new SocketTimeoutException("after partial response"),
                new RuntimeFailureEvidence(true, false, true));

        assertThat(fact.retryable()).isFalse();
        assertThat(fact.sideEffects()).isEqualTo("possible");
    }

    @Test
    void classify_retryCandidateWithToolCall_isObservedAndNotRetryable() {
        RuntimeFailureFact fact = classifier.classify(
                new SocketTimeoutException("after tool"),
                new RuntimeFailureEvidence(false, true, true));

        assertThat(fact.retryable()).isFalse();
        assertThat(fact.sideEffects()).isEqualTo("observed");
    }

    @Test
    void classify_retryCandidateWithoutPersistedUserTail_isFailClosed() {
        RuntimeFailureFact fact = classifier.classify(
                new SocketTimeoutException("tail unknown"),
                new RuntimeFailureEvidence(false, false, false));

        assertThat(fact.retryable()).isFalse();
        assertThat(fact.sideEffects()).isEqualTo("possible");
    }

    @Test
    void classify_plainHttpText_isNotTreatedAsProviderStatus() {
        RuntimeFailureFact fact = classifier.classify(
                new RuntimeException("user content mentioned HTTP 429"), safeBoundary());

        assertThat(fact.source()).isEqualTo("harness");
        assertThat(fact.code()).isEqualTo("HARNESS_UNEXPECTED_FAILURE");
        assertThat(fact.retryable()).isFalse();
        assertThat(fact.sideEffects()).isEqualTo("possible");
        assertThat(fact.sanitizedError()).isEqualTo("Agent runtime failed.");
    }

    @Test
    void classify_exceptionThatQuotesProviderPattern_isNotTreatedAsProviderStatus() {
        RuntimeFailureFact fact = classifier.classify(
                new RuntimeException("user report: Claude API error: HTTP 429"), safeBoundary());

        assertThat(fact.source()).isEqualTo("harness");
        assertThat(fact.code()).isEqualTo("HARNESS_UNEXPECTED_FAILURE");
        assertThat(fact.retryable()).isFalse();
        assertThat(fact.sideEffects()).isEqualTo("possible");
    }

    @Test
    void classify_modelCapabilityFailure_isStableUserActionFact() {
        RuntimeFailureFact fact = classifier.classify(
                new MultimodalNoVisionException("text-only-model"), safeBoundary());

        assertThat(fact.source()).isEqualTo("user_action");
        assertThat(fact.code()).isEqualTo(MultimodalNoVisionException.CODE);
        assertThat(fact.retryable()).isFalse();
        assertThat(fact.sideEffects()).isEqualTo("none");
        assertThat(fact.sanitizedError()).doesNotContain("text-only-model");
    }

    @Test
    void explicitToolHookAndUserActionFactories_useStableSourcesAndFailClosed() {
        RuntimeFailureFact tool = classifier.toolFailure(
                "TOOL_EXECUTION_FAILED", "Tool execution failed.", true);
        RuntimeFailureFact hook = classifier.hookFailure(
                "LIFECYCLE_HOOK_ABORTED", "Lifecycle hook stopped the run.");
        RuntimeFailureFact userAction = classifier.userActionFailure(
                "USER_CANCELLED", "The run was cancelled.");
        RuntimeFailureFact harnessObserved = classifier.harnessFailure(
                "ACP_RUN_FAILED", "ACP agent run failed.", "observed");

        assertThat(tool).isEqualTo(new RuntimeFailureFact(
                "tool", "TOOL_EXECUTION_FAILED", false, "observed", "Tool execution failed."));
        assertThat(hook).isEqualTo(new RuntimeFailureFact(
                "harness", "LIFECYCLE_HOOK_ABORTED", false, "possible", "Lifecycle hook stopped the run."));
        assertThat(userAction).isEqualTo(new RuntimeFailureFact(
                "user_action", "USER_CANCELLED", false, "none", "The run was cancelled."));
        assertThat(harnessObserved).isEqualTo(new RuntimeFailureFact(
                "harness", "ACP_RUN_FAILED", false, "observed", "ACP agent run failed."));
    }

    @Test
    void classify_nullFailure_isUnknownAndFailClosed() {
        RuntimeFailureFact fact = classifier.classify(null, safeBoundary());

        assertThat(fact).isEqualTo(new RuntimeFailureFact(
                "unknown", "UNKNOWN_RUNTIME_FAILURE", false, "possible", "Unknown runtime failure."));
    }

    @Test
    void explicitPreExecutionHarnessSaturation_isRetryableWithNoSideEffects() {
        assertThat(classifier.retryableHarnessFailure(
                "EXECUTOR_BUSY", "The agent runtime is busy. Please retry."))
                .isEqualTo(new RuntimeFailureFact(
                        "harness", "EXECUTOR_BUSY", true, "none",
                        "The agent runtime is busy. Please retry."));
    }

    @Test
    void failureFact_rejectsUnknownEnumsOversizedFieldsAndUnsafeRetry() {
        assertThatThrownBy(() -> new RuntimeFailureFact(
                "other", "CODE", false, "possible", "safe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeFailureFact(
                "harness", "CODE", false, "maybe", "safe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeFailureFact(
                "harness", "X".repeat(65), false, "possible", "safe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeFailureFact(
                "harness", "CODE", false, "possible", "x".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeFailureFact(
                "harness", "CODE", true, "possible", "safe"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RuntimeFailureEvidence safeBoundary() {
        return new RuntimeFailureEvidence(false, false, true);
    }
}
