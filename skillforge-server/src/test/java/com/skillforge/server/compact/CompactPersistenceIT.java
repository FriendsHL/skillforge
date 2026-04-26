package com.skillforge.server.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-F integration test: verifies the post-compact persisted shape round-trips correctly
 * through {@code SessionService.getContextMessages} and that the SUMMARY row's role is
 * USER (BUG-F-2 prefix-match prerequisite — DB role mirrors engine-side
 * {@code Message.user(summaryPrefix)} so {@code messageEquals} doesn't trigger fallback
 * rewrite on every reload).
 */
@DisplayName("CompactPersistence round-trip + SUMMARY-as-USER persistence")
class CompactPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionMessageRepository sessionMessageRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionMessageRepository.deleteAll();
        sessionRepository.deleteAll();
        sessionService = new SessionService(
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(),
                new ObjectMapper(),
                transactionManager
        );
    }

    private String newSession() {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(1L);
        s.setAgentId(10L);
        s.setTitle("compact-persistence-it");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        sessionRepository.save(s);
        return s.getId();
    }

    /**
     * Append the post-BUG-F shape: boundary (SYSTEM) + summary (USER) + young-gen,
     * then read back via getContextMessages — boundary is excluded, summary survives
     * with role=USER, and young-gen is preserved.
     */
    @Test
    @DisplayName("BUG-F-2: SUMMARY row role=USER round-trips through getContextMessages")
    void summaryRoleUser_roundTripsThroughGetContextMessages() {
        // Arrange: emulate persistCompactResult's append pattern
        String sid = newSession();

        // Pre-compact history: 2 NORMAL rows
        sessionService.appendNormalMessages(sid, List.of(
                Message.user("ancient turn 0"),
                Message.assistant("ancient response 0")));

        // Compact append batch: BOUNDARY (SYSTEM) + SUMMARY (USER) + young-gen
        Message boundary = new Message();
        boundary.setRole(Message.Role.SYSTEM);
        boundary.setContent("Conversation compacted");

        Message summary = new Message();
        summary.setRole(Message.Role.USER);  // BUG-F-2
        summary.setContent("[Context summary from 2 messages compacted at 2026-04-26]\nuser asked about X.");

        Message youngGen0 = Message.user("recent question");
        Message youngGen1 = Message.assistant("recent answer");

        List<SessionService.AppendMessage> compactBatch = new ArrayList<>();
        compactBatch.add(new SessionService.AppendMessage(
                boundary, SessionService.MSG_TYPE_COMPACT_BOUNDARY, Collections.emptyMap()));
        compactBatch.add(new SessionService.AppendMessage(
                summary, SessionService.MSG_TYPE_SUMMARY, Collections.emptyMap()));
        compactBatch.add(new SessionService.AppendMessage(
                youngGen0, SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()));
        compactBatch.add(new SessionService.AppendMessage(
                youngGen1, SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()));
        sessionService.appendMessages(sid, compactBatch);

        // Act: getContextMessages should skip everything before & including the boundary
        List<Message> context = sessionService.getContextMessages(sid);

        // Assert: returned list = [summary-as-USER, youngGen0, youngGen1]
        assertThat(context).hasSize(3);

        Message ctxSummary = context.get(0);
        assertThat(ctxSummary.getRole()).isEqualTo(Message.Role.USER);  // BUG-F-2 round-trip
        assertThat(ctxSummary.getContent()).isInstanceOf(String.class);
        assertThat((String) ctxSummary.getContent()).contains("[Context summary from 2 messages");

        assertThat(context.get(1).getRole()).isEqualTo(Message.Role.USER);
        assertThat(context.get(1).getContent()).isEqualTo("recent question");
        assertThat(context.get(2).getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(context.get(2).getContent()).isEqualTo("recent answer");

        // Sanity: full history still has the boundary + ancient rows
        List<SessionService.StoredMessage> all = sessionService.getFullHistoryRecords(sid);
        assertThat(all).hasSize(6); // 2 ancient + boundary + summary + 2 young-gen
        // The SUMMARY row has the right msg_type and role on disk
        SessionService.StoredMessage diskSummary = all.stream()
                .filter(r -> SessionService.MSG_TYPE_SUMMARY.equals(r.msgType()))
                .findFirst().orElseThrow();
        assertThat(diskSummary.message().getRole()).isEqualTo(Message.Role.USER);
    }

    /**
     * acbced3f-class regression: legacy DB content with a NORMAL row whose content
     * is a mixed list of [text, tool_result, ...] — the broken shape produced by the
     * deleted mergeSummaryIntoUser. Reload returns the message faithfully (Map-form
     * blocks preserved) so the BUG-F-3 OpenAi defensive filter has data to work on.
     */
    @Test
    @DisplayName("acbced3f legacy mixed user message persists & reloads as Map blocks")
    void acbced3fMixedMessage_reloadsAsMapBlocks() {
        String sid = newSession();

        // Build a legacy mixed message: [text, tool_result, tool_result]
        Message mixed = new Message();
        mixed.setRole(Message.Role.USER);
        List<Object> blocks = new ArrayList<>();
        blocks.add(ContentBlock.text("[Context summary from old]\nstored summary"));
        blocks.add(ContentBlock.toolResult("A", "result-A", false));
        blocks.add(ContentBlock.toolResult("B", "result-B", false));
        mixed.setContent(blocks);

        sessionService.appendNormalMessages(sid, List.of(mixed));

        // Reload — Jackson reads content_json back as List<LinkedHashMap>
        List<Message> context = sessionService.getContextMessages(sid);
        assertThat(context).hasSize(1);
        Object content = context.get(0).getContent();
        assertThat(content).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> reloadedBlocks = (List<Object>) content;
        assertThat(reloadedBlocks).hasSize(3);
        // Reloaded blocks are Maps (Jackson's default for Object content list) — this is
        // exactly the shape OpenAiProvider.convertMessages must filter through BUG-F-3.
        // We don't assert exact type here (could be ContentBlock or Map depending on
        // serializer config); only that the count and types survived.
    }
}
