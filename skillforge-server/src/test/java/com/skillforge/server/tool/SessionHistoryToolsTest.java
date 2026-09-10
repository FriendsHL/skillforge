package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.SystemResidentTool;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.history.CurrentSessionHistoryScope;
import com.skillforge.server.history.HistoryToolInputValidator;
import com.skillforge.server.history.SessionHistoryAvailabilityPolicy;
import com.skillforge.server.history.SessionHistoryReadResponse;
import com.skillforge.server.history.SessionHistoryScopeFactory;
import com.skillforge.server.history.SessionHistorySearchResponse;
import com.skillforge.server.history.SessionHistoryWireFormatter;
import com.skillforge.server.history.query.SessionHistoryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionHistoryToolsTest {

    private static final SessionHistoryAvailabilityPolicy.StoreReadiness READY =
            new SessionHistoryAvailabilityPolicy.StoreReadiness(true, true, true, true, false);

    private SessionHistoryProperties properties;
    private SessionHistoryScopeFactory scopeFactory;
    private SessionHistoryQueryService queryService;
    private SessionHistorySearchTool searchTool;
    private SessionHistoryReadTool readTool;

    @BeforeEach
    void setUp() {
        properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        scopeFactory = mock(SessionHistoryScopeFactory.class);
        queryService = mock(SessionHistoryQueryService.class);
        SessionHistoryAvailabilityPolicy availability =
                new SessionHistoryAvailabilityPolicy(properties);
        SessionHistoryWireFormatter formatter = new SessionHistoryWireFormatter(
                new ObjectMapper(), properties);
        HistoryToolInputValidator validator = new HistoryToolInputValidator();
        searchTool = new SessionHistorySearchTool(
                validator, queryService, formatter, availability, scopeFactory);
        readTool = new SessionHistoryReadTool(
                validator, queryService, formatter, availability, scopeFactory);
    }

    @Test
    void schemasAreClosedAndContainNoRuntimeIdentityOrFrontier() {
        for (ToolSchema schema : List.of(searchTool.getToolSchema(), readTool.getToolSchema())) {
            assertThat(schema.getInputSchema()).containsEntry("additionalProperties", false);
            assertThat(schema.getInputSchema().toString()).doesNotContain(
                    "sessionId", "userId", "historyEpoch",
                    "preIntentMaxMessageId", "preIntentMaxSeq");
        }
    }

    @Test
    void masterOnAndAuthoritativeStoreMakesBothSystemToolsAvailable() {
        SkillContext context = context("schema-call");
        when(scopeFactory.readiness(context)).thenReturn(READY);

        assertThat(searchTool.getSystemToolStatus(context))
                .isEqualTo(SystemResidentTool.Status.AVAILABLE);
        assertThat(readTool.getSystemToolStatus(context))
                .isEqualTo(SystemResidentTool.Status.AVAILABLE);
    }

    @Test
    void masterOffIsHiddenAndDirectInvocationFailsWithoutRepositoryAccess() {
        properties.setEnabled(false);
        SkillResult result = searchTool.execute(Map.of("query", "fact"), context("search-call"));

        assertThat(searchTool.getSystemToolStatus(context("schema")))
                .isEqualTo(SystemResidentTool.Status.DISABLED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("HISTORY_DISABLED");
        verifyNoInteractions(scopeFactory, queryService);
    }

    @Test
    void identityFieldIsRejectedByValidatorBeforeScopeRepositoryAccess() {
        SkillResult result = searchTool.execute(
                Map.of("query", "fact", "sessionId", "other-session"),
                context("search-call"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("IDENTITY_FIELD_FORBIDDEN");
        verifyNoInteractions(scopeFactory, queryService);
    }

    @Test
    void searchUsesTrustedScopeThenQueryServiceThenSingleWireFormatterBoundary() {
        SkillContext context = context("search-call");
        CurrentSessionHistoryScope scope = CurrentSessionHistoryScope.from(context, 7, 91, 40);
        when(scopeFactory.readiness(context)).thenReturn(READY);
        when(scopeFactory.resolve(context, "SessionHistorySearch")).thenReturn(scope);
        when(queryService.search(any(), any())).thenReturn(
                new SessionHistorySearchResponse(1, List.of(), null, true));

        SkillResult result = searchTool.execute(Map.of("query", "fact"), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(occurrences(result.getOutput(), "<context-data source=\"history\""))
                .isEqualTo(1);
        assertThat(result.getOutput()).doesNotContain("&lt;context-data");
        InOrder order = inOrder(scopeFactory, queryService);
        order.verify(scopeFactory).readiness(context);
        order.verify(scopeFactory).resolve(context, "SessionHistorySearch");
        order.verify(queryService).search(any(), any());
    }

    @Test
    void readUsesTrustedScopeAndExactReadQuery() {
        SkillContext context = context("read-call");
        CurrentSessionHistoryScope scope = CurrentSessionHistoryScope.from(context, 7, 91, 40);
        when(scopeFactory.readiness(context)).thenReturn(READY);
        when(scopeFactory.resolve(context, "SessionHistoryRead")).thenReturn(scope);
        when(queryService.read(any(), any())).thenReturn(
                new SessionHistoryReadResponse(1, List.of(), null, false));

        SkillResult result = readTool.execute(Map.of("tail", 5), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("&quot;events&quot;")
                .doesNotContain("sessionId", "userId");
        verify(queryService).read(any(), any());
    }

    @Test
    void authoritativeStoreFailureDeniesWithoutResolvingScopeOrQuerying() {
        SkillContext context = context("read-call");
        when(scopeFactory.readiness(context)).thenReturn(
                new SessionHistoryAvailabilityPolicy.StoreReadiness(
                        true, true, true, true, true));

        SkillResult result = readTool.execute(Map.of("tail", 5), context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("HISTORY_STORE_NOT_AUTHORITATIVE");
        verify(scopeFactory, never()).resolve(any(), any());
        verifyNoInteractions(queryService);
    }

    private static SkillContext context(String toolUseId) {
        SkillContext context = new SkillContext("/workspace", "session-1", 41L);
        context.setToolUseId(toolUseId);
        return context;
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
