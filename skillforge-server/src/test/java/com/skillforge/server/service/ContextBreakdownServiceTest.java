package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.ContextProvider;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.view.SessionSkillResolver;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.dto.ContextBreakdownDto;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies the context breakdown follows the same skill exposure model as the
 * runtime request: no prompt-side skill list, one view-filtered Skill loader schema.
 */
@ExtendWith(MockitoExtension.class)
class ContextBreakdownServiceTest {

    @Mock private AgentService agentService;
    @Mock private SessionService sessionService;
    @Mock private SkillRegistry skillRegistry;
    @Mock private MemoryService memoryService;
    @Mock private SessionSkillResolver sessionSkillResolver;

    private ContextBreakdownService service;

    @BeforeEach
    void setUp() {
        service = new ContextBreakdownService(
                agentService, sessionService, skillRegistry, memoryService,
                new com.skillforge.core.context.GlobalSystemPromptProvider(),
                List.<ContextProvider>of(), new ObjectMapper(),
                sessionSkillResolver);
    }

    private SkillDefinition def(String name, boolean isSystem) {
        SkillDefinition d = new SkillDefinition();
        d.setName(name);
        d.setDescription("desc-" + name);
        d.setSystem(isSystem);
        return d;
    }

    @Test
    @DisplayName("renderSkillsList is empty because skills are exposed by the Skill loader tool")
    void renderSkillsList_returnsEmptyWhenSkillsMoveToLoaderTool() {
        AgentDefinition agentDef = new AgentDefinition();
        agentDef.setId("42");

        String block = service.renderSkillsListBlock(agentDef);

        assertThat(block).isEmpty();
    }

    @Test
    @DisplayName("breakdown counts visible skills as one Skill loader tool schema")
    void breakdown_countsSkillLoaderSchema() {
        SkillDefinition skillhub = def("skillhub", true);
        SkillDefinition mySkill = def("my-private", false);
        Map<String, SkillDefinition> allowed = new LinkedHashMap<>();
        allowed.put("skillhub", skillhub);
        allowed.put("my-private", mySkill);
        SessionSkillView view = new SessionSkillView(allowed,
                Set.of("skillhub"), Set.of("my-private"));

        AgentDefinition agentDef = new AgentDefinition();
        agentDef.setId("42");
        AgentEntity agentEntity = new AgentEntity();
        agentEntity.setId(42L);
        agentEntity.setModelId("gpt-4o");
        SessionEntity session = new SessionEntity();
        session.setId("s1");
        session.setAgentId(42L);

        when(agentService.getAgent(42L)).thenReturn(agentEntity);
        when(agentService.toAgentDefinition(agentEntity)).thenReturn(agentDef);
        when(sessionService.getContextMessages("s1")).thenReturn(List.of());
        when(sessionSkillResolver.resolveFor(agentDef)).thenReturn(view);

        ContextBreakdownDto breakdown = service.breakdown(session, 7L);

        ContextBreakdownDto.Segment tools = breakdown.segments().stream()
                .filter(s -> "tool_schemas".equals(s.key()))
                .findFirst()
                .orElseThrow();
        assertThat(tools.tokens())
                .as("one Skill loader schema should be counted even when no Java tools are registered")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("breakdown exposes the global system prompt under system_prompt with key 'global_system_prompt'")
    void breakdown_exposesGlobalSystemPromptSegment() {
        AgentDefinition agentDef = new AgentDefinition();
        agentDef.setId("42");
        AgentEntity agentEntity = new AgentEntity();
        agentEntity.setId(42L);
        agentEntity.setModelId("gpt-4o");
        SessionEntity session = new SessionEntity();
        session.setId("s1");
        session.setAgentId(42L);

        when(agentService.getAgent(42L)).thenReturn(agentEntity);
        when(agentService.toAgentDefinition(agentEntity)).thenReturn(agentDef);
        when(sessionService.getContextMessages("s1")).thenReturn(List.of());
        when(sessionSkillResolver.resolveFor(agentDef)).thenReturn(SessionSkillView.EMPTY);

        ContextBreakdownDto breakdown = service.breakdown(session, 7L);

        ContextBreakdownDto.Segment systemPrompt = breakdown.segments().stream()
                .filter(s -> "system_prompt".equals(s.key()))
                .findFirst()
                .orElseThrow();
        ContextBreakdownDto.Segment global = childByKey(systemPrompt, "global_system_prompt");
        assertThat(global.tokens())
                .as("the built-in global system prompt must be counted as a system_prompt child")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("renderSkillsList: empty view → empty block (does not fall back to registry)")
    void renderSkillsList_emptyView_returnsEmpty() {
        AgentDefinition agentDef = new AgentDefinition();
        String block = service.renderSkillsListBlock(agentDef);

        assertThat(block).isEmpty();
    }

    @Test
    @DisplayName("renderSkillsList: no resolver access needed because prompt-side skill list is gone")
    void renderSkillsList_doesNotResolveSkills() {
        AgentDefinition agentDef = new AgentDefinition();
        String block = service.renderSkillsListBlock(agentDef);

        assertThat(block)
                .as("resolver exception must NOT fall back to registry-wide list")
                .isEmpty();
    }

    // ───────────────────────── bucketMessages / tool_result_other ─────────────────────────

    /** Build an assistant Message with a single {@code tool_use} block. */
    private static Message assistantToolUse(String id, String toolName) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", toolName);
        block.put("input", Map.of());
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(List.of(block));
        return m;
    }

    /** Build a user Message with a single {@code tool_result} block. */
    private static Message userToolResult(String toolUseId, String resultText) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", resultText);
        Message m = new Message();
        m.setRole(Message.Role.USER);
        m.setContent(List.of(block));
        return m;
    }

