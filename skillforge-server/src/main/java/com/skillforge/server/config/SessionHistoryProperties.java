package com.skillforge.server.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Rollout controls for current-session History recovery.
 *
 * <p>The master switch controls exposure and direct execution of the system History
 * tool pair. The search-index switch selects an implementation detail only; disabling
 * it must leave the authoritative bounded row scan available. Recovery evaluation is
 * evaluator-only and does not enable a production recovery attachment.
 */
@Validated
@ConfigurationProperties(prefix = "skillforge.session-history", ignoreUnknownFields = false)
public class SessionHistoryProperties {

    public static final int MAX_PROVIDER_WIRE_CHARS = 32_000;

    private boolean enabled = false;
    private boolean checkpointEnvelopeEnabled = false;
    private boolean searchIndexEnabled = false;

    @Min(1)
    @Max(MAX_PROVIDER_WIRE_CHARS)
    private int maxProviderWireChars = MAX_PROVIDER_WIRE_CHARS;

    private boolean recoveryEvalEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCheckpointEnvelopeEnabled() {
        return checkpointEnvelopeEnabled;
    }

    public void setCheckpointEnvelopeEnabled(boolean checkpointEnvelopeEnabled) {
        this.checkpointEnvelopeEnabled = checkpointEnvelopeEnabled;
    }

    public boolean isSearchIndexEnabled() {
        return searchIndexEnabled;
    }

    public void setSearchIndexEnabled(boolean searchIndexEnabled) {
        this.searchIndexEnabled = searchIndexEnabled;
    }

    public int getMaxProviderWireChars() {
        return maxProviderWireChars;
    }

    public void setMaxProviderWireChars(int maxProviderWireChars) {
        this.maxProviderWireChars = maxProviderWireChars;
    }

    public boolean isRecoveryEvalEnabled() {
        return recoveryEvalEnabled;
    }

    public void setRecoveryEvalEnabled(boolean recoveryEvalEnabled) {
        this.recoveryEvalEnabled = recoveryEvalEnabled;
    }

    /** The envelope never becomes effective while the History master is off. */
    public boolean isCheckpointEnvelopeEffective() {
        return enabled && checkpointEnvelopeEnabled;
    }
}
