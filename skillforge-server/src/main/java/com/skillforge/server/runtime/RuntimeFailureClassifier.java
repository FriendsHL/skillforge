package com.skillforge.server.runtime;

import com.skillforge.server.exception.MultimodalNoVisionException;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure classifier for stable runtime failure facts. */
public final class RuntimeFailureClassifier {

    private static final Pattern PROVIDER_HTTP = Pattern.compile(
            "(?i)^[\\p{Alnum}][\\p{Alnum}._ -]{0,63}\\s+API\\s+error\\s*:\\s*HTTP\\s+"
                    + "(401|403|429|500|502|503|504)\\b");

    public RuntimeFailureFact classify(Throwable failure, RuntimeFailureEvidence evidence) {
        RuntimeFailureEvidence boundary = evidence != null
                ? evidence : new RuntimeFailureEvidence(false, false, false);

        if (failure == null) {
            return uncertain("unknown", "UNKNOWN_RUNTIME_FAILURE",
                    "Unknown runtime failure.", boundary);
        }

        for (Throwable cause : causeChain(failure)) {
            if (cause instanceof MultimodalNoVisionException) {
                return atBoundary(base("user_action", MultimodalNoVisionException.CODE, false,
                        "The selected model does not support this input."), boundary);
            }
            if (cause instanceof SocketTimeoutException) {
                return atBoundary(base("network", "NETWORK_TIMEOUT", true,
                        "The model connection timed out."), boundary);
            }
            if (cause instanceof UnknownHostException) {
                return atBoundary(base("network", "NETWORK_DNS_FAILURE", true,
                        "The model host could not be reached."), boundary);
            }
            if (cause instanceof ConnectException) {
                return atBoundary(base("network", "NETWORK_CONNECT_FAILED", true,
                        "The model connection could not be established."), boundary);
            }
            if (cause instanceof SSLHandshakeException) {
                return atBoundary(base("network", "NETWORK_TLS_FAILED", false,
                        "The secure model connection failed."), boundary);
            }

            Matcher matcher = PROVIDER_HTTP.matcher(String.valueOf(cause.getMessage()));
            if (matcher.find()) {
                int status = Integer.parseInt(matcher.group(1));
                return atBoundary(providerHttp(status), boundary);
            }
        }

        return uncertain("harness", "HARNESS_UNEXPECTED_FAILURE",
                "Agent runtime failed.", boundary);
    }

    public RuntimeFailureFact toolFailure(String code, String sanitizedError, boolean observed) {
        return fact("tool", code, false, observed ? "observed" : "possible", sanitizedError);
    }

    public RuntimeFailureFact hookFailure(String code, String sanitizedError) {
        return fact("harness", code, false, "possible", sanitizedError);
    }

    public RuntimeFailureFact userActionFailure(String code, String sanitizedError) {
        return fact("user_action", code, false, "none", sanitizedError);
    }

    public RuntimeFailureFact harnessFailure(String code, String sanitizedError, String sideEffects) {
        if (!"none".equals(sideEffects) && !"possible".equals(sideEffects)
                && !"observed".equals(sideEffects)) {
            throw new IllegalArgumentException("Unsupported sideEffects: " + sideEffects);
        }
        return fact("harness", code, false, sideEffects, sanitizedError);
    }

    /** Known pre-execution Harness saturation with proven zero external side effects. */
    public RuntimeFailureFact retryableHarnessFailure(String code, String sanitizedError) {
        return fact("harness", code, true, "none", sanitizedError);
    }

    private static RuntimeFailureFact providerHttp(int status) {
        return switch (status) {
            case 401 -> base("model_provider", "PROVIDER_UNAUTHORIZED", false,
                    "Model provider authentication failed.");
            case 403 -> base("model_provider", "PROVIDER_FORBIDDEN", false,
                    "Model provider access was denied.");
            case 429 -> base("model_provider", "PROVIDER_RATE_LIMITED", true,
                    "The model provider is rate limited.");
            default -> base("model_provider", "PROVIDER_HTTP_" + status, true,
                    "The model provider is temporarily unavailable.");
        };
    }

    private static RuntimeFailureFact atBoundary(RuntimeFailureFact candidate,
                                                 RuntimeFailureEvidence evidence) {
        if (evidence.toolCallObserved()) {
            return fact(candidate.source(), candidate.code(), false, "observed",
                    candidate.sanitizedError());
        }
        if (evidence.providerStreamDeltaObserved() || !evidence.persistedTailIsUser()) {
            return fact(candidate.source(), candidate.code(), false, "possible",
                    candidate.sanitizedError());
        }
        return fact(candidate.source(), candidate.code(), candidate.retryable(), "none",
                candidate.sanitizedError());
    }

    private static RuntimeFailureFact uncertain(String source, String code,
                                                String sanitizedError,
                                                RuntimeFailureEvidence evidence) {
        return fact(source, code, false,
                evidence.toolCallObserved() ? "observed" : "possible", sanitizedError);
    }

    private static RuntimeFailureFact base(String source, String code, boolean retryable,
                                           String sanitizedError) {
        return fact(source, code, retryable, "none", sanitizedError);
    }

    private static RuntimeFailureFact fact(String source, String code, boolean retryable,
                                           String sideEffects, String sanitizedError) {
        return new RuntimeFailureFact(source, code, retryable, sideEffects, sanitizedError);
    }

    private static Iterable<Throwable> causeChain(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.List<Throwable> causes = new java.util.ArrayList<>();
        Throwable current = failure;
        while (current != null && causes.size() < 32 && seen.add(current)) {
            causes.add(current);
            current = current.getCause();
        }
        return causes;
    }
}
