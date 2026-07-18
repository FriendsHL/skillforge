package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.dto.SessionMessageDto;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.exception.RetryBusyException;
import com.skillforge.server.exception.SessionNotFoundException;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.PendingConfirmation;
import com.skillforge.server.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("MobileChatController")
class MobileChatControllerTest {

    private SessionService sessionService;
    private ChatService chatService;
    private AgentRepository agentRepository;
    private MobileAgentAccessService mobileAgentAccessService;
    private ChatAttachmentService chatAttachmentService;
    private LlmProperties llmProperties;
    private PendingConfirmationRegistry pendingConfirmationRegistry;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        chatService = mock(ChatService.class);
        agentRepository = mock(AgentRepository.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mobileAgentAccessService = new MobileAgentAccessService(
                agentRepository, objectMapper, new SkillRegistry(), 3L);
        chatAttachmentService = mock(ChatAttachmentService.class);
        llmProperties = mock(LlmProperties.class);
        pendingConfirmationRegistry = mock(PendingConfirmationRegistry.class);
        mvc = MockMvcBuilders.standaloneSetup(new MobileChatController(
                        sessionService, chatService, agentRepository, mobileAgentAccessService,
                        chatAttachmentService, llmProperties, pendingConfirmationRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("GET /api/mobile/client/sessions derives user from mobile principal")
    void listSessions_derivesUserFromMobilePrincipal() throws Exception {
        when(sessionService.listUserSessions(1L)).thenReturn(List.of(session("session-1", 1L, 3L)));

        mvc.perform(get("/api/mobile/client/sessions")
                        .param("userId", "999")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("session-1"))
                .andExpect(jsonPath("$[0].userId").value(1))
                .andExpect(jsonPath("$[0].totalInputTokens").doesNotExist());

        verify(sessionService).listUserSessions(1L);
        verify(sessionService, never()).listUserSessions(999L);
    }

    @Test
    @DisplayName("GET /api/mobile/client/sessions exposes runtime failure facts and server-computed retryability")
    void getSession_retryableFailure_returnsRuntimeFacts() throws Exception {
        SessionEntity failed = session("session-1", 1L, 3L);
        failed.setRuntimeStatus("error");
        failed.setRuntimeStep("retryable");
        failed.setRuntimeError("Model response timed out");
        failed.setRuntimeFailureSource("network");
        failed.setRuntimeFailureCode("NETWORK_TIMEOUT");
        failed.setRuntimeRetryable(true);
        failed.setRuntimeSideEffects("none");
        when(sessionService.getSession("session-1")).thenReturn(failed);

        mvc.perform(get("/api/mobile/client/sessions/session-1")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeStatus").value("error"))
                .andExpect(jsonPath("$.runtimeStep").value("retryable"))
                .andExpect(jsonPath("$.runtimeError").value("Model response timed out"))
                .andExpect(jsonPath("$.failureSource").value("network"))
                .andExpect(jsonPath("$.failureCode").value("NETWORK_TIMEOUT"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.sideEffects").value("none"));
    }

    @Test
    @DisplayName("GET session fails closed when an untrusted source carries a stale retryable flag")
    void getSession_untrustedRetryableSource_returnsRetryableFalse() throws Exception {
        SessionEntity failed = session("session-1", 1L, 3L);
        failed.setRuntimeStatus("error");
        failed.setRuntimeFailureSource("tool");
        failed.setRuntimeFailureCode("TOOL_FAILED");
        failed.setRuntimeRetryable(true);
        failed.setRuntimeSideEffects("none");
        when(sessionService.getSession("session-1")).thenReturn(failed);

        mvc.perform(get("/api/mobile/client/sessions/session-1")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureSource").value("tool"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    @DisplayName("GET /api/mobile/client/sessions does not make clients infer retryability from runtimeStep")
    void listSessions_nonErrorStatusWithRetryableStep_returnsRetryableFalse() throws Exception {
        SessionEntity running = session("session-1", 1L, 3L);
        running.setRuntimeStatus("running");
        running.setRuntimeStep("retryable");
        running.setRuntimeRetryable(false);
        when(sessionService.listUserSessions(1L)).thenReturn(List.of(running));

        mvc.perform(get("/api/mobile/client/sessions")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runtimeStep").value("retryable"))
                .andExpect(jsonPath("$[0].retryable").value(false));
    }

    @Test
    @DisplayName("POST /api/mobile/client/sessions ignores client userId")
    void createSession_ignoresClientUserId() throws Exception {
        when(agentRepository.findById(3L)).thenReturn(Optional.of(
                agent(3L, "Owned", 1L, false, "user", "active")));
        when(sessionService.createSession(1L, 3L)).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": 3, "userId": 999 }
                                """)
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("session-1"))
                .andExpect(jsonPath("$.userId").value(1));

        verify(sessionService).createSession(1L, 3L);
        verify(sessionService, never()).createSession(999L, 3L);
    }

    @Test
    @DisplayName("POST /api/mobile/client/sessions requires a mobile principal")
    void createSession_withoutPrincipal_returnsUnauthorized() throws Exception {
        mvc.perform(post("/api/mobile/client/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":3}"))
                .andExpect(status().isUnauthorized());

        verify(agentRepository, never()).findById(any());
        verify(sessionService, never()).createSession(any(), any());
    }

    @Test
    @DisplayName("POST /api/mobile/client/sessions requires chat:write")
    void createSession_withoutChatWriteScope_returnsForbidden() throws Exception {
        mvc.perform(post("/api/mobile/client/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":3}")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isForbidden());

        verify(agentRepository, never()).findById(any());
        verify(sessionService, never()).createSession(any(), any());
    }

    @Test
    @DisplayName("POST /api/mobile/client/sessions uses Main Assistant when agentId is omitted")
    void createSession_usesDefaultAgentWhenAgentIdOmitted() throws Exception {
        AgentEntity agent = agent(3L, "Main Assistant", null, true, "user", "active");
        when(agentRepository.findById(3L)).thenReturn(Optional.of(agent));
        when(sessionService.createSession(1L, 3L)).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(3));

        verify(sessionService).createSession(1L, 3L);
    }

    @Test
    @DisplayName("POST /api/mobile/client/sessions accepts a selectable public agent")
    void createSession_publicAgent_createsSession() throws Exception {
        when(agentRepository.findById(4L)).thenReturn(Optional.of(
                agent(4L, "Public", 2L, true, "user", "active")));
        when(sessionService.createSession(1L, 4L)).thenReturn(session("session-1", 1L, 4L));

        mvc.perform(post("/api/mobile/client/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":4}")
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(4));

        verify(sessionService).createSession(1L, 4L);
    }

    @Test
    @DisplayName("POST /api/mobile/client/sessions hides non-selectable explicit agents")
    void createSession_nonSelectableExplicitAgents_returnNotFound() throws Exception {
        List<AgentEntity> rejected = List.of(
                agent(20L, "Hidden", null, false, "user", "active"),
                agent(21L, "System", 1L, true, "system", "active"),
                agent(22L, "Inactive", 1L, true, "user", "inactive"),
                agent(23L, "Foreign", 2L, false, "user", "active"));

        for (AgentEntity candidate : rejected) {
            when(agentRepository.findById(candidate.getId())).thenReturn(Optional.of(candidate));

            mvc.perform(post("/api/mobile/client/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"agentId\":" + candidate.getId() + "}")
                            .with(principal(1L, "chat:write")))
                    .andExpect(status().isNotFound());
        }

        when(agentRepository.findById(24L)).thenReturn(Optional.empty());
        mvc.perform(post("/api/mobile/client/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":24}")
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isNotFound());

        verify(sessionService, never()).createSession(eq(1L), any());
    }

    @Test
    @DisplayName("POST /api/mobile/client/sessions hides a non-selectable default agent")
    void createSession_nonSelectableDefaultAgent_returnsNotFound() throws Exception {
        AgentEntity inactiveDefault = agent(3L, "Main Assistant", null, true, "user", "inactive");
        when(agentRepository.findById(3L)).thenReturn(Optional.of(inactiveDefault));

        mvc.perform(post("/api/mobile/client/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isNotFound());

        verify(sessionService, never()).createSession(any(), any());
    }

    @Test
    @DisplayName("GET /api/mobile/client/sessions/{id}/messages enforces session ownership")
    void getMessages_rejectsCrossUserSession() throws Exception {
        when(sessionService.getSession("session-2")).thenReturn(session("session-2", 2L, 3L));

        mvc.perform(get("/api/mobile/client/sessions/session-2/messages")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isForbidden());

        verify(sessionService, never()).getFullHistoryDtos("session-2");
    }

    @Test
    @DisplayName("GET /api/mobile/client/sessions/{id}/messages returns persisted history")
    void getMessages_returnsHistory() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        when(sessionService.getFullHistoryDtos("session-1")).thenReturn(List.of(
                new SessionMessageDto(
                        1L,
                        "assistant",
                        "Hello",
                        "normal",
                        "normal",
                        null,
                        null,
                        java.util.Map.of(),
                        "trace-1",
                        Instant.parse("2026-07-10T06:00:00Z"),
                        null)));

        mvc.perform(get("/api/mobile/client/sessions/session-1/messages")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seqNo").value(1))
                .andExpect(jsonPath("$[0].role").value("assistant"))
                .andExpect(jsonPath("$[0].content").value("Hello"));
    }

    @Test
    @DisplayName("GET messages maps a missing session to 404")
    void getMessages_missingSession_returnsNotFound() throws Exception {
        when(sessionService.getSession("missing"))
                .thenThrow(new SessionNotFoundException("missing"));

        mvc.perform(get("/api/mobile/client/sessions/missing/messages")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET messages does not hide database failures as 404")
    void getMessages_databaseFailure_remainsServerFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(sessionService.getSession("database-failure")).thenThrow(failure);

        assertThatThrownBy(() -> mvc.perform(get("/api/mobile/client/sessions/database-failure/messages")
                        .with(principal(1L, "chat:read"))))
                .hasCause(failure);
    }

    @Test
    @DisplayName("POST /api/mobile/client/sessions/{id}/messages sends as mobile principal user")
    void sendMessage_usesPrincipalUserId() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "message": "hello", "userId": 999 }
                                """)
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(chatService).chatAsync(eq("session-1"), eq("hello"), eq(1L), eq(List.of()));
    }

    @Test
    @DisplayName("POST /api/mobile/client/sessions/{id}/retry safely retries as the mobile principal")
    void retryFailedTurn_ownedSessionWithChatWrite_returnsAccepted() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-1/retry")
                        .param("userId", "999")
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(chatService).retryFailedTurnAsync("session-1");
    }

    @Test
    @DisplayName("POST retry requires a mobile principal")
    void retryFailedTurn_withoutPrincipal_returnsUnauthorized() throws Exception {
        mvc.perform(post("/api/mobile/client/sessions/session-1/retry"))
                .andExpect(status().isUnauthorized());

        verify(chatService, never()).retryFailedTurnAsync(any());
    }

    @Test
    @DisplayName("POST retry requires chat:write")
    void retryFailedTurn_withoutChatWrite_returnsForbidden() throws Exception {
        mvc.perform(post("/api/mobile/client/sessions/session-1/retry")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isForbidden());

        verify(chatService, never()).retryFailedTurnAsync(any());
    }

    @Test
    @DisplayName("POST retry rejects a session owned by another user")
    void retryFailedTurn_crossUserSession_returnsForbidden() throws Exception {
        when(sessionService.getSession("session-2")).thenReturn(session("session-2", 2L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-2/retry")
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isForbidden());

        verify(chatService, never()).retryFailedTurnAsync(any());
    }

    @Test
    @DisplayName("POST retry maps a non-retryable state to 409")
    void retryFailedTurn_nonRetryableState_returnsConflict() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        doThrow(new IllegalStateException(
                "session failure is not retryable: /Users/private/runtime secret-token"))
                .when(chatService).retryFailedTurnAsync("session-1");

        mvc.perform(post("/api/mobile/client/sessions/session-1/retry")
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETRY_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("This turn cannot be retried safely."))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("POST retry maps a loop still finishing to retryable 429")
    void retryFailedTurn_oldLoopStillFinishing_returnsTooManyRequests() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        doThrow(new RetryBusyException())
                .when(chatService).retryFailedTurnAsync("session-1");

        mvc.perform(post("/api/mobile/client/sessions/session-1/retry")
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RETRY_BUSY"))
                .andExpect(jsonPath("$.message").value("Server is busy. Please try again later."))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("POST retry maps executor saturation to retryable 429")
    void retryFailedTurn_rejectedExecutor_returnsTooManyRequests() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        doThrow(new RejectedExecutionException("queue full /Users/private/runtime"))
                .when(chatService).retryFailedTurnAsync("session-1");

        mvc.perform(post("/api/mobile/client/sessions/session-1/retry")
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RETRY_BUSY"))
                .andExpect(jsonPath("$.message").value("Server is busy. Please try again later."))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("POST answer derives user from principal and delegates to ChatService")
    void answerAsk_usesPrincipalUserId() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "askId": "ask-1", "answer": "yes", "userId": 999 }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(chatService).answerAsk("session-1", "ask-1", "yes", 1L);
        verify(chatService, never()).answerAsk("session-1", "ask-1", "yes", 999L);
    }

    @Test
    @DisplayName("POST answer requires confirmation:answer scope")
    void answerAsk_withoutRequiredScope_returnsForbidden() throws Exception {
        mvc.perform(post("/api/mobile/client/sessions/session-1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "askId": "ask-1", "answer": "yes" }
                                """)
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isForbidden());

        verify(chatService, never()).answerAsk(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST answer rejects cross-user session before delegation")
    void answerAsk_crossUserSession_returnsForbidden() throws Exception {
        when(sessionService.getSession("session-2")).thenReturn(session("session-2", 2L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-2/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "askId": "ask-1", "answer": "yes" }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isForbidden());

        verify(chatService, never()).answerAsk(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST answer rejects missing parameters")
    void answerAsk_missingParameter_returnsBadRequest() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "askId": "ask-1" }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST answer rejects blank control id and answer")
    void answerAsk_blankValues_returnsBadRequest() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "askId": " ", "answer": " " }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).answerAsk(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST answer maps unknown or expired ask to 410")
    void answerAsk_unknownAsk_returnsGone() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        doThrow(new IllegalArgumentException("missing control"))
                .when(chatService).answerAsk("session-1", "ask-1", "yes", 1L);

        mvc.perform(post("/api/mobile/client/sessions/session-1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "askId": "ask-1", "answer": "yes" }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("ask has expired or does not exist"));
    }

    @Test
    @DisplayName("POST answer maps state conflicts to 409")
    void answerAsk_stateConflict_returnsConflict() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        doThrow(new IllegalStateException("internal continuation detail"))
                .when(chatService).answerAsk("session-1", "ask-1", "yes", 1L);

        mvc.perform(post("/api/mobile/client/sessions/session-1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "askId": "ask-1", "answer": "yes" }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST confirmation delegates approved decision with principal user")
    void answerConfirmation_approved_usesPrincipalUserId() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "confirmationId": "confirmation-1", "decision": "approved", "userId": 999 }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isOk());

        verify(chatService).answerConfirmation("session-1", "confirmation-1", Decision.APPROVED, 1L);
    }

    @Test
    @DisplayName("POST confirmation accepts denied decision")
    void answerConfirmation_denied_delegatesDeniedDecision() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "confirmationId": "confirmation-1", "decision": "denied" }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isOk());

        verify(chatService).answerConfirmation("session-1", "confirmation-1", Decision.DENIED, 1L);
    }

    @Test
    @DisplayName("POST confirmation rejects decisions other than approved or denied")
    void answerConfirmation_invalidDecision_returnsBadRequest() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "confirmationId": "confirmation-1", "decision": "timeout" }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).answerConfirmation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST confirmation rejects a blank confirmation id")
    void answerConfirmation_blankId_returnsBadRequest() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));

        mvc.perform(post("/api/mobile/client/sessions/session-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "confirmationId": " ", "decision": "approved" }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).answerConfirmation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST confirmation maps unknown or expired confirmation to 410")
    void answerConfirmation_unknownConfirmation_returnsGone() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        doThrow(new IllegalArgumentException("unknown confirmation"))
                .when(chatService).answerConfirmation("session-1", "confirmation-1", Decision.APPROVED, 1L);

        mvc.perform(post("/api/mobile/client/sessions/session-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "confirmationId": "confirmation-1", "decision": "approved" }
                                """)
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("POST attachments rejects empty file")
    void uploadAttachment_emptyFile_returnsBadRequest() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        mvc.perform(multipart("/api/mobile/client/sessions/session-1/attachments")
                        .file(empty)
                        .with(principal(1L, "attachment:upload")))
                .andExpect(status().isBadRequest());

        verify(chatAttachmentService, never()).previewKind(any());
        verify(chatAttachmentService, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("POST attachments uploads as principal user and returns mobile DTO")
    void uploadAttachment_validFile_delegatesWithPrincipalUserId() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        when(chatAttachmentService.previewKind(any())).thenReturn("image");
        AgentEntity agent = new AgentEntity();
        agent.setId(3L);
        agent.setModelId("vision-model");
        when(agentRepository.findById(3L)).thenReturn(Optional.of(agent));
        when(llmProperties.supportsVision("vision-model")).thenReturn(true);
        ChatAttachmentEntity stored = attachment("att-1", "session-1", 1L);
        when(chatAttachmentService.upload(eq("session-1"), eq(1L), any())).thenReturn(stored);

        mvc.perform(multipart("/api/mobile/client/sessions/session-1/attachments")
                        .file(pngFile())
                        .param("userId", "999")
                        .with(principal(1L, "attachment:upload")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("att-1"))
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.kind").value("image"))
                .andExpect(jsonPath("$.mimeType").value("image/png"))
                .andExpect(jsonPath("$.filename").value("screen.png"))
                .andExpect(jsonPath("$.sizeBytes").value(3))
                .andExpect(jsonPath("$.pageCount").doesNotExist())
                .andExpect(jsonPath("$.status").value("uploaded"));

        verify(chatAttachmentService).upload(eq("session-1"), eq(1L), any());
        verify(chatAttachmentService, never()).upload(eq("session-1"), eq(999L), any());
    }

    @Test
    @DisplayName("POST attachments requires attachment:upload scope")
    void uploadAttachment_withoutRequiredScope_returnsForbidden() throws Exception {
        mvc.perform(multipart("/api/mobile/client/sessions/session-1/attachments")
                        .file(pngFile())
                        .with(principal(1L, "chat:write")))
                .andExpect(status().isForbidden());

        verify(chatAttachmentService, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("POST attachments rejects cross-user session before inspecting file")
    void uploadAttachment_crossUserSession_returnsForbidden() throws Exception {
        when(sessionService.getSession("session-2")).thenReturn(session("session-2", 2L, 3L));

        mvc.perform(multipart("/api/mobile/client/sessions/session-2/attachments")
                        .file(pngFile())
                        .with(principal(1L, "attachment:upload")))
                .andExpect(status().isForbidden());

        verify(chatAttachmentService, never()).previewKind(any());
        verify(chatAttachmentService, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("GET pending confirmations returns only the owned session snapshot")
    void getPendingConfirmations_returnsRegistrySnapshot() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        when(pendingConfirmationRegistry.pendingForSession("session-1")).thenReturn(List.of(
                new PendingConfirmation(
                        "confirmation-1", "session-1", "tool-1", "npm", "package",
                        "npm install package", null, 300)));

        mvc.perform(get("/api/mobile/client/sessions/session-1/pending-confirmations")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].confirmationId").value("confirmation-1"))
                .andExpect(jsonPath("$[0].title").value("确认执行 npm package"))
                .andExpect(jsonPath("$[0].commandPreview").value("npm install package"));
    }

    @Test
    @DisplayName("POST ask maps executor saturation to retryable 429")
    void answerAsk_rejectedExecutor_returnsTooManyRequests() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        doThrow(new RejectedExecutionException("queue full"))
                .when(chatService).answerAsk("session-1", "ask-1", "yes", 1L);

        mvc.perform(post("/api/mobile/client/sessions/session-1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"askId\":\"ask-1\",\"answer\":\"yes\"}")
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Server is busy, please try again later"));
    }

    @Test
    @DisplayName("POST confirmation maps executor saturation to retryable 429")
    void answerConfirmation_rejectedExecutor_returnsTooManyRequests() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(session("session-1", 1L, 3L));
        doThrow(new RejectedExecutionException("queue full"))
                .when(chatService).answerConfirmation(
                        "session-1", "confirmation-1", Decision.APPROVED, 1L);

        mvc.perform(post("/api/mobile/client/sessions/session-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationId\":\"confirmation-1\",\"decision\":\"approved\"}")
                        .with(principal(1L, "confirmation:answer")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Server is busy, please try again later"));
    }

    private static MockMultipartFile pngFile() {
        return new MockMultipartFile(
                "file",
                "screen.png",
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E});
    }

    private static ChatAttachmentEntity attachment(String id, String sessionId, Long userId) {
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId(id);
        attachment.setSessionId(sessionId);
        attachment.setUserId(userId);
        attachment.setKind("image");
        attachment.setMimeType("image/png");
        attachment.setFilename("screen.png");
        attachment.setSizeBytes(3L);
        attachment.setStatus("uploaded");
        return attachment;
    }

    private static RequestPostProcessor principal(Long userId, String... scopes) {
        return request -> {
            request.setAttribute(
                    MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE,
                    new MobileDevicePrincipal(UUID.randomUUID(), userId, "Youren iPhone", Set.of(scopes)));
            return request;
        };
    }

    private static SessionEntity session(String id, Long userId, Long agentId) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        session.setUserId(userId);
        session.setAgentId(agentId);
        session.setTitle("Mobile session");
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        session.setMessageCount(0);
        return session;
    }

    private static AgentEntity agent(Long id,
                                     String name,
                                     Long ownerId,
                                     boolean isPublic,
                                     String agentType,
                                     String status) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setName(name);
        agent.setOwnerId(ownerId);
        agent.setPublic(isPublic);
        agent.setAgentType(agentType);
        agent.setStatus(status);
        return agent;
    }
}
