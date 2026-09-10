package com.skillforge.server.tool;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.history.CurrentSessionHistoryScope;
import com.skillforge.server.history.HistoryInputValidationException;
import com.skillforge.server.history.HistoryProtocolException;
import com.skillforge.server.history.HistoryToolInputValidator;
import com.skillforge.server.history.SessionHistoryAvailabilityPolicy;
import com.skillforge.server.history.SessionHistoryScopeFactory;
import com.skillforge.server.history.SessionHistorySearchInput;
import com.skillforge.server.history.SessionHistoryToolSchemas;
import com.skillforge.server.history.SessionHistoryUnavailableException;
import com.skillforge.server.history.SessionHistoryWireFormatter;
import com.skillforge.server.history.query.SessionHistoryQueryService;

import java.util.Map;
import java.util.Objects;

/** System-resident current-Session History locator Tool. */
public final class SessionHistorySearchTool extends AbstractSessionHistoryTool {

    private final HistoryToolInputValidator inputValidator;
    private final SessionHistoryQueryService queryService;

    public SessionHistorySearchTool(
            HistoryToolInputValidator inputValidator,
            SessionHistoryQueryService queryService,
            SessionHistoryWireFormatter wireFormatter,
            SessionHistoryAvailabilityPolicy availabilityPolicy,
            SessionHistoryScopeFactory scopeFactory) {
        super(availabilityPolicy, scopeFactory, wireFormatter);
        this.inputValidator = Objects.requireNonNull(inputValidator, "inputValidator");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
    }

    @Override public String getName() {
        return SessionHistoryToolSchemas.SEARCH_NAME;
    }

    @Override public String getDescription() {
        return getToolSchema().getDescription();
    }

    @Override public ToolSchema getToolSchema() {
        return SessionHistoryToolSchemas.search();
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (!availabilityPolicy.isMasterEnabled()) return disabled();
        try {
            // The closed validator intentionally runs before any scope/repository access.
            SessionHistorySearchInput request = inputValidator.validateSearchInput(input);
            SessionHistoryAvailabilityPolicy.StoreReadiness readiness =
                    scopeFactory.readiness(context);
            availabilityPolicy.requireDirectDispatch(getName(), readiness);
            CurrentSessionHistoryScope scope = scopeFactory.resolve(context, getName());
            return SkillResult.success(wireFormatter.format(queryService.search(scope, request)));
        } catch (HistoryInputValidationException invalidInput) {
            return validationFailure(invalidInput);
        } catch (SessionHistoryUnavailableException unavailable) {
            return unavailableFailure(unavailable);
        } catch (HistoryProtocolException protocolFailure) {
            return protocolFailure(protocolFailure);
        } catch (RuntimeException failClosed) {
            return unavailableFailure();
        }
    }
}
