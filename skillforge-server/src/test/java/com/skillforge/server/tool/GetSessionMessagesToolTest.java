package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.dto.SessionMessageDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetSessionMessagesToolTest {

    @Mock
    private SessionService sessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GetSessionMessagesTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetSessionMessagesTool(sessionService, objectMapper);
    }

    @Test
    void executeReturnsRecentMessagesWithToolCalls() throws Exception {
        List<SessionMessageDto> messages = List.of(
                new SessionMessageDto(0, "user", "hello", "NORMAL", Map.of()),
                new SessionMessageDto(1, "assistant", List.of(Map.of(
                        "type", "tool_use",
                        "id", "tool-1",
                        "name", "Grep",
                        "input", Map.of("pattern", "TODO")
                )), "NORMAL", Map.of()),
                new SessionMessageDto(2, "assistant", "x".repeat(1200), "NORMAL", Map.of())
        );
        when(sessionService.getSession("s1")).thenReturn(session("s1", 1L));
        when(sessionService.getFullHistoryDtos("s1")).thenReturn(messages);

        SkillResult result = tool.execute(Map.of(
                "limit", 2,
                "maxContentChars", 100
        ), new SkillContext(null, "s1", 1L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode json = objectMapper.readTree(result.getOutput());
        assertThat(json.path("totalMessages").asInt()).isEqualTo(3);
        assertThat(json.path("returnedMessages").asInt()).isEqualTo(2);
        assertThat(json.path("messages").get(0).path("seqNo").asLong()).isEqualTo(1);
        assertThat(json.path("messages").get(0).path("toolCalls").get(0).path("name").asText())
                .isEqualTo("Grep");
        assertThat(json.path("messages").get(1).path("content").asText()).hasSize(103);
    }

    @Test
    void rejectsOtherUsersSession() {
        when(sessionService.getSession("other")).thenReturn(session("other", 99L));

        SkillResult result = tool.execute(Map.of("sessionId", "other"), new SkillContext(null, "s1", 1L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not accessible");
    }

    @Test
    void requiresSessionWhenContextMissing() {
        SkillResult result = tool.execute(Map.of(), new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("sessionId is required");
    }

    private static SessionEntity session(String id, Long userId) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        session.setUserId(userId);
        return session;
    }
}
