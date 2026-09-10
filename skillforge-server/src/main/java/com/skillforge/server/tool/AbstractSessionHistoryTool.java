package com.skillforge.server.tool;

import com.skillforge.core.skill.PreWrappedLowTrustTool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.SystemResidentTool;
import com.skillforge.server.history.HistoryInputValidationException;
import com.skillforge.server.history.HistoryProtocolException;
import com.skillforge.server.history.SessionHistoryAvailabilityPolicy;
import com.skillforge.server.history.SessionHistoryErrorResponse;
import com.skillforge.server.history.SessionHistoryScopeFactory;
import com.skillforge.server.history.SessionHistoryUnavailableException;
import com.skillforge.server.history.SessionHistoryWireFormatter;

import java.util.Objects;
import java.util.Set;

/** Shared availability and closed-error mechanics for the inseparable History pair. */
abstract class AbstractSessionHistoryTool
        implements SystemResidentTool, PreWrappedLowTrustTool {

    static final String LEGACY_TOOL_NAME = "GetSessionMessages";

    protected final SessionHistoryAvailabilityPolicy availabilityPolicy;
    protected final SessionHistoryScopeFactory scopeFactory;
    protected final SessionHistoryWireFormatter wireFormatter;

    AbstractSessionHistoryTool(
            SessionHistoryAvailabilityPolicy availabilityPolicy,
            SessionHistoryScopeFactory scopeFactory,
            SessionHistoryWireFormatter wireFormatter) {
        this.availabilityPolicy = Objects.requireNonNull(
                availabilityPolicy, "availabilityPolicy");
        this.scopeFactory = Objects.requireNonNull(scopeFactory, "scopeFactory");
        this.wireFormatter = Objects.requireNonNull(wireFormatter, "wireFormatter");
    }

    @Override
    public final Status getSystemToolStatus(SkillContext context) {
        if (!availabilityPolicy.isMasterEnabled()) return Status.DISABLED;
        SessionHistoryAvailabilityPolicy.Decision decision = availabilityPolicy.decide(
                scopeFactory.readiness(context), true);
        return decision.directDispatchAllowed() ? Status.AVAILABLE : Status.UNAVAILABLE;
    }

    @Override
    public final Set<String> getSupersededToolNames() {
        return Set.of(LEGACY_TOOL_NAME);
    }

    @Override
    public final String getSystemToolGroup() {
        return "session-history";
    }

    @Override
    public final boolean isReadOnly() {
        return true;
    }

    protected final SkillResult disabled() {
        return error("HISTORY_DISABLED", "Current-Session History is disabled");
    }

    protected final SkillResult validationFailure(HistoryInputValidationException failure) {
        return SkillResult.validationError(wireFormatter.format(SessionHistoryErrorResponse.of(
                failure.getCode(), "Invalid History Tool input")));
    }

    protected final SkillResult unavailableFailure(SessionHistoryUnavailableException failure) {
        return error(failure.getCode(), failure.getMessage());
    }

    protected final SkillResult protocolFailure(HistoryProtocolException failure) {
        return error(failure.getCode(), failure.getMessage());
    }

    protected final SkillResult unavailableFailure() {
        return error("HISTORY_UNAVAILABLE", "History is unavailable for the current Session");
    }

    private SkillResult error(String code, String message) {
        return SkillResult.error(wireFormatter.format(SessionHistoryErrorResponse.of(code, message)));
    }
}