    private ContextBreakdownDto.Segment runBreakdownOnMessages(List<Message> messages) {
        AgentDefinition agentDef = new AgentDefinition();
        agentDef.setId("42");
        AgentEntity agentEntity = new AgentEntity();
        agentEntity.setId(42L);
        agentEntity.setModelId("gpt-4o");
        SessionEntity session = new SessionEntity();
        session.setId("s1");
        session.setAgentId(42L);

        when(agentService.getAgent(42L)).thenReturn(agentEntity);
        when(agentService.toAgentDefinition(agentEntity)).thenReturn(agentDef);
        when(sessionService.getContextMessages("s1")).thenReturn(messages);

        ContextBreakdownDto breakdown = service.breakdown(session, 7L);
        return breakdown.segments().stream()
                .filter(s -> "messages".equals(s.key()))
                .findFirst()
                .orElseThrow();
    }

    private static ContextBreakdownDto.Segment childByKey(ContextBreakdownDto.Segment parent, String key) {
        return parent.children().stream()
                .filter(c -> key.equals(c.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing child " + key + " under " + parent.key()
                                + "; saw " + parent.children().stream().map(ContextBreakdownDto.Segment::key).toList()));
    }

    @Test
    @DisplayName("bucketMessages: multiple Read tool_results aggregate under one tool_result_Read child")
    void bucketMessages_multipleReadToolResults_aggregateUnderSameToolKey() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(assistantToolUse("u1", "Read"));
        msgs.add(userToolResult("u1", "file content one — moderate length string"));
        msgs.add(assistantToolUse("u2", "Read"));
        msgs.add(userToolResult("u2", "file content two — another moderate string"));

        ContextBreakdownDto.Segment messages = runBreakdownOnMessages(msgs);
        ContextBreakdownDto.Segment other = childByKey(messages, "tool_result_other");

        assertThat(other.children()).hasSize(1);
        assertThat(other.children().get(0).key()).isEqualTo("tool_result_Read");
        assertThat(other.children().get(0).label()).isEqualTo("Read results");
        assertThat(other.children().get(0).tokens())
                .as("two Read tool_results should sum, not appear as two segments")
                .isGreaterThan(0);
        assertThat(other.tokens())
                .as("parent tokens equal sum of children")
                .isEqualTo(other.children().get(0).tokens());
    }

    @Test
    @DisplayName("bucketMessages: distinct tools sort by tokens descending (largest first)")
    void bucketMessages_multipleDistinctTools_sortedByTokensDescending() {
        // Bash output deliberately largest, Read medium, Write smallest.
        String bashHuge = "x".repeat(2000);
        String readMid = "y".repeat(400);
        String writeSmall = "z".repeat(20);

        List<Message> msgs = List.of(
                assistantToolUse("u1", "Write"), userToolResult("u1", writeSmall),
                assistantToolUse("u2", "Read"),  userToolResult("u2", readMid),
                assistantToolUse("u3", "Bash"),  userToolResult("u3", bashHuge));

        ContextBreakdownDto.Segment messages = runBreakdownOnMessages(msgs);
        ContextBreakdownDto.Segment other = childByKey(messages, "tool_result_other");

        assertThat(other.children())
                .as("3 distinct tools → 3 children")
                .hasSize(3);
        // Largest first — matches debug intent "which tool is eating my context"
        List<String> keysInOrder = other.children().stream()
                .map(ContextBreakdownDto.Segment::key).toList();
        assertThat(keysInOrder).containsExactly(
                "tool_result_Bash", "tool_result_Read", "tool_result_Write");

        // Strict monotone decreasing
        long t0 = other.children().get(0).tokens();
        long t1 = other.children().get(1).tokens();
        long t2 = other.children().get(2).tokens();
        assertThat(t0).isGreaterThan(t1);
        assertThat(t1).isGreaterThan(t2);
        assertThat(other.tokens()).isEqualTo(t0 + t1 + t2);
    }

    @Test
    @DisplayName("bucketMessages: dangling tool_result (no matching tool_use) → tool_result_unknown child")
    void bucketMessages_danglingToolResult_classifiedAsUnknown() {
        // tool_result references a tool_use_id that's not present in the messages
        // list (e.g. compacted-out tool_use). Should land in 'unknown' bucket.
        List<Message> msgs = List.of(
                userToolResult("orphan-id", "result with no matching tool_use"));

        ContextBreakdownDto.Segment messages = runBreakdownOnMessages(msgs);
        ContextBreakdownDto.Segment other = childByKey(messages, "tool_result_other");

        assertThat(other.children()).hasSize(1);
        assertThat(other.children().get(0).key()).isEqualTo("tool_result_unknown");
        assertThat(other.children().get(0).tokens()).isGreaterThan(0);
    }

    @Test
    @DisplayName("bucketMessages: only memory tool_results → tool_result_other parent is empty (no children)")
    void bucketMessages_onlyMemoryToolResults_otherParentIsEmpty() {
        List<Message> msgs = List.of(
                assistantToolUse("u1", "memory_search"),
                userToolResult("u1", "memory hit one"),
                assistantToolUse("u2", "Memory"),
                userToolResult("u2", "memory hit two"));

        ContextBreakdownDto.Segment messages = runBreakdownOnMessages(msgs);
        ContextBreakdownDto.Segment other = childByKey(messages, "tool_result_other");
        ContextBreakdownDto.Segment memory = childByKey(messages, "tool_result_memory");

        assertThat(other.tokens())
                .as("only-memory case → 'Other tool results' parent tokens = 0")
                .isZero();
        assertThat(other.children())
                .as("only-memory case → no per-tool children under 'Other'")
                .isNullOrEmpty();
        assertThat(memory.tokens())
                .as("memory results accumulate in the dedicated memory bucket")
                .isGreaterThan(0);
    }
}
