package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.dto.ScheduledTaskRequest;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.service.ScheduledTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("MobileScheduleController")
class MobileScheduleControllerTest {

    private ScheduledTaskService scheduledTaskService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        scheduledTaskService = mock(ScheduledTaskService.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(
                        new MobileScheduleController(scheduledTaskService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("GET schedules derives user from principal and returns mobile-safe fields")
    void listSchedules_derivesUserAndReducesPayload() throws Exception {
        ScheduledTaskEntity task = task(7L, 1L);
        task.setPromptTemplate("private full prompt");
        task.setChannelTarget("{\"channelType\":\"feishu\",\"channelId\":\"secret\"}");
        when(scheduledTaskService.listForUser(1L)).thenReturn(List.of(task));

        mvc.perform(get("/api/mobile/client/schedules")
                        .param("userId", "999")
                        .with(principal(1L, "schedule:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].name").value("Morning brief"))
                .andExpect(jsonPath("$[0].cronExpr").value("0 0 7 * * *"))
                .andExpect(jsonPath("$[0].timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$[0].promptPreview").value("private full prompt"))
                .andExpect(jsonPath("$[0].promptTemplate").doesNotExist())
                .andExpect(jsonPath("$[0].channelTarget").doesNotExist())
                .andExpect(jsonPath("$[0].creatorUserId").doesNotExist());

        verify(scheduledTaskService).listForUser(1L);
        verify(scheduledTaskService, never()).listForUser(999L);
    }

    @Test
    @DisplayName("GET schedules requires schedule:read")
    void listSchedules_requiresReadScope() throws Exception {
        mvc.perform(get("/api/mobile/client/schedules")
                        .with(principal(1L, "chat:read")))
                .andExpect(status().isForbidden());

        verify(scheduledTaskService, never()).listForUser(any());
    }

    @Test
    @DisplayName("GET schedule runs derives user and clamps history limit")
    void listRuns_derivesUserAndClampsLimit() throws Exception {
        ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
        run.setId(91L);
        run.setTaskId(7L);
        run.setTriggeredAt(Instant.parse("2026-07-11T01:00:00Z"));
        run.setFinishedAt(Instant.parse("2026-07-11T01:01:00Z"));
        run.setStatus(ScheduledTaskRunEntity.STATUS_SUCCESS);
        run.setTriggeredSessionId("session-7");
        when(scheduledTaskService.listRuns(7L, 1L, 50, 0)).thenReturn(List.of(run));

        mvc.perform(get("/api/mobile/client/schedules/7/runs")
                        .param("limit", "999")
                        .with(principal(1L, "schedule:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(91))
                .andExpect(jsonPath("$[0].sessionId").value("session-7"))
                .andExpect(jsonPath("$[0].status").value("success"));

        verify(scheduledTaskService).listRuns(7L, 1L, 50, 0);
    }

    @Test
    @DisplayName("POST trigger requires schedule:write and returns accepted")
    void trigger_requiresWriteScopeAndQueuesRun() throws Exception {
        when(scheduledTaskService.triggerNow(7L, 1L)).thenReturn(task(7L, 1L));

        mvc.perform(post("/api/mobile/client/schedules/7/trigger")
                        .with(principal(1L, "schedule:write")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(7))
                .andExpect(jsonPath("$.status").value("trigger_requested"));

        verify(scheduledTaskService).triggerNow(7L, 1L);
    }

    @Test
    @DisplayName("POST trigger rejects a device with read-only schedule scope")
    void trigger_rejectsReadOnlyScope() throws Exception {
        mvc.perform(post("/api/mobile/client/schedules/7/trigger")
                        .with(principal(1L, "schedule:read")))
                .andExpect(status().isForbidden());

        verify(scheduledTaskService, never()).triggerNow(any(), any());
    }

    @Test
    @DisplayName("PUT enabled only accepts the enabled mobile mutation")
    void updateEnabled_rejectsExtraFields() throws Exception {
        mvc.perform(put("/api/mobile/client/schedules/7/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"cronExpr\":\"* * * * * *\"}")
                        .with(principal(1L, "schedule:write")))
                .andExpect(status().isBadRequest());

        verify(scheduledTaskService, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("PUT enabled derives user and delegates a narrow patch")
    void updateEnabled_derivesUserAndUpdatesFlag() throws Exception {
        ScheduledTaskEntity updated = task(7L, 1L);
        updated.setEnabled(false);
        when(scheduledTaskService.update(any(), any(), any(ScheduledTaskRequest.class)))
                .thenReturn(updated);

        mvc.perform(put("/api/mobile/client/schedules/7/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}")
                        .with(principal(1L, "schedule:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(scheduledTaskService).update(any(), any(), any(ScheduledTaskRequest.class));
    }

    private static ScheduledTaskEntity task(Long id, Long creatorUserId) {
        ScheduledTaskEntity task = new ScheduledTaskEntity();
        task.setId(id);
        task.setName("Morning brief");
        task.setCreatorUserId(creatorUserId);
        task.setAgentId(3L);
        task.setCronExpr("0 0 7 * * *");
        task.setTimezone("Asia/Shanghai");
        task.setSessionMode(ScheduledTaskEntity.SESSION_MODE_NEW);
        task.setEnabled(true);
        task.setStatus(ScheduledTaskEntity.STATUS_IDLE);
        task.setNextFireAt(Instant.parse("2026-07-12T23:00:00Z"));
        return task;
    }

    private static RequestPostProcessor principal(Long userId, String... scopes) {
        return request -> {
            request.setAttribute(
                    MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE,
                    new MobileDevicePrincipal(UUID.randomUUID(), userId, "Youren iPhone", Set.of(scopes)));
            return request;
        };
    }
}
